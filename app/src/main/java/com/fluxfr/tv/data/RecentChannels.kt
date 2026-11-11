package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dernières chaînes regardées par le profil actif (la plus récente en premier, 15 au plus). */
object RecentChannels {
    private const val PREFS = "fluxfr"
    private const val KEY = "recentChannels"
    private const val MAX = 15
    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids.asStateFlow()
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
        val raw = prefs(ctx).getString(prefKey(profile), "") ?: ""
        _ids.value = raw.split('\n').filter { it.isNotBlank() }
    }

    /** À appeler quand une chaîne en direct démarre réellement (lecture prête). */
    fun record(ctx: Context, channelId: String) {
        load(ctx)
        val now = (listOf(channelId) + _ids.value.filter { it != channelId }).take(MAX)
        _ids.value = now
        prefs(ctx).edit().putString(prefKey(loadedFor ?: Profiles.MAIN), now.joinToString("\n")).apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
