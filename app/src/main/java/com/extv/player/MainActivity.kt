package com.extv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.extv.player.ui.AppNav
import com.extv.player.ui.ExtvTheme
import com.extv.player.ui.StartLock
import com.extv.player.ui.UpdatePrompt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
