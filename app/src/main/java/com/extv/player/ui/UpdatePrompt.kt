package com.extv.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.extv.player.data.Updater
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Version pour laquelle l'utilisateur a choisi « Plus tard » (on ne la repropose pas avant le prochain lancement). */
private var postponedVersion = 0

/** Intervalle entre deux vérifications tant que l'accueil est affiché. */
private const val CHECK_EVERY_MS = 60_000L

/**
 * Au démarrage, si une nouvelle version est publiée, propose de l'installer :
 * OK télécharge puis ouvre l'installation, « Plus tard » ferme le message.
 */
@Composable
fun UpdatePrompt() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Vérifie à l'ouverture de l'accueil puis toutes les minutes, seulement quand l'accueil est à l'écran
    // (jamais pendant la lecture d'une vidéo).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                if (release == null) {
                    val found = runCatching { Updater.check() }.getOrNull()
                    if (found != null && found.versionCode != postponedVersion) release = found
                }
                kotlinx.coroutines.delay(CHECK_EVERY_MS)
            }
        }
    }

    val r = release ?: return
    val ok = remember { FocusRequester() }
    LaunchedEffect(r) { runCatching { ok.requestFocus() } }

    fun update() {
        if (progress != null) return
        if (!Updater.canInstall(context)) {
            message = "Autorisez EXTV à installer des applications, puis revenez et appuyez sur OK."
            Updater.openInstallPermission(context)
            return
        }
        progress = 0f
        message = null
        scope.launch {
            runCatching {
                val apk = Updater.download(context) { p -> progress = p }
                Updater.install(context, apk)
                release = null
            }.onFailure { message = "Téléchargement impossible, réessayez plus tard." }
            progress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) { postponedVersion = r.versionCode; release = null } },
        containerColor = C.Surface2,
        title = { Text("Mise à jour disponible", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("La version ${r.versionName} d'EXTV est disponible. Vos favoris et réglages sont conservés.")
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        color = C.Accent,
                    )
                    Text("Téléchargement… ${(it * 100).toInt()} %", color = C.TextDim, modifier = Modifier.padding(top = 6.dp))
                }
                message?.let { Text(it, color = C.Accent, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { update() }, modifier = Modifier.focusRequester(ok)) {
                Text("OK", color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = { postponedVersion = r.versionCode; release = null }) { Text("Plus tard", color = C.TextDim) }
        },
    )
}
