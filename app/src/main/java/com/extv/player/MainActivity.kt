package com.extv.player

import android.os.Bundle
import android.os.SystemClock
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

    // --- Télécommande : flèches régulières dans les menus -------------------------------------
    // Certaines télécommandes (ex. Formuler) envoient un appui en double, et une flèche maintenue
    // répète plus vite que l'écran ne suit : le focus « saute » des lignes. On ignore un second appui
    // de la même flèche reçu moins de DOUBLE_PRESS_MS après le précédent, et on espace les répétitions.
    private var lastArrowKey = 0
    private var lastArrowAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in ARROWS) {
            val now = SystemClock.uptimeMillis()
            val gap = now - lastArrowAt
            val sameKey = event.keyCode == lastArrowKey
            val drop = sameKey && if (event.repeatCount == 0) gap < DOUBLE_PRESS_MS else gap < REPEAT_MS
            if (drop) return true
            lastArrowKey = event.keyCode
            lastArrowAt = now
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        const val DOUBLE_PRESS_MS = 90L
        const val REPEAT_MS = 110L
    }
}
