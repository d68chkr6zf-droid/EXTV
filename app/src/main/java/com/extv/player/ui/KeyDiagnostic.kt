package com.extv.player.ui

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Diagnostic de la navigation (Paramètres) : derniers appuis reçus de la télécommande, images lentes
 * de l'écran, résolution et mémoire. Sert à comprendre un menu qui « saute des étapes » sur un appareil.
 */
object KeyLog {
    private const val MAX = 24
    private val lines = ArrayDeque<String>()
    private var lastDownAt = 0L
    val version = mutableIntStateOf(0)

    // Depuis le lancement : appuis reçus en retard (le fil principal était occupé) et images lentes.
    var downs = 0L
        private set
    var lateDowns = 0L
        private set
    var worstLateMs = 0L
        private set
    val frames = LongArray(3) // total, > 34 ms, > 100 ms
    private var monitoring = false

    /** Compte les images lentes de toute l'appli (appelé une fois par MainActivity). */
    fun startFrameMonitor() {
        if (monitoring) return
        monitoring = true
        var last = 0L
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (last != 0L) {
                    val ms = (t - last) / 1_000_000
                    // Une longue pause sans image (écran au repos, appli en arrière-plan) n'est pas comptée.
                    if (ms < 2_000) {
                        frames[0]++
                        if (ms > 34) frames[1]++
                        if (ms > 100) frames[2]++
                    }
                }
                last = t
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    /** Appelé par MainActivity pour chaque touche, avant tout traitement. */
    fun record(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return
        val now = SystemClock.uptimeMillis()
        val gap = if (e.action == KeyEvent.ACTION_DOWN) {
            val g = if (lastDownAt == 0L) 0 else now - lastDownAt
            lastDownAt = now
            "+${g}ms"
        } else {
            ""
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val late = (now - e.eventTime).coerceAtLeast(0)
            downs++
            if (late > 100) lateDowns++
            if (late > worstLateMs) worstLateMs = late
        }
        val dev = e.device
        val line = String.format(
            "%-6s %-4s rep=%-2d %7s  retard=%dms  dev=%d %s src=0x%x",
            KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_").take(6),
            if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP",
            e.repeatCount,
            gap,
            (now - e.eventTime).coerceAtLeast(0),
            e.deviceId,
            dev?.name?.take(22) ?: "?",
            e.source,
        )
        synchronized(lines) {
            lines.addFirst(line)
            while (lines.size > MAX) lines.removeLast()
        }
        version.intValue++
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}

@Composable
fun KeyDiagnosticScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val v by KeyLog.version
    // Rafraîchi deux fois par seconde (pour ne pas ralentir l'écran lui-même).
    val shown = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            shown.intValue++
        }
    }
    val display = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val d = (context as? Activity)?.windowManager?.defaultDisplay
            val mode = d?.mode
            val m = context.resources.displayMetrics
            "Interface ${m.widthPixels}×${m.heightPixels} (${m.densityDpi} dpi) · sortie ${mode?.physicalWidth}×${mode?.physicalHeight} " +
                "à ${"%.2f".format(mode?.refreshRate ?: 0f)} Hz"
        }.getOrDefault("?")
    }
    val rt = Runtime.getRuntime()
    val devices = remember {
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD || it.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD }
            .joinToString("  |  ") { "${it.id}: ${it.name.take(28)}" }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xF0101014)).focusRequester(focus).focusable()
            .padding(screenPadding()),
    ) {
        Column {
            Text("Diagnostic de la navigation", color = C.Accent, fontSize = 22.sp)
            Text(
                "Appuyez sur les flèches comme d'habitude, puis prenez une photo de cet écran. Retour pour fermer.",
                color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", fontSize = 14.sp)
            Text(display, fontSize = 14.sp)
            key(shown.intValue) {
                val mem = "Mémoire ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} / ${rt.maxMemory() / 1_048_576} Mo"
                val f = KeyLog.frames
                Text("Depuis le lancement : $mem · images lentes ${f[1]} (> 100 ms : ${f[2]}) sur ${f[0]}", fontSize = 14.sp)
                Text(
                    "Appuis traités en retard (> 100 ms) : ${KeyLog.lateDowns} sur ${KeyLog.downs} · pire retard ${KeyLog.worstLateMs} ms",
                    fontSize = 14.sp,
                )
            }
            Text("Appareils : $devices", color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            key(v) {
                KeyLog.snapshot().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}
