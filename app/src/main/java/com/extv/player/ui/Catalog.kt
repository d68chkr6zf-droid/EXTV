package com.extv.player.ui

import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.IptvRepository
import com.extv.player.data.LiveChannel
import com.extv.player.data.Movie
import com.extv.player.data.Series
import com.extv.player.data.lockedCategoryIds

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

suspend fun IptvRepository.liveEntries(archiveOnly: Boolean = false): List<CategoryEntry> =
    entriesOf(ContentType.LIVE, liveChannels(null).filter { !archiveOnly || it.hasArchive }, { it.categoryId }, { it.id }, false)

suspend fun IptvRepository.liveIn(categoryId: String, archiveOnly: Boolean = false): List<LiveChannel> =
    itemsOf(ContentType.LIVE, liveChannels(null).filter { !archiveOnly || it.hasArchive }, categoryId, { it.categoryId }, { it.id }, null)

suspend fun IptvRepository.movieEntries(): List<CategoryEntry> =
    entriesOf(ContentType.MOVIE, movies(null), { it.categoryId }, { it.id }, true)

suspend fun IptvRepository.moviesIn(categoryId: String): List<Movie> =
    itemsOf(ContentType.MOVIE, movies(null), categoryId, { it.categoryId }, { it.id }, { it.added })

suspend fun IptvRepository.seriesEntries(): List<CategoryEntry> =
    entriesOf(ContentType.SERIES, series(null), { it.categoryId }, { it.id }, true)

suspend fun IptvRepository.seriesIn(categoryId: String): List<Series> =
    itemsOf(ContentType.SERIES, series(null), categoryId, { it.categoryId }, { it.id }, { it.added })
