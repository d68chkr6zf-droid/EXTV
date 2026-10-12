package com.extv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.LiveChannel
import com.extv.player.data.normalized

/**
 * Multi-écrans : 2 ou 4 chaînes en même temps. Le son suit l'écran sélectionné.
 * Attention : chaque écran utilise une connexion de l'abonnement.
 */
@Composable
fun MultiScreen() {
    val compact = windowSize() == WindowSize.Compact
    var layout by remember { mutableIntStateOf(if (compact) 2 else 4) }
    val slots = remember { mutableStateListOf<LiveChannel?>(null, null, null, null) }
    var active by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("MULTI-ÉCRANS", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            FocusTile(onClick = { layout = 2 }, selected = layout == 2, shape = RoundedCornerShape(20.dp), normalColor = C.Surface2) {
                Text("2 écrans", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp)
            }
            Box(Modifier.size(8.dp))
            FocusTile(onClick = { layout = 4 }, selected = layout == 4, shape = RoundedCornerShape(20.dp), normalColor = C.Surface2) {
                Text("4 écrans", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp)
            }
        }
        Text(
            "OK sur un écran : activer son son. OK à nouveau : changer de chaîne. Chaque écran utilise une connexion de l'abonnement.",
            color = C.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        val slot: @Composable (Int, Modifier) -> Unit = { i, m ->
            val ch = slots[i]
            FocusTile(
                onClick = {
                    if (ch == null || active == i) picking = i else active = i
                },
                modifier = m,
                focusRequester = if (i == 0) first else null,
                selected = active == i && ch != null,
                normalColor = Color.Black,
                focusedColor = Color.Black,
                selectedColor = Color.Black,
                focusScale = 1f,
                shape = RoundedCornerShape(8.dp),
            ) {
                if (ch == null) {
                    Text("+  Ajouter une chaîne", Modifier.align(Alignment.Center), color = C.TextDim)
                } else {
                    VlcPreview(ch.streamUrl, muted = active != i, modifier = Modifier.fillMaxSize().padding(3.dp))
                    Text(
                        (if (active == i) "🔊 " else "") + ch.name,
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }
        }

        if (layout == 2) {
            if (compact) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slot(0, Modifier.weight(1f).fillMaxWidth())
                    slot(1, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    slot(0, Modifier.weight(1f).fillMaxHeight(0.6f))
                    slot(1, Modifier.weight(1f).fillMaxHeight(0.6f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slot(0, Modifier.weight(1f).fillMaxHeight())
                    slot(1, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slot(2, Modifier.weight(1f).fillMaxHeight())
                    slot(3, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    picking?.let { index ->
        ChannelPicker(
            onDismiss = { picking = null },
            onPick = { ch ->
                slots[index] = ch
                active = index
                picking = null
            },
        )
    }
}

@Composable
private fun ChannelPicker(onDismiss: () -> Unit, onPick: (LiveChannel) -> Unit) {
    val repo = App.instance.repository() ?: return
    val channels = rememberLoader(Unit) { repo.liveIn(Category.ALL) }
    var filter by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).background(C.Surface2, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Text("Choisir une chaîne", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            SearchField(filter, "Nom de la chaîne", Modifier.padding(bottom = 8.dp)) { filter = it }
            LoadView(channels, Modifier.fillMaxWidth().heightIn(min = 120.dp)) { all ->
                val shown = remember(all, filter) {
                    val f = filter.normalized().trim()
                    (if (f.isEmpty()) all else all.filter { it.name.normalized().contains(f) }).take(300)
                }
                LazyColumn {
                    items(shown) { ch ->
                        FocusTile(onClick = { onPick(ch) }, modifier = Modifier.fillMaxWidth(), normalColor = Color.Transparent, focusScale = 1f) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp, 32.dp))
                                Text(ch.name, Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
