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
import kotlinx.coroutines.launch

/** Intervalle entre deux vérifications tant que l'appli est ouverte. */
private const val CHECK_EVERY_MS = 60_000L

/**
 * Mise à jour obligatoire : dès qu'une nouvelle version est publiée, la fenêtre bloque l'appli,
 * télécharge et installe tout de suite (sans clic sur Android 12+ quand c'est possible).
 */
@Composable
fun UpdatePrompt() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Vérifie à l'ouverture puis toutes les minutes tant que l'appli est ouverte. Pendant la lecture d'une
    // vidéo (écran du lecteur), on attend le retour dans l'appli pour ne pas interrompre le film.
    val activity = context as? androidx.activity.ComponentActivity
    LaunchedEffect(Unit) {
        while (true) {
            val visible = activity == null || activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (release == null && visible) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
                runCatching { Updater.check() }
                    .onSuccess { found ->
                        Updater.lastAutoCheck.value = if (found == null) "$time · à jour" else "$time · version ${found.versionName} trouvée"
                        if (found != null) release = found
                    }
                    .onFailure { Updater.lastAutoCheck.value = "$time · échec (${it.javaClass.simpleName})" }
            }
            kotlinx.coroutines.delay(CHECK_EVERY_MS)
        }
    }

    val r = release ?: return
    val ok = remember { FocusRequester() }
    var installing by remember { mutableStateOf(false) }
    val failed by Updater.installFailed

    fun update() {
        if (progress != null) return
        installing = false
        if (!Updater.canInstall(context)) {
            message = "Autorisez EXTV à installer des applications, puis revenez et appuyez sur OK."
            Updater.openInstallPermission(context)
            return
        }
        progress = 0f
        message = null
        scope.launch {
            val apk = runCatching { Updater.download(context) { p -> progress = p } }.getOrNull()
            progress = null
            when {
                apk == null -> message = "Téléchargement impossible, réessayez dans quelques minutes."
                !Updater.isNewer(context, apk) ->
                    message = "La version ${r.versionName} est encore en cours de publication. Réessayez dans 2 minutes."
                else -> runCatching { Updater.install(context, apk) }
                    .onSuccess { installing = true }
                    .onFailure { message = "L'installation n'a pas pu s'ouvrir (${it.javaClass.simpleName}). Essayez Paramètres > Rechercher une mise à jour." }
            }
        }
    }

    // Démarre tout seul ; au retour dans l'appli (installation annulée), on relance.
    LaunchedEffect(r) {
        runCatching { ok.requestFocus() }
        update()
    }
    LaunchedEffect(failed) { if (failed != null) installing = false }
    // Retour des réglages après avoir autorisé l'installation : on reprend tout seul.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (progress == null && !installing && Updater.canInstall(context) && message?.startsWith("Autorisez") == true) update()
    }

    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = C.Surface2,
        title = { Text("Mise à jour disponible", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("La version ${r.versionName} d'EXTV est obligatoire. Elle s'installe maintenant, vos favoris et réglages sont conservés.")
                if (installing && failed == null) {
                    Text("Installation en cours… EXTV va redémarrer. Si Android le demande, choisissez « Installer ».", color = C.TextDim, modifier = Modifier.padding(top = 12.dp))
                }
                failed?.let { Text("L'installation n'a pas abouti ($it). Appuyez sur Réessayer.", color = C.Accent, modifier = Modifier.padding(top = 12.dp)) }
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
                Text(if (progress != null) "Patientez…" else "Réessayer", color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}
