package com.fluxfr.tv.player

/**
 * Lecture au fil de l'eau d'un flux MPEG-TS (paquets de 188 octets) : repère la synchronisation et les
 * paquets « point d'accès aléatoire » (random_access_indicator) du flux vidéo, c'est-à-dire les images
 * clés où la lecture peut reprendre proprement après un saut.
 *
 * Le flux vidéo est le PID le plus fréquent (la vidéo représente l'essentiel du débit) ; tant qu'il
 * n'est pas connu, tous les points d'accès sont retenus.
 */
class TsScanner(private val onKeyframe: (Long) -> Unit, private val onPhase: (Long) -> Unit = {}) {
    private val pkt = ByteArray(PACKET)
    private var fill = 0
    private var pktStart = 0L
    private var pos = 0L
    private var lastEnd = -1L
    private var streak = 0
    private val counts = HashMap<Int, Int>()
    private var counted = 0
    private var videoPid = -1

    /** Au moins trois paquets consécutifs bien alignés vus depuis le début. */
    var everSynced = false
        private set
    val bytesScanned: Long get() = pos

    fun feed(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            val x = b[i]
            if (fill == 0) {
                if (x != SYNC) { pos++; i++; continue }
                pktStart = pos
            }
            pkt[fill++] = x
            pos++
            i++
            if (fill == PACKET) {
                fill = 0
                handle(pktStart)
            }
        }
    }

    private fun handle(start: Long) {
        streak = if (start == lastEnd) streak + 1 else 1
        lastEnd = start + PACKET
        if (streak < 3) return
        if (!everSynced || streak == 3) onPhase(start % PACKET)
        everSynced = true
        val pid = ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
        if (videoPid < 0 && pid != 0 && pid != NULL_PID) {
            counts[pid] = (counts[pid] ?: 0) + 1
            counted++
            if (counted >= PIDS_SAMPLE) videoPid = counts.maxByOrNull { it.value }?.key ?: -1
        }
        val afc = (pkt[3].toInt() shr 4) and 0x3
        if (afc >= 2 && (pkt[4].toInt() and 0xFF) > 0 && (pkt[5].toInt() and 0x40) != 0) {
            if (videoPid < 0 || pid == videoPid) onKeyframe(start)
        }
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val NULL_PID = 0x1FFF
        private const val PIDS_SAMPLE = 3000

        /** Le début ressemble à une liste de lecture HLS (#EXTM3U) plutôt qu'à du MPEG-TS. */
        fun looksLikePlaylist(b: ByteArray, len: Int): Boolean {
            val s = String(b, 0, minOf(len, 64), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t')
            return s.startsWith("#EXTM3U") || s.startsWith("<")
        }
    }
}
