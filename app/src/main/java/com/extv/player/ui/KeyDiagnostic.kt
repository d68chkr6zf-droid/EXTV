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

    // --- Rapport envoyé automatiquement (accord de l'abonné, 11/11/2026) ----------------------------
    // Touches reçues, déplacements du focus et événements de pointeur, envoyés à ntfy.sh pour comprendre
    // les menus qui « sautent » sur certains boîtiers. Aucun identifiant ni mot de passe n'y figure.
    private const val REPORT_URL = "https://ntfy.sh/extv-diag-dc5e04a3609e"
    private const val REPORT_EVERY = 25 // flèches par rapport
    private const val MAX_REPORTS = 6 // par lancement
    private val events = ArrayList<String>()
    private var eventsStart = 0L
    private var arrowsInReport = 0
    private var reports = 0
    private var motionLogged = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sendNow = Runnable { flush() }

    private fun event(line: String) {
        if (reports >= MAX_REPORTS) return
        val now = SystemClock.uptimeMillis()
        if (events.isEmpty()) eventsStart = now
        if (events.size < 400) events += "${now - eventsStart} $line"
    }

    /** Une tuile de l'appli vient de prendre le focus (appelé par FocusTile). */
    fun focusGained() = event("FOCUS")

    fun motion(e: android.view.MotionEvent) {
        if (motionLogged > 60) return
        motionLogged++
        event("MOTION a=${e.actionMasked} src=0x${Integer.toHexString(e.source)} dev=${e.deviceId} x=${"%.2f".format(e.x)} y=${"%.2f".format(e.y)} hatX=${e.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)} hatY=${e.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)}")
    }

    private fun keyEvent(e: KeyEvent, dropped: Boolean) {
        val now = SystemClock.uptimeMillis()
        event(
            "${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}" +
                " rep=${e.repeatCount} retard=${(now - e.eventTime).coerceAtLeast(0)} dev=${e.deviceId} src=0x${Integer.toHexString(e.source)}" +
                " scan=${e.scanCode} flags=0x${Integer.toHexString(e.flags)}" + if (dropped) " IGNORE" else "",
        )
        val arrow = e.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
        if (arrow && e.action == KeyEvent.ACTION_DOWN) arrowsInReport++
        handler.removeCallbacks(sendNow)
        // Envoi après 2 s sans touche, une fois assez de flèches notées (la série d'appuis est complète).
        if (arrowsInReport >= REPORT_EVERY) handler.postDelayed(sendNow, 2_000)
    }

    private fun flush() {
        if (events.isEmpty() || reports >= MAX_REPORTS) return
        reports++
        val body = buildString {
            appendLine("EXTV ${com.extv.player.BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · rapport $reports")
            appendLine(header())
            appendLine("appuis=$downs en retard=$lateDowns pire=${worstLateMs}ms images=${frames[0]} >34ms=${frames[1]} >100ms=${frames[2]}")
            events.forEach { appendLine(it) }
        }
        events.clear()
        arrowsInReport = 0
        Thread {
            // ntfy.sh limite un message à 4 Ko : le rapport part en plusieurs morceaux si besoin.
            body.chunked(3_800).forEachIndexed { i, part ->
                runCatching {
                    val c = java.net.URL(REPORT_URL).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Title", "EXTV diag ${Build.MODEL} #$reports.${i + 1}")
                    c.outputStream.use { it.write(part.toByteArray()) }
                    c.inputStream.close()
                    c.disconnect()
                }
            }
        }.start()
    }

    /** Écran, accessibilité et périphériques d'entrée (une fois par rapport). */
    private fun header(): String = runCatching {
        val app = com.extv.player.App.instance
        val m = app.resources.displayMetrics
        @Suppress("DEPRECATION")
        val mode = (app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.mode
        val am = app.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .joinToString(",") { it.id }
        val anim = android.provider.Settings.Global.getFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val devices = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .joinToString(" | ") { "${it.id}:${it.name} v${it.vendorId}/p${it.productId} src=0x${Integer.toHexString(it.sources)} kb=${it.keyboardType}" }
        "écran ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi police=${app.resources.configuration.fontScale} sortie ${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate} " +
            "anim=$anim access=${am.isEnabled} touch=${am.isTouchExplorationEnabled} services=[$services] tactile=${app.resources.configuration.touchscreen}\nentrées: $devices"
    }.getOrDefault("?")

    /** Appelé par MainActivity pour chaque touche, avant tout traitement ([dropped] : ignorée par le filtre des flèches). */
    fun record(e: KeyEvent, dropped: Boolean = false) {
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return
        keyEvent(e, dropped)
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
