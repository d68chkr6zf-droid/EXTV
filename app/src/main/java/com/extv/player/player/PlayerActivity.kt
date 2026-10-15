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
import com.extv.player.data.ContentType
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

    // Appui long sur OK : ajoute/retire le programme en cours des favoris.
    private var okLongHandled = false
    private var favVersion by mutableIntStateOf(0)
    private var favMessage by mutableStateOf<String?>(null)
    private val hideFavMessage = Runnable { favMessage = null }

    private fun toggleFavorite() {
        val fav = items.getOrNull(index)?.favorite
        favMessage = if (fav == null) "Ce programme ne peut pas être mis en favori"
        else if (store.toggleFavorite(fav)) "★  ${fav.name} ajouté aux favoris"
        else "☆  ${fav.name} retiré des favoris"
        favVersion++
        handler.removeCallbacks(hideFavMessage)
        handler.postDelayed(hideFavMessage, 2_500)
    }
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
            // Flux en direct : ne pas jeter d'images quand l'horloge du serveur dérive (saccades).
            "--aout=${store.audioOutput}",
            // Langue préférée : français (piste audio et sous-titres quand le fichier les décrit).
            "--audio-language=fr,fre,fra,french,français",
            "--clock-jitter=0",
            "--clock-synchro=0",
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
        val item = items.getOrNull(index) ?: return
        frenchChosen = false
        errorText = null
        baseOffset = if (item.replayUrlAt != null) offset else 0L
        timeMs = baseOffset
        lengthMs = item.durationMs ?: 0
        isBuffering = true

        val url = if (item.replayUrlAt != null && baseOffset > 0) item.replayUrlAt.invoke(baseOffset) else item.url
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(store.hwDecoding, false)
        media.addOption(":http-user-agent=${store.userAgent}")
        if (store.deinterlace) {
            // Flux entrelacés (1080i) : on reconstitue 50 images/s à partir des 2 trames de chaque image.
            // Le décodage matériel doit alors rendre les images à VLC au lieu de les afficher directement.
            media.addOption(":no-mediacodec-dr")
            media.addOption(":deinterlace=-1")
            media.addOption(":deinterlace-mode=yadif2x")
        }
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

        if (item.historyType != null && item.historyId != null) {
            store.addHistory(item.historyType, item.historyId)
            if (item.historyType == ContentType.SERIES && item.resumeKey != null) store.setLastEpisode(item.historyId, item.resumeKey)
        }

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
                handler.postDelayed(ensureAudio, 1500)
            }
            MediaPlayer.Event.ESAdded -> {
                handler.removeCallbacks(ensureAudio)
                handler.postDelayed(ensureAudio, 800)
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
            MediaPlayer.Event.Buffering -> {
                bufferPercent = e.buffering
                isBuffering = e.buffering < 100f
            }
            MediaPlayer.Event.TimeChanged -> if (pendingTarget == null) timeMs = baseOffset + e.timeChanged
            MediaPlayer.Event.LengthChanged -> lengthMs = items.getOrNull(index)?.durationMs ?: e.lengthChanged
            MediaPlayer.Event.EndReached -> handler.post { onEnd() }
            MediaPlayer.Event.EncounteredError -> {
                isBuffering = false
                errorText = "Lecture impossible. Vérifiez la connexion ou l'abonnement."
                if (live) handler.postDelayed(retryLive, 5000)
            }
        }
    }

    /**
     * Certains fichiers (séries surtout) démarrent avec la piste audio désactivée ou sur une piste
     * illisible : on active la première piste audio disponible.
     */
    private var frenchChosen = false

    private val ensureAudio = Runnable {
        if (!::mediaPlayer.isInitialized) return@Runnable
        val tracks = mediaPlayer.audioTracks?.filter { it.id >= 0 }.orEmpty()
        // Français par défaut (une seule fois par vidéo, pour respecter un choix fait ensuite à la main).
        val french = tracks.firstOrNull { t -> FRENCH.any { t.name.orEmpty().lowercase().contains(it) } }
        if (!frenchChosen && french != null) {
            frenchChosen = true
            if (mediaPlayer.audioTrack != french.id) mediaPlayer.setAudioTrack(french.id)
        } else if (tracks.isNotEmpty() && tracks.none { it.id == mediaPlayer.audioTrack }) {
            mediaPlayer.setAudioTrack(tracks.first().id)
        }
        if (mediaPlayer.volume <= 0) mediaPlayer.setVolume(100)
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
        if (!live && items.getOrNull(index)?.replayUrlAt != null && lengthMs > 0) {
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
        // Info : ouverture au relâchement, sinon ce même relâchement refermerait aussitôt le menu.
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) {
            if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(hideOverlay)
                menuVisible = true
            }
            return true
        }
        // OK : appui court = action habituelle (au relâchement), appui long = favori.
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        okLongHandled = false
                    } else if (!okLongHandled && (event.isLongPress || event.repeatCount >= 2)) {
                        okLongHandled = true
                        toggleFavorite()
                    }
                }
                KeyEvent.ACTION_UP -> if (!okLongHandled && !event.isCanceled) {
                    when {
                        nextShown -> playNext()
                        !overlayVisible -> showOverlay()
                        else -> togglePause()
                    }
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (event.repeatCount > 4) 30_000L else 10_000L
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
                KeyEvent.KEYCODE_DPAD_UP -> if (live) zap(-1) else showOverlay()
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live) zap(1) else showOverlay()
                KeyEvent.KEYCODE_CHANNEL_UP -> zap(1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
                KeyEvent.KEYCODE_MEDIA_NEXT -> if (index < items.lastIndex || live) zap(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (index > 0 || live) zap(-1)
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
            favMessage?.let {
                Text(
                    it,
                    Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                        .background(Color(0xE6000000), RoundedCornerShape(24.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    color = C.Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            AnimatedVisibility(overlayVisible, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent)))
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                ) {
                    val isFav = remember(item, favVersion) { item?.favorite?.let { store.isFavorite(it.key) } == true }
                    Text((if (isFav) "★ " else "") + (item?.title ?: ""), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (live) {
                        Text("Chaîne ${index + 1} / ${items.size}  ·  ▲ ▼ pour zapper  ·  i : options", color = C.TextDim, fontSize = 14.sp)
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
        private const val NEXT_BUTTON_MS = 3 * 60_000L
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

/** Mots qui identifient une piste audio française dans le nom donné par VLC. */
private val FRENCH = listOf("fr", "français", "francais", "french", "vf", "vff", "vfq")
