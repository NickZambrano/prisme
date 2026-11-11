package com.fluxfr.tv.player

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** La position demandée a déjà été effacée du tampon (pause trop longue). */
class BehindWindowException : IOException("Position sortie du tampon du direct")

/** Le flux n'a jamais pu être enregistré (erreur HTTP, flux non MPEG-TS…). */
class TimeshiftSourceException(message: String, val httpCode: Int = 0, val notTs: Boolean = false) : IOException(message)

/** Écriture locale du tampon impossible (disque plein…) : rien à voir avec le réseau, inutile de se reconnecter. */
class TimeshiftWriteException(cause: IOException) : IOException("Écriture du tampon du direct impossible", cause)

/**
 * Tampon circulaire sur disque du direct : segments de [segmentBytes] dans [dir] (« 0.ts », « 1.ts »…),
 * positions absolues depuis le début de l'enregistrement. Les plus anciens segments sont effacés au-delà
 * de [maxBytes] octets ou de [maxDurationMs] de direct.
 *
 * Un seul écrivain (l'enregistreur) ; lecteurs multiples ([TimeshiftDataSource]).
 */
class TimeshiftBuffer(
    val dir: File,
    private val maxBytes: Long,
    private val maxDurationMs: Long = 90 * 60_000L,
    private val segmentBytes: Long = 8L shl 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Object()
    private val index = TimeIndex()

    /** Première position encore disponible. */
    @Volatile var start = 0L
        private set
    /** Fin des données reçues (position du prochain octet). */
    @Volatile var end = 0L
        private set
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null

    private var out: FileOutputStream? = null
    private var outSegment = -1L

    val isClosed: Boolean get() = closed

    fun segmentFile(k: Long) = File(dir, "$k.ts")

    /**
     * Ajoute des octets reçus (thread de l'enregistreur). Lève [TimeshiftWriteException] si l'écriture locale
     * échoue (disque plein…).
     */
    fun append(b: ByteArray, off: Int, len: Int) {
        if (closed || len <= 0) return
        val now = clock()
        synchronized(lock) { if (index.isEmpty) index.record(now, end) }
        var o = off
        var n = len
        while (n > 0 && !closed) {
            val k = end / segmentBytes
            val room = minOf((k + 1) * segmentBytes - end, n.toLong()).toInt()
            try {
                if (k != outSegment) {
                    runCatching { out?.close() }
                    out = null
                    outSegment = -1
                    dir.mkdirs()
                    out = FileOutputStream(segmentFile(k), true)
                    outSegment = k
                }
                out!!.write(b, o, room)
            } catch (e: IOException) {
                if (closed) return
                throw TimeshiftWriteException(e)
            }
            o += room
            n -= room
            synchronized(lock) { end += room }
        }
        synchronized(lock) {
            index.record(now, end)
            evict()
            lock.notifyAll()
        }
    }

    fun addKeyframe(offset: Long) = synchronized(lock) { if (offset >= start) index.addKeyframe(offset) }

    fun setPacketPhase(phase: Long) = synchronized(lock) { index.packetPhase = phase }

    private fun evict() {
        while (true) {
            val k = start / segmentBytes
            val segEnd = (k + 1) * segmentBytes
            if (segEnd >= end) return // jamais le segment en cours d'écriture
            val tooBig = end - start > maxBytes
            val tooOld = index.lastWall - index.wallAt(segEnd) > maxDurationMs
            if (!tooBig && !tooOld) return
            segmentFile(k).delete()
            start = segEnd
            index.dropBefore(start)
        }
    }

    /** L'enregistrement a définitivement échoué : les lecteurs en attente reçoivent l'erreur. */
    fun fail(e: IOException) = synchronized(lock) {
        failure = e
        lock.notifyAll()
    }

    val failed: Boolean get() = failure != null

    /** Échec dû à l'écriture locale (disque plein) et non au fournisseur. */
    val writeFailed: Boolean get() = failure is TimeshiftWriteException

    /**
     * Octets disponibles à partir de [pos] ; attend au plus [waitMs] s'il n'y en a pas encore.
     * 0 : toujours rien ; -1 : tampon fermé. Lève [BehindWindowException] si [pos] a été effacé.
     */
    fun available(pos: Long, waitMs: Long): Long = synchronized(lock) {
        if (pos < start) throw BehindWindowException()
        if (pos >= end) {
            failure?.let { throw it }
            if (closed) return -1L
            if (waitMs > 0) lock.wait(waitMs)
            if (pos < start) throw BehindWindowException()
            if (pos >= end) {
                failure?.let { throw it }
                return if (closed) -1L else 0L
            }
        }
        end - pos
    }

    /** Lit au plus [len] octets disponibles à [pos] (sans attente) ; [file] : fichier ouvert réutilisable. */
    fun readAt(pos: Long, b: ByteArray, off: Int, len: Int, file: OpenSegment): Int {
        val k = pos / segmentBytes
        val segEnd = (k + 1) * segmentBytes
        val n = minOf(len.toLong(), segEnd - pos, end - pos).toInt()
        if (n <= 0) return 0
        if (pos < start) throw BehindWindowException()
        val raf = try {
            file.get(segmentFile(k), k)
        } catch (e: FileNotFoundException) {
            throw BehindWindowException()
        }
        raf.seek(pos - k * segmentBytes)
        val r = raf.read(b, off, n)
        if (r <= 0) throw IOException("Lecture du tampon impossible")
        return r
    }

    // ---- Correspondance heure / position (protégée)

    val firstWall: Long get() = synchronized(lock) { index.wallAt(start) }
    val lastWall: Long get() = synchronized(lock) { index.lastWall }
    fun wallAt(offset: Long): Long = synchronized(lock) { index.wallAt(offset) }
    fun seekOffset(wallMs: Long): Long = synchronized(lock) { maxOf(start, index.seekOffset(wallMs)) }

    /** Position de reprise « au direct » : dernière image clé arrivée au moins [latencyMs] avant la fin. */
    fun liveOffset(latencyMs: Long = 3_000L): Long = synchronized(lock) {
        if (index.isEmpty) return start
        maxOf(start, index.seekOffset(index.lastWall - latencyMs, maxBackMs = 8_000L))
    }

    /** Ferme le tampon ; [delete] : efface aussi les fichiers. */
    fun close(delete: Boolean) {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching { out?.close() }
        if (delete) dir.deleteRecursively()
    }

    /** Fichier de segment ouvert par un lecteur (réouvert seulement quand on change de segment). */
    class OpenSegment {
        private var raf: RandomAccessFile? = null
        private var segment = -1L

        fun get(f: File, k: Long): RandomAccessFile {
            raf?.let { if (segment == k) return it }
            close()
            val r = RandomAccessFile(f, "r")
            raf = r
            segment = k
            return r
        }

        fun close() {
            runCatching { raf?.close() }
            raf = null
            segment = -1
        }
    }
}
