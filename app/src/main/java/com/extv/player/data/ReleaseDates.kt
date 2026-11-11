package com.extv.player.data

import com.extv.player.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Dates de sortie des films (« 2026-10-22 »), demandées au serveur (fiche du film, get_vod_info) car la liste
 * des films ne les donne pas toujours. Gardées dans un fichier : chaque film n'est demandé qu'une fois.
 */
object ReleaseDates {
    private val file get() = File(App.instance.filesDir, "release_dates.json")
    private val mutex = Mutex()
    private var memory: MutableMap<String, String>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val DATE = Regex("^(19|20)\\d{2}(-\\d{2}(-\\d{2})?)?")

    private fun normalize(raw: String?): String? = raw?.trim()?.let { DATE.find(it)?.value }

    private suspend fun cache(): MutableMap<String, String> = mutex.withLock {
        memory ?: runCatching {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
        }.getOrElse { HashMap() }.also { memory = it }
    }

    private suspend fun save() = mutex.withLock {
        val m = memory ?: return@withLock
        runCatching { file.writeText(JSONObject(m as Map<*, *>).toString()) }
    }

    /**
     * Date de sortie de chaque film (null si le serveur n'en donne pas). Les films inconnus sont demandés au
     * serveur (6 à la fois) ; on attend au plus [waitMs], la suite continue en arrière-plan pour la prochaine fois.
     */
    suspend fun of(repo: IptvRepository, movies: List<Movie>, waitMs: Long = 4_000): Map<String, String?> {
        val known = cache()
        val missing = movies.filter { normalize(it.releaseDate) == null && it.id !in known }.take(150)
        if (missing.isNotEmpty()) {
            val gate = Semaphore(6)
            val job = scope.async {
                missing.map { m ->
                    async {
                        gate.withPermit {
                            val d = runCatching { repo.movieDetail(m) }.getOrNull()
                            val date = normalize(d?.releaseDate) ?: normalize(d?.year)
                            // « - » : le serveur n'a pas de date, inutile de redemander.
                            mutex.withLock { known[m.id] = date ?: "-" }
                        }
                    }
                }.awaitAll()
                save()
            }
            withTimeoutOrNull(waitMs) { job.await() } ?: scope.launch { job.await() }
        }
        return mutex.withLock {
            movies.associate { m -> m.id to (normalize(m.releaseDate) ?: known[m.id]?.takeIf { it != "-" }) }
        }
    }
}
