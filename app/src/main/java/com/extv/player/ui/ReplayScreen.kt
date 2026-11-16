package com.extv.player.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.extv.player.App
import com.extv.player.data.ContentType
import com.extv.player.data.EpgProgram
import com.extv.player.data.IptvRepository
import com.extv.player.data.LiveChannel
import com.extv.player.data.lockedCategoryIds
import com.extv.player.player.PlayItem
import com.extv.player.player.PlayQueue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// --- Replay : chaîne + calendrier -----------------------------------------------------------------
//
// TV / tablette : chaînes (favorites d'abord) | calendrier des jours + émissions de la journée
// découpée en moments (soirée en premier) | fiche de l'émission avec Reprendre / Depuis le début.
// Smartphone : chaînes et jours en rangées défilantes, puis la liste (appui long = depuis le début).

/** Groupe de chaînes de la colonne de gauche. */
data class ReplaySection(val title: String, val channels: List<LiveChannel>)

/** Chaînes avec replay : favorites dans l'ordre des favoris, puis par catégorie (verrouillées masquées). */
suspend fun IptvRepository.replaySections(): List<ReplaySection> {
    val locked = lockedCategoryIds(ContentType.LIVE)
    val all = liveChannels(null).filter { it.hasArchive && it.categoryId !in locked }
    val byId = all.associateBy { it.id }
    val favorites = App.instance.store.favorites().filter { it.type == ContentType.LIVE }.mapNotNull { byId[it.id] }.distinctBy { it.id }
    val favoriteIds = favorites.map { it.id }.toSet()
    val byCategory = all.filter { it.id !in favoriteIds }.groupBy { it.categoryId }
    val categories = runCatching { categories(ContentType.LIVE) }.getOrDefault(emptyList())
    return buildList {
        if (favorites.isNotEmpty()) add(ReplaySection("★ Mes chaînes", favorites))
        categories.forEach { c -> byCategory[c.id]?.let { add(ReplaySection(c.name, it)) } }
        val known = categories.map { it.id }.toSet()
        val others = byCategory.filterKeys { it !in known }.values.flatten()
        if (others.isNotEmpty()) add(ReplaySection("Autres chaînes", others))
    }
}

private const val DAY_MS = 24 * 60 * 60_000L

private fun calendarOf(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

private fun dayStart(ms: Long): Long = calendarOf(ms).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Moment de la journée, pour regrouper les émissions. */
private fun momentOf(ms: Long): String = when (calendarOf(ms).get(Calendar.HOUR_OF_DAY)) {
    in 19..23 -> "SOIRÉE"
    in 12..18 -> "APRÈS-MIDI"
    in 5..11 -> "MATIN"
    else -> "NUIT"
}

/** Émission de première partie de soirée (vers 21 h, au moins 45 min). */
private fun isPrime(p: EpgProgram): Boolean {
    val c = calendarOf(p.start)
    val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    return minutes in (20 * 60 + 40)..(21 * 60 + 20) && p.end - p.start >= 45 * 60_000
}

private fun durationLabel(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${String.format(Locale.FRANCE, "%02d", min % 60)}" else "$min min"
}

/** Clé de reprise d'une émission en replay (position enregistrée par le lecteur). */
fun replayResumeKey(ch: LiveChannel, p: EpgProgram) = "replay:${ch.id}:${p.start}"

private fun playReplay(context: Context, repo: IptvRepository, ch: LiveChannel, p: EpgProgram, fromStart: Boolean) {
    val url = repo.catchupUrl(ch, p) ?: return
    val seekable = repo.catchupUrlAt(ch, p, 0) != null
    val item = PlayItem(
        url,
        "${ch.name} · ${p.title}",
        // La reprise n'est possible que si le serveur sait démarrer le replay à une heure donnée.
        resumeKey = if (seekable) replayResumeKey(ch, p) else null,
        startAtMs = if (fromStart || !seekable) 0L else null,
        replayUrlAt = if (seekable) { offset -> repo.catchupUrlAt(ch, p, offset) ?: url } else null,
        durationMs = (p.end - p.start).takeIf { seekable && it > 0 },
    )
    PlayQueue.start(context, listOf(item), 0, live = false)
}

@Composable
fun ReplayScreen() {
    val repo = App.instance.repository() ?: return
    val sections = rememberLoader(Unit) { repo.replaySections() }
    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        ScreenTitle("Replay")
        LoadView(sections) { list ->
            if (list.isEmpty() || list.all { it.channels.isEmpty() }) {
                Text("Aucune chaîne de votre abonnement ne propose le replay.", color = C.TextDim)
            } else {
                ReplayBody(list.filter { it.channels.isNotEmpty() })
            }
        }
    }
}

@Composable
private fun ReplayBody(sections: List<ReplaySection>) {
    val repo = App.instance.repository() ?: return
    val store = App.instance.store
    val context = LocalContext.current
    val compact = windowSize() == WindowSize.Compact
    val firstChannel = sections.first().channels.first()

    // Chaîne sous le focus, et chaîne affichée (chargée après une courte pause pour ne pas
    // interroger le serveur à chaque chaîne survolée).
    var focusedChannel by remember { mutableStateOf(firstChannel) }
    var channel by remember { mutableStateOf(firstChannel) }
    LaunchedEffect(focusedChannel) {
        if (focusedChannel.id != channel.id) {
            delay(400)
            channel = focusedChannel
        }
    }
    val programs = rememberLoader(channel.id) { repo.catchup(channel) }
    var focusedProgram by remember { mutableStateOf<EpgProgram?>(null) }
    LaunchedEffect(channel.id) { focusedProgram = null }

    // Jour choisi : gardé d'une chaîne à l'autre (pratique pour parcourir « hier soir » chaîne par chaîne).
    var day by remember { mutableStateOf<Long?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val firstChannelFocus = remember { FocusRequester() }
    val dayFocus = remember { FocusRequester() }
    val programFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstChannelFocus.requestFocus() } }

    val all = programs.value.orEmpty()
    val byDay = remember(all) { all.groupBy { dayStart(it.start) } }
    val days = remember(byDay) { byDay.keys.sortedDescending() }
    val shownDay = day?.takeIf { it in byDay } ?: days.firstOrNull()
    val dayPrograms = shownDay?.let { byDay[it] }.orEmpty().sortedByDescending { it.start }
    val positions = remember(dayPrograms, resumeTick, channel.id) {
        dayPrograms.associate { it.start to store.position(replayResumeKey(channel, it)) }
    }
    val selected = focusedProgram?.takeIf { it in dayPrograms } ?: dayPrograms.firstOrNull()

    // Thème (catégorie) affiché dans la colonne des chaînes, choisi dans une liste déroulante.
    var sectionIndex by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var refocusChannels by remember { mutableIntStateOf(0) }
    val section = sections[sectionIndex.coerceIn(sections.indices)]
    val sectionFirst = section.channels.first()
    val pickerFocus = remember { FocusRequester() }
    BackHandler(enabled = picking) { picking = false }
    LaunchedEffect(picking) { if (picking) runCatching { pickerFocus.requestFocus() } }
    LaunchedEffect(refocusChannels) { if (refocusChannels > 0) runCatching { firstChannelFocus.requestFocus() } }
    val chooseSection: (Int) -> Unit = { i ->
        sectionIndex = i
        picking = false
        focusedChannel = sections[i].channels.first()
        refocusChannels++
    }

    val themeHeader: @Composable (Modifier) -> Unit = { m ->
        FocusTile(
            onClick = { picking = !picking },
            modifier = m,
            normalColor = C.Surface2,
            focusScale = 1.02f,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    section.title.uppercase(),
                    Modifier.weight(1f).focusMarquee(),
                    color = C.Accent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (picking) "▲" else "▼", color = C.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    val themeItem: @Composable (Int, ReplaySection, Modifier) -> Unit = { i, s, m ->
        FocusTile(
            onClick = { chooseSection(i) },
            modifier = m,
            focusRequester = if (i == sectionIndex) pickerFocus else null,
            selected = i == sectionIndex,
            normalColor = C.Surface,
            focusScale = 1.02f,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title.uppercase(),
                    Modifier.weight(1f, fill = false).focusMarquee(),
                    color = if (i == sectionIndex) C.Accent else C.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${s.channels.size}", color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    val channelList: @Composable (Modifier) -> Unit = { m ->
        if (compact) {
            Column(m) {
                themeHeader(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (picking) {
                        sections.forEachIndexed { i, s -> item(key = "t$i") { themeItem(i, s, Modifier) } }
                    } else {
                        items(section.channels, key = { "c" + it.id }) { ch ->
                            FocusTile(
                                onClick = { focusedChannel = ch; channel = ch },
                                focusRequester = if (ch == sectionFirst) firstChannelFocus else null,
                                selected = ch.id == channel.id,
                                normalColor = C.Surface,
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(ch, Modifier.size(42.dp, 28.dp))
                                    Text(ch.name, Modifier.padding(start = 8.dp).width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(m) {
                themeHeader(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (picking) {
                        sections.forEachIndexed { i, s -> item(key = "t$i") { themeItem(i, s, Modifier.fillMaxWidth()) } }
                    } else {
                        items(section.channels, key = { "c" + it.id }) { ch ->
                            FocusTile(
                                onClick = {
                                    focusedChannel = ch
                                    channel = ch
                                    runCatching { dayFocus.requestFocus() }
                                },
                                onFocus = { focusedChannel = ch },
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = if (ch == sectionFirst) firstChannelFocus else null,
                                selected = ch.id == focusedChannel.id,
                                normalColor = C.Surface,
                                focusScale = 1.02f,
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(ch, Modifier.size(48.dp, 32.dp))
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(ch.name, Modifier.focusMarquee(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        if (ch.archiveDays > 0) Text("${ch.archiveDays} jours", color = C.TextDim, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dayStrip: @Composable () -> Unit = {
        val today = dayStart(System.currentTimeMillis())
        val weekday = remember { SimpleDateFormat("EEE", Locale.FRANCE) }
        LazyRow(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days, key = { it }) { d ->
                val on = d == shownDay
                FocusTile(
                    onClick = {
                        day = d
                        runCatching { programFocus.requestFocus() }
                    },
                    onFocus = { day = d },
                    focusRequester = if (on) dayFocus else null,
                    selected = on,
                    normalColor = C.Surface,
                    selectedColor = C.Accent,
                    focusedColor = if (on) Color(0xFFFFD27A) else C.SurfaceFocus,
                ) {
                    val ink = if (on) Color(0xFF1A1208) else C.Text
                    Column(Modifier.width(54.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(weekday.format(Date(d)).uppercase().trimEnd('.') + ".", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (on) ink else C.TextDim)
                        Text(calendarOf(d).get(Calendar.DAY_OF_MONTH).toString(), fontSize = 20.sp, fontWeight = FontWeight.Black, color = ink)
                        val label = when ((today - d + DAY_MS / 2) / DAY_MS) {
                            0L -> "auj."
                            1L -> "hier"
                            else -> " "
                        }
                        Text(label, fontSize = 9.sp, color = if (on) ink else C.TextDim)
                    }
                }
            }
        }
    }

    val programList: @Composable (Modifier) -> Unit = { m ->
        LoadView(programs, m) { _ ->
            if (dayPrograms.isEmpty()) {
                Text("Aucune émission disponible en replay pour cette chaîne.", color = C.TextDim, modifier = Modifier.padding(top = 12.dp))
            } else {
                Column(m) {
                    dayStrip()
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        var lastMoment: String? = null
                        dayPrograms.forEachIndexed { i, p ->
                            val moment = momentOf(p.start)
                            if (moment != lastMoment) {
                                lastMoment = moment
                                item(key = "m$moment") {
                                    Text(moment, Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp), color = C.Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                                }
                            }
                            item(key = "p${p.start}_$i") {
                                val pos = positions[p.start] ?: 0L
                                FocusTile(
                                    onClick = { playReplay(context, repo, channel, p, fromStart = pos <= 0) },
                                    onLongClick = { playReplay(context, repo, channel, p, fromStart = true) },
                                    onFocus = { focusedProgram = p },
                                    modifier = Modifier.fillMaxWidth(),
                                    focusRequester = if (i == 0) programFocus else null,
                                    normalColor = C.Surface,
                                    focusScale = 1.01f,
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(formatHour(p.start), Modifier.width(52.dp), color = C.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text(p.title, Modifier.weight(1f).focusMarquee(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            if (isPrime(p)) {
                                                Text(
                                                    "PRIME",
                                                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(C.Velvet).padding(horizontal = 5.dp, vertical = 1.dp),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                )
                                            }
                                            Text(durationLabel(p.end - p.start), Modifier.padding(start = 10.dp), color = C.TextDim, fontSize = 12.sp)
                                        }
                                        if (pos > 0) ThinProgress(pos.toFloat() / (p.end - p.start).coerceAtLeast(1), Modifier.fillMaxWidth().padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (compact) {
        Column(Modifier.fillMaxSize()) {
            channelList(Modifier.fillMaxWidth().padding(bottom = 10.dp))
            programList(Modifier.fillMaxWidth().weight(1f))
        }
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        channelList(Modifier.width(190.dp).fillMaxHeight())
        programList(Modifier.weight(1f).fillMaxHeight())
        ReplayCard(channel, selected, selected?.let { positions[it.start] } ?: 0L, Modifier.width(250.dp).fillMaxHeight()) { p, fromStart ->
            playReplay(context, repo, channel, p, fromStart)
        }
    }
}

/** Fiche de l'émission sélectionnée, avec les boutons de lecture. */
@Composable
private fun ReplayCard(
    channel: LiveChannel,
    program: EpgProgram?,
    position: Long,
    modifier: Modifier,
    onPlay: (EpgProgram, Boolean) -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Surface)) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).background(Brush.linearGradient(placeholderGradient(channel.name))),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel, Modifier.size(110.dp, 64.dp))
        }
        if (program == null) {
            Text(channel.name, Modifier.padding(14.dp), fontWeight = FontWeight.Bold)
        } else Column(Modifier.padding(14.dp)) {
            Text(
                "${channel.name} · ${formatDay(program.start)} · ${formatHour(program.start)} – ${formatHour(program.end)}".uppercase(),
                color = C.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(program.title, Modifier.padding(top = 6.dp), fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
            if (program.description.isNotBlank()) {
                Text(program.description, Modifier.padding(top = 6.dp), color = C.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (channel.archiveDays > 0) {
                val left = ((program.start + channel.archiveDays * DAY_MS - System.currentTimeMillis()) / DAY_MS).coerceAtLeast(0)
                Text(
                    when (left) {
                        0L -> "Disponible encore aujourd'hui"
                        1L -> "Disponible encore 1 jour"
                        else -> "Disponible encore $left jours"
                    },
                    Modifier.padding(top = 8.dp),
                    color = C.TextDim,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (position > 0) {
                ActionButton("▶  Reprendre à ${durationLabel(position)}", Modifier.fillMaxWidth(), primary = true) { onPlay(program, false) }
                Spacer(Modifier.height(8.dp))
                ActionButton("Depuis le début", Modifier.fillMaxWidth()) { onPlay(program, true) }
            } else {
                ActionButton("▶  Regarder", Modifier.fillMaxWidth(), primary = true) { onPlay(program, true) }
            }
        }
    }
}
