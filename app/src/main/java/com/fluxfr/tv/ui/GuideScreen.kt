package com.fluxfr.tv.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.Epg
import com.fluxfr.tv.data.EpgProgram
import com.fluxfr.tv.data.Favorites
import com.fluxfr.tv.data.Reminder
import com.fluxfr.tv.data.Reminders
import com.fluxfr.tv.data.Tnt
import com.fluxfr.tv.data.TntChannel
import com.fluxfr.tv.player.AccountLimits
import com.fluxfr.tv.player.Catchup
import com.fluxfr.tv.player.PlayItem
import com.fluxfr.tv.player.Playables
import com.fluxfr.tv.player.PlayerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Icônes du guide (tracés Material, licence Apache 2.0). */
object GuideIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Guide by lazy {
        icon("guide", "M17,12h-5v5h5v-5zM16,1v2H8V1H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2h-1V1h-2zM19,19H5V8h14v11z")
    }
    val Bell by lazy {
        icon("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    }
}

// ---------------------------------------------------------------- Utilitaires partagés (guide, matchs, rappels)

private val HHMM = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.FRANCE)
}

/** "20:45", "demain 08:00". */
fun formatClock(ms: Long, withDay: Boolean = true): String {
    val hm = HHMM.get()!!.format(Date(ms))
    if (!withDay) return hm
    val day = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_YEAR)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    return when (day) {
        today -> hm
        today + 1, 1 -> "demain $hm"
        else -> hm
    }
}

/** Joue une chaîne par son identifiant : dans la liste TNT (zapping ▲▼) si c'en est une, sinon seule. */
fun playChannelById(ctx: Context, catalog: Catalog, channelId: String): Boolean {
    val tnt = Tnt.find(catalog)
    val i = tnt.indexOfFirst { it.channel.id == channelId }
    if (i >= 0) {
        PlayerSession.start(ctx, Playables.tnt(tnt), i)
        return true
    }
    val ch = catalog.unfiltered.channels.firstOrNull { it.id == channelId } ?: return false
    PlayerSession.start(ctx, Playables.channels(listOf(ch)), 0)
    return true
}

/** Programme / retire un rappel et l'annonce par un Toast. */
fun toggleReminder(ctx: Context, r: Reminder): Boolean {
    val on = Reminders.toggle(ctx, r)
    Toast.makeText(
        ctx,
        if (on) "Rappel programmé : ${r.programTitle} (${formatClock(r.startMs)})" else "Rappel retiré",
        Toast.LENGTH_SHORT,
    ).show()
    return on
}

// ---------------------------------------------------------------- Grille

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW_MS = 7 * 60 * 60_000L // de maintenant -30/-60 min à +6 h au moins
private const val DP_PER_MIN = 6f
private val START_PAD = 24.dp
private val CHANNEL_COL = 220.dp

/** Colonne des chaînes : étroite sur téléphone (logo seul). */
@Composable
private fun channelCol(): Dp = if (LocalIsTv.current) CHANNEL_COL else 92.dp
private val COL_GAP = 8.dp
private val ROW_H = 60.dp
private val NOW_RED = Color(0xFFE5484D)

private data class GuideRow(val channel: Channel, val number: Int?)

private class Sel(val row: GuideRow, val program: EpgProgram?, val placeholder: Boolean)

/**
 * Pont entre l'écran « TV » (onglets) et le guide : entrer dans la grille sur le programme en cours (▼ depuis les
 * onglets) et revenir du menu sur la dernière case visitée. Renseigné par [GuideScreen], lu par [TvScreen].
 */
class GuideEntry {
    /** Donne le focus au programme EN COURS de la première chaîne visible. */
    var enterNow: (() -> Unit)? = null
    /** Donne le focus à la dernière case visitée (sinon au programme en cours). */
    var restore: (() -> Unit)? = null
    /** Le focus a quitté la grille par la gauche (vers le menu) : au retour, on reprend la dernière case. */
    var restoreOnEntry = false
}

/** État de navigation qui ne doit pas provoquer de recomposition. */
private class Nav {
    var anchor = 0L
    var vertical = false
    /** Dernière case focalisée : rangée et début (borné à la fenêtre). */
    var lastRow = -1
    var lastAnchor = 0L
    val focusers = HashMap<Int, (Long) -> Boolean>()
    /** Case de la rangée qui couvre une heure donnée (entrée dans la grille depuis les onglets). */
    val pickers = HashMap<Int, (Long) -> FocusRequester>()
}

/**
 * Guide TV en grille : chaînes TNT puis chaînes favorites ; frise de -30 min à +6 h.
 * ◀▶ programme précédent / suivant, ▲▼ chaîne précédente / suivante à la même heure,
 * OK : regarder (programme en cours) ou programmer un rappel (programme à venir).
 */
@Composable
fun GuideScreen(
    catalog: Catalog,
    onExitLeft: () -> Unit = {},
    compactTop: Boolean = false,
    entry: GuideEntry? = null,
) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    LaunchedEffect(Unit) {
        Favorites.load(ctx)
        Reminders.start(ctx)
    }
    val favs by Favorites.items.collectAsState()
    val reminders by Reminders.items.collectAsState()
    val reminderKeys = remember(reminders) { reminders.mapTo(HashSet()) { it.key } }

    val rows = remember(catalog, favs) {
        val tnt = Tnt.find(catalog)
        val seen = tnt.mapTo(HashSet()) { it.channel.id }
        val favIds = favs.filter { it.startsWith("live:") }.map { it.removePrefix("live:") }.filter { it !in seen }
        val byId = if (favIds.isEmpty()) emptyMap() else {
            val wanted = favIds.toHashSet()
            catalog.unfiltered.channels.filter { it.id in wanted }.associateBy { it.id }
        }
        tnt.map { GuideRow(it.channel, it.number) } + favIds.mapNotNull { byId[it] }.map { GuideRow(it, null) }
    }
    val playItems: List<PlayItem> = remember(rows) {
        rows.map { r ->
            if (r.number != null) Playables.tnt(listOf(TntChannel(r.number, r.channel))).first()
            else Playables.channels(listOf(r.channel)).first()
        }
    }
    // Replay : le fuseau horaire du serveur (heures des URL de replay) est mis en cache à l'avance.
    LaunchedEffect(rows) {
        if (rows.any { it.channel.archiveDays > 0 }) AccountLimits.warmUp(ctx)
    }

    if (rows.isEmpty()) {
        CenterMessage(
            "Aucune chaîne à afficher",
            "Le guide montre les chaînes de la TNT et tes chaînes favorites (appui long sur une chaîne).",
        )
        return
    }

    // Fenêtre alignée sur la demi-heure ; recalculée quand « maintenant » franchit une limite (voir refreshClock).
    var winStart by remember { mutableLongStateOf(System.currentTimeMillis().let { it - it % HALF_HOUR - HALF_HOUR }) }
    val winEnd = winStart + WINDOW_MS
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val density = LocalDensity.current
    val pxPerMs = with(density) { DP_PER_MIN.dp.toPx() } / 60_000f
    val totalPx = WINDOW_MS * pxPerMs
    val totalDp = (WINDOW_MS / 60_000f * DP_PER_MIN).dp
    val scroll = remember { Animatable(0f) }
    var viewW by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val nav = remember { Nav() }
    var sel by remember { mutableStateOf<Sel?>(null) }
    val marginPx = with(density) { 16.dp.toPx() }

    fun scrollTo(target: Float) {
        val max = max(0f, totalPx - viewW)
        val t = target.coerceIn(0f, max)
        if (t != scroll.targetValue) scope.launch { scroll.animateTo(t, tween(180)) }
    }

    fun ensureVisible(p: EpgProgram, vertical: Boolean) {
        if (viewW <= 0) return
        val s = (max(p.startMs, winStart) - winStart) * pxPerMs
        val e = (min(p.endMs, winEnd) - winStart) * pxPerMs
        val cur = scroll.targetValue
        if (vertical) {
            // Changement de chaîne : on ne bouge que si le programme est (presque) hors de l'écran.
            if (e >= cur + 3 * marginPx && s <= cur + viewW - 3 * marginPx) return
        }
        when {
            s < cur -> scrollTo(s - marginPx)
            s > cur + viewW * 0.6f -> scrollTo(s - viewW * 0.25f)
        }
    }

    fun viewStartMs(): Long = winStart + (scroll.targetValue / pxPerMs).toLong()

    // Horloge : toutes les 30 s et au retour au premier plan. Si « maintenant » franchit une limite de demi-heure,
    // la fenêtre glisse ; le défilement est décalé du même écart (même heure à l'écran) et le focus est rendu
    // à la chaîne sélectionnée (les cellules sont recréées avec la nouvelle fenêtre).
    val guideHasFocus = remember { BooleanArray(1) }
    fun refreshClock() {
        val t = System.currentTimeMillis()
        now = t
        val newStart = t - t % HALF_HOUR - HALF_HOUR
        if (newStart == winStart) return
        val delta = newStart - winStart
        winStart = newStart
        val max = max(0f, totalPx - viewW)
        scope.launch {
            scroll.snapTo((scroll.value - delta * pxPerMs).coerceIn(0f, max))
            val s = sel
            if (guideHasFocus[0] && s != null) {
                val idx = rows.indexOfFirst { it.channel.id == s.row.channel.id }
                delay(60)
                if (idx >= 0) nav.focusers[idx]?.invoke(nav.anchor)
            }
        }
    }
    val refreshClockState = rememberUpdatedState(::refreshClock)
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            refreshClockState.value()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshClockState.value() }

    /** Donne le focus à la case de la rangée [row] qui couvre [anchorMs] (la rangée est amenée à l'écran si besoin). */
    fun focusRowAt(row: Int, anchorMs: Long) {
        scope.launch {
            if (nav.focusers[row] == null) {
                runCatching { listState.scrollToItem(row) }
                for (i in 0 until 6) {
                    if (nav.focusers[row] != null) break
                    withFrameNanos { }
                }
            }
            nav.focusers[row]?.invoke(anchorMs)
        }
    }
    SideEffect {
        entry?.enterNow = { focusRowAt(listState.firstVisibleItemIndex.coerceIn(0, rows.lastIndex), System.currentTimeMillis()) }
        entry?.restore = {
            if (nav.lastRow in rows.indices) focusRowAt(nav.lastRow, nav.lastAnchor)
            else entry?.enterNow?.invoke()
        }
    }

    fun onCellFocused(row: GuideRow, p: EpgProgram, placeholder: Boolean) {
        sel = Sel(row, p.takeUnless { placeholder }, placeholder)
        entry?.restoreOnEntry = false
        nav.lastRow = rows.indexOfFirst { it.channel.id == row.channel.id }
        nav.lastAnchor = if (placeholder) now else max(p.startMs, winStart)
        if (!placeholder) ensureVisible(p, nav.vertical)
        if (!nav.vertical) nav.anchor = if (placeholder) max(now, viewStartMs()) else max(p.startMs, viewStartMs())
        nav.vertical = false
    }

    /** ▲▼ : rangée [target] à l'heure de l'ancre. false = laisser faire la recherche de focus par défaut. */
    fun moveVertical(target: Int): Boolean {
        // ▲ depuis la première chaîne : recherche de focus par défaut (onglets au-dessus).
        if (target < 0) return false
        if (target > rows.lastIndex) return true
        nav.vertical = true
        val f = nav.focusers[target]
        if (f != null && f(nav.anchor)) return true
        return false
    }

    // Tactile : un appui ouvre une fiche (pas d'action silencieuse).
    var sheet by remember { mutableStateOf<ProgramSheetData?>(null) }
    BackHandler(enabled = sheet != null) { sheet = null }

    fun scrollToNow() {
        scrollTo((now - winStart) * pxPerMs - 2 * marginPx)
    }

    fun activate(index: Int, row: GuideRow, p: EpgProgram, placeholder: Boolean) {
        val t = System.currentTimeMillis()
        if (!isTv && !placeholder) {
            sheet = ProgramSheetData(index, row, p)
            return
        }
        // Programme terminé d'une chaîne avec replay : on le revoit chez le fournisseur.
        if (!placeholder && p.endMs <= t && Catchup.replayable(row.channel.archiveDays, p, t)) {
            val item = Catchup.item(ctx, playItems[index], p)
            if (item != null) {
                PlayerSession.start(ctx, listOf(item), 0)
                return
            }
        }
        if (placeholder || p.startMs <= t) {
            PlayerSession.start(ctx, playItems, index)
        } else {
            toggleReminder(ctx, Reminder(row.channel.id, row.channel.name, p.title, p.startMs, p.endMs))
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(top = if (compactTop) 8.dp else 22.dp)
            .onFocusChanged { guideHasFocus[0] = it.hasFocus },
    ) {
        GuideHeader(sel, now, reminderKeys, onNow = if (isTv) null else ({ scrollToNow() }))

        // ---- Frise horaire
        Row(Modifier.fillMaxWidth().height(26.dp).padding(start = START_PAD)) {
            Box(Modifier.width(channelCol()), contentAlignment = Alignment.CenterStart) {
                Text(
                    (if (isTv) "Aujourd'hui · " else "") + formatClock(now, withDay = false),
                    fontSize = 12.sp, color = Palette.Dim, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(COL_GAP))
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { viewW = it.width }
            ) {
                Box(
                    Modifier.offset { IntOffset(-scroll.value.roundToInt(), 0) }
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .requiredWidth(totalDp).fillMaxHeight()
                ) {
                    val ticks = (WINDOW_MS / HALF_HOUR).toInt()
                    for (k in 0..ticks) {
                        val x = (k * 30 * DP_PER_MIN).dp
                        Box(Modifier.offset(x = x).width(1.dp).height(8.dp).align(Alignment.BottomStart).background(Palette.Stroke))
                        Text(
                            formatClock(winStart + k * HALF_HOUR, withDay = false),
                            fontSize = 12.sp, color = Palette.Faint,
                            modifier = Modifier.offset(x = x + 6.dp).align(Alignment.CenterStart),
                        )
                    }
                    // Repère de l'heure actuelle (la ligne elle-même passe sous le texte des cases).
                    val nowX = ((now - winStart) / 60_000f * DP_PER_MIN).dp
                    Box(
                        Modifier.offset(x = nowX - 4.dp).size(8.dp).align(Alignment.BottomStart)
                            .background(NOW_RED, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // Téléphone / tablette : glisser horizontalement (avec élan) fait défiler la frise de toutes les rangées.
        val dragModifier = if (isTv) Modifier else Modifier.draggable(
            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
            state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                val maxScroll = max(0f, totalPx - viewW)
                val t = (scroll.value - delta).coerceIn(0f, maxScroll)
                scope.launch { scroll.snapTo(t) }
            },
            onDragStopped = { velocity ->
                scroll.updateBounds(0f, max(0f, totalPx - viewW))
                scroll.animateDecay(-velocity, androidx.compose.animation.core.exponentialDecay())
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().then(dragModifier)) {
            LazyColumn(
                state = listState,
                // ▼ depuis les onglets : on arrive sur le programme EN COURS de la première chaîne visible,
                // pas sur la case passée la plus proche (OK y lançait un replay).
                modifier = Modifier.fillMaxSize()
                    .focusProperties {
                        enter = {
                            nav.pickers[listState.firstVisibleItemIndex]?.invoke(System.currentTimeMillis())
                                ?: FocusRequester.Default
                        }
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(start = START_PAD, end = 24.dp, top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(rows, key = { _, r -> r.channel.id }) { i, row ->
                    GuideRowView(
                        index = i, row = row, winStart = winStart, winEnd = winEnd, now = now,
                        pxPerMs = pxPerMs, totalDp = totalDp, scrollPx = { scroll.value },
                        reminderKeys = reminderKeys, nav = nav,
                        onFocused = { p, ph -> onCellFocused(row, p, ph) },
                        onClick = { p, ph -> activate(i, row, p, ph) },
                        onLeftEdge = {
                            // Vers le menu : au retour, TvScreen rend le focus à cette case (pas aux onglets).
                            entry?.restoreOnEntry = true
                            onExitLeft()
                        },
                        onVertical = { moveVertical(it) },
                    )
                }
            }
            // La ligne rouge « maintenant » est dessinée par les cases elles-mêmes, SOUS leur texte (voir ProgramCell) ;
            // son repère (point rouge) est dans la frise horaire.
        }
    }
    val sh = sheet
    if (sh != null) {
        ProgramSheet(
            sh, now, sh.row.number?.let { "$it · " }.orEmpty() + sh.row.channel.name,
            reminded = Reminders.key(sh.row.channel.id, sh.program.startMs) in reminderKeys,
            onDismiss = { sheet = null },
            onWatch = {
                sheet = null
                PlayerSession.start(ctx, playItems, sh.index)
            },
            onReplay = {
                sheet = null
                Catchup.item(ctx, playItems[sh.index], sh.program)?.let { PlayerSession.start(ctx, listOf(it), 0) }
            },
            onRemind = {
                sheet = null
                val r = sh.row
                toggleReminder(ctx, Reminder(r.channel.id, r.channel.name, sh.program.title, sh.program.startMs, sh.program.endMs))
            },
        )
    }
    }
}

private class ProgramSheetData(val index: Int, val row: GuideRow, val program: EpgProgram)

/** Tactile : fiche d'un programme (titre, horaire, chaîne, synopsis) avec les actions possibles. */
@Composable
private fun ProgramSheet(
    d: ProgramSheetData,
    now: Long,
    channelLabel: String,
    reminded: Boolean,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onReplay: () -> Unit,
    onRemind: () -> Unit,
) {
    val p = d.program
    val live = p.isNow(now)
    val past = p.endMs <= now
    val replay = past && Catchup.replayable(d.row.channel.archiveDays, p, now)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFF1D212D))
                // Les appuis dans la fiche ne la ferment pas.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatClock(p.startMs)} – ${formatClock(p.endMs, withDay = false)}  ·  ${formatDuration(p.endMs - p.startMs)}",
                    fontSize = 13.sp, color = Palette.Dim, maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                if (live) LiveBadge()
                else if (reminded) {
                    FluxIcon(GuideIcons.Bell, size = 14.dp, tint = Palette.Gold)
                    Spacer(Modifier.width(4.dp))
                    Text("Rappel programmé", fontSize = 12.sp, color = Palette.Gold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(channelLabel, fontSize = 13.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val desc = p.description.orEmpty().trim()
            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    desc, fontSize = 13.sp, color = Palette.Dim, lineHeight = 19.sp,
                    modifier = Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    live -> ActionButton("Regarder", onWatch, primary = true, icon = FluxIcons.Play)
                    !past -> ActionButton(
                        if (reminded) "Retirer le rappel" else "Me prévenir", onRemind,
                        primary = true, icon = GuideIcons.Bell,
                    )
                    else -> {
                        if (replay) ActionButton("Replay", onReplay, primary = true, icon = FluxIcons.Refresh)
                        ActionButton("Regarder le direct", onWatch, primary = !replay, icon = FluxIcons.Play)
                    }
                }
                ActionButton("Fermer", onDismiss)
            }
        }
    }
}

@Composable
private fun GuideHeader(sel: Sel?, now: Long, reminderKeys: Set<String>, onNow: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().then(if (LocalIsTv.current) Modifier.height(158.dp) else Modifier.padding(bottom = 8.dp)).padding(start = START_PAD + 16.dp, end = screenPadEnd())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).background(Palette.AccentBrush, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { FluxIcon(GuideIcons.Guide, size = 18.dp) }
            Spacer(Modifier.width(10.dp))
            Text("Guide TV", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            // Indication selon la case focalisée : ce que fait OK ici.
            val fp = sel?.program
            val fReplay = fp != null && fp.endMs <= now && sel != null &&
                Catchup.replayable(sel.row.channel.archiveDays, fp, now)
            Text(
                when {
                    !LocalIsTv.current -> "Touche un programme : regarder ou être prévenu"
                    sel == null -> "OK : regarder le direct ou programmer un rappel"
                    fp == null -> "OK : regarder la chaîne"
                    fp.isNow(now) -> "OK : regarder le direct"
                    fReplay -> "OK : revoir (replay)"
                    fp.endMs <= now -> "Programme terminé"
                    Reminders.key(sel.row.channel.id, fp.startMs) in reminderKeys -> "OK : retirer le rappel"
                    else -> "OK : programmer un rappel"
                },
                fontSize = 12.sp, color = if (fReplay) Palette.Accent else Palette.Faint,
                fontWeight = if (fReplay) FontWeight.SemiBold else FontWeight.Normal,
                modifier = if (onNow != null) Modifier.weight(1f, fill = false) else Modifier,
                maxLines = if (onNow != null) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            if (onNow != null) {
                Spacer(Modifier.weight(1f))
                Pill("Maintenant", selected = false, onClick = onNow)
            }
        }
        Spacer(Modifier.height(12.dp))
        val s = sel ?: return@Column
        val p = s.program
        Row(verticalAlignment = Alignment.CenterVertically) {
            val chan = (s.row.number?.let { "$it · " } ?: "") + s.row.channel.name
            Text(chan, fontSize = 13.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (p != null) {
                Text(
                    "  ·  ${formatClock(p.startMs)} – ${formatClock(p.endMs, withDay = false)}  ·  ${formatDuration(p.endMs - p.startMs)}",
                    fontSize = 13.sp, color = Palette.Dim, maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                when {
                    p.isNow(now) -> LiveBadge()
                    Reminders.key(s.row.channel.id, p.startMs) in reminderKeys -> {
                        FluxIcon(GuideIcons.Bell, size = 14.dp, tint = Palette.Gold)
                        Spacer(Modifier.width(4.dp))
                        Text("Rappel programmé", fontSize = 12.sp, color = Palette.Gold)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            p?.title ?: if (s.placeholder) "Programme indisponible" else "",
            fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            p?.description.orEmpty(),
            fontSize = 13.sp, color = Palette.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "EN DIRECT") {
    Box(modifier.background(NOW_RED, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun GuideRowView(
    index: Int,
    row: GuideRow,
    winStart: Long,
    winEnd: Long,
    now: Long,
    pxPerMs: Float,
    totalDp: Dp,
    scrollPx: () -> Float,
    reminderKeys: Set<String>,
    nav: Nav,
    onFocused: (EpgProgram, Boolean) -> Unit,
    onClick: (EpgProgram, Boolean) -> Unit,
    onLeftEdge: () -> Unit,
    onVertical: (Int) -> Boolean,
) {
    val ctx = LocalContext.current
    val id = row.channel.id
    // Chargement paresseux : seules les rangées composées (visibles) interrogent le serveur.
    val programs by produceState(Epg.cachedDay(id), id) {
        if (value == null) value = runCatching { Epg.day(ctx, id) }.getOrElse { emptyList() }
    }
    val placeholder = programs.isNullOrEmpty()
    val cells: List<EpgProgram> = remember(programs, winStart) {
        val list = programs?.filter { it.endMs > winStart && it.startMs < winEnd }.orEmpty()
        list.ifEmpty {
            listOf(EpgProgram(if (programs == null) "Chargement du programme…" else "Programme indisponible", null, winStart, winEnd))
        }
    }
    val requesters = remember(cells) { List(cells.size) { FocusRequester() } }

    DisposableEffect(index, cells) {
        val picker: (Long) -> FocusRequester = { anchor ->
            val j = cells.indexOfFirst { anchor >= it.startMs && anchor < it.endMs }
                .takeIf { it >= 0 } ?: cells.indexOfFirst { it.startMs >= anchor }.takeIf { it >= 0 } ?: cells.lastIndex
            requesters[j]
        }
        val focuser: (Long) -> Boolean = { anchor -> runCatching { picker(anchor).requestFocus() }.isSuccess }
        nav.focusers[index] = focuser
        nav.pickers[index] = picker
        onDispose {
            if (nav.focusers[index] === focuser) nav.focusers.remove(index)
            if (nav.pickers[index] === picker) nav.pickers.remove(index)
        }
    }

    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        ChannelColumnCell(row)
        Spacer(Modifier.width(COL_GAP))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Box(
                Modifier.offset { IntOffset(-scrollPx().roundToInt(), 0) }
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(totalDp).fillMaxHeight()
            ) {
                cells.forEachIndexed { j, p ->
                    val startMin = (max(p.startMs, winStart) - winStart) / 60_000f
                    val endMin = (min(p.endMs, winEnd) - winStart) / 60_000f
                    val x = (startMin * DP_PER_MIN).dp
                    val w = ((endMin - startMin) * DP_PER_MIN).dp - 3.dp
                    val cellStart = max(p.startMs, winStart)
                    val cellEnd = min(p.endMs, winEnd)
                    ProgramCell(
                        program = p,
                        placeholder = placeholder,
                        live = !placeholder && p.isNow(now),
                        past = !placeholder && p.endMs <= now,
                        replay = !placeholder && p.endMs <= now && Catchup.replayable(row.channel.archiveDays, p, now),
                        reminded = !placeholder && Reminders.key(id, p.startMs) in reminderKeys,
                        scrollPx = scrollPx,
                        cellXPx = (cellStart - winStart) * pxPerMs,
                        cellWPx = (cellEnd - cellStart) * pxPerMs,
                        cellWDp = w,
                        // Ligne « maintenant » : dessinée ici, sous le texte (null hors de cette case).
                        nowXPx = if (now in cellStart until cellEnd) (now - cellStart) * pxPerMs else null,
                        now = now,
                        modifier = Modifier.offset(x = x).width(max(w.value, 4f).dp).fillMaxHeight()
                            .focusRequester(requesters[j])
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionLeft -> {
                                        if (j > 0) runCatching { requesters[j - 1].requestFocus() } else onLeftEdge()
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (j < cells.lastIndex) runCatching { requesters[j + 1].requestFocus() }
                                        true
                                    }
                                    Key.DirectionUp -> onVertical(index - 1)
                                    Key.DirectionDown -> onVertical(index + 1)
                                    else -> false
                                }
                            },
                        onFocused = { onFocused(p, placeholder) },
                        onClick = { onClick(p, placeholder) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelColumnCell(row: GuideRow) {
    Row(
        Modifier.width(channelCol()).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Palette.Card)
            .padding(horizontal = if (LocalIsTv.current) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tv = LocalIsTv.current
        Text(
            row.number?.toString() ?: "★", fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = if (row.number == null) Palette.Gold else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.width(if (tv) 26.dp else 24.dp),
        )
        Box(Modifier.size(width = if (tv) 52.dp else 50.dp, height = 34.dp), contentAlignment = Alignment.Center) {
            if (!row.channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = row.channel.logo, contentDescription = row.channel.name,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (!tv) return@Row
        Spacer(Modifier.width(10.dp))
        Text(
            row.channel.name, fontSize = 12.sp, color = Palette.Dim, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ProgramCell(
    program: EpgProgram,
    placeholder: Boolean,
    live: Boolean,
    past: Boolean,
    /** Programme passé revisible (replay du fournisseur) : badge « Replay » devant l'heure. */
    replay: Boolean,
    reminded: Boolean,
    /** Défilement horizontal de la frise, position et largeur de la case (px) : le titre reste dans la partie visible. */
    scrollPx: () -> Float,
    cellXPx: Float,
    cellWPx: Float,
    cellWDp: Dp,
    /** Abscisse (px, dans la case) de la ligne « maintenant », ou null. */
    nowXPx: Float?,
    now: Long,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
        shape = RoundedCornerShape(7.dp),
        focusedScale = 1.0f,
        containerColor = when {
            live -> Color(0xFF1C2541)
            past || placeholder -> Color(0xFF101219)
            else -> Palette.Card
        },
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
        glow = false,
    ) {
        val main = if (focused) Color.Black else if (past) Palette.Faint else Color.White
        val dim = if (focused) Color.Black.copy(alpha = 0.7f) else Palette.Faint
        val minVisiblePx = with(LocalDensity.current) { 110.dp.toPx() }
        val lineW = with(LocalDensity.current) { 2.dp.toPx() }
        // Contenu écrêté à la case ; la ligne « maintenant » est peinte AVANT le texte (donc dessous).
        Box(
            Modifier.fillMaxSize().clipToBounds().then(
                if (nowXPx == null) Modifier else Modifier.drawBehind {
                    drawRect(NOW_RED, topLeft = Offset(nowXPx - lineW / 2, 0f), size = Size(lineW, size.height))
                }
            )
        ) {
        // Tactile : une case trop étroite (< 40 dp) n'affiche rien plutôt qu'un texte tronqué (« 11: » + « … »).
        val hideText = !LocalIsTv.current && cellWDp < 40.dp
        if (!hideText) Column(
            Modifier.fillMaxSize()
                // Case en partie défilée hors de l'écran à gauche : le texte glisse pour rester dans la partie visible
                // (sans quitter la case : on s'arrête quand il ne reste plus que ~110 dp de case visible).
                .offset {
                    val shift = (scrollPx() - cellXPx).coerceIn(0f, max(0f, cellWPx - minVisiblePx))
                    IntOffset(shift.roundToInt(), 0)
                }
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (!placeholder) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (reminded) {
                        FluxIcon(GuideIcons.Bell, size = 11.dp, tint = if (focused) Color.Black else Palette.Gold)
                        Spacer(Modifier.width(3.dp))
                    }
                    if (replay) {
                        ReplayBadge(compact = cellWDp < 150.dp)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(formatClock(program.startMs, withDay = false), fontSize = 10.sp, color = dim, maxLines = 1)
                }
            }
            Text(
                program.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (focused || live) FontWeight.SemiBold else FontWeight.Medium, color = main,
            )
        }
        }
        if (live) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(program.progress(now)).height(2.dp)
                    .background(if (focused) Palette.Accent else Palette.Accent.copy(alpha = 0.8f))
            )
        }
    }
}

/** Badge « Replay » (programme passé revisible) : pastille de 22 dp ; l'icône seule dans les cases étroites. */
@Composable
private fun ReplayBadge(compact: Boolean) {
    Row(
        Modifier.height(22.dp)
            .background(Palette.Accent, if (compact) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(11.dp))
            .then(if (compact) Modifier.width(22.dp) else Modifier.padding(horizontal = 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        FluxIcon(FluxIcons.Refresh, size = 13.dp, tint = Color.White)
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            Text("Replay", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Rappel : fenêtre globale

/**
 * Fenêtre « Ça commence » (en bas à droite) quand un rappel arrive à échéance. À placer par-dessus tout
 * dans l'écran principal. N'apparaît que si l'activité est au premier plan ; se ferme seule après 30 s.
 */
@Composable
fun ReminderOverlay(catalog: Catalog, onClosed: () -> Unit = {}) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Reminders.start(ctx) }
    val due by Reminders.due.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val r = due
    if (r == null || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return
    val focus = remember(r) { FocusRequester() }
    val close = {
        Reminders.dismiss()
        onClosed()
    }
    LaunchedEffect(r) {
        delay(80)
        runCatching { focus.requestFocus() }
        delay(30_000)
        if (Reminders.due.value == r) close()
    }
    BackHandler { close() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            (if (LocalIsTv.current) Modifier.padding(end = 48.dp, bottom = 40.dp).width(440.dp) else Modifier.padding(12.dp).fillMaxWidth())
                .clip(RoundedCornerShape(16.dp)).background(Color(0xF21D212D))
                .focusProperties {
                    exit = { dir -> if (dir == FocusDirection.Enter || dir == FocusDirection.Exit) FocusRequester.Default else FocusRequester.Cancel }
                }
                .focusGroup()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FluxIcon(GuideIcons.Bell, size = 16.dp, tint = Palette.Gold)
                Spacer(Modifier.width(8.dp))
                Text("RAPPEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Gold)
                Spacer(Modifier.width(8.dp))
                Text(formatClock(r.startMs, withDay = false), fontSize = 11.sp, color = Palette.Dim)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ça commence : ${r.programTitle} · ${r.channelName}",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    "Regarder",
                    onClick = {
                        close()
                        playChannelById(ctx, catalog, r.channelId)
                    },
                    modifier = Modifier.focusRequester(focus),
                    primary = true,
                    icon = FluxIcons.Play,
                )
                ActionButton("Ignorer", onClick = close)
            }
        }
    }
}
