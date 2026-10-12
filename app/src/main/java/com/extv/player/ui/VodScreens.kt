package com.extv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.Favorite
import com.extv.player.data.Movie
import com.extv.player.data.Series
import com.extv.player.data.lockedCategoryIds
import com.extv.player.data.toFavorite
import com.extv.player.player.PlayItem
import com.extv.player.player.PlayQueue

// --- Catalogues (films / séries) -----------------------------------------------------------------

@Composable
fun MoviesScreen(onOpen: (Movie) -> Unit) {
    val repo = App.instance.repository() ?: return
    CatalogScreen(
        type = ContentType.MOVIE,
        allLabel = "Tous les films",
        load = { repo.movies(it) },
        categoryOf = { it.categoryId },
        title = { it.name },
        image = { it.poster },
        onOpen = onOpen,
    )
}

@Composable
fun SeriesScreen(onOpen: (Series) -> Unit) {
    val repo = App.instance.repository() ?: return
    CatalogScreen(
        type = ContentType.SERIES,
        allLabel = "Toutes les séries",
        load = { repo.series(it) },
        categoryOf = { it.categoryId },
        title = { it.name },
        image = { it.cover },
        onOpen = onOpen,
    )
}

@Composable
private fun <T> CatalogScreen(
    type: ContentType,
    allLabel: String,
    load: suspend (String?) -> List<T>,
    categoryOf: (T) -> String,
    title: (T) -> String,
    image: (T) -> String?,
    onOpen: (T) -> Unit,
) {
    val app = App.instance
    val repo = app.repository() ?: return
    val categories = rememberLoader(type) { listOf(Category(Category.ALL, allLabel, type)) + repo.categories(type) }
    var selected by remember { mutableStateOf<Category?>(null) }
    val firstCategory = remember { FocusRequester() }
    val select = rememberCategoryGate { selected = it }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        LoadView(categories, Modifier.width(270.dp).fillMaxHeight()) { list ->
            LaunchedEffect(list) {
                if (selected == null) selected = list.firstOrNull { !app.parental.isLocked(it) }
                runCatching { firstCategory.requestFocus() }
            }
            CategoryColumn(list, selected?.id, select, firstFocus = firstCategory)
        }
        val category = selected
        if (category != null) {
        val items = rememberLoader(type, category.id) {
            val all = load(category.id.takeUnless { it == Category.ALL })
            val locked = if (category.id == Category.ALL) repo.lockedCategoryIds(type) else emptySet()
            all.filter { categoryOf(it) !in locked }
        }
        LoadView(items, Modifier.weight(1f).fillMaxHeight()) { list ->
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("Aucun contenu", color = C.TextDim) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list) { item -> PosterCard(title(item), image(item), onClick = { onOpen(item) }) }
                }
            }
        }
        }
    }
}

// --- Fiche film ----------------------------------------------------------------------------------

@Composable
fun MovieDetailScreen() {
    val initial = Selection.movie ?: return
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val detail = rememberLoader(initial.id) { runCatching { repo.movieDetail(initial) }.getOrDefault(initial) }
    val movie = detail.value ?: initial
    val resumeKey = "movie:${movie.id}"
    var position by remember { mutableLongStateOf(app.store.position(resumeKey)) }
    var fav by remember { mutableStateOf(app.store.isFavorite(Favorite.keyOf(ContentType.MOVIE, movie.id))) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { position = app.store.position(resumeKey) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun play(startAt: Long?) = PlayQueue.start(
        context,
        listOf(PlayItem(movie.streamUrl, movie.name, resumeKey, startAt)),
        0,
        live = false,
    )

    Row(Modifier.fillMaxSize().padding(48.dp)) {
        AsyncImage(
            model = movie.poster,
            contentDescription = movie.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(280.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.Surface),
        )
        Column(Modifier.padding(start = 40.dp).verticalScroll(rememberScrollState())) {
            Text(movie.name, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            val meta = listOfNotNull(movie.year, movie.genre, movie.duration, movie.rating?.let { "★ $it" }).joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, color = C.TextDim, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (position > 0) {
                    ActionButton("▶ Reprendre à ${formatDuration(position)}", focusRequester = first) { play(null) }
                    ActionButton("Depuis le début") { play(0) }
                } else {
                    ActionButton("▶ Lire", focusRequester = first) { play(0) }
                }
                ActionButton(if (fav) "★ Retirer des favoris" else "☆ Ajouter aux favoris") {
                    fav = app.store.toggleFavorite(movie.toFavorite())
                }
            }
            movie.plot?.let { Text(it, fontSize = 17.sp, lineHeight = 24.sp) }
            movie.director?.let { Text("Réalisation : $it", color = C.TextDim, modifier = Modifier.padding(top = 16.dp)) }
            movie.cast?.let { Text("Avec : $it", color = C.TextDim, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

// --- Fiche série ---------------------------------------------------------------------------------

@Composable
fun SeriesDetailScreen() {
    val initial = Selection.series ?: return
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val detail = rememberLoader(initial.id) { repo.seriesDetail(initial) }
    var fav by remember { mutableStateOf(app.store.isFavorite(Favorite.keyOf(ContentType.SERIES, initial.id))) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val first = remember { FocusRequester() }

    LoadView(detail) { d ->
        val series = d.series
        var season by remember { mutableIntStateOf(d.seasons.keys.minOrNull() ?: 1) }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Row {
                AsyncImage(
                    model = series.cover,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(C.Surface),
                )
                Column(Modifier.padding(start = 28.dp)) {
                    Text(series.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    series.rating?.let { Text("★ $it", color = C.TextDim) }
                    series.plot?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp)) }
                    ActionButton(if (fav) "★ Retirer des favoris" else "☆ Ajouter aux favoris") {
                        fav = app.store.toggleFavorite(series.toFavorite())
                    }
                }
            }
            if (d.seasons.isEmpty()) {
                Text("Aucun épisode disponible.", color = C.TextDim, modifier = Modifier.padding(top = 24.dp))
            }
            LazyRow(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.seasons.keys.toList()) { n ->
                    FocusTile(onClick = { season = n }, selected = n == season, focusRequester = if (n == season) first else null) {
                        Text("Saison $n", Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                }
            }
            val episodes = d.seasons[season].orEmpty()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(episodes) { i, ep ->
                    val key = "ep:${ep.id}"
                    val pos = remember(refresh, key) { app.store.position(key) }
                    FocusTile(
                        onClick = {
                            val queue = episodes.map {
                                PlayItem(it.streamUrl, "${series.name} · S${it.season}E${it.episode} · ${it.title}", "ep:${it.id}")
                            }
                            PlayQueue.start(context, queue, i, live = false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("E${ep.episode}", Modifier.width(56.dp), color = C.Accent, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ep.plot?.let { Text(it, color = C.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if (pos > 0) Text("Reprendre à ${formatDuration(pos)}", color = C.TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

