package com.fluxfr.tv.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.BuildConfig
import com.fluxfr.tv.data.Http
import com.fluxfr.tv.data.PhoneSetupServer
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.XtreamClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(settings: Settings, onDone: () -> Unit) {
    // Sélection initiale sur « Se connecter » : identifiants déjà préremplis, un appui sur OK suffit.
    val connectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { connectFocus.requestFocus() } }
    var mode by remember { mutableStateOf(settings.mode) }
    var server by remember { mutableStateOf(settings.server.ifBlank { BuildConfig.DEFAULT_SERVER }) }
    var user by remember { mutableStateOf(settings.user.ifBlank { BuildConfig.DEFAULT_USER }) }
    var pass by remember { mutableStateOf(settings.pass.ifBlank { BuildConfig.DEFAULT_PASS }) }
    var m3u by remember { mutableStateOf(settings.m3uUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            Http.userAgent = settings.userAgent
            var workingServer = server.trim()
            val err = withContext(Dispatchers.IO) {
                try {
                    if (mode == Settings.MODE_XTREAM) {
                        if (server.isBlank() || user.isBlank()) "Renseigne le serveur et l'utilisateur"
                        else XtreamClient(server, user.trim(), XtreamClient.cleanPass(pass)).let { c ->
                            c.authenticate().also { workingServer = c.base }
                        }
                    } else {
                        if (m3u.isBlank()) "Renseigne l'URL de la playlist"
                        else { Http.open(m3u.trim()).disconnect(); null }
                    }
                } catch (e: Exception) {
                    XtreamClient.loginError(e)
                }
            }
            busy = false
            if (err != null) {
                error = err
            } else {
                settings.mode = mode
                settings.server = workingServer; settings.user = user.trim(); settings.pass = XtreamClient.cleanPass(pass)
                settings.m3uUrl = m3u.trim()
                onDone()
            }
        }
    }

    // Saisie depuis le téléphone : mini serveur web local tant que cet écran est affiché.
    // Canal d'événements (et non un StateFlow) : renvoyer le MÊME formulaire (après une erreur) est retraité.
    val received = remember { kotlinx.coroutines.channels.Channel<Map<String, String>>(kotlinx.coroutines.channels.Channel.BUFFERED) }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val srv = PhoneSetupServer { received.trySend(it) }
        scope.launch(Dispatchers.IO) { phoneUrl = srv.start() }
        onDispose { srv.stop() }
    }
    LaunchedEffect(Unit) {
        for (form in received) {
            mode = if (form["mode"] == Settings.MODE_M3U) Settings.MODE_M3U else Settings.MODE_XTREAM
            form["server"]?.let { server = it.trim() }
            form["user"]?.let { user = it.trim() }
            form["pass"]?.let { pass = it }
            form["m3u"]?.let { m3u = it.trim() }
            connect()
        }
    }

    // ---- Téléphone / tablette : formulaire classique au clavier, qui défile ; saisie depuis un autre
    // appareil (QR / adresse) proposée en dessous.
    if (!LocalIsTv.current) {
        Column(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF07080C), Color(0xFF121A3A), Color(0xFF1E1236), Color(0xFF07080C)))
            ).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                BrandLogo()
                Spacer(Modifier.height(18.dp))
                Text("Bienvenue", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Connecte ton abonnement IPTV : seul le contenu français HD / 4K sera affiché.",
                    fontSize = 13.sp, color = Palette.Dim, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Xtream Codes", mode == Settings.MODE_XTREAM, onClick = { mode = Settings.MODE_XTREAM })
                    Pill("Playlist M3U", mode == Settings.MODE_M3U, onClick = { mode = Settings.MODE_M3U })
                }
                Spacer(Modifier.height(16.dp))
                if (mode == Settings.MODE_XTREAM) {
                    TvTextField(server, { server = it }, "Serveur", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Uri, placeholder = "http://monserveur.com:8080")
                    Spacer(Modifier.height(12.dp))
                    TvTextField(user, { user = it }, "Utilisateur", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TvTextField(
                        pass, { pass = it }, "Mot de passe", Modifier.fillMaxWidth(), password = true, imeAction = ImeAction.Done,
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { connect() }),
                    )
                } else {
                    TvTextField(
                        m3u, { m3u = it }, "URL de la playlist M3U", Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { connect() }),
                    )
                }
                Spacer(Modifier.height(20.dp))
                ActionButton(
                    if (busy) "Connexion en cours…" else "Se connecter", { connect() }, Modifier.fillMaxWidth(), primary = true,
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.Error, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                val url = phoneUrl
                if (url != null) {
                    Spacer(Modifier.height(28.dp))
                    Column(
                        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).padding(16.dp),
                    ) {
                        Text("Depuis un autre appareil", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Même Wi-Fi : ouvre cette adresse pour saisir les identifiants.", fontSize = 12.sp, color = Palette.Dim)
                        Spacer(Modifier.height(6.dp))
                        Text(url.removePrefix("http://"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                    }
                }
            }
        }
        return
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF07080C), Color(0xFF121A3A), Color(0xFF1E1236), Color(0xFF07080C)))
        )
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- Formulaire
            Column(Modifier.weight(1f)) {
                BrandLogo()
                Spacer(Modifier.height(18.dp))
                Text("Bienvenue", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Connecte ton abonnement IPTV : seul le contenu français HD / 4K sera affiché.", fontSize = 13.sp, color = Palette.Dim)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Xtream Codes", mode == Settings.MODE_XTREAM, onClick = { mode = Settings.MODE_XTREAM })
                    Pill("Playlist M3U", mode == Settings.MODE_M3U, onClick = { mode = Settings.MODE_M3U })
                }
                Spacer(Modifier.height(16.dp))
                if (mode == Settings.MODE_XTREAM) {
                    TvTextField(server, { server = it }, "Serveur", keyboardType = KeyboardType.Uri, placeholder = "http://monserveur.com:8080")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvTextField(user, { user = it }, "Utilisateur", Modifier.weight(1f))
                        TvTextField(pass, { pass = it }, "Mot de passe", Modifier.weight(1f), password = true, imeAction = ImeAction.Done)
                    }
                } else {
                    TvTextField(m3u, { m3u = it }, "URL de la playlist M3U", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                }
                Spacer(Modifier.height(20.dp))
                ActionButton(
                    if (busy) "Connexion en cours…" else "Se connecter",
                    { connect() }, Modifier.fillMaxWidth().focusRequester(connectFocus), primary = true,
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Palette.Error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.width(40.dp))

            // ---- Depuis le téléphone
            Column(
                Modifier.width(280.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FluxIcon(FluxIcons.Phone, size = 26.dp, tint = Palette.Accent)
                Spacer(Modifier.height(8.dp))
                Text("Depuis ton téléphone", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Pas besoin de la télécommande", fontSize = 13.sp, color = Palette.Dim)
                Spacer(Modifier.height(16.dp))
                val url = phoneUrl
                if (url != null) {
                    Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        QrCode(url, Modifier.size(150.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Scanne, ou ouvre :", fontSize = 13.sp, color = Palette.Dim)
                    Text(url.removePrefix("http://"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("Même Wi-Fi que la TV", fontSize = 12.sp, color = Palette.Dim)
                } else {
                    Text(
                        "TV non connectée au réseau local : utilise le formulaire.",
                        fontSize = 13.sp, color = Palette.Dim,
                    )
                }
            }
        }
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() }
        ?: return
    Canvas(modifier) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
