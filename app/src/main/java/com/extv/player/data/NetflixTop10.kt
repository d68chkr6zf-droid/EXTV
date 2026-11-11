package com.extv.player.data

import com.extv.player.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Top 10 Netflix France de la semaine, d'après le fichier public publié chaque semaine par Netflix
 * (top10.netflix.com). Netflix n'y donne que les titres anglais : le titre français de chaque film ou
 * série est cherché une fois sur Wikidata (gratuit, sans clé), pour retrouver le titre dans le catalogue.
 * Le résultat est gardé dans un fichier et rechargé au plus une fois par jour (304 si rien n'a changé).
 */
object NetflixTop10 {
    enum class Kind { FILMS, TV }

    /** Une place du classement : rang, titre Netflix (anglais), titres français et année trouvés. */
    class Entry(val kind: Kind, val rank: Int, val title: String, val altTitles: List<String>, val year: Int?) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("rank", rank).put("title", title)
            .put("alt", JSONArray(altTitles)).put("year", year ?: 0)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                Kind.valueOf(o.getString("kind")),
                o.getInt("rank"),
                o.getString("title"),
                o.optJSONArray("alt")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                o.optInt("year").takeIf { it > 0 },
            )
        }
    }

    private const val COUNTRY = "FR"
    private val SOURCES = listOf(
        "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv",
        "https://top10.netflix.com/data/all-weeks-countries.tsv",
    )
    private const val REFRESH_MS = 6L * 3600 * 1000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private val mutex = Mutex()
    @Volatile private var memory: List<Entry>? = null

    private val cacheFile get() = File(App.instance.filesDir, "netflix_top10.json")

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: kotlinx.coroutines.Deferred<List<Entry>>? = null

    /** Lance le chargement en arrière-plan (au démarrage de l'appli) pour qu'il soit prêt à l'ouverture de Films / Séries. */
    fun prefetch() {
        runCatching { start() }
    }

    @Synchronized
    private fun start(): kotlinx.coroutines.Deferred<List<Entry>> {
        job?.let { if (!it.isCompleted || memory != null) return it }
        return scope.async { runCatching { load() }.getOrDefault(emptyList()) }.also { job = it }
    }

    /**
     * Classement de la semaine (vide si indisponible). Ne lève pas d'erreur. Le chargement continue en
     * arrière-plan même si l'écran n'attend plus (il sera prêt à la prochaine ouverture).
     */
    suspend fun entries(): List<Entry> = start().await()

    private suspend fun load(): List<Entry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cached = runCatching { JSONObject(cacheFile.readText()) }.getOrNull()
            val cachedEntries = cached?.optJSONArray("entries")?.let { a ->
                (0 until a.length()).mapNotNull { runCatching { Entry.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }
            val fresh = cached != null && System.currentTimeMillis() - cached.optLong("checked") < REFRESH_MS
            memory?.let { if (fresh) return@withContext it }
            if (fresh && !cachedEntries.isNullOrEmpty()) return@withContext cachedEntries.also { memory = it }
            // 1) Classement préparé chaque jour par GitHub Actions (petit fichier, titres français déjà cherchés).
            // 2) Sinon, directement depuis Netflix + Wikidata.
            val updated = runCatching { prepared() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { download(cached) }.getOrNull()?.takeIf { it.isNotEmpty() }
            val result = updated ?: cachedEntries.orEmpty()
            if (result.isNotEmpty()) memory = result
            result
        }
    }

    /** top10.json publié à côté de l'APK (voir scripts/top10.py). */
    private fun prepared(): List<Entry>? {
        val url = com.extv.player.BuildConfig.UPDATE_URL + "top10.json?t=${System.currentTimeMillis() / 600_000}"
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body!!.string())
            val a = o.getJSONArray("entries")
            val entries = (0 until a.length()).mapNotNull { runCatching { Entry.fromJson(a.getJSONObject(it)) }.getOrNull() }
            if (entries.isEmpty()) return null
            val json = JSONObject().put("checked", System.currentTimeMillis()).put("source", "github")
                .put("entries", JSONArray(entries.map { it.toJson() }))
            cacheFile.writeText(json.toString())
            return entries
        }
    }

    /** Télécharge le fichier si besoin ; null en cas d'échec (on garde alors l'ancien classement). */
    private suspend fun download(cached: JSONObject?): List<Entry>? {
        val etag = cached?.optString("etag").orEmpty()
        val lastModified = cached?.optString("lastModified").orEmpty()
        for (url in SOURCES) {
            val builder = Request.Builder().url(url).header("User-Agent", "EXTV/1.0 (Android TV)")
            if (cached?.has("entries") == true) {
                if (etag.isNotEmpty()) builder.header("If-None-Match", etag)
                if (lastModified.isNotEmpty()) builder.header("If-Modified-Since", lastModified)
            }
            val response = runCatching { client.newCall(builder.build()).execute() }.getOrNull() ?: continue
            response.use { r ->
                if (r.code == 304 && cached != null) {
                    cached.put("checked", System.currentTimeMillis())
                    cacheFile.writeText(cached.toString())
                    val a = cached.getJSONArray("entries")
                    return (0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) }
                }
                if (!r.isSuccessful) return@use
                val rows = parse(r.body!!.charStream().buffered())
                if (rows.isEmpty()) return@use
                val entries = translate(rows)
                val json = JSONObject()
                    .put("checked", System.currentTimeMillis())
                    .put("etag", r.header("ETag").orEmpty())
                    .put("lastModified", r.header("Last-Modified").orEmpty())
                    .put("entries", JSONArray(entries.map { it.toJson() }))
                cacheFile.writeText(json.toString())
                return entries
            }
        }
        return null
    }

    private class Row(val kind: Kind, val rank: Int, val title: String)

    /** Garde les lignes « France » de la semaine la plus récente (le fichier est lu en continu). */
    private fun parse(reader: java.io.BufferedReader): List<Row> {
        val header = reader.readLine()?.split('\t') ?: return emptyList()
        val iCountry = header.indexOf("country_iso2")
        val iWeek = header.indexOf("week")
        val iCategory = header.indexOf("category")
        val iRank = header.indexOf("weekly_rank")
        val iTitle = header.indexOf("show_title")
        if (listOf(iCountry, iWeek, iCategory, iRank, iTitle).any { it < 0 }) return emptyList()
        var bestWeek = ""
        val rows = ArrayList<Row>()
        val marker = "\t$COUNTRY\t"
        reader.lineSequence().forEach { line ->
            // Tri rapide : la plupart des lignes concernent d'autres pays.
            if (!line.contains(marker)) return@forEach
            val f = line.split('\t')
            if (f.size <= maxOf(iCountry, iWeek, iCategory, iRank, iTitle)) return@forEach
            if (f[iCountry] != COUNTRY) return@forEach
            val week = f[iWeek]
            if (week < bestWeek) return@forEach
            if (week > bestWeek) {
                bestWeek = week
                rows.clear()
            }
            val kind = if (f[iCategory].startsWith("Film", ignoreCase = true)) Kind.FILMS else Kind.TV
            val rank = f[iRank].toIntOrNull() ?: return@forEach
            rows += Row(kind, rank, f[iTitle].trim())
        }
        return rows.sortedWith(compareBy({ it.kind }, { it.rank }))
    }

    // --- Titres français (Wikidata) -----------------------------------------------------------------

    private const val WIKIDATA = "https://www.wikidata.org/w/api.php"
    private val FILM_WORDS = Regex("film|movie|documentary|animated|special", RegexOption.IGNORE_CASE)
    private val TV_WORDS = Regex("series|show|sitcom|anime|docuseries|telenovela|miniseries", RegexOption.IGNORE_CASE)
    private val FILM_OK = Regex("\\b(19|20)\\d{2}\\b|\\bby\\b|directed", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
    private val NOT_A_WORK = Regex("genre|type of|disambiguation|wikimedia|concept|term\\b|category|topic|list of|style of", RegexOption.IGNORE_CASE)

    private fun getJson(url: okhttp3.HttpUrl): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", "EXTV/1.0 (Android TV app)").build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body!!.string()) else null
        }
    }.getOrNull()

    private suspend fun translate(rows: List<Row>): List<Entry> = coroutineScope {
        // 1) Pour chaque titre : l'élément Wikidata qui correspond (film ou série), et son année.
        val found = rows.map { row ->
            async(Dispatchers.IO) {
                val url = WIKIDATA.toHttpUrl().newBuilder()
                    .addQueryParameter("action", "wbsearchentities")
                    .addQueryParameter("search", row.title)
                    .addQueryParameter("language", "en")
                    .addQueryParameter("type", "item")
                    .addQueryParameter("limit", "8")
                    .addQueryParameter("format", "json")
                    .build()
                val results = getJson(url)?.optJSONArray("search") ?: return@async null
                val words = if (row.kind == Kind.FILMS) FILM_WORDS else TV_WORDS
                (0 until results.length()).map { results.getJSONObject(it) }
                    .firstOrNull { r ->
                        val d = r.optString("description")
                        words.containsMatchIn(d) && !NOT_A_WORK.containsMatchIn(d) && (row.kind == Kind.TV || FILM_OK.containsMatchIn(d))
                    }
                    ?.let { it.getString("id") to YEAR.find(it.optString("description"))?.value?.toIntOrNull() }
            }
        }.awaitAll()
        // 2) Titres français (libellé, autres noms, titre de l'article Wikipédia) en une seule requête.
        val ids = found.mapNotNull { it?.first }.distinct()
        val french = HashMap<String, List<String>>()
        ids.chunked(40).forEach { chunk ->
            val url = WIKIDATA.toHttpUrl().newBuilder()
                .addQueryParameter("action", "wbgetentities")
                .addQueryParameter("ids", chunk.joinToString("|"))
                .addQueryParameter("props", "labels|aliases|sitelinks")
                .addQueryParameter("languages", "fr")
                .addQueryParameter("sitefilter", "frwiki")
                .addQueryParameter("format", "json")
                .build()
            val entities = withContext(Dispatchers.IO) { getJson(url) }?.optJSONObject("entities") ?: return@forEach
            chunk.forEach { id ->
                val e = entities.optJSONObject(id) ?: return@forEach
                val names = buildList {
                    e.optJSONObject("labels")?.optJSONObject("fr")?.optString("value")?.let(::add)
                    e.optJSONObject("sitelinks")?.optJSONObject("frwiki")?.optString("title")
                        ?.replace(Regex("\\s*\\(.*\\)$"), "")?.let(::add)
                    e.optJSONObject("aliases")?.optJSONArray("fr")?.let { a ->
                        (0 until minOf(a.length(), 6)).forEach { add(a.getJSONObject(it).optString("value")) }
                    }
                }.filter { it.isNotBlank() }.distinct()
                french[id] = names
            }
        }
        rows.mapIndexed { i, row ->
            val f = found[i]
            Entry(row.kind, row.rank, row.title, f?.first?.let { french[it] }.orEmpty(), f?.second)
        }
    }

    // --- Rapprochement avec le catalogue --------------------------------------------------------------

    private val TAGS = setOf(
        "fr", "vf", "vff", "vfq", "vo", "vost", "vostfr", "en", "us", "uk", "multi", "4k", "uhd", "fhd", "hd", "sd",
        "hdr", "hevc", "h265", "x265", "1080p", "720p", "2160p", "nf", "netflix", "ca", "qc", "be", "ch", "truefrench", "french",
    )
    private val BRACKETS = Regex("[\\[(][^\\])]*[\\])]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val ARTICLES = setOf("the", "le", "la", "les", "l", "a", "an")

    /** Titre réduit à l'essentiel : sans accents, étiquettes (FR, 4K, VOSTFR…), année ni ponctuation. */
    fun titleKey(raw: String): String {
        val words = BRACKETS.replace(raw.normalized(), " ").replace("&", " and ")
            .split(NON_ALNUM).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first() in TAGS) words.removeAt(0)
        while (words.isNotEmpty() && (words.last() in TAGS || YEAR.matches(words.last()))) words.removeAt(words.lastIndex)
        while (words.size > 1 && words.first() in ARTICLES) words.removeAt(0)
        return words.filterNot { it == "and" || it == "et" }.joinToString(" ")
    }

    private val SEGMENTS = Regex("\\s+[-–|]\\s+|\\s*:\\s+")

    /**
     * Clés d'un titre : le titre entier, puis chaque partie autour de « : » ou « - »
     * (ex. « The Rookie : Le Flic de Los Angeles » se retrouve aussi par « Le Flic de Los Angeles »).
     */
    fun titleKeys(raw: String): List<String> {
        val parts = raw.split(SEGMENTS)
        val keys = listOf(titleKey(raw)) + if (parts.size > 1) parts.map(::titleKey).filter { it.length >= 6 } else emptyList()
        return keys.filter { it.isNotEmpty() }.distinct()
    }

    /** Année indiquée dans un nom du catalogue, ex. « Alerte rouge (2021) ». */
    fun yearIn(raw: String): Int? = YEAR.findAll(raw).lastOrNull()?.value?.toIntOrNull()

    /**
     * Places du classement retrouvées dans le catalogue, dans l'ordre du rang (chaque titre une seule fois).
     * year : année connue de l'élément du catalogue (ou null).
     */
    fun <T> match(kind: Kind, entries: List<Entry>, catalog: List<T>, name: (T) -> String, year: (T) -> Int?): List<Pair<Int, T>> {
        val byKey = HashMap<String, MutableList<T>>()
        catalog.forEach { item ->
            titleKeys(name(item)).forEach { k -> byKey.getOrPut(k) { ArrayList(1) } += item }
        }
        val used = HashSet<T>()
        // Un même titre peut revenir plusieurs fois (une ligne par saison) : on garde son meilleur rang.
        return entries.filter { it.kind == kind }.sortedBy { it.rank }.distinctBy { titleKey(it.title) }.mapNotNull { e ->
            // Titres entiers d'abord, puis leurs parties.
            val names = listOf(e.title) + e.altTitles
            val keys = (names.map(::titleKey) + names.flatMap(::titleKeys)).filter { it.isNotEmpty() }.distinct()
            // L'année (souvent celle d'un homonyme plus ancien sur Wikidata) départage seulement, sans exclure.
            val candidates = keys.flatMap { byKey[it].orEmpty() }.distinct().filter { it !in used }
            val best = candidates.firstOrNull { e.year != null && year(it) == e.year } ?: candidates.firstOrNull()
            best?.let { used += it; e.rank to it }
        }
    }
}
