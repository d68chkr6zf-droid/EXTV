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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.extv.player.data.RemoteSupport
import kotlinx.coroutines.launch

/**
 * Clic sur « Assistance à distance » alors qu'AnyDesk n'est pas installé : propose de le télécharger.
 * onInstalled est appelé au retour dans EXTV une fois AnyDesk installé.
 */
@Composable
fun AnyDeskInstallDialog(onDismiss: () -> Unit, onInstalled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (RemoteSupport.isInstalled(context)) onInstalled() }

    fun download() {
        if (progress != null) return
        progress = 0f
        message = null
        scope.launch {
            message = RemoteSupport.downloadAndInstall(context) { p -> progress = p }
            progress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        containerColor = C.Surface2,
        title = { Text("Assistance à distance", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "AnyDesk n'est pas installé sur ce boîtier. Cette application gratuite permet à votre installateur " +
                        "de voir votre écran et de vous aider à distance. Voulez-vous la télécharger maintenant ?",
                )
                progress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), color = C.Accent)
                    Text("Téléchargement d'AnyDesk… ${(it * 100).toInt()} %", color = C.TextDim, modifier = Modifier.padding(top = 6.dp))
                }
                message?.let { Text(it, color = C.Accent, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { download() }, modifier = Modifier.focusRequester(ok)) {
                val label = when {
                    progress != null -> "Patientez…"
                    message != null -> "Réessayer"
                    else -> "Télécharger"
                }
                Text(label, color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (progress == null) onDismiss() }) { Text("Annuler", color = C.TextDim) }
        },
    )
}
