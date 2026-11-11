package com.fluxfr.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.fluxfr.tv.data.Channel
import com.fluxfr.tv.data.Episode
import com.fluxfr.tv.data.Movie
import com.fluxfr.tv.data.Progress
import com.fluxfr.tv.data.Quality
import com.fluxfr.tv.data.Series
import com.fluxfr.tv.data.Source
import com.fluxfr.tv.data.TntChannel

data class PlayItem(
    val title: String,
    val subtitle: String?,
    val sources: List<Source>,
    val live: Boolean,
    val progressKey: String?,
    val type: String,
    val itemId: String,
    val poster: String?,
    val seriesId: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    /** Numéro de chaîne affiché (TNT) ; sinon la position dans la liste. */
    val number: Int? = null,
    /** Fiche (écran de pause) : résumé, année, casting, titre de l'épisode. Récupérés en arrière-plan si absents. */
    val plot: String? = null,
    val year: String? = null,
    val cast: String? = null,
    val episodeTitle: String? = null,
    /** Direct : jours de replay proposés par le fournisseur (tv_archive_duration), 0 = aucun. */
    val archiveDays: Int = 0,
    /** Replay : identifiant de la chaîne en direct dont ce programme provient. */
    val catchupOf: String? = null,
    /** Fichier (onglet « Fichiers ») : sous-titres .srt du même nom, ajoutés au choix des sous-titres. */
    val subtitleUri: String? = null,
)

/**
 * Démarrage du premier élément lancé : [ASK] = panneau « Reprendre la lecture ? » s'il y a une position
 * enregistrée ; [RESUME] = reprise directe à la position enregistrée ; [FROM_START] = depuis le début.
 * Les éléments suivants (épisode suivant, zapping) sont toujours en [ASK].
 */
enum class StartMode { ASK, RESUME, FROM_START }

/** File de lecture transmise au lecteur (chaînes d'une catégorie, épisodes d'une série…). */
object PlayerSession {
    var queue: List<PlayItem> = emptyList()
        private set
    var index: Int = 0
    private var lastStartAt = 0L
    private var pendingStartMode = StartMode.ASK

    /** Mode de démarrage demandé pour le prochain élément lancé (puis [StartMode.ASK]). */
    internal fun takeStartMode(): StartMode = pendingStartMode.also { pendingStartMode = StartMode.ASK }

    /**
     * Lance le lecteur. [startMode] : le choix déjà fait sur la fiche (« Reprendre à 1:15 » → [StartMode.RESUME],
     * « Depuis le début » → [StartMode.FROM_START]) pour ne pas reposer la question dans le lecteur.
     */
    fun start(ctx: Context, queue: List<PlayItem>, index: Int, startMode: StartMode = StartMode.ASK) {
        if (queue.isEmpty()) return
        // Double appui sur OK : on ignore le second lancement.
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAt < 600) return
        lastStartAt = now
        this.queue = queue
        this.index = index.coerceIn(0, queue.lastIndex)
        pendingStartMode = startMode
        val intent = Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}

object Playables {
    fun channels(list: List<Channel>) = list.map {
        PlayItem(it.name, it.category, it.sources, live = true, progressKey = null, type = "live", itemId = it.id, poster = it.logo,
            number = it.num.takeIf { n -> n in 1..9999 }, archiveDays = it.archiveDays,
        )
    }

    fun tnt(list: List<TntChannel>) = list.map {
        PlayItem(
            it.channel.name, "TNT", it.channel.sources, live = true, progressKey = null,
            type = "live", itemId = it.channel.id, poster = it.channel.logo, number = it.number,
            archiveDays = it.channel.archiveDays,
        )
    }

    fun movie(m: Movie) = PlayItem(
        m.name, null, listOf(Source(m.url, m.quality)), live = false,
        progressKey = Progress.movieKey(m.id), type = Progress.MOVIE, itemId = m.id, poster = m.poster,
    )

    fun fromProgress(p: Progress) = PlayItem(
        p.title, p.subtitle, listOfNotNull(p.url?.let { Source(it, Quality.UNKNOWN) }), live = false,
        progressKey = p.key, type = p.type, itemId = p.itemId, poster = p.poster,
        seriesId = p.seriesId, season = p.season, episode = p.episode,
    )

    fun episodes(series: Series, eps: List<Episode>) = eps.map {
        PlayItem(
            series.name, episodeCode(it), listOf(Source(it.url, series.quality)), live = false,
            progressKey = Progress.episodeKey(it.id), type = Progress.EPISODE, itemId = it.id,
            poster = series.poster, seriesId = series.id, season = it.season, episode = it.number,
            plot = it.plot?.takeIf { p -> p.isNotBlank() }, episodeTitle = it.title.takeIf { t -> t.isNotBlank() },
        )
    }

    fun episodeCode(e: Episode) = "S%02d E%02d".format(e.season, e.number)
}
