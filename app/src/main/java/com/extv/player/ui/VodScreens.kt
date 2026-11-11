package com.extv.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
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
import com.extv.player.data.Episode
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
        unit = "films",
        loadEntries = { repo.movieEntries() },
        loadItems = { repo.moviesIn(it) },
        loadTop10 = { repo.top10Movies() },
        key = { it.id },
        categoryOf = { it.categoryId },
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
        unit = "séries",
        loadEntries = { repo.seriesEntries() },
        loadItems = { repo.seriesIn(it) },
        loadTop10 = { repo.top10Series() },
        key = { it.id },
        categoryOf = { it.categoryId },
        name = { it.name },
        image = { it.cover },
        rating = { it.rating },
        favorite = { it.toFavorite() },
        onOpen = onOpen,
    )
}

/** Pilules propres à l'écran Films / Séries : l'accueil en rangées et le Top 10 Netflix. */
private const val HOME = "__home"
private const val TOP10 = "__top10"

/** Nombre d'affiches par rangée de l'accueil (au-delà, une tuile « Tout voir » ouvre la catégorie). */
private const val ROW_LIMIT = 40

private class HomeRow<T>(val id: String, val title: String, val items: List<T>, val total: Int)

/**
 * Films / Séries façon plateforme de streaming : barre de catégories en pilules défilantes, puis
 * - « Accueil » : rangées horizontales (Top 10 Netflix, Reprendre, Favoris, Récemment ajouté, puis une par catégorie) ;
 * - autre pilule (ou recherche) : toute la catégorie en grille.
 */
@Composable
private fun <T> CatalogScreen(
    title: String,
    unit: String,
    loadEntries: suspend () -> List<CategoryEntry>,
    loadItems: suspend (String) -> List<T>,
    loadTop10: suspend () -> List<Pair<Int, T>>,
    key: (T) -> String,
    categoryOf: (T) -> String,
    name: (T) -> String,
    image: (T) -> String?,
    rating: (T) -> String?,
    favorite: (T) -> Favorite,
    onOpen: (T) -> Unit,
) {
    val store = App.instance.store
    val parental = App.instance.parental
    val toast = rememberToast()
    val size = windowSize()
    val wide = size != WindowSize.Compact
    val type = if (title == "Séries") ContentType.SERIES else ContentType.MOVIE
    // Les tris / filtrages du catalogue (dizaines de milliers d'éléments) se font hors du thread de l'écran.
    // Au retour d'une fiche, l'accueil réaffiche aussitôt ce qu'il montrait (ScreenCache) et se met à jour derrière.
    val cachePrefix = "${store.activeProfileId}/$type"
    val entries = rememberCachedLoader<List<CategoryEntry>>("$cachePrefix/entries") { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { loadEntries() } }
    // Favoris lus une seule fois (et non pour chaque affiche affichée en faisant défiler les rangées).
    var favVersion by remember { mutableIntStateOf(0) }
    val favKeys = remember(favVersion) { store.favorites().mapTo(HashSet()) { it.key } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { favVersion++ }
    // Top 10 Netflix : chargé à part (téléchargement la première fois), la rangée apparaît quand il est prêt.
    val top10 = rememberCachedLoader<List<Pair<Int, T>>>("$cachePrefix/top10", refresh = false) {
        try {
            // Attendu avant d'afficher l'accueil pour qu'il soit tout en haut dès l'ouverture
            // (le téléchargement du classement est limité à quelques secondes, voir Catalog.kt).
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { loadTop10() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val top10List = top10.value.orEmpty()
    // Gardés en revenant d'une fiche : pilule, recherche, dernière affiche ouverte et sa rangée.
    var selectedId by rememberSaveable { mutableStateOf(HOME) }
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    var lastRow by rememberSaveable { mutableStateOf<String?>(null) }
    val pillFocus = remember { FocusRequester() }
    val pillState = rememberLazyListState()
    val homeState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    val topFocus = remember { FocusRequester() }
    val returned = remember { booleanArrayOf(false) }
    val startedOnTop = remember { booleanArrayOf(false) }
    val scope = rememberCoroutineScope()
    val select = rememberCategoryGate { selectedId = it.id }
    var query by rememberSaveable { mutableStateOf("") }
    var contentFocused by remember { mutableStateOf(false) }
    // Appui long : menu de l'affiche ; « Déplacer » range les favoris (dans la catégorie Favoris).
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingMove by remember { mutableStateOf<String?>(null) }
    val mover = remember { FavoriteMover(type, key) }
    val moveFocus = remember { FocusRequester() }
    BackHandler(enabled = mover.active) { mover.cancel() }
    // Index de recherche (noms normalisés) préparé une seule fois, en arrière-plan, dès l'ouverture de l'écran.
    val searchIndex = remember {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            loadItems(Category.ALL).map { it to name(it).normalized() }
        }
    }
    LaunchedEffect(Unit) { runCatching { searchIndex.start() } }
    val searchHint = "Rechercher ${if (type == ContentType.SERIES) "une série" else "un film"}"
    val posterWidth = when (size) {
        WindowSize.Compact -> 92.dp
        WindowSize.Medium -> 104.dp
        WindowSize.Expanded -> 118.dp
    }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtvLogo(if (wide) 24.sp else 22.sp)
            Text("  |  $title", fontSize = if (wide) 18.sp else 16.sp, color = C.TextDim, modifier = Modifier.weight(1f))
            if (wide) SearchField(query, searchHint, Modifier.width(320.dp)) { query = it }
        }
        if (!wide) SearchField(query, searchHint, Modifier.padding(top = 8.dp)) { query = it }
        LoadView(entries) { list -> Column(Modifier.fillMaxSize()) {
            val pills = remember(list, top10List.isNotEmpty()) {
                buildList {
                    add(CategoryEntry(Category(HOME, "Accueil", type), null))
                    if (top10List.isNotEmpty()) add(CategoryEntry(Category(TOP10, "Top 10", type), top10List.size))
                    addAll(list)
                }
            }
            val category = pills.firstOrNull { it.category.id == selectedId }?.category ?: pills.first().category
            val selectedIndex = pills.indexOfFirst { it.category.id == category.id }.coerceAtLeast(0)
            /** Remet le focus sur la pilule sélectionnée (en la faisant défiler à l'écran si besoin). */
            fun focusPills() {
                scope.launch {
                    val visible = pillState.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == selectedIndex }) pillState.scrollToItem(selectedIndex)
                    withFrameNanos { }
                    runCatching { pillFocus.requestFocus() }
                }
            }
            LaunchedEffect(Unit) {
                if (selectedIndex > 0) pillState.scrollToItem(selectedIndex)
                withFrameNanos { }
                runCatching { pillFocus.requestFocus() }
            }
            // Retour depuis les affiches : on remonte aux pilules (Retour depuis les pilules quitte l'écran).
            BackHandler(enabled = contentFocused && !mover.active && menuFor == null) { focusPills() }

            PillBar(pills, category.id, pillState, pillFocus, size == WindowSize.Compact) { query = ""; select(it) }

            val q = query.normalized().trim()
            val inFavorites = category.id == Category.FAVORITES && q.isEmpty()
            LaunchedEffect(category.id, q) { mover.reset() }

            /** Une affiche, avec le menu de l'appui long (favoris / déplacer). */
            val card: @Composable (T, String?, Modifier, FocusRequester?, Boolean, List<T>) -> Unit = { item, rowId, modifier, requester, moving, siblings ->
                val favKey = remember(item) { favorite(item).key }
                val isFav = favKey in favKeys
                PosterCard(
                    name(item),
                    image(item),
                    onClick = {
                        lastOpened = key(item)
                        lastRow = rowId
                        onOpen(item)
                    },
                    modifier = modifier,
                    focusRequester = requester,
                    rating = rating(item),
                    onLongClick = { if (!mover.active) menuFor = "${rowId.orEmpty()}/${key(item)}" },
                    favorite = isFav,
                    moveArrows = if (moving) "◀ ▲ ▼ ▶" else null,
                    menu = {
                        if (menuFor == "${rowId.orEmpty()}/${key(item)}") {
                            FavoriteMenu(
                                isFavorite = isFav,
                                onToggle = {
                                    val now = store.toggleFavorite(favorite(item))
                                    favVersion++
                                    toast(if (now) "★ Ajouté aux favoris" else "Retiré des favoris")
                                },
                                onMove = {
                                    if (inFavorites) {
                                        mover.start(siblings, key(item))
                                    } else {
                                        pendingMove = key(item)
                                        query = ""
                                        selectedId = Category.FAVORITES
                                    }
                                },
                                onDismiss = { menuFor = null },
                            )
                        }
                    },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { contentFocused = it.hasFocus }) {
                if (q.isEmpty() && category.id == HOME) {
                    // --- Accueil : rangées horizontales ---
                    val home = rememberCachedLoader<List<HomeRow<T>>>("$cachePrefix/home", list) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val groups = loadItems(Category.ALL).groupBy(categoryOf)
                            buildList {
                                fun virtual(id: String, label: String, items: List<T>) {
                                    if (items.isNotEmpty()) add(HomeRow(id, label, items.take(ROW_LIMIT), items.size))
                                }
                                virtual(Category.CONTINUE, "Reprendre la lecture", loadItems(Category.CONTINUE))
                                virtual(Category.FAVORITES, "Favoris", loadItems(Category.FAVORITES))
                                virtual(Category.RECENT, "Récemment ajouté", loadItems(Category.RECENT))
                                // Catégories « Tendance sur … » du serveur en premier, juste après Reprendre / Favoris / Récents.
                                list.filter { !Category.isVirtual(it.category.id) && !parental.isLocked(it.category) }
                                    .sortedBy { if (it.category.name.normalized().contains("tendance")) 0 else 1 }
                                    .forEach { e ->
                                    groups[e.category.id]?.let { add(HomeRow(e.category.id, e.category.name, it.take(ROW_LIMIT), it.size)) }
                                }
                            }
                        }
                    }
                    LoadView(top10) { _ -> LoadView(home) { rows ->
                        val all = remember(rows, top10List) {
                            if (top10List.isEmpty()) rows else listOf(HomeRow(TOP10, "Top 10 du moment", top10List.map { it.second }, top10List.size)) + rows
                        }
                        val ranks = remember(top10List) { top10List.associate { key(it.second) to it.first } }
                        // L'écran s'ouvre sur la première rangée (le Top 10 quand il y en a un).
                        LaunchedEffect(all.firstOrNull()?.id) {
                            if (lastOpened == null && homeState.firstVisibleItemIndex <= 1) homeState.scrollToItem(0)
                        }
                        if (all.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Aucun contenu", color = C.TextDim) }
                        } else {
                            LazyColumn(state = homeState, contentPadding = PaddingValues(bottom = 24.dp)) {
                                items(all, key = { it.id }) { row ->
                                    val rowState = rememberLazyListState()
                                    // Retour d'une fiche : focus sur l'affiche ouverte, dans sa rangée (une seule fois).
                                    LaunchedEffect(Unit) {
                                        if (returned[0] || row.id != lastRow) return@LaunchedEffect
                                        returned[0] = true
                                        val i = row.items.indexOfFirst { key(it) == lastOpened }
                                        if (i < 0) return@LaunchedEffect
                                        if (rowState.layoutInfo.visibleItemsInfo.none { it.index == i }) rowState.scrollToItem(i)
                                        withFrameNanos { }
                                        runCatching { returnFocus.requestFocus() }
                                    }
                                    // À l'ouverture, le focus va sur la 1re affiche du Top 10 (comme sur Netflix).
                                    LaunchedEffect(Unit) {
                                        if (row.id != TOP10 || startedOnTop[0] || lastOpened != null || contentFocused) return@LaunchedEffect
                                        startedOnTop[0] = true
                                        withFrameNanos { }
                                        runCatching { topFocus.requestFocus() }
                                    }
                                    Column(Modifier.padding(top = 10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                                            Text(row.title, fontSize = if (wide) 18.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            if (row.id != TOP10) {
                                                Text("  · ${row.total} $unit", fontSize = 12.sp, color = C.TextDim, maxLines = 1)
                                            }
                                        }
                                        LazyRow(
                                            state = rowState,
                                            horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                        ) {
                                            lazyItemsIndexed(row.items, key = { _, it -> key(it) }) { index, item ->
                                                val requester = when {
                                                    row.id == lastRow && key(item) == lastOpened -> returnFocus
                                                    row.id == TOP10 && index == 0 && lastOpened == null -> topFocus
                                                    else -> null
                                                }
                                                val rank = if (row.id == TOP10) ranks[key(item)] else null
                                                if (rank != null) {
                                                    Row(Modifier.showWholeWhenFocused(), verticalAlignment = Alignment.Bottom) {
                                                        RankNumber(rank, posterWidth)
                                                        card(item, row.id, Modifier.width(posterWidth).offset(x = (-posterWidth * 0.08f)), requester, false, row.items)
                                                    }
                                                } else {
                                                    card(item, row.id, Modifier.width(posterWidth), requester, false, row.items)
                                                }
                                            }
                                            if (row.total > row.items.size) {
                                                item(key = "__more") {
                                                    SeeAllTile(row.total, unit, posterWidth) {
                                                        pills.firstOrNull { it.category.id == row.id }?.let { query = ""; select(it.category) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } }
                } else {
                    // --- Grille : catégorie entière, Top 10 ou résultats de recherche ---
                    // Recherche : toujours dans TOUT, quelle que soit la catégorie sélectionnée.
                    val items = rememberLoader(category.id, q, top10List) {
                        if (q.isEmpty()) {
                            if (category.id == TOP10) top10List.map { it.second } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { loadItems(category.id) }
                        } else {
                            kotlinx.coroutines.delay(150)
                            val index = searchIndex.await()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                index.filter { it.second.contains(q) }.map { it.first }
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        LoadView(items) { loaded ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp)) {
                                Text(
                                    if (q.isNotEmpty()) "Résultats pour « ${query.trim()} »" else if (category.id == TOP10) "Top 10 du moment" else category.name,
                                    fontSize = if (wide) 18.sp else 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text("  · ${loaded.size} $unit", fontSize = 12.sp, color = C.TextDim, maxLines = 1)
                            }
                            val all = mover.display(loaded)
                            val gridState = rememberLazyGridState()
                            // « Déplacer » choisi hors de la catégorie Favoris : on y passe, puis le déplacement commence.
                            LaunchedEffect(loaded, pendingMove) {
                                val id = pendingMove
                                if (id != null && inFavorites) {
                                    pendingMove = null
                                    mover.start(loaded, id)
                                }
                            }
                            // Retour d'une fiche : on retrouve l'affiche ouverte, avec le focus dessus (une seule fois).
                            LaunchedEffect(loaded) {
                                if (returned[0] || lastRow != null) return@LaunchedEffect
                                returned[0] = true
                                val i = lastOpened?.let { id -> all.indexOfFirst { key(it) == id } } ?: -1
                                if (i < 0) return@LaunchedEffect
                                gridState.scrollToItem(i)
                                withFrameNanos { }
                                runCatching { returnFocus.requestFocus() }
                            }
                            // L'affiche déplacée reste visible et garde le focus.
                            LaunchedEffect(mover.movingId, mover.order) {
                                val i = mover.index()
                                if (i < 0) return@LaunchedEffect
                                val visible = gridState.layoutInfo.visibleItemsInfo
                                if (visible.none { it.index == i } || i < visible.first().index + 1 || i > visible.last().index - 1) {
                                    gridState.scrollToItem(i)
                                }
                                withFrameNanos { }
                                runCatching { moveFocus.requestFocus() }
                            }
                            if (all.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (q.isNotEmpty()) "Aucun résultat dans TOUT" else "Aucun contenu", color = C.TextDim) }
                            } else {
                                LazyVerticalGrid(
                                    state = gridState,
                                    // Même largeur d'affiche que dans les rangées de l'accueil.
                                    columns = GridCells.Adaptive(posterWidth),
                                    contentPadding = PaddingValues(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp),
                                ) {
                                    items(all, key = key) { item ->
                                        val moving = mover.isMoving(item)
                                        card(
                                            item,
                                            null,
                                            (if (mover.active) Modifier.animateItem() else Modifier).moveKeys(
                                                active = moving,
                                                onMove = { d ->
                                                    val columns = (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1
                                                    mover.shift(
                                                        when (d) {
                                                            MoveDir.LEFT -> -1
                                                            MoveDir.RIGHT -> 1
                                                            MoveDir.UP -> -columns
                                                            MoveDir.DOWN -> columns
                                                        },
                                                    )
                                                },
                                                onDone = {
                                                    mover.commit()
                                                    toast("Favoris rangés")
                                                },
                                            ),
                                            if (moving) moveFocus else if (lastRow == null && key(item) == lastOpened) returnFocus else null,
                                            moving,
                                            all,
                                        )
                                    }
                                }
                            }
                            if (mover.active) MoveHint("◀ ▲ ▼ ▶")
                        }
                    }
                }
            }
        } }
    }
}

/** Barre de catégories en pilules arrondies, défilant à l'horizontale. */
@Composable
private fun PillBar(
    pills: List<CategoryEntry>,
    selectedId: String,
    state: androidx.compose.foundation.lazy.LazyListState,
    focus: FocusRequester,
    compact: Boolean,
    onSelect: (Category) -> Unit,
) {
    val parental = App.instance.parental
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = if (compact) 8.dp else 12.dp),
    ) {
        items(pills, key = { it.category.id }) { e ->
            val selected = e.category.id == selectedId
            FocusTile(
                onClick = { onSelect(e.category) },
                selected = selected,
                focusRequester = if (selected) focus else null,
                shape = RoundedCornerShape(50),
                normalColor = C.Surface2,
                focusedColor = C.Accent,
                selectedColor = C.Selected,
                focusScale = 1.08f,
            ) { focused ->
                Row(
                    Modifier.padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.category.name,
                        Modifier.widthIn(max = 260.dp).focusMarquee(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            focused -> Color.Black
                            selected -> C.Accent
                            else -> C.Text
                        },
                    )
                    if (parental.isLocked(e.category)) Text(" 🔒", fontSize = 12.sp)
                }
            }
        }
    }
}

/** Grand chiffre doré du Top 10 (contour or sur fond sombre), à gauche de l'affiche. */
@Composable
private fun RankNumber(rank: Int, posterWidth: androidx.compose.ui.unit.Dp) {
    // Chiffre dessiné comme une forme (Path) et non comme du texte : certains boîtiers (Formuler) forcent
    // le texte en blanc (« texte à contraste élevé ») et ignoraient le contour doré. Forme calculée une seule fois.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val strokePx = with(density) { 3.dp.toPx() }
    val shape = remember(rank, posterWidth, density) {
        val textPx = with(density) { (posterWidth * 1.05f).toPx() }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            } else {
                android.graphics.Typeface.DEFAULT_BOLD
            }
            letterSpacing = -0.08f / 1.05f
        }
        val path = android.graphics.Path()
        val text = "$rank"
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        path.offset(-bounds.left + strokePx, -bounds.top + strokePx)
        Triple(path.asComposePath(), bounds.width() + 2 * strokePx, bounds.height() + 2 * strokePx)
    }
    val (path, w, h) = shape
    androidx.compose.foundation.Canvas(
        Modifier.padding(end = 2.dp).size(with(density) { w.toDp() }, with(density) { h.toDp() }),
    ) {
        drawPath(path, C.Bg)
        drawPath(path, C.Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx))
    }
}

/** Dernière tuile d'une rangée trop longue : ouvre la catégorie entière. */
@Composable
private fun SeeAllTile(total: Int, unit: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = Modifier.width(width), normalColor = C.Surface2, focusScale = 1.07f) { focused ->
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tout voir", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (focused) C.Accent else C.Text)
                Text("$total $unit", fontSize = 12.sp, color = C.TextDim)
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
        listOf(PlayItem(movie.streamUrl, movie.name, resumeKey, startAt, ContentType.MOVIE, movie.id, favorite = movie.toFavorite())),
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
                movie.trailer.validTrailer()?.let { t -> ActionButton("Bande-annonce") { openTrailer(context, t) } }
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
            val ordered = remember(d) { d.seasons.toSortedMap().values.flatten() }
            // Épisode à reprendre : le dernier lancé s'il n'est pas fini, sinon le suivant.
            val resume = remember(d, refresh) {
                val lastKey = app.store.lastEpisode(series.id)
                val last = ordered.indexOfFirst { "ep:${it.id}" == lastKey }
                when {
                    last < 0 -> null
                    app.store.position(lastKey!!) > 0 -> ordered[last] to true
                    last < ordered.lastIndex -> ordered[last + 1] to false
                    else -> null
                }
            }
            var season by remember { mutableIntStateOf(resume?.first?.season ?: d.seasons.keys.minOrNull() ?: 1) }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            val episodes = d.seasons[season].orEmpty()

            fun playEpisode(ep: Episode) {
                val list = d.seasons[ep.season].orEmpty()
                val queue = list.map {
                    PlayItem(
                        it.streamUrl,
                        "${series.name} · S${it.season}E${it.episode} · ${it.title}",
                        "ep:${it.id}",
                        historyType = ContentType.SERIES,
                        historyId = series.id,
                        favorite = series.toFavorite(),
                    )
                }
                PlayQueue.start(context, queue, list.indexOf(ep).coerceAtLeast(0), live = false)
            }

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
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                resume?.let { (ep, started) ->
                                    val pos = app.store.position("ep:${ep.id}")
                                    val label = if (started) "▶  Reprendre S${ep.season}E${ep.episode} à ${formatDuration(pos)}"
                                    else "▶  Épisode suivant S${ep.season}E${ep.episode}"
                                    ActionButton(label, focusRequester = first, primary = true) { playEpisode(ep) }
                                }
                                // Favoris à gauche, bande-annonce à droite, sur la même ligne.
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ActionButton(if (fav) "★ Favori" else "☆ Ajouter aux favoris") { fav = app.store.toggleFavorite(series.toFavorite()) }
                                    series.trailer.validTrailer()?.let { t -> ActionButton("Bande-annonce") { openTrailer(context, t) } }
                                }
                            }
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
                                    focusRequester = if (n == season && resume == null) first else null,
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
                        onClick = { playEpisode(ep) },
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
                                TileText(ep.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (pos > 0) Text("Reprendre à ${formatDuration(pos)}", color = C.Accent, fontSize = 12.sp)
                                else ep.plot?.let { TileText(it, color = C.TextDim, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Bande-annonce -------------------------------------------------------------------------------

/** Lit la bande-annonce dans l'appli (lecteur YouTube intégré, sans l'appli YouTube). */
private fun openTrailer(context: android.content.Context, trailer: String) =
    com.extv.player.player.TrailerActivity.start(context, trailer)

/** Valeur de bande-annonce utilisable (les serveurs renvoient parfois "" ou "0"). */
private fun String?.validTrailer(): String? = this?.trim()?.takeIf { it.length >= 6 && it != "null" }
