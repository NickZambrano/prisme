package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Un match (ou une course, un tournoi…) diffusé dans les prochaines 24 h. */
data class Match(
    /** Affiche des équipes ("PSG – OM") ou titre du programme. */
    val title: String,
    /** Compétition devinée ("Ligue 1", "Top 14"…) ou le sport ("Football"). */
    val competition: String,
    val startMs: Long,
    val endMs: Long,
    val channel: Channel,
    /** Numéro TNT de la chaîne, si c'en est une. */
    val tntNumber: Int? = null,
    val description: String? = null,
) {
    val key: String get() = channel.id + "@" + startMs
    val isLive: Boolean get() = isLive(System.currentTimeMillis())
    fun isLive(now: Long) = now in startMs until endMs
}

/** Résultat de la reconnaissance d'un titre de programme. */
data class MatchGuess(val title: String, val competition: String)

/**
 * Reconnaissance des matchs dans les titres du guide TV (fonctions pures, testées).
 * Règles :
 *  - magazines exclus (Téléfoot, Canal Football Club, Jour de foot, L'Équipe du soir…) ;
 *  - compétition forte dans le titre (Ligue 1, Top 14, NBA, Grand Prix, Roland-Garros…) : match ;
 *  - « A - B », « A vs B », « A / B » entre deux noms d'équipe : match si le titre, la description
 *    ou la chaîne (chaîne de sport) évoque le sport ; « vs » suffit à lui seul ;
 *  - mot de sport générique (football, rugby, match…) : seulement sur une chaîne de sport.
 */
object MatchDetector {
    private fun norm(s: String) = searchKey(s).replace('’', '\'')

    private val MAGAZINES = listOf(
        "telefoot", "canal football club", "jour de foot", "l'equipe du soir", "equipe du soir",
        "late football club", "canal rugby club", "intérieur sport", "interieur sport", "j+1",
        "l'equipe de greg", "l'equipe d'estelle", "tout le sport", "stade 2", "sport dimanche",
        "100% foot", "after foot", "rmc sport news", "magazine", "le mag", "debrief", "résumé", "resume",
        "les buts", "tirage au sort", "journal", "jt ", "meteo", "zap ", "best of", "dans les coulisses",
        "documentaire", "talk", "avant-match", "avant match", "le club", "the bridge",
    ).map { norm(it) }

    /** Mot-clé (forme normalisée, avec \b) -> libellé de compétition. Ordre = priorité. */
    private val COMPETITIONS: List<Pair<Regex, String>> = listOf(
        "ligue des champions" to "Ligue des champions",
        "champions league" to "Ligue des champions",
        "europa league" to "Europa League",
        "ligue europa" to "Europa League",
        "conference league" to "Conference League",
        "ligue des nations" to "Ligue des nations",
        "coupe du monde" to "Coupe du monde",
        "coupe de france" to "Coupe de France",
        "coupe de la ligue" to "Coupe de la Ligue",
        "trophee des champions" to "Trophée des champions",
        "ligue 1" to "Ligue 1",
        "ligue 2" to "Ligue 2",
        "premier league" to "Premier League",
        "bundesliga" to "Bundesliga",
        "serie a" to "Serie A",
        "liga" to "Liga",
        "laliga" to "Liga",
        "top 14" to "Top 14",
        "pro d2" to "Pro D2",
        "six nations" to "Six Nations",
        "6 nations" to "Six Nations",
        "champions cup" to "Champions Cup",
        "nba" to "NBA",
        "nfl" to "NFL",
        "euroleague" to "Euroleague",
        "formule 1" to "Formule 1",
        "formula 1" to "Formule 1",
        "f1" to "Formule 1",
        "grand prix" to "Grand Prix",
        "motogp" to "MotoGP",
        "roland-garros" to "Roland-Garros",
        "roland garros" to "Roland-Garros",
        "wimbledon" to "Wimbledon",
        "us open" to "US Open",
        "open d'australie" to "Open d'Australie",
        "masters 1000" to "Tennis",
        "atp" to "Tennis",
        "wta" to "Tennis",
        "tour de france" to "Tour de France",
        "euro" to "Euro",
        "jeux olympiques" to "Jeux olympiques",
        "starligue" to "Handball",
        "multiplex" to "Multiplex",
    ).map { (k, v) -> Regex("(^|[^a-z0-9])" + Regex.escape(norm(k)) + "($|[^a-z0-9])") to v }

    /** Mots génériques : un sport, pas une compétition. */
    private val SPORTS: List<Pair<Regex, String>> = listOf(
        "football" to "Football", "foot" to "Football", "rugby" to "Rugby", "tennis" to "Tennis",
        "basket" to "Basket", "basketball" to "Basket", "handball" to "Handball", "volley" to "Volley",
        "hockey" to "Hockey", "boxe" to "Boxe", "ufc" to "MMA", "mma" to "MMA", "cyclisme" to "Cyclisme",
        "golf" to "Golf", "athletisme" to "Athlétisme", "match" to "Match", "biathlon" to "Biathlon",
        "ski" to "Ski", "natation" to "Natation", "moto" to "Moto",
    ).map { (k, v) -> Regex("(^|[^a-z0-9])" + Regex.escape(k) + "($|[^a-z0-9])") to v }

    /** Séparateurs entre deux équipes. */
    private val SEPARATOR = Regex("\\s+(?:-|–|—|vs\\.?|v\\.?|/)\\s+", RegexOption.IGNORE_CASE)
    private val HAS_VS = Regex("\\s(?:vs\\.?|v\\.)\\s", RegexOption.IGNORE_CASE)
    private val NOT_TEAM = Regex(
        "(^|[\\s-])(episode|épisode|ep|saison|partie|chapitre|vol|volume|part|acte|semaine|jour|journée|journee|" +
            "tour|round|manche|etape|étape|finale|final|quart|quarts|huitième|huitieme|direct|live|replay|" +
            "multiplex|résumé|resume|highlights|rediffusion|integrale|intégrale)(\\s|$|\\.|\\d|-)",
        RegexOption.IGNORE_CASE,
    )

    private val PARENS = Regex("\\([^)]*\\)")

    private fun teamLike(s: String): Boolean {
        val t = s.trim().trim('"', '«', '»', ' ', '(', ')')
        if (t.length !in 2..32) return false
        if (!t.first().isLetter()) return false
        if (t.split(Regex("\\s+")).size > 4) return false
        if (NOT_TEAM.containsMatchIn(norm(t))) return false
        if (t.count { it.isDigit() } > 2) return false
        return true
    }

    /** Équipes trouvées dans le dernier segment du titre (après « : »), ou null. */
    fun teams(title: String): Pair<String, String>? {
        val segments = title.split(':', '|').map { it.trim() }.filter { it.isNotEmpty() }
        for (seg in segments.asReversed()) {
            // Retire un éventuel complément entre parenthèses ("(J12)", "(direct)").
            val parts = seg.replace(PARENS, " ").trim().split(SEPARATOR).map { it.trim() }
            // « Football - Ligue 1 - PSG / OM » : on cherche la paire la plus à droite.
            for (i in parts.lastIndex downTo 1) {
                if (teamLike(parts[i - 1]) && teamLike(parts[i])) return parts[i - 1] to parts[i]
            }
        }
        return null
    }

    fun isMagazine(title: String): Boolean {
        val n = " " + norm(title) + " "
        return MAGAZINES.any { it in n }
    }

    fun competition(text: String): String? {
        val n = norm(text)
        return COMPETITIONS.firstOrNull { it.first.containsMatchIn(n) }?.second
    }

    fun sport(text: String): String? {
        val n = norm(text)
        return SPORTS.firstOrNull { it.first.containsMatchIn(n) }?.second
    }

    /**
     * [sportsChannel] : chaîne dédiée au sport (beIN, RMC Sport, Canal+ Sport, L'Équipe…).
     * Renvoie le titre à afficher et la compétition, ou null si ce n'est pas un match.
     */
    fun detect(title: String, description: String? = null, sportsChannel: Boolean = false): MatchGuess? {
        val t = title.trim()
        if (t.isEmpty() || isMagazine(t)) return null
        val comp = competition(t)
        val sportInTitle = sport(t)
        val teams = teams(t)
        val desc = description.orEmpty()
        val label = comp ?: sportInTitle ?: competition(desc) ?: sport(desc)
        val display = teams?.let { "${it.first} – ${it.second}" } ?: t
        return when {
            teams != null && (comp != null || sportInTitle != null) -> MatchGuess(display, comp ?: sportInTitle!!)
            teams != null && HAS_VS.containsMatchIn(t) -> MatchGuess(display, label ?: "Sport")
            teams != null && (sportsChannel || label != null) -> MatchGuess(display, label ?: "Sport")
            comp != null && comp != "Multiplex" -> MatchGuess(t, comp)
            comp == "Multiplex" && (sportsChannel || sportInTitle != null) -> MatchGuess(t, sportInTitle ?: "Multiplex")
            sportInTitle != null && sportsChannel -> MatchGuess(t, sportInTitle)
            else -> null
        }.let { g ->
            // « Match » est un libellé trop vague : on précise avec la description si possible.
            if (g != null && g.competition == "Match") g.copy(competition = competition(desc) ?: sport(desc)?.takeIf { it != "Match" } ?: "Sport")
            else g
        }
    }

    // ------------------------------------------------------------ Créneaux « bouche-trou »

    /** Un vrai match ne dure pas plus de 4 h (au-delà : bloc de programmation, « 06:00 – 12:00 »…). */
    const val MAX_MATCH_MS = 4 * 3600_000L

    private val FILLER_TITLES = listOf(
        "programmes", "programme", "programmation", "fin des programmes", "rediffusion", "rediff",
        "a suivre", "a venir", "prochainement", "bientot", "no information", "pas d'information",
        "information non disponible", "no programme", "no program", "to be announced", "tba", "tbd",
        "off air", "hors antenne", "mire", "boucle", "en attente", "programme non communique",
    ).map { norm(it) }

    private val ALNUM = Regex("[^a-z0-9]")

    /**
     * Créneau qui n'est pas un programme : trop long (> 4 h), titre générique (« Programmes », « Rediffusion »,
     * « Fin des programmes »…) ou simple habillage de la chaîne (titre = nom de la chaîne).
     */
    fun isFiller(title: String, startMs: Long, endMs: Long, channelName: String? = null): Boolean {
        if (endMs - startMs > MAX_MATCH_MS) return true
        val n = norm(title).trim().trim('.', '!', '-', ' ')
        if (n.isEmpty()) return true
        if (FILLER_TITLES.any { n == it || n.startsWith("$it ") && it.length >= 8 }) return true
        if (n.startsWith("rediffusion") || n.startsWith("(rediffusion")) return true
        if (channelName != null) {
            val t = n.replace(ALNUM, "")
            val c = QUALITY_SUFFIX.replace(ContentFilter.dedupKey(channelName), "")
            if (t.isNotEmpty() && c.isNotEmpty() && (t == c || t == c + "hd" || t == c + "direct")) return true
        }
        return false
    }

    // ------------------------------------------------------------ Chaînes candidates

    private val SPORT_CHANNEL_KEYS = listOf(
        "canalsport", "canalfoot", "canalplussport", "canalplusfoot", "beinsport", "rmcsport", "eurosport",
        "primevideo", "ligue1", "dazn", "lequipe", "infosport", "golfplus", "automoto", "sportenfrance",
        "canalplus", "canal",
    )

    private val QUALITY_SUFFIX = Regex("(fhd|uhd|hd|4k|sd)$")

    /** Chaîne dédiée au sport, d'après son nom (clé de dédoublonnage : minuscules, alphanumérique). */
    fun isSportsChannelName(name: String): Boolean {
        val k = ContentFilter.dedupKey(name)
        return SPORT_CHANNEL_KEYS.any { key ->
            // CANAL+ seul (éventuellement « HD »/« 4K »), pas « Canal J ».
            if (key == "canal" || key == "canalplus") QUALITY_SUFFIX.replace(k, "") == key
            else k.startsWith(key) || key in k
        }
    }
}

/**
 * « Matchs du jour » : parcourt le guide (maintenant → +24 h) des chaînes susceptibles de diffuser du sport.
 * Cache 30 min ; 60 chaînes au plus ; requêtes limitées par [Epg.day] (6 en parallèle).
 */
object Matches {
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_CHANNELS = 60

    /** Chaînes TNT qui diffusent du sport en clair. */
    private val TNT_SPORT_NUMBERS = listOf(1, 2, 3, 4, 6, 9, 10, 11, 21, 17)

    @Volatile private var cache: Triple<Long, Long, List<Match>>? = null

    /** Résultat en cache pour ce catalogue (null si absent / périmé), sans réseau. */
    fun cached(catalog: Catalog): List<Match>? {
        val c = cache ?: return null
        if (c.first != catalog.fetchedAt || System.currentTimeMillis() - c.second > TTL_MS) return null
        val now = System.currentTimeMillis()
        return c.third.filter { it.endMs > now }
    }

    /** Chaînes à scanner : TNT sportives, puis chaînes de sport par nom, puis catégorie « sport ». */
    fun candidates(catalog: Catalog): List<Pair<Channel, Int?>> {
        val out = LinkedHashMap<String, Pair<Channel, Int?>>()
        val tnt = Tnt.find(catalog)
        for (n in TNT_SPORT_NUMBERS) tnt.firstOrNull { it.number == n }?.let { out.putIfAbsent(it.channel.id, it.channel to n) }
        val all = catalog.unfiltered.channels
        all.asSequence().filter { MatchDetector.isSportsChannelName(it.name) }
            .sortedByDescending { it.quality.rank }
            .forEach { if (out.size < MAX_CHANNELS) out.putIfAbsent(it.id, it to null) }
        if (out.size < MAX_CHANNELS) {
            all.asSequence().filter { "sport" in searchKey(it.category) }
                .forEach { if (out.size < MAX_CHANNELS) out.putIfAbsent(it.id, it to null) }
        }
        return out.values.take(MAX_CHANNELS)
    }

    /** Charge (ou relit le cache) ; à appeler depuis une coroutine (le réseau passe sur IO). */
    suspend fun load(ctx: Context, catalog: Catalog, force: Boolean = false): List<Match> = withContext(Dispatchers.Default) {
        if (!force) cached(catalog)?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val horizon = now + 24 * 3600_000L
        val chans = candidates(catalog)
        val found = coroutineScope {
            chans.map { (ch, number) ->
                async {
                    val sportsChannel = number == 21 || MatchDetector.isSportsChannelName(ch.name)
                    val day = try {
                        Epg.day(ctx, ch.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    day.filter { it.endMs > now && it.startMs < horizon }
                        .filterNot { MatchDetector.isFiller(it.title, it.startMs, it.endMs, ch.name) }
                        .mapNotNull { p ->
                            MatchDetector.detect(p.title, p.description, sportsChannel)?.let { g ->
                                Match(g.title, g.competition, p.startMs, p.endMs, ch, number, p.description)
                            }
                        }
                }
            }.awaitAll().flatten()
        }
        val result = dedupe(found).sortedWith(compareBy({ !it.isLive(now) }, { it.startMs }))
        cache = Triple(catalog.fetchedAt, now, result)
        result
    }

    /** Même match sur plusieurs chaînes : on garde la première (ordre des candidates = TNT, puis HD). */
    fun dedupe(list: List<Match>): List<Match> {
        val seen = HashSet<String>()
        return list.filter { m ->
            val k = searchKey(m.title).replace(Regex("[^a-z0-9]"), "") + "@" + (m.startMs / (15 * 60_000L))
            seen.add(k)
        }
    }
}
