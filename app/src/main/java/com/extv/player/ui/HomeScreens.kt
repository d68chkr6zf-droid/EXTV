package com.extv.player.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extv.player.App
import com.extv.player.BuildConfig
import com.extv.player.R
import com.extv.player.data.ContentType
import com.extv.player.data.Profile
import com.extv.player.data.ProfileType
import kotlinx.coroutines.launch
import java.util.UUID

// --- Choix du profil -----------------------------------------------------------------------------

@Composable
fun ProfilesScreen(onOpen: () -> Unit, onAdd: () -> Unit) {
    val app = App.instance
    val profiles = remember { mutableStateListOf(*app.store.profiles().toTypedArray()) }
    var toDelete by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(screenPadding()).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        ExtvLogo(40.sp)
        Text("Qui regarde ?", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 28.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(profiles) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusTile(
                        onClick = {
                            app.store.activeProfileId = p.id
                            app.resetRepository()
                            app.parental.relock()
                            onOpen()
                        },
                        modifier = Modifier.size(150.dp),
                        focusRequester = if (p == profiles.first()) first else null,
                        shape = RoundedCornerShape(20.dp),
                        normalColor = C.Surface2,
                        focusScale = 1.08f,
                    ) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(p.name.take(1).uppercase(), fontSize = 48.sp, fontWeight = FontWeight.Black, color = C.Accent)
                        }
                    }
                    Text(p.name, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                    TextButton(onClick = { toDelete = p }) { Text("Supprimer", color = C.TextDim, fontSize = 12.sp) }
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusTile(
                        onClick = onAdd,
                        modifier = Modifier.size(150.dp),
                        focusRequester = if (profiles.isEmpty()) first else null,
                        shape = RoundedCornerShape(20.dp),
                        normalColor = C.Surface,
                        focusScale = 1.08f,
                    ) {
                        Text("+", Modifier.align(Alignment.Center), fontSize = 56.sp, color = C.TextDim)
                    }
                    Text("Ajouter", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = C.Surface2,
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les favoris et l'historique de ce profil seront effacés.") },
            confirmButton = {
                TextButton(onClick = {
                    app.store.deleteProfile(p.id)
                    profiles.remove(p)
                    toDelete = null
                }) { Text("Supprimer", color = C.Accent) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler", color = C.TextDim) } },
        )
    }
}

// --- Ajout d'un compte ---------------------------------------------------------------------------

@Composable
fun AddProfileScreen(onDone: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun submit() {
        errorMsg = if (username.isBlank() || password.isBlank()) "Renseignez le nom d'utilisateur et le mot de passe." else null
        if (errorMsg != null) return
        // Le serveur est fixé dans l'application et n'est jamais affiché.
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = username.trim(),
            type = ProfileType.XTREAM,
            server = BuildConfig.DEFAULT_SERVER,
            username = username.trim(),
            password = password.trim(),
            m3uUrl = "",
            epgUrl = "",
        )
        busy = true
        scope.launch {
            val result = runCatching {
                val repo = app.createRepository(profile)
                val info = repo.accountInfo() ?: throw IllegalStateException("Connexion impossible, réessayez plus tard")
                if (!info.authenticated) throw IllegalStateException("Nom d'utilisateur ou mot de passe incorrect")
            }
            busy = false
            result.onSuccess {
                app.store.saveProfile(profile)
                app.store.activeProfileId = profile.id
                app.resetRepository()
                onDone()
            }.onFailure {
                // Ne jamais afficher l'adresse du serveur dans un message d'erreur.
                val host = BuildConfig.DEFAULT_SERVER.substringAfter("://")
                errorMsg = it.message?.takeUnless { m -> m.contains(host, ignoreCase = true) || m.contains("http", ignoreCase = true) }
                    ?: "Connexion impossible, réessayez plus tard"
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(screenPadding()).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtvLogo(34.sp)
            Text("Connexion", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Field("Nom d'utilisateur", username, focusRequester = first) { username = it }
            Field("Mot de passe", password, KeyboardType.Password, secret = true) { password = it }
            errorMsg?.let { Text(it, color = C.Error) }
            ActionButton(if (busy) "Connexion…" else "Se connecter", primary = true) { if (!busy) submit() }
            Text("Utilisez uniquement des abonnements et contenus dont vous détenez les droits.", color = C.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, focusedLabelColor = C.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

// --- Accueil -------------------------------------------------------------------------------------

private class CatalogStatus(val count: Int? = null, val loading: Boolean = true, val error: String? = null)

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onSwitchProfile: () -> Unit) {
    val app = App.instance
    val repo = app.repository() ?: return
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    val size = windowSize()
    val compact = size == WindowSize.Compact
    // Grille horizontale sur TV, et sur smartphone/tablette tenus en paysage.
    val config = LocalConfiguration.current
    val grid = size == WindowSize.Expanded || (!compact && config.screenWidthDp > config.screenHeightDp)
    val account = rememberLoader(app.store.activeProfileId) { repo.accountInfo() }
    val now = rememberNow()
    val statuses = remember { mutableStateMapOf<ContentType, CatalogStatus>() }
    val first = remember { FocusRequester() }

    fun load(type: ContentType, force: Boolean = false) {
        scope.launch {
            statuses[type] = CatalogStatus(loading = true)
            statuses[type] = runCatching {
                if (force) repo.refresh(type)
                when (type) {
                    ContentType.LIVE -> repo.liveChannels(null).size
                    ContentType.MOVIE -> repo.movies(null).size
                    ContentType.SERIES -> repo.series(null).size
                }
            }.fold({ CatalogStatus(it, false) }, { CatalogStatus(null, false, it.message ?: "erreur") })
        }
    }
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        ContentType.values().forEach { if (statuses[it] == null) load(it) }
    }

    fun statusText(type: ContentType, unit: String): String {
        val s = statuses[type] ?: return "Chargement…"
        return when {
            s.loading -> "Chargement…"
            s.error != null -> "Erreur : ${s.error}"
            else -> "${s.count} $unit · mis à jour ${timeAgo(repo.loadedAt(type), now)}"
        }
    }

    val live: @Composable (Modifier) -> Unit = { m ->
        MainTile("TV en direct", R.drawable.ic_live, C.LiveGradient, statusText(ContentType.LIVE, "chaînes"), m, first,
            onRefresh = { load(ContentType.LIVE, true) }) { onNavigate(Routes.LIVE) }
    }
    val movies: @Composable (Modifier) -> Unit = { m ->
        MainTile("Films", R.drawable.ic_movie, C.MoviesGradient, statusText(ContentType.MOVIE, "films"), m,
            onRefresh = { load(ContentType.MOVIE, true) }) { onNavigate(Routes.MOVIES) }
    }
    val series: @Composable (Modifier) -> Unit = { m ->
        MainTile("Séries", R.drawable.ic_series, C.SeriesGradient, statusText(ContentType.SERIES, "séries"), m,
            onRefresh = { load(ContentType.SERIES, true) }) { onNavigate(Routes.SERIES) }
    }
    val smallTiles: @Composable (Modifier) -> Unit = { m ->
        SmallTile("Guide TV", R.drawable.ic_guide, m) { onNavigate(Routes.GUIDE) }
        SmallTile("Multi-écrans", R.drawable.ic_multiscreen, m) { onNavigate(Routes.MULTI) }
        SmallTile("Replay", R.drawable.ic_replay, m) { onNavigate(Routes.REPLAY) }
    }
    val expiration = account.value?.expDate?.let { "Abonnement valable jusqu'au ${formatLongDate(it)}" }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        TopBar(
            compact = compact,
            now = now,
            onSearch = { onNavigate(Routes.SEARCH) },
            onFavorites = { onNavigate(Routes.FAVORITES) },
            onProfiles = onSwitchProfile,
            onSettings = { onNavigate(Routes.SETTINGS) },
            onExit = { activity?.finish() },
        )
        Spacer(Modifier.height(if (compact || config.screenHeightDp < 500) 14.dp else 24.dp))
        if (grid) {
            val gap = if (size == WindowSize.Expanded) 20.dp else 12.dp
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                live(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.75f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.weight(1.7f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        movies(Modifier.weight(1f).fillMaxHeight())
                        series(Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        smallTiles(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                live(Modifier.fillMaxWidth().height(if (compact) 180.dp else 240.dp))
                Row(Modifier.fillMaxWidth().height(if (compact) 160.dp else 210.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    movies(Modifier.weight(1f).fillMaxHeight())
                    series(Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().height(if (compact) 84.dp else 100.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    smallTiles(Modifier.weight(1f).fillMaxHeight())
                }
                expiration?.let { Text(it, color = C.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally)) }
            }
        }
        if (grid) {
            expiration?.let {
                Text(it, color = C.TextDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun TopBar(
    compact: Boolean,
    now: Long,
    onSearch: () -> Unit,
    onFavorites: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ExtvLogo(if (compact) 26.sp else 32.sp)
        if (!compact) {
            Text(
                "${formatHour(now)}   ${formatLongDate(now)}",
                fontSize = 17.sp,
                color = C.TextDim,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            FocusTile(onClick = onSearch, shape = RoundedCornerShape(24.dp), normalColor = C.Surface2) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = C.TextDim, modifier = Modifier.size(20.dp))
                    Text("Rechercher", color = C.TextDim, modifier = Modifier.padding(start = 8.dp, end = 40.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
        } else {
            RoundIconButton(Icons.Default.Search, "Rechercher", 40.dp, onSearch)
            Spacer(Modifier.width(8.dp))
        }
        val gap = if (compact) 8.dp else 12.dp
        val btn = if (compact) 40.dp else 46.dp
        RoundIconButton(Icons.Default.Star, "Favoris", btn, onFavorites)
        Spacer(Modifier.width(gap))
        RoundIconButton(Icons.Default.Person, "Profils", btn, onProfiles)
        Spacer(Modifier.width(gap))
        RoundIconButton(Icons.Default.Settings, "Paramètres", btn, onSettings)
        if (!compact) {
            Spacer(Modifier.width(gap))
            RoundIconButton(Icons.Default.ExitToApp, "Quitter", btn, onExit)
        }
    }
}

@Composable
private fun MainTile(
    title: String,
    icon: Int,
    gradient: List<Color>,
    status: String,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    onRefresh: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Brush.linearGradient(gradient))) {
        FocusTile(
            onClick = onClick,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            focusRequester = focusRequester,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            normalColor = Color.Transparent,
            focusedColor = Color(0x26FFFFFF),
            focusScale = 1f,
        ) {
            TileLabel(title, icon)
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0x40000000)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status, Modifier.weight(1f), fontSize = 12.sp, color = Color(0xE6FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            FocusTile(onClick = onRefresh, modifier = Modifier.size(34.dp), shape = CircleShape, normalColor = Color(0x33FFFFFF), focusScale = 1.15f) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(18.dp))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileLabel(title: String, icon: Int) {
    // Tuile basse (smartphone en paysage) : icône et texte réduits pour que le titre reste lisible.
    BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
        val small = maxHeight < 130.dp
        Column(Modifier.padding(if (small) 4.dp else 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 36.dp else 64.dp))
            Text(
                title.uppercase(),
                fontSize = if (small) 17.sp else 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = Color.White,
                modifier = Modifier.padding(top = if (small) 4.dp else 8.dp),
            )
        }
    }
}

@Composable
private fun SmallTile(label: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    FocusTile(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        normalColor = C.Surface2,
        focusedColor = C.SurfaceFocus,
    ) { focused ->
        if (windowSize() == WindowSize.Compact) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), contentDescription = null, tint = if (focused) C.Accent else C.Text, modifier = Modifier.size(24.dp))
                Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            Row(Modifier.align(Alignment.Center).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, tint = if (focused) C.Accent else C.Text, modifier = Modifier.size(26.dp))
                Text(
                    label.uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}
