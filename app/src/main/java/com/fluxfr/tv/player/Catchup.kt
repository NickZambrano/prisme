package com.fluxfr.tv.player

import android.content.Context
import com.fluxfr.tv.data.EpgProgram
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.Settings
import com.fluxfr.tv.data.Source
import com.fluxfr.tv.data.XtreamClient
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Replay du fournisseur (« catch-up » Xtream : chaînes tv_archive = 1, sur tv_archive_duration jours).
 * URL : {serveur}/timeshift/{user}/{pass}/{durée en minutes}/{AAAA-MM-JJ:HH-mm}/{stream_id}.ts, l'heure de
 * début étant exprimée dans le fuseau du serveur (server_info.timezone, mémorisé par [AccountLimits]).
 */
object Catchup {
    const val TYPE = "catchup"
    /** Un programme commencé depuis moins longtemps n'a pas d'intérêt à être repris au début. */
    const val MIN_ELAPSED_MS = 60_000L
    private const val DAY_MS = 24 * 3600_000L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Durée demandée au serveur, en minutes entières (arrondi au-dessus, 1 au moins). */
    fun durationMinutes(startMs: Long, endMs: Long): Long =
        ((endMs - startMs + 59_999) / 60_000).coerceAtLeast(1)

    fun formatStart(startMs: Long, tz: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = tz }.format(Date(startMs))

    fun url(server: String, user: String, pass: String, streamId: String, startMs: Long, endMs: Long, tz: TimeZone): String {
        val base = XtreamClient.normalize(server)
        return "$base/timeshift/${enc(user)}/${enc(pass)}/${durationMinutes(startMs, endMs)}/" +
            "${formatStart(startMs, tz)}/$streamId.ts"
    }

    /** URL de replay pour le compte configuré ; null hors Xtream (M3U) ou identifiant inattendu. */
    fun url(ctx: Context, streamId: String, startMs: Long, endMs: Long): String? {
        val s = Settings(ctx)
        if (s.mode != Settings.MODE_XTREAM || s.server.isBlank() || s.user.isBlank()) return null
        if (streamId.isEmpty() || !streamId.all { it.isDigit() }) return null
        return url(s.server, s.user, s.pass, streamId, startMs, endMs, AccountLimits.serverTimeZone(ctx))
    }

    /** Le programme peut être revu chez le fournisseur (déjà commencé, encore dans la durée d'archive). */
    fun replayable(archiveDays: Int, program: EpgProgram, now: Long = System.currentTimeMillis()): Boolean =
        archiveDays > 0 && program.startMs <= now - MIN_ELAPSED_MS && program.startMs >= now - archiveDays * DAY_MS &&
            program.endMs > program.startMs

    /** Élément de lecture « replay » d'un programme de la chaîne [live] ; null si indisponible. */
    fun item(ctx: Context, live: PlayItem, program: EpgProgram): PlayItem? {
        val u = url(ctx, live.itemId, program.startMs, program.endMs) ?: return null
        return PlayItem(
            title = live.title,
            subtitle = "⟲ " + program.title,
            sources = listOf(Source(u, Quality.UNKNOWN)),
            live = false,
            progressKey = null,
            type = TYPE,
            itemId = "catchup:${live.itemId}:${program.startMs}",
            poster = live.poster,
            number = live.number,
            plot = program.description,
            archiveDays = live.archiveDays,
            catchupOf = live.itemId,
        )
    }
}
