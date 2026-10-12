package com.extv.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extv.player.App
import com.extv.player.BuildConfig
import com.extv.player.R
import com.extv.player.data.AccountInfo
import com.extv.player.data.ContentType
import com.extv.player.data.Profile
import com.extv.player.data.ProfileType
import kotlinx.coroutines.delay
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

    Column(Modifier.fillMaxSize().padding(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), color = C.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Qui regarde ?", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 32.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(profiles) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusTile(
                        onClick = {
                            app.store.activeProfileId = p.id
                            app.resetRepository()
                            app.parental.relock()
                            onOpen()
                        },
                        modifier = Modifier.size(220.dp, 150.dp),
                        focusRequester = if (p == profiles.first()) first else null,
                    ) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(p.name.take(1).uppercase(), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = C.Accent)
                            Text(p.name, fontSize = 18.sp)
                            Text(if (p.type == ProfileType.XTREAM) "Xtream Codes" else "Liste M3U", color = C.TextDim, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { toDelete = p }) { Text("Supprimer", color = C.TextDim) }
                }
            }
            item {
                FocusTile(
                    onClick = onAdd,
                    modifier = Modifier.size(220.dp, 150.dp),
                    focusRequester = if (profiles.isEmpty()) first else null,
                ) {
                    Text("+  Ajouter", Modifier.align(Alignment.Center), fontSize = 22.sp)
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les favoris et l'historique de ce profil seront effacés.") },
            confirmButton = {
                TextButton(onClick = {
                    app.store.deleteProfile(p.id)
                    profiles.remove(p)
                    toDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

// --- Ajout d'un compte ---------------------------------------------------------------------------

@Composable
fun AddProfileScreen(onDone: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(ProfileType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(BuildConfig.DEFAULT_SERVER) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun submit() {
        errorMsg = when {
            type == ProfileType.XTREAM && (server.isBlank() || username.isBlank() || password.isBlank()) ->
                "Renseignez l'adresse du serveur, l'identifiant et le mot de passe."
            type == ProfileType.M3U && m3uUrl.isBlank() -> "Renseignez l'URL de la liste M3U."
            else -> null
        }
        if (errorMsg != null) return
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { if (type == ProfileType.XTREAM) username.trim() else "Ma liste" },
            type = type,
            server = server.trim(),
            username = username.trim(),
            password = password.trim(),
            m3uUrl = m3uUrl.trim(),
            epgUrl = epgUrl.trim(),
        )
        busy = true
        scope.launch {
            val result = runCatching {
                val repo = app.createRepository(profile)
                if (type == ProfileType.XTREAM) {
                    val info = repo.accountInfo() ?: throw IllegalStateException("Réponse du serveur non reconnue")
                    if (!info.authenticated) throw IllegalStateException("Identifiants refusés par le serveur")
                } else {
                    repo.categories(ContentType.LIVE)
                }
            }
            busy = false
            result.onSuccess {
                app.store.saveProfile(profile)
                app.store.activeProfileId = profile.id
                app.resetRepository()
                onDone()
            }.onFailure { errorMsg = it.message ?: it.toString() }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 40.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Ajouter un compte")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusTile(onClick = { type = ProfileType.XTREAM }, selected = type == ProfileType.XTREAM, focusRequester = first) {
                Text("Xtream Codes API", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            FocusTile(onClick = { type = ProfileType.M3U }, selected = type == ProfileType.M3U) {
                Text("Liste M3U (URL)", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        Field("Nom du profil (facultatif)", name) { name = it }
        if (type == ProfileType.XTREAM) {
            Field("Adresse du serveur (ex. http://exemple.com:8080)", server, KeyboardType.Uri) { server = it }
            Field("Nom d'utilisateur", username) { username = it }
            Field("Mot de passe", password, KeyboardType.Password, secret = true) { password = it }
        } else {
            Field("URL de la liste M3U", m3uUrl, KeyboardType.Uri) { m3uUrl = it }
            Field("URL du guide XMLTV (facultatif)", epgUrl, KeyboardType.Uri) { epgUrl = it }
        }
        errorMsg?.let { Text(it, color = C.Error) }
        ActionButton(if (busy) "Connexion…" else "Se connecter") { if (!busy) submit() }
        Text(
            "Utilisez uniquement des abonnements et contenus dont vous détenez les droits.",
            color = C.TextDim,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

// --- Accueil -------------------------------------------------------------------------------------

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onSwitchProfile: () -> Unit) {
    val app = App.instance
    val profile = remember { app.store.activeProfile() }
    val account = rememberLoader(profile?.id) { app.repository()?.accountInfo() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Accent)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${formatHour(now)}  ·  ${formatDay(now)}", fontSize = 18.sp)
                Text(profile?.name ?: "", color = C.TextDim)
                account.value?.let { Text(it.summary(), color = C.TextDim, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigTile("📺", "TV en direct", Modifier.weight(1f).fillMaxHeight(), first) { onNavigate(Routes.LIVE) }
            BigTile("🎬", "Films", Modifier.weight(1f).fillMaxHeight()) { onNavigate(Routes.MOVIES) }
            BigTile("🎞️", "Séries", Modifier.weight(1f).fillMaxHeight()) { onNavigate(Routes.SERIES) }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val m = Modifier.weight(1f).fillMaxHeight()
            SmallTile("📅", "Guide TV", m) { onNavigate(Routes.LIVE) }
            SmallTile("⏪", "Rattrapage", m) { onNavigate(Routes.CATCHUP_CHANNELS) }
            SmallTile("⭐", "Favoris", m) { onNavigate(Routes.FAVORITES) }
            SmallTile("🔍", "Recherche", m) { onNavigate(Routes.SEARCH) }
            SmallTile("⚙️", "Paramètres", m) { onNavigate(Routes.SETTINGS) }
            SmallTile("👤", "Profils", m, onClick = onSwitchProfile)
        }
    }
}

private fun AccountInfo.summary(): String = buildList {
    status?.let { add(it) }
    expDate?.let { add("expire le ${formatDate(it)}") }
    if (maxConnections != null) add("connexions ${activeConnections ?: 0}/$maxConnections")
}.joinToString("  ·  ")

@Composable
private fun BigTile(icon: String, label: String, modifier: Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier, focusRequester = focusRequester, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) { focused ->
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 56.sp)
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (focused) C.Accent else C.Text)
        }
    }
}

@Composable
private fun SmallTile(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 30.sp)
            Text(label, fontSize = 15.sp)
        }
    }
}
