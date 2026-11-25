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
import com.extv.player.data.RemoteSupport.Step
import kotlinx.coroutines.launch

/**
 * Clic sur « Assistance » : si AnyDesk est prêt, l'ouvre ; sinon renvoie false et l'appelant affiche
 * [AssistanceSetupDialog].
 */
fun openAssistance(context: android.content.Context): Boolean =
    RemoteSupport.nextStep(context) == Step.READY && RemoteSupport.launch(context)

/**
 * Préparation de l'assistance, étape par étape : AnyDesk, puis son Control Plugin (téléchargés à la suite),
 * puis l'activation du plugin dans Accessibilité. Au retour dans EXTV, passe tout seul à l'étape suivante ;
 * quand tout est prêt, onReady est appelé (l'appelant ouvre AnyDesk).
 */
@Composable
fun AssistanceSetupDialog(onDismiss: () -> Unit, onReady: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(RemoteSupport.nextStep(context)) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ok = remember { FocusRequester() }

    fun startPlugin() {
        if (progress != null) return
        progress = 0f
        message = null
        scope.launch {
            message = RemoteSupport.installPlugin(context) { p -> progress = p }
            progress = null
        }
    }

    fun refresh() {
        val now = RemoteSupport.nextStep(context)
        if (now == Step.READY) {
            onReady()
            return
        }
        if (now != step) {
            step = now
            message = null
            // AnyDesk vient d'être installé : on enchaîne tout de suite avec le plugin.
            if (now == Step.PLUGIN) startPlugin()
        }
    }

    LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (progress == null) refresh() }

    fun action() {
        if (progress != null) return
        when (step) {
            Step.ANYDESK -> {
                progress = 0f
                message = null
                scope.launch {
                    message = RemoteSupport.installAnyDesk(context) { p -> progress = p }
                    progress = null
                }
            }
            Step.PLUGIN -> startPlugin()
            Step.ACCESSIBILITY -> {
                if (!RemoteSupport.openAccessibility(context)) {
                    message = "Ouvrez Paramètres > Accessibilité sur le boîtier et activez « AnyDesk Control Plugin »."
                }
            }
            Step.READY -> onReady()
        }
    }

    val anydeskReady = step != Step.ANYDESK
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        containerColor = C.Surface2,
        title = { Text("Assistance à distance", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    when (step) {
                        Step.ANYDESK ->
                            "Étape 1 sur 3 : AnyDesk n'est pas installé sur ce boîtier. Cette application gratuite permet à " +
                                "votre installateur de voir votre écran et de vous aider à distance. EXTV va télécharger " +
                                "AnyDesk puis son module de contrôle, à la suite."
                        Step.PLUGIN ->
                            "Étape 2 sur 3 : il faut « AnyDesk Control Plugin », le module qui permet à votre installateur " +
                                "de piloter le boîtier (et pas seulement de voir l'écran)."
                        Step.ACCESSIBILITY ->
                            "Étape 3 sur 3 : dans la page qui va s'ouvrir, choisissez « AnyDesk Control Plugin » " +
                                "(parfois dans « Services » ou « Applications téléchargées »), activez-le et acceptez. " +
                                "Revenez ensuite dans EXTV avec la touche Retour."
                        Step.READY -> "Tout est prêt."
                    },
                )
                progress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), color = C.Accent)
                    Text("Téléchargement… ${(it * 100).toInt()} %", color = C.TextDim, modifier = Modifier.padding(top = 6.dp))
                }
                message?.let { Text(it, color = C.Accent, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { action() }, modifier = Modifier.focusRequester(ok)) {
                val label = when {
                    progress != null -> "Patientez…"
                    step == Step.ACCESSIBILITY -> "Ouvrir Accessibilité"
                    message != null -> "Réessayer"
                    else -> "Télécharger"
                }
                Text(label, color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            // AnyDesk installé : on peut l'ouvrir sans finir (l'installateur verra l'écran, sans piloter).
            TextButton(onClick = {
                if (progress != null) return@TextButton
                if (anydeskReady) RemoteSupport.launch(context)
                onDismiss()
            }) {
                Text(if (anydeskReady) "Ouvrir AnyDesk quand même" else "Annuler", color = C.TextDim)
            }
        },
    )
}
