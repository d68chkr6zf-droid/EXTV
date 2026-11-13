package com.extv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl

/**
 * Réserve de démarrage réglable à la volée : après un changement de version de la chaîne (FHD → HD → SD
 * ou secours), l'image repart dès [fastStartUs] de vidéo reçue au lieu d'attendre la réserve normale
 * (8 s). Remis à 0 dès que la lecture a repris.
 */
@OptIn(UnstableApi::class)
class FastStartLoadControl(private val delegate: LoadControl) : LoadControl by delegate {
    @Volatile var fastStartUs: Long = 0L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val fast = fastStartUs
        if (fast > 0 && parameters.bufferedDurationUs >= fast) return true
        return delegate.shouldStartPlayback(parameters)
    }
}
