package com.fluxfr.tv.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Chaînes « événement » des fournisseurs (DAZN, Prime Video, Max… en PPV) : pas de guide des programmes, le match
 * et son heure sont dans le NOM de la chaîne. Deux formats courants :
 *
 *   « DAZN FR 003| Vilnius Rytas vs. Nanterre 92 | BCL (2026-11-11 18:30:00) »
 *   « Snooker: John Higgins vs Stuart Bingham @ Aug 11 08:00 AM :MAX FR 01 »   (sans année)
 *
 * Heures données à l'heure de Paris. Fonctions pures (testées).
 */
object EventChannels {
    data class Event(val title: String, val competition: String?, val startMs: Long)

    /** Durée supposée d'un événement (pas d'heure de fin dans le nom). */
    const val DURATION_MS = 3 * 3600_000L
    /** Un événement commencé depuis plus longtemps est terminé : chaîne retirée du catalogue. */
    private const val STALE_MS = 6 * 3600_000L

    private val PARIS: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    private val DATED = Regex("""^(.*?)\(\s*(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::\d{2})?\s*\)\s*$""")
    private val AT_TIME = Regex(
        """^(.*?)\s@\s*([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2})\s+(\d{1,2}):(\d{2})\s*([AaPp][Mm])?\s*(?::.*)?$""",
    )
    /** Étiquette de la chaîne en tête : « DAZN FR 003 », « PRIME FR 017 ». */
    private val SLOT_LABEL = Regex("""^[A-Z+ .]*\bFR\b\s*\d+$""", RegexOption.IGNORE_CASE)
    /** Créneau vide : seulement l'étiquette (« DAZN FR 045 », « PRIME FR 012 ») ; « DAZN 1 » est une vraie chaîne. */
    private val EMPTY_SLOT = Regex("""^\s*(DAZN|PRIME|MAX|HBO MAX|AMAZON|PPV|EVENT|EVENTS)\s*FR\s*\d+\s*[|:]*\s*$""", RegexOption.IGNORE_CASE)
    /** Affiche entre deux équipes / joueurs : « A vs. B », « A @ B », « A - B ». */
    private val VERSUS = Regex("""\s(vs\.?|v\.?|@|-|–)\s""", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Événement lu dans le nom, ou null si ce n'est pas une chaîne événement datée. */
    fun parse(name: String, now: Long): Event? {
        val s = name.trim()
        DATED.find(s)?.let { m ->
            val (body, y, mo, d, h, mi) = m.destructured
            val start = millis(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) ?: return null
            return split(body, start)
        }
        AT_TIME.find(s)?.let { m ->
            val (body, mon, d, h, mi, ampm) = m.destructured
            val month = MONTHS.indexOf(mon.lowercase(Locale.ROOT)) + 1
            if (month == 0) return null
            var hour = h.toInt()
            // « 01:30 PM » → 13:30 ; « 13:30 PM » (déjà sur 24 h, fréquent) reste 13:30.
            if (ampm.equals("PM", true) && hour < 12) hour += 12
            if (ampm.equals("AM", true) && hour == 12) hour = 0
            // Pas d'année : celle qui place l'événement le plus près d'aujourd'hui.
            val year = Calendar.getInstance(PARIS).apply { timeInMillis = now }.get(Calendar.YEAR)
            val start = (year - 1..year + 1).mapNotNull { millis(it, month, d.toInt(), hour, mi.toInt()) }
                .minByOrNull { abs(it - now) } ?: return null
            val colon = body.indexOf(": ")
            return if (colon in 1..40) {
                Event(body.substring(colon + 2).trim(), body.substring(0, colon).trim(), start).takeIf { it.title.isNotEmpty() }
            } else split(body, start)
        }
        return null
    }

    private fun split(body: String, start: Long): Event? {
        val parts = body.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.size >= 2 && SLOT_LABEL.matches(parts[0])) parts.removeAt(0)
        val first = parts.firstOrNull() ?: return null
        val second = parts.getOrNull(1)
        // « 2e tour | Championnat X » : pas d'affiche (A vs B) dans la 1re partie → titre « Championnat X · 2e tour ».
        val title = if (second != null && !VERSUS.containsMatchIn(first)) "$second · $first" else first
        return Event(title, second, start)
    }

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long? {
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
        return Calendar.getInstance(PARIS).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis
    }

    /** Chaîne événement (datée), quel que soit son horaire. */
    fun isEvent(name: String, now: Long = System.currentTimeMillis()): Boolean = parse(name, now) != null

    /** Événement terminé depuis longtemps : la chaîne ne sert plus à rien (PPV d'il y a des semaines…). */
    fun isStale(name: String, now: Long): Boolean = parse(name, now)?.let { it.startMs < now - STALE_MS } == true

    /** Créneau d'événement vide (« DAZN FR 045 ») : rien à regarder. */
    fun isEmptySlot(name: String): Boolean = EMPTY_SLOT.matches(name)
}
