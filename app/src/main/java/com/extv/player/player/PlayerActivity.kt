package com.extv.player.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extv.player.App
import com.extv.player.ui.C
import com.extv.player.ui.FocusTile
import com.extv.player.ui.ExtvTheme
import com.extv.player.ui.formatDuration
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Lecteur plein écran basé sur libVLC.
 * Télécommande : OK = pause / afficher l'interface, ◀ ▶ = reculer / avancer,
 * ▲ ▼ ou CH+/CH- = zapper (live), MENU = format d'image, pistes audio et sous-titres.
 */
class PlayerActivity : ComponentActivity() {

    private val store get() = App.instance.store

    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout

    private var items: MutableList<PlayItem> = mutableListOf()
    private var live = false
    private var pendingSeek = 0L
    private var scaleIndex = 0

    // État affiché par l'interface Compose
    private var index by mutableIntStateOf(0)
    private var overlayVisible by mutableStateOf(true)
    private var menuVisible by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(true)
    private var bufferPercent by mutableFloatStateOf(0f)
    private var timeMs by mutableLongStateOf(0L)
    private var lengthMs by mutableLongStateOf(0L)
    private var errorText by mutableStateOf<String?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlayVisible = false }
    private val retryLive = Runnable { playCurrent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        items = PlayQueue.items.toMutableList()
        if (items.isEmpty()) {
            finish()
            return
        }
        index = PlayQueue.index
        live = PlayQueue.live

        val options = arrayListOf(
            "--network-caching=${store.networkCaching}",
            "--live-caching=${store.networkCaching}",
            "--http-reconnect",
            "--audio-time-stretch",
            "-v",
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer.setEventListener { onVlcEvent(it) }

        videoLayout = VLCVideoLayout(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(videoLayout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            ComposeView(this).apply { setContent { ExtvTheme(background = false) { Overlay() } } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        mediaPlayer.attachViews(videoLayout, null, true, false)
        playCurrent()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        savePosition()
        mediaPlayer.stop()
        mediaPlayer.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.setEventListener(null)
            mediaPlayer.release()
            libVlc.release()
        }
    }

    // --- Lecture -----------------------------------------------------------------------------

    private fun playCurrent() {
        handler.removeCallbacks(retryLive)
        val item = items.getOrNull(index) ?: return
        errorText = null
        timeMs = 0
        lengthMs = 0
        isBuffering = true

        val media = Media(libVlc, Uri.parse(item.url))
        media.setHWDecoderEnabled(store.hwDecoding, false)
        media.addOption(":http-user-agent=${store.userAgent}")
        mediaPlayer.setMedia(media)
        media.release()

        pendingSeek = when {
            live -> 0L
            item.startAtMs != null -> item.startAtMs
            item.resumeKey != null -> store.position(item.resumeKey)
            else -> 0L
        }
        // Après un retour en arrière-plan, on reprend toujours à la position enregistrée.
        items[index] = item.copy(startAtMs = null)

        if (item.historyType != null && item.historyId != null) store.addHistory(item.historyType, item.historyId)

        mediaPlayer.play()
        showOverlay()
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                isPlaying = true
                isBuffering = false
                if (pendingSeek > 0) {
                    mediaPlayer.setTime(pendingSeek)
                    pendingSeek = 0
                }
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
            MediaPlayer.Event.Buffering -> {
                bufferPercent = e.buffering
                isBuffering = e.buffering < 100f
            }
            MediaPlayer.Event.TimeChanged -> timeMs = e.timeChanged
            MediaPlayer.Event.LengthChanged -> lengthMs = e.lengthChanged
            MediaPlayer.Event.EndReached -> handler.post { onEnd() }
            MediaPlayer.Event.EncounteredError -> {
                isBuffering = false
                errorText = "Lecture impossible. Vérifiez la connexion ou l'abonnement."
                if (live) handler.postDelayed(retryLive, 5000)
            }
        }
    }

    private fun onEnd() {
        if (live) {
            // Un flux live qui s'arrête est en général une coupure : on relance.
            handler.postDelayed(retryLive, 2000)
            return
        }
        items.getOrNull(index)?.resumeKey?.let { store.savePosition(it, 0) }
        if (index < items.lastIndex) {
            index++
            playCurrent()
        } else {
            finish()
        }
    }

    private fun savePosition() {
        if (live || !::mediaPlayer.isInitialized) return
        val key = items.getOrNull(index)?.resumeKey ?: return
        val length = lengthMs
        if (length <= 0) return
        val pos = mediaPlayer.time
        store.savePosition(key, if (pos > length * 0.95 || pos < 10_000) 0L else pos)
    }

    private fun togglePause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
        showOverlay()
    }

    private fun seekBy(delta: Long) {
        if (live || lengthMs <= 0) {
            showOverlay()
            return
        }
        val target = (mediaPlayer.time + delta).coerceIn(0L, (lengthMs - 1000).coerceAtLeast(0L))
        mediaPlayer.setTime(target)
        timeMs = target
        showOverlay()
    }

    private fun zap(delta: Int) {
        if (items.size < 2) return
        index = (index + delta).mod(items.size)
        playCurrent()
    }

    private fun cycleScale(): String {
        scaleIndex = (scaleIndex + 1) % SCALES.size
        mediaPlayer.setVideoScale(SCALES[scaleIndex].first)
        return SCALES[scaleIndex].second
    }

    private fun showOverlay() {
        overlayVisible = true
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4000)
    }

    // --- Télécommande ------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) menuVisible = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val code = event.keyCode
        if (code !in HANDLED_KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (event.repeatCount > 4) 30_000L else 10_000L
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (!overlayVisible) showOverlay() else togglePause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
                KeyEvent.KEYCODE_DPAD_UP -> if (live) zap(-1) else showOverlay()
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live) zap(1) else showOverlay()
                KeyEvent.KEYCODE_CHANNEL_UP -> zap(1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
                KeyEvent.KEYCODE_MEDIA_NEXT -> if (index < items.lastIndex || live) zap(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (index > 0 || live) zap(-1)
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    handler.removeCallbacks(hideOverlay)
                    menuVisible = true
                }
            }
        }
        return true
    }

    // --- Interface superposée ----------------------------------------------------------------

    @Composable
    private fun Overlay() {
        val item = items.getOrNull(index)
        val noRipple = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) {
                if (overlayVisible) overlayVisible = false else showOverlay()
            },
        ) {
            if (isBuffering && errorText == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = C.Accent)
                    if (bufferPercent > 0f) Text("${bufferPercent.toInt()} %", color = Color.White, fontSize = 14.sp)
                }
            }
            errorText?.let {
                Text(
                    it,
                    Modifier.align(Alignment.Center)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(24.dp),
                    color = Color.White,
                    fontSize = 18.sp,
                )
            }
            AnimatedVisibility(overlayVisible, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent)))
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                ) {
                    Text(item?.title ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (live) {
                        Text("Chaîne ${index + 1} / ${items.size}  ·  ▲ ▼ pour zapper  ·  MENU : options", color = C.TextDim, fontSize = 14.sp)
                    }
                }
            }
            // Commandes tactiles (smartphone / tablette) ; à la télécommande, les touches suffisent.
            AnimatedVisibility(overlayVisible, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (items.size > 1) RoundControl("⏮", 52) { zap(-1) }
                    if (!live) RoundControl("⏪", 52) { seekBy(-10_000) }
                    RoundControl(if (isPlaying) "⏸" else "▶", 72) { togglePause() }
                    if (!live) RoundControl("⏩", 52) { seekBy(10_000) }
                    if (items.size > 1) RoundControl("⏭", 52) { zap(1) }
                    RoundControl("⚙", 52) {
                        handler.removeCallbacks(hideOverlay)
                        menuVisible = true
                    }
                }
            }
            AnimatedVisibility(overlayVisible && !live, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = C.Accent,
                        trackColor = Color(0x55FFFFFF),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(formatDuration(timeMs), color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (isPlaying) "OK : pause  ·  ◀ ▶ : -10 s / +10 s  ·  MENU : options" else "En pause",
                            color = C.TextDim,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(lengthMs), color = Color.White)
                    }
                }
            }
            if (menuVisible) OptionsMenu(Modifier.align(Alignment.CenterEnd))
        }
    }

    @Composable
    private fun RoundControl(symbol: String, size: Int, onClick: () -> Unit) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = Color.White, fontSize = (size / 2.4).sp)
        }
    }

    @Composable
    private fun OptionsMenu(modifier: Modifier) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val audio = remember { mediaPlayer.audioTracks?.toList().orEmpty() }
        val subtitles = remember { mediaPlayer.spuTracks?.toList().orEmpty() }
        var currentAudio by remember { mutableIntStateOf(mediaPlayer.audioTrack) }
        var currentSpu by remember { mutableIntStateOf(mediaPlayer.spuTrack) }
        var scaleLabel by remember { mutableStateOf(SCALES[scaleIndex].second) }

        LazyColumn(
            modifier.width(380.dp).fillMaxHeight().background(Color(0xF010141C)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text("Format d'image", color = C.TextDim, fontSize = 14.sp) }
            item { MenuItem("↔  $scaleLabel", first) { scaleLabel = cycleScale() } }
            if (audio.isNotEmpty()) {
                item { Text("Audio", color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                items(audio) { t ->
                    MenuItem((if (t.id == currentAudio) "●  " else "○  ") + t.name) {
                        mediaPlayer.setAudioTrack(t.id)
                        currentAudio = t.id
                    }
                }
            }
            if (subtitles.isNotEmpty()) {
                item { Text("Sous-titres", color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                items(subtitles) { t ->
                    MenuItem((if (t.id == currentSpu) "●  " else "○  ") + t.name) {
                        mediaPlayer.setSpuTrack(t.id)
                        currentSpu = t.id
                    }
                }
            }
            item { MenuItem("Fermer") { menuVisible = false } }
        }
    }

    @Composable
    private fun MenuItem(label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
        FocusTile(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusRequester = focusRequester) {
            Text(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color.White)
        }
    }

    companion object {
        private val SCALES = listOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Automatique",
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Ajuster à l'écran",
            MediaPlayer.ScaleType.SURFACE_FILL to "Remplir",
            MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
            MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
            MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Taille d'origine",
        )

        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
        )
    }
}
