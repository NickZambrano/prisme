package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Un rappel de programme (grille « Guide », matchs du jour). */
data class Reminder(
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val startMs: Long,
    val endMs: Long,
) {
    val key: String get() = Reminders.key(channelId, startMs)
}

/**
 * Rappels du profil actif. Stockage : SharedPreferences "fluxfr", clé "reminders_<profil>"
 * (une ligne par rappel, champs séparés par des tabulations).
 *
 * Tant que le processus vit, un minuteur (toutes les 30 s) publie dans [due] le rappel dont le programme
 * commence dans moins d'une minute, puis le retire de la liste. Les rappels passés sont supprimés.
 *  - Écran principal : `ReminderOverlay` (ui/GuideScreen.kt) affiche [due] par-dessus tout.
 *  - Lecteur (PlayerActivity) : peut aussi observer [due] et appeler [dismiss] une fois affiché.
 */
object Reminders {
    private const val PREFS = "fluxfr"
    private const val KEY = "reminders"
    private const val TICK_MS = 30_000L
    private const val LEAD_MS = 60_000L
    /** Un rappel publié et jamais traité disparaît après ce délai. */
    private const val DUE_TIMEOUT_MS = 90_000L

    fun key(channelId: String, startMs: Long) = "$channelId@$startMs"

    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    private val _due = MutableStateFlow<Reminder?>(null)
    /** Rappel à afficher maintenant (null sinon). */
    val due: StateFlow<Reminder?> = _due.asStateFlow()
    @Volatile private var dueAt = 0L

    @Volatile private var loadedFor: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var ticker: Job? = null

    /** Charge les rappels du profil actif et lance le minuteur (une seule fois par processus). */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                runCatching { tick(app) }
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun load(ctx: Context) {
        Profiles.load(ctx)
        val profile = Profiles.current.value
        if (loadedFor == profile) return
        loadedFor = profile
        _items.value = read(ctx, profile)
    }

    @Synchronized
    private fun tick(ctx: Context) {
        load(ctx)
        val now = System.currentTimeMillis()
        if (_due.value != null && now - dueAt > DUE_TIMEOUT_MS) _due.value = null
        val cur = _items.value
        val fire = cur.filter { it.startMs - LEAD_MS <= now && it.endMs > now }.minByOrNull { it.startMs }
        val keep = cur.filter { it.endMs > now && it !== fire }
        if (fire != null) {
            _due.value = fire
            dueAt = now
        }
        if (keep.size != cur.size) write(ctx, keep)
    }

    fun isSet(channelId: String, startMs: Long) = _items.value.any { it.channelId == channelId && it.startMs == startMs }

    /** Ajoute ou retire ; renvoie true si le rappel est maintenant programmé. */
    @Synchronized
    fun toggle(ctx: Context, r: Reminder): Boolean {
        load(ctx.applicationContext)
        val cur = _items.value
        val exists = cur.any { it.key == r.key }
        val next = if (exists) cur.filter { it.key != r.key } else (cur + r).sortedBy { it.startMs }
        write(ctx.applicationContext, next)
        start(ctx)
        return !exists
    }

    /** Le rappel affiché a été traité (Regarder / Ignorer / délai écoulé). */
    fun dismiss() {
        _due.value = null
    }

    private fun write(ctx: Context, list: List<Reminder>) {
        _items.value = list
        val profile = loadedFor ?: Profiles.current.value
        val raw = list.joinToString("\n") { r ->
            listOf(r.channelId, r.channelName, r.programTitle, r.startMs.toString(), r.endMs.toString())
                .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }
        prefs(ctx).edit().putString(KEY + "_" + profile, raw).apply()
    }

    private fun read(ctx: Context, profile: String): List<Reminder> {
        val raw = prefs(ctx).getString(KEY + "_" + profile, "") ?: ""
        return raw.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            Reminder(f[0], f[1], f[2], f[3].toLongOrNull() ?: return@mapNotNull null, f[4].toLongOrNull() ?: return@mapNotNull null)
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
