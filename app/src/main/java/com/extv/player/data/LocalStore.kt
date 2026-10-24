package com.extv.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage local simple (SharedPreferences + JSON) : profils, favoris, positions de lecture,
 * contrôle parental et réglages du lecteur. Favoris, positions et verrous sont propres à chaque profil.
 */
class LocalStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("extv", Context.MODE_PRIVATE))

    // --- Profils -----------------------------------------------------------------------------

    fun profiles(): List<Profile> = readArray("profiles").map { Profile.fromJson(it) }

    fun saveProfile(p: Profile) {
        val list = profiles().filterNot { it.id == p.id } + p
        writeArray("profiles", list.map { it.toJson() })
    }

    fun deleteProfile(id: String) {
        writeArray("profiles", profiles().filterNot { it.id == id }.map { it.toJson() })
        prefs.edit().remove("fav_$id").remove("pos_$id").remove("locked_$id").apply()
        if (activeProfileId == id) activeProfileId = null
    }

    var activeProfileId: String?
        get() = prefs.getString("active", null)
        set(value) = prefs.edit().putString("active", value).apply()

    fun activeProfile(): Profile? = activeProfileId?.let { id -> profiles().find { it.id == id } }

    private fun scoped(key: String) = "${key}_${activeProfileId}"

    // --- Favoris -----------------------------------------------------------------------------

    fun favorites(): List<Favorite> = readArray(scoped("fav")).mapNotNull { runCatching { Favorite.fromJson(it) }.getOrNull() }

    fun isFavorite(key: String) = favorites().any { it.key == key }

    /** Ajoute ou retire le favori ; renvoie true s'il est désormais en favori. */
    fun toggleFavorite(f: Favorite): Boolean {
        val list = favorites()
        val exists = list.any { it.key == f.key }
        val updated = if (exists) list.filterNot { it.key == f.key } else list + f
        writeArray(scoped("fav"), updated.map { it.toJson() })
        return !exists
    }

    /**
     * Range les favoris d'un type dans l'ordre donné (identifiants). Les favoris absents de la liste
     * (catégorie verrouillée, chaîne disparue…) gardent leur place.
     */
    fun reorderFavorites(type: ContentType, orderedIds: List<String>) {
        val list = favorites().toMutableList()
        val wanted = orderedIds.toHashSet()
        val slots = list.indices.filter { list[it].type == type && list[it].id in wanted }
        val byId = slots.associate { list[it].id to list[it] }
        val ordered = orderedIds.distinct().mapNotNull { byId[it] }
        if (ordered.size != slots.size) return
        slots.forEachIndexed { n, i -> list[i] = ordered[n] }
        writeArray(scoped("fav"), list.map { it.toJson() })
    }

    /** Favoris du compte actif au format JSON, pour la sauvegarde sur le boîtier. */
    fun exportFavorites(): String =
        JSONObject().put("app", "EXTV").put("version", 1).put("favorites", JSONArray(favorites().map { it.toJson() })).toString(2)

    /** Réintègre des favoris sauvegardés (sans doublons) ; renvoie le nombre de favoris ajoutés. */
    fun importFavorites(json: String): Int {
        val array = JSONObject(json).getJSONArray("favorites")
        val incoming = (0 until array.length()).mapNotNull { i -> runCatching { Favorite.fromJson(array.getJSONObject(i)) }.getOrNull() }
        val current = favorites()
        val keys = current.map { it.key }.toHashSet()
        val added = incoming.filter { keys.add(it.key) }
        writeArray(scoped("fav"), (current + added).map { it.toJson() })
        return added.size
    }

    // --- Historique (récemment regardé) ------------------------------------------------------

    fun history(type: ContentType): List<String> {
        val raw = prefs.getString(scoped("hist_${type.name}"), null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun addHistory(type: ContentType, id: String) {
        val list = (listOf(id) + history(type).filterNot { it == id }).take(60)
        val a = JSONArray()
        list.forEach { a.put(it) }
        prefs.edit().putString(scoped("hist_${type.name}"), a.toString()).apply()
    }

    // --- Reprise de lecture ------------------------------------------------------------------

    fun position(key: String): Long = readObject(scoped("pos")).optLong(key, 0L)

    fun savePosition(key: String, ms: Long) {
        val o = readObject(scoped("pos"))
        if (ms <= 0) o.remove(key) else o.put(key, ms)
        prefs.edit().putString(scoped("pos"), o.toString()).apply()
    }

    /** Dernier épisode lancé pour chaque série (clé de reprise "ep:<id>"). */
    fun lastEpisode(seriesId: String): String? =
        readObject(scoped("lastEp")).optString(seriesId, "").ifEmpty { null }

    fun setLastEpisode(seriesId: String, episodeKey: String) {
        val o = readObject(scoped("lastEp"))
        o.put(seriesId, episodeKey)
        prefs.edit().putString(scoped("lastEp"), o.toString()).apply()
    }

    /** Films commencés et séries entamées, du plus récent au plus ancien. */
    fun inProgress(type: ContentType): List<String> = when (type) {
        ContentType.MOVIE -> history(type).filter { position("movie:$it") > 0 }
        ContentType.SERIES -> history(type).filter { lastEpisode(it) != null }
        else -> emptyList()
    }

    // --- Contrôle parental -------------------------------------------------------------------

    var parentalPin: String
        get() = prefs.getString("pin", "0000") ?: "0000"
        set(value) = prefs.edit().putString("pin", value).apply()

    /** Verrouillage d'EXTV au démarrage par un code à 4 chiffres choisi par l'abonné (désactivé par défaut). */
    var startLock: Boolean
        get() = prefs.getBoolean("startLock", false) && startPin.length == 4
        set(value) = prefs.edit().putBoolean("startLock", value).apply()

    var startPin: String
        get() = prefs.getString("startPin", "") ?: ""
        set(value) = prefs.edit().putString("startPin", value).apply()

    var autoLockAdult: Boolean
        get() = prefs.getBoolean("autoLockAdult", true)
        set(value) = prefs.edit().putBoolean("autoLockAdult", value).apply()

    fun lockedCategories(): Set<String> = prefs.getStringSet(scoped("locked"), emptySet())?.toSet() ?: emptySet()

    fun setLocked(categoryKey: String, locked: Boolean) {
        val s = lockedCategories().toMutableSet()
        if (locked) s += categoryKey else s -= categoryKey
        prefs.edit().putStringSet(scoped("locked"), s).apply()
    }

    // --- Lecteur -----------------------------------------------------------------------------

    var hwDecoding: Boolean
        get() = prefs.getBoolean("hw", true)
        set(value) = prefs.edit().putBoolean("hw", value).apply()

    var networkCaching: Int
        get() = prefs.getInt("caching", 20000)
        set(value) = prefs.edit().putInt("caching", value).apply()

    /** Marge de sécurité autour de l'interface, en % de l'écran (0 par défaut ; -1 = automatique : 5 % sur TV). */
    var overscan: Int
        get() = prefs.getInt("overscan", 0)
        set(value) = prefs.edit().putInt("overscan", value).apply()

    /** Moteur de lecture plein écran : "native" (lecteur Android, ExoPlayer, par défaut) ou "vlc". */
    /** VLC : désentrelacement (yadif 2x) pour les chaînes en 1080i, sport surtout. */
    var deinterlace: Boolean
        get() = prefs.getBoolean("deinterlace", true)
        set(value) = prefs.edit().putBoolean("deinterlace", value).apply()

    /** Lecteur natif : caler la fréquence de l'écran (50/60 Hz) sur celle de la vidéo. */
    var matchFrameRate: Boolean
        get() = prefs.getBoolean("match_fps", true)
        set(value) = prefs.edit().putBoolean("match_fps", value).apply()

    var playerEngine: String
        get() = prefs.getString("engine", "native") ?: "native"
        set(value) = prefs.edit().putString("engine", value).apply()

    /** Sortie audio libVLC : "android_audiotrack" (par défaut) ou "opensles" (si pas de son sur certains contenus). */
    var audioOutput: String
        get() = prefs.getString("aout", "android_audiotrack") ?: "android_audiotrack"
        set(value) = prefs.edit().putString("aout", value).apply()

    /** Extension des flux live Xtream : "ts" ou "m3u8". */
    var liveFormat: String
        get() = prefs.getString("liveFormat", "ts") ?: "ts"
        set(value) = prefs.edit().putString("liveFormat", value).apply()

    var userAgent: String
        get() = prefs.getString("ua", DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString("ua", value).apply()

    // --- Utilitaires JSON --------------------------------------------------------------------

    private fun readArray(key: String): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        val a = JSONArray()
        items.forEach { a.put(it) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun readObject(key: String): JSONObject =
        prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    companion object {
        val USER_AGENTS = listOf(
            "VLC/3.0.21 LibVLC/3.0.21",
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "okhttp/4.12.0",
        )
        /** Agent « Linux » (Android TV) par défaut. */
        val DEFAULT_USER_AGENT = USER_AGENTS[1]
        val CACHING_VALUES = listOf(1000, 2000, 3000, 5000, 8000, 10000, 12000, 16000, 20000, 30000)
    }
}

/** Verrouillage des catégories par code PIN. Les déverrouillages ne durent que le temps de la session. */
class Parental(private val store: LocalStore) {
    private val unlocked = mutableSetOf<String>()
    private val adult = Regex("(?i)(adult|xxx|18\\+|\\+18|porn)")

    fun isRestricted(c: Category): Boolean =
        c.key in store.lockedCategories() || (store.autoLockAdult && adult.containsMatchIn(c.name))

    fun isAutoLocked(c: Category): Boolean = store.autoLockAdult && adult.containsMatchIn(c.name)

    fun isLocked(c: Category): Boolean = isRestricted(c) && c.key !in unlocked

    fun unlock(c: Category) {
        unlocked += c.key
    }

    fun relock() = unlocked.clear()
}
