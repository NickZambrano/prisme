package com.fluxfr.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Liens profonds (prisme://…), recherche vocale / globale de Google TV.
 *
 * [MainActivity] analyse l'intent reçu et dépose ici la cible ; l'interface ([com.fluxfr.tv.ui.FluxApp])
 * la consomme ([consume]) une fois le catalogue prêt. La lecture directe (prisme://play) est gérée par
 * [MainActivity] elle-même et ne passe pas par [pending].
 */
object DeepLinks {
    const val SCHEME = "prisme"

    sealed interface Target {
        data class OpenMovie(val id: String) : Target
        data class OpenSeries(val id: String) : Target
        data class Search(val query: String) : Target
    }

    /** Lien de lecture : clé de reprise (« movie:… » / « ep:… ») et profil éventuel. */
    data class Play(val key: String, val profile: String?)

    val pending = MutableStateFlow<Target?>(null)

    /** Récupère la cible en attente et la retire (null s'il n'y en a pas). */
    fun consume(): Target? = pending.getAndUpdate { null }

    fun movieUri(id: String): Uri = Uri.parse("$SCHEME://movie/" + Uri.encode(id))
    fun seriesUri(id: String): Uri = Uri.parse("$SCHEME://series/" + Uri.encode(id))
    fun playUri(key: String, profile: String): Uri = Uri.Builder().scheme(SCHEME).authority("play")
        .appendQueryParameter("key", key).appendQueryParameter("profile", profile).build()

    /** Résultat de l'analyse d'un intent : une cible d'interface, une lecture, ou rien. */
    sealed interface Parsed {
        data class Ui(val target: Target) : Parsed
        data class Playback(val play: Play) : Parsed
    }

    fun parse(intent: Intent?): Parsed? {
        intent ?: return null
        return runCatching {
            when (intent.action) {
                Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                    val q = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                    if (q.isEmpty()) null else Parsed.Ui(Target.Search(q))
                }
                Intent.ACTION_VIEW -> parseUri(intent.data)
                else -> null
            }
        }.getOrNull()
    }

    fun parseUri(uri: Uri?): Parsed? {
        if (uri == null || uri.scheme != SCHEME) return null
        val first = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        return when (uri.host) {
            "play" -> uri.getQueryParameter("key")?.takeIf { it.isNotBlank() }
                ?.let { Parsed.Playback(Play(it, uri.getQueryParameter("profile")?.takeIf { p -> p.isNotBlank() })) }
            "movie" -> first?.let { Parsed.Ui(Target.OpenMovie(it)) }
            "series" -> first?.let { Parsed.Ui(Target.OpenSeries(it)) }
            "search" -> uri.getQueryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }?.let { Parsed.Ui(Target.Search(it)) }
            else -> null
        }
    }
}
