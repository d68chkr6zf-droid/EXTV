package com.extv.player.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
            liveCache ?: apiArray("get_live_streams").objects().mapIndexed { i, o ->
                val id = o.str("stream_id") ?: ""
                LiveChannel(
                    id = id,
                    name = o.str("name") ?: "Chaîne $id",
                    logo = o.str("stream_icon"),
                    categoryId = o.str("category_id") ?: "",
                    epgId = o.str("epg_channel_id"),
                    streamUrl = "$base/live/$user/$pass/$id.${store.liveFormat}",
                    hasArchive = o.str("tv_archive") == "1",
                    archiveDays = o.str("tv_archive_duration")?.toIntOrNull() ?: 0,
                    num = o.str("num")?.toIntOrNull() ?: (i + 1),
                )
            }.also { liveCache = it }
        }
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    override suspend fun movies(categoryId: String?): List<Movie> {
        val all = mutex.withLock {
            movieCache ?: apiArray("get_vod_streams").objects().map { o ->
                val id = o.str("stream_id") ?: ""
                val ext = o.str("container_extension") ?: "mp4"
                Movie(
                    id = id,
                    name = o.str("name") ?: "Film $id",
                    poster = o.str("stream_icon"),
                    categoryId = o.str("category_id") ?: "",
                    streamUrl = "$base/movie/$user/$pass/$id.$ext",
                    rating = o.str("rating")?.takeIf { it != "0" },
                )
            }.also { movieCache = it }
        }
        return if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    override suspend fun series(categoryId: String?): List<Series> {
        val all = mutex.withLock {
            seriesCache ?: apiArray("get_series").objects().map { o ->
                Series(
                    id = o.str("series_id") ?: "",
                    name = o.str("name") ?: "?",
                    cover = o.str("cover"),
                    categoryId = o.str("category_id") ?: "",
                    plot = o.str("plot"),
                    rating = o.str("rating")?.takeIf { it != "0" },
                )
            }.also { seriesCache = it }
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
    }

    override suspend fun catchup(channel: LiveChannel): List<EpgProgram> {
        val now = System.currentTimeMillis()
        val since = now - (channel.archiveDays.coerceAtLeast(1)) * DAY_MS
        return parseListings(api("get_simple_data_table", "stream_id" to channel.id))
            .filter { (p, archived) -> p.end < now && (archived || p.start > since) }
            .map { it.first }
            .sortedByDescending { it.start }
    }

    override fun catchupUrl(channel: LiveChannel, program: EpgProgram): String {
        val minutes = ((program.end - program.start) / 60_000).coerceAtLeast(1)
        // Le serveur attend son heure locale au format yyyy-MM-dd:HH-mm.
        val start = program.startRaw?.takeIf { it.length >= 16 }?.let { raw ->
            raw.substring(0, 10) + ":" + raw.substring(11, 16).replace(':', '-')
        } ?: SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(program.start))
        return "$base/timeshift/$user/$pass/$minutes/$start/${channel.id}.ts"
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
