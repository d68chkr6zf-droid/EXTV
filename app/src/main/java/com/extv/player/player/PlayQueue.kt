package com.extv.player.player

import android.content.Context
import android.content.Intent

/**
 * @param startAtMs null = reprendre à la position enregistrée (si resumeKey), 0 = depuis le début.
 */
data class PlayItem(
    val url: String,
    val title: String,
    val resumeKey: String? = null,
    val startAtMs: Long? = null,
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
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
}
