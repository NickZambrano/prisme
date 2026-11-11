package com.fluxfr.tv.data

/**
 * Genres canoniques (en français) et normalisation des genres bruts des fournisseurs :
 * « Action, Aventure », « Sci-Fi & Fantasy », « Comedy », « Comédies », « Drame / Romance »,
 * « Animation (jeunesse) »… Les genres inconnus sont ignorés.
 */
object Genres {
    const val ACTION = "Action"
    const val AVENTURE = "Aventure"
    const val ANIMATION = "Animation"
    const val COMEDIE = "Comédie"
    const val CRIME = "Crime"
    const val DOCUMENTAIRE = "Documentaire"
    const val DRAME = "Drame"
    const val FAMILIAL = "Familial"
    const val FANTASTIQUE = "Fantastique"
    const val GUERRE = "Guerre"
    const val HISTOIRE = "Histoire"
    const val HORREUR = "Horreur"
    const val JEUNESSE = "Jeunesse"
    const val MUSIQUE = "Musique"
    const val MYSTERE = "Mystère"
    const val ROMANCE = "Romance"
    const val SCIENCE_FICTION = "Science-fiction"
    const val THRILLER = "Thriller"
    const val WESTERN = "Western"
    const val TELEREALITE = "Téléréalité"

    val ALL = listOf(
        ACTION, AVENTURE, ANIMATION, COMEDIE, CRIME, DOCUMENTAIRE, DRAME, FAMILIAL, FANTASTIQUE, GUERRE,
        HISTOIRE, HORREUR, JEUNESSE, MUSIQUE, MYSTERE, ROMANCE, SCIENCE_FICTION, THRILLER, WESTERN, TELEREALITE,
    )

    /** Clés sans accents, en minuscules, mots séparés par une espace. */
    private val DICT: Map<String, List<String>> = buildMap {
        fun add(target: List<String>, vararg keys: String) = keys.forEach { put(it, target) }
        fun add(target: String, vararg keys: String) = add(listOf(target), *keys)
        add(ACTION, "action", "arts martiaux", "martial arts")
        add(AVENTURE, "aventure", "adventure", "aventures")
        add(ANIMATION, "animation", "anime", "dessin anime", "dessins animes", "cartoon", "manga")
        add(COMEDIE, "comedie", "comedy", "humour", "humor", "sitcom", "comique")
        add(listOf(COMEDIE, DRAME), "comedie dramatique", "dramedy")
        add(listOf(COMEDIE, ROMANCE), "comedie romantique", "romcom", "romantic comedy")
        add(MUSIQUE, "musique", "music", "musical", "musicale", "comedie musicale", "concert")
        add(CRIME, "crime", "criminel", "policier", "polar", "gangster", "detective")
        add(DOCUMENTAIRE, "documentaire", "documentary", "docu", "doc")
        add(DRAME, "drame", "drama", "dramatique")
        add(FAMILIAL, "familial", "family", "famille", "familiale")
        add(FANTASTIQUE, "fantastique", "fantasy", "fantaisie", "merveilleux", "surnaturel", "supernatural")
        add(GUERRE, "guerre", "war")
        add(HISTOIRE, "histoire", "history", "historique", "historical", "biopic", "biographie", "biography", "peplum")
        add(HORREUR, "horreur", "horror", "epouvante", "gore", "slasher", "zombie")
        add(JEUNESSE, "jeunesse", "kids", "kid", "enfant", "enfants", "children", "child", "junior")
        add(MYSTERE, "mystere", "mystery", "mysteres", "enquete", "suspense")
        add(ROMANCE, "romance", "romantique", "romantic", "amour", "sentimental")
        add(SCIENCE_FICTION, "science fiction", "sci fi", "scifi", "sf", "anticipation")
        add(THRILLER, "thriller", "espionnage", "spy")
        add(WESTERN, "western")
        add(TELEREALITE, "telerealite", "tele realite", "reality", "reality tv", "realite", "real tv")
    }

    /** Séparateurs entre genres d'une même chaîne. */
    private val SPLIT = Regex("\\s*(?:[,;/|&+()\\[\\]]|\\bet\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE)
    private val NON_LETTER = Regex("[^a-z0-9]+")

    private fun key(raw: String): String = NON_LETTER.replace(searchKey(raw), " ").trim()

    private fun lookup(k: String): List<String>? =
        DICT[k] ?: if (k.length > 3 && k.endsWith("s")) DICT[k.dropLast(1)] else null

    /** Genres canoniques d'une chaîne brute (sans doublon, dans l'ordre d'apparition). */
    fun normalize(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // « Sci-Fi & Fantasy » : on essaie d'abord la chaîne entière, puis chaque morceau.
        lookup(key(raw))?.let { out += it }
        if (out.isEmpty()) {
            for (part in raw.split(SPLIT)) {
                val k = key(part)
                if (k.isEmpty()) continue
                val hit = lookup(k)
                if (hit != null) { out += hit; continue }
                // « Film d'action », « Action-Aventure » : mot par mot.
                for (w in k.split(' ')) lookup(w)?.let { out += it }
            }
        }
        return out.toList()
    }

    /** Normalise et fusionne plusieurs valeurs brutes. */
    fun normalizeAll(raws: Iterable<String?>): List<String> {
        val out = LinkedHashSet<String>()
        for (r in raws) out += normalize(r)
        return out.toList()
    }
}
