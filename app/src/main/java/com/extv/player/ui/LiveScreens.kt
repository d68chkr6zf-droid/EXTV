package com.extv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.EpgProgram
import com.extv.player.data.Favorite
import com.extv.player.data.LiveChannel
import com.extv.player.data.lockedCategoryIds
import com.extv.player.data.toFavorite
import com.extv.player.player.PlayItem
import com.extv.player.player.PlayQueue
import kotlinx.coroutines.delay

fun LiveChannel.toPlayItem() = PlayItem(streamUrl, "$num · $name")

/**
 * TV en direct : catégories | chaînes | guide de la chaîne sélectionnée.
 * Avec archiveOnly = true, n'affiche que les chaînes disposant du rattrapage.
 */
@Composable
fun LiveScreen(archiveOnly: Boolean, onCatchup: (LiveChannel) -> Unit) {
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val categories = rememberLoader(archiveOnly) {
        listOf(Category(Category.ALL, if (archiveOnly) "Toutes (rattrapage)" else "Toutes les chaînes", ContentType.LIVE)) +
            repo.categories(ContentType.LIVE)
    }
    var selected by remember { mutableStateOf<Category?>(null) }
    var focusedChannel by remember { mutableStateOf<LiveChannel?>(null) }
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
            val channels = rememberLoader(category.id, archiveOnly) {
                val all = repo.liveChannels(category.id.takeUnless { it == Category.ALL })
                val locked = if (category.id == Category.ALL) repo.lockedCategoryIds(ContentType.LIVE) else emptySet()
                all.filter { it.categoryId !in locked && (!archiveOnly || it.hasArchive) }
            }
            LoadView(channels, Modifier.width(400.dp).fillMaxHeight()) { list ->
                if (list.isEmpty()) {
                    Box(Modifier.width(400.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("Aucune chaîne", color = C.TextDim)
                    }
                } else {
                    LazyColumn(
                        Modifier.width(400.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        itemsIndexed(list) { i, ch ->
                            ChannelRow(
                                ch,
                                onFocus = { focusedChannel = ch },
                                onClick = {
                                    if (archiveOnly) onCatchup(ch)
                                    else PlayQueue.start(context, list.map { it.toPlayItem() }, i, live = true)
                                },
                            )
                        }
                    }
                }
            }
        }

        ChannelInfoPanel(focusedChannel, onCatchup, Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp))
    }
}

@Composable
private fun ChannelRow(ch: LiveChannel, onFocus: () -> Unit, onClick: () -> Unit) {
    val isFav = remember(ch.id) { App.instance.store.isFavorite(Favorite.keyOf(ContentType.LIVE, ch.id)) }
    FocusTile(onClick = onClick, onFocus = onFocus, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.num}", Modifier.width(48.dp), color = C.TextDim, fontSize = 14.sp)
            AsyncImage(
                model = ch.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(56.dp, 36.dp),
            )
            Text(
                ch.name,
                Modifier.weight(1f).padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ch.hasArchive) Text("⏪", fontSize = 14.sp)
            if (isFav) Text(" ★", color = C.Accent)
        }
    }
}

@Composable
private fun ChannelInfoPanel(channel: LiveChannel?, onCatchup: (LiveChannel) -> Unit, modifier: Modifier) {
    if (channel == null) {
        Text("Sélectionnez une chaîne pour voir son programme.", modifier, color = C.TextDim)
        return
    }
    val repo = App.instance.repository() ?: return
    Column(modifier) {
        val store = App.instance.store
        val epg by produceState<List<EpgProgram>?>(null, channel.id) {
            delay(300) // évite une requête par chaîne pendant le défilement rapide
            value = runCatching { repo.epg(channel, 8) }.getOrDefault(emptyList())
        }
        var fav by remember(channel.id) { mutableStateOf(store.isFavorite(Favorite.keyOf(ContentType.LIVE, channel.id))) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(110.dp, 70.dp),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(channel.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                if (channel.hasArchive) {
                    Text("Rattrapage disponible${if (channel.archiveDays > 0) " (${channel.archiveDays} j)" else ""}", color = C.TextDim)
                }
            }
        }
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(if (fav) "★ Retirer des favoris" else "☆ Ajouter aux favoris") {
                fav = store.toggleFavorite(channel.toFavorite())
            }
            if (channel.hasArchive) ActionButton("⏪ Rattrapage") { onCatchup(channel) }
        }

        val programs = epg
        when {
            programs == null -> Text("Chargement du guide…", color = C.TextDim)
            programs.isEmpty() -> Text("Aucune information de programme.", color = C.TextDim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(programs) { p -> ProgramRow(p) }
            }
        }
    }
}

@Composable
private fun ProgramRow(p: EpgProgram) {
    val now = System.currentTimeMillis()
    val onAir = now in p.start until p.end
    Column(
        Modifier.fillMaxWidth()
            .background(if (onAir) C.Selected else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row {
            Text("${formatHour(p.start)} – ${formatHour(p.end)}", color = C.Accent, modifier = Modifier.width(130.dp))
            Text(p.title, fontWeight = if (onAir) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAir) {
            LinearProgressIndicator(
                progress = { ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp),
                color = C.Accent,
            )
            if (p.description.isNotBlank()) Text(p.description, color = C.TextDim, fontSize = 14.sp, maxLines = 4)
        }
    }
}

/** Liste des émissions passées d'une chaîne, lisibles en rattrapage. */
@Composable
fun CatchupScreen() {
    val channel = Selection.channel ?: return
    val repo = App.instance.repository() ?: return
    val context = LocalContext.current
    val programs = rememberLoader(channel.id) { repo.catchup(channel) }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(40.dp)) {
        ScreenTitle("Rattrapage · ${channel.name}")
        LoadView(programs) { list ->
            if (list.isEmpty()) {
                Text("Aucune émission disponible en rattrapage pour cette chaîne.", color = C.TextDim)
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
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(formatDay(p.start), Modifier.width(140.dp), color = C.TextDim)
                                Text("${formatHour(p.start)} – ${formatHour(p.end)}", Modifier.width(130.dp), color = C.Accent)
                                Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
