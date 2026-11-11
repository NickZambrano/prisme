package com.fluxfr.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.fluxfr.tv.ui.settings.ColorDot
import com.fluxfr.tv.ui.settings.PinSetup
import com.fluxfr.tv.ui.settings.SettingsButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fluxfr.tv.data.Catalog
import com.fluxfr.tv.data.DriveSync
import com.fluxfr.tv.data.GoogleAuth
import com.fluxfr.tv.data.GoogleOAuthParse
import com.fluxfr.tv.data.PhoneSetupServer
import com.fluxfr.tv.data.PinHash
import com.fluxfr.tv.data.Profile
import com.fluxfr.tv.data.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------ Avatars

/** Couleurs d'avatar proposées (index stocké dans le profil). */
val ProfileColors: List<List<Color>> = listOf(
    listOf(Palette.Accent, Palette.Accent2),
    listOf(Color(0xFFFFB347), Color(0xFFFF5E9C)),
    listOf(Color(0xFF3DDC97), Color(0xFF1E9E8A)),
    listOf(Color(0xFFB06AF5), Color(0xFF6A4CF5)),
    listOf(Color(0xFFFF6B6B), Color(0xFFD9354F)),
    listOf(Color(0xFF4FD1E8), Color(0xFF2F7FD9)),
)

fun profileBrush(color: Int): Brush = Brush.linearGradient(ProfileColors[color.mod(ProfileColors.size)])

@Composable
fun ProfileAvatar(profile: Profile, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(profileBrush(profile.color)), contentAlignment = Alignment.Center) {
        Text(
            if (profile.kids) "🙂" else profile.name.trim().take(1).uppercase().ifEmpty { "P" },
            fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
    }
}

/** Fond assombri qui garde le focus, avec une carte centrée. Retour : géré par l'appelant. */
@Composable
fun ModalPanel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
            // Tactile : les appuis sur le fond ne traversent pas jusqu'à l'écran dessous.
            .pointerInput(Unit) { detectTapGestures { } }
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Largeur fixe sur TV ; sur téléphone, réduite à l'écran (marges de 16 dp).
            Modifier.padding(16.dp).widthIn(max = width).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.CardHigh, Palette.Card)), RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

// ------------------------------------------------------------------------------------ « Qui regarde ? »

/**
 * Choix du profil : grands avatars. Quitter un profil Enfants protégé par un code pour un profil
 * adulte demande ce code. [onChosen] reçoit le profil retenu (déjà activé).
 */
@Composable
fun ProfilePicker(onChosen: (Profile) -> Unit, subtitle: String? = null) {
    val ctx = LocalContext.current
    val profiles by Profiles.list.collectAsState()
    val active by Profiles.active.collectAsState()
    var askPinFor by remember { mutableStateOf<Profile?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(askPinFor) { if (askPinFor == null) { delay(30); runCatching { firstFocus.requestFocus() } } }
    BackHandler(enabled = askPinFor != null) { askPinFor = null; errorTick = 0 }

    fun choose(target: Profile) {
        if (Profiles.needsParentalPin(active, target, profiles)) {
            askPinFor = target
        } else {
            Profiles.set(ctx, target.id)
            onChosen(target)
        }
    }

    val pinTarget = askPinFor
    // Conteneur propre : appelé dans un Box (fenêtre tactile), les éléments ne doivent pas se superposer.
    Column(
        if (LocalIsTv.current) Modifier else Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    if (pinTarget == null) {
        Text("Qui regarde ?", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(26.dp))
        val choices: @Composable () -> Unit = {
            profiles.forEach { p ->
                ProfileChoice(
                    p, selected = p.id == active.id, onClick = { choose(p) },
                    modifier = if (p.id == active.id) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        if (LocalIsTv.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { choices() }
        } else {
            // Téléphone : les cartes passent à la ligne au lieu d'être écrasées (3 à 5 profils).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) { choices() }
        }
    } else {
        PinPad(
            title = "Code parental",
            message = if (errorTick > 0) "Code incorrect" else "Saisis le code pour passer au profil ${pinTarget.name}",
            isError = errorTick > 0,
            errorTick = errorTick,
            onSubmit = { pin ->
                if (Profiles.checkParentalPin(pin, profiles)) {
                    Profiles.set(ctx, pinTarget.id)
                    askPinFor = null
                    onChosen(pinTarget)
                } else errorTick++
            },
        )
    }
    }
}

@Composable
private fun ProfileChoice(profile: Profile, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val google = remember(profile.id) { GoogleAuth.isLinked(ctx, profile.id) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(width = 140.dp, height = 156.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(18.dp),
        selected = selected,
        focusedContainerColor = Color.White,
        focusedContentColor = Color.Black,
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar(profile, 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                profile.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = if (focused) Color.Black else Color.White,
            )
            Text(
                when {
                    selected -> "Profil actuel"
                    profile.kids -> "Enfants"
                    google -> "Google"
                    else -> " "
                },
                fontSize = 11.sp, color = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.Dim,
            )
        }
    }
}

/** Écran plein « Qui regarde ? » au démarrage de l'appli. */
@Composable
fun WhoIsWatchingScreen(onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.BgBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo()
            Spacer(Modifier.height(36.dp))
            ProfilePicker(onChosen = { onDone() })
        }
    }
}

// ------------------------------------------------------------------------------------ Édition d'un profil

/** Création (ou modification complète) d'un profil : nom, couleur, Enfants, code. */
@Composable
internal fun ProfileEditor(profile: Profile?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val profiles by Profiles.list.collectAsState()
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var color by remember { mutableIntStateOf(profile?.color ?: (profiles.size % ProfileColors.size)) }
    var kids by remember { mutableStateOf(profile?.kids ?: false) }
    // Empreinte du code (jamais le code en clair), vide si aucun code.
    var pin by remember { mutableStateOf(profile?.pinHash ?: "") }
    var editingPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Il doit rester au moins un profil adulte (sinon les Réglages deviendraient inaccessibles).
    val lastAdult = profile != null && !profile.kids && profiles.count { !it.kids } <= 1
    val first = remember { FocusRequester() }
    LaunchedEffect(editingPin) { if (!editingPin) { delay(30); runCatching { first.requestFocus() } } }
    BackHandler(enabled = editingPin) { editingPin = false }

    ModalPanel(560.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                if (profile == null) "Nouveau profil" else "Modifier le profil",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            if (!editingPin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(Profile("x", name.ifBlank { "?" }, color, kids), 56.dp)
                    Spacer(Modifier.width(16.dp))
                    TvTextField(name, { name = it.take(20) }, "Nom du profil", Modifier.weight(1f).focusRequester(first))
                }
                Text("Couleur", fontSize = 13.sp, color = Palette.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileColors.indices.forEach { i -> ColorDot(i, selected = i == color, onClick = { color = i }) }
                }
                ToggleRow(
                    if (lastAdult) "Profil Enfants (impossible : il faut garder un profil adulte)"
                    else "Profil Enfants : catalogue jeunesse uniquement, sans TNT ni Réglages",
                    kids, { if (!lastAdult) kids = !kids },
                )
                if (kids) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Code pour quitter ce profil : " + if (pin.isNotEmpty()) "défini" else "aucun",
                            fontSize = 13.sp, color = Palette.Dim, modifier = Modifier.weight(1f),
                        )
                        SettingsButton(if (pin.isNotEmpty()) "Changer le code" else "Définir un code", { editingPin = true })
                        if (pin.isNotEmpty()) SettingsButton("Supprimer le code", { pin = "" })
                    }
                }
                error?.let { Text(it, color = Palette.Error, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsButton("Enregistrer", {
                        val n = name.trim()
                        if (n.isEmpty()) {
                            error = "Donne un nom au profil"
                        } else {
                            if (profile == null) {
                                val created = Profiles.add(ctx, n, color, kids)
                                if (created != null && pin.isNotEmpty()) Profiles.update(ctx, created.copy(pinHash = pin))
                            } else {
                                Profiles.update(ctx, profile.copy(name = n, color = color, kids = kids, pinHash = if (kids) pin else ""))
                            }
                            onClose()
                        }
                    }, primary = true, icon = FluxIcons.Check)
                    SettingsButton("Annuler", onClose)
                }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PinSetup(onDone = { entered -> pin = PinHash.hash(entered); editingPin = false })
                    Spacer(Modifier.height(12.dp))
                    SettingsButton("Annuler", { editingPin = false })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Connexion d'un compte

/** État d'une connexion par code (OAuth « device ») : Google. */
private sealed interface ConnectState {
    data object Loading : ConnectState
    data class Code(val userCode: String, val url: String) : ConnectState
    data class Done(val user: String?) : ConnectState
    data class Error(val message: String, val denied: Boolean = false) : ConnectState
}

// ------------------------------------------------------------------------------------ Google (synchro Drive)

/** « il y a 2 min », « il y a 3 h »… */
internal fun agoText(t: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "à l'instant"
        s < 3600 -> "il y a ${s / 60} min"
        s < 86_400 -> "il y a ${s / 3600} h"
        else -> "il y a ${s / 86_400} j"
    }
}

/**
 * Compte Google d'un profil : connexion par QR code + code (OAuth « device »), puis état de la synchro
 * du suivi (reprises, épisodes vus, « Ma liste ») via un fichier dans le Drive de l'utilisateur.
 */
@Composable
internal fun GoogleSyncPanel(profile: Profile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(GoogleAuth.isLinked(ctx, profile.id)) }
    var state by remember { mutableStateOf<ConnectState>(ConnectState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var disconnecting by remember { mutableStateOf(false) }
    val statusMap by DriveSync.status.collectAsState()
    val lastMap by DriveSync.lastSync.collectAsState()
    val busy by DriveSync.busy.collectAsState()
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(linked) { delay(50); runCatching { closeFocus.requestFocus() } }
    // « synchronisé il y a 2 min » : rafraîchi toutes les 30 s.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    if (!linked) LaunchedEffect(attempt) {
        state = ConnectState.Loading
        if (!GoogleAuth.isConfigured()) {
            state = ConnectState.Error("Connexion Google indisponible dans cette version de l'appli.")
            return@LaunchedEffect
        }
        val dc = try {
            withContext(Dispatchers.IO) { GoogleAuth.deviceCode() }
        } catch (e: GoogleAuth.AuthError) {
            state = ConnectState.Error("Google a refusé la demande (HTTP ${e.code}).")
            return@LaunchedEffect
        } catch (e: Exception) {
            state = ConnectState.Error("Impossible de joindre Google. Vérifie la connexion Internet.")
            return@LaunchedEffect
        }
        state = ConnectState.Code(dc.userCode, dc.verificationUrl)
        var interval = dc.intervalSec.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + dc.expiresInSec.coerceAtMost(1800) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                runCatching { GoogleAuth.poll(dc.deviceCode) }.getOrDefault(GoogleOAuthParse.Poll.Pending)
            }
            when (r) {
                is GoogleOAuthParse.Poll.Authorized -> {
                    if (!GoogleAuth.hasDriveScope(r.tokens)) {
                        state = ConnectState.Error(
                            "L'accès à Google Drive n'a pas été autorisé : recommence et coche la case Google Drive."
                        )
                        return@LaunchedEffect
                    }
                    if (!GoogleAuth.save(ctx, profile.id, r.tokens)) {
                        state = ConnectState.Error("Google n'a pas fourni d'accès durable. Réessaie.")
                        return@LaunchedEffect
                    }
                    DriveSync.forget(ctx, profile.id)
                    linked = true
                    DriveSync.syncNow(ctx, profile.id)
                    return@LaunchedEffect
                }
                GoogleOAuthParse.Poll.Pending -> Unit
                GoogleOAuthParse.Poll.SlowDown -> interval += 5
                GoogleOAuthParse.Poll.Denied -> { state = ConnectState.Error("Autorisation refusée sur Google."); return@LaunchedEffect }
                GoogleOAuthParse.Poll.Expired -> { state = ConnectState.Error("Le code a expiré."); return@LaunchedEffect }
                is GoogleOAuthParse.Poll.Failed -> { state = ConnectState.Error(r.message); return@LaunchedEffect }
            }
        }
        state = ConnectState.Error("Le code a expiré.")
    }

    ModalPanel(680.dp) {
        Text(
            if (linked) "Synchronisation Google de « ${profile.name} »" else "Se connecter avec Google",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Reprises, épisodes vus et « Ma liste » suivent ce profil sur " +
                (if (LocalIsTv.current) "toutes tes TV" else "tous tes appareils") + " reliés au même compte " +
                "(un fichier « Prisme » dans ton Google Drive).",
            fontSize = 13.sp, color = Palette.Dim, textAlign = TextAlign.Center, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(16.dp))
        if (linked) {
            val email = remember(linked) { GoogleAuth.email(ctx, profile.id) }
            val last = lastMap[profile.id] ?: remember(linked, busy) { DriveSync.lastSyncFor(ctx, profile.id) }
            val status = statusMap[profile.id] ?: remember(linked) { DriveSync.statusFor(ctx, profile.id) }
            val syncing = profile.id in busy
            Row(verticalAlignment = Alignment.CenterVertically) {
                FluxIcon(FluxIcons.Check, size = 20.dp, tint = Palette.Success)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Google : " + (email ?: "compte connecté") + "  —  " + when {
                        syncing -> "synchronisation…"
                        last > 0 -> "synchronisé " + agoText(last, maxOf(now, last))
                        else -> "pas encore synchronisé"
                    },
                    fontSize = 15.sp, color = Color.White,
                )
            }
            if (status != null && status != "Synchronisé" && !syncing) {
                Spacer(Modifier.height(6.dp))
                Text(status, fontSize = 12.sp, color = Palette.Faint)
            }
        } else when (val s = state) {
            ConnectState.Loading -> Text("Demande d'un code à Google…", fontSize = 14.sp, color = Palette.Dim)
            is ConnectState.Code -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    QrCode(s.url, Modifier.size(170.dp))
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(
                        "Sur ton téléphone : scanne le QR code, choisis ton compte Google et saisis ce code",
                        fontSize = 14.sp, color = Palette.Dim, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(s.userCode, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 4.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ou ouvre " + s.url.removePrefix("https://").removePrefix("http://"),
                        fontSize = 13.sp, color = Palette.Accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("En attente de l'autorisation…", fontSize = 12.sp, color = Palette.Faint)
                }
            }
            is ConnectState.Done -> Unit
            is ConnectState.Error -> Text(s.message, fontSize = 14.sp, color = Palette.Error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (linked) {
                SettingsButton(if (profile.id in busy) "Synchronisation…" else "Synchroniser maintenant", {
                    if (profile.id !in busy) DriveSync.syncNow(ctx, profile.id)
                }, primary = true, icon = FluxIcons.Refresh)
                // Déconnexion : second appui pour confirmer (dans les 4 s).
                var armed by remember { mutableStateOf(false) }
                LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
                SettingsButton(
                    when {
                        disconnecting -> "Déconnexion…"
                        armed -> "Confirmer la déconnexion"
                        else -> "Déconnecter"
                    },
                    {
                        if (!armed) armed = true
                        else if (!disconnecting) {
                            armed = false
                            disconnecting = true
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { DriveSync.disconnect(ctx, profile.id) } }
                                disconnecting = false
                                linked = false
                                attempt++
                            }
                        }
                    },
                    danger = true,
                )
            } else if (state is ConnectState.Error) {
                SettingsButton("Réessayer", { attempt++ }, primary = true, icon = FluxIcons.Refresh)
            }
            SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
        }
    }
}

// ------------------------------------------------------------------------------------ Depuis le téléphone

/** Page web locale : nom d'un nouveau profil, saisi depuis le téléphone. */
@Composable
internal fun PhoneExtrasPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val received = remember { MutableStateFlow<Map<String, String>?>(null) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { closeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        val srv = PhoneSetupServer(PhoneSetupServer.Page.EXTRAS) { received.value = it }
        scope.launch(Dispatchers.IO) { url = srv.start(); started = true }
        onDispose { srv.stop() }
    }
    LaunchedEffect(Unit) {
        received.collect { form ->
            if (form == null) return@collect
            val msgs = ArrayList<String>()
            val name = form["profileName"].orEmpty().trim()
            if (name.isNotEmpty()) {
                msgs += if (Profiles.add(ctx, name, Profiles.list.value.size % ProfileColors.size, kids = false) != null)
                    "Profil « $name » créé" else "Nombre maximal de profils atteint"
            }
            info = msgs.joinToString(" · ").ifEmpty { "Rien à enregistrer" }
        }
    }

    ModalPanel(560.dp) {
        FluxIcon(FluxIcons.Phone, size = 26.dp, tint = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text("Depuis ton téléphone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Nom d'un nouveau profil", fontSize = 13.sp, color = Palette.Dim)
        Spacer(Modifier.height(16.dp))
        val u = url
        when {
            u != null -> {
                Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    QrCode(u, Modifier.size(150.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Scanne, ou ouvre :", fontSize = 13.sp, color = Palette.Dim)
                Text(
                    u.removePrefix("http://"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Accent,
                    textAlign = TextAlign.Center,
                )
                Text("Même Wi-Fi que la TV", fontSize = 12.sp, color = Palette.Dim)
            }
            started -> Text("TV non connectée au réseau local.", fontSize = 13.sp, color = Palette.Error)
            else -> Text("Démarrage…", fontSize = 13.sp, color = Palette.Dim)
        }
        info?.let {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FluxIcon(FluxIcons.Check, size = 16.dp, tint = Palette.Success)
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 14.sp, color = Color.White)
            }
        }
        Spacer(Modifier.height(18.dp))
        SettingsButton("Fermer", onClose, Modifier.focusRequester(closeFocus))
    }
}
