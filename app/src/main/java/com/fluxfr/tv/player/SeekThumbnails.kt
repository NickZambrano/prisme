package com.fluxfr.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Images d'aperçu pendant un déplacement dans la barre (façon Netflix).
 *
 * - Direct enregistré : image extraite du tampon local (aucun accès réseau) — une tranche du tampon
 *   à partir de l'image clé la plus proche est copiée dans un fichier temporaire puis décodée.
 * - VOD : nécessite une seconde connexion HTTP au fournisseur ; l'activité ne l'appelle que si
 *   l'abonnement autorise au moins 2 connexions simultanées.
 *
 * Une seule extraction à la fois, lancée 250 ms après le dernier déplacement ; les demandes périmées
 * sont abandonnées ; une image arrivée après 1,5 s n'est pas affichée (mais gardée en cache). Les images
 * sont mises en cache par tranche de 10 s. Les adresses ne sont jamais journalisées.
 */
internal class SeekThumbnails(
    private val ctx: Context,
    private val handler: Handler,
    private val userAgent: String,
    private val widthPx: Int,
) {
    private val heightPx = widthPx * 9 / 16
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var token = 0L
    private var pending: Runnable? = null

    // Fil de travail uniquement.
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUrl: String? = null
    private val failedUrls = HashSet<String>()
    private val releaseRetriever = Runnable { runCatching { executor.execute { closeRetriever() } } }

    fun requestVod(url: String, positionMs: Long, cb: (Bitmap?) -> Unit) {
        // Seconde connexion au fournisseur : seulement si l'abonnement l'autorise (cf. ProviderConnections).
        if (!ProviderConnections.allowsExtra()) { cancel(); return }
        val key = "v:${url.hashCode()}:${positionMs / BUCKET_MS}"
        request(key, cb) { frameFromUrl(url, positionMs) }
        handler.removeCallbacks(releaseRetriever)
        handler.postDelayed(releaseRetriever, 8_000)
    }

    fun requestLive(session: TimeshiftSession, wallMs: Long, cb: (Bitmap?) -> Unit) {
        val key = "l:${session.id}:${wallMs / BUCKET_MS}"
        request(key, cb) { frameFromBuffer(session, wallMs) }
    }

    /** Abandonne la demande en cours (fin du déplacement). */
    fun cancel() {
        token++
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    fun release() {
        cancel()
        handler.removeCallbacks(releaseRetriever)
        runCatching { executor.execute { closeRetriever() } }
        executor.shutdown()
        cache.evictAll()
    }

    private fun request(key: String, cb: (Bitmap?) -> Unit, loader: () -> Bitmap?) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val my = ++token
        cache.get(key)?.let { cb(it); return }
        val r = Runnable {
            pending = null
            if (my != token) return@Runnable
            val asked = SystemClock.elapsedRealtime()
            runCatching {
                executor.execute {
                    if (my != token) return@execute
                    val bmp = try { loader() } catch (t: Throwable) { null }
                    handler.post {
                        if (bmp != null) cache.put(key, bmp)
                        if (my == token && SystemClock.elapsedRealtime() - asked <= MAX_WAIT_MS) cb(bmp)
                    }
                }
            }
        }
        pending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun frameFromUrl(url: String, positionMs: Long): Bitmap? {
        if (url in failedUrls) return null
        if (retrieverUrl != url) {
            closeRetriever()
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(url, mapOf("User-Agent" to userAgent))
            } catch (t: Throwable) {
                runCatching { r.release() }
                failedUrls += url
                return null
            }
            retriever = r
            retrieverUrl = url
        }
        return frame(retriever ?: return null, positionMs * 1000)
    }

    private fun frameFromBuffer(s: TimeshiftSession, wallMs: Long): Bitmap? {
        if (s.released) return null
        val buf = s.buffer
        val from = buf.seekOffset(wallMs)
        val len = minOf(SLICE_BYTES, buf.end - from)
        if (len < MIN_SLICE_BYTES) return null
        val tmp = File(ctx.cacheDir, "seekthumb.ts")
        val seg = TimeshiftBuffer.OpenSegment()
        try {
            FileOutputStream(tmp).use { out ->
                val b = ByteArray(64 * 1024)
                var pos = from
                val stop = from + len
                while (pos < stop) {
                    val n = buf.readAt(pos, b, 0, minOf(b.size.toLong(), stop - pos).toInt(), seg)
                    if (n <= 0) break
                    out.write(b, 0, n)
                    pos += n
                }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            seg.close()
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(tmp.absolutePath)
            frame(r, 0)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
            tmp.delete()
        }
    }

    private fun frame(r: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        val opt = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(timeUs, opt, widthPx, heightPx)
        } else {
            r.getFrameAtTime(timeUs, opt)?.let { full ->
                Bitmap.createScaledBitmap(full, widthPx, heightPx, true).also { if (it !== full) full.recycle() }
            }
        }
    }

    private fun closeRetriever() {
        runCatching { retriever?.release() }
        retriever = null
        retrieverUrl = null
    }

    private companion object {
        const val BUCKET_MS = 10_000L
        const val DEBOUNCE_MS = 250L
        const val MAX_WAIT_MS = 1_500L
        const val SLICE_BYTES = 4L shl 20
        const val MIN_SLICE_BYTES = 128L shl 10
    }
}
