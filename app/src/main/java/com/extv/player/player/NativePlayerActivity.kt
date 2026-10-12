package com.extv.player.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.extv.player.App
import com.extv.player.ui.C
import com.extv.player.ui.ExtvTheme
import com.extv.player.ui.FocusTile
import com.extv.player.ui.formatDuration

/**
 * Lecteur plein écran basé sur le lecteur natif d'Android (ExoPlayer / Media3).
 * Mêmes commandes que le lecteur VLC : OK = pause, ◀ ▶ = reculer / avancer,
 * ▲ ▼ ou CH+/CH- = zapper (live), MENU = format d'image, pistes audio et sous-titres.
 */
@OptIn(UnstableApi::class)
class NativePlayerActivity : ComponentActivity() {

    private val store get() = App.instance.store

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private var items: MutableList<PlayItem> = mutableListOf()
    private var live = false
    private var scaleIndex = 0

    private var index by mutableIntStateOf(0)
    private var overlayVisible by mutableStateOf(true)
    private var menuVisible by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(true)
    private var timeMs by mutableLongStateOf(0L)
    private var lengthMs by mutableLongStateOf(0L)
    private var errorText by mutableStateOf<String?>(null)
    private var tracksVersion by mutableIntStateOf(0)

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlayVisible = false }
    private val retryLive = Runnable { playCurrent() }
    private val tick = object : Runnable {
        override fun run() {
            player?.let {
                timeMs = it.currentPosition.coerceAtLeast(0L)
                val d = it.duration
                lengthMs = if (d == MC.TIME_UNSET || d < 0) 0L else d
            }
            handler.postDelayed(this, 500)
        }
    }

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

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(false)
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            ComposeView(this).apply { setContent { ExtvTheme(background = false) { Overlay() } } },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (items.isEmpty()) return
        player = buildPlayer()
        playerView.player = player
        playCurrent()
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        savePosition()
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun buildPlayer(): ExoPlayer {
        // La « mémoire tampon réseau » des paramètres sert aussi au lecteur natif.
        val caching = store.networkCaching
        val minBuffer = maxOf(caching, 15_000)
        val maxBuffer = maxOf(minBuffer * 2, 50_000)
        val startBuffer = minOf(2_500, minBuffer)
        val rebuffer = minOf(maxOf(caching / 2, 5_000), minBuffer)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, startBuffer, rebuffer)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(store.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                // Français par défaut pour l'audio.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages("fr", "fre", "fra")
                    .build()
                addListener(listener)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) errorText = null
            if (state == Player.STATE_ENDED) handler.post { onEnd() }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracksVersion++
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (live && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            isBuffering = false
            errorText = "Lecture impossible. Vérifiez la connexion ou l'abonnement."
            if (live) handler.postDelayed(retryLive, 5000)
        }
    }

    // --- Lecture -----------------------------------------------------------------------------

    private fun playCurrent() {
        handler.removeCallbacks(retryLive)
        val p = player ?: return
        val item = items.getOrNull(index) ?: return
        errorText = null
        timeMs = 0
        lengthMs = 0
        isBuffering = true

        val start = when {
            live -> 0L
            item.startAtMs != null -> item.startAtMs
            item.resumeKey != null -> store.position(item.resumeKey)
            else -> 0L
        }
        items[index] = item.copy(startAtMs = null)
        if (item.historyType != null && item.historyId != null) store.addHistory(item.historyType, item.historyId)

        p.setMediaItem(MediaItem.fromUri(item.url), start)
        p.prepare()
        p.playWhenReady = true
        showOverlay()
    }

    private fun onEnd() {
        if (live) {
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
        val p = player ?: return
        if (live) return
        val key = items.getOrNull(index)?.resumeKey ?: return
        val length = p.duration
        if (length == MC.TIME_UNSET || length <= 0) return
        val pos = p.currentPosition
        store.savePosition(key, if (pos > length * 0.95 || pos < 10_000) 0L else pos)
    }

    private fun togglePause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        showOverlay()
    }

    private fun seekBy(delta: Long) {
        val p = player
        if (p == null || live || lengthMs <= 0) {
            showOverlay()
            return
        }
        val target = (p.currentPosition + delta).coerceIn(0L, (lengthMs - 1000).coerceAtLeast(0L))
        p.seekTo(target)
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
        playerView.resizeMode = SCALES[scaleIndex].first
        return SCALES[scaleIndex].second
    }

    private fun showOverlay() {
        overlayVisible = true
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4000)
    }

    // --- Pistes ------------------------------------------------------------------------------

    private class TrackChoice(val label: String, val selected: Boolean, val select: () -> Unit)

    @SuppressLint("UnsafeOptInUsageError")
    private fun trackChoices(type: Int): List<TrackChoice> {
        val p = player ?: return emptyList()
        val result = mutableListOf<TrackChoice>()
        var n = 0
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                val f = group.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.uppercase()).distinct().joinToString(" · ").ifEmpty { "Piste $n" }
                result += TrackChoice(label, group.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        return result
    }

    private fun disableSubtitles() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true)
            .build()
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
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = C.Accent)
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
        var refresh by remember { mutableIntStateOf(0) }
        val version = tracksVersion + refresh
        val audio = remember(version) { trackChoices(MC.TRACK_TYPE_AUDIO) }
        val subtitles = remember(version) { trackChoices(MC.TRACK_TYPE_TEXT) }
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
                    MenuItem((if (t.selected) "●  " else "○  ") + t.label) {
                        t.select()
                        refresh++
                    }
                }
            }
            if (subtitles.isNotEmpty()) {
                item { Text("Sous-titres", color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                item {
                    MenuItem((if (subtitles.none { it.selected }) "●  " else "○  ") + "Désactivés") {
                        disableSubtitles()
                        refresh++
                    }
                }
                items(subtitles) { t ->
                    MenuItem((if (t.selected) "●  " else "○  ") + t.label) {
                        t.select()
                        refresh++
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
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Automatique",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Ajuster à l'écran",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Remplir",
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Largeur fixe",
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "Hauteur fixe",
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
