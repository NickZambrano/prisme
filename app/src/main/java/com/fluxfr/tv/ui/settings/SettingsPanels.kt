package com.fluxfr.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Household
import com.fluxfr.tv.data.Http
import com.fluxfr.tv.data.NetflixImport
import com.fluxfr.tv.data.PrimeExport
import com.fluxfr.tv.data.PhoneSetupServer
import com.fluxfr.tv.data.Profile
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.UpdateState
import com.fluxfr.tv.data.Updater
import com.fluxfr.tv.data.XtreamClient
import com.fluxfr.tv.ui.FluxIcon
import com.fluxfr.tv.ui.FluxIcons
import com.fluxfr.tv.ui.ModalPanel
import com.fluxfr.tv.ui.Palette
import com.fluxfr.tv.ui.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Texte et couleur de l'avancement d'une mise à jour (null au repos). */
internal fun updateStateText(state: UpdateState): Pair<String, Color>? = when (state) {
    UpdateState.Idle -> null
    is UpdateState.Downloading ->
        (if (state.progress >= 0f) "Téléchargement… ${(state.progress * 100).toInt()} %" else "Téléchargement…") to Palette.Accent
    UpdateState.Verifying -> "Vérification de l'APK…" to Palette.Accent
    UpdateState.Installing -> "Installation…" to Palette.Accent
    UpdateState.WaitingUser -> "Confirme l'installation dans la fenêtre d'Android." to Palette.Accent
    UpdateState.Installed -> "Mise à jour installée." to Palette.Success
    is UpdateState.Failed -> state.message to Palette.Error
    UpdateState.NeedsPermission -> "Android doit autoriser Prisme à installer des applications." to Palette.Gold
}

/** QR code + adresse d'une page web locale servie par la TV. */
@Composable
private fun LocalPageQr(url: String?, started: Boolean) {
    when {
        url != null -> {
            Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                QrCode(url, Modifier.size(150.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Scanne, ou ouvre :", fontSize = 13.sp, color = Palette.Dim)
            // L'adresse contient un jeton aléatoire (propre à cette ouverture) : à recopier en entier.
            Text(
                url.removePrefix("http://"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Accent,
                textAlign = TextAlign.Center,
            )
            Text("Même Wi-Fi que la TV", fontSize = 12.sp, color = Palette.Dim)
        }
        started -> Text("TV non connectée au réseau local.", fontSize = 13.sp, color = Palette.Error)
        else -> Text("Démarrage…", fontSize = 13.sp, color = Palette.Dim)
    }
}

/**
 * Association de deux TV (côté hôte) : affiche un code à 6 chiffres valable 2 minutes, à saisir sur
 * l'autre TV (« Saisir le code d'une autre TV »). Le code change après 5 essais faux.
 */
@Composable
internal fun PairHostPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hosting by Household.hosting.collectAsState()
    val pairedAt by Household.hostPairedAt.collectAsState()
    val openedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // L'hôte arrête d'afficher le code dès que l'autre TV l'a utilisé.
    val done = pairedAt >= openedAt
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { closeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        Household.startHosting(ctx)
        onDispose { Household.stopHosting() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    }

    ModalPanel(520.dp) {
        FluxIcon(FluxIcons.Check, size = 26.dp, tint = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        val tv = com.fluxfr.tv.ui.LocalIsTv.current
        Text(
            if (tv) "Associer une autre TV" else "Associer un autre appareil",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
        Text(
            if (tv) "Sur l'autre TV : Réglages → Synchronisation → Saisir le code d'une autre TV"
            else "Sur l'autre appareil : Réglages → Synchronisation → Saisir le code d'un autre appareil",
            fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        val h = hosting
        when {
            done -> Text(
                if (tv) "TV associée : les réglages vont se synchroniser." else "Appareil associé : les réglages vont se synchroniser.",
                fontSize = 15.sp, color = Palette.Success,
            )
            h != null && now < h.expiresAt -> {
                Text(h.code.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                val left = ((h.expiresAt - now) / 1000).coerceAtLeast(0)
                Text("Valable encore ${left / 60}:${(left % 60).toString().padStart(2, '0')}", fontSize = 13.sp, color = Palette.Dim)
            }
            else -> {
                Text("Code expiré.", fontSize = 14.sp, color = Palette.Error)
                Spacer(Modifier.height(8.dp))
                SettingsButton("Nouveau code", { Household.startHosting(ctx) }, primary = true)
            }
        }
        Spacer(Modifier.height(18.dp))
        SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
    }
}

/** Page web locale pour envoyer un APK de Prisme depuis le téléphone. */
@Composable
internal fun PhoneUpdatePanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val upState by Updater.state.collectAsState()
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { closeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        val app = ctx.applicationContext
        val srv = PhoneSetupServer(PhoneSetupServer.Page.UPDATE) { }
        srv.uploadDir = app.cacheDir
        srv.onApk = { file ->
            received = true
            Updater.installApkAsync(app, file)
        }
        scope.launch(Dispatchers.IO) { url = srv.start(); started = true }
        onDispose { srv.stop() }
    }

    ModalPanel(560.dp) {
        FluxIcon(FluxIcons.Phone, size = 26.dp, tint = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text("Mettre à jour depuis le téléphone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Envoie le fichier .apk de Prisme depuis ton téléphone", fontSize = 13.sp, color = Palette.Dim)
        Spacer(Modifier.height(16.dp))
        LocalPageQr(url, started)
        if (received || upState != UpdateState.Idle) {
            Spacer(Modifier.height(12.dp))
            if (received && upState == UpdateState.Idle) Text("APK reçu", fontSize = 13.sp, color = Palette.Success)
            updateStateText(upState)?.let { (t, c) -> Text(t, fontSize = 13.sp, color = c, textAlign = TextAlign.Center) }
            if (upState == UpdateState.NeedsPermission) {
                Spacer(Modifier.height(8.dp))
                SettingsButton("Ouvrir les paramètres", { Updater.openInstallPermissionSettings(ctx) }, primary = true)
            }
        }
        Spacer(Modifier.height(18.dp))
        SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
    }
}

/**
 * Nouveau compte IPTV saisi depuis le téléphone (même page que l'écran d'accueil) : vérifié auprès
 * du fournisseur, enregistré, puis [onSaved] recharge le catalogue.
 */
@Composable
internal fun PhoneAccountPanel(settings: Settings, onSaved: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    val received = remember { MutableStateFlow<Map<String, String>?>(null) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { closeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        val srv = PhoneSetupServer(PhoneSetupServer.Page.ACCOUNT) { received.value = it }
        scope.launch(Dispatchers.IO) { url = srv.start(); started = true }
        onDispose { srv.stop() }
    }
    LaunchedEffect(Unit) {
        received.collect { form ->
            if (form == null) return@collect
            status = "Vérification du compte…" to Palette.Dim
            val mode = if (form["mode"] == Settings.MODE_M3U) Settings.MODE_M3U else Settings.MODE_XTREAM
            val server = (form["server"] ?: settings.server).trim()
            val user = (form["user"] ?: settings.user).trim()
            val pass = XtreamClient.cleanPass(form["pass"] ?: settings.pass)
            val m3u = (form["m3u"] ?: settings.m3uUrl).trim()
            var workingServer = server
            val err = withContext(Dispatchers.IO) {
                Http.userAgent = settings.userAgent
                try {
                    if (mode == Settings.MODE_XTREAM) {
                        if (server.isBlank() || user.isBlank()) "Renseigne le serveur et l'utilisateur"
                        else XtreamClient(server, user, pass).let { c -> c.authenticate().also { workingServer = c.base } }
                    } else {
                        if (m3u.isBlank()) "Renseigne l'URL de la playlist"
                        else { Http.open(m3u).disconnect(); null }
                    }
                } catch (e: Exception) {
                    XtreamClient.loginError(e)
                }
            }
            if (err != null) {
                status = err to Palette.Error
            } else {
                settings.mode = mode
                settings.server = workingServer; settings.user = user; settings.pass = pass
                settings.m3uUrl = m3u
                onSaved()
                status = "Compte enregistré, rechargement du catalogue…" to Palette.Success
            }
        }
    }

    ModalPanel(560.dp) {
        FluxIcon(FluxIcons.Phone, size = 26.dp, tint = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text("Configurer depuis le téléphone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Saisis le serveur et les identifiants sur ton téléphone", fontSize = 13.sp, color = Palette.Dim)
        Spacer(Modifier.height(16.dp))
        LocalPageQr(url, started)
        status?.let { (t, c) ->
            Spacer(Modifier.height(12.dp))
            Text(t, fontSize = 13.sp, color = c, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
    }
}

/**
 * Import d'un historique Netflix ou Prime Video d'un profil : page web locale (QR code + adresse) pour envoyer le CSV
 * depuis un téléphone ou un PC, puis avancement et résumé de l'import sur la TV.
 */
@Composable
internal fun NetflixImportPanel(profile: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by NetflixImport.state.collectAsState()
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { closeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        val app = ctx.applicationContext
        val srv = PhoneSetupServer(PhoneSetupServer.Page.NETFLIX) { }
        srv.netflixDefault = profile.id
        srv.netflixProfiles = { Profiles.list.value.map { it.id to it.name } }
        srv.onNetflixCsv = { pid, name, text -> NetflixImport.start(app, pid, text, name) }
        srv.netflixStatus = { NetflixImport.statusJson() }
        srv.netflixImports = { NetflixImport.importsJson(app) }
        srv.onNetflixUndo = { pid ->
            if (NetflixImport.undo(app, pid) < 0) "Un import est en cours sur la TV : réessaie quand il sera terminé." else null
        }
        srv.primeScript = {
            runCatching { app.assets.open(PrimeExport.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        }
        scope.launch(Dispatchers.IO) { url = srv.start(); started = true }
        onDispose { srv.stop() }
    }

    ModalPanel(600.dp) {
        FluxIcon(FluxIcons.Phone, size = 26.dp, tint = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text("Importer un historique (Netflix ou Prime Video)", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Netflix : netflix.com → Compte → Profil → Activité de visionnage → Télécharger tout.\n" +
                "Prime Video : sur ordinateur, installe le bouton « Exporter Prime Video » " +
                (url?.let { "(${it.removePrefix("http://")}prime-export) " } ?: "") +
                "puis clique dessus sur primevideo.com → Paramètres → Historique de visionnage.\n" +
                "Envoie ensuite le fichier .csv depuis ton téléphone ou ton ordinateur.",
            fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        LocalPageQr(url, started)
        val summary = st.summary
        when {
            st.running -> {
                Spacer(Modifier.height(12.dp))
                val count = if (st.total > 0) "  ${st.done} / ${st.total}" else ""
                Text(st.phase + count, fontSize = 14.sp, color = Palette.Accent, textAlign = TextAlign.Center)
            }
            st.error != null -> {
                Spacer(Modifier.height(12.dp))
                Text(st.error!!, fontSize = 14.sp, color = Palette.Error, textAlign = TextAlign.Center)
            }
            summary != null -> {
                Spacer(Modifier.height(12.dp))
                Text(NetflixImport.doneLine(summary), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Success)
                NetflixImport.summaryLines(summary).forEach { Text(it, fontSize = 13.sp, color = Color.White) }
                if (summary.notFoundCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Titres non trouvés (${summary.notFoundCount}) : " + summary.notFound.joinToString(", ") +
                            if (summary.notFoundCount > summary.notFound.size) "…" else "",
                        fontSize = 12.sp, color = Palette.Dim, textAlign = TextAlign.Center, maxLines = 4,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
    }
}
