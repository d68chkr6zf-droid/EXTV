package com.extv.player.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.extv.player.App
import com.extv.player.data.ContentType

// --- Menu de l'appui long sur une vignette et déplacement des favoris ----------------------------

private fun isOkKey(code: Int) =
    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

/** Place le menu juste sous la vignette, ou au-dessus s'il n'y a pas la place en bas de l'écran. */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Petit menu ouvert par un appui long sur OK : « Ajouter aux favoris » ou « Retirer des favoris »,
 * puis « Déplacer » (grisé tant que l'élément n'est pas en favori). À placer dans la vignette.
 */
@Composable
fun BoxScope.FavoriteMenu(isFavorite: Boolean, onToggle: () -> Unit, onMove: () -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    var anchorWidth by remember { mutableIntStateOf(0) }
    Box(Modifier.matchParentSize().onSizeChanged { anchorWidth = it.width }) {
        Popup(
            popupPositionProvider = remember(density) { BelowAnchor(with(density) { 6.dp.roundToPx() }) },
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            val first = remember { FocusRequester() }
            // L'OK encore enfoncé de l'appui long (répétitions, relâchement) ne doit rien valider dans le menu :
            // seul un nouvel appui compte.
            val armed = remember { booleanArrayOf(false) }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .width(with(density) { anchorWidth.toDp() }.coerceIn(230.dp, 360.dp))
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        if (!isOkKey(k.keyCode)) return@onPreviewKeyEvent false
                        if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) armed[0] = true
                        !armed[0]
                    }
                    .shadow(16.dp, shape)
                    .background(C.Surface2, shape)
                    .border(1.dp, Color(0xFF3A3238), shape)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MenuEntry(if (isFavorite) "★" else "☆", if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris", first) {
                    onDismiss()
                    onToggle()
                }
                if (isFavorite) {
                    MenuEntry("⇅", "Déplacer") {
                        onDismiss()
                        onMove()
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⇅", Modifier.width(30.dp), color = Color(0xFF5F5860), fontSize = 17.sp)
                        Text("Déplacer", color = Color(0xFF5F5860), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: String, label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusTile(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(8.dp),
        normalColor = Color.Transparent,
        focusedColor = C.Accent,
        focusScale = 1f,
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = if (focused) Color(0xFF1A0B10) else C.Text
            Text(icon, Modifier.width(30.dp), color = color, fontSize = 17.sp)
            Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

enum class MoveDir { UP, DOWN, LEFT, RIGHT }

/**
 * Touches pendant le déplacement d'un favori : les flèches déplacent, OK valide la place.
 * Retour (annuler) est géré par l'écran avec un BackHandler.
 */
fun Modifier.moveKeys(active: Boolean, onMove: (MoveDir) -> Unit, onDone: () -> Unit): Modifier =
    if (!active) this else onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        val dir = when (k.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> MoveDir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> MoveDir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> MoveDir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> MoveDir.RIGHT
            else -> null
        }
        when {
            dir != null -> {
                if (k.action == KeyEvent.ACTION_DOWN) onMove(dir)
                true
            }
            isOkKey(k.keyCode) -> {
                if (k.action == KeyEvent.ACTION_UP) onDone()
                true
            }
            else -> false
        }
    }

/**
 * Ordre affiché d'une liste de favoris pendant (et après) un déplacement : provisoire tant que
 * OK n'a pas validé, remis comme avant par Retour.
 */
class FavoriteMover<T>(private val type: ContentType, private val idOf: (T) -> String) {
    /** Élément en cours de déplacement. */
    var movingId by mutableStateOf<String?>(null)
        private set

    /** Ordre à afficher à la place de la liste chargée (null : ordre chargé). */
    var order by mutableStateOf<List<T>?>(null)
        private set
    private var before: List<T>? = null

    val active: Boolean get() = movingId != null

    fun display(loaded: List<T>): List<T> = order ?: loaded

    fun isMoving(item: T) = movingId != null && idOf(item) == movingId

    fun index(): Int = order?.indexOfFirst { idOf(it) == movingId } ?: -1

    fun start(list: List<T>, id: String) {
        if (list.none { idOf(it) == id }) return
        before = order
        order = list
        movingId = id
    }

    /** Déplace l'élément de [delta] places ; faux s'il est déjà au bout. */
    fun shift(delta: Int): Boolean {
        val list = order ?: return false
        val i = index()
        val j = i + delta
        if (i < 0 || j !in list.indices) return false
        order = list.toMutableList().apply { add(j, removeAt(i)) }
        return true
    }

    fun commit() {
        order?.let { list -> App.instance.store.reorderFavorites(type, list.map(idOf)) }
        movingId = null
    }

    fun cancel() {
        order = before
        movingId = null
    }

    /** Oublie l'ordre affiché (changement de catégorie, liste rechargée). */
    fun reset() {
        order = null
        before = null
        movingId = null
    }
}

/** En bas, au centre de la fenêtre, quel que soit l'endroit où il est appelé. */
private object WindowBottom : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset((windowSize.width - popupContentSize.width) / 2, windowSize.height - popupContentSize.height)
}

/** Rappel des touches en bas de l'écran pendant un déplacement. */
@Composable
fun MoveHint(arrows: String) {
    Popup(popupPositionProvider = WindowBottom, properties = PopupProperties(focusable = false)) {
        Text(
            "$arrows  déplacer   ·   OK  valider la place   ·   Retour  annuler",
            Modifier.padding(bottom = 18.dp)
                .background(Color(0xF0221E27), RoundedCornerShape(20.dp))
                .border(1.dp, C.Accent, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
            color = C.Text,
            fontSize = 14.sp,
        )
    }
}
