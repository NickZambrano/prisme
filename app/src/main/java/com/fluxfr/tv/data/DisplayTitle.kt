package com.fluxfr.tv.data

/**
 * Titre tel qu'on l'AFFICHE (vignettes, bandeaux, fiches, lecteur). Les noms du catalogue restent
 * intacts : la correspondance Top 10 / TMDB / sagas s'appuie sur l'année et les mentions qu'ils portent.
 *
 *   "Finestkind - 2023"                      → "Finestkind"            (année : 2023)
 *   "EN - Megalomaniac [MULTI-SUB] - 2023"   → "Megalomaniac"
 *   "Vaiana, la légende du bout du monde (2026)" → "Vaiana, la légende du bout du monde"
 */
object DisplayTitle {
    private val YEAR_SUFFIX = Regex("""\s*[-–—(\[]\s*((?:19|20)\d{2})\s*[)\]]?\s*$""")
    private val BRACKET_TAGS = Regex("""\s*[\[(]\s*(?:MULTI(?:[- ]?SUBS?)?|SUBS?|VOSTFR|VOST|VF|VFF|VFQ|VO|TRUEFRENCH|FRENCH|4K|UHD|FHD|HD|HDR|HEVC|H265|X265)\s*[\])]""", RegexOption.IGNORE_CASE)
    private val LANG_PREFIX = Regex("""^\s*(?:EN|FR|VF|VO|VOSTFR|MULTI|DE|ES|IT|US|UK)\s*[-–—|:]\s+""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s{2,}""")

    /** Titre sans année finale, sans préfixe de langue ni mentions techniques entre crochets. */
    fun of(name: String): String {
        var s = name.trim()
        s = BRACKET_TAGS.replace(s, " ")
        // L'année peut précéder une mention retirée ci-dessus : on la cherche après nettoyage.
        s = YEAR_SUFFIX.replace(s.trim(), "")
        s = LANG_PREFIX.replace(s, "")
        s = SPACES.replace(s, " ").trim().trim('-', '–', '—', '|', ':').trim()
        return s.ifBlank { name.trim() }
    }

    /** Année portée par le nom (« … - 2023 », « … (2023) »), sinon null. */
    fun year(name: String): Int? =
        YEAR_SUFFIX.find(BRACKET_TAGS.replace(name.trim(), " ").trim())?.groupValues?.get(1)?.toIntOrNull()
}
