package com.extv.player.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Catégorie « FR| TNT » de la TV en direct : les chaînes de la TNT française dans l'ordre des numéros
 * (numérotation du 6 juin 2025), puis Canal+ (qui a quitté la TNT mais que l'abonnement propose).
 *
 * Chaque chaîne est prise dans l'abonnement (meilleure qualité, guide des programmes). Quand la chaîne
 * diffuse elle-même un direct gratuit et officiel, il sert de flux de secours si celui de l'abonnement
 * échoue ou se fige, et remplace la chaîne si l'abonnement ne l'a pas. Sans direct officiel, le secours
 * est une autre version de la même chaîne dans l'abonnement (ex. France 2 HD → France 2 SD).
 */
object FrenchTnt {
    const val CATEGORY_NAME = "FR| TNT"

    private class Entry(val num: Int, val name: String, val keys: List<String>, val official: String? = null)

    private val CHANNELS = listOf(
        Entry(1, "TF1", listOf("tf1")),
        Entry(2, "France 2", listOf("france2")),
        Entry(3, "France 3", listOf("france3", "france3national", "france3nationale", "france3paris", "france3parisidf")),
        Entry(4, "France 4", listOf("france4")),
        Entry(5, "France 5", listOf("france5")),
        Entry(6, "M6", listOf("m6")),
        Entry(7, "Arte", listOf("arte", "artefr", "artefrance"), "https://artesimulcast.akamaized.net/hls/live/2031003/artelive_fr/index.m3u8"),
        Entry(8, "LCP", listOf("lcp", "lcpan", "lcpassembleenationale", "lcp100", "lcp24", "lcppublicsenat")),
        Entry(8, "Public Sénat", listOf("publicsenat", "publicsenat24"), "dailymotion:xkxbzc"),
        Entry(9, "W9", listOf("w9")),
        Entry(10, "TMC", listOf("tmc")),
        Entry(11, "TFX", listOf("tfx")),
        Entry(12, "Gulli", listOf("gulli")),
        Entry(13, "BFMTV", listOf("bfmtv", "bfm"), "https://live-cdn-stream-euw1.bfmtv.bct.nextradiotv.com/master.m3u8"),
        Entry(14, "CNews", listOf("cnews"), "dailymotion:x3b68jn"),
        Entry(15, "LCI", listOf("lci")),
        Entry(16, "franceinfo", listOf("franceinfo", "franceinfotv")),
        Entry(17, "CStar", listOf("cstar")),
        Entry(18, "T18", listOf("t18"), "dailymotion:x9jhhyc"),
        Entry(19, "Novo19", listOf("novo19", "novo")),
        Entry(20, "TF1 Séries Films", listOf("tf1seriesfilms", "tf1seriefilms", "tf1sf")),
        Entry(21, "L'Équipe", listOf("lequipe", "lequipetv", "lequipe21"),
            "https://dq37unyetkpcz.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-m04j89j7k5gtp/LEquipe_FR.m3u8"),
        Entry(22, "6ter", listOf("6ter")),
        Entry(23, "RMC Story", listOf("rmcstory"),
            "https://d15aro46bnpfm8.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-fqkqiax1078up/RMC_Story_FR.m3u8"),
        Entry(24, "RMC Découverte", listOf("rmcdecouverte"),
            "https://d16zzycxcd0m0r.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-hixvx5kymecr9/RMC_Decouverte_FR.m3u8"),
        Entry(25, "Chérie 25", listOf("cherie25")),
        Entry(26, "Canal+", listOf("canalplus")),
    )

    /** Flux officiel de secours, par identifiant de chaîne de l'abonnement. */
    private val backups = ConcurrentHashMap<String, String>()

    fun backupFor(channelId: String): String? = backups[channelId]

    private val QUALITY = setOf(
        "fhd", "hd", "sd", "uhd", "4k", "8k", "hevc", "h265", "h264", "x265", "1080", "1080p", "720", "720p", "576p",
        "2160p", "50fps", "60fps", "fps", "hdr", "raw", "vip", "backup", "multi", "fr", "fra", "direct", "live",
    )
    private val MARKS = Regex("\\p{M}+")
    private val PREFIX = Regex("^\\s*(fr|fra|fre|france|frhd|vip fr)\\s*[|:\\-]\\s*")
    private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")

    private fun base(raw: String): String {
        var s = MARKS.replace(Normalizer.normalize(raw, Normalizer.Form.NFKD), "").lowercase()
        s = PREFIX.replace(s, "")
        s = s.substringAfter('|').ifBlank { s }
        return BRACKETS.replace(s, " ").replace("+", " plus ")
    }

    private fun tokens(raw: String) = base(raw).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Nom réduit à l'essentiel : « FR| France 2 FHD ⁽ᴴᴰ⁾ » → « france2 ». */
    fun key(raw: String): String = tokens(raw).filter { it !in QUALITY }.joinToString("")

    /** Préférence entre plusieurs versions d'une même chaîne : catégorie française, HD plutôt que 4K. */
    private fun score(ch: LiveChannel, categoryName: String): Int {
        val cat = categoryName.normalized()
        val t = tokens(ch.name).toSet()
        var s = 0
        if (cat.startsWith("fr") || cat.contains("france")) s += 10
        if (cat.contains("generaliste") || cat.contains("tnt")) s += 10
        if (cat.contains("region") || cat.contains("local")) s -= 4
        if ("fhd" in t || "hd" in t || "1080p" in t) s += 3
        if ("sd" in t) s += 1
        if ("4k" in t || "uhd" in t || "2160p" in t || "8k" in t) s -= 6
        if ("hevc" in t || "h265" in t || "x265" in t) s -= 3
        if ("backup" in t) s -= 2
        return s
    }

    /** Chaînes de la catégorie, construites à partir des chaînes (visibles) de l'abonnement. */
    fun channels(all: List<LiveChannel>, categoryNames: Map<String, String>): List<LiveChannel> {
        val byKey = HashMap<String, MutableList<LiveChannel>>()
        all.forEach { ch -> byKey.getOrPut(key(ch.name)) { mutableListOf() } += ch }
        return CHANNELS.mapNotNull { e ->
            val ranked = e.keys.flatMap { byKey[it].orEmpty() }
                .sortedByDescending { score(it, categoryNames[it.categoryId].orEmpty()) }
            val sub = ranked.firstOrNull()
            when {
                sub != null -> {
                    // Secours : le direct officiel, sinon une autre version de la chaîne dans l'abonnement.
                    val backup = e.official ?: ranked.drop(1).firstOrNull { it.streamUrl != sub.streamUrl }?.streamUrl
                    if (backup != null) backups[sub.id] = backup else backups.remove(sub.id)
                    sub
                }
                e.official != null -> LiveChannel(
                    id = "tnt:${e.num}:${e.name}",
                    name = e.name,
                    logo = null,
                    categoryId = Category.TNT,
                    epgId = null,
                    streamUrl = e.official,
                    hasArchive = false,
                    archiveDays = 0,
                    num = e.num,
                )
                else -> null
            }
        }
    }

    fun isOwn(channel: LiveChannel) = channel.id.startsWith("tnt:")

    /**
     * Adresse lisible d'un flux officiel. Les directs Dailymotion demandent une adresse signée, obtenue
     * auprès de Dailymotion à chaque lecture. À appeler hors du fil principal ; null en cas d'échec.
     */
    fun resolve(url: String): String? {
        if (!url.startsWith("dailymotion:")) return url
        val id = url.removePrefix("dailymotion:")
        return runCatching {
            val c = URL("https://www.dailymotion.com/player/metadata/video/$id").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).getJSONObject("qualities").getJSONArray("auto").getJSONObject(0).getString("url")
        }.getOrNull()
    }
}
