package com.fluxfr.tv.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import com.fluxfr.tv.data.Quality
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.fluxfr.tv.data.Epg
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fluxfr.tv.data.AvFormat
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.NewEpisodes
import com.fluxfr.tv.data.ProgressStore
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role

// ---------------------------------------------------------------- Type d'appareil

/**
 * true sur TV (télécommande), false sur téléphone / tablette (tactile). Fourni une fois par [com.fluxfr.tv.MainActivity]
 * (calcul unique) : les vignettes le lisent sans appel système. Valeur par défaut : TV (comportement d'origine).
 */
val LocalIsTv = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * Téléphone / tablette : un panneau (fiche, menu « Plus »…) est ouvert par-dessus la section. Les retours
 * propres à une section (saga, grille) se désactivent alors (sur TV, ils dépendent du focus).
 */
val LocalOverlayOpen = androidx.compose.runtime.compositionLocalOf { false }

/**
 * TV : dernière surface ([FocusSurface]) qui a eu le focus. L'appli la note à l'ouverture d'une fiche pour
 * rendre le focus à la vignette d'origine à la fermeture (le focusRestorer de la section retombait sur la
 * première rangée de l'accueil quand le focus partait vers la fiche par requestFocus).
 */
class FocusMemory {
    var last: FocusRequester? = null
}

val LocalFocusMemory = androidx.compose.runtime.staticCompositionLocalOf<FocusMemory?> { null }

/** Marges latérales des écrans : 40 dp sur TV, 16 dp sur téléphone. */
@Composable
fun screenPadStart(): Dp = if (LocalIsTv.current) 40.dp else 16.dp

@Composable
fun screenPadEnd(): Dp = if (LocalIsTv.current) 56.dp else 16.dp

// ---------------------------------------------------------------- Surfaces de base

/** Surface focalisable : légère mise à l'échelle, liseré blanc et halo coloré au focus. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    selected: Boolean = false,
    focusedScale: Float = 1.06f,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Palette.Card,
    focusedContainerColor: Color = Palette.Focused,
    focusedContentColor: Color = Color.White,
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!LocalIsTv.current) {
        // La Surface de tv-material ne gère pas le toucher : surface cliquable « classique ».
        TouchSurface(onClick, modifier, shape, if (selected) Palette.Selected else containerColor, onLongClick, content)
        return
    }
    val memory = LocalFocusMemory.current
    val requester = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = if (memory == null) modifier else modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) memory.last = requester
        },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Selected else containerColor,
            contentColor = Color.White,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = focusedContentColor,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
        ),
        glow = if (glow) ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Palette.Accent.copy(alpha = 0.55f), elevation = 14.dp),
        ) else ClickableSurfaceDefaults.glow(),
        content = content,
    )
}

/**
 * Surface tactile (téléphone / tablette) : clic, appui long (avec retour haptique), voile clair et
 * léger enfoncement pendant l'appui. Mise en page identique à la Surface TV (contenu en haut à gauche,
 * boîte intérieure à la taille de la surface).
 */
@Composable
fun TouchSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = Palette.Card,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(containerColor)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick?.let { lp ->
                    {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        lp()
                    }
                },
                onClick = onClick,
            )
            .drawWithContent {
                drawContent()
                if (pressed) drawRect(Color.White.copy(alpha = 0.14f))
            },
        propagateMinConstraints = true,
    ) {
        Box {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Color.White) { content() }
        }
    }
}

/** Bouton principal : bleu au repos, blanc (texte noir) quand il est sélectionné. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick, modifier.onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        // Secondaire : gris foncé presque opaque (même rendu que l'ancien blanc 10 % sur le fond sombre),
        // pour rester lisible quand le bouton passe sur une image ou une bande-annonce claire.
        containerColor = if (primary) Palette.Accent else Color(0xE0222530),
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        Row(
            // Téléphone : cible tactile d'au moins 48 dp.
            // (et libellé centré quand le bouton est élargi).
            (if (LocalIsTv.current) Modifier else Modifier.align(Alignment.Center).heightIn(min = 48.dp))
                .padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val color = if (focused) Color.Black else Color.White
            if (icon != null) {
                FluxIcon(icon, size = 18.dp, tint = color)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
    }
}

/** Pastille (catégories, saisons, onglets). */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick,
        modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() },
        onLongClick = onLongClick,
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f),
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> Palette.Dim
            },
            // Téléphone : pastille plus haute (cible tactile ~44 dp).
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (LocalIsTv.current) 7.dp else 12.dp),
        )
    }
}

// ---------------------------------------------------------------- Petits éléments

@Composable
fun QualityBadge(q: Quality, modifier: Modifier = Modifier) {
    if (q.label.isEmpty() || q == Quality.LOW) return
    val (bg, fg) = when (q) {
        Quality.UHD -> Palette.Gold to Color.Black
        Quality.FHD -> Color.White.copy(alpha = 0.92f) to Color.Black
        else -> Color.Black.copy(alpha = 0.55f) to Color.White
    }
    Box(modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(q.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/**
 * Ligne d'infos d'un grand en-tête (accueil, Films, Séries, fiches) : « 2023  •  Thriller, Drame  •  ★ 7,6 ».
 * Badge de qualité éventuel devant, puces « • », note sur une décimale avec virgule et étoile jaune.
 *
 * [leading] : texte libre placé en premier (« Série », « S01 E03 », « Reste 1 h 05 »…).
 * [year] : année (prendre `DisplayTitle.year(name) ?: VodMeta…year`), affichée ici et non dans le titre.
 * [genres] : genres déjà normalisés ([HomeRows.heroGenres] écarte les catégories du fournisseur qui n'en sont pas).
 * [rating] : note brute du fournisseur (« 7.62 », « 0 », null) : mise en forme ici, masquée si absente / nulle.
 * [trailing] : texte libre placé avant la note (durée…).
 */
@Composable
fun HeroMeta(
    year: Int? = null,
    genres: List<String> = emptyList(),
    rating: String? = null,
    modifier: Modifier = Modifier,
    leading: String? = null,
    trailing: String? = null,
    quality: Quality? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    color: Color = Palette.Dim,
) {
    val text = remember(year, genres, rating, leading, trailing) {
        val parts = HomeRows.metaParts(leading, year, genres, trailing)
        val note = HomeRows.formatRating(rating)
        androidx.compose.ui.text.buildAnnotatedString {
            parts.forEachIndexed { i, p ->
                if (i > 0) append(HomeRows.META_SEPARATOR)
                append(p)
            }
            if (note != null) {
                if (parts.isNotEmpty()) append(HomeRows.META_SEPARATOR)
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.Gold))
                append("★")
                pop()
                append(" ")
                append(note)
            }
        }
    }
    val badge = quality?.takeIf { it.hasBadge }
    if (text.isEmpty() && badge == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (badge != null) {
            QualityBadge(badge)
            if (text.isNotEmpty()) Spacer(Modifier.width(10.dp))
        }
        if (text.isNotEmpty()) {
            Text(text, fontSize = fontSize, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Palette.AccentBrush))
    }
}

/** Le prisme : un « play » découpé en trois facettes (bleu, bleu clair, violet). */
@Composable
fun PrismMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Triangle « play » centré optiquement : pointe à droite.
        val left = w * 0.14f
        val right = w * 0.92f
        val top = h * 0.06f
        val bottom = h * 0.94f
        val midY = h * 0.5f
        val inner = left + (right - left) * 0.38f
        fun tri(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>, color: Color) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(a.first, a.second); lineTo(b.first, b.second); lineTo(c.first, c.second); close()
            }
            drawPath(p, color)
        }
        tri(left to top, right to midY, left to bottom, PrismColors.Blue)
        tri(left to top, right to midY, inner to midY, PrismColors.Light)
        tri(left to bottom, right to midY, inner to midY, PrismColors.Violet)
    }
}

object PrismColors {
    val Blue = Color(0xFF378ADD)
    val Light = Color(0xFF85B7EB)
    val Violet = Color(0xFF7F77DD)
}

@Composable
fun BrandLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { PrismMark(size = 30.dp) }
        if (!compact) {
            Spacer(Modifier.width(12.dp))
            Text(
                "PRISME", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White,
                letterSpacing = 4.sp, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = modifier)
}

@Composable
fun Artwork(url: String?, title: String, contentScale: ContentScale, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Box(
            modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF232842), Color(0xFF151821)))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.take(24), modifier = Modifier.padding(10.dp), color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        AsyncImage(model = url, contentDescription = title, contentScale = contentScale, modifier = modifier.fillMaxSize())
    }
}

/**
 * Grand visuel d'arrière-plan qui se fond dans le décor (effet « immersif ») :
 * l'image est placée à droite et s'efface vers la gauche et vers le bas.
 */
@Composable
fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.68f,
    /** Vidéo (aperçu animé) posée sous les voiles, en haut à droite, sur [videoHeightFraction] de la hauteur. */
    video: (@Composable BoxScope.() -> Unit)? = null,
    videoHeightFraction: Float = 0.55f,
) {
    // Fluidité : image + voiles rendus UNE fois dans une texture (couche hors écran réutilisée tant que rien
    // ne change), au lieu de 3 passes plein écran à chaque image d'animation. Pas avec la vidéo
    // (SurfaceView, incompatible avec une couche hors écran).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier.fillMaxSize().then(
            if (video == null) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            else Modifier
        )
    ) {
        Crossfade(targetState = url, animationSpec = tween(250), label = "backdrop") { u ->
            if (!u.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    val request = remember(u) {
                        // Deux tiers de la taille de la zone (fond sous des voiles : la différence ne se voit pas), quelle que soit la taille de l'image source.
                        coil.request.ImageRequest.Builder(ctx).data(u).size(880, 720).allowRgb565(true)
                            .precision(coil.size.Precision.INEXACT).build()
                    }
                    AsyncImage(
                        model = request, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxWidth(widthFraction).fillMaxSize(),
                    )
                }
            }
        }
        if (video != null) {
            Box(
                Modifier.align(Alignment.TopEnd).fillMaxWidth(widthFraction)
                    .fillMaxHeight(videoHeightFraction).clipToBounds()
            ) {
                video()
                // Haut et bas de la vidéo fondus dans le décor (masque aussi les bandes noires incrustées
                // de nombreuses bandes-annonces au format cinéma).
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Palette.Bg, 0.09f to Palette.Bg, 0.26f to Color.Transparent,
                            0.45f to Color.Transparent, 0.92f to Palette.Bg, 1f to Palette.Bg,
                        )
                    )
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Palette.Bg, 0.32f to Palette.Bg, 0.62f to Palette.Bg.copy(alpha = 0.55f), 1f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Palette.Bg.copy(alpha = 0.6f), 1f to Palette.Bg)
            )
        )
    }
}

/**
 * Quand un élément de la vignette prend le focus, fait défiler pour montrer la vignette ENTIÈRE
 * (affiche + titre dessous), et pas seulement la zone focalisable.
 */
@Composable
fun Modifier.revealOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}

/** Titre sur une ligne : coupé au repos, défile (marquee) quand la carte a le focus. */
@Composable
fun CardTitle(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    unfocusedWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text, maxLines = 1, fontSize = fontSize,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        fontWeight = if (focused) FontWeight.SemiBold else unfocusedWeight,
        color = if (focused) Color.White else Palette.Dim,
        modifier = if (focused) modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900) else modifier,
    )
}

// ---------------------------------------------------------------- Cartes

@Composable
fun PosterCard(
    title: String,
    image: String?,
    quality: Quality?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
    width: Dp = Dp.Unspecified,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    /** Pastille d'accent en bas de l'affiche (ex. « NOUVEL ÉPISODE »). */
    badge: String? = null,
    /** Callback de perte de focus (aperçus animés de l'accueil). */
    onBlur: (() -> Unit)? = null,
    /**
     * [title] est un nom brut du catalogue (film / série) : affiché nettoyé ([com.fluxfr.tv.data.DisplayTitle] :
     * sans année finale, préfixe de langue ni mentions techniques). false : affiché tel quel.
     */
    cleanTitle: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shownTitle = if (cleanTitle) remember(title) { HomeRows.shownTitle(title) } else title
    val isTv = LocalIsTv.current
    // Tactile en largeur non compacte (tablette, paysage) : vignettes plus grandes, titre sur 2 lignes.
    val touchWide = !isTv && !rememberIsCompact()
    val cardWidth = when {
        width != Dp.Unspecified -> width
        isTv -> 132.dp
        touchWide -> 144.dp
        else -> 110.dp
    }
    Column(
        modifier.revealOnFocus().width(cardWidth)
            // Tactile : un seul nœud d'accessibilité, lu avec le titre (TalkBack).
            .then(if (isTv) Modifier else Modifier.touchCardSemantics(shownTitle, subtitle, onClick, onLongClick))
    ) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .onFocusChanged {
                    val was = focused
                    focused = it.isFocused
                    if (it.isFocused) onFocus?.invoke() else if (was) onBlur?.invoke()
                },
            onLongClick = onLongClick,
            focusedScale = 1.08f,
        ) {
            Artwork(image, shownTitle, ContentScale.Crop)
            if (quality != null) QualityBadge(quality, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (badge != null) AccentBadge(badge, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 10.dp))
            if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(8.dp))
        if (touchWide) {
            Text(
                shownTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 17.sp,
                color = Palette.Dim,
            )
        } else {
            CardTitle(shownTitle, focused)
        }
        if (subtitle != null) Text(subtitle, maxLines = 1, fontSize = 11.sp, color = Palette.Faint)
    }
}

/**
 * Carte tactile : un seul nœud d'accessibilité (affiche, badges et titre fusionnés) annoncé par son titre
 * (et son sous-titre), au lieu d'une affiche muette suivie d'un texte séparé.
 */
internal fun Modifier.touchCardSemantics(
    label: String,
    extra: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = this.clearAndSetSemantics {
    contentDescription = if (extra.isNullOrBlank()) label else "$label, $extra"
    role = androidx.compose.ui.semantics.Role.Button
    onClick(label = null) { onClick(); true }
    if (onLongClick != null) onLongClick(label = null) { onLongClick(); true }
}

@Composable
fun ChannelCard(
    name: String,
    logo: String?,
    quality: Quality,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    /** Identifiant de chaîne (stream_id) : affiche le programme en cours sous le nom. */
    channelId: String? = null,
    onLongClick: (() -> Unit)? = null,
    favorite: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = LocalIsTv.current
    Column(
        modifier.revealOnFocus().width(if (isTv) 176.dp else 144.dp)
            // Tactile : un seul nœud d'accessibilité annoncé par le nom de la chaîne.
            .then(if (isTv) Modifier else Modifier.touchCardSemantics(name, if (favorite) "dans Ma liste" else null, onClick, onLongClick))
    ) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() },
            onLongClick = onLongClick,
            containerColor = Palette.CardHigh,
            focusedContainerColor = Color(0xFF2E3550),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))
                )
            )
            Box(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
                if (logo.isNullOrBlank()) {
                    Text(
                        name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    AsyncImage(model = logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            QualityBadge(quality, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (favorite) FavoriteBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(name, focused)
        if (channelId != null) EpgNowLine(channelId, focused)
    }
}

/** Texte du badge des séries suivies qui ont un nouvel épisode. */
const val NEW_EPISODE_BADGE = "NOUVEL ÉPISODE"

/** Pastille couleur d'accent posée sur une affiche. */
@Composable
fun AccentBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Palette.Accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, letterSpacing = 0.5.sp)
    }
}

/** Badges de format : HDR10 / HLG / Dolby Vision, Dolby Atmos, 5.1 / 7.1. */
@Composable
fun AvBadges(av: AvFormat, modifier: Modifier = Modifier) {
    if (av.isEmpty) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        av.hdr?.let { OutlineBadge(it) }
        if (av.atmos) OutlineBadge("Dolby Atmos")
        av.channels?.let { OutlineBadge(it) }
    }
}

@Composable
private fun OutlineBadge(text: String) {
    Box(
        Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

/**
 * Séries suivies (reprise ou « Ma liste ») qui ont un nouvel épisode. Calcul en arrière-plan
 * (préférences + SQLite), recalculé quand la progression, « Ma liste », le profil ou les séries vues changent.
 */
@Composable
fun rememberNewEpisodeIds(catalog: Catalog): Set<String> {
    val ctx = LocalContext.current
    val progressVersion by ProgressStore.version.collectAsState()
    val seenVersion by NewEpisodes.version.collectAsState()
    val favKeys by com.fluxfr.tv.data.Favorites.items.collectAsState()
    val profile by com.fluxfr.tv.data.Profiles.current.collectAsState()
    val ids by produceState(emptySet<String>(), catalog, progressVersion, seenVersion, favKeys, profile) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                com.fluxfr.tv.data.Favorites.load(ctx)
                NewEpisodes.compute(ctx, catalog, profile, com.fluxfr.tv.data.Favorites.items.value)
            }.getOrDefault(emptySet())
        }
    }
    return ids
}

// ---------------------------------------------------------------- Vidéo (bande-annonce)

private const val TRAILER_UA =
    "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

/**
 * Bande-annonce (mp4 ou HLS m3u8) lue en boucle par un ExoPlayer dédié, sans contrôles, dans un
 * PlayerView non focalisable. Invisible jusqu'à la première image (ce qui est dessous reste visible),
 * puis apparaît en fondu ; [onStarted] à la première image. [onFailed] sur erreur de lecture ou si
 * rien ne s'affiche dans [timeoutMs]. [muted] : piste audio désactivée (pas de décodage du son).
 *
 * Le lecteur est créé et libéré dans un DisposableEffect : quand l'URL change, l'ancien est libéré
 * avant que le nouveau soit créé (jamais deux lecteurs à la fois).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun TrailerVideo(
    url: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onFailed: () -> Unit = {},
    onStarted: () -> Unit = {},
    timeoutMs: Long = 20_000L,
    /** Volume à zéro, piste audio gardée (le son peut être rétabli sans relancer la vidéo). */
    volumeOff: Boolean = false,
    /** Haut et bas fondus dans le décor (bandes noires incrustées) : fiche plein écran sur TV. */
    edgeFade: Boolean = false,
    /**
     * Tactile : vidéo rendue dans une TextureView, recadrée (« zoom ») et découpée EXACTEMENT à la boîte de
     * [modifier] (une SurfaceView agrandie débordait de sa boîte, sous le titre de la fiche).
     */
    clipped: Boolean = false,
) {
    val ctx = LocalContext.current
    val failed by rememberUpdatedState(onFailed)
    val started by rememberUpdatedState(onStarted)
    val volumeOffNow = rememberUpdatedState(volumeOff)
    var rendered by remember(url) { mutableStateOf(false) }
    var errored by remember(url) { mutableStateOf(false) }
    var player by remember(url) { mutableStateOf<ExoPlayer?>(null) }
    var createFailed by remember(url) { mutableStateOf(false) }

    DisposableEffect(url, muted) {
        val p = try {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(TRAILER_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
            ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
                .build().apply {
                    volume = if (muted || volumeOffNow.value) 0f else 1f
                    if (muted) {
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    }
                    repeatMode = Player.REPEAT_MODE_ONE
                    setMediaItem(
                        MediaItem.Builder().setUri(url).apply {
                            if (".m3u8" in url) setMimeType(MimeTypes.APPLICATION_M3U8)
                        }.build()
                    )
                    playWhenReady = true
                    prepare()
                }
        } catch (e: Throwable) {
            null
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!rendered) { rendered = true; started() }
            }
            override fun onPlayerError(error: PlaybackException) { errored = true }
        }
        p?.addListener(listener)
        player = p
        createFailed = p == null
        onDispose {
            player = null
            runCatching {
                p?.removeListener(listener)
                p?.stop()
                p?.release()
            }
        }
    }

    LaunchedEffect(url, createFailed, errored) {
        if (createFailed || errored) { failed(); return@LaunchedEffect }
        delay(timeoutMs)
        if (!rendered) failed()
    }

    val p = player ?: return
    // Son coupé / rétabli sans recréer le lecteur (bouton « son » de la fiche sur téléphone).
    LaunchedEffect(p, volumeOff) { p.volume = if (muted || volumeOff) 0f else 1f }
    val alpha by animateFloatAsState(if (rendered && !errored) 1f else 0f, tween(900), label = "trailerVideo")
    if (clipped) {
        ClippedVideo(p, modifier.focusProperties { canFocus = false }.alpha(alpha))
        return
    }
    Box(modifier.focusProperties { canFocus = false }.alpha(alpha)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    keepScreenOn = true
                }
            },
            update = { it.player = p },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        if (edgeFade) TrailerEdgeFade()
    }
}

/**
 * Vidéo de [player] dans une TextureView (dessinée par Compose, donc découpée par clipToBounds), agrandie pour
 * couvrir toute la boîte (équivalent de RESIZE_MODE_ZOOM) et centrée. Tactile seulement.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ClippedVideo(player: ExoPlayer, modifier: Modifier) {
    var videoW by remember(player) { mutableStateOf(player.videoSize.let { (it.width * it.pixelWidthHeightRatio).toInt() }) }
    var videoH by remember(player) { mutableStateOf(player.videoSize.height) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                videoH = videoSize.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val boxW = maxWidth
        val boxH = maxHeight
        val (w, h) = if (videoW > 0 && videoH > 0 && boxW.value > 0f && boxH.value > 0f) {
            val s = maxOf(boxW.value / videoW, boxH.value / videoH)
            (videoW * s).dp to (videoH * s).dp
        } else boxW to boxH
        AndroidView(
            factory = { c ->
                android.view.TextureView(c).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    keepScreenOn = true
                }
            },
            update = { tv ->
                if (tv.tag !== player) {
                    tv.tag = player
                    runCatching { player.setVideoTextureView(tv) }
                }
            },
            onRelease = { tv ->
                (tv.tag as? ExoPlayer)?.let { runCatching { it.clearVideoTextureView(tv) } }
                tv.tag = null
            },
            modifier = Modifier.requiredSize(w, h).focusProperties { canFocus = false },
        )
    }
}

/**
 * Haut et bas d'une bande-annonce plein cadre fondus dans le décor : la plupart des bandes-annonces
 * ont des bandes noires incrustées (format cinéma dans du 16:9), qui faisaient deux bords nets.
 */
@Composable
internal fun TrailerEdgeFade() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Palette.Bg, 0.135f to Palette.Bg, 0.25f to Color.Transparent,
                0.75f to Color.Transparent, 0.865f to Palette.Bg, 1f to Palette.Bg,
            )
        )
    )
}

/** Petite étoile « Ma liste » posée sur une vignette. */
@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(20.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("★", fontSize = 12.sp, color = Palette.Gold, fontWeight = FontWeight.Bold)
    }
}

/** Ajoute / retire une chaîne de « Ma liste » (appui long) et l'annonce par un Toast. */
fun toggleChannelFavorite(ctx: android.content.Context, channelId: String) {
    val added = com.fluxfr.tv.data.Favorites.toggle(ctx, com.fluxfr.tv.data.Favorites.liveKey(channelId))
    android.widget.Toast.makeText(
        ctx, if (added) "Ajoutée à Ma liste" else "Retirée de Ma liste", android.widget.Toast.LENGTH_SHORT,
    ).show()
}

/**
 * Programme en cours (guide TV) sous le nom d'une chaîne : titre sur une ligne + fine barre de progression.
 * Chargé paresseusement pour les seules vignettes composées (Epg met en cache 5 min).
 * La hauteur est réservée même sans guide, pour que la grille ne « saute » pas.
 */
@Composable
fun EpgNowLine(channelId: String, focused: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val now by produceState(Epg.cached(channelId).firstOrNull(), channelId) {
        value = withContext(Dispatchers.IO) {
            runCatching { Epg.nowNext(ctx, channelId, 2).firstOrNull() }.getOrNull()
        }
    }
    Column(modifier.fillMaxWidth().height(20.dp).padding(top = 3.dp)) {
        val p = now?.takeIf { it.isNow() }
        if (p != null) {
            Text(
                p.title, maxLines = 1, fontSize = 10.sp, lineHeight = 12.sp, color = Palette.Dim,
                overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900) else Modifier,
            )
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.14f))
            ) {
                Box(Modifier.fillMaxWidth(p.progress()).height(2.dp).background(Palette.AccentBrush))
            }
        }
    }
}

/** Icône micro (tracé Material, licence Apache 2.0). */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "mic", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.addPathNodes(
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        ),
        fill = SolidColor(Color.White),
    ).build()
}

// ---------------------------------------------------------------- Formulaires

@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onToggle, modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.01f, glow = false,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            // Interrupteur façon iOS
            Box(
                Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(50))
                    .background(if (checked) Palette.Accent else Color.White.copy(alpha = 0.18f)),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(18.dp).background(Color.White, CircleShape))
            }
        }
    }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** Téléphone seulement : le champ prend le focus et le clavier s'ouvre dès l'affichage. */
    autoFocus: Boolean = false,
) {
    if (!LocalIsTv.current) {
        PhoneTextField(
            value, onValueChange, label, modifier, password, keyboardType, imeAction, placeholder, leadingIcon,
            keyboardActions, autoFocus,
        )
        return
    }
    // Sur TV, le clavier ne s'ouvre qu'avec OK : on peut parcourir l'écran sans qu'il surgisse.
    var boxFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val active = boxFocused || editing
    LaunchedEffect(editing) { if (editing) runCatching { fieldFocus.requestFocus() } }
    fun stop() {
        editing = false
        runCatching { boxFocus.requestFocus() }
    }
    val actions = KeyboardActions(
        onDone = { keyboardActions.onDone?.invoke(this); stop() },
        onGo = { keyboardActions.onGo?.invoke(this); stop() },
        onNext = { keyboardActions.onNext?.invoke(this); stop() },
        onPrevious = { keyboardActions.onPrevious?.invoke(this); stop() },
        onSearch = { keyboardActions.onSearch?.invoke(this); stop() },
        onSend = { keyboardActions.onSend?.invoke(this); stop() },
    )
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 12.sp, color = if (active) Color.White else Palette.Dim, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth()
                .focusRequester(boxFocus)
                .onFocusChanged { boxFocused = it.isFocused; if (!it.hasFocus) editing = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true }
                .background(if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(
                    if (boxFocused) 2.dp else 1.5.dp,
                    when {
                        editing -> Palette.Accent
                        boxFocused -> Color.White
                        else -> Palette.Stroke
                    },
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                FluxIcon(leadingIcon, size = 18.dp, tint = if (active) Color.White else Palette.Dim)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, fontSize = 15.sp, color = Palette.Faint)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = Poppins),
                    cursorBrush = SolidColor(Palette.Accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = actions,
                    modifier = Modifier.fillMaxWidth()
                        // ▲ / ▼ pendant la saisie (clavier fermé par Retour) : on quitte le champ et on va
                        // aux résultats ; le champ gardait le focus et la page semblait bloquée.
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val dir = when (e.key) {
                                androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                                androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                                else -> return@onPreviewKeyEvent false
                            }
                            stop()
                            focusManager.moveFocus(dir)
                            true
                        }
                        .focusProperties { canFocus = editing }
                        .focusRequester(fieldFocus)
                        .onFocusChanged { if (!it.isFocused && editing && !it.hasFocus) editing = false },
                )
            }
            if (boxFocused && !editing) {
                Spacer(Modifier.width(10.dp))
                Text("OK pour écrire", fontSize = 11.sp, color = Palette.Faint)
            }
        }
    }
}

/** Champ de saisie tactile (téléphone / tablette) : un appui ouvre directement le clavier. */
@Composable
private fun PhoneTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    password: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    placeholder: String?,
    leadingIcon: ImageVector?,
    keyboardActions: KeyboardActions,
    autoFocus: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        delay(120)
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 12.sp, color = if (focused) Color.White else Palette.Dim, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    runCatching { fieldFocus.requestFocus() }
                    keyboard?.show()
                }
                .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(if (focused) 2.dp else 1.5.dp, if (focused) Palette.Accent else Palette.Stroke, RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                FluxIcon(leadingIcon, size = 18.dp, tint = if (focused) Color.White else Palette.Dim)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, fontSize = 15.sp, color = Palette.Faint, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = Poppins),
                    cursorBrush = SolidColor(Palette.Accent),
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).onFocusChanged { focused = it.isFocused },
                )
            }
            when {
                password -> PhoneFieldAction(if (reveal) "Masquer" else "Afficher") { reveal = !reveal }
                value.isNotEmpty() && focused -> PhoneFieldAction("✕") { onValueChange("") }
                else -> Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun PhoneFieldAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = Palette.Dim, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- Rangées

/**
 * Défilement minimal : ne fait défiler que juste assez pour montrer l'élément.
 * (Sur Android TV, Compose garde sinon l'élément sélectionné vers 30 % de l'écran.)
 */
val MinimalScrollSpec: BringIntoViewSpec = object : BringIntoViewSpec {}

/**
 * Pour une liste verticale de rangées : la rangée sélectionnée vient se caler en haut, titre compris
 * ([headerDp] = hauteur du titre au-dessus des vignettes), façon Netflix.
 */
@Composable
fun rememberRowOnTopSpec(headerDp: Dp = 56.dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { headerDp.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - px
        }
    }
}

/** Une rangée horizontale avec titre (accueil, recherche). */
/**
 * Premier élément (invisible) d'une liste de rangées chargées en différé. Une LazyColumn reste accrochée
 * à son premier élément visible : sans ce repère fixe, les rangées arrivées après coup (Top 10, tendances…)
 * s'inséraient AU-DESSUS de l'écran et l'accueil s'ouvrait directement sur une rangée plus bas.
 */
fun LazyListScope.listTopAnchor(nav: RowNav? = null) {
    nav?.count = 1 // l'index 0 est ce repère ; les rangées suivent dans l'ordre
    item(key = "list-top-anchor", contentType = "anchor") { Spacer(Modifier.height(1.dp)) }
}

/**
 * Marge de fin (TV) : la DERNIÈRE rangée peut, elle aussi, remonter en haut de la liste comme les autres.
 * Sans elle, la liste butait en bas et la fin de l'avant-dernière rangée restait à moitié coupée sous l'en-tête.
 */
fun LazyListScope.listBottomRoom() {
    item(key = "list-bottom-room", contentType = "anchor") { Spacer(Modifier.fillParentMaxHeight(0.45f)) }
}

/**
 * Navigation ▲▼ explicite entre les rangées d'une LazyColumn : ▼ va TOUJOURS à la rangée suivante
 * (défilement puis focus), même si elle n'est pas encore composée. La recherche de focus par défaut
 * pouvait sauter des rangées après un aller-retour dans la liste (rangées détruites hors écran).
 * Toutes les rangées de la liste doivent passer par [listTopAnchor] puis [shelf] avec ce même objet.
 */
class RowNav(
    private val list: androidx.compose.foundation.lazy.LazyListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Espace laissé au-dessus du titre de la rangée calée en haut (px). */
    private val topGapPx: Int = 0,
) {
    private var job: kotlinx.coroutines.Job? = null
    /** Rangée visée par le dernier ▲▼ (le focus la rejoint dès qu'elle est à l'écran). */
    private var pending = -1
    private var lastMoveAt = 0L
    internal var count = 1
    internal val requesters = HashMap<Int, FocusRequester>()

    /** Dernière rangée qui a eu le focus (index de liste), pour retrouver sa place si le focus est perdu. */
    var lastRow: Int = -1
        internal set

    internal fun nextIndex(): Int = count++

    /**
     * Rend le focus à la rangée [target] (amenée à l'écran si besoin) ; elle y retrouve sa dernière vignette.
     * false si l'index n'existe plus.
     */
    fun focusRow(target: Int): Boolean {
        if (target < 1 || target >= count) return false
        go(target, animate = false)
        return true
    }

    /** Donne le focus à la rangée [from] + [delta] ; false si hors liste (▲ depuis la 1re rangée → en-tête). */
    internal fun move(from: Int, delta: Int): Boolean {
        // Appuis enchaînés : on part de la rangée DÉJÀ visée (le focus ne l'a peut-être pas encore atteinte),
        // sinon le 2e appui rapide repartait de l'ancienne rangée et semblait ne rien faire.
        val base = if (job?.isActive == true && pending >= 1) pending else from
        val target = base + delta
        if (target < 1 || target >= count) return false
        go(target)
        return true
    }

    /**
     * La rangée [target] vient TOUJOURS se caler au même endroit (titre en haut de la liste), par un
     * défilement animé piloté ici — et non par le focus ([NoVerticalScrollSpec] sur la liste) : même
     * mouvement à chaque ▲▼, que la rangée soit déjà à l'écran ou non, quelle que soit sa hauteur.
     * Le focus passe dès qu'elle est visible, sans attendre la fin de l'animation.
     */
    private fun go(target: Int, animate: Boolean = true) {
        val now = android.os.SystemClock.uptimeMillis()
        // Appui rapide (animation précédente encore en cours, ou touche maintenue) : on se cale directement,
        // sans relancer une animation interrompue à chaque appui — c'est ce qui faisait saccader.
        val rapid = job?.isActive == true || now - lastMoveAt < 300
        lastMoveAt = now
        pending = target
        job?.cancel()
        val animateThis = animate && !rapid
        job = scope.launch {
            // 1re rangée : tout en haut (repère compris), l'accueil se retrouve dans son état d'ouverture.
            val index = if (target <= 1) 0 else target
            val offset = if (target <= 1) 0 else -topGapPx
            launch {
                runCatching {
                    val shown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    if (animateThis && shown != null) {
                        // Rangée voisine déjà à l'écran : glissement court et toujours identique (pas de ressort).
                        list.animateScrollBy(
                            (shown.offset + offset).toFloat(),
                            androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        )
                    } else if (animateThis) list.animateScrollToItem(index, offset)
                    else list.scrollToItem(index, offset)
                }
            }
            repeat(40) {
                if (list.layoutInfo.visibleItemsInfo.any { it.index == target }) {
                    val r = requesters[target]
                    if (r != null && runCatching { r.requestFocus() }.getOrDefault(false)) return@launch
                }
                androidx.compose.runtime.withFrameNanos { }
            }
        }
    }
}

/**
 * Liste verticale de rangées pilotée par [RowNav] : le focus ne fait PAS défiler la liste (c'est RowNav
 * qui cale la rangée). Les rangées gardent leur propre défilement horizontal.
 */
val NoVerticalScrollSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * État d'une liste verticale de rangées (TV) : pendant le défilement, la rangée suivante est préparée EN ENTIER
 * (8 vignettes au lieu de 2 par défaut) — sinon ses vignettes se composent pendant l'animation et la font saccader.
 */
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun rowsListState(): androidx.compose.foundation.lazy.LazyListState =
    androidx.compose.foundation.lazy.LazyListState(
        0, 0, androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 8),
    )

/** [RowNav] d'une liste de rangées TV, avec l'espace standard au-dessus du titre calé en haut. */
@Composable
fun rememberRowNav(list: androidx.compose.foundation.lazy.LazyListState): RowNav {
    val scope = rememberCoroutineScope()
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    return remember(list, gap) { RowNav(list, scope, gap) }
}

/** Marge de gauche des rangées (TV) : la vignette sélectionnée se cale ici. */
private val ROW_START = 40.dp
/** Marge de droite « réelle » des rangées (TV) quand la rangée tient sans défiler. */
private val ROW_END = 56.dp
/** Rail replié (App) : la place des rangées est la largeur de l'écran moins ce rail. */
private val ROW_RAIL = 72.dp
/** Vignette la plus étroite (affiche) : sert à calculer la marge de fin qui permet à la dernière de se caler à gauche. */
private val ROW_MIN_CARD = 132.dp

/**
 * Défilement des rangées (façon Netflix / Google TV) : la vignette sélectionnée se cale à la marge de gauche
 * ([startPx]) et la rangée défile dessous ; anneau, zoom et titre jamais coupés. Une rangée qui tient
 * entièrement à l'écran (sans sa marge de fin artificielle) ne défile pas.
 */
private class PinToStartSpec(
    private val state: androidx.compose.foundation.lazy.LazyListState,
    private val startPx: Float,
    private val endPx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isNotEmpty() && visible.first().index == 0 && visible.last().index == info.totalItemsCount - 1) {
            val first = visible.first()
            val last = visible.last()
            val contentWidth = (last.offset + last.size - first.offset).toFloat()
            // Tout tient : retour au début (first.offset = -défilement actuel), sinon rien.
            if (contentWidth <= containerSize - startPx - endPx) return first.offset.toFloat()
        }
        return offset - startPx
    }
}

fun <T> LazyListScope.shelf(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T, Int) -> Unit,
) = shelf(null, title, items, key, card = card)

fun <T> LazyListScope.shelf(
    nav: RowNav?,
    title: String,
    items: List<T>,
    key: (T) -> Any,
    /**
     * Rangée encore en chargement : sa place est RÉSERVÉE (titre + vignettes grises) au lieu d'apparaître plus
     * tard au-dessus de l'endroit où l'on est descendu — ce qui donnait l'impression d'avoir sauté une rangée.
     * Même clé de liste vide ou pleine : rien ne bouge quand le contenu arrive.
     */
    loading: Boolean = false,
    /** Carte ajoutée en fin de rangée (ex. « Pas pour moi »). */
    footer: (@Composable () -> Unit)? = null,
    card: @Composable (T, Int) -> Unit,
) {
    if (items.isEmpty() && !loading) return
    val rowIndex = nav?.nextIndex() ?: -1
    item(key = "shelf-$title", contentType = "shelf") {
        if (items.isEmpty()) {
            PlaceholderShelf(title, nav, rowIndex)
            return@item
        }
        if (!LocalIsTv.current) {
            // Téléphone / tablette : rangée balayable au doigt, sans navigation ▲▼ ni focus.
            PhoneShelfRow(title, null) {
                items(items.size, key = { key(items[it]) }) { i -> card(items[i], i) }
                if (footer != null) item(key = "__footer") { footer() }
            }
            return@item
        }
        val rowState = rememberLazyListState()
        val rowFocus = remember { FocusRequester() }
        // Dernière vignette visitée (clé) : gardée même si la rangée sort de l'écran et est recréée
        // (état sauvegardé de l'élément de liste). Lue seulement dans les callbacks : aucune recomposition.
        val lastKey = rememberSaveable { mutableStateOf<String?>(null) }
        val itemRequesters = remember { HashMap<String, FocusRequester>() }
        val density = LocalDensity.current
        val spec = remember(rowState, density) {
            with(density) { PinToStartSpec(rowState, ROW_START.toPx(), ROW_END.toPx()) }
        }
        // Marge de fin : la DERNIÈRE vignette peut, elle aussi, venir se caler à gauche.
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val endPad = (screenW - ROW_RAIL - ROW_START - ROW_MIN_CARD).coerceAtLeast(ROW_END)
        if (nav != null) {
            androidx.compose.runtime.DisposableEffect(rowIndex) {
                nav.requesters[rowIndex] = rowFocus
                onDispose { if (nav.requesters[rowIndex] === rowFocus) nav.requesters.remove(rowIndex) }
            }
        }
        // La rangée entière est UN bloc pour la navigation ▲▼ : on passe toujours à la rangée voisine
        // (jamais plus loin), et on y retrouve la dernière vignette visitée (lastKey), sinon la première visible.
        Column(
            Modifier
                .then(
                    if (nav == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            androidx.compose.ui.input.key.Key.DirectionDown -> nav.move(rowIndex, 1)
                            androidx.compose.ui.input.key.Key.DirectionUp -> nav.move(rowIndex, -1)
                            else -> false
                        }
                    }.onFocusChanged { if (it.hasFocus) nav.lastRow = rowIndex }
                )
                .focusRequester(rowFocus)
                .focusProperties {
                    enter = { itemRequesters[lastKey.value] ?: FocusRequester.Default }
                }
                .focusGroup()
        ) {
            SectionTitle(title, Modifier.padding(start = ROW_START, bottom = 10.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(start = ROW_START, end = endPad, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(items.size, key = { key(items[it]) }) { i ->
                        val item = items[i]
                        val k = remember(item) { key(item).toString() }
                        val req = remember { FocusRequester() }
                        androidx.compose.runtime.DisposableEffect(k) {
                            itemRequesters[k] = req
                            onDispose { if (itemRequesters[k] === req) itemRequesters.remove(k) }
                        }
                        Box(Modifier.focusRequester(req).onFocusChanged { if (it.hasFocus) lastKey.value = k }) {
                            card(item, i)
                        }
                    }
                    if (footer != null) item(key = "__footer") { footer() }
                }
            }
        }
    }
}


/**
 * Rangée en cours de chargement ([shelf] avec loading) : titre et vignettes grises à la taille des affiches.
 * TV : la première est sélectionnable et la rangée suit la navigation ▲▼ comme les autres.
 */
@Composable
private fun PlaceholderShelf(title: String, nav: RowNav?, rowIndex: Int) {
    val isTv = LocalIsTv.current
    val rowFocus = remember { FocusRequester() }
    if (isTv && nav != null) {
        DisposableEffect(rowIndex) {
            nav.requesters[rowIndex] = rowFocus
            onDispose { if (nav.requesters[rowIndex] === rowFocus) nav.requesters.remove(rowIndex) }
        }
    }
    val cardW = if (isTv) ROW_MIN_CARD else 110.dp
    val start = if (isTv) ROW_START else 16.dp
    Column(
        if (isTv && nav != null) Modifier
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> nav.move(rowIndex, 1)
                    androidx.compose.ui.input.key.Key.DirectionUp -> nav.move(rowIndex, -1)
                    else -> false
                }
            }
            .onFocusChanged { if (it.hasFocus) nav.lastRow = rowIndex }
            .focusRequester(rowFocus)
            .focusGroup()
        else Modifier
    ) {
        if (isTv) SectionTitle(title, Modifier.padding(start = start, bottom = 10.dp))
        else Text(
            title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            maxLines = 1, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        )
        Row(
            Modifier.padding(start = start, top = if (isTv) 10.dp else 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 10.dp),
        ) {
            repeat(7) { i ->
                val shape = RoundedCornerShape(12.dp)
                val m = Modifier.width(cardW).aspectRatio(2f / 3f)
                if (isTv && i == 0) {
                    FocusSurface({}, m, containerColor = Color.White.copy(alpha = 0.06f)) {}
                } else {
                    Box(m.clip(shape).background(Color.White.copy(alpha = 0.06f)))
                }
            }
        }
    }
}

/**
 * Rangée horizontale tactile (téléphone / tablette) : titre (avec « Tout voir › » touchable si [onSeeAll]),
 * puis LazyRow balayable (défilement avec élan).
 */
@Composable
fun PhoneShelfRow(
    title: String,
    onSeeAll: (() -> Unit)?,
    state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onSeeAll)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Tout voir ›", fontSize = 13.sp, color = Palette.Accent, fontWeight = FontWeight.Medium)
                }
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h} h ${"%02d".format(m)}" else "$m min"
}
