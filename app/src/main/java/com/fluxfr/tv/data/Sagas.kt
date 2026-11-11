package com.fluxfr.tv.data

/** Une saga (franchise) : au moins trois films distincts, dans l'ordre de sortie. */
class Saga(val key: String, val name: String, val movies: List<Movie>)

class SagaIndex(val sagas: List<Saga>, val byMovieId: Map<String, Saga>) {
    companion object {
        val EMPTY = SagaIndex(emptyList(), emptyMap())
    }
}

/**
 * Regroupe les films du catalogue en sagas, d'après leurs titres (heuristique, sans réseau) :
 * - préfixe avant un séparateur (« Mission : Impossible - Fallout » → « Mission : Impossible ») ;
 * - préfixe avant un numéro de suite (« Le Parrain 2 », « Saw III », « Chapitre 2 », « Partie 1 ») ;
 * - préfixe suivi d'un mot de liaison (« Harry Potter à l'école… », « Harry Potter et la chambre… »).
 * Le préfixe commun doit compter au moins deux mots significatifs, ou un seul mot d'au moins 6 lettres
 * qui ne soit pas un mot courant (évite « Le Roi … », « Les Aventures de … »).
 * Les préfixes les plus longs sont servis en premier ; un film n'appartient qu'à une saga.
 *
 * Calcul en O(films × mots) : à faire hors du thread principal ([index] mémorise par liste de films).
 */
object Sagas {
    private const val MIN_FILMS = 3
    private const val MAX_PREFIX_WORDS = 7

    private val YEAR_SUFFIX = Regex("\\s*[-–(\\[]?\\s*(19|20)\\d{2}\\s*[)\\]]?\\s*$")
    private val NAME_YEAR = Regex("(19|20)\\d{2}")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    /** Séparateurs de sous-titre : « : », « - », « – », « , » (et « — », « | »). */
    private val SEPARATOR = Regex(":|\\s[-–—|]\\s|^\\s*[–—|]\\s*$|,")
    private val ROMAN = setOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii")
    private val ORDINAL = Regex("\\d{1,2}(e|er|re|eme|nd|rd|th|st)")
    private val SEQUEL_WORDS = setOf(
        "chapitre", "chapter", "partie", "part", "episode", "volume", "vol", "saison", "opus", "livre", "tome",
    )
    /** Mots de liaison : un préfixe suivi de l'un d'eux est un « titre principal » (Harry Potter et …). */
    private val CONNECTORS = setOf(
        "et", "a", "au", "aux", "de", "du", "des", "d", "la", "le", "les", "l", "contre", "vs", "versus",
        "and", "of", "in", "the", "en", "dans", "chez", "sur",
    )
    /** Mots vides ou trop courants pour nommer une saga à eux seuls. */
    private val STOP = CONNECTORS + setOf(
        "un", "une", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "notre", "nos", "votre", "vos",
        "leur", "leurs", "ce", "cet", "cette", "ces", "qui", "que", "quoi", "il", "elle", "ils", "elles", "on",
        "nous", "vous", "je", "tu", "moi", "toi", "lui", "y", "pour", "par", "avec", "sans", "sous", "o", "ou",
        "pas", "ne", "plus", "tout", "tous", "toute", "toutes", "si", "est", "c", "j", "s", "n", "qu", "m", "t",
        "an", "my", "your", "his", "her", "our", "their", "to", "on", "at", "for", "with", "from", "is", "i",
        "you", "we", "it", "all", "no", "not", "be", "this", "that", "what", "who", "how", "why", "when",
        "un", "deux", "trois",
    )
    /** Mots courants (≥ 6 lettres) qui ne suffisent pas à désigner une franchise. */
    private val COMMON = setOf(
        "aventure", "aventures", "histoire", "histoires", "retour", "nouveau", "nouvelle", "nouvelles",
        "dernier", "derniere", "derniers", "premier", "premiere", "grande", "grandes", "petite", "petites",
        "enfants", "enfant", "famille", "maison", "voyage", "secret", "secrets", "mission", "operation",
        "projet", "affaire", "guerre", "monde", "mondes", "amours", "amoureux", "mariage", "vacances", "soiree",
        "journee", "chasse", "chasseur", "chasseurs", "voisin", "voisins", "copains", "amis", "chroniques",
        "legende", "legendes", "mystere", "mysteres", "princesse", "prince", "reine", "royaume", "paradis",
        "enfer", "dernieres", "personne", "quelque", "toujours", "jamais", "comment", "pourquoi", "contre",
        "inside", "return", "rising", "beyond", "legend", "legends", "secret", "story", "stories", "christmas",
        "noel", "people", "little", "before", "after", "between", "summer", "winter", "night", "nights",
        "adventure", "adventures", "chronicles", "mystery", "mysteries", "american", "francais", "francaise",
        "special", "edition", "version", "director", "collection", "integrale", "spectacle", "concert",
        "documentaire", "animation", "musique", "comedie", "drame", "horreur", "action", "thriller",
    )

    /** Tokens d'un titre : forme normalisée (sans accents, minuscules) + fin du mot dans le titre. */
    private class Title(val text: String, val words: List<String>, val ends: IntArray, val boundaryAfter: BooleanArray)

    private fun normWord(w: String) = searchKey(w)

    /** Titre propre (sans balises de qualité ni année finale). */
    internal fun baseTitle(name: String): String {
        var t = ContentFilter.cleanName(name)
        t = YEAR_SUFFIX.replace(t, "").trim()
        // « Fast & Furious » = « Fast and Furious »
        t = t.replace(" & ", " and ")
        return t.trim { it.isWhitespace() || it in "-–:|,." }
    }

    private fun parse(title: String): Title {
        val matches = WORD.findAll(title).toList()
        val words = matches.map { normWord(it.value) }
        val ends = IntArray(matches.size) { matches[it].range.last + 1 }
        val boundary = BooleanArray(matches.size)
        for (i in 0 until matches.size - 1) {
            val between = title.substring(ends[i], matches[i + 1].range.first)
            boundary[i] = SEPARATOR.containsMatchIn(between)
        }
        return Title(title, words, ends, boundary)
    }

    private fun isNumberish(w: String) = w.all { it.isDigit() } || w in ROMAN || ORDINAL.matches(w)

    private fun romanValue(w: String): Int? = when (w) {
        "i" -> 1; "ii" -> 2; "iii" -> 3; "iv" -> 4; "v" -> 5; "vi" -> 6; "vii" -> 7; "viii" -> 8
        "ix" -> 9; "x" -> 10; "xi" -> 11; "xii" -> 12; else -> null
    }

    private fun numberValue(w: String): Int? =
        w.toIntOrNull() ?: romanValue(w) ?: ORDINAL.matchEntire(w)?.let { w.takeWhile { c -> c.isDigit() }.toIntOrNull() }

    /** Le préfixe [words] (k premiers mots) peut-il nommer une saga ? */
    internal fun acceptablePrefix(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val last = words.last()
        if (last in STOP || last in SEQUEL_WORDS || isNumberish(last)) return false
        val significant = words.filter { it !in STOP && !it.all { c -> c.isDigit() } && it.length > 1 }
        return when {
            significant.size >= 2 -> true
            significant.size == 1 -> significant[0].length >= 6 && significant[0] !in COMMON
            else -> false
        }
    }

    /** Nombre de mots significatifs (hors articles, mots de liaison, nombres) du préfixe. */
    private fun significantCount(words: List<String>): Int =
        words.count { it !in STOP && !it.all { c -> c.isDigit() } && it.length > 1 }

    /** Marqueur de suite au mot [i] : numéro (« 2 », « III », « 2e ») ou « chapitre / partie / épisode N ». */
    private fun sequelMarkerAt(t: Title, i: Int): Boolean {
        val w = t.words.getOrNull(i) ?: return false
        if (isNumberish(w)) return true
        return w in SEQUEL_WORDS && t.words.getOrNull(i + 1)?.let(::isNumberish) == true
    }

    /**
     * Longueurs de préfixe (en mots) admissibles pour ce titre. Préfixe d'UN seul mot significatif
     * (« L'Odyssée », « Le Parrain ») : seulement le titre exact ou un titre suivi d'un marqueur de suite
     * (« Le Parrain 2 », « … chapitre 3 ») — « L'Odyssée de Pi » et « L'Odyssée d'Alice » ne sont pas une saga.
     */
    private fun prefixLengths(t: Title): List<Int> {
        val n = t.words.size
        val out = ArrayList<Int>()
        for (k in 1..minOf(n, MAX_PREFIX_WORDS)) {
            val prefix = t.words.subList(0, k)
            if (!acceptablePrefix(prefix)) continue
            val valid = if (significantCount(prefix) <= 1) {
                k == n || sequelMarkerAt(t, k)
            } else {
                k == n || t.boundaryAfter[k - 1] || isNumberish(t.words[k]) ||
                    t.words[k] in SEQUEL_WORDS || t.words[k] in CONNECTORS
            }
            if (valid) out += k
        }
        return out
    }

    /**
     * Sagas TMDB connues ([collectionOf] : id de saga, "" = aucune, null = inconnu) : dès que 2 films du groupe
     * ont une réponse, on ne garde que ceux de la saga majoritaire (et ceux dont on ne sait rien).
     */
    private fun byCollection(list: List<Entry>, collectionOf: (Movie) -> String?): List<Entry> {
        val known = list.map { it to collectionOf(it.movie) }
        if (known.count { it.second != null } < 2) return list
        val best = known.mapNotNull { it.second?.takeIf { c -> c.isNotEmpty() } }
            .groupingBy { it }.eachCount().filter { it.value >= 2 }.maxByOrNull { it.value }?.key
        return known.filter { (_, c) -> c == null || c == best }.map { it.first }
    }

    private class Entry(val movie: Movie, val title: Title, val identity: String, val year: Int?)

    fun yearFromName(name: String): Int? = NAME_YEAR.findAll(name).lastOrNull()?.value?.toIntOrNull()

    private fun defaultYear(m: Movie): Int? = yearFromName(m.name) ?: VodMeta.get(VodMeta.movieKey(m.id))?.year

    /** Meilleur exemplaire d'un même film : meilleure qualité, puis celui qui a une affiche. */
    private fun better(m: Movie, than: Movie): Boolean =
        m.quality.rank > than.quality.rank ||
            (m.quality.rank == than.quality.rank && than.poster.isNullOrBlank() && !m.poster.isNullOrBlank())

    /**
     * Calcule les sagas d'une liste de films. [yearOf] : année de sortie (nom, sinon métadonnées).
     * [collectionOf] : saga TMDB déjà connue d'un film ("" = aucune, null = inconnu), prioritaire sur les titres.
     */
    fun compute(
        movies: List<Movie>,
        collectionOf: ((Movie) -> String?)? = null,
        yearOf: (Movie) -> Int? = ::defaultYear,
    ): SagaIndex {
        if (movies.size < MIN_FILMS) return SagaIndex.EMPTY
        // 1) Un seul exemplaire par film (même titre et même année) : la meilleure qualité, avec affiche.
        val entries = HashMap<String, Entry>()
        for (m in movies) {
            val base = baseTitle(m.name)
            val t = parse(base)
            if (t.words.isEmpty()) continue
            val year = yearOf(m)
            val id = t.words.joinToString(" ") + "|" + (year ?: "")
            val prev = entries[id]
            if (prev == null || better(m, prev.movie)) entries[id] = Entry(m, t, id, year)
        }
        // Même titre sans année connue + une seule version datée : le même film (en double dans le catalogue).
        val alias = HashMap<String, String>()
        val datedByWords = entries.values.filter { it.year != null }.groupBy { it.title.words.joinToString(" ") }
        for (e in entries.values.toList()) {
            if (e.year != null) continue
            val words = e.title.words.joinToString(" ")
            val dated = datedByWords[words]?.singleOrNull() ?: continue
            entries.remove(e.identity)
            alias[e.identity] = dated.identity
            if (better(e.movie, dated.movie)) entries[dated.identity] = Entry(e.movie, dated.title, dated.identity, dated.year)
        }
        val collections = HashMap<String, String?>()
        val collectionMemo: ((Movie) -> String?)? = collectionOf?.let { f ->
            { m -> if (collections.containsKey(m.id)) collections[m.id] else f(m).also { collections[m.id] = it } }
        }
        // 2) Préfixe candidat → films.
        val byKey = HashMap<String, MutableList<Entry>>()
        val keyLen = HashMap<String, Int>()
        for (e in entries.values) {
            for (k in prefixLengths(e.title)) {
                val key = e.title.words.subList(0, k).joinToString(" ")
                byKey.getOrPut(key) { ArrayList() } += e
                keyLen[key] = k
            }
        }
        // 3) Du plus spécifique au plus général ; chaque film n'est pris qu'une fois.
        val candidates = byKey.entries.filter { it.value.size >= MIN_FILMS }
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<Entry>>> { keyLen[it.key] ?: 0 }.thenByDescending { it.value.size }.thenBy { it.key })
        val taken = HashSet<String>()
        val sagas = ArrayList<Saga>()
        for ((key, list) in candidates) {
            var free = list.filter { it.identity !in taken }
            // Distincts par titre complet (deux années différentes d'un même titre = deux films).
            if (free.size < MIN_FILMS) continue
            if (collectionMemo != null) {
                free = byCollection(free, collectionMemo)
                if (free.size < MIN_FILMS) continue
            }
            val k = keyLen[key] ?: continue
            free.forEach { taken += it.identity }
            val ordered = free.sortedWith(
                compareBy<Entry, Int?>(nullsLast()) { it.year }
                    .thenBy(nullsFirst()) { sequelNumber(it.title, k) }
                    .thenBy { it.title.text.lowercase() }
            )
            sagas += Saga(key, sagaName(ordered.map { it.title }, k), ordered.map { it.movie })
        }
        sagas.sortWith(compareByDescending<Saga> { it.movies.size }.thenBy { it.name.lowercase() })
        val byMovie = HashMap<String, Saga>()
        for (s in sagas) for (m in s.movies) byMovie[m.id] = s
        // Les autres versions (qualités) d'un même film renvoient aussi à la saga.
        for (m in movies) if (m.id !in byMovie) {
            val t = parse(baseTitle(m.name))
            val raw = t.words.joinToString(" ") + "|" + (yearOf(m) ?: "")
            val id = alias[raw] ?: raw
            entries[id]?.movie?.id?.let { byMovie[it] }?.let { byMovie[m.id] = it }
        }
        return SagaIndex(sagas, byMovie)
    }

    /** Numéro de suite après le préfixe (« 2 », « II », « Episode IV » → 4) ; 0 si le titre est le préfixe seul. */
    private fun sequelNumber(t: Title, k: Int): Int? {
        if (t.words.size == k) return 0
        for (i in k until minOf(t.words.size, k + 3)) numberValue(t.words[i])?.let { return it }
        return null
    }

    private val SMALL = setOf(
        "de", "du", "des", "d", "la", "le", "les", "l", "et", "a", "à", "au", "aux", "en", "of", "the", "and", "in",
    )

    /** Nom de la saga : le préfixe tel qu'écrit dans le titre le plus fréquent, en casse « titre ». */
    private fun sagaName(titles: List<Title>, k: Int): String {
        val raw = titles.map { it.text.substring(0, it.ends[k - 1]) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: titles.first().text
        val shouting = raw.none { it.isLowerCase() }
        var first = true
        return WORD.replace(raw) { m ->
            val w = if (shouting) m.value.lowercase() else m.value
            val out = if (!first && w.lowercase() in SMALL) w.lowercase()
            else w.replaceFirstChar { it.titlecase() }
            first = false
            out
        }
    }

    // ------------------------------------------------------------------ Mémorisation

    private val memo = ArrayList<Pair<List<Movie>, SagaIndex>>(2)

    /**
     * Sagas de [movies], mémorisées par liste (identité) : deux listes au plus (vue filtrée, catalogue complet).
     * [ctx] : les sagas TMDB déjà en cache disque (aucune requête) départagent les groupes de titres.
     */
    fun index(movies: List<Movie>, ctx: android.content.Context? = null): SagaIndex {
        synchronized(memo) { memo.firstOrNull { it.first === movies }?.let { return it.second } }
        val app = ctx?.applicationContext
        val idx = compute(
            movies,
            collectionOf = app?.let { c -> { m: Movie -> runCatching { Tmdb.cachedCollection(c, VodMeta.movieKey(m.id)) }.getOrNull() } },
        )
        synchronized(memo) {
            memo.removeAll { it.first === movies }
            memo.add(0, movies to idx)
            while (memo.size > 2) memo.removeAt(memo.size - 1)
        }
        return idx
    }
}
