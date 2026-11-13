package com.extv.player.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Flux direct (.ts) : quand la connexion au serveur se coupe ou reste muette, on la rouvre aussitôt
 * en arrière-plan et on recolle la suite du flux, pendant que l'image continue sur la réserve
 * (mémoire tampon). Sans cela, une coupure vidait la réserve puis rouvrait la chaîne (écran noir).
 *
 * Le lecteur voit un seul flux continu ; le format TS se resynchronise tout seul au raccord.
 * Après [MAX_ATTEMPTS] échecs d'affilée sans aucune donnée reçue, on rend la main au lecteur
 * (réouverture complète de la chaîne, comme avant).
 */
@OptIn(UnstableApi::class)
class LiveReconnectDataSource(
    private val upstream: DataSource,
    private val onReconnect: () -> Unit,
) : DataSource {

    private var reopenSpec: DataSpec? = null
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        // Pour rouvrir : toujours le direct depuis le début de la réponse du serveur.
        reopenSpec = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        upstream.open(dataSpec)
        upstreamOpen = true
        // Un direct n'a pas de fin : le lecteur ne doit pas en attendre une.
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var attempt = 0
        while (true) {
            if (upstreamOpen) {
                try {
                    val n = upstream.read(buffer, offset, length)
                    if (n != C.RESULT_END_OF_INPUT) return n
                } catch (e: IOException) {
                    // Fermeture du lecteur (changement de chaîne, sortie) : on n'insiste pas.
                    // Sinon : coupure ou serveur muet trop longtemps, on reconnecte.
                    if (Thread.currentThread().isInterrupted || attempt >= MAX_ATTEMPTS) throw e
                }
            }
            if (attempt >= MAX_ATTEMPTS) return C.RESULT_END_OF_INPUT
            attempt++
            reconnect(attempt)
        }
    }

    private fun reconnect(attempt: Int) {
        closeUpstream()
        try {
            Thread.sleep(minOf(250L * attempt, 2_000L))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
        try {
            upstream.open(reopenSpec ?: return)
            upstreamOpen = true
            onReconnect()
        } catch (e: IOException) {
            closeUpstream()
            if (Thread.currentThread().isInterrupted) throw e
        }
    }

    private fun closeUpstream() {
        upstreamOpen = false
        try {
            upstream.close()
        } catch (_: IOException) {
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        reopenSpec = null
        closeUpstream()
    }

    class Factory(private val upstream: DataSource.Factory, private val onReconnect: () -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveReconnectDataSource(upstream.createDataSource(), onReconnect)
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
