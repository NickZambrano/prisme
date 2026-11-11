package com.fluxfr.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.Source
import com.fluxfr.tv.data.files.FileEntry
import com.fluxfr.tv.data.files.FilesStore
import com.fluxfr.tv.data.files.LocalStorage
import com.fluxfr.tv.data.files.MediaFiles
import com.fluxfr.tv.data.files.SmbClient
import com.fluxfr.tv.data.files.SmbPaths
import com.fluxfr.tv.data.files.SmbShare
import com.fluxfr.tv.data.files.StorageRoot
import com.fluxfr.tv.player.PlayItem
import com.fluxfr.tv.player.PlayerSession
import com.fluxfr.tv.ui.settings.ConfirmDialog
import com.fluxfr.tv.ui.settings.SettingsButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ================================================================ Onglet « Fichiers »
//
// Vidéos de l'appareil (mémoire interne, clés USB, disques), dossiers choisis avec le sélecteur d'Android et
// partages réseau (SMB). Affiché seulement si l'option est active (Réglages → Lecture → Fichiers).

/** Icônes de l'onglet (tracés Material, licence Apache 2.0). */
object FilesIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Folder by lazy {
        icon("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val FolderOpen by lazy {
        icon("folderOpen", "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM20,18H4V8h16v10z")
    }
    val Usb by lazy {
        icon(
            "usb",
            "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z",
        )
    }
    val Storage by lazy {
        icon("storage", "M18,2h-8L4.02,8 4,20c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,8h-2V4h2v4zM15,8h-2V4h2v4zM18,8h-2V4h2v4z")
    }
    val Network by lazy {
        icon(
            "network",
            "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1zM7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1zM7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }
}

private enum class SrcKind { LOCAL, TREE, SMB }

/** Source parcourue : [id] = chemin du volume, URI du dossier choisi ou identifiant du partage. */
private data class Src(val kind: SrcKind, val id: String, val label: String) {
    val key: String get() = "${kind.name}:$id"
}

/** Dossier de la pile de navigation : [ref] = chemin, identifiant de document ou chemin SMB (« / »). */
private data class Crumb(val name: String, val ref: String)

private data class Place(val src: Src, val stack: List<Crumb>) {
    val here: Crumb get() = stack.last()
}

/** Mémoire de la session (processus) : dossier ouvert, et dernier dossier de chaque source. */
private object FilesMemory {
    var place: Place? = null
    val lastStack = HashMap<String, List<Crumb>>()
}

/** Contenu affiché et le dossier auquel il appartient (le précédent reste visible pendant la lecture du suivant). */
private data class Shown(val place: Place, val listing: Listing)

private sealed interface Listing {
    data object Loading : Listing
    data class Ok(val raw: List<FileEntry>, val shown: List<FileEntry>) : Listing
    data class Failed(
        val message: String,
        val askPermission: Boolean = false,
        val offerPicker: Boolean = false,
        val editShare: Boolean = false,
    ) : Listing
}

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    var place by remember { mutableStateOf(FilesMemory.place) }
    // Nom de l'élément à focaliser après un changement de dossier (TV) : le dossier d'où l'on revient.
    var focusName by remember { mutableStateOf<String?>(null) }
    var wantFocus by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<SmbShare?>(null) }
    var confirmRemoveTree by remember { mutableStateOf<Src?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // TV : pendant un changement de dossier, la ligne qui avait le focus disparaît ; sans point d'ancrage, le focus
    // partait dans le menu de gauche (qui se rouvrait). Il est « garé » ici, invisible, jusqu'à la nouvelle liste.
    val parking = remember { FocusRequester() }
    var parked by remember { mutableStateOf(false) }

    fun go(p: Place?, focus: String? = null) {
        if (isTv) {
            parked = true
            runCatching { parking.requestFocus() }
        }
        place = p
        FilesMemory.place = p
        if (p != null) FilesMemory.lastStack[p.src.key] = p.stack
        focusName = focus
        wantFocus = isTv
    }

    fun open(src: Src, root: Crumb) {
        val remembered = FilesMemory.lastStack[src.key]?.takeIf { it.firstOrNull() == root }
        go(Place(src, remembered ?: listOf(root)))
    }

    fun up() {
        val p = place ?: return
        if (p.stack.size > 1) go(p.copy(stack = p.stack.dropLast(1)), focus = p.here.name)
        else go(null, focus = p.src.key)
    }

    // Retour : dossier parent, puis liste des sources ; depuis les sources, l'appli reprend la main (Accueil).
    BackHandler(enabled = place != null && editor == null && confirmRemoveTree == null) { up() }
    BackHandler(enabled = editor != null) { editor = null }
    BackHandler(enabled = confirmRemoveTree != null) { confirmRemoveTree = null }

    // Sélecteur de dossier d'Android (SAF) : clés USB illisibles directement sur Android 11+, dossiers précis.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (ok) {
            FilesStore.addTree(ctx, uri.toString())
            message = null
        } else message = "Android n'a pas accordé l'accès durable à ce dossier."
    }
    val launchPicker: () -> Unit = {
        try {
            picker.launch(null)
        } catch (_: ActivityNotFoundException) {
            message = "Le sélecteur de dossiers d'Android n'est pas disponible sur cet appareil."
        } catch (_: SecurityException) {
            message = "Le sélecteur de dossiers d'Android n'est pas disponible sur cet appareil."
        }
    }
    var permTick by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val askPermission: () -> Unit = {
        runCatching { permission.launch(LocalStorage.readPermission) }
            .onFailure { message = "Impossible de demander l'autorisation sur cet appareil." }
    }

    Box(Modifier.fillMaxSize()) {
        if (isTv) {
            Box(
                Modifier.size(1.dp)
                    .focusProperties { canFocus = parked }
                    .focusRequester(parking)
                    .focusable()
            )
        }
        val p = place
        val focusDone = { wantFocus = false; parked = false }
        if (p == null) {
            SourcesPage(
                focusKey = focusName,
                wantFocus = wantFocus,
                onFocusDone = focusDone,
                permTick = permTick,
                message = message,
                onOpen = { src, root -> message = null; open(src, root) },
                onEditShare = { editor = it },
                onAddShare = {
                    editor = SmbShare(id = "", label = "", host = "")
                },
                onAddFolder = launchPicker,
                onRemoveTree = { confirmRemoveTree = it },
                onAskPermission = askPermission,
            )
        } else {
            FolderPage(
                place = p,
                focusName = focusName,
                wantFocus = wantFocus,
                onFocusDone = focusDone,
                permTick = permTick,
                onOpenDir = { e -> go(p.copy(stack = p.stack + Crumb(e.name, e.ref))) },
                onUp = { up() },
                onAskPermission = askPermission,
                onPicker = launchPicker,
                onEditShare = { FilesStore.share(ctx, p.src.id)?.let { editor = it } },
            )
        }

        editor?.let { s ->
            ShareEditor(
                initial = s,
                onSaved = { saved ->
                    editor = null
                    // Partage modifié alors qu'il était ouvert : on repart de sa racine.
                    if (place?.src?.id == saved.id) go(null, focus = "SMB:${saved.id}")
                    FilesMemory.lastStack.remove("SMB:${saved.id}")
                },
                onDelete = { id ->
                    FilesStore.deleteShare(ctx, id)
                    FilesMemory.lastStack.remove("SMB:$id")
                    editor = null
                    if (place?.src?.id == id) go(null)
                },
                onDismiss = { editor = null },
            )
        }
        confirmRemoveTree?.let { src ->
            ConfirmDialog(
                "Retirer « ${src.label} » ?",
                "Le dossier reste sur le support ; Prisme n'y aura simplement plus accès.",
                "Retirer",
                onConfirm = {
                    runCatching {
                        ctx.contentResolver.releasePersistableUriPermission(Uri.parse(src.id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    FilesStore.removeTree(ctx, src.id)
                    FilesMemory.lastStack.remove(src.key)
                    confirmRemoveTree = null
                },
                onDismiss = { confirmRemoveTree = null },
            )
        }
    }
}

// ---------------------------------------------------------------- Sources

private class SourcesData(val roots: List<StorageRoot>, val trees: List<Pair<String, String>>, val permission: Boolean)

@Composable
private fun SourcesPage(
    focusKey: String?,
    wantFocus: Boolean,
    onFocusDone: () -> Unit,
    permTick: Int,
    message: String?,
    onOpen: (Src, Crumb) -> Unit,
    onEditShare: (SmbShare) -> Unit,
    onAddShare: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveTree: (Src) -> Unit,
    onAskPermission: () -> Unit,
) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    val shares by FilesStore.shares.collectAsState()
    val trees by FilesStore.trees.collectAsState()
    // Clé USB branchée / retirée pendant que l'appli était en arrière-plan : volumes relus au retour.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val data by produceState<SourcesData?>(null, trees, resumeTick, permTick) {
        value = withContext(Dispatchers.IO) {
            SourcesData(
                roots = runCatching { LocalStorage.roots(ctx) }.getOrDefault(emptyList()),
                trees = trees.map { t -> t to runCatching { LocalStorage.treeLabel(ctx, Uri.parse(t)) }.getOrDefault("Dossier") },
                permission = LocalStorage.hasReadPermission(ctx),
            )
        }
    }
    val target = remember { FocusRequester() }
    val scroll = rememberScrollState()
    ScrollToTopEffect { scroll.animateScrollTo(0) }
    // Retour depuis une source (TV) : le focus revient sur sa ligne.
    LaunchedEffect(wantFocus, data) {
        if (!wantFocus || data == null) return@LaunchedEffect
        repeat(4) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) { onFocusDone(); return@LaunchedEffect }
            androidx.compose.runtime.withFrameNanos { }
        }
        onFocusDone()
    }
    fun Modifier.target(key: String) = if (key == focusKey) this.focusRequester(target) else this

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = screenPadStart(), end = screenPadEnd(), top = if (isTv) 32.dp else 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilesHeader("Fichiers", "Vidéos de l'appareil, clés USB, disques et partages réseau")
        message?.let { Notice(it) }
        val d = data
        GroupLabel("Sur cet appareil")
        if (d == null) {
            Notice("Recherche des supports…")
        } else {
            if (!d.permission) {
                FileRow(
                    "Autoriser l'accès aux vidéos", FilesIcons.Storage,
                    detail = "Nécessaire pour parcourir la mémoire et les clés USB",
                    accent = true, onClick = onAskPermission,
                )
            }
            for (r in d.roots) {
                val src = Src(SrcKind.LOCAL, r.path, r.label)
                FileRow(
                    r.label, if (r.removable) FilesIcons.Usb else FilesIcons.Storage,
                    detail = r.path, modifier = Modifier.target(src.key),
                    onClick = { onOpen(src, Crumb(r.label, r.path)) },
                )
            }
            for ((uri, label) in d.trees) {
                val src = Src(SrcKind.TREE, uri, label)
                FileRow(
                    label, FilesIcons.FolderOpen,
                    detail = if (isTv) "Dossier choisi · appui long : retirer" else "Dossier choisi · appui long pour retirer",
                    modifier = Modifier.target(src.key),
                    onClick = { onOpen(src, Crumb(label, "")) },
                    onLongClick = { onRemoveTree(src) },
                )
            }
        }
        FileRow(
            "Choisir un dossier…", FluxIcons.Plus,
            detail = "Clé USB ou dossier illisible ci-dessus : passe par le sélecteur d'Android",
            onClick = onAddFolder,
        )
        GroupLabel("Réseau")
        for (s in shares.sortedWith(compareBy(MediaFiles.naturalOrder) { it.displayName })) {
            val src = Src(SrcKind.SMB, s.id, s.displayName)
            FileRow(
                s.displayName, FilesIcons.Network,
                detail = buildString {
                    append("\\\\").append(s.host)
                    if (s.rootSegments.isNotEmpty()) append('\\').append(s.rootSegments.joinToString("\\"))
                    append(if (s.guest) " · invité" else " · ${s.user}")
                    append(if (isTv) " · appui long : modifier" else " · appui long pour modifier")
                },
                modifier = Modifier.target(src.key),
                onClick = { onOpen(src, Crumb(s.displayName, s.rootSegments.joinToString("/"))) },
                onLongClick = { onEditShare(s) },
            )
        }
        FileRow(
            "Ajouter un partage réseau", FluxIcons.Plus,
            detail = "Dossier partagé d'un PC ou d'un NAS (SMB)",
            onClick = onAddShare,
        )
    }
}

// ---------------------------------------------------------------- Dossier

@Composable
private fun FolderPage(
    place: Place,
    focusName: String?,
    wantFocus: Boolean,
    onFocusDone: () -> Unit,
    permTick: Int,
    onOpenDir: (FileEntry) -> Unit,
    onUp: () -> Unit,
    onAskPermission: () -> Unit,
    onPicker: () -> Unit,
    onEditShare: () -> Unit,
) {
    val ctx = LocalContext.current
    val isTv = LocalIsTv.current
    var reload by remember { mutableIntStateOf(0) }
    // Le dossier précédent reste affiché tant que le nouveau n'est pas lu (pas d'écran vide « Chargement… » qui
    // clignote à chaque dossier) ; « Chargement… » n'apparaît que si la lecture dure (réseau lent).
    val shown by produceState(Shown(place, Listing.Loading), place, reload, permTick) {
        val slow = launch { delay(350); value = Shown(place, Listing.Loading) }
        val r = withContext(Dispatchers.IO) { load(ctx, place) }
        slow.cancel()
        value = Shown(place, r)
    }
    val listState = rememberLazyListState()
    ScrollToTopEffect { listState.animateScrollToItem(0) }
    val target = remember { FocusRequester() }
    val fallback = remember { FocusRequester() }
    val l = shown.listing
    val current = shown.place == place
    @Suppress("NAME_SHADOWING") val place = shown.place
    // TV : à l'arrivée dans un dossier, focus sur le premier élément (ou sur le dossier d'où l'on revient).
    LaunchedEffect(wantFocus, l, current) {
        if (!wantFocus || !current || l is Listing.Loading) return@LaunchedEffect
        if (l is Listing.Ok) {
            val idx = l.shown.indexOfFirst { it.name == focusName }.coerceAtLeast(0)
            if (l.shown.isNotEmpty()) runCatching { listState.scrollToItem(idx + 1) }
        }
        repeat(5) {
            val ok = runCatching { target.requestFocus() }.getOrDefault(false) ||
                runCatching { fallback.requestFocus() }.getOrDefault(false)
            if (ok) { onFocusDone(); return@LaunchedEffect }
            delay(30)
        }
        onFocusDone()
    }

    val crumbs = place.stack.joinToString("  ›  ") { it.name }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(
            start = screenPadStart(), end = screenPadEnd(), top = if (isTv) 32.dp else 8.dp, bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 6.dp else 4.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isTv) {
                    RoundIconButton(TouchIcons.Back, onUp)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        place.here.name, fontSize = if (isTv) 26.sp else 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (isTv) "$crumbs  ·  Retour : dossier parent" else crumbs,
                        fontSize = 13.sp, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(if (isTv) 12.dp else 6.dp))
        }
        when (l) {
            Listing.Loading -> item(key = "loading") { Notice("Chargement…") }
            is Listing.Failed -> item(key = "failed") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Notice(l.message, color = Palette.Error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            l.askPermission -> SettingsButton("Autoriser l'accès", onAskPermission, Modifier.focusRequester(fallback), primary = true)
                            l.offerPicker -> SettingsButton("Choisir avec Android", onPicker, Modifier.focusRequester(fallback), primary = true)
                            l.editShare -> SettingsButton("Modifier le partage", onEditShare, Modifier.focusRequester(fallback), primary = true)
                            else -> SettingsButton("Réessayer", { reload++ }, Modifier.focusRequester(fallback), primary = true)
                        }
                        if (l.askPermission || l.offerPicker || l.editShare) SettingsButton("Réessayer", { reload++ })
                        SettingsButton("Retour", onUp)
                    }
                }
            }
            is Listing.Ok -> {
                if (l.shown.isEmpty()) {
                    item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Notice("Aucun dossier ni aucune vidéo ici.")
                            SettingsButton("Retour", onUp, Modifier.focusRequester(fallback))
                        }
                    }
                }
                val focusIdx = l.shown.indexOfFirst { it.name == focusName }.coerceAtLeast(0)
                itemsIndexed(l.shown, key = { _, e -> (if (e.isDir) "d:" else "f:") + e.name }) { i, e ->
                    FileRow(
                        e.name,
                        if (e.isDir) FilesIcons.Folder else FluxIcons.Movie,
                        value = if (e.isDir) null else MediaFiles.formatSize(e.size).ifBlank { null },
                        modifier = if (i == focusIdx) Modifier.focusRequester(target) else Modifier,
                        onClick = {
                            if (e.isDir) onOpenDir(e)
                            else play(ctx, place, l, e)
                        },
                    )
                }
            }
        }
    }
}

/** Contenu d'un dossier (hors du fil principal) ; erreurs traduites en français. */
private fun load(ctx: Context, place: Place): Listing {
    val src = place.src
    val ref = place.here.ref
    val raw: List<FileEntry> = when (src.kind) {
        SrcKind.LOCAL -> LocalStorage.list(File(ref)) ?: return if (!LocalStorage.hasReadPermission(ctx)) {
            Listing.Failed("Prisme n'a pas encore l'autorisation de lire les vidéos de l'appareil.", askPermission = true)
        } else {
            Listing.Failed(
                "Android ne laisse pas Prisme lire ce dossier directement. Choisis-le avec le sélecteur d'Android pour y accéder.",
                offerPicker = true,
            )
        }
        SrcKind.TREE -> LocalStorage.listTree(ctx, Uri.parse(src.id), ref.ifEmpty { null })
            ?: return Listing.Failed("Ce dossier n'est plus accessible (support retiré ?). Branche-le, ou retire-le et choisis-le à nouveau.")
        SrcKind.SMB -> {
            val share = FilesStore.share(ctx, src.id) ?: return Listing.Failed("Ce partage a été supprimé.")
            try {
                SmbClient.list(share, SmbPaths.segments(ref))
            } catch (t: Throwable) {
                val msg = SmbClient.message(t)
                return Listing.Failed(msg, editShare = msg == com.fluxfr.tv.data.files.SmbErrors.MSG_AUTH ||
                    msg == com.fluxfr.tv.data.files.SmbErrors.MSG_SHARE || msg == com.fluxfr.tv.data.files.SmbErrors.MSG_HOST)
            }
        }
    }
    return Listing.Ok(raw, MediaFiles.prepare(raw))
}

/** Lance la vidéo choisie ; les autres vidéos du dossier forment la file de lecture (suivante / précédente). */
private fun play(ctx: Context, place: Place, l: Listing.Ok, chosen: FileEntry) {
    val videos = l.shown.filter { !it.isDir }
    val index = videos.indexOf(chosen).coerceAtLeast(0)
    val names = l.raw.filter { !it.isDir }.map { it.name }
    val byName = l.raw.associateBy { it.name }
    val folder = place.here.name
    val queue = videos.map { e ->
        val sub = MediaFiles.subtitleFor(e.name, names)?.let { byName[it] }
        val (uri, key, subUri) = when (place.src.kind) {
            SrcKind.LOCAL -> Triple(
                Uri.fromFile(File(e.ref)).toString(), LocalStorage.progressKey(e.ref),
                sub?.let { Uri.fromFile(File(it.ref)).toString() },
            )
            SrcKind.TREE -> {
                val tree = Uri.parse(place.src.id)
                val u = LocalStorage.documentUri(tree, e.ref).toString()
                Triple(u, "saf:$u", sub?.let { LocalStorage.documentUri(tree, it.ref).toString() })
            }
            SrcKind.SMB -> {
                val segs = SmbPaths.segments(e.ref)
                Triple(
                    SmbPaths.buildUri(place.src.id, segs), SmbPaths.progressKey(place.src.id, segs),
                    sub?.let { SmbPaths.buildUri(place.src.id, SmbPaths.segments(it.ref)) },
                )
            }
        }
        PlayItem(
            title = MediaFiles.baseName(e.name), subtitle = folder,
            sources = listOf(Source(uri, Quality.UNKNOWN)), live = false,
            progressKey = key, type = MediaFiles.TYPE, itemId = key, poster = null,
            subtitleUri = subUri,
        )
    }
    PlayerSession.start(ctx, queue, index)
}

// ---------------------------------------------------------------- Partage réseau : ajout / modification

@Composable
private fun ShareEditor(
    initial: SmbShare,
    onSaved: (SmbShare) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val isNew = initial.id.isEmpty()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(initial.label) }
    var host by remember { mutableStateOf(initial.host) }
    var path by remember { mutableStateOf(initial.path) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var domain by remember { mutableStateOf(initial.domain) }
    var smb1 by remember { mutableStateOf(initial.allowSmb1) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    fun build(): SmbShare? {
        val (h, p) = SmbPaths.parseHostInput(host, path)
        if (h.isBlank()) { status = "Indique l'adresse du PC ou du NAS (nom ou adresse IP)." to false; return null }
        return SmbShare(
            id = initial.id.ifEmpty { FilesStore.newId() }, label = label.trim(), host = h, path = p,
            user = user.trim(), password = password, domain = domain.trim(), allowSmb1 = smb1,
        )
    }

    ModalPanel(640.dp) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (isNew) "Ajouter un partage réseau" else "Modifier le partage",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Dossier partagé d'un PC Windows, d'un Mac ou d'un NAS (SMB 2/3). Utilisateur vide : accès invité.",
                fontSize = 12.sp, color = Palette.Dim, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                host, { host = it.take(200) }, "Adresse (nom ou IP)", Modifier.fillMaxWidth().focusRequester(first),
                keyboardType = KeyboardType.Uri, placeholder = "192.168.1.20 ou \\\\NAS",
            )
            Spacer(Modifier.height(10.dp))
            TvTextField(path, { path = it.take(300) }, "Partage / dossier (facultatif)", Modifier.fillMaxWidth(), placeholder = "Films/HD")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(user, { user = it.take(100) }, "Utilisateur", Modifier.weight(1f))
                TvTextField(password, { password = it.take(200) }, "Mot de passe", Modifier.weight(1f), password = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(domain, { domain = it.take(100) }, "Domaine (facultatif)", Modifier.weight(1f))
                TvTextField(label, { label = it.take(60) }, "Nom affiché (facultatif)", Modifier.weight(1f), imeAction = ImeAction.Done)
            }
            Spacer(Modifier.height(10.dp))
            ToggleRow("Autoriser SMB1 (très vieux NAS, moins sûr)", smb1, { smb1 = !smb1 }, Modifier.fillMaxWidth())
            status?.let { (t, ok) ->
                Spacer(Modifier.height(10.dp))
                Text(t, fontSize = 13.sp, color = if (ok) Palette.Success else Palette.Error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsButton(if (busy) "Test…" else "Tester", {
                    if (busy) return@SettingsButton
                    val s = build() ?: return@SettingsButton
                    busy = true
                    status = "Connexion…" to true
                    scope.launch {
                        // Contexte de test jetable (identifiant à part) : rien n'est enregistré.
                        val probe = s.copy(id = "test-" + FilesStore.newId())
                        val err = withContext(Dispatchers.IO) { SmbClient.test(probe).also { SmbClient.forget(probe.id) } }
                        busy = false
                        status = if (err == null) "Connexion réussie." to true else err to false
                    }
                })
                SettingsButton("Enregistrer", {
                    val s = build() ?: return@SettingsButton
                    FilesStore.saveShare(ctx, s)
                    onSaved(s)
                }, primary = true)
                if (!isNew) SettingsButton("Supprimer", { confirmDelete = true }, danger = true)
                SettingsButton("Annuler", onDismiss)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Supprimer ce partage ?", "Il disparaîtra de l'onglet Fichiers (rien n'est effacé sur le PC ou le NAS).",
            "Supprimer",
            onConfirm = { confirmDelete = false; onDelete(initial.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---------------------------------------------------------------- Petits éléments

@Composable
private fun FilesHeader(title: String, subtitle: String) {
    val isTv = LocalIsTv.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Box(
            Modifier.size(if (isTv) 44.dp else 40.dp).background(Palette.AccentBrush, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { FluxIcon(FilesIcons.Folder, size = 24.dp) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = if (isTv) 26.sp else 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 13.sp, color = Palette.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Faint,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Notice(text: String, color: Color = Palette.Dim) {
    Text(text, fontSize = 13.sp, color = color, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
}

/**
 * Ligne de l'onglet (source, dossier, vidéo) : télécommande (focus blanc, texte noir) et tactile ([FocusSurface]).
 * [value] à droite (taille), [detail] sous le titre.
 */
@Composable
private fun FileRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    accent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val isTv = LocalIsTv.current
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.widthIn(max = 1100.dp).fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        containerColor = if (accent) Palette.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
    ) {
        val fg = if (focused) Color.Black else Color.White
        val dim = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.Dim
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (isTv) 52.dp else 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxIcon(icon, size = 22.dp, tint = if (focused) Color.Black else if (accent) Palette.Accent else Palette.Dim)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(value, fontSize = 13.sp, color = dim, maxLines = 1)
            }
        }
    }
}
