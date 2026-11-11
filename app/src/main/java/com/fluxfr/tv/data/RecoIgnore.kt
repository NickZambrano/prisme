package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Titres que le profil ne veut plus voir servir de point de départ aux recommandations
 * (« Parce que tu as regardé « X » » alors que X a été regardé par quelqu'un d'autre, ou importé par erreur).
 * Clé = [searchKey] du titre : vaut aussi pour les titres de l'historique Netflix absents du catalogue.
 */
object RecoIgnore {
    private const val PREFS = "reco_ignore"

    private val _version = MutableStateFlow(0)
    /** Change à chaque ajout / remise à zéro : les rangées de recommandations se recalculent. */
    val version: StateFlow<Int> = _version

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keys(ctx: Context, profile: String): Set<String> =
        prefs(ctx).getStringSet(profile, null)?.toSet().orEmpty()

    fun add(ctx: Context, profile: String, title: String) {
        val k = searchKey(CatalogTitles.cleanTitle(title))
        if (k.isBlank()) return
        val set = keys(ctx, profile) + k
        prefs(ctx).edit().putStringSet(profile, set).apply()
        _version.value++
    }

    fun clear(ctx: Context, profile: String) {
        prefs(ctx).edit().remove(profile).apply()
        _version.value++
    }

    /** Pour la signature du calcul : un changement relance les recommandations. */
    fun stamp(ctx: Context, profile: String): String = "ignore:" + keys(ctx, profile).sorted().joinToString(",")

    /** true si la graine de titre [title] est écartée. */
    fun matches(ignored: Set<String>, title: String): Boolean =
        ignored.isNotEmpty() && searchKey(CatalogTitles.cleanTitle(title)) in ignored
}
