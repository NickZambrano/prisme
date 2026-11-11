package com.fluxfr.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Tnt
import com.fluxfr.tv.data.Favorites
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.fluxfr.tv.data.TntChannel
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.player.Playables

private const val TAB_PREFS = "fluxfr"
private const val KEY_TV_TAB = "tvTab"
private const val TAB_CHANNELS = "channels"
private const val TAB_GUIDE = "guide"

/**
 * Section « TV » : deux onglets en haut, « Chaînes » (TNT) et « Guide TV » (grille des programmes).
 * Le dernier onglet est mémorisé ; ▲ depuis le contenu remonte sur les onglets.
 */
@Composable
fun TvScreen(catalog: Catalog, onExitLeft: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(TAB_PREFS, android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(prefs.getString(KEY_TV_TAB, TAB_CHANNELS) ?: TAB_CHANNELS) }
    val isTv = LocalIsTv.current
    // Entrée dans les onglets : sur l'onglet affiché (et pas « Chaînes » quand le guide est ouvert).
    val tabFocus = remember { mapOf(TAB_CHANNELS to FocusRequester(), TAB_GUIDE to FocusRequester()) }
    val guideEntry = remember { GuideEntry() }
    val hadFocus = remember { BooleanArray(1) }
    Column(
        Modifier.fillMaxSize().onFocusChanged {
            // Le focus REVIENT dans la section (depuis le menu) : si on l'avait quittée depuis la grille du guide,
            // on le rend à la dernière case visitée au lieu de le laisser sur l'onglet.
            val has = it.hasFocus
            if (has && !hadFocus[0] && tab == TAB_GUIDE && guideEntry.restoreOnEntry) {
                guideEntry.restoreOnEntry = false
                guideEntry.restore?.invoke()
            }
            hadFocus[0] = has
        }
    ) {
        LazyRow(
            modifier = Modifier
                .focusProperties { enter = { tabFocus.getValue(tab) } }
                .onPreviewKeyEvent { e ->
                    // ▼ depuis les onglets : on entre dans la grille sur le programme EN COURS (pas sur une case passée).
                    if (tab == TAB_GUIDE && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && guideEntry.enterNow != null) {
                        guideEntry.enterNow?.invoke()
                        true
                    } else false
                },
            contentPadding = PaddingValues(
                start = screenPadStart(), end = screenPadEnd(), top = if (isTv) 22.dp else 12.dp, bottom = 2.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((t, label) in listOf(TAB_CHANNELS to "Chaînes", TAB_GUIDE to "Guide TV")) {
                item(key = t) {
                    Pill(label, tab == t, onClick = {
                        tab = t
                        prefs.edit().putString(KEY_TV_TAB, t).apply()
                    }, modifier = Modifier.focusRequester(tabFocus.getValue(t)))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_GUIDE) GuideScreen(catalog, onExitLeft = onExitLeft, compactTop = true, entry = guideEntry)
            else TntScreen(catalog, compactTop = true)
        }
    }
}

/** Les chaînes de la TNT, dans l'ordre des numéros, récupérées sur le flux IPTV. */
@Composable
fun TntScreen(catalog: Catalog, compactTop: Boolean = false) {
    val isTv = LocalIsTv.current
    val ctx = LocalContext.current
    val tnt = remember(catalog) { Tnt.find(catalog) }
    LaunchedEffect(Unit) { Favorites.load(ctx) }
    val favs by Favorites.items.collectAsState()

    if (tnt.isEmpty()) {
        CenterMessage(
            "Aucune chaîne TNT trouvée",
            "Les chaînes TF1, France 2, M6… n'ont pas été reconnues dans ton flux (ou pas en HD).",
        )
        return
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    ScrollToTopEffect { gridState.animateScrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv) 176.dp else 150.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(
            start = screenPadStart(), end = screenPadEnd(), top = if (compactTop) 16.dp else 32.dp, bottom = 40.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Box(
                    Modifier.size(44.dp).background(Palette.AccentBrush, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { FluxIcon(FluxIcons.Tnt, size = 24.dp) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("TNT", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isTv) "${tnt.size} chaînes de la TNT, depuis ton abonnement  ·  ▲ ▼ pour zapper"
                        else "${tnt.size} chaînes de la TNT, depuis ton abonnement",
                        fontSize = 13.sp, color = Palette.Dim,
                    )
                }
            }
        }
        itemsIndexed(tnt, key = { _, it -> it.number }) { i, t ->
            TntTile(
                t,
                favorite = Favorites.liveKey(t.channel.id) in favs,
                onLongClick = { toggleChannelFavorite(ctx, t.channel.id) },
            ) { PlayerSession.start(ctx, Playables.tnt(tnt), i) }
        }
    }
}

@Composable
private fun TntTile(t: TntChannel, favorite: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.revealOnFocus()) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
            onLongClick = onLongClick,
            containerColor = Palette.CardHigh,
            focusedContainerColor = Color(0xFF2E3550),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))
                )
            )
            Box(
                Modifier.fillMaxSize().then(
                    if (LocalIsTv.current) Modifier.padding(start = 46.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)
                    // Tactile : le numéro a sa pastille dans le coin, le logo garde toute la largeur dessous.
                    else Modifier.padding(start = 14.dp, end = 14.dp, top = 26.dp, bottom = 10.dp)
                )
            ) {
                if (t.channel.logo.isNullOrBlank()) {
                    Text(
                        t.channel.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        model = t.channel.logo, contentDescription = t.channel.name,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Gros numéro de chaîne, façon télécommande
            if (LocalIsTv.current) {
                Text(
                    t.number.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (focused) Palette.Accent else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp),
                )
            } else {
                Text(
                    t.number.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            QualityBadge(t.channel.quality, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (favorite) FavoriteBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        CardTitle(t.channel.name, focused)
        EpgNowLine(t.channel.id, focused)
    }
}
