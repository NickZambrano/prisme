package com.fluxfr.tv.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.DriveSync
import com.fluxfr.tv.data.GoogleAuth
import com.fluxfr.tv.data.HiddenCategories
import com.fluxfr.tv.data.Household
import com.fluxfr.tv.data.PinHash
import com.fluxfr.tv.data.NetflixImport
import com.fluxfr.tv.data.PrismeNode
import com.fluxfr.tv.data.Profile
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.ProgressStore
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.SettingsSync
import com.fluxfr.tv.data.UpdateState
import com.fluxfr.tv.data.Updater
import com.fluxfr.tv.ui.BrandLogo
import com.fluxfr.tv.ui.FluxIcons
import com.fluxfr.tv.ui.GoogleSyncPanel
import com.fluxfr.tv.ui.Palette
import com.fluxfr.tv.ui.PhoneExtrasPanel
import com.fluxfr.tv.ui.ProfileAvatar
import com.fluxfr.tv.ui.ProfileColors
import com.fluxfr.tv.ui.ProfileEditor
import com.fluxfr.tv.ui.agoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Sections des réglages (colonne de gauche). */
internal enum class SettingsSection(val title: String, val summary: String) {
    PROFILES("Profils", "Qui regarde, Enfants, Google"),
    ACCOUNT("Compte IPTV", "Serveur et identifiants"),
    PLAYBACK("Lecture", "Image, son, qualité et fichiers"),
    CATALOG("Catégories affichées", "Catégories masquées et filtres"),
    SYNC("Synchronisation", "TV de la maison et Google Drive"),
    UPDATE("Mise à jour", "Version et nouvelles versions"),
    ABOUT("À propos", "Prisme et TMDB");

    val icon: ImageVector
        get() = when (this) {
            PROFILES -> SettingsIcons.Person
            ACCOUNT -> SettingsIcons.Server
            PLAYBACK -> FluxIcons.Play
            CATALOG -> SettingsIcons.Category
            SYNC -> SettingsIcons.Sync
            UPDATE -> SettingsIcons.Download
            ABOUT -> FluxIcons.Info
        }
}

private const val APP_PREFS = "fluxfr"
private const val KEY_AFR = "afr"
private const val KEY_NIGHT = "nightMode"
private const val TMDB_ATTRIBUTION =
    "Recommandations et tendances : TMDB. Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB."

/** Titre de la sous-page affichée (barre du haut en mode compact, en-tête sinon). */
@Composable
internal fun subPageTitle(sub: SubPage): String = when (sub) {
    is SubPage.ProfileSheet -> {
        val profiles by Profiles.list.collectAsState()
        profiles.firstOrNull { it.id == sub.id }?.name ?: "Profil"
    }
    is SubPage.Categories -> sub.label
}

/** Contenu du panneau : sous-page éventuelle, sinon la section. */
@Composable
internal fun SectionBody(section: SettingsSection, catalog: Catalog?) {
    val ui = LocalSettingsUi.current
    when (val sub = ui.sub) {
        is SubPage.ProfileSheet -> ProfileSheet(sub.id)
        is SubPage.Categories -> CategoriesPage(sub, catalog)
        null -> when (section) {
            SettingsSection.PROFILES -> ProfilesSection()
            SettingsSection.ACCOUNT -> AccountSection()
            SettingsSection.PLAYBACK -> PlaybackSection()
            SettingsSection.CATALOG -> CatalogSection(catalog)
            SettingsSection.SYNC -> SyncSection()
            SettingsSection.UPDATE -> UpdateSection()
            SettingsSection.ABOUT -> AboutSection()
        }
    }
}

// ------------------------------------------------------------------------------------ Profils

@Composable
private fun ProfilesSection() {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val profiles by Profiles.list.collectAsState()
    val active by Profiles.active.collectAsState()
    var askAtStart by remember { mutableStateOf(Profiles.askAtStart(ctx)) }

    SettingsRow(
        "Demander le profil au démarrage", checked = askAtStart,
        description = "Affiche « Qui regarde ? » à l'ouverture",
        onClick = { askAtStart = !askAtStart; Profiles.setAskAtStart(ctx, askAtStart) },
    )
    SectionLabel("Profils")
    profiles.forEach { p ->
        key(p.id) {
            val linked = remember(p.id, ui.tick) { GoogleAuth.isLinked(ctx, p.id) }
            SettingsRow(
                p.name,
                leading = { ProfileAvatar(p, 32.dp) },
                tags = listOfNotNull(
                    if (p.id == active.id) "Actuel" else null,
                    if (p.kids) "Enfants" else null,
                    if (linked) "Google" else null,
                ),
                description = when {
                    !p.kids -> "Tout le catalogue"
                    p.hasPin -> "Catalogue jeunesse · code parental"
                    else -> "Catalogue jeunesse · sans code"
                },
                chevron = true,
                focusKey = "profile:${p.id}",
                onClick = { ui.push(SubPage.ProfileSheet(p.id)) },
            )
        }
    }
    if (profiles.size < Profiles.MAX_PROFILES) {
        SettingsRow("Ajouter un profil", icon = SettingsIcons.Add, onClick = {
            ui.open { ProfileEditor(null, onClose = ui::close) }
        })
    }
    if (ui.isTv) {
        SettingsRow(
            "Ajouter depuis le téléphone", icon = FluxIcons.Phone,
            description = "Saisis le nom du profil sur ton téléphone",
            onClick = { ui.open { PhoneExtrasPanel(onClose = ui::close) } },
        )
    }
}

/** Fiche d'un profil : nom, couleur, Enfants et code, Google, suppression. */
@Composable
private fun ProfileSheet(id: String) {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val profiles by Profiles.list.collectAsState()
    val p = profiles.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { ui.pop() }
        return
    }
    fun edit(f: (Profile) -> Profile) {
        Profiles.get(id)?.let { Profiles.update(ctx, f(it)) }
    }
    val adults = profiles.count { !it.kids }
    val lastAdult = !p.kids && adults <= 1
    val canDelete = profiles.size > 1 && (p.kids || adults > 1)
    val linked = remember(id, ui.tick) { GoogleAuth.isLinked(ctx, id) }
    val email = remember(id, ui.tick) { GoogleAuth.email(ctx, id) }

    SettingsRow("Nom", icon = SettingsIcons.Edit, value = p.name, onClick = {
        ui.open {
            TextEditDialog(
                "Nom du profil", p.name, maxLength = 20,
                onSave = { n -> if (n.isNotBlank()) edit { it.copy(name = n.trim()) }; ui.close() },
                onDismiss = ui::close,
            )
        }
    })
    RowContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Couleur", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileColors.indices.forEach { i ->
                    ColorDot(i, selected = i == p.color.mod(ProfileColors.size), onClick = { edit { it.copy(color = i) } })
                }
            }
        }
    }
    SettingsRow(
        "Profil Enfants", checked = p.kids, enabled = !lastAdult,
        description = if (lastAdult) "Impossible : il faut garder un profil adulte"
        else "Catalogue jeunesse uniquement, sans TNT ni Réglages",
        onClick = { if (!lastAdult) edit { it.copy(kids = !it.kids, pinHash = if (!it.kids) it.pinHash else "") } },
    )
    if (p.kids) {
        SettingsRow(
            "Code parental", icon = SettingsIcons.Lock,
            value = if (p.hasPin) "Défini" else "Aucun",
            description = "Demandé pour quitter ce profil",
            onClick = {
                ui.open {
                    PinSetupDialog(onDone = { pin ->
                        val hash = PinHash.hash(pin) // seule l'empreinte salée est enregistrée
                        edit { it.copy(pinHash = hash) }
                        ui.close()
                    }, onDismiss = ui::close)
                }
            },
        )
        if (p.hasPin) {
            SettingsRow("Supprimer le code", description = "Quitter ce profil ne demandera plus de code", onClick = {
                edit { it.copy(pinHash = "") }
                ui.pendingFocus = "Code parental"
            })
        }
    }
    if (linked || GoogleAuth.isConfigured()) {
        SettingsRow(
            if (linked) "Compte Google" else "Se connecter avec Google",
            icon = SettingsIcons.Cloud,
            value = if (linked) email ?: "Connecté" else null,
            description = if (ui.isTv) "Reprises et « Ma liste » sur toutes tes TV (Google Drive)"
            else "Reprises et « Ma liste » sur tous tes appareils (Google Drive)",
            chevron = true,
            onClick = { ui.open { GoogleSyncPanel(p, onClose = ui::close) } },
        )
    }
    SettingsRow(
        "Importer un historique (Netflix ou Prime Video)",
        icon = SettingsIcons.Download,
        description = "Marque comme vu ce que tu as regardé sur Netflix ou Prime Video et reprend où tu en es",
        chevron = true,
        onClick = { ui.open { NetflixImportPanel(p, onClose = ui::close) } },
    )
    NetflixUndoRow(p)
    if (canDelete) {
        Spacer(Modifier.height(12.dp))
        SettingsRow("Supprimer le profil", icon = SettingsIcons.Delete, danger = true, onClick = {
            ui.open {
                ConfirmDialog(
                    "Supprimer « ${p.name} » ?",
                    "Ses reprises, sa liste et sa liaison Google seront effacées.",
                    "Supprimer",
                    onConfirm = {
                        ui.scope.launch {
                            withContext(Dispatchers.IO) { runCatching { Profiles.delete(ctx, id) } }
                            ui.close()
                            ui.sub = null
                            ui.pendingFocus = "Demander le profil au démarrage"
                        }
                    },
                    onDismiss = ui::close,
                )
            }
        })
    }
}

/**
 * « Annuler les imports » (fiche d'un profil) : visible seulement si le profil a des titres importés (Netflix,
 * Prime Video) ou des graines ; retire ces titres après confirmation, sans toucher à l'historique Prisme.
 */
@Composable
private fun NetflixUndoRow(p: Profile) {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val version by ProgressStore.version.collectAsState()
    val nf by NetflixImport.state.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val info by produceState<NetflixImport.Info?>(null, p.id, version, nf.running, nf.summary, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { NetflixImport.info(ctx, p.id) }.getOrNull() }
    }
    val i = info ?: return
    if (!i.undoable) return
    val last = i.last
    val description = buildString {
        append(i.label)
        if (last != null) {
            append(" · le ").append(DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE).format(Date(last.at)))
            if (last.fileName.isNotBlank()) append(" (").append(last.fileName).append(')')
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsRow(
        "Annuler les imports", icon = SettingsIcons.Delete, danger = true, enabled = !nf.running,
        description = description,
        onClick = {
            if (nf.running) return@SettingsRow
            ui.open {
                ConfirmDialog(
                    "Annuler les imports ?",
                    "Supprimer les films et épisodes importés depuis Netflix et Prime Video pour ce profil ? " +
                        "Ton historique Prisme n'est pas touché.",
                    "Supprimer",
                    onConfirm = {
                        ui.scope.launch {
                            withContext(Dispatchers.IO) { runCatching { NetflixImport.undo(ctx, p.id) } }
                            refresh++
                            ui.close()
                            ui.pendingFocus = "Importer un historique (Netflix ou Prime Video)"
                        }
                    },
                    onDismiss = ui::close,
                )
            }
        },
    )
}

// ------------------------------------------------------------------------------------ Compte IPTV

@Composable
private fun AccountSection() {
    val ui = LocalSettingsUi.current
    val s = ui.settings
    val tick = ui.tick
    val m3u = remember(tick) { s.mode == Settings.MODE_M3U }
    var reloaded by remember { mutableStateOf(false) }

    SettingsRow("Type de compte", value = if (m3u) "Playlist M3U" else "Xtream Codes")
    if (m3u) {
        SettingsRow("Playlist", value = remember(tick) { s.m3uUrl.substringBefore('?') })
    } else {
        SettingsRow("Serveur", value = remember(tick) { s.server })
        SettingsRow("Utilisateur", value = remember(tick) { s.user })
    }
    if (ui.isTv) {
        SettingsRow(
            "Configurer depuis le téléphone", icon = FluxIcons.Phone,
            description = "Scanne un QR code pour saisir un autre compte",
            onClick = { ui.open { PhoneAccountPanel(s, onSaved = { ui.reloadNow() }, onClose = ui::close) } },
        )
    }
    SettingsRow(
        "Recharger le catalogue", icon = FluxIcons.Refresh, accent = ui.pendingReload,
        value = when {
            ui.pendingReload -> "Modifications en attente"
            reloaded -> "Rechargement lancé"
            else -> null
        },
        description = "Relit chaînes, films et séries chez le fournisseur",
        onClick = { ui.reloadNow(); reloaded = true },
    )
    Spacer(Modifier.height(12.dp))
    SettingsRow(
        "Se déconnecter", icon = SettingsIcons.Logout, danger = true,
        description = "Oublie ce compte IPTV sur cet appareil",
        onClick = {
            ui.open {
                ConfirmDialog(
                    "Se déconnecter ?", "Il faudra ressaisir le compte IPTV.", "Se déconnecter",
                    onConfirm = { ui.close(); ui.logout() }, onDismiss = ui::close,
                )
            }
        },
    )
}

// ------------------------------------------------------------------------------------ Lecture

@Composable
private fun PlaybackSection() {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val s = ui.settings
    val prefs = remember { ctx.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE) }
    // Réglages reçus d'une autre TV : relit les valeurs enregistrées.
    val synced by SettingsSync.applied.collectAsState()
    var afr by remember { mutableStateOf(prefs.getBoolean(KEY_AFR, true)) }
    var night by remember { mutableStateOf(prefs.getBoolean(KEY_NIGHT, false)) }
    var uhd by remember(synced) { mutableStateOf(s.preferUhdLive) }
    var strict by remember(synced) { mutableStateOf(s.strictVodQuality) }
    var unknown by remember(synced) { mutableStateOf(s.keepUnknownLive) }
    val ua = remember(synced, ui.tick) { s.userAgent }
    var historyCleared by remember { mutableStateOf(false) }
    var filesTab by remember { mutableStateOf(s.filesTab) }

    SectionLabel("Image et son")
    SettingsRow(
        "Fréquence d'image auto", checked = afr,
        description = if (ui.isTv) "La TV suit la cadence de la vidéo (24, 25, 50 images/s)"
        else "L'écran suit la cadence de la vidéo (24, 25, 50 images/s)",
        onClick = { afr = !afr; prefs.edit().putBoolean(KEY_AFR, afr).apply() },
    )
    SettingsRow(
        "Mode nuit", checked = night,
        description = if (ui.isTv) "Réduit l'écart de volume entre dialogues et action"
        else "Réduit les écarts de volume : dialogues plus audibles, explosions moins fortes",
        onClick = { night = !night; prefs.edit().putBoolean(KEY_NIGHT, night).apply() },
    )
    SectionLabel("Qualité")
    SettingsRow(
        "Chaînes en 4K d'abord", checked = uhd,
        description = "Versions 4K souvent non officielles",
        onClick = { uhd = !uhd; s.preferUhdLive = uhd; ui.markCatalogChanged() },
    )
    SettingsRow(
        "Films et séries en HD minimum", checked = strict,
        description = "Seulement les titres marqués HD, FHD ou 4K",
        onClick = { strict = !strict; s.strictVodQuality = strict; ui.markCatalogChanged() },
    )
    SettingsRow(
        "Chaînes sans mention de qualité", checked = unknown,
        description = if (ui.isTv) "Les garder aussi dans la liste"
        else "Garde aussi les chaînes dont le nom n'indique ni HD, ni FHD, ni 4K",
        onClick = { unknown = !unknown; s.keepUnknownLive = unknown; ui.markCatalogChanged() },
    )
    SectionLabel("Fichiers")
    SettingsRow(
        "Fichiers (disque local et réseau)", checked = filesTab,
        description = if (ui.isTv) "Ajoute l'onglet Fichiers au menu : clés USB, disques et partages réseau"
        else "Ajoute l'onglet Fichiers : vidéos de l'appareil, clés USB, disques et partages réseau (SMB)",
        onClick = { filesTab = !filesTab; s.filesTab = filesTab },
    )
    SectionLabel("Avancé")
    // Tactile : le texte par défaut mentionne « Android TV » ; affichage neutre (la valeur enregistrée reste inchangée).
    SettingsRow("User-Agent", value = if (ui.isTv) ua else ua.replace("Android TV", "Android"), description = "Seulement si ton fournisseur en exige un", onClick = {
        ui.open {
            TextEditDialog(
                "User-Agent", ua, default = Settings.DEFAULT_UA,
                onSave = { v -> s.userAgent = v.trim(); ui.markCatalogChanged(); ui.close() },
                onDismiss = ui::close,
            )
        }
    })
    Spacer(Modifier.height(12.dp))
    SettingsRow(
        "Vider l'historique de lecture", icon = SettingsIcons.Delete, danger = true,
        value = if (historyCleared) "Historique vidé" else null,
        description = "Efface « Continuer à regarder » et les reprises",
        onClick = {
            ui.open {
                ConfirmDialog(
                    "Vider l'historique ?", "« Continuer à regarder » et les reprises seront effacés.", "Tout effacer",
                    onConfirm = {
                        ui.close()
                        ui.scope.launch {
                            withContext(Dispatchers.IO) { runCatching { ProgressStore.get(ctx).clear() } }
                            historyCleared = true
                        }
                    },
                    onDismiss = ui::close,
                )
            }
        },
    )
}

// ------------------------------------------------------------------------------------ Catégories

private fun categoryGroups(catalog: Catalog) = listOf(
    Triple("Chaînes", HiddenCategories.LIVE, catalog.unfiltered.channelCategories),
    Triple("Films", HiddenCategories.MOVIE, catalog.unfiltered.movieCategories),
    Triple("Séries", HiddenCategories.SERIES, catalog.unfiltered.seriesCategories),
)

@Composable
private fun CatalogSection(catalog: Catalog?) {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val s = ui.settings
    val hidden by HiddenCategories.hidden.collectAsState()
    val synced by SettingsSync.applied.collectAsState()
    val fr = remember(synced, ui.tick) { s.frKeywords }
    val excl = remember(synced, ui.tick) { s.excludeKeywords }

    if (catalog == null) {
        StatusLine("Catalogue en cours de chargement…")
    } else {
        val groups = remember(catalog) { categoryGroups(catalog) }
        for ((label, kind, cats) in groups) {
            if (cats.isEmpty()) continue
            key(kind) {
            val n = remember(cats, hidden) { cats.count { HiddenCategories.key(kind, it) in hidden } }
            SettingsRow(
                label,
                value = if (!ui.isTv) "${cats.size - n} affichées sur ${cats.size}"
                else if (n > 0) "$n masquée${if (n > 1) "s" else ""} sur ${cats.size}" else "${cats.size} affichées",
                description = "Appui long sur une catégorie dans $label : la masquer",
                chevron = true,
                focusKey = "cat:$kind",
                onClick = { ui.push(SubPage.Categories(kind, label)) },
            )
            }
        }
    }
    SectionLabel("Filtres")
    SettingsRow("Mots du contenu français", value = fr,
        description = if (ui.isTv) "Repèrent les catégories et titres français"
        else "Mots qui repèrent les catégories et titres français (FR, FRANCE…)", onClick = {
        ui.open {
            TextEditDialog(
                "Mots du contenu français", fr, default = Settings.DEFAULT_FR, hint = "Séparés par des virgules",
                onSave = { v -> s.frKeywords = v; ui.markCatalogChanged(); ui.close() }, onDismiss = ui::close,
            )
        }
    })
    SettingsRow("Mots à exclure", value = excl, description = "VOSTFR, adulte…", onClick = {
        ui.open {
            TextEditDialog(
                "Mots à exclure", excl, default = Settings.DEFAULT_EXCLUDE, hint = "Séparés par des virgules",
                onSave = { v -> s.excludeKeywords = v; ui.markCatalogChanged(); ui.close() }, onDismiss = ui::close,
            )
        }
    })
    if (ui.pendingReload) {
        SettingsRow(
            "Appliquer maintenant", icon = FluxIcons.Refresh, accent = true,
            description = "Recharge le catalogue (sinon en quittant les réglages)",
            onClick = { ui.reloadNow() },
        )
    }
    if (hidden.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            "Tout réafficher", danger = true, value = "${hidden.size} masquée${if (hidden.size > 1) "s" else ""}",
            description = "Réaffiche toutes les catégories masquées",
            onClick = {
                ui.open {
                    ConfirmDialog(
                        "Tout réafficher ?", "Toutes les catégories masquées redeviennent visibles.", "Réafficher",
                        onConfirm = { HiddenCategories.clear(ctx); ui.close() }, onDismiss = ui::close,
                    )
                }
            },
        )
    }
}

/** Pastilles d'un type de catégories : OK masque ou réaffiche. */
@Composable
private fun CategoriesPage(sub: SubPage.Categories, catalog: Catalog?) {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val hidden by HiddenCategories.hidden.collectAsState()
    val cats = remember(catalog, sub.kind) {
        catalog?.let { c -> categoryGroups(c).firstOrNull { it.second == sub.kind }?.third }.orEmpty()
    }
    if (cats.isEmpty()) {
        LaunchedEffect(Unit) { ui.pop() }
        return
    }
    val hiddenHere = remember(cats, hidden) { cats.filter { HiddenCategories.key(sub.kind, it) in hidden } }
    StatusLine(if (ui.isTv) "OK sur une catégorie pour la masquer ou la réafficher" else "Touche une catégorie pour la masquer ou la réafficher")
    Spacer(Modifier.height(4.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (c in cats) {
            val h = HiddenCategories.key(sub.kind, c) in hidden
            CategoryChip(c, hidden = h, onClick = { HiddenCategories.set(ctx, sub.kind, c, hide = !h) })
        }
    }
    if (hiddenHere.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SettingsRow(
            "Tout afficher", value = "${hiddenHere.size} masquée${if (hiddenHere.size > 1) "s" else ""}",
            onClick = { hiddenHere.forEach { HiddenCategories.set(ctx, sub.kind, it, hide = false) } },
        )
    }
}

// ------------------------------------------------------------------------------------ Synchronisation

@Composable
private fun SyncSection() {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val syncStatus by SettingsSync.status.collectAsState()
    val syncBusy by SettingsSync.busy.collectAsState()
    val syncMsg by SettingsSync.message.collectAsState()
    var syncOn by remember { mutableStateOf(SettingsSync.isEnabled(ctx)) }
    val profiles by Profiles.list.collectAsState()
    val lastMap by DriveSync.lastSync.collectAsState()
    val statusMap by DriveSync.status.collectAsState()
    val busy by DriveSync.busy.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        PrismeNode.start(ctx) // sans effet si l'appli l'a déjà démarré
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }

    SectionLabel(if (ui.isTv) "Entre les TV de la maison" else "Entre tes appareils")
    SettingsRow(
        if (ui.isTv) "Partager avec les autres TV" else "Partager avec tes autres appareils", checked = syncOn,
        description = "Profils, catégories masquées, favoris et préférences",
        onClick = { syncOn = !syncOn; SettingsSync.setEnabled(ctx, syncOn) },
    )
    // Synchronisation seulement entre TV associées (clé du foyer partagée par code, voir Household).
    val pairedState by Household.paired.collectAsState()
    LaunchedEffect(Unit) { Household.isPaired(ctx) }
    val paired = pairedState == true
    val joining by Household.joining.collectAsState()
    var pairMsg by remember { mutableStateOf<String?>(null) }
    if (syncOn) {
        val st = syncStatus
        if (paired) {
            SettingsRow(
                "Synchroniser maintenant", icon = SettingsIcons.Sync,
                value = when {
                    syncBusy -> "En cours…"
                    st != null -> SettingsSync.describe(st, now)
                    else -> "Pas encore synchronisé"
                },
                description = syncMsg ?: "Par le réseau local · le compte IPTV n'est jamais partagé",
                onClick = { if (!syncBusy) SettingsSync.syncNowAsync(ctx) },
            )
        } else {
            SettingsRow(
                "État", value = "Non associée",
                description = if (ui.isTv) "Associe cette TV à une autre de la maison pour partager les réglages"
                else "Associe cet appareil à un autre pour partager les réglages",
            )
        }
        SettingsRow(
            if (ui.isTv) "Associer une autre TV" else "Associer un autre appareil", icon = SettingsIcons.Sync,
            description = if (ui.isTv) "Affiche un code à saisir sur l'autre TV (valable 2 minutes)"
            else "Affiche un code à saisir sur l'autre appareil (valable 2 minutes)",
            onClick = { pairMsg = null; ui.open { PairHostPanel(onClose = ui::close) } },
        )
        SettingsRow(
            if (ui.isTv) "Saisir le code d'une autre TV" else "Saisir le code d'un autre appareil", icon = SettingsIcons.Edit,
            value = if (joining) "Association…" else null,
            description = if (ui.isTv) "Code affiché sur l'autre TV (Réglages → Synchronisation → Associer une autre TV)"
            else "Code affiché sur l'autre appareil (Réglages → Synchronisation → Associer un autre appareil)",
            onClick = {
                if (!joining) ui.open {
                    TextEditDialog(
                        "Code d'association", "", maxLength = 6,
                        hint = if (ui.isTv) "Les 6 chiffres affichés sur l'autre TV" else "Les 6 chiffres affichés sur l'autre appareil",
                        onSave = { code ->
                            ui.close()
                            pairMsg = null
                            val ok = if (ui.isTv) "TV associée : les réglages vont se synchroniser"
                            else "Appareil associé : les réglages vont se synchroniser"
                            Household.joinAsync(ctx, code) { err -> pairMsg = err ?: ok }
                        },
                        onDismiss = ui::close,
                    )
                }
            },
        )
        pairMsg?.let { StatusLine(it) }
        if (paired) {
            SettingsRow(
                if (ui.isTv) "Dissocier cette TV" else "Dissocier cet appareil",
                description = "Elle ne partagera plus ses réglages tant qu'elle n'est pas associée à nouveau",
                onClick = { Household.unpair(ctx); pairMsg = null },
            )
        }
    }
    SectionLabel("Google Drive, par profil")
    profiles.forEach { p ->
        key(p.id) {
            val linked = remember(p.id, ui.tick) { GoogleAuth.isLinked(ctx, p.id) }
            val email = remember(p.id, ui.tick) { GoogleAuth.email(ctx, p.id) }
            val last = lastMap[p.id] ?: remember(p.id, ui.tick, busy) { DriveSync.lastSyncFor(ctx, p.id) }
            val status = statusMap[p.id]
            SettingsRow(
                p.name,
                leading = { ProfileAvatar(p, 28.dp) },
                value = when {
                    !linked -> "Non connecté"
                    p.id in busy -> "Synchronisation…"
                    last > 0 -> "Synchronisé " + agoText(last, maxOf(now, last))
                    else -> "Pas encore synchronisé"
                },
                description = when {
                    !linked -> "Reprises et « Ma liste » via son compte Google"
                    status != null && status != "Synchronisé" -> status
                    else -> email ?: "Compte Google connecté"
                },
                chevron = true,
                focusKey = "drive:${p.id}",
                onClick = { ui.open { GoogleSyncPanel(p, onClose = ui::close) } },
            )
        }
    }
}

// ------------------------------------------------------------------------------------ Mise à jour

@Composable
private fun UpdateSection() {
    val ui = LocalSettingsUi.current
    val ctx = LocalContext.current
    val available by Updater.available.collectAsState()
    val upState by Updater.state.collectAsState()
    val checking by Updater.checking.collectAsState()
    val checkMsg by Updater.checkMessage.collectAsState()
    var settingsMissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { PrismeNode.start(ctx) }
    // Retour des paramètres Android (« sources inconnues ») : reprend l'APK du téléphone s'il attendait.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (Updater.state.value == UpdateState.NeedsPermission && Updater.canInstall(ctx)) {
            Updater.reset()
            Updater.resumePendingAsync(ctx)
        }
    }

    SettingsRow("Version installée", value = Updater.currentVersionName)
    val info = available
    if (info != null) {
        SettingsRow(
            "Installer la version ${info.versionName}", icon = FluxIcons.Check, accent = true,
            value = info.device,
            description = if (ui.isTv) "Transmise par une TV de la maison" else "Transmise par un autre appareil de la maison",
            onClick = { Updater.installAsync(ctx, info) },
        )
    }
    updateStateText(upState)?.let { (t, c) -> StatusLine(t, c) }
    if (upState == UpdateState.NeedsPermission) {
        SettingsRow(
            "Autoriser l'installation", icon = FluxIcons.Settings, accent = true,
            description = if (settingsMissing) "Paramètres > Applications > Prisme > Installer des applis inconnues"
            else "Active « Autoriser cette source », puis reviens ici",
            onClick = { settingsMissing = !Updater.openInstallPermissionSettings(ctx) },
        )
    }
    SettingsRow(
        "Rechercher une mise à jour", icon = FluxIcons.Refresh,
        value = if (checking) "Recherche…" else null,
        description = if (ui.isTv) "Auprès des autres TV du réseau local" else "Auprès de tes autres appareils du réseau local",
        onClick = { if (!checking) Updater.checkAsync(ctx) },
    )
    if (info == null && !checking) checkMsg?.let { StatusLine(it) }
    if (ui.isTv) {
        SettingsRow(
            "Mettre à jour depuis le téléphone", icon = FluxIcons.Phone,
            description = "Envoie le fichier .apk de Prisme depuis ton téléphone",
            onClick = { Updater.reset(); ui.open { PhoneUpdatePanel(onClose = ui::close) } },
        )
    }
}

// ------------------------------------------------------------------------------------ À propos

@Composable
private fun AboutSection() {
    Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp)) {
        BrandLogo()
        Spacer(Modifier.height(6.dp))
        Text("Version ${Updater.currentVersionName}", fontSize = 13.sp, color = Palette.Dim)
    }
    SectionLabel("Raccourcis")
    SettingsRow("Continuer à regarder", value = "Appui long : retirer")
    // Raccourcis réels du lecteur (▲▼ ouvre la liste des chaînes ; ◀▶ avance / recule dans le direct).
    if (LocalSettingsUi.current.isTv) {
        SettingsRow("Changer de chaîne", value = "CH+ / CH− · ▲▼ : liste")
        SettingsRow("Pendant une chaîne", value = "◀▶ ±10 s · OK : pause")
        SettingsRow("Options de lecture", value = "MENU : audio, sous-titres")
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.padding(horizontal = 4.dp)) {
        Spacer(Modifier.width(0.dp))
        Text(TMDB_ATTRIBUTION, fontSize = 11.sp, color = Palette.Faint, lineHeight = 15.sp)
    }
}
