package com.extv.player.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.delay
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Résolution des adresses (DNS) résistante aux blocages des fournisseurs d'accès.
 *
 * Les box Orange / SFR / Free / Bouygues bloquent certains serveurs IPTV par leur DNS : la TV reçoit
 * « adresse introuvable » (Unable to resolve host) ou une page web de blocage à la place des données
 * (erreur JSON « line 1 column 10 » = « <!DOCTYPE »). Les autres applis passent car elles utilisent
 * un DNS public. EXTV demande donc d'abord l'adresse à Cloudflare puis Google en DNS chiffré (HTTPS),
 * que la box ne peut pas modifier, et se rabat sur le DNS de la TV si ceux-ci ne répondent pas.
 */
object SafeDns : Dns {
    private const val CACHE_MS = 10 * 60_000L
    private const val DOH_DOWN_MS = 5 * 60_000L

    private class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    /** Serveurs que le DNS de la TV ne trouve pas ou détourne : VLC doit les joindre par leur adresse IP. */
    private val blockedBySystem = ConcurrentHashMap<String, InetAddress>()
    private val checker = Executors.newSingleThreadExecutor { r -> Thread(r, "extv-dns-check").apply { isDaemon = true } }
    @Volatile private var dohDownUntil = 0L

    private val bootstrap: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val providers: List<DnsOverHttps> by lazy {
        listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
            doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112"),
        )
    }

    private fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder()
        .client(bootstrap)
        .url(url.toHttpUrlOrNull()!!)
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .includeIPv6(false)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname) || hostname == "localhost") return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.at < CACHE_MS) return it.addresses }

        val fromDoh = if (now >= dohDownUntil) lookupDoh(hostname) else null
        if (fromDoh != null) {
            cache[hostname] = Entry(fromDoh, now)
            checker.execute { checkSystem(hostname, fromDoh) }
            return fromDoh
        }
        // DNS chiffré injoignable (réseau qui le filtre) : DNS de la TV, avec un second essai.
        val system = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            Thread.sleep(1_000)
            Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname] = Entry(system, now)
        return system
    }

    private fun lookupDoh(hostname: String): List<InetAddress>? {
        var reachable = false
        for (p in providers) {
            try {
                val r = p.lookup(hostname)
                if (r.isNotEmpty()) return r
                reachable = true
            } catch (e: UnknownHostException) {
                // Réponse « n'existe pas » d'un fournisseur joignable, ou fournisseur injoignable.
                if (e.cause == null) reachable = true
            } catch (e: Exception) {
            }
        }
        if (!reachable) dohDownUntil = System.currentTimeMillis() + DOH_DOWN_MS
        return null
    }

    /** Compare avec le DNS de la TV : s'il échoue ou donne une autre adresse (page de blocage), on le note. */
    private fun checkSystem(hostname: String, good: List<InetAddress>) {
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
        val ok = system != null && system.any { s -> good.any { it.hostAddress == s.hostAddress } } &&
            system.none { it.isLoopbackAddress || it.isAnyLocalAddress || it.isSiteLocalAddress }
        if (ok) blockedBySystem.remove(hostname)
        else good.firstOrNull { it is Inet4Address }?.let { blockedBySystem[hostname] = it }
    }

    /**
     * Adresse à donner à VLC, qui utilise le DNS de la TV : si celui-ci bloque le serveur, le nom est
     * remplacé par l'adresse IP obtenue en DNS chiffré. Sans réseau (appel depuis l'écran) : uniquement
     * d'après ce qui est déjà connu.
     */
    fun urlForSystemResolver(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val ip = blockedBySystem[parsed.host] ?: return url
        return parsed.newBuilder().host(ip.hostAddress ?: return url).build().toString()
    }

    private fun isIpLiteral(host: String) = host.contains(':') || host.all { it.isDigit() || it == '.' }
}

object Net {
    /** Attend (au plus [maxMs]) qu'un réseau soit connecté : au démarrage de la TV, EXTV peut être lancée avant. */
    suspend fun awaitNetwork(context: Context, maxMs: Long = 20_000) {
        val cm = runCatching { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }.getOrNull() ?: return
        val end = System.currentTimeMillis() + maxMs
        while (!isConnected(cm) && System.currentTimeMillis() < end) delay(500)
    }

    @Suppress("DEPRECATION")
    private fun isConnected(cm: ConnectivityManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            cm.activeNetworkInfo?.isConnected == true
        }
    }.getOrDefault(true)

    private fun retryable(e: Throwable): Boolean = e is IOException && e !is HttpStatusException

    /**
     * Requête réseau avec nouveaux essais automatiques (1 s, 2 s, 4 s, 8 s) sur une coupure, un DNS qui
     * ne répond pas encore ou une page web reçue à la place des données.
     */
    suspend fun <T> withRetry(context: Context?, attempts: Int = 5, block: suspend () -> T): T {
        var wait = 1_000L
        var last: Throwable? = null
        repeat(attempts) { i ->
            if (context != null) awaitNetwork(context, if (i == 0) 20_000 else 5_000)
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || !retryable(e)) throw e
                last = e
                if (i < attempts - 1) {
                    delay(wait)
                    wait *= 2
                }
            }
        }
        throw last!!
    }

    /** Message compréhensible (en français, sans adresse de serveur) pour une erreur de chargement. */
    fun friendlyMessage(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.firstOrNull { it is IOException || it is JSONException } ?: e
        return when (root) {
            is UnknownHostException ->
                "Le serveur est introuvable. Vérifiez que la TV est connectée à Internet, puis réessayez."
            is ServerPageException ->
                "Le serveur a renvoyé une page web au lieu de la liste. Il est peut-être en maintenance : réessayez dans quelques minutes."
            is HttpStatusException -> when (root.code) {
                401, 403 -> "Accès refusé par le serveur. Vérifiez votre abonnement ou vos identifiants."
                in 500..599 -> "Le serveur rencontre un problème (erreur ${root.code}). Réessayez dans quelques minutes."
                else -> "Le serveur a répondu par une erreur (${root.code}). Réessayez."
            }
            is SocketTimeoutException -> "Le serveur met trop de temps à répondre. Réessayez."
            is ConnectException, is NoRouteToHostException -> "Impossible de joindre le serveur. Vérifiez la connexion Internet, puis réessayez."
            is SSLException -> "La connexion sécurisée au serveur a échoué. Réessayez."
            is JSONException, is IllegalStateException ->
                if (root.message?.contains("JSON", true) == true || root is JSONException)
                    "Le serveur a envoyé une réponse illisible. Réessayez dans quelques minutes."
                else root.message ?: "Erreur inattendue. Réessayez."
            is IOException ->
                if (root.javaClass.simpleName == "MalformedJsonException") "Le serveur a envoyé une réponse illisible. Réessayez dans quelques minutes."
                else "La connexion au serveur a été interrompue. Réessayez."
            else -> root.message?.takeUnless { it.contains("http", true) || it.contains("://") } ?: "Erreur inattendue. Réessayez."
        }
    }
}

/** Réponse HTTP en erreur (pas de nouvel essai automatique : le serveur a bien répondu). */
class HttpStatusException(val code: Int) : IOException("Le serveur a répondu HTTP $code")

/** Le serveur (ou la box) a renvoyé une page HTML au lieu des données JSON attendues. */
class ServerPageException : IOException("Page web reçue à la place des données")
