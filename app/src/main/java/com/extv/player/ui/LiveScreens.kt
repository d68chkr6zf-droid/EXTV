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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.R
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.EpgProgram
import com.extv.player.data.Favorite
import com.extv.player.data.LiveChannel
import com.extv.player.data.toFavorite
import com.extv.player.player.PlayItem
import com.extv.player.player.PlayQueue

fun LiveChannel.toPlayItem() = PlayItem(streamUrl, name, historyType = ContentType.LIVE, historyId = id)

private fun programFraction(p: EpgProgram, now: Long): Float =
    ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

// --- TV en direct ---------------------------------------------------------------------------------

/**
 * TV / tablette : catégories | chaînes (programme en cours) | aperçu vidéo + guide.
 * Un clic lance l'aperçu, un second clic passe en plein écran. Appui long : favori.
 * Smartphone : catégories en pastilles et lecture directe en plein écran.
 */
@Composable
fun LiveScreen() {
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val toast = rememberToast()
    val wide = windowSize() != WindowSize.Compact
    var favVersion by remember { mutableIntStateOf(0) }
    val entries = rememberLoader(Unit) { repo.liveEntries() }
    var selected by remember { mutableStateOf<Category?>(null) }
    var previewing by remember { mutableStateOf<LiveChannel?>(null) }
    var focused by remember { mutableStateOf<LiveChannel?>(null) }
    var muted by remember { mutableStateOf(false) }
    val firstCategory = remember { FocusRequester() }
    val select = rememberCategoryGate { selected = it }

    fun toggleFavorite(ch: LiveChannel) {
        val fav = app.store.toggleFavorite(ch.toFavorite())
        toast(if (fav) "Ajoutée aux favoris" else "Retirée des favoris")
        favVersion++
    }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtvLogo(if (wide) 26.sp else 22.sp)
            Text("  |  TV en direct", fontSize = 18.sp, color = C.TextDim)
        }
        LoadView(entries) { list ->
            LaunchedEffect(Unit) {
                if (selected == null) selected = list.getOrNull(3)?.category ?: list.firstOrNull()?.category
                runCatching { firstCategory.requestFocus() }
            }
            val category = selected
            val channels = rememberLoader(category?.id) { category?.let { repo.liveIn(it.id) }.orEmpty() }

            val channelList: @Composable (Modifier) -> Unit = { m ->
                LoadView(channels, m) { chs ->
                    if (chs.isEmpty()) {
                        Box(m, contentAlignment = Alignment.Center) { Text("Aucune chaîne dans cette catégorie", color = C.TextDim) }
                    } else {
                        LazyColumn(m, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                            itemsIndexed(chs, key = { i, c -> "$i-${c.id}" }) { i, ch ->
                                ChannelRow(
                                    ch,
                                    favVersion = favVersion,
                                    isPreviewing = ch.id == previewing?.id,
                                    onFocus = { focused = ch },
                                    onLongClick = { toggleFavorite(ch) },
                                    onClick = {
                                        if (wide && previewing?.id != ch.id) {
                                            previewing = ch
                                        } else {
                                            previewing = null // libère la connexion avant le plein écran
                                            PlayQueue.start(context, chs.map { it.toPlayItem() }, i, live = true)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategorySidebar(list, category?.id, select, Modifier.width(260.dp).fillMaxHeight(), firstCategory)
                    channelList(Modifier.weight(1f).fillMaxHeight())
                    ChannelPanel(
                        channel = previewing ?: focused,
                        previewUrl = previewing?.streamUrl,
                        muted = muted,
                        onToggleMute = { muted = !muted },
                        onFullscreen = { ch ->
                            previewing = null
                            PlayQueue.start(context, listOf(ch.toPlayItem()), 0, live = true)
                        },
                        onFavorite = { toggleFavorite(it) },
                        modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    )
                }
            } else {
                CategoryChips(list, category?.id, select, Modifier.padding(top = 8.dp))
                channelList(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: LiveChannel, favVersion: Int, isPreviewing: Boolean, onFocus: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    val repo = App.instance.repository() ?: return
    val isFav = remember(ch.id, favVersion) { App.instance.store.isFavorite(Favorite.keyOf(ContentType.LIVE, ch.id)) }
    val now = rememberNow(60_000)
    val programs by produceState<List<EpgProgram>>(emptyList(), ch.id) { value = repo.nowNext(ch) }
    val current = programs.firstOrNull { now in it.start until it.end }
    FocusTile(
        onClick = onClick,
        onFocus = onFocus,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        selected = isPreviewing,
        normalColor = C.Surface,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2630)), contentAlignment = Alignment.Center) {
                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (current != null) {
                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = C.TextDim)
                    ThinProgress(programFraction(current, now), Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (ch.hasArchive) Icon(painterResource(R.drawable.ic_replay), contentDescription = "Replay", tint = C.TextDim, modifier = Modifier.size(16.dp))
            if (isFav) Text(" ★", color = C.Accent)
        }
    }
}

@Composable
private fun ChannelPanel(
    channel: LiveChannel?,
    previewUrl: String?,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onFullscreen: (LiveChannel) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
    modifier: Modifier,
) {
    val repo = App.instance.repository() ?: return
    val now = rememberNow()
    val epg by produceState<List<EpgProgram>?>(null, channel?.id) {
        value = null
        if (channel != null) {
            kotlinx.coroutines.delay(250) // évite une requête par chaîne pendant le défilement rapide
            value = runCatching { repo.epg(channel, 8) }.getOrDefault(emptyList())
        }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            VlcPreview(previewUrl, muted, Modifier.fillMaxSize())
            if (previewUrl == null) {
                Text(
                    if (channel == null) "Choisissez une chaîne" else "OK pour l'aperçu, OK à nouveau pour le plein écran",
                    Modifier.align(Alignment.Center).padding(16.dp),
                    color = C.TextDim,
                    fontSize = 13.sp,
                )
            }
        }
        if (channel != null) {
        Text(channel.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("⛶ Plein écran", primary = true) { onFullscreen(channel) }
            if (previewUrl != null) ActionButton(if (muted) "🔇" else "🔊") { onToggleMute() }
            ActionButton("★") { onFavorite(channel) }
        }
        val programs = epg
        when {
            programs == null -> Text("Chargement du guide…", color = C.TextDim)
            programs.isEmpty() -> Text("Aucune information de programme.", color = C.TextDim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(programs) { p -> ProgramRow(p, now) }
            }
        }
        }
    }
}

@Composable
private fun ProgramRow(p: EpgProgram, now: Long) {
    val onAir = now in p.start until p.end
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${formatHour(p.start)} - ${formatHour(p.end)}", color = if (onAir) C.Accent else C.TextDim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(p.title, Modifier.padding(start = 10.dp), fontWeight = if (onAir) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAir) ThinProgress(programFraction(p, now), Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (p.description.isNotBlank()) Text(p.description, color = C.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// --- Guide TV -------------------------------------------------------------------------------------

/** Programme en cours et suivant pour chaque chaîne d'une catégorie. */
@Composable
fun GuideScreen() {
    val repo = App.instance.repository() ?: return
    val context = LocalContext.current
    val wide = windowSize() != WindowSize.Compact
    val entries = rememberLoader(Unit) { repo.liveEntries() }
    var selected by remember { mutableStateOf<Category?>(null) }
    val firstCategory = remember { FocusRequester() }
    val select = rememberCategoryGate { selected = it }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        ScreenTitle("Guide TV")
        LoadView(entries) { list ->
            LaunchedEffect(Unit) {
                if (selected == null) selected = list.getOrNull(3)?.category ?: list.firstOrNull()?.category
                runCatching { firstCategory.requestFocus() }
            }
            val category = selected
            val channels = rememberLoader(category?.id) { category?.let { repo.liveIn(it.id) }.orEmpty() }
            val grid: @Composable (Modifier) -> Unit = { m ->
                LoadView(channels, m) { chs ->
                    LazyColumn(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(chs, key = { i, c -> "$i-${c.id}" }) { i, ch ->
                            GuideRow(ch, wide) { PlayQueue.start(context, chs.map { it.toPlayItem() }, i, live = true) }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategorySidebar(list, category?.id, select, Modifier.width(260.dp).fillMaxHeight(), firstCategory)
                    grid(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                CategoryChips(list, category?.id, select)
                grid(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun GuideRow(ch: LiveChannel, wide: Boolean, onClick: () -> Unit) {
    val repo = App.instance.repository() ?: return
    val now = rememberNow(60_000)
    val programs by produceState<List<EpgProgram>>(emptyList(), ch.id) { value = repo.nowNext(ch) }
    val current = programs.firstOrNull { now in it.start until it.end }
    val next = programs.firstOrNull { it.start >= (current?.end ?: now) }
    FocusTile(onClick = onClick, modifier = Modifier.fillMaxWidth(), normalColor = C.Surface, focusScale = 1.01f) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp, 36.dp))
            Text(
                ch.name,
                Modifier.width(if (wide) 190.dp else 110.dp).padding(horizontal = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(current?.let { "${formatHour(it.start)}  ${it.title}" } ?: "Pas d'information", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                current?.let { ThinProgress(programFraction(it, now), Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
            if (wide) {
                Text(
                    next?.let { "${formatHour(it.start)}  ${it.title}" } ?: "",
                    Modifier.weight(1f),
                    color = C.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// --- Replay ---------------------------------------------------------------------------------------

/** Catégories des chaînes disposant du replay, puis chaînes, puis émissions passées. */
@Composable
fun ReplayScreen(onChannel: (LiveChannel) -> Unit) {
    val repo = App.instance.repository() ?: return
    val compact = windowSize() == WindowSize.Compact
    val entries = rememberLoader(Unit) {
        repo.liveEntries(archiveOnly = true).filter { it.category.id == Category.ALL || (it.count ?: 0) > 0 }
    }
    var selected by remember { mutableStateOf<Category?>(null) }
    val select = rememberCategoryGate { selected = it }
    val first = remember { FocusRequester() }
    BackHandler(enabled = selected != null) { selected = null }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        ScreenTitle(selected?.let { "Replay · ${it.name}" } ?: "Replay")
        val category = selected
        if (category == null) {
            LoadView(entries) { list ->
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                if (list.size <= 1) {
                    Text("Aucune chaîne de votre abonnement ne propose le replay.", color = C.TextDim)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (compact) 1 else 2),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list) { e ->
                            FocusTile(
                                onClick = { select(e.category) },
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = if (e == list.first()) first else null,
                                normalColor = C.Surface2,
                                focusScale = 1.02f,
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_replay), contentDescription = null, tint = C.Accent, modifier = Modifier.size(26.dp))
                                    Text(e.category.name.uppercase(), Modifier.weight(1f).padding(horizontal = 14.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${e.count ?: 0}", color = C.TextDim)
                                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = C.TextDim)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val channels = rememberLoader(category.id) { repo.liveIn(category.id, archiveOnly = true) }
            val firstChannel = remember { FocusRequester() }
            LoadView(channels) { chs ->
                LaunchedEffect(Unit) { runCatching { firstChannel.requestFocus() } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (compact) 300.dp else 340.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chs) { ch ->
                        FocusTile(
                            onClick = { onChannel(ch) },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = if (ch == chs.first()) firstChannel else null,
                            normalColor = C.Surface,
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(60.dp, 40.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    if (ch.archiveDays > 0) Text("${ch.archiveDays} jours de replay", color = C.TextDim, fontSize = 12.sp)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = C.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Liste des émissions passées d'une chaîne, lisibles en replay. */
@Composable
fun CatchupScreen() {
    val channel = Selection.channel ?: return
    val repo = App.instance.repository() ?: return
    val context = LocalContext.current
    val programs = rememberLoader(channel.id) { repo.catchup(channel) }
    val first = remember { FocusRequester() }
    val compact = windowSize() == WindowSize.Compact

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp, 44.dp))
            Text("Replay · ${channel.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        LoadView(programs) { list ->
            if (list.isEmpty()) {
                Text("Aucune émission disponible en replay pour cette chaîne.", color = C.TextDim)
            } else {
                LaunchedEffect(list) { runCatching { first.requestFocus() } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list) { i, p ->
                        FocusTile(
                            onClick = {
                                val url = repo.catchupUrl(channel, p) ?: return@FocusTile
                                PlayQueue.start(context, listOf(PlayItem(url, "${channel.name} · ${p.title}", startAtMs = 0)), 0, live = false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = if (i == 0) first else null,
                            normalColor = C.Surface,
                            focusScale = 1.01f,
                        ) {
                            if (compact) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${formatDay(p.start)} · ${formatHour(p.start)} - ${formatHour(p.end)}", color = C.Accent, fontSize = 13.sp)
                                    Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(formatDay(p.start), Modifier.width(130.dp), color = C.TextDim)
                                    Text("${formatHour(p.start)} - ${formatHour(p.end)}", Modifier.width(130.dp), color = C.Accent)
                                    Column(Modifier.weight(1f)) {
                                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        if (p.description.isNotBlank()) Text(p.description, color = C.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
