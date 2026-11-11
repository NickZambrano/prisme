package com.fluxfr.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Contrôle du direct (pause, retour en arrière, retour au direct) avec UNE seule connexion au fournisseur :
 * le flux MPEG-TS est enregistré une fois ([TimeshiftRecorder]) dans un tampon disque
 * ([TimeshiftBuffer], cacheDir/timeshift) et le lecteur lit ce tampon via « prisme-ts://<id>/<octet>.ts »
 * ([TimeshiftDataSource]). Un saut = relancer la lecture à la position (image clé) correspondant à l'heure
 * visée, grâce à l'index temps → octets tenu pendant l'enregistrement.
 */
object Timeshift {
    const val SCHEME = "prisme-ts"
    /** En dessous : pas de contrôle du direct. */
    const val MIN_FREE_BYTES = 1L shl 30
    const val DEFAULT_CAP_BYTES = 4L shl 30
    const val MAX_DURATION_MS = 90 * 60_000L
    private const val TAG = "FluxTimeshift"

    internal val buffers = ConcurrentHashMap<String, TimeshiftBuffer>()
    private val ids = AtomicLong(System.currentTimeMillis())
    @Volatile private var lastRecorder: TimeshiftRecorder? = null

    /** Dernier enregistreur démarré (peut-être arrêté, sa connexion en cours de fermeture). */
    internal fun lastRecorderOrNull(): TimeshiftRecorder? = lastRecorder

    fun root(ctx: Context) = File(ctx.cacheDir, "timeshift")

    /** Taille maximale du tampon selon l'espace libre ; 0 = pas assez de place (< 1 Go). */
    fun capBytes(freeBytes: Long): Long =
        if (freeBytes < MIN_FREE_BYTES) 0L else minOf(DEFAULT_CAP_BYTES, freeBytes / 4)

    /** Flux enregistrable : HTTP(S), hors HLS / DASH (ExoPlayer gère alors lui-même la fenêtre de direct). */
    fun isEligible(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val path = u.substringBefore('?')
        return !path.endsWith(".m3u8") && !path.endsWith(".m3u") && !path.endsWith(".mpd") && ".m3u8" !in u
    }

    fun uri(id: String, offset: Long): Uri = Uri.parse("$SCHEME://$id/$offset.ts")

    /** (identifiant, position) d'une URI prisme-ts ; null sinon. */
    fun parse(uri: Uri): Pair<String, Long>? {
        if (uri.scheme != SCHEME) return null
        val id = uri.host ?: return null
        val off = uri.lastPathSegment?.removeSuffix(".ts")?.toLongOrNull() ?: return null
        return id to off
    }

    /** Démarre l'enregistrement de [url] ; null si l'espace disque est insuffisant. */
    fun start(ctx: Context, itemId: String, url: String, userAgent: String): TimeshiftSession? {
        val root = root(ctx)
        root.mkdirs()
        val free = runCatching { root.usableSpace }.getOrDefault(0L)
        val cap = capBytes(free)
        if (cap <= 0) {
            Log.w(TAG, "Espace libre insuffisant (${free / (1L shl 20)} Mo) : contrôle du direct désactivé")
            return null
        }
        val id = "s" + ids.incrementAndGet()
        val buffer = TimeshiftBuffer(File(root, id), maxBytes = cap, maxDurationMs = MAX_DURATION_MS)
        buffers[id] = buffer
        return TimeshiftSession(id, itemId, url, userAgent, buffer, newRecorder(url, userAgent, buffer))
    }

    /** Nouvel enregistreur ; il attend que le précédent ait fermé sa connexion (comptes à une connexion). */
    internal fun newRecorder(url: String, userAgent: String, buffer: TimeshiftBuffer): TimeshiftRecorder {
        val recorder = TimeshiftRecorder(url, userAgent, buffer, lastRecorder)
        lastRecorder = recorder
        recorder.start()
        return recorder
    }

    /** Efface les tampons orphelins (appli tuée pendant un enregistrement). */
    fun cleanup(ctx: Context) {
        val root = root(ctx)
        val live = buffers.keys.toHashSet()
        Thread({
            root.listFiles()?.forEach { f -> if (f.name !in live) f.deleteRecursively() }
        }, "TimeshiftCleanup").start()
    }
}

/** Enregistrement en cours d'une chaîne (une source). */
class TimeshiftSession(
    val id: String,
    val itemId: String,
    val url: String,
    private val userAgent: String,
    val buffer: TimeshiftBuffer,
    recorder: TimeshiftRecorder,
) {
    @Volatile var released = false
        private set
    @Volatile var recorder: TimeshiftRecorder = recorder
        private set

    /** Reprend l'enregistrement (après [stopRecording]) dans le même tampon. */
    fun resumeRecording() {
        if (released || !recorder.stopped || buffer.failed) return
        recorder = Timeshift.newRecorder(url, userAgent, buffer)
    }

    /** Fenêtre disponible (heure du plus ancien et du plus récent octet) ; -1 tant que rien n'est reçu. */
    val firstWall: Long get() = buffer.firstWall
    val lastWall: Long get() = buffer.lastWall
    val hasData: Boolean get() = buffer.end > buffer.start && buffer.lastWall > 0

    /** Arrête l'enregistrement (la connexion au fournisseur est fermée) sans effacer le tampon. */
    fun stopRecording() = recorder.stop()

    /** Arrête et efface tout. */
    fun release() {
        if (released) return
        released = true
        recorder.stop()
        Timeshift.buffers.remove(id)
        Thread({ buffer.close(delete = true) }, "TimeshiftDelete").start()
    }
}

/** Lecture du tampon du direct par ExoPlayer (flux progressif MPEG-TS sans fin, sauts par relance). */
@OptIn(UnstableApi::class)
class TimeshiftDataSource : BaseDataSource(false) {
    private var buffer: TimeshiftBuffer? = null
    private var position = 0L
    private var uri: Uri? = null
    private var opened = false
    private val file = TimeshiftBuffer.OpenSegment()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (id, base) = Timeshift.parse(dataSpec.uri) ?: throw IOException("URI de direct invalide")
        val b = Timeshift.buffers[id] ?: throw IOException("Tampon du direct fermé")
        position = base + dataSpec.position
        if (position < b.start) throw BehindWindowException()
        buffer = b
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buffer ?: return C.RESULT_END_OF_INPUT
        val waitStart = System.nanoTime()
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException()
            val avail = b.available(position, 250)
            if (avail < 0) return C.RESULT_END_OF_INPUT
            if (avail == 0L) {
                if (System.nanoTime() - waitStart > NO_DATA_TIMEOUT_NS) throw IOException("Le direct ne répond plus")
                continue
            }
            val n = b.readAt(position, target, offset, minOf(length.toLong(), avail).toInt(), file)
            position += n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        file.close()
        buffer = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val NO_DATA_TIMEOUT_NS = 45_000_000_000L
    }
}

/**
 * Erreurs de lecture du tampon local : pas de nouvelle tentative interne d'ExoPlayer (elle reprendrait au
 * début de l'élément, donc dans le passé) ; le lecteur reprend lui-même à la bonne heure.
 */
@OptIn(UnstableApi::class)
class TimeshiftLoadErrorPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.loadEventInfo.uri.scheme == Timeshift.SCHEME) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/** Aiguille les URI prisme-ts vers le tampon local, tout le reste vers [upstream] (HTTP). */
@OptIn(UnstableApi::class)
class TimeshiftRoutingDataSource(private val upstream: DataSource) : DataSource {
    private val local = TimeshiftDataSource()
    private var active: DataSource? = null
    /** Connexion au fournisseur comptée dans [ProviderConnections] (fermée dans [close]). */
    private var counted = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == Timeshift.SCHEME) local else upstream
        if (ds === upstream) {
            // Abonnement à une connexion : l'enregistreur du direct arrêté doit d'abord avoir raccroché.
            ProviderConnections.beforePlayerOpen()
            if (!counted) { counted = true; ProviderConnections.playerOpened() }
        }
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Source non ouverte")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val a = active
        active = null
        try {
            a?.close()
        } finally {
            if (counted) { counted = false; ProviderConnections.playerClosed() }
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftRoutingDataSource(upstream.createDataSource())
    }
}
