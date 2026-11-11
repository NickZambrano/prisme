package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Catégories masquées par l'utilisateur (TV, films, séries). Le masquage est appliqué à l'affichage :
 * pas besoin de recharger le catalogue, et on peut tout réafficher depuis les Réglages.
 */
object HiddenCategories {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"

    private const val PREFS = "fluxfr"
    private const val KEY = "hiddenCategories"

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()
    private var loaded = false

    fun key(kind: String, category: String) = "$kind|$category"

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _hidden.value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun set(ctx: Context, kind: String, category: String, hide: Boolean) {
        load(ctx)
        val k = key(kind, category)
        val next = if (hide) _hidden.value + k else _hidden.value - k
        if (next == _hidden.value) return
        _hidden.value = next
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
        SettingsSync.touch(ctx, SettingsSync.HIDDEN)
    }

    fun clear(ctx: Context) {
        _hidden.value = emptySet()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        SettingsSync.touch(ctx, SettingsSync.HIDDEN)
    }

    /** Catégories masquées reçues d'une autre TV ([SettingsSync]) : appliquées tout de suite, sans renvoi. */
    internal fun replaceAll(ctx: Context, set: Set<String>) {
        loaded = true
        _hidden.value = set
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }
}

/** Le catalogue tel qu'affiché : sans les catégories masquées (le catalogue complet reste dans [Catalog.unfiltered]). */
fun Catalog.withoutHidden(hidden: Set<String>): Catalog {
    if (hidden.isEmpty()) return this
    fun visible(kind: String, cat: String) = HiddenCategories.key(kind, cat) !in hidden
    return Catalog(
        channels = channels.filter { visible(HiddenCategories.LIVE, it.category) },
        movies = movies.filter { visible(HiddenCategories.MOVIE, it.category) },
        series = series.filter { visible(HiddenCategories.SERIES, it.category) },
        channelCategories = channelCategories.filter { visible(HiddenCategories.LIVE, it) },
        movieCategories = movieCategories.filter { visible(HiddenCategories.MOVIE, it) },
        seriesCategories = seriesCategories.filter { visible(HiddenCategories.SERIES, it) },
        fetchedAt = fetchedAt,
        full = unfiltered,
    )
}
