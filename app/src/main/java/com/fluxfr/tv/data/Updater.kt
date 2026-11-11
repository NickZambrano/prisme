package com.fluxfr.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.fluxfr.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Type de build : « personal » (identifiants IPTV du propriétaire intégrés) ou « public » (aucun). Même
 * applicationId, même clé, même versionCode : le type est donc annoncé par /prisme/version et inscrit dans
 * le manifeste (meta-data [META_KEY]) pour qu'une TV n'installe jamais l'autre type.
 */
object Flavour {
    const val PERSONAL = "personal"
    const val PUBLIC = "public"
    const val META_KEY = "prisme.flavour"

    /** Même type, connu des deux côtés (une valeur absente n'est jamais compatible). */
    fun compatible(a: String?, b: String?): Boolean = !a.isNullOrEmpty() && a == b

    /**
     * Mise à jour à proposer parmi [peers] : la plus récente des versions supérieures à la nôtre, du MÊME type
     * (personnelle / publique) — une TV d'un autre type, ou qui ne l'annonce pas, est ignorée.
     */
    fun pickUpdate(peers: List<PeerInfo>, ownVersion: Int, ownFlavour: String): PeerInfo? =
        peers.filter { compatible(it.flavour, ownFlavour) && it.versionCode > ownVersion }.maxByOrNull { it.versionCode }

    /** Message d'erreur si le type d'un APK ([archiveFlavour], lu dans son manifeste) diffère du nôtre. */
    fun archiveError(archiveFlavour: String?, ownFlavour: String): String? =
        if (compatible(archiveFlavour, ownFlavour)) null
        else "Cette version n'est pas du même type que celle installée"
}

/** Version plus récente de Prisme trouvée sur une autre TV de la maison. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val device: String,
    val apkUrl: String,
    /** Empreinte attendue de l'APK (mise à jour par Internet, [RemoteUpdate]) ; null entre TV de la maison. */
    val sha256: String? = null,
)

/** Avancement d'une installation (depuis une autre TV ou depuis le téléphone). */
sealed interface UpdateState {
    data object Idle : UpdateState
    /** [progress] entre 0 et 1, ou négatif si la taille est inconnue. */
    data class Downloading(val progress: Float) : UpdateState
    data object Verifying : UpdateState
    /** Il faut d'abord autoriser Prisme à installer des applis (« sources inconnues »). */
    data object NeedsPermission : UpdateState
    data object Installing : UpdateState
    /** La fenêtre de confirmation d'Android est affichée. */
    data object WaitingUser : UpdateState
    data object Installed : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Mises à jour sans câble ni ADB : une TV qui a une version plus récente la sert aux autres
 * ([PrismeNode], GET /prisme/apk), ou l'APK est envoyé depuis le téléphone ([PhoneSetupServer.Page.UPDATE]).
 * Avant d'installer, on vérifie que c'est bien Prisme, signé avec la même clé.
 */
object Updater {
    const val MAX_APK_BYTES = PhoneSetupServer.MAX_APK_BYTES
    private const val ACTION_INSTALL_STATUS = "com.fluxfr.tv.INSTALL_STATUS"

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** Mise à jour proposée par une autre TV (null si aucune) : à afficher en bannière sur l'accueil. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Résultat de la dernière recherche manuelle (« Prisme est à jour »…). */
    private val _checkMessage = MutableStateFlow<String?>(null)
    val checkMessage: StateFlow<String?> = _checkMessage.asStateFlow()

    /** APK déjà vérifié, en attente de l'autorisation « sources inconnues ». */
    @Volatile private var pendingFile: File? = null
    @Volatile private var receiverRegistered = false

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Résultat d'une recherche de TV : retient la plus récente des versions supérieures à la nôtre (même type). */
    fun onPeers(ctx: Context, peers: List<PeerInfo>) {
        val best = Flavour.pickUpdate(peers, BuildConfig.VERSION_CODE, BuildConfig.FLAVOUR)
        val fromPeer = best?.let { UpdateInfo(it.versionCode, it.versionName, it.device, it.url("/prisme/apk")) }
        // Une offre venue d'Internet ([RemoteUpdate]) n'est pas effacée par une recherche de TV sans résultat.
        val current = _available.value
        _available.value = when {
            fromPeer == null -> current?.takeIf { it.sha256 != null }
            current?.sha256 != null && current.versionCode >= fromPeer.versionCode -> current
            else -> fromPeer
        }
    }

    /** Version plus récente publiée sur Internet ([RemoteUpdate]) : affichée dans le bandeau de l'accueil. */
    fun offer(info: UpdateInfo) {
        val current = _available.value
        if (current == null || info.versionCode >= current.versionCode) _available.value = info
    }

    /** Installation en cours lancée sans l'utilisateur (Prisme en arrière-plan) : pas de fenêtre de confirmation. */
    @Volatile private var quietInstall = false

    /** Télécharge [info] dans [dest] et contrôle son empreinte ; false en cas d'échec (état remis au repos). */
    internal fun downloadTo(info: UpdateInfo, dest: File): Boolean {
        return try {
            _state.value = UpdateState.Downloading(0f)
            download(info.apkUrl, dest)
            val ok = info.sha256 == null || RemoteUpdate.sha256(dest) == info.sha256
            if (!ok) dest.delete()
            ok
        } catch (e: Exception) {
            dest.delete()
            false
        } finally {
            if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Idle
        }
    }

    /**
     * « Rechercher une mise à jour » : sur Internet (version publique, [RemoteUpdate]) puis parmi les autres TV de la
     * maison (~4 s).
     */
    suspend fun check(ctx: Context) {
        if (_checking.value) return
        _checking.value = true
        _checkMessage.value = null
        try {
            // Internet d'abord : une nouvelle version publiée apparaît dans le bandeau et se télécharge.
            val online = if (RemoteUpdate.enabled) {
                runCatching { RemoteUpdate.checkAndPrepare(ctx.applicationContext) }.fold(
                    onSuccess = { it },
                    onFailure = { RemoteUpdate.Outcome.FAILED },
                )
            } else null
            val peers = PrismeNode.discover(ctx.applicationContext)
            onPeers(ctx, peers)
            val same = peers.filter { Flavour.compatible(it.flavour, BuildConfig.FLAVOUR) }
            _checkMessage.value = when {
                _available.value != null -> null
                online == RemoteUpdate.Outcome.UP_TO_DATE -> "Prisme est à jour ($currentVersionName)"
                online == RemoteUpdate.Outcome.FAILED -> "Impossible de vérifier les mises à jour sur Internet"
                peers.isEmpty() -> "Aucune autre TV avec Prisme trouvée sur le réseau"
                same.isEmpty() -> "Aucune autre TV avec le même type de Prisme"
                else -> "Prisme est à jour (" + same.joinToString { it.device + " : " + it.versionName } + ")"
            }
        } finally {
            _checking.value = false
        }
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Ouvre le réglage « Installer des applis inconnues » de Prisme ; false si la TV ne le propose pas. */
    fun openInstallPermissionSettings(ctx: Context): Boolean {
        val intents = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            else null,
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    // Versions « lancer et oublier » : l'opération continue même si l'écran Réglages est quitté.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun checkAsync(ctx: Context) { val c = ctx.applicationContext; scope.launch { runCatching { check(c) } } }

    /** Une seule opération à la fois (double appui sur « Installer » : un seul téléchargement dans update.apk). */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun installAsync(ctx: Context, info: UpdateInfo) {
        if (busy() || !running.compareAndSet(false, true)) return
        val c = ctx.applicationContext
        scope.launch {
            try { runCatching { downloadAndInstall(c, info) } } finally { running.set(false) }
        }
    }

    fun installApkAsync(ctx: Context, file: File) {
        if (busy() || !running.compareAndSet(false, true)) return
        val c = ctx.applicationContext
        scope.launch {
            try { runCatching { installApk(c, file) } } finally { running.set(false) }
        }
    }

    fun resumePendingAsync(ctx: Context) {
        val c = ctx.applicationContext
        scope.launch { runCatching { resumePending(c) } }
    }

    fun busy(): Boolean = when (_state.value) {
        is UpdateState.Downloading, UpdateState.Verifying, UpdateState.Installing -> true
        else -> false
    }

    fun reset() {
        if (!busy()) _state.value = UpdateState.Idle
    }

    /** Télécharge l'APK de l'autre TV puis l'installe. */
    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        if (!canInstall(app)) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        // Déjà téléchargée et contrôlée en arrière-plan : installation directe.
        RemoteUpdate.readyFileFor(info)?.let { installApk(app, it); return }
        val file = File(app.cacheDir, "update.apk")
        try {
            _state.value = UpdateState.Downloading(0f)
            withContext(Dispatchers.IO) { download(apkUrlWithProof(info), file) }
            if (info.sha256 != null && withContext(Dispatchers.IO) { RemoteUpdate.sha256(file) } != info.sha256) {
                throw IOException("empreinte différente")
            }
        } catch (e: Exception) {
            file.delete()
            _state.value = UpdateState.Failed(
                if (info.sha256 != null) "Téléchargement impossible, réessaie plus tard" else "Téléchargement impossible depuis ${info.device}",
            )
            return
        }
        installApk(app, file)
    }

    /**
     * Version personnelle : l'autre TV ne sert son APK qu'avec la preuve des mêmes identifiants intégrés
     * (nonce neuf + HMAC, voir [ApkProof]) ; la version publique n'en a pas besoin.
     */
    private fun apkUrlWithProof(info: UpdateInfo): String {
        if (BuildConfig.FLAVOUR != Flavour.PERSONAL) return info.apkUrl
        val key = ApkProof.key(BuildConfig.DEFAULT_SERVER, BuildConfig.DEFAULT_USER, BuildConfig.DEFAULT_PASS)
        return info.apkUrl + (if ('?' in info.apkUrl) "&" else "?") + ApkProof.query(key, info.versionCode)
    }

    private fun download(url: String, dest: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        // Internet (https) : redirections suivies (jamais vers http : HttpURLConnection ne change pas de protocole).
        // Réseau local : aucune.
        c.instanceFollowRedirects = url.startsWith("https://")
        c.connectTimeout = 8_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (total > MAX_APK_BYTES) throw IOException("trop gros")
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("trop gros")
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = UpdateState.Downloading(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("incomplet")
        } finally {
            c.disconnect()
        }
    }

    /** Vérifie puis installe un APK (téléchargé, ou reçu du téléphone). */
    suspend fun installApk(ctx: Context, file: File, quiet: Boolean = false) {
        val app = ctx.applicationContext
        quietInstall = quiet
        _state.value = UpdateState.Verifying
        val error = withContext(Dispatchers.IO) { verify(app, file) }
        if (error != null) {
            file.delete()
            _state.value = UpdateState.Failed(error)
            return
        }
        if (!canInstall(app)) {
            pendingFile = file
            _state.value = UpdateState.NeedsPermission
            return
        }
        pendingFile = null
        _state.value = UpdateState.Installing
        try {
            withContext(Dispatchers.IO) { commit(app, file) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Installation impossible : " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Après l'autorisation « sources inconnues » : reprend l'APK reçu du téléphone, s'il y en a un. */
    suspend fun resumePending(ctx: Context): Boolean {
        val f = pendingFile?.takeIf { it.exists() } ?: return false
        installApk(ctx, f)
        return true
    }

    // ------------------------------------------------------------------------- Vérification

    /** Null si l'APK est bien Prisme, signé avec la même clé et pas plus ancien ; sinon le message d'erreur. */
    internal fun verify(ctx: Context, file: File): String? {
        if (!file.exists() || file.length() < 1024) return "Fichier APK vide ou incomplet"
        val pm = ctx.packageManager
        val archive = runCatching { archiveInfo(pm, file) }.getOrNull() ?: return "Ce fichier n'est pas un APK valide"
        if (archive.packageName != ctx.packageName) return "Ce n'est pas Prisme (${archive.packageName})"
        val own = runCatching { installedInfo(pm, ctx.packageName) }.getOrNull() ?: return "Signature de Prisme illisible"
        val a = signatures(archive)
        val o = signatures(own)
        if (a.isEmpty() || a != o) return "APK signé avec une autre clé que Prisme installé : installation refusée"
        // Même clé de signature pour les deux types : on vérifie le type déclaré dans le manifeste de l'APK.
        Flavour.archiveError(archive.applicationInfo?.metaData?.getString(Flavour.META_KEY), BuildConfig.FLAVOUR)?.let { return it }
        if (versionCode(archive) < BuildConfig.VERSION_CODE) {
            return "Version ${archive.versionName ?: "?"} plus ancienne que celle installée ($currentVersionName)"
        }
        return null
    }

    private val SIG_FLAGS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = SIG_FLAGS or PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(SIG_FLAGS.toLong()))
        else pm.getPackageInfo(pkg, SIG_FLAGS)

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else info.signatures
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------------- Installation

    private fun commit(ctx: Context, file: File) {
        ensureReceiver(ctx)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            // Android 12+ : mise à jour de Prisme par lui-même sans fenêtre de confirmation, quand le système
            // l'autorise (Prisme a installé la version actuelle). Sinon la confirmation est demandée comme avant.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("prisme.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Installation lancée en arrière-plan qui demande malgré tout une confirmation (1re mise à jour
                    // par Prisme, Android < 12) : rien n'est affiché par-dessus une autre appli ; le bandeau de
                    // l'accueil propose « Installer » au prochain passage.
                    if (quietInstall && !RemoteUpdate.inForeground) {
                        _state.value = UpdateState.Idle
                        return
                    }
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        _state.value = UpdateState.Failed("Confirmation d'installation indisponible")
                        return
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    _state.value = if (runCatching { c.startActivity(confirm) }.isSuccess) UpdateState.WaitingUser
                    else UpdateState.Failed("Impossible d'afficher la confirmation d'installation")
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    _state.value = UpdateState.Installed
                    _available.value = null
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Failed("Installation annulée")
                PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = UpdateState.Failed("Espace de stockage insuffisant")
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    _state.value = UpdateState.Failed("APK incompatible avec la version installée")
                else -> _state.value = UpdateState.Failed(
                    "Installation échouée" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { " : $it" } ?: "")
                )
            }
        }
    }

    @Synchronized
    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            ctx.applicationContext, receiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }
}
