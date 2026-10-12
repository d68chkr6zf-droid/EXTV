package com.extv.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage local simple (SharedPreferences + JSON) : profils, favoris, positions de lecture,
 * contrôle parental et réglages du lecteur. Favoris, positions et verrous sont propres à chaque profil.
 */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("extv", Context.MODE_PRIVATE)

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

    // --- Contrôle parental -------------------------------------------------------------------

    var parentalPin: String
        get() = prefs.getString("pin", "0000") ?: "0000"
        set(value) = prefs.edit().putString("pin", value).apply()

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
        get() = prefs.getInt("caching", 3000)
        set(value) = prefs.edit().putInt("caching", value).apply()

    /** Extension des flux live Xtream : "ts" ou "m3u8". */
    var liveFormat: String
        get() = prefs.getString("liveFormat", "ts") ?: "ts"
        set(value) = prefs.edit().putString("liveFormat", value).apply()

    var userAgent: String
        get() = prefs.getString("ua", USER_AGENTS.first()) ?: USER_AGENTS.first()
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
        val CACHING_VALUES = listOf(1000, 2000, 3000, 5000, 8000)
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
