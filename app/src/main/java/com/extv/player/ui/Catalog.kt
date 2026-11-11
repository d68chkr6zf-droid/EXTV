package com.extv.player.ui

import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.FrenchTnt
import com.extv.player.data.IptvRepository
import com.extv.player.data.LiveChannel
import com.extv.player.data.Movie
import com.extv.player.data.Series
import com.extv.player.data.lockedCategoryIds
import com.extv.player.data.normalized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Construit les listes de catégories affichées (avec Tout, Favoris, Historique, Récemment ajouté
 * et le nombre d'éléments) et le contenu de chaque catégorie, en masquant les catégories verrouillées.
 */
private const val RECENT_LIMIT = 100

private suspend fun <T> IptvRepository.entriesOf(
    type: ContentType,
    all: List<T>,
    categoryOf: (T) -> String,
    idOf: (T) -> String,
    withRecent: Boolean,
): List<CategoryEntry> {
    val store = App.instance.store
    val locked = lockedCategoryIds(type)
    val visible = all.filter { categoryOf(it) !in locked }
    val favorites = store.favorites().filter { it.type == type }.map { it.id }.toSet()
    val history = store.history(type).toSet()
    val counts = all.groupingBy(categoryOf).eachCount()
    return buildList {
        add(CategoryEntry(Category(Category.ALL, "Tout", type), visible.size))
        add(CategoryEntry(Category(Category.FAVORITES, "Favoris", type), visible.count { idOf(it) in favorites }))
        val historyName = if (type == ContentType.LIVE) "Historique" else "Récemment regardé"
        add(CategoryEntry(Category(Category.HISTORY, historyName, type), visible.count { idOf(it) in history }))
        if (type != ContentType.LIVE) {
            val started = store.inProgress(type).toSet()
            add(CategoryEntry(Category(Category.CONTINUE, "Reprendre la lecture", type), visible.count { idOf(it) in started }))
        }
        if (withRecent) add(CategoryEntry(Category(Category.RECENT, "Récemment ajouté", type), minOf(RECENT_LIMIT, visible.size)))
        categories(type).forEach { c ->
            val n = counts[c.id] ?: 0
            if (n > 0) add(CategoryEntry(c, n))
        }
    }
}

private suspend fun <T> IptvRepository.itemsOf(
    type: ContentType,
    all: List<T>,
    categoryId: String,
    categoryOf: (T) -> String,
    idOf: (T) -> String,
    addedOf: ((T) -> Long)?,
): List<T> {
    val store = App.instance.store
    if (!Category.isVirtual(categoryId)) return all.filter { categoryOf(it) == categoryId }
    val locked = lockedCategoryIds(type)
    val visible = all.filter { categoryOf(it) !in locked }
    return when (categoryId) {
        Category.FAVORITES -> {
            val byId = visible.associateBy(idOf)
            store.favorites().filter { it.type == type }.mapNotNull { byId[it.id] }
        }
        Category.HISTORY -> {
            val byId = visible.associateBy(idOf)
            store.history(type).mapNotNull { byId[it] }
        }
        Category.CONTINUE -> {
            val byId = visible.associateBy(idOf)
            store.inProgress(type).mapNotNull { byId[it] }
        }
        Category.RECENT -> if (addedOf != null) visible.sortedByDescending(addedOf).take(RECENT_LIMIT) else visible.take(RECENT_LIMIT)
        else -> visible
    }
}

/** Prépare le flux de secours de chaque chaîne (autre version de la même chaîne dans l'abonnement). */
private suspend fun IptvRepository.indexAlternates() {
    val all = runCatching { liveChannels(null) }.getOrNull() ?: return
    withContext(Dispatchers.Default) { FrenchTnt.indexAlternates(all) }
}

suspend fun IptvRepository.liveEntries(archiveOnly: Boolean = false): List<CategoryEntry> {
    indexAlternates()
    val entries = entriesOf(ContentType.LIVE, liveChannels(null).filter { !archiveOnly || it.hasArchive }, { it.categoryId }, { it.id }, false)
    if (archiveOnly) return entries
    // « FR| TNT » juste sous Favoris.
    val tnt = CategoryEntry(Category(Category.TNT, FrenchTnt.CATEGORY_NAME, ContentType.LIVE), tntChannels().size)
    val at = entries.indexOfFirst { it.category.id == Category.FAVORITES } + 1
    return entries.toMutableList().apply { add(at.coerceIn(0, size), tnt) }
}

/** Chaînes de la TNT : celles de l'abonnement (hors catégories verrouillées), complétées par les flux officiels. */
private suspend fun IptvRepository.tntChannels(): List<LiveChannel> {
    val locked = lockedCategoryIds(ContentType.LIVE)
    val all = runCatching { liveChannels(null) }.getOrDefault(emptyList()).filter { it.categoryId !in locked }
    val names = runCatching { categories(ContentType.LIVE) }.getOrDefault(emptyList()).associate { it.id to it.name }
    return withContext(Dispatchers.Default) { FrenchTnt.channels(all, names) }
}

suspend fun IptvRepository.liveIn(categoryId: String, archiveOnly: Boolean = false): List<LiveChannel> {
    indexAlternates()
    if (categoryId == Category.TNT) {
        val list = tntChannels().filter { !archiveOnly || it.hasArchive }
        // Directs Dailymotion : adresse signée demandée au moment d'afficher la catégorie.
        return withContext(Dispatchers.IO) {
            list.map { ch ->
                if (FrenchTnt.isOwn(ch) && ch.streamUrl.startsWith("dailymotion:")) {
                    async { FrenchTnt.resolve(ch.streamUrl)?.let { ch.copy(streamUrl = it) } }
                } else {
                    CompletableDeferred<LiveChannel?>(ch)
                }
            }.awaitAll().filterNotNull()
        }
    }
    return itemsOf(ContentType.LIVE, liveChannels(null).filter { !archiveOnly || it.hasArchive }, categoryId, { it.categoryId }, { it.id }, null)
}

suspend fun IptvRepository.movieEntries(): List<CategoryEntry> =
    entriesOf(ContentType.MOVIE, movies(null), { it.categoryId }, { it.id }, true)

suspend fun IptvRepository.moviesIn(categoryId: String): List<Movie> =
    itemsOf(ContentType.MOVIE, movies(null), categoryId, { it.categoryId }, { it.id }, { it.added })

suspend fun IptvRepository.seriesEntries(): List<CategoryEntry> =
    entriesOf(ContentType.SERIES, series(null), { it.categoryId }, { it.id }, true)

suspend fun IptvRepository.seriesIn(categoryId: String): List<Series> =
    itemsOf(ContentType.SERIES, series(null), categoryId, { it.categoryId }, { it.id }, { it.added })


/** Catégories « Tendance sur … » du serveur (Netflix, Prime Video, Disney+, Apple TV+, HBO…), dans l'ordre du serveur. */
private suspend fun IptvRepository.serverTrendingCategories(type: ContentType): List<com.extv.player.data.Category> {
    val parental = App.instance.parental
    return runCatching { categories(type) }.getOrDefault(emptyList()).filter { !parental.isLocked(it) }
        .filter { c -> c.name.normalized().let { it.contains("tendance") || it.contains("trend") || (it.contains("top") && PLATFORMS.any(it::contains)) } }
}

private val PLATFORMS = listOf("netflix", "prime", "amazon", "disney", "apple", "hbo", "max", "canal", "paramount")

/** Catégories du serveur avec les nouveautés (« VOD Récemment ajouté », « Nouveautés », « Au cinéma »…). */
private fun isRecentCategory(name: String): Boolean = name.normalized().let {
    it.contains("recemment") || it.contains("recent") || it.contains("nouveaut") || it.contains("cinema") || it.contains("ajout")
}

/**
 * Top 10 mélangé, à tour de rôle :
 *  - Netflix : vrai classement (France puis mondial) retrouvé dans l'abonnement, complété par « Tendance sur Netflix » ;
 *  - Cinéma (films seulement) : box-office France de la semaine, retrouvé parmi les films récemment ajoutés ;
 *  - puis chaque autre « Tendance sur … » du serveur (Prime Video, Disney+, Apple TV+, HBO…).
 * Chaque titre une seule fois, numérotés de 1 à 10.
 */
private suspend fun <T> IptvRepository.top10Of(
    type: ContentType,
    kind: com.extv.player.data.NetflixTop10.Kind,
    itemsIn: suspend (String) -> List<T>,
    name: (T) -> String,
    year: (T) -> Int?,
): List<Pair<Int, T>> {
    val Top = com.extv.player.data.NetflixTop10
    val trendCats = serverTrendingCategories(type)
    val (netflixCats, otherCats) = trendCats.partition { it.name.normalized().contains("netflix") }
    val netflixTrending = netflixCats.firstOrNull()?.let { itemsIn(it.id) }.orEmpty()
    val others = otherCats.map { itemsIn(it.id) }.filter { it.isNotEmpty() }
    // Classement attendu 8 s au plus (il continue de se charger en arrière-plan pour la prochaine fois).
    val entries = kotlinx.coroutines.withTimeoutOrNull(8_000) { Top.entries() }.orEmpty()
    val all = if (entries.isEmpty()) emptyList() else itemsIn(Category.ALL)
    val netflix = withContext(Dispatchers.Default) {
        if (entries.isEmpty()) emptyList() else Top.match(kind, entries, all, name, year, netflixTrending)
    } + netflixTrending
    val cinema = if (kind != Top.Kind.FILMS || entries.none { it.kind == Top.Kind.CINEMA }) emptyList() else {
        // Films au cinéma : cherchés dans « Récemment ajouté » et les catégories de nouveautés du serveur,
        // puis dans tout le catalogue seulement s'ils sont de cette année ou de l'an dernier (évite les homonymes anciens).
        val recentCats = runCatching { categories(type) }.getOrDefault(emptyList()).filter { isRecentCategory(it.name) && !App.instance.parental.isLocked(it) }
        val recent = (itemsIn(Category.RECENT) + recentCats.flatMap { itemsIn(it.id) }).distinct()
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val pool = (recent + all.filter { year(it)?.let { y -> y >= thisYear - 1 } == true }).distinct()
        withContext(Dispatchers.Default) { Top.match(Top.Kind.CINEMA, entries, pool, name, year) }
    }
    val sources = listOf(netflix, cinema) + others
    val picked = ArrayList<T>()
    val keys = HashSet<String>()
    val cursors = IntArray(sources.size)
    while (picked.size < 10 && sources.indices.any { cursors[it] < sources[it].size }) {
        for (i in sources.indices) {
            if (picked.size >= 10) break
            val list = sources[i]
            while (cursors[i] < list.size) {
                val item = list[cursors[i]++]
                val key = Top.titleKey(name(item)).ifEmpty { name(item) }
                if (item !in picked && keys.add(key)) {
                    picked += item
                    break
                }
            }
        }
    }
    return picked.mapIndexed { i, item -> (i + 1) to item }
}

suspend fun IptvRepository.top10Movies(): List<Pair<Int, Movie>> =
    top10Of<Movie>(ContentType.MOVIE, com.extv.player.data.NetflixTop10.Kind.FILMS, { moviesIn(it) }, { it.name }) { m ->
        m.year?.take(4)?.toIntOrNull() ?: com.extv.player.data.NetflixTop10.yearIn(m.name)
    }

suspend fun IptvRepository.top10Series(): List<Pair<Int, Series>> =
    top10Of<Series>(ContentType.SERIES, com.extv.player.data.NetflixTop10.Kind.TV, { seriesIn(it) }, { it.name }) { s ->
        com.extv.player.data.NetflixTop10.yearIn(s.name)
    }
