package com.fluxfr.tv.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.fluxfr.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Mise à jour automatique de la version PUBLIQUE depuis Internet (fichier de version de la dernière publication
 * GitHub du dépôt public, envoyée par tools/publish-update.mjs). La version personnelle n'a pas d'adresse ([BuildConfig.UPDATE_MANIFEST_URL] vide) :
 * elle continue de se mettre à jour entre les TV de la maison.
 *
 * Vérifie au démarrage puis toutes les 6 h ; télécharge en arrière-plan ; contrôle l'empreinte SHA-256 puis la
 * signature et le type ([Updater.verify]) ; installe seulement quand Prisme n'est plus à l'écran (jamais pendant
 * une lecture). Android 12+ : sans confirmation dès que Prisme a installé lui-même la version précédente ;
 * sinon la confirmation est proposée par le bandeau de l'accueil.
 */
object RemoteUpdate {
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val sha256: String,
        val size: Long,
        val flavour: String,
    )

    /** Lecture du fichier de version ; null s'il est invalide ou incomplet. */
    fun parse(json: String): Manifest? {
        val m = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
        val code = (m["versionCode"] as? Number)?.toInt() ?: return null
        val name = m["versionName"] as? String ?: return null
        val apk = m["apk"] as? String ?: return null
        val sha = (m["sha256"] as? String)?.lowercase() ?: return null
        val size = (m["size"] as? Number)?.toLong() ?: -1L
        val flavour = m["flavour"] as? String ?: return null
        if (code <= 0 || !apk.startsWith("https://") || !Regex("[0-9a-f]{64}").matches(sha)) return null
        return Manifest(code, name, apk, sha, size, flavour)
    }

    /** À installer : même type que nous et version strictement plus récente. */
    fun isNewer(m: Manifest, ownCode: Int, ownFlavour: String): Boolean =
        Flavour.compatible(m.flavour, ownFlavour) && m.versionCode > ownCode

    val enabled: Boolean get() = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()

    private const val INTERVAL_MS = 6 * 3600_000L
    /** Au retour dans l'appli : nouvelle vérification si la précédente date de plus de 30 min. */
    private const val RECHECK_MS = 30 * 60_000L
    @Volatile private var lastCheck = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var visible = 0
    @Volatile private var ready: Pair<File, UpdateInfo>? = null

    /** true si une activité de Prisme est à l'écran (accueil, lecteur…). */
    val inForeground: Boolean get() = visible > 0

    /** À appeler depuis MainActivity.onCreate : lance les vérifications et suit la visibilité de l'appli. */
    fun start(activity: Activity) {
        if (!enabled || started) return
        started = true
        // La 1re vérification est faite par la boucle ci-dessous (après le chargement de l'accueil).
        lastCheck = System.currentTimeMillis()
        val app = activity.application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                // Retour dans Prisme (le processus est souvent resté en mémoire) : nouvelle vérification si la
                // dernière date de plus de 30 min — sinon une version publiée entre-temps n'apparaissait qu'au
                // bout de 6 h ou après un redémarrage complet.
                if (visible == 0 && System.currentTimeMillis() - lastCheck > RECHECK_MS) {
                    scope.launch { runCatching { checkAndPrepare(app) } }
                }
                visible++
            }
            override fun onActivityStopped(a: Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                // Prisme quitte l'écran (TV en veille, autre appli) : moment idéal pour installer.
                if (visible == 0) installIfReady(app)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        scope.launch {
            delay(20_000) // laisse l'accueil se charger d'abord
            while (true) {
                runCatching { checkAndPrepare(app) }
                delay(INTERVAL_MS)
            }
        }
    }

    private fun fetchManifest(): Manifest? {
        val c = Http.open(BuildConfig.UPDATE_MANIFEST_URL)
        return try {
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(text)
        } finally {
            c.disconnect()
        }
    }

    enum class Outcome { UP_TO_DATE, AVAILABLE, FAILED }

    /**
     * Vérifie la version publiée ; si elle est plus récente, l'annonce (bandeau de l'accueil), la télécharge et la
     * contrôle, sans installer. Le téléchargement continue en arrière-plan : le résultat est connu tout de suite.
     */
    suspend fun checkAndPrepare(ctx: Context): Outcome {
        val app = ctx.applicationContext
        lastCheck = System.currentTimeMillis()
        val m = runCatching { fetchManifest() }.getOrNull() ?: return Outcome.FAILED
        if (!isNewer(m, BuildConfig.VERSION_CODE, BuildConfig.FLAVOUR)) return Outcome.UP_TO_DATE
        val info = UpdateInfo(m.versionCode, m.versionName, "Internet", m.apk, m.sha256)
        Updater.offer(info)
        scope.launch { runCatching { prepare(app, info) } }
        return Outcome.AVAILABLE
    }

    @Volatile private var preparing = false

    private fun prepare(app: Context, info: UpdateInfo) {
        if (preparing || Updater.busy() || readyFileFor(info) != null) return
        preparing = true
        try {
            val file = File(app.filesDir, "remote-update.apk")
            if (!(file.exists() && sha256(file) == info.sha256)) {
                file.delete()
                if (!Updater.downloadTo(info, file)) return
            }
            if (Updater.verify(app, file) != null) { file.delete(); return }
            ready = file to info
            if (!inForeground) installIfReady(app)
        } finally {
            preparing = false
        }
    }

    private fun installIfReady(ctx: Context) {
        val (file, _) = ready ?: return
        if (inForeground || Updater.busy() || !file.exists()) return
        if (!Updater.canInstall(ctx)) return
        ready = null
        scope.launch { runCatching { Updater.installApk(ctx.applicationContext, file, quiet = true) } }
    }

    /** APK déjà téléchargé et contrôlé pour [info] (le bandeau « Installer » l'utilise sans re-télécharger). */
    fun readyFileFor(info: UpdateInfo): File? = ready?.takeIf { it.second.versionCode == info.versionCode && it.first.exists() }?.first

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
