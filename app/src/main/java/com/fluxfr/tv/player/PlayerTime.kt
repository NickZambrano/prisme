package com.fluxfr.tv.player

/** Mise en forme des temps affichés par le lecteur (fonctions pures, testées). */
internal object PlayerTime {
    /** Signe moins typographique (U+2212). */
    const val MINUS = "−"

    /** « 1:02:15 » au-delà d'une heure, « 4:05 » sinon. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Temps restant / retard : « −38:20 ». */
    fun remaining(ms: Long): String = MINUS + clock(ms)

    /** Durée d'un saut : « 30 s », « 2 min », « 1 min 30 s ». */
    fun delta(ms: Long): String {
        val s = kotlin.math.abs(ms) / 1000
        return when {
            s < 60 -> "$s s"
            s % 60 == 0L -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }

    /** Durée d'un film : « 1 h 52 », « 45 min ». */
    fun runtime(ms: Long): String {
        val min = ((ms + 30_000) / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
    }
}
