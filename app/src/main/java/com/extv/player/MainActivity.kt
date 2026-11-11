package com.extv.player

import android.os.Bundle
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

    /** Chaque touche est notée pour le diagnostic de la navigation (Paramètres), sans rien changer à son effet. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.extv.player.ui.KeyLog.record(event)
        return super.dispatchKeyEvent(event)
    }
}
