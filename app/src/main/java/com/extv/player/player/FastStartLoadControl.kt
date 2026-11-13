package com.extv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Réserve de démarrage réglable à la volée : après un changement de version de la chaîne (FHD → HD → SD
 * ou secours), l'image repart dès [fastStartUs] de vidéo reçue au lieu d'attendre la réserve normale
 * (8 s). Remis à 0 dès que la lecture a repris.
 *
 * Toutes les méthodes appelées par le lecteur sont relayées explicitement : une délégation Kotlin
 * (« by ») ne relaie pas les méthodes par défaut de l'interface Java, qui lèvent alors une exception
 * (plantage au lancement d'une chaîne en 1.0.182 / 1.0.184).
 */
@OptIn(UnstableApi::class)
class FastStartLoadControl(private val delegate: LoadControl) : LoadControl {
    @Volatile var fastStartUs: Long = 0L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val fast = fastStartUs
        if (fast > 0 && parameters.bufferedDurationUs >= fast) return true
        return delegate.shouldStartPlayback(parameters)
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(): Allocator = delegate.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
}
