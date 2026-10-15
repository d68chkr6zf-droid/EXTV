package com.extv.player.player

import android.content.Context
import android.content.Intent
import com.extv.player.App
import com.extv.player.data.ContentType
import com.extv.player.data.Favorite

/**
 * @param startAtMs null = reprendre à la position enregistrée (si resumeKey), 0 = depuis le début.
 */
data class PlayItem(
    val url: String,
    val title: String,
    val resumeKey: String? = null,
    val startAtMs: Long? = null,
    /** Pour l'historique « Récemment regardé ». */
    val historyType: ContentType? = null,
    val historyId: String? = null,
    /**
     * Replay : adresse de l'émission démarrant à offsetMs. Les serveurs de replay ne permettent pas
     * d'avancer dans le flux, on relance donc la lecture à partir de la nouvelle heure.
     */
    val replayUrlAt: ((Long) -> String)? = null,
    /** Durée de l'émission (replay), pour la barre de progression. */
    val durationMs: Long? = null,
    /** Favori correspondant (appui long sur OK pendant la lecture). */
    val favorite: Favorite? = null,
)

/**
 * File de lecture passée au lecteur en mémoire : une liste de milliers de chaînes
 * dépasserait la taille maximale d'un Intent.
 */
object PlayQueue {
    var items: List<PlayItem> = emptyList()
        private set
    var index: Int = 0
        private set
    var live: Boolean = false
        private set

    fun start(context: Context, items: List<PlayItem>, index: Int, live: Boolean) {
        if (items.isEmpty()) return
        this.items = items
        this.index = index.coerceIn(items.indices)
        this.live = live
        val native = App.instance.store.playerEngine == "native"
        context.startActivity(Intent(context, if (native) NativePlayerActivity::class.java else PlayerActivity::class.java))
    }
}
