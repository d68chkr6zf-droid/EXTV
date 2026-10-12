package com.extv.player

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.extv.player.data.AccountInfo
import com.extv.player.data.BaseRepository
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.EpgProgram
import com.extv.player.data.Episode
import com.extv.player.data.LiveChannel
import com.extv.player.data.LocalStore
import com.extv.player.data.Movie
import com.extv.player.data.Series
import com.extv.player.data.SeriesDetail
import com.extv.player.ui.ExtvTheme
import com.extv.player.ui.GuideScreen
import com.extv.player.ui.HomeScreen
import com.extv.player.ui.LiveScreen
import com.extv.player.ui.MovieDetailScreen
import com.extv.player.ui.MoviesScreen
import com.extv.player.ui.ReplayScreen
import com.extv.player.ui.Selection
import com.extv.player.ui.SeriesDetailScreen
import com.extv.player.ui.SeriesScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Captures d'écran générées automatiquement (Paparazzi) avec des données d'exemple fictives.
 * Commande : ./gradlew recordPaparazziDebug — images dans app/src/test/snapshots/images.
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = TV)

    @Before
    fun setUp() {
        App.installForScreenshots(LocalStore(MemoryPrefs()), FakeRepository())
        Selection.movie = FakeRepository.movies[1]
        Selection.series = FakeRepository.series[0]
    }

    private fun shoot(name: String, device: DeviceConfig, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device)
        // Rendu après un court délai pour laisser les données (fictives) se charger.
        val view = ComposeView(paparazzi.context).apply { setContent { CompositionLocalProvider(LocalInspectionMode provides true) { ExtvTheme { content() } } } }
        paparazzi.snapshot(view, name, offsetMillis = 3_000L)
    }

    @Test fun tvHome() = shoot("tv_1_accueil", TV) { HomeScreen(onNavigate = {}, onSwitchProfile = {}) }
    @Test fun tvLive() = shoot("tv_2_direct", TV) { LiveScreen() }
    @Test fun tvMovies() = shoot("tv_3_films", TV) { MoviesScreen(onOpen = {}) }
    @Test fun tvMovie() = shoot("tv_4_fiche_film", TV) { MovieDetailScreen() }
    @Test fun tvSeries() = shoot("tv_5_series", TV) { SeriesScreen(onOpen = {}) }
    @Test fun tvSeriesDetail() = shoot("tv_6_fiche_serie", TV) { SeriesDetailScreen() }
    @Test fun tvGuide() = shoot("tv_7_guide", TV) { GuideScreen() }
    @Test fun tvReplay() = shoot("tv_8_replay", TV) { ReplayScreen(onChannel = {}) }

    @Test fun phoneHome() = shoot("phone_1_accueil", PHONE) { HomeScreen(onNavigate = {}, onSwitchProfile = {}) }
    @Test fun phoneLive() = shoot("phone_2_direct", PHONE) { LiveScreen() }
    @Test fun phoneMovies() = shoot("phone_3_films", PHONE) { MoviesScreen(onOpen = {}) }
    @Test fun phoneMovie() = shoot("phone_4_fiche_film", PHONE) { MovieDetailScreen() }

    @Test fun phoneLandHome() = shoot("phone_5_accueil_paysage", PHONE_LAND) { HomeScreen(onNavigate = {}, onSwitchProfile = {}) }
    @Test fun phoneLandLive() = shoot("phone_6_direct_paysage", PHONE_LAND) { LiveScreen() }

    @Test fun tabletHome() = shoot("tablette_1_accueil", TABLET) { HomeScreen(onNavigate = {}, onSwitchProfile = {}) }

    companion object {
        val TV = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920,
            screenHeight = 1080,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
        )
        val PHONE = DeviceConfig.PIXEL_5
        val PHONE_LAND = DeviceConfig.PIXEL_5.copy(
            screenWidth = DeviceConfig.PIXEL_5.screenHeight,
            screenHeight = DeviceConfig.PIXEL_5.screenWidth,
            orientation = ScreenOrientation.LANDSCAPE,
        )
        val TABLET = DeviceConfig.PIXEL_C
    }
}

/** Données fictives (aucun vrai programme ni vraie chaîne). */
class FakeRepository : BaseRepository() {
    companion object {
        private val now = System.currentTimeMillis()
        private const val H = 3_600_000L

        val liveCategories = listOf("FR | Généraliste", "FR | Cinéma", "FR | Sport", "FR | Information", "FR | Jeunesse", "FR | Documentaires", "FR | Musique", "FR | Régions")
            .mapIndexed { i, n -> Category("c$i", n, ContentType.LIVE) }

        private val channelNames = listOf(
            "Étoile TV HD", "Horizon FHD", "Canal Lumière", "Rivage TV", "Cinéma Club", "Grand Écran HD",
            "Sport Arena 1", "Sport Arena 2", "Info 24", "Actu Direct", "Kids Planet", "Toon City",
            "Découverte+", "Planète Nature", "Musique Live", "Région Ouest", "Région Sud", "Horizon SD",
        )
        val channels = channelNames.mapIndexed { i, n ->
            LiveChannel(
                id = "$i", name = n, logo = null,
                categoryId = if (i < 4 || i == 17) "c0" else "c${(i - 2) / 2}".let { if (it == "c0") "c1" else it },
                epgId = null, streamUrl = "http://exemple/$i.ts",
                hasArchive = i % 3 != 2, archiveDays = 7, num = i + 1,
            )
        }

        private val programTitles = listOf(
            "Le journal de 13h", "Petits plats de saison", "Météo", "Le grand film de l'après-midi",
            "Magazine découverte", "Football : le multiplex", "Dessins animés", "Les infos en continu",
        )

        val movieCategories = listOf("Action", "Comédie", "Drame", "Science-fiction", "Famille", "Policier", "Animation", "Thriller")
            .mapIndexed { i, n -> Category("m$i", n, ContentType.MOVIE) }
        private val movieTitles = listOf(
            "Le Dernier Horizon", "Nuit Rouge", "L'Ombre du Phare", "Mission Atlas", "Les Voyageurs",
            "Cendres d'Or", "Le Secret de Montmartre", "Écho", "Tempête sur Brest", "La Route du Nord",
            "Opération Minuit", "Les Lumières de Paris", "Vertige", "Le Septième Quai", "Mirage",
            "La Ligne Bleue", "Les Héritiers", "Au-delà des Dunes", "Silence Radio", "Le Cercle",
            "Point de Rupture", "Les Enfants du Vent", "Contre-courant", "L'Envol",
        )
        val movies = movieTitles.mapIndexed { i, t ->
            Movie(
                id = "$i", name = "$t (2026)", poster = null, categoryId = "m${i % 8}", streamUrl = "",
                rating = listOf("7.8", "6.5", "8.2", "7.1", "5.9", "8.8")[i % 6],
                plot = "Une enquête haletante entre Paris et la côte bretonne, où chaque indice rapproche les héros d'un secret de famille enfoui depuis trente ans.",
                genre = "Thriller, Drame", duration = "1h 58min", year = "2026", cast = "Camille Durand, Hugo Martin, Léa Bernard",
                director = "Julie Moreau", added = now - i * H,
            )
        }

        val seriesCategories = listOf("Séries FR", "Familial", "Animation", "Policier", "Science-fiction", "Comédie")
            .mapIndexed { i, n -> Category("s$i", n, ContentType.SERIES) }
        private val seriesTitles = listOf(
            "Les Gardiens du Phare", "Quartier Libre", "Station Polaire", "La Brigade", "Les Mystères de Lyon",
            "Héritage", "Galaxie 9", "Famille Dupont", "Le Bureau d'à côté", "Tribunal", "Mer d'Iroise", "Les Apprentis",
        )
        val series = seriesTitles.mapIndexed { i, t ->
            Series("$i", t, null, "s${i % 6}", "Dans un petit port de pêche, trois générations de gardiens se succèdent et tentent de percer le mystère du naufrage de 1987.", listOf("8.1", "7.4", "6.9")[i % 3], now - i * H)
        }
    }

    override suspend fun categories(type: ContentType) = when (type) {
        ContentType.LIVE -> liveCategories
        ContentType.MOVIE -> movieCategories
        ContentType.SERIES -> seriesCategories
    }

    override suspend fun liveChannels(categoryId: String?) = channels.filter { categoryId == null || it.categoryId == categoryId }
    override suspend fun movies(categoryId: String?) = movies.filter { categoryId == null || it.categoryId == categoryId }
    override suspend fun series(categoryId: String?) = series.filter { categoryId == null || it.categoryId == categoryId }
    override suspend fun movieDetail(movie: Movie) = movie

    override suspend fun seriesDetail(series: Series): SeriesDetail {
        val seasons = (1..3).associateWith { s ->
            (1..8).map { e -> Episode("$s-$e", "Épisode $e — ${listOf("Le retour", "La tempête", "Secrets", "L'appel du large", "Marée haute", "Le phare", "Révélations", "Final")[e - 1]}", s, e, "", "Un nouvel indice relance l'enquête.", null) }
        }
        return SeriesDetail(series, seasons)
    }

    override suspend fun epg(channel: LiveChannel, limit: Int): List<EpgProgram> {
        val base = now - (now % H) - H / 2 + channel.num * 300_000L
        return (0 until limit).map { k ->
            EpgProgram(programTitles[(channel.num + k) % programTitles.size], "Présentation du programme et des invités du jour.", base + k * H, base + (k + 1) * H)
        }
    }

    override suspend fun catchup(channel: LiveChannel) = emptyList<EpgProgram>()
    override fun catchupUrl(channel: LiveChannel, program: EpgProgram): String? = null
    override suspend fun accountInfo() = AccountInfo(true, "Active", now + 15 * 24 * H, 1, 2)
    override suspend fun refresh(type: ContentType) = Unit
    override fun loadedAt(type: ContentType): Long = now - 2 * 60_000
}

/** SharedPreferences en mémoire pour les tests. */
class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            map[key!!] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            map.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit() = true
        override fun apply() = Unit
    }
}
