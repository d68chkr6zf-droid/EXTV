package com.extv.player.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.extv.player.App
import com.extv.player.data.Category
import com.extv.player.data.normalized
import kotlinx.coroutines.CancellationException
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Palette « salle de cinéma » : noir profond, velours rouge, or ---------------------------------

object C {
    val Bg = Color(0xFF0B0A0F)
    val BgTop = Color(0xFF1A0B10)
    val Surface = Color(0xFF17151C)
    val Surface2 = Color(0xFF221E27)
    val SurfaceFocus = Color(0xFF3A2E2A)
    val Selected = Color(0xFF5C1324)
    val Accent = Color(0xFFE8B04B) // or
    val AccentDark = Color(0xFFB8862E)
    val Velvet = Color(0xFFB3122E) // rouge velours
    val Text = Color(0xFFF3EEE6)
    val TextDim = Color(0xFFA59E95)
    val Error = Color(0xFFFF6B6B)

    val LiveGradient = listOf(Color(0xFFC0182F), Color(0xFF5E0718))
    val MoviesGradient = listOf(Color(0xFFEFBE5A), Color(0xFFA9561A))
    val SeriesGradient = listOf(Color(0xFF7A35B8), Color(0xFF2A0F52))
}

private val colors: ColorScheme = darkColorScheme(
    primary = C.Accent,
    onPrimary = Color.Black,
    secondary = C.Velvet,
    background = C.Bg,
    surface = C.Surface2,
    onSurface = C.Text,
    onBackground = C.Text,
)

@Composable
fun ExtvTheme(background: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides C.Text) {
            if (background) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(C.BgTop, C.Bg, Color(0xFF060508))))
                        .systemBarsPadding(),
                ) {
                    // Certains téléviseurs coupent les bords de l'image (surbalayage) : marge de sécurité.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val pct = effectiveOverscan() / 100f
                        Box(Modifier.fillMaxSize().padding(horizontal = maxWidth * pct, vertical = maxHeight * pct)) { content() }
                    }
                }
            } else {
                content()
            }
        }
    }
}

// --- Adaptation à l'écran : smartphone, tablette, téléviseur ----------------------------------

/** Valeur courante du réglage « Cadrage de l'écran », observée par l'interface. */
val overscanSetting by lazy { mutableIntStateOf(runCatching { App.instance.store.overscan }.getOrDefault(0)) }

val OVERSCAN_VALUES = listOf(0, 2, 4, 6, 8, 10, -1)

/** Marge de sécurité en % : réglage choisi, ou 5 % sur TV en automatique. */
@Composable
fun effectiveOverscan(): Int {
    val value = overscanSetting.intValue
    if (value >= 0) return value
    val tv = (LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    return if (tv) 5 else 0
}

enum class WindowSize { Compact, Medium, Expanded }

@Composable
fun windowSize(): WindowSize {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width < 600 -> WindowSize.Compact
        width < 900 -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

/** Marge extérieure des écrans selon la taille. */
@Composable
fun screenPadding(): Dp = when (windowSize()) {
    WindowSize.Compact -> 14.dp
    WindowSize.Medium -> 24.dp
    WindowSize.Expanded -> 36.dp
}

@Composable
fun ExtvLogo(size: TextUnit = 30.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("EX", fontSize = size, fontWeight = FontWeight.Black, color = C.Accent, letterSpacing = 1.sp)
        Text("TV", fontSize = size, fontWeight = FontWeight.Black, color = C.Text, letterSpacing = 1.sp)
    }
}

// --- Éléments focalisables (télécommande) et tactiles -------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    normalColor: Color = C.Surface,
    focusedColor: Color = C.SurfaceFocus,
    selectedColor: Color = C.Selected,
    focusScale: Float = 1.04f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "scale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.transformOrigin = transformOrigin
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .background(
                when {
                    focused -> focusedColor
                    selected -> selectedColor
                    else -> normalColor
                },
            )
            .border(2.dp, if (focused) C.Accent else Color.Transparent, shape)
            .then(if (onLongClick != null) Modifier.okLongPress(onClick, onLongClick) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) { CompositionLocalProvider(LocalTileFocused provides focused) { content(focused) } }
}

/** Vrai à l'intérieur d'une [FocusTile] qui a le focus. */
val LocalTileFocused = compositionLocalOf { false }

/**
 * Texte d'une ligne dans une [FocusTile] : s'il est trop long, il défile jusqu'au bout tant que la tuile
 * a le focus (les autres restent coupés par « … » pour ne pas agiter l'écran).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusMarquee(): Modifier =
    if (LocalTileFocused.current) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800, repeatDelayMillis = 1500, velocity = 45.dp) else this

/**
 * Texte de plusieurs lignes dans une [FocusTile] : coupé comme d'habitude, mais quand la tuile a le focus
 * et que le texte ne tient pas, il passe sur une seule ligne qui défile jusqu'au bout.
 */
@Composable
fun TileText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
) {
    var cut by remember(text) { mutableStateOf(false) }
    val scroll = cut && LocalTileFocused.current
    Text(
        text,
        if (scroll) modifier.focusMarquee() else modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = if (scroll) 1 else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!scroll) cut = it.hasVisualOverflow },
    )
}

/**
 * Télécommande : OK court = onClick, OK maintenu = onLongClick (une seule fois, sans clic ensuite).
 * Géré ici plutôt que par combinedClickable, qui sur certaines TV déclenche aussi le clic.
 */
@Composable
private fun Modifier.okLongPress(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val longDone = remember { booleanArrayOf(false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!ok) return@onPreviewKeyEvent false
        when (k.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (k.repeatCount == 0) {
                    longDone[0] = false
                } else if (!longDone[0] && (k.isLongPress || k.repeatCount >= 2)) {
                    longDone[0] = true
                    longClick()
                }
            }
            android.view.KeyEvent.ACTION_UP -> {
                if (!longDone[0] && !k.isCanceled) click()
                longDone[0] = false
            }
        }
        true
    }
}

@Composable
fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    FocusTile(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(24.dp),
        // Agrandi depuis le bord gauche pour rester aligné avec les boutons du dessous.
        transformOrigin = TransformOrigin(0f, 0.5f),
        normalColor = if (primary) C.Accent else C.Surface2,
        focusedColor = if (primary) Color(0xFFFFD27A) else C.SurfaceFocus,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.Black else C.Text,
        )
    }
}

/** Bouton rond à icône (barre du haut). */
@Composable
fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, size: Dp = 46.dp, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = Modifier.size(size), shape = CircleShape, normalColor = C.Surface2, focusScale = 1.12f) {
        Icon(icon, contentDescription = description, tint = C.Text, modifier = Modifier.align(Alignment.Center).size(size * 0.5f))
    }
}

/** Affiche de film / série avec note et titre en surimpression. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rating: String? = null,
    aspect: Float = 2f / 3f,
    fit: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    favorite: Boolean = false,
    subtitle: String? = null,
    progress: Float? = null,
) {
    FocusTile(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(10.dp),
        focusScale = 1.07f,
        normalColor = C.Surface2,
        focusedColor = C.Surface2,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Brush.linearGradient(placeholderGradient(title)))) {
            Text(title.take(1).uppercase(), Modifier.align(Alignment.Center), fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color(0x55FFFFFF))
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (fit) 14.dp else 0.dp),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
            ) {
                Column {
                    TileText(title, maxLines = if (subtitle == null) 2 else 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    // Chaînes : programme en cours (EPG) et avancement.
                    if (subtitle != null) Text(subtitle, Modifier.focusMarquee(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color(0xFFD8D2DC))
                    if (progress != null) ThinProgress(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            formatRating(rating)?.let { r ->
                Text(
                    r,
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(C.Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (favorite) {
                Text(
                    "★",
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    color = C.Accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Dégradé de remplacement quand l'affiche est absente (couleur stable par titre). */
private val placeholderPalettes = listOf(
    listOf(Color(0xFF5E0718), Color(0xFF1A0B10)),
    listOf(Color(0xFF6B4A12), Color(0xFF1C130A)),
    listOf(Color(0xFF2A0F52), Color(0xFF0E0A1C)),
    listOf(Color(0xFF0E3A4A), Color(0xFF081418)),
    listOf(Color(0xFF3A3A3A), Color(0xFF111111)),
)

fun placeholderGradient(seed: String): List<Color> = placeholderPalettes[(seed.hashCode() and 0x7fffffff) % placeholderPalettes.size]

fun formatRating(raw: String?): String? {
    val v = raw?.replace(',', '.')?.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    val ten = if (v > 10) v / 10 else v
    return if (ten % 1.0 == 0.0) "${ten.toInt()}" else String.format(Locale.US, "%.1f", ten)
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(bottom = 14.dp),
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.5.sp,
        color = C.Text,
    )
}

// --- Listes de catégories avec compteurs ---------------------------------------------------------

data class CategoryEntry(val category: Category, val count: Int?)

@Composable
fun SearchField(value: String, placeholder: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = C.TextDim, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = C.TextDim) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Accent,
            unfocusedBorderColor = C.Surface2,
            focusedContainerColor = C.Surface,
            unfocusedContainerColor = C.Surface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Colonne latérale (TV / tablette) : recherche + catégories avec nombre d'éléments. */
@Composable
fun CategorySidebar(
    entries: List<CategoryEntry>,
    selectedId: String?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    query: String? = null,
    onQuery: ((String) -> Unit)? = null,
    searchHint: String = "Rechercher",
    showSearch: Boolean = true,
) {
    val parental = App.instance.parental
    var filter by remember { mutableStateOf("") }
    // Avec onQuery, le champ cherche dans le contenu (TOUT) ; sinon il filtre les catégories.
    val searchesContent = onQuery != null
    val shown = remember(entries, filter, searchesContent) {
        val f = filter.normalized().trim()
        if (searchesContent || f.isEmpty()) entries else entries.filter { it.category.name.normalized().contains(f) }
    }
    Column(modifier) {
        if (!showSearch) {
            // Pas de champ de recherche (TV en direct).
        } else if (onQuery != null) {
            SearchField(query.orEmpty(), searchHint, Modifier.padding(bottom = 8.dp), onQuery)
        } else {
            SearchField(filter, "Rechercher dans les catégories", Modifier.padding(bottom = 8.dp)) { filter = it }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
            itemsIndexed(shown) { i, e ->
                val selected = e.category.id == selectedId
                FocusTile(
                    onClick = { onSelect(e.category) },
                    modifier = Modifier.fillMaxWidth(),
                    selected = selected,
                    focusRequester = if (i == 0) firstFocus else null,
                    normalColor = Color.Transparent,
                    focusScale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            e.category.name.uppercase(),
                            Modifier.weight(1f).focusMarquee(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp,
                            color = if (selected) C.Accent else C.Text,
                        )
                        if (parental.isLocked(e.category)) Text("🔒", fontSize = 13.sp)
                        e.count?.let { Text("$it", color = C.TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}

/** Version smartphone : catégories en pastilles défilantes. */
@Composable
fun CategoryChips(entries: List<CategoryEntry>, selectedId: String?, onSelect: (Category) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        itemsIndexed(entries) { _, e ->
            val selected = e.category.id == selectedId
            FocusTile(
                onClick = { onSelect(e.category) },
                selected = selected,
                shape = RoundedCornerShape(20.dp),
                normalColor = C.Surface2,
            ) {
                Text(
                    buildString {
                        append(e.category.name)
                        e.count?.let { append("  $it") }
                    },
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = if (selected) C.Accent else C.Text,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// --- Aperçu vidéo intégré (libVLC) ----------------------------------------------------------------

/** Petit lecteur VLC intégré à un écran (aperçu de chaîne, multi-écrans). url = null : arrêt. */
@Composable
fun VlcPreview(url: String?, muted: Boolean, modifier: Modifier = Modifier, idleText: String? = "Aucune lecture") {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        // Rendu des captures d'écran : pas de moteur vidéo, une image de remplacement.
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF2B1A3A), Color(0xFF0E2236), Color(0xFF3A1A12))))) {
            if (url != null) Text("▶", Modifier.align(Alignment.Center), fontSize = 40.sp, color = Color(0xCCFFFFFF))
        }
        return
    }
    val context = LocalContext.current
    val app = App.instance
    val layout = remember { VLCVideoLayout(context) }
    val player = remember { MediaPlayer(app.libVlc) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.detachViews()
            player.release()
        }
    }
    LifecycleResumeEffect(url) {
        // La surface vidéo est recréée au retour du plein écran : on la rattache à chaque reprise,
        // sinon l'aperçu reste noir.
        if (!player.vlcVout.areViewsAttached()) player.attachViews(layout, null, false, false)
        if (url != null) {
            val media = Media(app.libVlc, Uri.parse(url))
            media.setHWDecoderEnabled(app.store.hwDecoding, false)
            media.addOption(":http-user-agent=${app.store.userAgent}")
            media.addOption(":network-caching=${app.store.networkCaching}")
            media.addOption(":live-caching=${app.store.networkCaching}")
            player.setMedia(media)
            media.release()
            player.play()
        } else {
            player.stop()
        }
        onPauseOrDispose {
            player.stop()
            player.detachViews()
        }
    }
    LaunchedEffect(muted, url) { player.setVolume(if (muted) 0 else 100) }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { layout }, modifier = Modifier.fillMaxSize())
        if (url == null && idleText != null) Text(idleText, Modifier.align(Alignment.Center), color = C.TextDim)
    }
}

// --- Chargement asynchrone -----------------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val error: Throwable) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val value: T? get() = (state as? Load.Ok<T>)?.value
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        // Captures d'écran (données fictives) : chargement immédiat.
        val result = remember(*keys) { runCatching { kotlinx.coroutines.runBlocking { block() } } }
        return Loader(result.fold({ Load.Ok(it) }, { Load.Err(it) })) {}
    }
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
        containerColor = C.Surface2,
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
        confirmButton = { TextButton(onClick = { if (check(pin)) onSuccess(pin) else error = true }) { Text("Valider", color = C.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = C.TextDim) } },
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

// --- Divers ---------------------------------------------------------------------------------------

@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }
}

@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(periodMs)
        }
    }
    return now
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(C.Accent))
    }
}

fun timeAgo(ms: Long?, now: Long = System.currentTimeMillis()): String {
    if (ms == null) return "jamais"
    val minutes = (now - ms) / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatHour(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))

fun formatDay(ms: Long): String = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(ms))

fun formatLongDate(ms: Long): String = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(ms))

fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(ms))
