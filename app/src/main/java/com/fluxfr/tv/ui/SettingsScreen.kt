package com.fluxfr.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.HiddenCategories
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.Updater
import com.fluxfr.tv.ui.settings.LocalSettingsUi
import com.fluxfr.tv.ui.settings.PaneHeader
import com.fluxfr.tv.ui.settings.SectionBody
import com.fluxfr.tv.ui.settings.SettingsIcons
import com.fluxfr.tv.ui.settings.SettingsRow
import com.fluxfr.tv.ui.settings.SettingsSection
import com.fluxfr.tv.ui.settings.SettingsUi
import com.fluxfr.tv.ui.settings.subPageTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Réglages façon Google TV : sections à gauche, contenu de la section choisie à droite.
 * TV et tablette : deux volets ; téléphone (compact) : liste des sections puis page de section.
 */
@Composable
fun SettingsScreen(settings: Settings, onReload: () -> Unit, onLogout: () -> Unit, catalog: Catalog? = null) {
    val ctx = LocalContext.current
    val isTv = rememberIsTv()
    val compact = !isTv && rememberIsCompact()
    val scope = rememberCoroutineScope()
    val ui = remember { SettingsUi(settings, isTv, scope, onReload, onLogout) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HiddenCategories.load(ctx) } }
    // Filtres modifiés sans « Appliquer » : le catalogue est rechargé en quittant les réglages.
    DisposableEffect(Unit) { onDispose { if (ui.pendingReload) ui.reloadNow() } }

    var section by rememberSaveable { mutableStateOf(SettingsSection.PROFILES) }
    var compactDetail by rememberSaveable { mutableStateOf(false) }
    val sectionFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val paneFocus = remember { FocusRequester() }
    var paneHasFocus by remember { mutableStateOf(false) }

    fun select(s: SettingsSection) {
        if (s != section) {
            section = s
            ui.sub = null
        }
    }

    // Sous-page ouverte (fiche de profil, catégories) : le focus passe sur sa première ligne.
    val sub = ui.sub
    LaunchedEffect(sub) {
        if (sub != null && !compact) { delay(60); runCatching { paneFocus.requestFocus() } }
    }

    // Retour : fenêtre > sous-page > panneau de droite (TV) / page de section (téléphone).
    val overlay = ui.overlay
    BackHandler(enabled = overlay != null) { ui.close() }
    BackHandler(enabled = overlay == null && ui.sub != null) { ui.pop() }
    BackHandler(enabled = overlay == null && ui.sub == null && compact && compactDetail) { compactDetail = false }
    BackHandler(enabled = overlay == null && ui.sub == null && !compact && paneHasFocus) {
        runCatching { sectionFocus.getValue(section).requestFocus() }
    }

    CompositionLocalProvider(LocalSettingsUi provides ui) {
        Box(Modifier.fillMaxSize()) {
            if (compact) {
                CompactSettings(section, compactDetail, catalog, onOpen = { select(it); compactDetail = true }, onBack = {
                    if (ui.sub != null) ui.pop() else compactDetail = false
                })
            } else {
                Row(
                    Modifier.fillMaxSize().padding(
                        start = if (isTv) 24.dp else 16.dp, end = if (isTv) 48.dp else 16.dp,
                        top = if (isTv) 32.dp else 16.dp,
                    ),
                ) {
                    // ---- Sections
                    Column(
                        Modifier.width(if (isTv) 300.dp else 260.dp).fillMaxHeight()
                            .focusProperties {
                                enter = { sectionFocus.getValue(section) }
                                exit = { if (it == FocusDirection.Right) paneFocus else FocusRequester.Default }
                            }
                            .focusGroup()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Réglages", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(start = 16.dp, bottom = 14.dp),
                        )
                        val update by Updater.available.collectAsState()
                        SettingsSection.entries.forEach { s ->
                            SectionItem(
                                s, selected = s == section, touch = !isTv,
                                badge = s == SettingsSection.UPDATE && update != null,
                                modifier = Modifier.focusRequester(sectionFocus.getValue(s)),
                                onFocused = { if (isTv) select(s) },
                                onClick = {
                                    select(s)
                                    if (isTv) runCatching { paneFocus.requestFocus() }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(if (isTv) 40.dp else 24.dp))
                    // ---- Section choisie
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .onFocusChanged { paneHasFocus = it.hasFocus }
                            .focusRequester(paneFocus)
                            .focusProperties {
                                exit = { if (it == FocusDirection.Left) sectionFocus.getValue(section) else FocusRequester.Default }
                            }
                            .focusGroup(),
                    ) {
                        key(section, ui.sub) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(bottom = 48.dp),
                            ) {
                                Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val sub = ui.sub
                                    if (sub == null) PaneHeader(section.title)
                                    else PaneHeader(
                                        subPageTitle(sub), subtitle = section.title,
                                        onBack = if (isTv) null else ({ ui.pop() }),
                                    )
                                    SectionBody(section, catalog)
                                }
                            }
                        }
                    }
                }
            }
            overlay?.invoke()
        }
    }
}

/** Élément de la colonne des sections. */
@Composable
private fun SectionItem(
    s: SettingsSection,
    selected: Boolean,
    touch: Boolean,
    badge: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> Color.Black
        selected -> Color.White
        else -> Palette.Dim
    }
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (touch) 56.dp else 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = if (touch) LocalIndication.current else null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxIcon(s.icon, size = 20.dp, tint = fg)
        Spacer(Modifier.width(14.dp))
        Text(
            s.title, fontSize = 15.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (badge) Box(Modifier.size(8.dp).background(Palette.Accent, CircleShape))
    }
}

/** Téléphone : liste des sections, puis page de la section avec barre du haut et flèche retour. */
@Composable
private fun CompactSettings(
    section: SettingsSection,
    detail: Boolean,
    catalog: Catalog?,
    onOpen: (SettingsSection) -> Unit,
    onBack: () -> Unit,
) {
    val ui = LocalSettingsUi.current
    if (!detail) {
        val update by Updater.available.collectAsState()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Réglages", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            SettingsSection.entries.forEach { s ->
                SettingsRow(
                    s.title, icon = s.icon, chevron = true,
                    description = s.summary,
                    tags = if (s == SettingsSection.UPDATE && update != null) listOf("Nouveau") else emptyList(),
                    focusKey = "section:${s.name}",
                    onClick = { onOpen(s) },
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            val sub = ui.sub
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, LocalIndication.current, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { FluxIcon(SettingsIcons.Back, size = 24.dp) }
                Spacer(Modifier.width(4.dp))
                Text(
                    if (sub != null) subPageTitle(sub) else section.title,
                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            key(section, sub) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionBody(section, catalog)
                }
            }
        }
    }
}
