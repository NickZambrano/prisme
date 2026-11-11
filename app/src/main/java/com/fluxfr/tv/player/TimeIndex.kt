package com.fluxfr.tv.player

/**
 * Index temps → octets du tampon de direct (contrôle du direct).
 *
 * Points (heure d'arrivée en ms, octets reçus à cet instant), au plus un par [stepMs] : le dernier point
 * suit l'arrivée des données jusqu'à ce qu'un nouveau soit ajouté. Entre deux points, interpolation
 * linéaire (débit constant). Garde aussi les positions des points d'accès aléatoire (images clés) du flux
 * vidéo, pour démarrer la lecture sur une image complète après un saut.
 *
 * Non synchronisé : l'appelant protège les accès ([TimeshiftBuffer]).
 */
class TimeIndex(private val stepMs: Long = 1_000L) {
    private val walls = ArrayList<Long>()
    private val offsets = ArrayList<Long>()
    private val keyframes = ArrayList<Long>()

    /** Décalage des paquets TS de 188 octets (position d'un octet de synchronisation, modulo 188). */
    var packetPhase = 0L

    val isEmpty: Boolean get() = walls.isEmpty()
    val size: Int get() = walls.size
    val firstWall: Long get() = walls.firstOrNull() ?: -1L
    val lastWall: Long get() = walls.lastOrNull() ?: -1L
    val firstOffset: Long get() = offsets.firstOrNull() ?: 0L
    val lastOffset: Long get() = offsets.lastOrNull() ?: 0L
    val keyframeCount: Int get() = keyframes.size

    /** À l'instant [wallMs], [offset] octets ont été reçus (valeurs croissantes). */
    fun record(wallMs: Long, offset: Long) {
        if (walls.isEmpty()) { walls += wallMs; offsets += offset; return }
        val w = maxOf(wallMs, lastWall)
        val o = maxOf(offset, lastOffset)
        val n = walls.size
        if (n >= 2 && w - walls[n - 2] < stepMs) {
            walls[n - 1] = w
            offsets[n - 1] = o
        } else {
            walls += w
            offsets += o
        }
    }

    fun addKeyframe(offset: Long) {
        if (keyframes.isEmpty() || offset > keyframes.last()) keyframes += offset
    }

    /** Heure (ms) à laquelle l'octet [offset] est arrivé ; -1 si l'index est vide. */
    fun wallAt(offset: Long): Long {
        if (walls.isEmpty()) return -1L
        if (offset <= offsets[0]) return walls[0]
        val last = offsets.size - 1
        if (offset >= offsets[last]) return walls[last]
        val i = floorIndex(offsets, offset)
        val o0 = offsets[i]; val o1 = offsets[i + 1]
        val w0 = walls[i]; val w1 = walls[i + 1]
        if (o1 <= o0) return w1
        return w0 + ((w1 - w0).toDouble() * (offset - o0) / (o1 - o0)).toLong()
    }

    /** Position (octets) correspondant à l'heure [wallMs], bornée aux données présentes. */
    fun offsetAt(wallMs: Long): Long {
        if (walls.isEmpty()) return 0L
        if (wallMs <= walls[0]) return offsets[0]
        val last = walls.size - 1
        if (wallMs >= walls[last]) return offsets[last]
        val i = floorIndex(walls, wallMs)
        val w0 = walls[i]; val w1 = walls[i + 1]
        val o0 = offsets[i]; val o1 = offsets[i + 1]
        if (w1 <= w0) return o1
        return o0 + ((o1 - o0).toDouble() * (wallMs - w0) / (w1 - w0)).toLong()
    }

    /** Dernière image clé située à [offset] ou avant ; null si aucune. */
    fun keyframeAtOrBefore(offset: Long): Long? {
        if (keyframes.isEmpty() || keyframes[0] > offset) return null
        return keyframes[floorIndex(keyframes, offset)]
    }

    /** Arrondi au début de paquet TS précédent (selon [packetPhase]). */
    fun alignDown(offset: Long): Long {
        val p = packetPhase % TS_PACKET
        if (offset < p) return offset
        return offset - (offset - p) % TS_PACKET
    }

    /**
     * Où reprendre la lecture pour l'heure [wallMs] : sur la dernière image clé qui précède (si elle n'est
     * pas à plus de [maxBackMs]), sinon au début de paquet le plus proche.
     */
    fun seekOffset(wallMs: Long, maxBackMs: Long = 10_000L): Long {
        val target = offsetAt(wallMs)
        val kf = keyframeAtOrBefore(target)
        if (kf != null && kf >= firstOffset && wallAt(kf) >= wallMs - maxBackMs) return kf
        return maxOf(firstOffset, alignDown(target))
    }

    /** Oublie ce qui précède [offset] (début du tampon après suppression d'un segment). */
    fun dropBefore(offset: Long) {
        if (walls.isEmpty()) return
        while (offsets.size > 2 && offsets[1] <= offset) {
            walls.removeAt(0)
            offsets.removeAt(0)
        }
        if (offsets[0] < offset && offsets.size >= 2) {
            val w = wallAt(offset)
            walls[0] = w
            offsets[0] = minOf(offset, offsets[1])
        }
        var k = 0
        while (k < keyframes.size && keyframes[k] < offset) k++
        if (k > 0) keyframes.subList(0, k).clear()
    }

    private companion object {
        const val TS_PACKET = 188L

        /** Dernier indice i tel que list[i] <= value (list triée, value >= list[0]). */
        fun floorIndex(list: List<Long>, value: Long): Int {
            var lo = 0
            var hi = list.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (list[mid] <= value) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
