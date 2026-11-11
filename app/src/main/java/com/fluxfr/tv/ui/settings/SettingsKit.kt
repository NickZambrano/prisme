package com.fluxfr.tv.ui.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.fluxfr.tv.ui.LocalIsTv
import com.fluxfr.tv.ui.Poppins
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.ui.FluxIcon
import com.fluxfr.tv.ui.ModalPanel
import com.fluxfr.tv.ui.Palette
import com.fluxfr.tv.ui.PinPad
import com.fluxfr.tv.ui.TvTextField
import com.fluxfr.tv.ui.profileBrush
import com.fluxfr.tv.ui.rememberIsTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// ------------------------------------------------------------------------------------ Icônes

/** Icônes propres aux réglages (tracés Material, licence Apache 2.0). */
internal object SettingsIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Person by lazy {
        icon("person", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    }
    val Server by lazy {
        icon("server", "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1zM7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1zM7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z")
    }
    val Category by lazy {
        icon("category", "M12,2l-5.5,9h11zM17.5,13c-2.49,0 -4.5,2.01 -4.5,4.5s2.01,4.5 4.5,4.5 4.5,-2.01 4.5,-4.5 -2.01,-4.5 -4.5,-4.5zM3,21.5h8v-8H3z")
    }
    val Sync by lazy {
        icon("sync", "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z")
    }
    val Download by lazy { icon("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val Chevron by lazy { icon("chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
    val Back by lazy { icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Add by lazy { icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val Delete by lazy {
        icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }
    val Logout by lazy {
        icon("logout", "M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4V5z")
    }
    val Lock by lazy {
        icon("lock", "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z")
    }
    val Cloud by lazy {
        icon("cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
    }
    val Edit by lazy {
        icon("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    }
}

// ------------------------------------------------------------------------------------ État partagé

/** Sous-page affichée dans le panneau de droite (au-dessus de la section). */
internal sealed interface SubPage {
    data class ProfileSheet(val id: String) : SubPage
    data class Categories(val kind: String, val label: String) : SubPage
}

/**
 * État de l'écran Réglages partagé par les sections : fenêtre modale, sous-page, rechargement
 * du catalogue en attente et focus à rendre à la ligne qui a ouvert une fenêtre.
 */
@Stable
internal class SettingsUi(
    val settings: Settings,
    val isTv: Boolean,
    val scope: CoroutineScope,
    private val onReload: () -> Unit,
    private val onLogout: () -> Unit,
) {
    var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    var sub by mutableStateOf<SubPage?>(null)
    var pendingReload by mutableStateOf(false)
        private set
    /** Incrémenté à la fermeture d'une fenêtre : les lignes relisent l'état stocké (Google, compte…). */
    var tick by mutableIntStateOf(0)
        private set
    /** Clé de la ligne qui doit reprendre le focus (après une fenêtre ou une sous-page). */
    var pendingFocus by mutableStateOf<String?>(null)
    var lastFocusKey: String? = null
    private var overlayReturn: String? = null
    private var subReturn: String? = null

    fun open(content: @Composable () -> Unit) {
        overlayReturn = lastFocusKey
        overlay = content
    }

    fun close() {
        overlay = null
        tick++
        pendingFocus = overlayReturn
    }

    fun push(page: SubPage) {
        subReturn = lastFocusKey
        sub = page
    }

    fun pop() {
        sub = null
        pendingFocus = subReturn
    }

    fun refresh() { tick++ }

    /** Réglage du catalogue modifié : rechargé à la demande, ou en quittant les réglages. */
    fun markCatalogChanged() { pendingReload = true }

    fun reloadNow() {
        pendingReload = false
        onReload()
    }

    fun logout() {
        pendingReload = false
        onLogout()
    }
}

internal val LocalSettingsUi = staticCompositionLocalOf<SettingsUi> { error("SettingsUi absent") }

// ------------------------------------------------------------------------------------ Lignes

private val RowShape = RoundedCornerShape(12.dp)
private val DangerFocused = Color(0xFFD9354F)

/**
 * Ligne de réglage : titre, valeur à droite ou interrupteur, description d'une ligne (au focus sur TV,
 * toujours en tactile). Focus : fond blanc, texte noir. Fonctionne à la télécommande et au doigt.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    tags: List<String> = emptyList(),
    checked: Boolean? = null,
    danger: Boolean = false,
    accent: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    focusKey: String = title,
    onClick: () -> Unit = {},
) {
    val ui = LocalSettingsUi.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val pending = ui.pendingFocus
    LaunchedEffect(pending) {
        if (pending != null && pending == focusKey) {
            delay(40)
            runCatching { requester.requestFocus() }
            if (ui.pendingFocus == focusKey) ui.pendingFocus = null
        }
    }
    val bg = when {
        focused -> Color.White
        accent -> Palette.Accent.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.04f)
    }
    val titleColor = when {
        focused && danger -> DangerFocused
        focused -> Color.Black
        !enabled -> Palette.Faint
        danger -> Palette.Error
        else -> Color.White
    }
    val secondary = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.Dim
    // Volet étroit (téléphone, tablette en portrait) : la valeur passe sous le titre.
    val stacked = !ui.isTv && LocalConfiguration.current.screenWidthDp < 960
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (ui.isTv) 52.dp else 56.dp)
            .clip(RowShape)
            .background(bg)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) ui.lastFocusKey = focusKey
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = if (ui.isTv) null else LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            FluxIcon(icon, size = 20.dp, tint = if (danger && !focused) Palette.Error else if (focused) titleColor else Palette.Dim)
            Spacer(Modifier.width(14.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        // Tactile : la valeur prend la place restante (sous le titre quand le volet est étroit).
        val splitValue = !ui.isTv && value != null && !stacked
        val valueColor = if (accent && !focused) Color.White else secondary
        Column(Modifier.weight(if (splitValue) 0.4f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                tags.forEach { Tag(it, focused) }
            }
            if (!ui.isTv && stacked && value != null) {
                Text(value, fontSize = 13.sp, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (description != null && (focused || !ui.isTv)) {
                Text(
                    description, fontSize = 12.sp, color = secondary,
                    maxLines = if (ui.isTv) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null && ui.isTv) {
            Spacer(Modifier.width(16.dp))
            Text(
                value, fontSize = 13.sp, color = valueColor,
                // 220 dp : le titre garde la place de s'afficher en entier (« Mots du contenu français »).
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
            )
        } else if (splitValue) {
            Spacer(Modifier.width(16.dp))
            Text(
                value!!, fontSize = 13.sp, color = valueColor, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f),
            )
        }
        if (checked != null) {
            Spacer(Modifier.width(16.dp))
            Switch(checked, focused, enabled)
        }
        if (chevron) {
            Spacer(Modifier.width(10.dp))
            FluxIcon(SettingsIcons.Chevron, size = 20.dp, tint = secondary)
        }
    }
}

@Composable
private fun Tag(text: String, focused: Boolean) {
    Spacer(Modifier.width(8.dp))
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (focused) Color.Black.copy(alpha = 0.7f) else Palette.Dim,
        modifier = Modifier
            .background(if (focused) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Interrupteur (même dessin que ToggleRow). */
@Composable
private fun Switch(checked: Boolean, focused: Boolean, enabled: Boolean) {
    val track = when {
        checked && enabled -> Palette.Accent
        checked -> Palette.Accent.copy(alpha = 0.5f)
        focused -> Color.Black.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    Box(
        Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(50)).background(track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(18.dp).background(Color.White, CircleShape))
    }
}

/** Petit intitulé de groupe dans une section. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Faint,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Ligne d'état (non focalisable), une ligne, discrète. */
@Composable
internal fun StatusLine(text: String, color: Color = Palette.Dim) {
    Text(
        text, fontSize = 12.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
    )
}

/** En-tête du panneau de droite (titre de la section ou de la sous-page). */
@Composable
internal fun PaneHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, LocalIndication.current, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { FluxIcon(SettingsIcons.Back, size = 22.dp) }
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Boutons et fenêtres

/** Bouton arrondi (fenêtres) : télécommande et tactile. Focus : blanc, texte noir. */
@Composable
fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        primary -> Palette.Accent
        danger -> Palette.Error.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val fg = when {
        focused && danger -> DangerFocused
        focused -> Color.Black
        danger -> Palette.Error
        else -> Color.White
    }
    Row(
        modifier.heightIn(min = 44.dp)
            .scale(if (focused) 1.04f else 1f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            FluxIcon(icon, size = 18.dp, tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** Confirmation d'une action rare ou destructive. Le focus démarre sur « Annuler ». */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { cancel.requestFocus() } }
    ModalPanel(480.dp) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsButton("Annuler", onDismiss, Modifier.focusRequester(cancel))
            SettingsButton(confirmLabel, onConfirm, danger = danger, primary = !danger)
        }
    }
}

/** Saisie d'un texte (nom, mots-clés, User-Agent). */
@Composable
internal fun TextEditDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    default: String? = null,
    maxLength: Int = 500,
    hint: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { field.requestFocus() } }
    ModalPanel(640.dp) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        val touch = !LocalIsTv.current
        if (touch) {
            TouchEditField(text, { text = it.take(maxLength) }, Modifier.fillMaxWidth(), field) { onSave(text) }
        } else {
            TvTextField(
                text, { text = it.take(maxLength) }, "", Modifier.fillMaxWidth().focusRequester(field),
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (touch) {
                // Tactile : action principale à droite.
                SettingsButton("Annuler", onDismiss)
                if (default != null && text != default) SettingsButton("Valeur par défaut", { text = default })
                SettingsButton("Enregistrer", { onSave(text) }, primary = true)
            } else {
                SettingsButton("Enregistrer", { onSave(text) }, primary = true)
                if (default != null && text != default) SettingsButton("Valeur par défaut", { text = default })
                SettingsButton("Annuler", onDismiss)
            }
        }
    }
}

/** Champ tactile des fenêtres de saisie : le curseur démarre à la fin du texte, le clavier s'ouvre. */
@Composable
private fun TouchEditField(
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Texte modifié de l'extérieur (« Valeur par défaut ») : curseur à la fin.
    if (tfv.text != text) tfv = TextFieldValue(text, TextRange(text.length))
    var focused by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() }; keyboard?.show() }
    Box(
        modifier.heightIn(min = 48.dp)
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.05f), RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.5.dp, if (focused) Palette.Accent else Palette.Stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = tfv,
            onValueChange = { tfv = it; onChange(it.text) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = Poppins),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
    }
}

/** Saisie d'un code à 4 chiffres : pavé numérique sur TV, champ numérique en tactile. */
@Composable
fun PinEntry(title: String, message: String?, isError: Boolean, onSubmit: (String) -> Unit, errorTick: Int = 0) {
    if (rememberIsTv()) {
        PinPad(title = title, message = message, isError = isError, errorTick = errorTick, onSubmit = onSubmit)
    } else {
        var v by remember { mutableStateOf("") }
        val submit = { if (v.length == 4) { val pin = v; v = ""; onSubmit(pin) } }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (message != null) {
                Text(message, fontSize = 13.sp, color = if (isError) Palette.Error else Palette.Dim, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            TvTextField(
                v, { s -> v = s.filter { it.isDigit() }.take(4) }, "", Modifier.width(200.dp),
                password = true, keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(12.dp))
            SettingsButton("Valider", { submit() }, primary = true)
        }
    }
}

/** Définition d'un code en deux saisies identiques. */
@Composable
internal fun PinSetup(onDone: (String) -> Unit) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableIntStateOf(0) }
    key(firstPin == null) {
        PinEntry(
            title = if (firstPin == null) "Nouveau code" else "Confirme le code",
            message = when {
                firstPin == null && mismatch > 0 -> "Les codes ne correspondent pas, recommence"
                firstPin == null -> "Choisis 4 chiffres"
                else -> "Saisis-le une seconde fois"
            },
            isError = firstPin == null && mismatch > 0,
            onSubmit = { entered ->
                val f = firstPin
                when {
                    f == null -> firstPin = entered
                    f == entered -> { firstPin = null; onDone(entered) }
                    else -> { mismatch++; firstPin = null }
                }
            },
        )
    }
}

@Composable
internal fun PinSetupDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    ModalPanel(480.dp) {
        PinSetup(onDone)
        Spacer(Modifier.height(12.dp))
        SettingsButton("Annuler", onDismiss)
    }
}

/** Pastille de couleur d'avatar (choix de couleur d'un profil). */
@Composable
fun ColorDot(index: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(40.dp)
            .scale(if (focused) 1.15f else 1f)
            .clip(CircleShape)
            .background(profileBrush(index))
            .then(
                if (focused || selected) Modifier.border(if (focused) 3.dp else 2.dp, Color.White, CircleShape) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) FluxIcon(com.fluxfr.tv.ui.FluxIcons.Check, size = 18.dp)
    }
}

/** Pastille de catégorie (affichée / masquée). */
@Composable
internal fun CategoryChip(text: String, hidden: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        (if (hidden) "⊘ " else "") + text,
        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (hidden) FontWeight.Normal else FontWeight.Medium,
        color = when {
            focused -> Color.Black
            hidden -> Palette.Faint
            else -> Color.White
        },
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    hidden -> Color.White.copy(alpha = 0.03f)
                    else -> Color.White.copy(alpha = 0.14f)
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Conteneur pleine largeur non focalisable, au style des lignes (ex. choix de couleur). */
@Composable
internal fun RowContainer(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RowShape).background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) { content() }
}
