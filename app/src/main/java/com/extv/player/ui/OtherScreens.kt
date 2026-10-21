package com.extv.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.extv.player.App
import com.extv.player.data.Updater
import com.extv.player.data.Category
import com.extv.player.data.ContentType
import com.extv.player.data.Favorite
import com.extv.player.data.LiveChannel
import com.extv.player.data.LocalStore
import com.extv.player.data.Movie
import com.extv.player.data.ProfileType
import com.extv.player.data.Series
import com.extv.player.data.searchVisible
import com.extv.player.player.PlayQueue
import kotlinx.coroutines.launch

// --- Favoris -------------------------------------------------------------------------------------

@Composable
fun FavoritesScreen(
    openMovie: (Movie) -> Unit,
    openSeries: (Series) -> Unit,
    title: String = "Favoris",
    types: Set<ContentType> = ContentType.values().toSet(),
) {
    val app = App.instance
    val repo = app.repository() ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf(app.store.favorites().filter { it.type in types }) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { favorites = app.store.favorites().filter { it.type in types } }
    var message by remember { mutableStateOf<String?>(null) }

    fun open(f: Favorite) {
        scope.launch {
            runCatching {
                when (f.type) {
                    ContentType.LIVE -> {
                        val all = repo.liveChannels(null).associateBy { it.id }
                        val channels = favorites.filter { it.type == ContentType.LIVE }.mapNotNull { all[it.id] }
                        val index = channels.indexOfFirst { it.id == f.id }
                        if (index >= 0) PlayQueue.start(context, channels.map { it.toPlayItem() }, index, live = true)
                        else message = "Cette chaîne n'existe plus dans la liste."
                    }
                    ContentType.MOVIE -> repo.movies(null).find { it.id == f.id }?.let(openMovie)
                        ?: run { message = "Ce film n'existe plus dans la liste." }
                    ContentType.SERIES -> repo.series(null).find { it.id == f.id }?.let(openSeries)
                        ?: run { message = "Cette série n'existe plus dans la liste." }
                }
                Unit
            }.onFailure { message = it.message }
        }
    }

    Column(Modifier.fillMaxSize().padding(screenPadding()).verticalScroll(rememberScrollState())) {
        ScreenTitle(title)
        message?.let { Text(it, color = C.Error, modifier = Modifier.padding(bottom = 12.dp)) }
        if (favorites.isEmpty()) {
            Text(
                if (ContentType.LIVE in types && types.size == 1) "Aucune chaîne favorite. Restez appuyé sur une chaîne pour l'ajouter."
                else if (ContentType.LIVE !in types) "Aucun film ni série en favori. Restez appuyé sur une affiche pour l'ajouter."
                else "Aucun favori pour l'instant. Ajoutez-en depuis une chaîne, un film ou une série.",
                color = C.TextDim,
            )
        }
        // Chaînes du catalogue (en cache) pour retrouver l'EPG des chaînes favorites.
        val liveById = if (favorites.any { it.type == ContentType.LIVE }) {
            rememberLoader("favLive") { repo.liveChannels(null).associateBy { it.id } }.value
        } else null
        val first = remember { FocusRequester() }
        var firstAssigned = false
        listOf(
            ContentType.LIVE to "Chaînes",
            ContentType.MOVIE to "Films",
            ContentType.SERIES to "Séries",
        ).forEach { (type, label) ->
            val list = favorites.filter { it.type == type }
            if (list.isNotEmpty()) {
                val requester = if (!firstAssigned) first else null
                firstAssigned = true
                Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(list) { i, f ->
                        if (type == ContentType.LIVE) {
                            FavoriteChannelCard(
                                f,
                                liveById?.get(f.id),
                                onClick = { open(f) },
                                focusRequester = if (i == 0) requester else null,
                            )
                        } else PosterCard(
                            f.name,
                            f.image,
                            onClick = { open(f) },
                            modifier = Modifier.width(150.dp),
                            focusRequester = if (i == 0) requester else null,
                        )
                    }
                }
            }
        }
        LaunchedEffect(favorites.isNotEmpty()) { if (favorites.isNotEmpty()) runCatching { first.requestFocus() } }
    }
}

/** Vignette d'une chaîne favorite avec le programme en cours (seules les vignettes affichées chargent leur EPG). */
@Composable
private fun FavoriteChannelCard(f: Favorite, channel: LiveChannel?, onClick: () -> Unit, focusRequester: FocusRequester?) {
    val repo = App.instance.repository()
    val now = rememberNow(60_000)
    val programs = rememberLoader(channel?.id) {
        if (channel == null || repo == null) emptyList() else repo.nowNext(channel)
    }.value.orEmpty()
    val current = programs.firstOrNull { now in it.start until it.end }
    PosterCard(
        f.name,
        f.image,
        onClick = onClick,
        modifier = Modifier.width(170.dp),
        aspect = 16f / 10f,
        fit = true,
        focusRequester = focusRequester,
        subtitle = current?.title?.ifBlank { null },
        progress = current?.let { ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
    )
}

// --- Recherche -----------------------------------------------------------------------------------

@Composable
fun SearchScreen(openMovie: (Movie) -> Unit, openSeries: (Series) -> Unit) {
    val repo = App.instance.repository() ?: return
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        ScreenTitle("Recherche")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Chaîne, film ou série") },
                singleLine = true,
                modifier = Modifier.weight(1f).widthIn(max = 520.dp).focusRequester(field),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            )
            Spacer(Modifier.width(16.dp))
            ActionButton("Rechercher") { submitted = query.trim() }
        }
        if (submitted.length >= 2) {
            val results = rememberLoader(submitted) { repo.searchVisible(submitted) }
            LoadView(results) { r ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                    if (r.isEmpty) Text("Aucun résultat pour « $submitted ».", color = C.TextDim)
                    if (r.live.isNotEmpty()) {
                        ResultTitle("Chaînes (${r.live.size})")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            itemsIndexed(r.live) { i, ch ->
                                PosterCard(
                                    ch.name,
                                    ch.logo,
                                    onClick = { PlayQueue.start(context, r.live.map { it.toPlayItem() }, i, live = true) },
                                    modifier = Modifier.width(170.dp),
                                    aspect = 16f / 10f,
                                    fit = true,
                                )
                            }
                        }
                    }
                    if (r.movies.isNotEmpty()) {
                        ResultTitle("Films (${r.movies.size})")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(r.movies) { m -> PosterCard(m.name, m.poster, onClick = { openMovie(m) }, modifier = Modifier.width(140.dp), rating = m.rating) }
                        }
                    }
                    if (r.series.isNotEmpty()) {
                        ResultTitle("Séries (${r.series.size})")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(r.series) { s -> PosterCard(s.name, s.cover, onClick = { openSeries(s) }, modifier = Modifier.width(140.dp), rating = s.rating) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
}

// --- Paramètres ----------------------------------------------------------------------------------

@Composable
fun SettingsScreen(onLockedCategories: () -> Unit, onSwitchProfile: () -> Unit) {
    val app = App.instance
    val store = app.store
    val profile = remember { store.activeProfile() }
    val account = rememberLoader(profile?.id) { app.repository()?.accountInfo() }
    var tick by remember { mutableIntStateOf(0) } // force le rafraîchissement des valeurs affichées
    var pinRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askNewPin by remember { mutableStateOf(false) }
    // Code au démarrage : vérification du code actuel, puis choix (1) et confirmation (2) du nouveau.
    var startPinRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var startStep by remember { mutableIntStateOf(0) }
    var newStartPin by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateText by remember { mutableStateOf("Vérifier") }
    var updating by remember { mutableStateOf(false) }

    fun changed() {
        tick++
    }

    fun checkUpdate() {
        if (updating) return
        if (!Updater.canInstall(context)) {
            updateText = "Autorisez EXTV à installer des applications, puis revenez"
            Updater.openInstallPermission(context)
            return
        }
        updating = true
        updateText = "Recherche…"
        scope.launch {
            runCatching {
                val release = Updater.check()
                if (release == null) {
                    updateText = "Vous avez la dernière version"
                } else {
                    updateText = "Téléchargement de la version ${release.versionName}…"
                    val apk = Updater.download(context) { p -> updateText = "Téléchargement… ${(p * 100).toInt()} %" }
                    if (!Updater.isNewer(context, apk)) {
                        updateText = "Version ${release.versionName} encore en cours de publication, réessayez dans 2 min"
                        updating = false
                        return@launch
                    }
                    updateText = "Installation de la version ${release.versionName}"
                    Updater.install(context, apk)
                }
            }.onFailure { updateText = "Vérification impossible, réessayez plus tard" }
            updating = false
        }
    }

    Column(Modifier.fillMaxSize().padding(screenPadding()).verticalScroll(rememberScrollState())) {
        ScreenTitle("Paramètres · version ${Updater.currentVersion}")
        key(tick) {
            Section("Compte")
            InfoRow("Compte", profile?.name ?: "-")
            account.value?.let { a ->
                InfoRow("Statut", a.status ?: "-")
                a.expDate?.let { InfoRow("Expiration", formatDate(it)) }
                a.maxConnections?.let { InfoRow("Connexions", "${a.activeConnections ?: 0} / $it") }
            }

            Section("Application")
            InfoRow("Version installée", Updater.currentVersion)
            InfoRow("Vérification automatique", Updater.lastAutoCheck.value)
            SettingRow("Rechercher une mise à jour (manuel)", updateText) { checkUpdate() }

            SettingRow("Cadrage de l'écran (bords coupés)", if (store.overscan < 0) "Automatique" else "Marge ${store.overscan} %") {
                store.overscan = OVERSCAN_VALUES[(OVERSCAN_VALUES.indexOf(store.overscan) + 1).mod(OVERSCAN_VALUES.size)]
                overscanSetting.intValue = store.overscan
                changed()
            }

            Section("Lecteur")
            SettingRow("Lecteur vidéo", if (store.playerEngine == "native") "Natif (Android)" else "VLC", first) {
                store.playerEngine = if (store.playerEngine == "native") "vlc" else "native"
                changed()
            }
            SettingRow("Fréquence d'écran adaptée (50/60 Hz, natif)", if (store.matchFrameRate) "Activé" else "Désactivé") {
                store.matchFrameRate = !store.matchFrameRate
                changed()
            }
            SettingRow("Décodage matériel (VLC)", if (store.hwDecoding) "Activé" else "Désactivé") {
                store.hwDecoding = !store.hwDecoding
                changed()
            }
            SettingRow("Mode sport", if (store.deinterlace) "Activé" else "Désactivé") {
                store.deinterlace = !store.deinterlace
                changed()
            }
            SettingRow("Sortie audio VLC (si pas de son)", if (store.audioOutput == "opensles") "OpenSL ES" else "AudioTrack (par défaut)") {
                store.audioOutput = if (store.audioOutput == "opensles") "android_audiotrack" else "opensles"
                changed()
            }
            SettingRow("Mémoire tampon réseau", "${store.networkCaching} ms") {
                val values = LocalStore.CACHING_VALUES
                store.networkCaching = values[(values.indexOf(store.networkCaching) + 1).mod(values.size)]
                changed()
            }
            if (profile?.type == ProfileType.XTREAM) {
                SettingRow("Format des flux en direct", if (store.liveFormat == "ts") "MPEG-TS (.ts)" else "HLS (.m3u8)") {
                    store.liveFormat = if (store.liveFormat == "ts") "m3u8" else "ts"
                    app.resetRepository()
                    changed()
                }
            }
            SettingRow("User-Agent", store.userAgent.take(40)) {
                val values = LocalStore.USER_AGENTS
                store.userAgent = values[(values.indexOf(store.userAgent) + 1).mod(values.size)]
                changed()
            }

            Section("Verrouillage de l'application")
            SettingRow("Code au démarrage", if (store.startLock) "Activé" else "Désactivé") {
                if (store.startLock) {
                    startPinRequest = { store.startLock = false; changed() }
                } else {
                    newStartPin = ""
                    startStep = 1
                }
            }
            if (store.startLock) {
                SettingRow("Modifier le code de démarrage", "") {
                    startPinRequest = { newStartPin = ""; startStep = 1 }
                }
            }

            Section("Contrôle parental")
            SettingRow("Verrouiller les catégories adultes", if (store.autoLockAdult) "Oui" else "Non") {
                if (store.autoLockAdult) {
                    pinRequest = { store.autoLockAdult = false; changed() }
                } else {
                    store.autoLockAdult = true
                    app.parental.relock()
                    changed()
                }
            }
            SettingRow("Catégories verrouillées", "Gérer") { pinRequest = onLockedCategories }
            SettingRow("Modifier le code PIN", "Code par défaut : 0000") { pinRequest = { askNewPin = true } }

            Section("Données")
            SettingRow("Actualiser les listes", "") {
                app.resetRepository()
                info = "Les listes seront rechargées à la prochaine ouverture."
            }
            SettingRow("Changer de profil", "", onClick = onSwitchProfile)
            info?.let { Text(it, color = C.Accent, modifier = Modifier.padding(top = 12.dp)) }
        }
    }

    pinRequest?.let { action ->
        PinDialog(onDismiss = { pinRequest = null }) {
            pinRequest = null
            action()
        }
    }
    startPinRequest?.let { action ->
        PinDialog(
            title = "Code de démarrage actuel",
            onDismiss = { startPinRequest = null },
            check = { it == store.startPin },
        ) {
            startPinRequest = null
            action()
        }
    }
    if (startStep == 1) {
        PinDialog(
            title = "Choisissez votre code (4 chiffres)",
            onDismiss = { startStep = 0 },
            check = { it.length == 4 },
        ) { pin ->
            newStartPin = pin
            startStep = 2
        }
    }
    if (startStep == 2) {
        PinDialog(
            title = "Confirmez votre code",
            onDismiss = { startStep = 0 },
            check = { it == newStartPin },
        ) { pin ->
            store.startPin = pin
            store.startLock = true
            markStartUnlocked()
            startStep = 0
            info = "Code au démarrage activé. Il sera demandé à chaque ouverture d'EXTV et sortie de veille."
            tick++
        }
    }
    if (askNewPin) {
        PinDialog(
            title = "Nouveau code PIN (4 chiffres ou plus)",
            onDismiss = { askNewPin = false },
            check = { it.length >= 4 },
        ) { newPin ->
            store.parentalPin = newPin
            askNewPin = false
            info = "Code PIN modifié."
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, Modifier.width(260.dp), color = C.TextDim)
        Text(value)
    }
}

@Composable
private fun SettingRow(label: String, value: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusRequester = focusRequester) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, Modifier.weight(1f))
            Text(value, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --- Choix des catégories verrouillées ------------------------------------------------------------

@Composable
fun LockedCategoriesScreen() {
    val app = App.instance
    val repo = app.repository() ?: return
    val all = rememberLoader(Unit) {
        ContentType.values().flatMap { type -> runCatching { repo.categories(type) }.getOrDefault(emptyList()) }
    }
    var locked by remember { mutableStateOf(app.store.lockedCategories()) }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(screenPadding())) {
        ScreenTitle("Catégories verrouillées")
        Text("Les catégories cochées demanderont le code PIN.", color = C.TextDim, modifier = Modifier.padding(bottom = 12.dp))
        LoadView(all) { list ->
            LaunchedEffect(list) { runCatching { first.requestFocus() } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list) { i, c: Category ->
                    val auto = app.parental.isAutoLocked(c)
                    val isLocked = c.key in locked || auto
                    FocusTile(
                        onClick = {
                            if (!auto) {
                                app.store.setLocked(c.key, c.key !in locked)
                                locked = app.store.lockedCategories()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (i == 0) first else null,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(if (isLocked) "🔒" else "🔓", Modifier.width(40.dp))
                            Text(
                                when (c.type) {
                                    ContentType.LIVE -> "TV"
                                    ContentType.MOVIE -> "Film"
                                    ContentType.SERIES -> "Série"
                                },
                                Modifier.width(80.dp),
                                color = C.TextDim,
                            )
                            Text(c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (auto) Text("auto", color = C.TextDim)
                        }
                    }
                }
            }
        }
    }
}
