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
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.extv.player.App
import com.extv.player.data.ContentType
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
                val replay = items.getOrNull(index)?.durationMs
                if (pendingTarget == null) timeMs = baseOffset + it.currentPosition.coerceAtLeast(0L)
                val d = it.duration
                lengthMs = replay ?: if (d == MC.TIME_UNSET || d < 0) 0L else d
            }
            if (overlayVisible) refreshTechInfo()
            handler.postDelayed(this, 500)
        }
    }

    // --- Fréquence d'affichage calée sur la vidéo (50 Hz pour le sport européen, 60 Hz sinon) -------------
    // Une vidéo à 25/50 images/s sur un écran à 60 Hz donne des saccades régulières (surtout en sport).
    @Volatile private var rateMatched = false
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var frameCount = 0

    private val frameListener = VideoFrameMetadataListener { ptsUs, _, format, _ ->
        if (rateMatched) return@VideoFrameMetadataListener
        if (format.frameRate > 0f) {
            rateMatched = true
            handler.post { matchRefreshRate(format.frameRate) }
            return@VideoFrameMetadataListener
        }
        // Débit d'images non indiqué dans le flux (fréquent en direct) : on le mesure sur 60 images.
        if (firstPtsUs < 0 || ptsUs <= lastPtsUs) {
            firstPtsUs = ptsUs
            frameCount = 0
        } else {
            frameCount++
        }
        lastPtsUs = ptsUs
        if (frameCount >= 60 && ptsUs > firstPtsUs) {
            rateMatched = true
            val fps = frameCount * 1_000_000f / (ptsUs - firstPtsUs)
            measuredFps = fps
            handler.post { matchRefreshRate(fps) }
        }
    }

    // Infos techniques affichées sous le titre (résolution, images/s, fréquence de l'écran, images perdues).
    @Volatile private var measuredFps = 0f
    private var droppedFrames by mutableIntStateOf(0)
    private var stalls by mutableIntStateOf(0)
    private var wasReady = false
    private var techInfo by mutableStateOf("")

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
        }
    }

    private fun refreshTechInfo() {
        val p = player ?: return
        val f = p.videoFormat
        @Suppress("DEPRECATION")
        val display = window.decorView.display ?: windowManager.defaultDisplay
        val hz = display?.refreshRate ?: 0f
        val parts = mutableListOf<String>()
        if (f != null) {
            parts += "${f.width}×${f.height}"
            val codec = when {
                f.sampleMimeType?.contains("hevc") == true -> "H.265"
                f.sampleMimeType?.contains("avc") == true -> "H.264"
                f.sampleMimeType?.contains("mpeg2") == true -> "MPEG-2"
                else -> f.sampleMimeType?.substringAfter('/') ?: "?"
            }
            parts += codec
            val fps = if (f.frameRate > 0f) f.frameRate else measuredFps
            if (fps > 0f) parts += "%.2f img/s".format(fps)
        }
        if (hz > 0f) parts += "écran %.2f Hz".format(hz)
        parts += "images perdues : $droppedFrames"
        parts += "arrêts : $stalls"
        parts += "tampon %.1f s".format(p.totalBufferedDuration / 1000f)
        techInfo = parts.joinToString("  ·  ")
    }

    private fun resetRateMatch() {
        measuredFps = 0f
        droppedFrames = 0
        stalls = 0
        wasReady = false
        firstPtsUs = -1L
        lastPtsUs = -1L
        frameCount = 0
        rateMatched = !store.matchFrameRate
    }

    /** Choisit, à résolution égale, le mode d'écran dont la fréquence est un multiple du débit de la vidéo. */
    private fun matchRefreshRate(fps: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        if (fps < 10f || fps > 121f || isFinishing) return
        @Suppress("DEPRECATION")
        val display = window.decorView.display ?: windowManager.defaultDisplay ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        fun score(rate: Float): Float? {
            val ratio = rate / fps
            val k = Math.round(ratio)
            if (k < 1 || k > 4) return null
            return if (Math.abs(ratio - k) < 0.012f * k) k.toFloat() else null
        }
        // On évite les modes très lents (25/30 Hz : scintillement, mouvement haché) : 25 img/s → 50 Hz.
        val matching = candidates.mapNotNull { mode -> score(mode.refreshRate)?.let { mode to it } }
        val best = (matching.filter { it.first.refreshRate >= 47f }.minByOrNull { it.second }
            ?: matching.maxByOrNull { it.first.refreshRate })?.first ?: return
        if (best.modeId == current.modeId) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
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
                setVideoFrameMetadataListener(frameListener)
                addAnalyticsListener(analytics)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                errorText = null
                wasReady = true
            }
            if (state == Player.STATE_BUFFERING && wasReady) stalls++
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

    /** Replay : position (dans l'émission) où démarre le flux en cours, et cible d'avance rapide en attente. */
    private var baseOffset = 0L
    private var pendingTarget by mutableStateOf<Long?>(null)
    private val applyReplaySeek = Runnable {
        val target = pendingTarget ?: return@Runnable
        pendingTarget = null
        playCurrent(target / 60_000 * 60_000)
    }

    private fun playCurrent(offset: Long = 0L) {
        handler.removeCallbacks(retryLive)
        handler.removeCallbacks(applyReplaySeek)
        pendingTarget = null
        val p = player ?: return
        val item = items.getOrNull(index) ?: return
        resetRateMatch()
        errorText = null
        baseOffset = if (item.replayUrlAt != null) offset else 0L
        timeMs = baseOffset
        lengthMs = item.durationMs ?: 0
        isBuffering = true

        val start = when {
            live -> 0L
            item.startAtMs != null -> item.startAtMs
            item.resumeKey != null -> store.position(item.resumeKey)
            else -> 0L
        }
        items[index] = item.copy(startAtMs = null)
        if (item.historyType != null && item.historyId != null) {
            store.addHistory(item.historyType, item.historyId)
            if (item.historyType == ContentType.SERIES && item.resumeKey != null) store.setLastEpisode(item.historyId, item.resumeKey)
        }

        val url = if (item.replayUrlAt != null && baseOffset > 0) item.replayUrlAt.invoke(baseOffset) else item.url
        p.setMediaItem(MediaItem.fromUri(url), if (item.replayUrlAt != null) 0L else start)
        p.prepare()
        p.playWhenReady = true
        showOverlay()
    }

    /** Bouton « Épisode suivant » : affiché pendant les [NEXT_BUTTON_MS] dernières millisecondes d'un épisode. */
    private val nextAvailable: Boolean
        get() = !live && index < items.lastIndex && lengthMs > 0 && lengthMs - timeMs in 1..NEXT_BUTTON_MS

    /** Épisode pour lequel l'utilisateur a choisi de regarder le générique (touche Retour). */
    private var creditsFor by mutableIntStateOf(-1)
    private val nextShown: Boolean get() = nextAvailable && creditsFor != index && !menuVisible

    private fun playNext() {
        items.getOrNull(index)?.resumeKey?.let { store.savePosition(it, 0) }
        if (index < items.lastIndex) {
            index++
            playCurrent()
        }
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
        if (p != null && !live && items.getOrNull(index)?.replayUrlAt != null && lengthMs > 0) {
            // Replay : on cumule les appuis puis on relance le flux à la nouvelle heure.
            // Pas d'une minute (5 min en maintenant la touche), le serveur ne gérant que des minutes entières.
            val step = (if (kotlin.math.abs(delta) >= 30_000) 300_000L else 60_000L) * (if (delta < 0) -1 else 1)
            val from = pendingTarget ?: (timeMs / 60_000 * 60_000)
            val target = (from + step).coerceIn(0L, (lengthMs - 60_000).coerceAtLeast(0L))
            pendingTarget = target
            timeMs = target
            handler.removeCallbacks(applyReplaySeek)
            handler.postDelayed(applyReplaySeek, 900)
            showOverlay()
            return
        }
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
        if (nextShown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Retour masque le bouton (pour finir l'épisode tranquillement).
            if (event.action == KeyEvent.ACTION_UP) creditsFor = index
            return true
        }
        if (menuVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
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
                    when {
                        nextShown -> playNext()
                        !overlayVisible -> showOverlay()
                        else -> togglePause()
                    }
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
                        Text("Chaîne ${index + 1} / ${items.size}  ·  ▲ ▼ pour zapper  ·  i : options", color = C.TextDim, fontSize = 14.sp)
                    }
                    if (techInfo.isNotEmpty()) Text(techInfo, color = C.TextDim, fontSize = 13.sp)
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
                            when {
                                pendingTarget != null -> "Reprise à ${formatDuration(timeMs)}…"
                                !isPlaying -> "En pause"
                                items.getOrNull(index)?.replayUrlAt != null -> "OK : pause  ·  ◀ ▶ : -1 min / +1 min (maintenir : 5 min)  ·  i : options"
                                else -> "OK : pause  ·  ◀ ▶ : -10 s / +10 s  ·  i : options"
                            },
                            color = C.TextDim,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(lengthMs), color = Color.White)
                    }
                }
            }
            if (nextShown) {
                // Bouton discret et semi-transparent pendant les 5 dernières minutes, sans compte à rebours.
                val next = items.getOrNull(index + 1)
                Column(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66000000))
                        .clickable { playNext() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text("▶  Épisode suivant", color = Color(0xDDFFFFFF), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        (next?.title?.let { "$it  ·  " } ?: "") + "OK",
                        color = Color(0x99FFFFFF),
                        fontSize = 12.sp,
                    )
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
        private const val NEXT_BUTTON_MS = 5 * 60_000L
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
