package com.extv.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.data.Category
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Thème ---------------------------------------------------------------------------------------

object C {
    val Bg = Color(0xFF0D1117)
    val Surface = Color(0xFF171C26)
    val SurfaceFocus = Color(0xFF26324A)
    val Selected = Color(0xFF173A35)
    val Accent = Color(0xFF1FB89A)
    val Text = Color(0xFFE8ECF2)
    val TextDim = Color(0xFF9AA4B2)
    val Error = Color(0xFFFF6B6B)
}

private val colors: ColorScheme = darkColorScheme(
    primary = C.Accent,
    background = C.Bg,
    surface = C.Surface,
    onSurface = C.Text,
    onBackground = C.Text,
)

@Composable
fun ExtvTheme(background: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        if (background) {
            Surface(color = C.Bg, contentColor = C.Text, modifier = Modifier.fillMaxSize()) { content() }
        } else {
            content()
        }
    }
}

// --- Éléments focalisables à la télécommande ---------------------------------------------------

@Composable
fun FocusTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .background(
                when {
                    focused -> C.SurfaceFocus
                    selected -> C.Selected
                    else -> C.Surface
                },
            )
            .border(2.dp, if (focused) C.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick),
    ) { content(focused) }
}

@Composable
fun ActionButton(label: String, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier, focusRequester = focusRequester) {
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 12.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspect: Float = 2f / 3f,
    fit: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    FocusTile(onClick = onClick, modifier = modifier, focusRequester = focusRequester) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color(0xFF222838))) {
                Text(
                    title.take(1).uppercase(),
                    Modifier.align(Alignment.Center),
                    fontSize = 32.sp,
                    color = C.TextDim,
                )
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (fit) 12.dp else 0.dp),
                )
            }
            Text(
                title,
                Modifier.padding(8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(bottom = 16.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold)
}

/** Colonne des catégories (gauche), avec cadenas pour les catégories protégées. */
@Composable
fun CategoryColumn(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    val parental = App.instance.parental
    LazyColumn(
        modifier.width(270.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(8.dp),
    ) {
        itemsIndexed(categories) { i, c ->
            FocusTile(
                onClick = { onSelect(c) },
                modifier = Modifier.fillMaxWidth(),
                selected = c.id == selectedId,
                focusRequester = if (i == 0) firstFocus else null,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (parental.isLocked(c)) Text("🔒")
                }
            }
        }
    }
}

// --- Chargement asynchrone -----------------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val error: Throwable) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit) {
    val value: T? get() = (state as? Load.Ok<T>)?.value
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    var attempt by remember { mutableIntStateOf(0) }
    val currentBlock by rememberUpdatedState(block)
    val state: State<Load<T>> = produceState<Load<T>>(Load.Loading, *keys, attempt) {
        value = Load.Loading
        value = try {
            Load.Ok(currentBlock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e)
        }
    }
    return Loader(state.value) { attempt++ }
}

@Composable
fun <T> LoadView(loader: Loader<T>, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        is Load.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
        is Load.Err -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.error.message ?: s.error.toString(), color = C.Error, modifier = Modifier.padding(16.dp))
                ActionButton("Réessayer", onClick = loader.retry)
            }
        }
        is Load.Ok -> content(s.value)
    }
}

// --- Contrôle parental ----------------------------------------------------------------------------

@Composable
fun PinDialog(
    title: String = "Code parental",
    onDismiss: () -> Unit,
    check: (String) -> Boolean = { it == App.instance.store.parentalPin },
    onSuccess: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v ->
                        pin = v.filter { it.isDigit() }.take(8)
                        error = false
                    },
                    label = { Text("Code PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error) Text("Code incorrect", color = C.Error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (check(pin)) onSuccess(pin) else error = true }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Renvoie une fonction de sélection qui demande le code PIN pour les catégories verrouillées. */
@Composable
fun rememberCategoryGate(onUnlocked: (Category) -> Unit): (Category) -> Unit {
    var pending by remember { mutableStateOf<Category?>(null) }
    val callback by rememberUpdatedState(onUnlocked)
    pending?.let { cat ->
        PinDialog(onDismiss = { pending = null }) {
            App.instance.parental.unlock(cat)
            pending = null
            callback(cat)
        }
    }
    return { cat -> if (App.instance.parental.isLocked(cat)) pending = cat else callback(cat) }
}

// --- Formatage ------------------------------------------------------------------------------------

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatHour(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))

fun formatDay(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(ms))

fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(ms))
