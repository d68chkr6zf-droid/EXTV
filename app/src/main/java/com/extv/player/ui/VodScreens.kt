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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.async
import com.extv.player.data.normalized
import com.extv.player.data.ContentType
import com.extv.player.data.Favorite
import com.extv.player.data.Movie
import com.extv.player.data.Series
import com.extv.player.data.toFavorite
import com.extv.player.player.PlayItem
import com.extv.player.player.PlayQueue

// --- Catalogues (films / séries) -----------------------------------------------------------------

@Composable
fun MoviesScreen(onOpen: (Movie) -> Unit) {
    val repo = App.instance.repository() ?: return
    CatalogScreen(
        title = "Films",
        loadEntries = { repo.movieEntries() },
        loadItems = { repo.moviesIn(it) },
        key = { it.id },
        name = { it.name },
        image = { it.poster },
        rating = { it.rating },
        favorite = { it.toFavorite() },
        onOpen = onOpen,
    )
}

@Composable
fun SeriesScreen(onOpen: (Series) -> Unit) {
    val repo = App.instance.repository() ?: return
    CatalogScreen(
        title = "Séries",
        loadEntries = { repo.seriesEntries() },
        loadItems = { repo.seriesIn(it) },
        key = { it.id },
        name = { it.name },
        image = { it.cover },
        rating = { it.rating },
        favorite = { it.toFavorite() },
        onOpen = onOpen,
    )
}

@Composable
private fun <T> CatalogScreen(
    title: String,
    loadEntries: suspend () -> List<CategoryEntry>,
    loadItems: suspend (String) -> List<T>,
    key: (T) -> String,
    name: (T) -> String,
    image: (T) -> String?,
    rating: (T) -> String?,
    favorite: (T) -> Favorite,
    onOpen: (T) -> Unit,
) {
    val store = App.instance.store
    val toast = rememberToast()
    val size = windowSize()
    val wide = size != WindowSize.Compact
    val entries = rememberLoader(Unit) { loadEntries() }
    var selected by remember { mutableStateOf<Category?>(null) }
    val firstCategory = remember { FocusRequester() }
    val select = rememberCategoryGate { selected = it }
    var query by remember { mutableStateOf("") }
    // Index de recherche (noms normalisés) préparé une seule fois, en arrière-plan, dès l'ouverture de l'écran.
    val searchIndex = remember {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            loadItems(Category.ALL).map { it to name(it).normalized() }
        }
    }
    LaunchedEffect(Unit) { runCatching { searchIndex.start() } }
    val searchHint = "Rechercher ${if (title == "Séries") "une série" else "un film"}"

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        LoadView(entries) { list ->
            LaunchedEffect(Unit) {
                runCatching { firstCategory.requestFocus() }
            }
            val category = selected ?: (list.firstOrNull { it.category.id == Category.RECENT }?.category ?: list.firstOrNull()?.category)
            val q = query.normalized().trim()
            // Recherche : toujours dans TOUT, quelle que soit la catégorie sélectionnée.
            val items = rememberLoader(category?.id, q) {
                if (q.isEmpty()) {
                    category?.let { loadItems(it.id) }.orEmpty()
                } else {
                    kotlinx.coroutines.delay(150)
                    val index = searchIndex.await()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        index.filter { it.second.contains(q) }.map { it.first }
                    }
                }
            }

            val grid: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    if (wide) {
                        Text(
                            if (q.isNotEmpty()) "RÉSULTATS POUR « ${query.trim().uppercase()} »" else (category?.name ?: title).uppercase(),
                            Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    LoadView(items) { all ->
                        if (all.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (q.isNotEmpty()) "Aucun résultat dans TOUT" else "Aucun contenu", color = C.TextDim) }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(
                                    when (size) {
                                        WindowSize.Compact -> 104.dp
                                        WindowSize.Medium -> 130.dp
                                        WindowSize.Expanded -> 150.dp
                                    },
                                ),
                                contentPadding = PaddingValues(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp),
                                verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp),
                            ) {
                                items(all, key = key) { item ->
                                    PosterCard(
                                        name(item),
                                        image(item),
                                        onClick = { onOpen(item) },
                                        rating = rating(item),
                                        onLongClick = {
                                            val fav = store.toggleFavorite(favorite(item))
                                            toast(if (fav) "Ajouté aux favoris" else "Retiré des favoris")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.width(if (size == WindowSize.Expanded) 290.dp else 230.dp).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            ExtvLogo(24.sp)
                            Text("  |  $title", fontSize = 18.sp, color = C.TextDim)
                        }
                        CategorySidebar(
                            list,
                            category?.id,
                            { query = ""; select(it) },
                            Modifier.fillMaxSize(),
                            firstCategory,
                            query = query,
                            onQuery = { query = it },
                            searchHint = searchHint,
                        )
                    }
                    grid(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExtvLogo(22.sp)
                    Text("  |  $title", fontSize = 16.sp, color = C.TextDim)
                }
                SearchField(query, searchHint, Modifier.padding(top = 8.dp)) { query = it }
                CategoryChips(list, category?.id, { query = ""; select(it) }, Modifier.padding(vertical = 6.dp))
                grid(Modifier.fillMaxSize())
            }
        }
    }
}

// --- Fond « affiche » des fiches -------------------------------------------------------------------

@Composable
private fun Backdrop(image: String?) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.28f),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.Bg, Color(0xCC0B0A0F), Color(0x660B0A0F)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg))))
    }
}

// --- Fiche film ----------------------------------------------------------------------------------

@Composable
fun MovieDetailScreen() {
    val initial = Selection.movie ?: return
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val compact = windowSize() == WindowSize.Compact
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
        listOf(PlayItem(movie.streamUrl, movie.name, resumeKey, startAt, ContentType.MOVIE, movie.id)),
        0,
        live = false,
    )

    val poster: @Composable (Modifier) -> Unit = { m ->
        AsyncImage(
            model = movie.poster,
            contentDescription = movie.name,
            contentScale = ContentScale.Crop,
            modifier = m.aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(placeholderGradient(movie.name))),
        )
    }
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(movie.name, fontSize = if (compact) 24.sp else 34.sp, fontWeight = FontWeight.Black)
            val meta = listOfNotNull(movie.year, movie.genre, movie.duration, formatRating(movie.rating)?.let { "★ $it" }).joinToString("   ·   ")
            if (meta.isNotBlank()) Text(meta, color = C.Accent, modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (position > 0) {
                    ActionButton("▶  Reprendre ${formatDuration(position)}", focusRequester = first, primary = true) { play(null) }
                    ActionButton("Depuis le début") { play(0) }
                } else {
                    ActionButton("▶  Lecture", focusRequester = first, primary = true) { play(0) }
                }
                ActionButton(if (fav) "★" else "☆") { fav = app.store.toggleFavorite(movie.toFavorite()) }
            }
            movie.plot?.let { Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = Color(0xFFDCD5CB)) }
            movie.director?.let { Text("Réalisation : $it", color = C.TextDim, modifier = Modifier.padding(top = 16.dp)) }
            movie.cast?.let { Text("Avec : $it", color = C.TextDim, modifier = Modifier.padding(top = 4.dp)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(movie.poster)
        if (compact) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding())) {
                poster(Modifier.width(180.dp).align(Alignment.CenterHorizontally))
                info(Modifier.padding(top = 16.dp))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(48.dp)) {
                poster(Modifier.width(280.dp))
                info(Modifier.padding(start = 40.dp).verticalScroll(rememberScrollState()))
            }
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
    val compact = windowSize() == WindowSize.Compact
    val detail = rememberLoader(initial.id) { repo.seriesDetail(initial) }
    var fav by remember { mutableStateOf(app.store.isFavorite(Favorite.keyOf(ContentType.SERIES, initial.id))) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val first = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(detail.value?.series?.cover ?: initial.cover)
        LoadView(detail) { d ->
            val series = d.series
            var season by remember { mutableIntStateOf(d.seasons.keys.minOrNull() ?: 1) }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            val episodes = d.seasons[season].orEmpty()

            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (compact) 300.dp else 380.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(screenPadding()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row {
                        AsyncImage(
                            model = series.cover,
                            contentDescription = series.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(if (compact) 110.dp else 170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(placeholderGradient(series.name))),
                        )
                        Column(Modifier.padding(start = 20.dp)) {
                            Text(series.name, fontSize = if (compact) 22.sp else 32.sp, fontWeight = FontWeight.Black)
                            formatRating(series.rating)?.let { Text("★ $it", color = C.Accent, fontWeight = FontWeight.SemiBold) }
                            series.plot?.let {
                                Text(it, maxLines = if (compact) 4 else 5, overflow = TextOverflow.Ellipsis, color = Color(0xFFDCD5CB), modifier = Modifier.padding(vertical = 8.dp))
                            }
                            ActionButton(if (fav) "★ Favori" else "☆ Ajouter aux favoris") { fav = app.store.toggleFavorite(series.toFavorite()) }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (d.seasons.isEmpty()) {
                        Text("Aucun épisode disponible.", color = C.TextDim, modifier = Modifier.padding(top = 20.dp))
                    } else {
                        LazyRow(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.seasons.keys.toList()) { n ->
                                FocusTile(
                                    onClick = { season = n },
                                    selected = n == season,
                                    focusRequester = if (n == season) first else null,
                                    shape = RoundedCornerShape(20.dp),
                                    normalColor = C.Surface2,
                                ) {
                                    Text("Saison $n", Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                itemsIndexed(episodes, key = { _, ep -> ep.id }) { i, ep ->
                    val resumeKey = "ep:${ep.id}"
                    val pos = remember(refresh, resumeKey) { app.store.position(resumeKey) }
                    FocusTile(
                        onClick = {
                            val queue = episodes.map {
                                PlayItem(
                                    it.streamUrl,
                                    "${series.name} · S${it.season}E${it.episode} · ${it.title}",
                                    "ep:${it.id}",
                                    historyType = ContentType.SERIES,
                                    historyId = series.id,
                                )
                            }
                            PlayQueue.start(context, queue, i, live = false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        normalColor = Color(0xCC17151C),
                        focusScale = 1.02f,
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2630))) {
                                AsyncImage(model = ep.image ?: series.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Text(
                                    "E${ep.episode}",
                                    Modifier.align(Alignment.TopStart).padding(4.dp).background(C.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (pos > 0) Text("Reprendre à ${formatDuration(pos)}", color = C.Accent, fontSize = 12.sp)
                                else ep.plot?.let { Text(it, color = C.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
    }
}
