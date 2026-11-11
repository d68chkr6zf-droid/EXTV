package com.extv.player

import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.extv.player.ui.AppNav
import com.extv.player.ui.ExtvTheme
import com.extv.player.ui.StartLock
import com.extv.player.ui.UpdatePrompt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // EXTV est rouvert : la relance programmée pendant une mise à jour n'a plus lieu d'être.
        com.extv.player.data.Updater.cancelRestart(this)
        // Diagnostic de la navigation (Paramètres) : images lentes depuis le lancement.
        com.extv.player.ui.KeyLog.startFrameMonitor()
        val compose = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                ExtvTheme {
                    AppNav()
                    // Code au démarrage (si activé par l'abonné) ; la mise à jour obligatoire passe par-dessus.
                    StartLock()
                    // Vérifie les mises à jour sur tous les écrans de l'appli.
                    UpdatePrompt()
                }
            }
        }
        setContentView(QuietAccessibilityLayout(this).apply { addView(compose) })
    }

    override fun onResume() {
        super.onResume()
        useSmoothDisplayMode()
    }

    /**
     * Menus à 50 ou 60 Hz. Après un film, certains boîtiers (Formuler Z12) restent à 24 Hz, la fréquence du
     * film choisie par le lecteur : l'interface n'affichait plus que 24 images par seconde et suivait mal
     * la télécommande. On redemande ici, à résolution égale, le mode le plus fluide (60 Hz au plus).
     */
    private fun useSmoothDisplayMode() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = window.decorView.display ?: windowManager.defaultDisplay ?: return
            val current = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .filter { it.refreshRate in 47f..61f }
                .maxByOrNull { it.refreshRate } ?: return
            if (current.refreshRate >= 47f) return // déjà fluide : on garde le réglage du boîtier
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    /**
     * Conteneur de l'écran qui ne transmet pas les événements d'accessibilité de l'interface, sauf si un
     * lecteur d'écran (TalkBack) est actif. Sur le Formuler, le service de la télécommande est un service
     * d'accessibilité : chaque déplacement du focus lui était annoncé et l'appli prenait jusqu'à 1 s de
     * retard sur les touches (0,4 s au pire sur un boîtier sans ce service).
     */
    private class QuietAccessibilityLayout(context: android.content.Context) : android.widget.FrameLayout(context) {
        private val manager = context.getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager

        override fun requestSendAccessibilityEvent(child: android.view.View, event: android.view.accessibility.AccessibilityEvent): Boolean =
            if (manager.isTouchExplorationEnabled) super.requestSendAccessibilityEvent(child, event) else false
    }

    // --- Flèches : un seul déplacement du focus par image affichée ---------------------------------
    // Quand l'écran met du temps à se redessiner, les appuis reçus entre-temps étaient tous appliqués d'un
    // coup sur la liste, et le focus sautait plusieurs lignes à l'image suivante. Une flèche n'est donc
    // traitée que si le déplacement précédent a déjà été affiché ; une flèche maintenue avance au plus
    // toutes les REPEAT_MS.
    private var arrowPending = false
    private var lastRepeatAt = 0L
    private val arrowShown = Choreographer.FrameCallback { arrowPending = false }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val drop = event.action == KeyEvent.ACTION_DOWN && event.keyCode in ARROWS && arrowDrop(event)
        com.extv.player.ui.KeyLog.record(event, drop)
        if (drop) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in ARROWS) {
            arrowPending = true
            Choreographer.getInstance().postFrameCallback(arrowShown)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun arrowDrop(event: KeyEvent): Boolean {
        if (arrowPending) return true
        // Répétition pendant la première seconde : sur le Formuler, le service de la télécommande retient le
        // relâchement de la touche jusqu'à 1 s ; Android croit la touche maintenue et répète l'appui, ce qui
        // faisait sauter une ou deux cases. Une flèche vraiment maintenue répète au-delà de HOLD_MS.
        if (event.repeatCount > 0 && event.eventTime - event.downTime < HOLD_MS) return true
        if (event.repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatAt < REPEAT_MS) return true
            lastRepeatAt = now
        }
        return false
    }

    /** Événements « souris / pavé / manette » de la télécommande : notés pour le rapport de diagnostic. */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        com.extv.player.ui.KeyLog.motion(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    private companion object {
        val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        const val REPEAT_MS = 90L
        const val HOLD_MS = 1_200L
    }
}
