package com.fluxfr.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import com.fluxfr.tv.data.Match
import com.fluxfr.tv.data.Matches
import com.fluxfr.tv.data.Reminder
import com.fluxfr.tv.data.Reminders
import kotlinx.coroutines.delay

/**
 * Matchs du jour pour [catalog] : vide d'abord (ou le cache), puis rempli en arrière-plan
 * (scan du guide sur IO, cache 30 min), rafraîchi toutes les 5 min (matchs terminés retirés).
 */
@Composable
fun rememberMatches(catalog: Catalog): List<Match> {
    val ctx = LocalContext.current
    val state by produceState(Matches.cached(catalog).orEmpty(), catalog) {
        while (true) {
            value = runCatching { Matches.load(ctx.applicationContext, catalog) }.getOrElse { value }
            delay(5 * 60_000L)
        }
    }
    return state
}

/** Sur-titre de l'en-tête d'accueil pour un match : "Ligue 1 · En direct" / "Ligue 1 · 20:45". */
fun Match.eyebrow(): String = competition + " · " + if (isLive) "En direct" else formatClock(startMs)

/** Ligne d'infos : "CANAL+ · 20:45 – 22:45". */
fun Match.meta(): String = channel.name + " · " + formatClock(startMs) + " – " + formatClock(endMs, withDay = false)

/** Libellé de l'action principale (OK). */
fun Match.actionLabel(): String = when {
    isLive -> "Regarder"
    Reminders.isSet(channel.id, startMs) -> "Retirer le rappel"
    else -> "Me prévenir"
}

/**
 * OK sur un match : en direct → la chaîne ; à venir → rappel ajouté / retiré.
 * Le lecteur ne s'ouvre qu'après l'arrêt de l'aperçu en direct de l'accueil ([HomeLivePreview.playAfterPreview]).
 */
fun activateMatch(ctx: Context, catalog: Catalog, m: Match) {
    if (m.startMs <= System.currentTimeMillis()) HomeLivePreview.playAfterPreview { playChannelById(ctx, catalog, m.channel.id) }
    else toggleReminder(ctx, Reminder(m.channel.id, m.channel.name, m.title, m.startMs, m.endMs))
}

/**
 * Rangée « Matchs du jour » (même présentation que les autres rangées de l'accueil).
 * [onFocus] : appelé quand une carte prend le focus (pour mettre à jour le grand en-tête).
 */
fun LazyListScope.matchesShelf(
    matches: List<Match>,
    catalog: Catalog,
    title: String = "Matchs du jour",
    onFocus: (Match) -> Unit = {},
    nav: RowNav? = null,
) {
    shelf(nav, title, matches, key = { "match-" + it.key }) { m, _ ->
        val ctx = LocalContext.current
        MatchCard(m, onClick = { activateMatch(ctx, catalog, m) }, onFocus = { onFocus(m) })
    }
}

@Composable
fun MatchCard(m: Match, onClick: () -> Unit, onFocus: () -> Unit = {}, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val reminders by Reminders.items.collectAsState()
    val reminded = remember(reminders, m) { reminders.any { it.channelId == m.channel.id && it.startMs == m.startMs } }
    val live = m.isLive
    Column(modifier.revealOnFocus().width(268.dp)) {
        FocusSurface(
            onClick,
            Modifier.width(268.dp).height(138.dp).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            },
            containerColor = Palette.CardHigh,
            focusedContainerColor = Color(0xFF2E3550),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(
                            (if (live) Color(0xFFE5484D) else Palette.Accent).copy(alpha = 0.22f),
                            Color.Transparent,
                        )
                    )
                )
            )
            // Logo de la chaîne en filigrane, en haut à droite
            if (!m.channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = m.channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    alpha = 0.85f,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(width = 54.dp, height = 30.dp),
                )
            }
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    m.competition.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (live) Color(0xFFFF8A8D) else Palette.Accent, maxLines = 1,
                    modifier = Modifier.padding(end = 64.dp),
                )
                Text(
                    m.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 21.sp, color = Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        LiveBadge()
                        Spacer(Modifier.width(8.dp))
                        Text(m.channel.name, fontSize = 12.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        if (reminded) {
                            FluxIcon(GuideIcons.Bell, size = 13.dp, tint = Palette.Gold)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            formatClock(m.startMs) + " · " + m.channel.name,
                            fontSize = 12.sp, color = if (reminded) Palette.Gold else Palette.Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Plus de « OK pour regarder » répété sous chaque carte : seulement l'état du rappel, discret, quand il y en a un.
        if (!live && reminded) {
            Spacer(Modifier.height(8.dp))
            CardTitle("Rappel programmé", focused, fontSize = 11.sp)
        }
    }
}
