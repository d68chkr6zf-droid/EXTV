package com.extv.player.data

import com.extv.player.App
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Source de contenus commune aux comptes Xtream Codes et aux listes M3U. */
interface IptvRepository {
    suspend fun categories(type: ContentType): List<Category>

    /** categoryId = null pour tout le catalogue. */
    suspend fun liveChannels(categoryId: String?): List<LiveChannel>
    suspend fun movies(categoryId: String?): List<Movie>
    suspend fun series(categoryId: String?): List<Series>

    suspend fun movieDetail(movie: Movie): Movie
    suspend fun seriesDetail(series: Series): SeriesDetail

    /** Programmes à venir (en cours en premier). */
    suspend fun epg(channel: LiveChannel, limit: Int = 10): List<EpgProgram>

    /** Programmes passés disponibles en rattrapage (catch-up), du plus récent au plus ancien. */
    suspend fun catchup(channel: LiveChannel): List<EpgProgram>
    fun catchupUrl(channel: LiveChannel, program: EpgProgram): String?

    suspend fun accountInfo(): AccountInfo?

    suspend fun search(query: String): SearchResults
}

abstract class BaseRepository : IptvRepository {
    override suspend fun search(query: String): SearchResults {
        val q = query.normalized().trim()
        if (q.length < 2) return SearchResults()
        val live = runCatching { liveChannels(null) }.getOrDefault(emptyList())
            .filter { it.name.normalized().contains(q) }.take(120)
        val movies = runCatching { movies(null) }.getOrDefault(emptyList())
            .filter { it.name.normalized().contains(q) }.take(120)
        val series = runCatching { series(null) }.getOrDefault(emptyList())
            .filter { it.name.normalized().contains(q) }.take(120)
        return SearchResults(live, movies, series)
    }
}

fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Identifiants des catégories actuellement verrouillées par le contrôle parental. */
suspend fun IptvRepository.lockedCategoryIds(type: ContentType): Set<String> {
    val parental = App.instance.parental
    return runCatching { categories(type) }.getOrDefault(emptyList())
        .filter { parental.isLocked(it) }.map { it.id }.toSet()
}

/** Recherche en masquant les contenus des catégories verrouillées. */
suspend fun IptvRepository.searchVisible(query: String): SearchResults {
    val r = search(query)
    val lockedLive = lockedCategoryIds(ContentType.LIVE)
    val lockedMovies = lockedCategoryIds(ContentType.MOVIE)
    val lockedSeries = lockedCategoryIds(ContentType.SERIES)
    return SearchResults(
        live = r.live.filterNot { it.categoryId in lockedLive },
        movies = r.movies.filterNot { it.categoryId in lockedMovies },
        series = r.series.filterNot { it.categoryId in lockedSeries },
    )
}

internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
