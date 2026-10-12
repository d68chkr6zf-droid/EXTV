package com.extv.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Source basée sur une liste M3U (URL) et, en option, un guide XMLTV.
 * Les films et séries sont reconnus par les URL de type /movie/ et /series/.
 */
class M3uRepository(
    private val profile: Profile,
    private val http: OkHttpClient,
) : BaseRepository() {

    private class Data(
        val live: List<LiveChannel>,
        val movies: List<Movie>,
        val series: List<Series>,
        val episodes: Map<String, List<Episode>>,
        val categories: Map<ContentType, List<Category>>,
        val epgUrl: String?,
    )

    private val mutex = Mutex()
    private var data: Data? = null
    private val epgMutex = Mutex()
    private var epgData: Map<String, List<EpgProgram>>? = null

    private suspend fun data(): Data = mutex.withLock {
        data ?: load().also {
            data = it
            val now = System.currentTimeMillis()
            ContentType.values().forEach { t -> loadTimes[t] = now }
        }
    }

    override suspend fun refresh(type: ContentType) {
        mutex.withLock {
            data = null
            loadTimes.clear()
        }
        epgMutex.withLock { epgData = null }
    }

    private suspend fun load(): Data = withContext(Dispatchers.IO) {
        val playlist = http.newCall(Request.Builder().url(profile.m3uUrl.trim()).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("La liste a répondu HTTP ${r.code}")
            val body = r.body ?: throw IOException("Réponse vide")
            M3uParser.parse(body.charStream().buffered())
        }
        if (playlist.entries.isEmpty()) throw IOException("Liste vide ou format non reconnu")

        val live = ArrayList<LiveChannel>()
        val movies = ArrayList<Movie>()
        val seriesMap = LinkedHashMap<String, Series>()
        val episodes = HashMap<String, MutableList<Episode>>()
        val cats = ContentType.values().associateWith { LinkedHashMap<String, Category>() }

        playlist.entries.forEachIndexed { i, e ->
            val group = e.attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Sans catégorie"
            val type = when {
                "/movie/" in e.url -> ContentType.MOVIE
                "/series/" in e.url -> ContentType.SERIES
                else -> ContentType.LIVE
            }
            cats.getValue(type).getOrPut(group) { Category(group, group, type) }
            val logo = e.attrs["tvg-logo"]?.ifBlank { null }
            when (type) {
                ContentType.LIVE -> live += LiveChannel(
                    id = "$i",
                    name = e.name,
                    logo = logo,
                    categoryId = group,
                    epgId = e.attrs["tvg-id"]?.ifBlank { null },
                    streamUrl = e.url,
                    hasArchive = e.attrs["catchup"] != null || e.attrs["catchup-source"] != null,
                    archiveDays = (e.attrs["catchup-days"] ?: e.attrs["timeshift"])?.toIntOrNull() ?: 0,
                    num = live.size + 1,
                    catchupType = e.attrs["catchup"],
                    catchupSource = e.attrs["catchup-source"],
                )
                ContentType.MOVIE -> movies += Movie("$i", e.name, logo, group, e.url, null)
                ContentType.SERIES -> {
                    val m = EPISODE_RE.find(e.name)
                    val seriesName = m?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: e.name
                    val seriesId = "$group|$seriesName"
                    seriesMap.getOrPut(seriesId) { Series(seriesId, seriesName, logo, group, null, null) }
                    val list = episodes.getOrPut(seriesId) { mutableListOf() }
                    list += Episode(
                        id = "$i",
                        title = e.name,
                        season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1,
                        episode = m?.groupValues?.get(3)?.toIntOrNull() ?: (list.size + 1),
                        streamUrl = e.url,
                        plot = null,
                        image = logo,
                    )
                }
            }
        }
        val epgUrl = profile.epgUrl.ifBlank { playlist.header["url-tvg"] ?: playlist.header["x-tvg-url"] ?: "" }
            .split(',').firstOrNull()?.trim()?.ifBlank { null }
        Data(live, movies, seriesMap.values.toList(), episodes, cats.mapValues { it.value.values.toList() }, epgUrl)
    }

    override suspend fun categories(type: ContentType): List<Category> = data().categories[type].orEmpty()

    override suspend fun liveChannels(categoryId: String?) =
        data().live.filter { categoryId == null || it.categoryId == categoryId }

    override suspend fun movies(categoryId: String?) =
        data().movies.filter { categoryId == null || it.categoryId == categoryId }

    override suspend fun series(categoryId: String?) =
        data().series.filter { categoryId == null || it.categoryId == categoryId }

    override suspend fun movieDetail(movie: Movie) = movie

    override suspend fun seriesDetail(series: Series): SeriesDetail {
        val eps = data().episodes[series.id].orEmpty()
        return SeriesDetail(series, eps.groupBy { it.season }.toSortedMap().mapValues { (_, v) -> v.sortedBy { it.episode } })
    }

    // --- Guide XMLTV -------------------------------------------------------------------------

    private suspend fun epgMap(): Map<String, List<EpgProgram>> = epgMutex.withLock {
        epgData ?: loadEpg().also { epgData = it }
    }

    private suspend fun loadEpg(): Map<String, List<EpgProgram>> = withContext(Dispatchers.IO) {
        val url = data().epgUrl ?: return@withContext emptyMap()
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val now = System.currentTimeMillis()
                XmltvParser.parse(maybeGunzip(r.body!!.byteStream()), now - 8 * DAY_MS, now + 2 * DAY_MS)
            }
        }.getOrDefault(emptyMap())
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    private suspend fun programsFor(ch: LiveChannel): List<EpgProgram> {
        val map = epgMap()
        return map[ch.epgId ?: ""] ?: map[ch.name].orEmpty()
    }

    override suspend fun epg(channel: LiveChannel, limit: Int): List<EpgProgram> {
        val now = System.currentTimeMillis()
        return programsFor(channel).filter { it.end > now }.take(limit)
    }

    override suspend fun catchup(channel: LiveChannel): List<EpgProgram> {
        val now = System.currentTimeMillis()
        val days = if (channel.archiveDays > 0) channel.archiveDays else 7
        return programsFor(channel).filter { it.end < now && it.start > now - days * DAY_MS }.sortedByDescending { it.start }
    }

    override fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? {
        val now = System.currentTimeMillis() / 1000
        val start = program.start / 1000
        val end = program.end / 1000
        val duration = end - start
        fun fill(t: String) = t
            .replace("\${start}", "$start").replace("{utc}", "$start").replace("{start}", "$start")
            .replace("\${end}", "$end").replace("{utcend}", "$end").replace("{end}", "$end")
            .replace("\${timestamp}", "$now").replace("{lutc}", "$now")
            .replace("\${duration}", "$duration").replace("{duration}", "$duration")
            .replace("\${offset}", "${now - start}").replace("{offset}", "${now - start}")
        val source = channel.catchupSource
        return when {
            source != null && channel.catchupType == "append" -> channel.streamUrl + fill(source)
            source != null -> fill(source)
            channel.catchupType == "shift" || channel.catchupType == "default" -> {
                val sep = if ('?' in channel.streamUrl) "&" else "?"
                "${channel.streamUrl}${sep}utc=$start&lutc=$now"
            }
            else -> null
        }
    }

    override suspend fun accountInfo(): AccountInfo? = null

    companion object {
        private const val DAY_MS = 24L * 3600 * 1000
        private val EPISODE_RE = Regex("""(?i)^(.*?)[\s._-]*S(\d{1,2})\s*E(\d{1,3})""")
    }
}
