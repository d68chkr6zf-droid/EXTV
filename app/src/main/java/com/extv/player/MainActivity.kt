package com.extv.player

import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    }
}
