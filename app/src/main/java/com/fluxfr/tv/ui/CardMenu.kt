package com.fluxfr.tv.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.player.StartMode

/**
 * Entrée du menu d'actions d'une vignette (appui long). [danger] : action destructive (en rouge).
 * [navigates] : l'action ouvre autre chose (fiche, lecteur) : le focus n'est pas rendu à la vignette après coup.
 */
data class CardAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val navigates: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * État du menu d'une vignette.
 *
 * TV : l'appui long (OK maintenu) ne l'ouvre qu'au RELÂCHEMENT de OK ([pending] puis [open]). Ouvrir la fenêtre
 * pendant que la touche est encore enfoncée enverrait le relâchement au menu, et la Surface de la vignette (qui
 * attend ce relâchement pour oublier son appui long) avalerait alors le prochain clic.
 */
@Stable
class CardMenuState internal constructor() {
    internal var pending by mutableStateOf(false)
    var open by mutableStateOf(false)
        internal set
    internal val anchor = FocusRequester()
    internal var anchorFocused = false
    /** Incrémenté à la fermeture sans navigation : rend le focus à la vignette. */
    internal var restoreTick by mutableIntStateOf(0)

    internal fun requestOpen(isTv: Boolean) {
        if (isTv) pending = true else open = true
    }

    internal fun close(restoreFocus: Boolean) {
        open = false
        pending = false
        if (restoreFocus) restoreTick++
    }
}

@Composable
fun rememberCardMenuState(): CardMenuState = remember { CardMenuState() }

private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * À poser sur la surface focalisable de la vignette (avant la Surface TV) : mémorise le focus (pour le rendre à la
 * fermeture du menu) et, sur TV, ouvre le menu au relâchement de OK qui suit l'appui long. Ne consomme rien :
 * la Surface reçoit toujours le relâchement (et remet son appui long à zéro).
 */
internal fun Modifier.cardMenuAnchor(state: CardMenuState?): Modifier =
    if (state == null) this
    else this
        .focusRequester(state.anchor)
        .onFocusChanged {
            state.anchorFocused = it.isFocused
            if (!it.isFocused) state.pending = false
        }
        .onPreviewKeyEvent { e ->
            if (state.pending && e.type == KeyEventType.KeyUp && e.isOk()) {
                state.pending = false
                state.open = true
            }
            false
        }

/**
 * Menu d'actions d'une vignette : petit panneau sombre ancré à la vignette (à sa droite, sinon à sa gauche),
 * dans une fenêtre à part (Popup focalisable) : le focus y reste enfermé, ▲▼ parcourt les entrées, Retour
 * (ou un appui à côté, en tactile) le ferme. À composer DANS la vignette (elle sert d'ancre).
 *
 * Focus : la fenêtre du menu prend le focus de fenêtre, mais le focus Compose de l'écran reste sur la vignette
 * (rien ne bouge dessous : ni rail, ni défilement). À la fermeture (Retour ou action qui reste sur la page), la
 * vignette le redemande si besoin ; si elle a disparu (retirée de la rangée), l'appli le rend à la rangée.
 */
@Composable
internal fun CardMenuPopup(state: CardMenuState, title: String?, actions: () -> List<CardAction>) {
    val isTv = LocalIsTv.current
    // Rien de composé tant que le menu n'a jamais été fermé (grilles de milliers de vignettes).
    if (isTv && state.restoreTick > 0) LaunchedEffect(state.restoreTick) {
        // Laisse la fenêtre du menu se retirer (le focus de fenêtre revient à l'écran).
        repeat(2) { withFrameNanos { } }
        if (!state.anchorFocused) runCatching { state.anchor.requestFocus() }
    }
    if (!state.open) return
    val items = remember { actions() }
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { CardMenuPosition(gap = 10.dp.roundToPx(), margin = 16.dp.roundToPx()) }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { state.close(restoreFocus = true) },
        properties = PopupProperties(focusable = true),
    ) {
        // Les entrées du menu ne sont pas notées comme « dernière vignette » (retour de fiche, ▶ depuis le rail).
        CompositionLocalProvider(LocalFocusMemory provides null) {
            CardMenuPanel(title, items) { a ->
                state.close(restoreFocus = !a.navigates)
                a.onClick()
            }
        }
    }
}

/** Place le menu à droite de la vignette, sinon à gauche, sinon par-dessus ; toujours dans l'écran. */
private class CardMenuPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val maxX = (windowSize.width - margin - w).coerceAtLeast(margin)
        val maxY = (windowSize.height - margin - h).coerceAtLeast(margin)
        val right = anchorBounds.right + gap
        val left = anchorBounds.left - gap - w
        val x = when {
            right <= maxX -> right
            left >= margin -> left
            else -> anchorBounds.left + (anchorBounds.width - w) / 2
        }.coerceIn(margin, maxX)
        val y = anchorBounds.top.coerceIn(margin, maxY)
        return IntOffset(x, y)
    }
}

@Composable
private fun CardMenuPanel(title: String?, items: List<CardAction>, onPick: (CardAction) -> Unit) {
    val isTv = LocalIsTv.current
    val first = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(140)) }
    if (isTv) {
        LaunchedEffect(Unit) {
            // Premier élément sélectionné dès l'ouverture (quelques essais le temps que la fenêtre s'attache).
            repeat(6) {
                if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }
    // Un relâchement de OK sans appui vu ici (appui commencé sur la vignette) ne déclenche rien.
    var okDownSeen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .graphicsLayer {
                val v = appear.value
                alpha = v
                scaleX = 0.92f + 0.08f * v
                scaleY = 0.92f + 0.08f * v
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .width(if (isTv) 270.dp else 250.dp)
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Palette.CardHigh)
            .border(1.dp, Palette.Stroke, shape)
            .onPreviewKeyEvent { e ->
                if (!e.isOk()) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> { okDownSeen = true; false }
                    KeyEventType.KeyUp -> !okDownSeen
                    else -> false
                }
            }
            .padding(vertical = 8.dp, horizontal = 6.dp)
            .focusGroup(),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        items.forEachIndexed { i, a ->
            CardMenuRow(a, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(a) }
        }
    }
}

@Composable
private fun CardMenuRow(action: CardAction, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val isTv = LocalIsTv.current
    val focusedBg = if (action.danger) Palette.Error else Color.White
    val color = when {
        focused && action.danger -> Color.White
        focused -> Color.Black
        action.danger -> Palette.Error
        else -> Color.White
    }
    FocusSurface(
        onClick,
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(9.dp),
        focusedScale = 1.03f,
        containerColor = Color.Transparent,
        focusedContainerColor = focusedBg,
        focusedContentColor = color,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (isTv) Modifier else Modifier.heightIn(min = 48.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxIcon(action.icon, size = 18.dp, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(
                action.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- Actions communes

internal fun shortToast(ctx: Context, text: String) {
    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
}

/** « Ajouter à Ma liste » / « Retirer de Ma liste » (état lu à l'ouverture du menu). */
internal fun myListAction(ctx: Context, key: String): CardAction {
    Favorites.load(ctx)
    val inList = Favorites.contains(key)
    return CardAction(
        if (inList) "Retirer de Ma liste" else "Ajouter à Ma liste",
        if (inList) FluxIcons.Check else FluxIcons.Plus,
    ) {
        val added = Favorites.toggle(ctx, key)
        shortToast(ctx, if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
    }
}

/**
 * Menu d'un film : « Reprendre » + « Lire depuis le début » (position enregistrée) ou « Lecture », « Plus d'infos »
 * (fiche), Ma liste, puis [extra]. [play] lance le film avec le mode de démarrage voulu.
 */
internal fun movieActions(
    ctx: Context,
    m: Movie,
    progress: Progress?,
    onInfo: (() -> Unit)?,
    play: (StartMode) -> Unit,
    extra: List<CardAction> = emptyList(),
): List<CardAction> = buildList {
    val resumable = progress != null && !progress.finished && progress.position > 0
    if (resumable) {
        add(CardAction("Reprendre", FluxIcons.Play, navigates = true) { play(StartMode.RESUME) })
        add(CardAction("Lire depuis le début", FluxIcons.Refresh, navigates = true) { play(StartMode.FROM_START) })
    } else {
        add(CardAction("Lecture", FluxIcons.Play, navigates = true) { play(StartMode.ASK) })
    }
    if (onInfo != null) add(CardAction("Plus d'infos", FluxIcons.Info, navigates = true, onClick = onInfo))
    add(myListAction(ctx, Favorites.movieKey(m.id)))
    addAll(extra)
}

/** Position enregistrée d'un film, lue à l'ouverture du menu (écrans sans carte des progressions). */
internal fun savedMovieProgress(ctx: Context, m: Movie): Progress? =
    runCatching { ProgressStore.get(ctx).get(Progress.movieKey(m.id)) }.getOrNull()

/** Menu d'une série :« Voir les épisodes » (fiche), Ma liste, puis [extra]. */
internal fun seriesActions(
    ctx: Context,
    s: Series,
    onOpen: () -> Unit,
    extra: List<CardAction> = emptyList(),
): List<CardAction> = buildList {
    add(CardAction("Voir les épisodes", FluxIcons.Series, navigates = true, onClick = onOpen))
    add(myListAction(ctx, Favorites.seriesKey(s.id)))
    addAll(extra)
}
