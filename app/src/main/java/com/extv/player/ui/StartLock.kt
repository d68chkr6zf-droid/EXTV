package com.extv.player.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.extv.player.App

/** Allumage de l'écran pour lequel EXTV a déjà été déverrouillé (-1 : pas encore depuis le lancement). */
private val unlockedAtWake = mutableIntStateOf(-1)

/** Évite de redemander le code juste après l'avoir choisi dans les paramètres. */
fun markStartUnlocked() {
    unlockedAtWake.intValue = App.instance.screenOnCount.intValue
}

/**
 * Verrouillage au démarrage : si l'abonné l'a activé dans les paramètres, EXTV demande son code à
 * 4 chiffres à l'ouverture et à chaque sortie de veille de la TV. Fenêtre à part : la mise à jour
 * obligatoire, affichée par-dessus, n'est jamais bloquée.
 */
@Composable
fun StartLock() {
    val store = App.instance.store
    val wake by App.instance.screenOnCount
    if (!store.startLock || unlockedAtWake.intValue == wake) return
    val activity = LocalContext.current as? Activity

    var code by remember(wake) { mutableStateOf("") }
    var error by remember(wake) { mutableStateOf(false) }
    var forgot by remember(wake) { mutableStateOf(false) }

    fun type(d: String) {
        if (code.length >= 4) return
        code += d
        error = false
        if (code.length == 4) {
            if (code == store.startPin) {
                unlockedAtWake.intValue = wake
            } else {
                error = true
                code = ""
            }
        }
    }

    Dialog(
        onDismissRequest = { activity?.finish() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
            if (forgot) {
                ForgotCode(
                    onBack = { forgot = false },
                    onUnlocked = {
                        store.startLock = false
                        store.startPin = ""
                        unlockedAtWake.intValue = wake
                    },
                )
                return@Box
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val digit = k.keyCode - KeyEvent.KEYCODE_0
                    if (digit in 0..9) {
                        if (k.action == KeyEvent.ACTION_DOWN) type("$digit")
                        true
                    } else if (k.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (k.action == KeyEvent.ACTION_DOWN) code = code.dropLast(1)
                        true
                    } else {
                        false
                    }
                },
            ) {
                ExtvLogo(30.sp)
                Text("Entrez votre code", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .background(if (i < code.length) C.Accent else Color.Transparent)
                                .border(2.dp, C.Accent, CircleShape),
                        )
                    }
                }
                Text(
                    if (error) "Code incorrect" else " ",
                    color = C.Error,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                // Pavé numérique pour les télécommandes sans chiffres.
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { key ->
                                FocusTile(
                                    onClick = {
                                        when (key) {
                                            "⌫" -> code = code.dropLast(1)
                                            "OK" -> if (code.length < 4) error = true
                                            else -> type(key)
                                        }
                                    },
                                    modifier = Modifier.size(68.dp, 56.dp),
                                    focusRequester = if (key == "1") first else null,
                                    shape = RoundedCornerShape(12.dp),
                                    normalColor = C.Surface2,
                                ) {
                                    Text(key, Modifier.align(Alignment.Center), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                FocusTile(
                    onClick = { forgot = true },
                    modifier = Modifier.padding(top = 18.dp),
                    shape = RoundedCornerShape(20.dp),
                    normalColor = Color.Transparent,
                ) {
                    Text("Code oublié ?", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = C.TextDim)
                }
            }
        }
    }
}

/** Code oublié : le mot de passe de l'abonnement déverrouille et désactive le verrouillage. */
@Composable
private fun ForgotCode(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val profile = remember { App.instance.store.activeProfile() }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(420.dp)) {
        Text("Code oublié", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Entrez le mot de passe de votre abonnement. Le verrouillage sera désactivé, vous pourrez choisir un nouveau code dans les paramètres.",
            color = C.TextDim,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = false
            },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.focusRequester(field),
        )
        if (error) Text("Mot de passe incorrect", color = C.Error, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Valider", primary = true) {
                if (profile != null && password.isNotEmpty() && password == profile.password) onUnlocked() else error = true
            }
            ActionButton("Retour") { onBack() }
        }
    }
}
