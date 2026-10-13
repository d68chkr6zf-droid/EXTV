package com.extv.player.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client de l'API « player_api.php » (Xtream Codes), utilisée par la plupart des fournisseurs IPTV.
 * Les catalogues sont chargés une fois puis gardés en mémoire (pour la navigation et la recherche).
 */
class XtreamRepository(
    private val profile: Profile,
    private val http: OkHttpClient,
    private val store: LocalStore,
) : BaseRepository() {

    private val base: String = normalizeServer(profile.server)
    private val user = profile.username
    private val pass = profile.password

    private val mutex = Mutex()
    private val categoryCache = HashMap<ContentType, List<Category>>()
    private var liveCache: List<LiveChannel>? = null
    private var movieCache: List<Movie>? = null
    private var seriesCache: List<Series>? = null

    // --- Appels réseau -----------------------------------------------------------------------

    private suspend fun api(action: String?, vararg params: Pair<String, String>): Any = withContext(Dispatchers.IO) {
        val url = "$base/player_api.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .apply {
                if (action != null) addQueryParameter("action", action)
                params.forEach { addQueryParameter(it.first, it.second) }
            }
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Le serveur a répondu HTTP ${r.code}")
            r.body?.string().orEmpty().trim()
        }
        if (body.isEmpty()) JSONArray() else JSONTokener(body).nextValue()
    }

    private suspend fun apiArray(action: String, vararg params: Pair<String, String>): JSONArray =
        api(action, *params) as? JSONArray ?: JSONArray()

    /**
     * Grandes listes (chaînes, films, séries) : lecture en flux, sans charger toute la réponse en mémoire.
     * Seuls les champs simples (texte, nombre) sont gardés ; les tableaux et objets imbriqués sont ignorés.
     */
    private suspend fun <T> apiList(action: String, map: (Map<String, String>, Int) -> T): List<T> = withContext(Dispatchers.IO) {
        val url = "$base/player_api.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", action)
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Le serveur a répondu HTTP ${r.code}")
            val body = r.body ?: return@use emptyList()
            val out = ArrayList<T>()
            JsonReader(body.charStream()).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    return@use
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    val fields = HashMap<String, String>(16)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        when (reader.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString().trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it }
                            JsonToken.BOOLEAN -> fields[key] = if (reader.nextBoolean()) "1" else "0"
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    out += map(fields, out.size)
                }
                reader.endArray()
            }
            out
        }
    }

    // --- Catalogue ---------------------------------------------------------------------------

    override suspend fun categories(type: ContentType): List<Category> = mutex.withLock {
        categoryCache[type] ?: run {
            val action = when (type) {
                ContentType.LIVE -> "get_live_categories"
                ContentType.MOVIE -> "get_vod_categories"
                ContentType.SERIES -> "get_series_categories"
            }
            apiArray(action).objects().map {
                Category(it.str("category_id") ?: "", it.str("category_name") ?: "?", type)
            }.also { categoryCache[type] = it }
        }
    }

    override suspend fun liveChannels(categoryId: String?): List<LiveChannel> {
        val all = mutex.withLock {
            liveCache ?: apiList("get_live_streams") { o, i ->
                val id = o["stream_id"] ?: ""
                LiveChannel(
                    id = id,
                    name = o["name"] ?: "Chaîne $id",
                    logo = o["stream_icon"],
                    categoryId = o["category_id"] ?: "",
                    epgId = o["epg_channel_id"],
                    streamUrl = "$base/live/$user/$pass/$id.${store.liveFormat}",
                    hasArchive = o["tv_archive"] == "1",
                    archiveDays = o["tv_archive_duration"]?.toIntOrNull() ?: 0,
                    num = o["num"]?.toIntOrNull() ?: (i + 1),
                )
            }.also {
                liveCache = it
                loadTimes[ContentType.LIVE] = System.currentTimeMillis()
            }
        }
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    override suspend fun movies(categoryId: String?): List<Movie> {
        val all = mutex.withLock {
            movieCache ?: apiList("get_vod_streams") { o, _ ->
                val id = o["stream_id"] ?: ""
                val ext = o["container_extension"] ?: "mp4"
                Movie(
                    id = id,
                    name = o["name"] ?: "Film $id",
                    poster = o["stream_icon"],
                    categoryId = o["category_id"] ?: "",
                    streamUrl = "$base/movie/$user/$pass/$id.$ext",
                    rating = o["rating"]?.takeIf { it != "0" },
                    added = (o["added"]?.toLongOrNull() ?: 0) * 1000,
                )
            }.also {
                movieCache = it
                loadTimes[ContentType.MOVIE] = System.currentTimeMillis()
            }
        }
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    override suspend fun series(categoryId: String?): List<Series> {
        val all = mutex.withLock {
            seriesCache ?: apiList("get_series") { o, _ ->
                Series(
                    id = o["series_id"] ?: "",
                    name = o["name"] ?: "?",
                    cover = o["cover"],
                    categoryId = o["category_id"] ?: "",
                    plot = o["plot"],
                    rating = o["rating"]?.takeIf { it != "0" },
                    added = (o["last_modified"]?.toLongOrNull() ?: 0) * 1000,
                )
            }.also {
                seriesCache = it
                loadTimes[ContentType.SERIES] = System.currentTimeMillis()
            }
        }
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    override suspend fun movieDetail(movie: Movie): Movie {
        val o = api("get_vod_info", "vod_id" to movie.id) as? JSONObject ?: return movie
        val info = o.optJSONObject("info") ?: return movie
        return movie.copy(
            plot = info.str("plot") ?: info.str("description") ?: movie.plot,
            rating = info.str("rating")?.takeIf { it != "0" } ?: movie.rating,
            genre = info.str("genre"),
            duration = info.str("duration"),
            year = info.str("releasedate")?.take(4),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            poster = info.str("movie_image") ?: info.str("cover_big") ?: movie.poster,
        )
    }

    override suspend fun seriesDetail(series: Series): SeriesDetail {
        val o = api("get_series_info", "series_id" to series.id) as? JSONObject
            ?: return SeriesDetail(series, emptyMap())
        val info = o.optJSONObject("info")
        val s = series.copy(
            plot = info?.str("plot") ?: series.plot,
            cover = info?.str("cover") ?: series.cover,
            rating = info?.str("rating")?.takeIf { it != "0" } ?: series.rating,
        )
        // "episodes" est un objet { "1": [..], "2": [..] } chez la plupart des serveurs, parfois un tableau.
        val raw = mutableListOf<JSONObject>()
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.objects()?.let(raw::addAll) }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val item = eps.opt(i)) {
                    is JSONArray -> raw += item.objects()
                    is JSONObject -> raw += item
                }
            }
        }
        val episodes = raw.map { e ->
            val id = e.str("id") ?: ""
            val ext = e.str("container_extension") ?: "mp4"
            val epInfo = e.optJSONObject("info")
            Episode(
                id = id,
                title = e.str("title") ?: "Épisode",
                season = e.str("season")?.toIntOrNull() ?: 1,
                episode = e.str("episode_num")?.toIntOrNull() ?: 0,
                streamUrl = "$base/series/$user/$pass/$id.$ext",
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image"),
            )
        }
        val seasons = episodes.groupBy { it.season }.toSortedMap().mapValues { (_, v) -> v.sortedBy { it.episode } }
        return SeriesDetail(s, seasons)
    }

    // --- Guide TV et rattrapage --------------------------------------------------------------

    private fun parseListings(o: Any): List<Pair<EpgProgram, Boolean>> {
        val listings = (o as? JSONObject)?.optJSONArray("epg_listings") ?: return emptyList()
        return listings.objects().mapNotNull { e ->
            val start = e.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = e.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            EpgProgram(
                title = b64(e.str("title")),
                description = b64(e.str("description")),
                start = start * 1000,
                end = end * 1000,
                startRaw = e.str("start"),
            ) to (e.str("has_archive") == "1")
        }
    }

    override suspend fun epg(channel: LiveChannel, limit: Int): List<EpgProgram> {
        val now = System.currentTimeMillis()
        return parseListings(api("get_short_epg", "stream_id" to channel.id, "limit" to "$limit"))
            .map { it.first }.filter { it.end > now }.sortedBy { it.start }
            .dedupePrograms()
    }

    override suspend fun catchup(channel: LiveChannel): List<EpgProgram> {
        val now = System.currentTimeMillis()
        val since = now - (channel.archiveDays.coerceAtLeast(1)) * DAY_MS
        return parseListings(api("get_simple_data_table", "stream_id" to channel.id))
            .filter { (p, archived) -> p.end < now && (archived || p.start > since) }
            .map { it.first }
            .sortedBy { it.start }
            .dedupePrograms()
            .reversed()
    }

    override fun catchupUrl(channel: LiveChannel, program: EpgProgram): String = catchupUrlAt(channel, program, 0)

    override fun catchupUrlAt(channel: LiveChannel, program: EpgProgram, offsetMs: Long): String {
        // Le serveur travaille à la minute : on démarre à la minute entière la plus proche.
        val offsetMin = (offsetMs / 60_000).coerceAtLeast(0)
        val minutes = ((program.end - program.start) / 60_000 - offsetMin).coerceAtLeast(1)
        // Le serveur attend son heure locale au format yyyy-MM-dd:HH-mm.
        val out = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        val start = program.startRaw?.takeIf { it.length >= 16 }?.let { raw ->
            // Heure du serveur sans fuseau : calcul en UTC pour seulement ajouter des minutes.
            val utc = java.util.TimeZone.getTimeZone("UTC")
            val parsed = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = utc }.parse(raw.substring(0, 10) + " " + raw.substring(11, 16))
            }.getOrNull() ?: return@let null
            out.timeZone = utc
            out.format(Date(parsed.time + offsetMin * 60_000))
        } ?: out.format(Date(program.start + offsetMin * 60_000))
        return "$base/timeshift/$user/$pass/$minutes/$start/${channel.id}.ts"
    }

    override suspend fun refresh(type: ContentType) {
        mutex.withLock {
            categoryCache.remove(type)
            loadTimes.remove(type)
            when (type) {
                ContentType.LIVE -> liveCache = null
                ContentType.MOVIE -> movieCache = null
                ContentType.SERIES -> seriesCache = null
            }
            Unit
        }
    }

    override suspend fun accountInfo(): AccountInfo? {
        val o = api(null) as? JSONObject ?: return null
        val ui = o.optJSONObject("user_info") ?: return null
        return AccountInfo(
            authenticated = ui.str("auth") == "1",
            status = ui.str("status"),
            expDate = ui.str("exp_date")?.toLongOrNull()?.times(1000),
            activeConnections = ui.str("active_cons")?.toIntOrNull(),
            maxConnections = ui.str("max_connections")?.toIntOrNull(),
        )
    }

    companion object {
        private const val DAY_MS = 24L * 3600 * 1000

        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: throw IllegalArgumentException("Adresse du serveur invalide")
            val defaultPort = (url.scheme == "http" && url.port == 80) || (url.scheme == "https" && url.port == 443)
            return if (defaultPort) "${url.scheme}://${url.host}" else "${url.scheme}://${url.host}:${url.port}"
        }

        private fun b64(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                s
            }
        }
    }
}

/** Certains guides contiennent le même programme deux fois, décalé d'une minute : on n'en garde qu'un. */
private fun List<EpgProgram>.dedupePrograms(): List<EpgProgram> {
    val out = ArrayList<EpgProgram>(size)
    for (p in this) {
        val last = out.lastOrNull()
        if (last != null && last.title == p.title && kotlin.math.abs(last.start - p.start) < 10 * 60_000) continue
        out += p
    }
    return out
}
