package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * « Ma liste » du profil actif : films, séries et chaînes mis de côté.
 * Clés : "movie:<id>", "series:<id>", "live:<id>". Stockage : "favorites_<profil>".
 */
object Favorites {
    fun movieKey(id: String) = "movie:$id"
    fun seriesKey(id: String) = "series:$id"
    fun liveKey(id: String) = "live:$id"

    private const val PREFS = "fluxfr"
    private const val KEY = "favorites"

    /** Ordre d'ajout conservé (le plus récent en premier). */
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items.asStateFlow()
    @Volatile private var loadedFor: String? = null

    private fun prefKey(profile: String) = KEY + "_" + profile

    fun load(ctx: Context) {
        Profiles.load(ctx)
        if (loadedFor == Profiles.current.value) return
        reload(ctx)
    }

    /** Relit la liste du profil actif (changement de profil). */
    fun reload(ctx: Context) {
        val profile = Profiles.current.value
        loadedFor = profile
        _items.value = read(ctx, profile)
    }

    private fun read(ctx: Context, profile: String): List<String> {
        val raw = prefs(ctx).getString(prefKey(profile), "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun contains(key: String) = key in _items.value

    /** Ajoute ou retire ; renvoie true si l'élément est maintenant dans la liste. */
    fun toggle(ctx: Context, key: String): Boolean {
        load(ctx)
        val now = if (key in _items.value) _items.value - key else listOf(key) + _items.value
        write(ctx, Profiles.current.value, now)
        val added = key in now
        // Chaînes favorites : partagées avec les autres TV.
        if (key.startsWith("live:")) SettingsSync.touch(ctx, SettingsSync.FAVORITES)
        return added
    }

    /** Chaînes favorites reçues d'une autre TV ([SettingsSync]) : [merge] calcule la nouvelle liste du profil. */
    internal fun replaceLive(ctx: Context, profile: String, merge: (List<String>) -> List<String>) {
        val cur = if (profile == loadedFor) _items.value else read(ctx, profile)
        val next = merge(cur)
        if (next != cur) write(ctx, profile, next)
    }

    /** Liste d'un profil quelconque (synchro Google Drive). */
    internal fun listFor(ctx: Context, profile: String): List<String> =
        if (profile == loadedFor) _items.value else read(ctx, profile)

    /** Résultat d'une synchro Google Drive ([DriveSync]) : écrit sans la renvoyer vers Drive. */
    internal fun applySynced(ctx: Context, profile: String, merge: (List<String>) -> List<String>) {
        val cur = listFor(ctx, profile)
        val next = merge(cur)
        if (next != cur) write(ctx, profile, next, track = false)
    }

    private fun write(ctx: Context, profile: String, list: List<String>, track: Boolean = true) {
        if (track) {
            // Google Drive : dates d'ajout / de retrait, pour que les suppressions suivent sur les autres TV.
            val before = listFor(ctx, profile)
            runCatching { DriveSync.onFavoritesChanged(ctx, profile, list - before.toSet(), before - list.toSet()) }
        }
        if (profile == loadedFor) _items.value = list
        prefs(ctx).edit().putString(prefKey(profile), list.joinToString("\n")).apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
