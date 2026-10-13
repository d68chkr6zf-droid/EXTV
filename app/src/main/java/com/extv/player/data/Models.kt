package com.extv.player.data

import org.json.JSONObject

enum class ProfileType { XTREAM, M3U }

enum class ContentType { LIVE, MOVIE, SERIES }

data class Profile(
    val id: String,
    val name: String,
    val type: ProfileType,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("server", server).put("username", username).put("password", password)
        .put("m3uUrl", m3uUrl).put("epgUrl", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { ProfileType.valueOf(o.optString("type")) }.getOrDefault(ProfileType.XTREAM),
            server = o.optString("server"),
            username = o.optString("username"),
            password = o.optString("password"),
            m3uUrl = o.optString("m3uUrl"),
            epgUrl = o.optString("epgUrl"),
        )
    }
}

data class Category(val id: String, val name: String, val type: ContentType) {
    val key: String get() = "${type.name}:$id"

    companion object {
        const val ALL = "__all"
        const val FAVORITES = "__fav"
        const val HISTORY = "__hist"
        const val RECENT = "__new"
        const val CONTINUE = "__cont"

        fun isVirtual(id: String) = id.startsWith("__")
    }
}

data class LiveChannel(
    val id: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val streamUrl: String,
    val hasArchive: Boolean,
    val archiveDays: Int,
    val num: Int,
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val streamUrl: String,
    val rating: String?,
    val plot: String? = null,
    val genre: String? = null,
    val duration: String? = null,
    val year: String? = null,
    val cast: String? = null,
    val director: String? = null,
    /** Bande-annonce : identifiant ou lien YouTube fourni par le serveur. */
    val trailer: String? = null,
    /** Date d'ajout sur le serveur (ms), pour « Récemment ajouté ». */
    val added: Long = 0,
)

data class Series(
    val id: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    val added: Long = 0,
    /** Bande-annonce : identifiant ou lien YouTube fourni par le serveur. */
    val trailer: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val streamUrl: String,
    val plot: String?,
    val image: String?,
)

data class SeriesDetail(val series: Series, val seasons: Map<Int, List<Episode>>)

data class EpgProgram(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    /** Heure de début telle que renvoyée par le serveur (utile pour le rattrapage Xtream). */
    val startRaw: String? = null,
)

data class AccountInfo(
    val authenticated: Boolean,
    val status: String?,
    val expDate: Long?,
    val activeConnections: Int?,
    val maxConnections: Int?,
)

data class SearchResults(
    val live: List<LiveChannel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class Favorite(val type: ContentType, val id: String, val name: String, val image: String?) {
    val key: String get() = keyOf(type, id)

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("id", id).put("name", name).put("image", image ?: "")

    companion object {
        fun keyOf(type: ContentType, id: String) = "${type.name}:$id"
        fun fromJson(o: JSONObject) = Favorite(
            type = ContentType.valueOf(o.getString("type")),
            id = o.getString("id"),
            name = o.optString("name"),
            image = o.optString("image").ifBlank { null },
        )
    }
}

fun LiveChannel.toFavorite() = Favorite(ContentType.LIVE, id, name, logo)
fun Movie.toFavorite() = Favorite(ContentType.MOVIE, id, name, poster)
fun Series.toFavorite() = Favorite(ContentType.SERIES, id, name, cover)
