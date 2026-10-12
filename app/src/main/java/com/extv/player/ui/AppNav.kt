package com.extv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.extv.player.App
import com.extv.player.data.ContentType
import com.extv.player.data.LiveChannel
import com.extv.player.data.Movie
import com.extv.player.data.Series

/** Élément choisi transmis à l'écran de détail (évite de sérialiser les objets dans la route). */
object Selection {
    var movie: Movie? = null
    var series: Series? = null
    var channel: LiveChannel? = null
}

object Routes {
    const val PROFILES = "profiles"
    const val ADD_PROFILE = "addProfile"
    const val HOME = "home"
    const val LIVE = "live"
    const val REPLAY = "replay"
    const val GUIDE = "guide"
    const val MULTI = "multi"
    const val CATCHUP = "catchup"
    const val MOVIES = "movies"
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val SERIES_DETAIL = "seriesDetail"
    const val FAVORITES = "favorites"
    const val FAV_TV = "favorites_tv"
    const val FAV_VOD = "favorites_vod"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOCKED_CATEGORIES = "lockedCategories"
}

private fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = App.instance
    val start = remember {
        when {
            app.store.activeProfile() != null -> Routes.HOME
            app.store.profiles().isNotEmpty() -> Routes.PROFILES
            else -> Routes.ADD_PROFILE
        }
    }
    val openMovie: (Movie) -> Unit = { Selection.movie = it; nav.navigate(Routes.MOVIE) }
    val openSeries: (Series) -> Unit = { Selection.series = it; nav.navigate(Routes.SERIES_DETAIL) }
    val openCatchup: (LiveChannel) -> Unit = { Selection.channel = it; nav.navigate(Routes.CATCHUP) }
    val switchProfile = {
        app.store.activeProfileId = null
        app.resetRepository()
        app.parental.relock()
        nav.resetTo(Routes.PROFILES)
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.PROFILES) {
            ProfilesScreen(onOpen = { nav.resetTo(Routes.HOME) }, onAdd = { nav.navigate(Routes.ADD_PROFILE) })
        }
        composable(Routes.ADD_PROFILE) { AddProfileScreen(onDone = { nav.resetTo(Routes.HOME) }) }
        composable(Routes.HOME) {
            HomeScreen(onNavigate = { nav.navigate(it) }, onSwitchProfile = switchProfile)
        }
        composable(Routes.LIVE) { LiveScreen() }
        composable(Routes.REPLAY) { ReplayScreen(onChannel = openCatchup) }
        composable(Routes.GUIDE) { GuideScreen() }
        composable(Routes.MULTI) { MultiScreen() }
        composable(Routes.CATCHUP) { CatchupScreen() }
        composable(Routes.MOVIES) { MoviesScreen(onOpen = openMovie) }
        composable(Routes.MOVIE) { MovieDetailScreen() }
        composable(Routes.SERIES) { SeriesScreen(onOpen = openSeries) }
        composable(Routes.SERIES_DETAIL) { SeriesDetailScreen() }
        composable(Routes.FAVORITES) { FavoritesScreen(openMovie = openMovie, openSeries = openSeries) }
        composable(Routes.FAV_TV) {
            FavoritesScreen(openMovie = openMovie, openSeries = openSeries, title = "Favoris TV", types = setOf(ContentType.LIVE))
        }
        composable(Routes.FAV_VOD) {
            FavoritesScreen(
                openMovie = openMovie,
                openSeries = openSeries,
                title = "Favoris Films & Séries",
                types = setOf(ContentType.MOVIE, ContentType.SERIES),
            )
        }
        composable(Routes.SEARCH) { SearchScreen(openMovie = openMovie, openSeries = openSeries) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onLockedCategories = { nav.navigate(Routes.LOCKED_CATEGORIES) },
                onSwitchProfile = switchProfile,
            )
        }
        composable(Routes.LOCKED_CATEGORIES) { LockedCategoriesScreen() }
    }
}
