package com.fluxfr.tv.data

import java.text.Normalizer

/**
 * Décide ce qu'on garde : contenu français uniquement, et en HD / FHD / 4K.
 * Tout repose sur les noms donnés par le fournisseur (catégories et titres).
 */
class ContentFilter(
    frKeywords: String,
    excludeKeywords: String,
    private val strictVod: Boolean = false,
    private val keepUnknownLive: Boolean = false,
    private val preferUhdLive: Boolean = false,
) {
    constructor(settings: Settings) : this(
        settings.frKeywords, settings.excludeKeywords, settings.strictVodQuality, settings.keepUnknownLive,
        settings.preferUhdLive,
    )

    private val fr = parseKeywords(frKeywords)
    // Dans un titre, "FRANCE" est ambigu (France 2, France 24 English…) et "MULTI" désigne souvent
    // une VO multi-sous-titres : seuls les marqueurs explicites (FR, VF, FRENCH…) comptent.
    private val frInName = fr - setOf("FRANCE", "MULTI")
    private val excl = parseKeywords(excludeKeywords)

    /**
     * Français si la catégorie porte un marqueur français (FR, FRANCE, VF…), ou si le titre lui-même
     * en porte un (FR, VF, FRENCH…). Une catégorie seulement "MULTI" ne suffit pas (souvent de la VO).
     */
    fun isFrench(category: String, name: String): Boolean =
        tokens(category).any { it in fr && it != "MULTI" } || tokens(name).any { it in frInName }

    /**
     * Faut-il télécharger le contenu de cette catégorie ? Oui si elle est française, MULTI, ou sans
     * étiquette de pays ; non si elle est clairement d'une autre langue (ES, PT, IT, NL, EN…).
     * Évite de télécharger des centaines de milliers d'entrées étrangères.
     */
    fun worthFetching(category: String): Boolean {
        val t = tokens(category)
        if (t.any { it in fr || it == "MULTI" }) return true
        return t.none { it in COUNTRY_TAGS }
    }

    /**
     * Fausses chaînes des fournisseurs : séparateurs ("##### FRENCH #####") et créneaux vides
     * ("NO EVENT", "NO MATCH"…) qui n'affichent qu'une image fixe.
     */
    fun isPlaceholder(name: String): Boolean {
        if (name.trim().none { it.isLetterOrDigit() }) return true
        // Nom nettoyé : retire symboles et préfixes ("⭐ NO EVENT ⭐", "|FR| NO MATCH").
        val t = cleanName(name).trim()
        if (t.none { it.isLetterOrDigit() }) return true
        if (Regex("^[#=*~_\\-\\s]{3,}").containsMatchIn(t) || Regex("[#=*~]{3,}\\s*$").containsMatchIn(t)) return true
        val n = norm(t)
        return PLACEHOLDERS.any { n == it || n.startsWith("$it ") }
    }

    fun isExcluded(category: String, name: String): Boolean =
        tokens(category).any { it in excl } || tokens(name).any { it in excl }

    /** La qualité indiquée dans le nom prime ; sinon on prend celle de la catégorie. */
    fun itemQuality(name: String, category: String): Quality =
        quality(name).let { if (it != Quality.UNKNOWN) it else quality(category) }

    /** Films / séries : comme [itemQuality], mais une catégorie « CAM / TS » rend tout son contenu basse qualité. */
    fun vodQuality(name: String, category: String): Quality =
        if (isLowRelease(name) || isLowRelease(category)) Quality.LOW else itemQuality(name, category)

    /** Une chaîne est affichée si sa MEILLEURE version est au moins HD. */
    fun acceptLive(best: Quality) = best.rank >= 1 || (best == Quality.UNKNOWN && keepUnknownLive)

    /**
     * Ordre de préférence des versions d'une même chaîne. Par défaut FHD > HD > 4K > inconnue > SD :
     * chez beaucoup de fournisseurs, la "4K" des chaînes n'est pas le vrai flux de la chaîne.
     */
    fun liveRank(q: Quality): Int = when (q) {
        Quality.UHD -> if (preferUhdLive) 5 else 2
        Quality.FHD -> 4
        Quality.HD -> 3
        Quality.UNKNOWN -> 1
        Quality.LOW -> 0
    }

    fun acceptVod(q: Quality) = if (strictVod) q.rank >= 1 else q != Quality.LOW

    companion object {
        /** Étiquettes de langue / pays étrangers des catégories (utilisées seulement pour éviter de les télécharger). */
        private val COUNTRY_TAGS = setOf(
            "EN", "ENG", "ENGLISH", "UK", "US", "USA", "AR", "ARA", "ARABIC", "DE", "GER", "GERMAN", "ES", "ESP",
            "SPANISH", "LAT", "LATINO", "LATAM", "MX", "IT", "ITA", "ITALIAN", "PT", "POR", "BR", "TR", "TUR",
            "TURKISH", "NL", "DUTCH", "PL", "RU", "RUS", "UA", "IN", "HINDI", "PK", "PH", "KR", "KOREAN", "JP",
            "CN", "AL", "ALB", "EXYU", "GR", "RO", "SE", "NO", "DK", "FI", "NORDIC", "IR", "PERSIAN", "KU", "KURD",
            "VN", "TH", "BG", "HU", "CZ", "SK", "HR", "RS", "BA", "MK", "SI", "IL",
        )

        private val PLACEHOLDERS = listOf("NO EVENT", "NO EVENTS", "NO MATCH", "NO STREAM", "NO SIGNAL", "OFFLINE", "COMING SOON", "PROCHAINEMENT")

        private val SPLIT = Regex("[^A-Z0-9+]+")
        private val MARKS = Regex("\\p{Mn}+")
        private val UHD = setOf("4K", "UHD", "2160P", "2160", "8K")
        private val FHD = setOf("FHD", "1080P", "1080", "1080I", "FULLHD")
        private val HD = setOf("HD", "720P", "720", "HDR", "HDR10")
        private val CODEC_HD = setOf("HEVC", "H265", "X265")
        private val LOW = setOf("SD", "LQ", "LOW", "480P", "576P", "360P", "240P", "SDTV")

        fun parseKeywords(s: String): Set<String> =
            s.split(',', ';').map { norm(it).trim() }.filter { it.isNotEmpty() }.toSet()

        private fun norm(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").uppercase()

        fun tokens(s: String): List<String> = norm(s).split(SPLIT).filter { it.isNotEmpty() }

        /**
         * Copies filmées en salle ou pré-versions (CAM, HDCAM, TS, TELESYNC, TC, SCREENER, R5…) : toujours
         * basse qualité, même si le nom annonce « 1080p ». Mots isolés seulement (« TSUNAMI » n'est pas « TS »).
         */
        private val LOW_RELEASE = setOf(
            "CAM", "HDCAM", "CAMRIP", "CAMHD", "HDCAMRIP", "TS", "HDTS", "TSRIP", "TELESYNC", "TC", "HDTC", "TELECINE",
            "SCR", "SCREENER", "DVDSCR", "DVDSCREENER", "BDSCR", "R5", "WORKPRINT",
        )
        // "HD-CAM", "HD CAM", "HD-TS", "HD TC" : deux mots après découpage.
        private val LOW_RELEASE_PAIRS = setOf("CAM", "TS", "TC")

        /** Nom d'une copie « cinéma » (CAM, TS, TC, SCREENER…), à écarter du catalogue. */
        fun isLowRelease(s: String): Boolean {
            val t = tokens(s)
            return t.any { it in LOW_RELEASE } ||
                t.zipWithNext().any { (a, b) -> a == "HD" && b in LOW_RELEASE_PAIRS }
        }

        fun quality(s: String): Quality {
            val t = tokens(s)
            if (t.isEmpty()) return Quality.UNKNOWN
            return when {
                // Copies « cinéma » (CAM, TS…) : traitées par vodQuality, films et séries seulement
                // (un nom de chaîne contenant « TS » ou « TC » ne doit pas être masqué).
                t.any { it in UHD } -> Quality.UHD
                t.any { it in FHD } || norm(s).contains("FULL HD") -> Quality.FHD
                t.any { it in HD } -> Quality.HD
                t.any { it in LOW } -> Quality.LOW // "SD HEVC" reste SD
                t.any { it in CODEC_HD } -> Quality.HD
                else -> Quality.UNKNOWN
            }
        }

        private val PREFIX_SEP = Regex(
            "^[\\s|\\[(•*★#-]*(FR|FRA|FRANCE|FRENCH|VF|VFF|VFQ|MULTI)\\s*[|\\]):•\\-]+\\s*",
            RegexOption.IGNORE_CASE,
        )
        private val PREFIX_SPACE = Regex("^\\s*FR\\s+(?=\\S)", RegexOption.IGNORE_CASE)
        private val QUALITY_WORDS = Regex(
            "(?i)(?<![\\p{L}\\p{N}])(4K|UHD|FHD|FULL\\s?HD|HDR10|HDR|HD|SD|HEVC|H\\.?265|X265|2160P|1080P|720P)(?![\\p{L}\\p{N}])"
        )
        private val EMPTY_BRACKETS = Regex("[\\[(]\\s*[\\])]")
        // Repères de secours / pays entre crochets : "M6 [BK]", "TF1 (FR)", "TF1 HD (backup)".
        private val TAGS = Regex("(?i)[\\[(]\\s*(BK|B|BACKUP|BACK\\s?UP|ALT|FR|VF|MULTI|\\d)\\s*[\\])]")
        private val TRAILING_TAGS = Regex("(?i)\\s+(BACKUP|BK|FR)\\s*$")
        // Symboles décoratifs (◉ ● ★ ✪ …) qui polluent les noms.
        private val SYMBOLS = Regex("[\\p{So}\\p{Sk}]")
        private val SPACES = Regex("\\s{2,}")
        private const val TRIM_CHARS = "|-:•*★_/"

        /** "|FR| TF1 FHD" -> "TF1" ; "FR: France 2 ᴴᴰ" -> "France 2". */
        fun cleanName(raw: String, fallback: String? = null): String {
            var s = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            repeat(2) {
                s = PREFIX_SEP.replace(s, "")
                s = PREFIX_SPACE.replace(s, "")
            }
            s = SYMBOLS.replace(s, " ")
            s = QUALITY_WORDS.replace(s, " ")
            s = TAGS.replace(s, " ")
            s = EMPTY_BRACKETS.replace(s, " ")
            s = TRAILING_TAGS.replace(s.trim(), "")
            s = SPACES.replace(s, " ").trim { it.isWhitespace() || it in TRIM_CHARS }
            return s.ifBlank { fallback ?: raw.trim() }
        }

        private val CATEGORY_PREFIX = Regex(
            "^[\\s|\\[(]*(TV|VOD|VIP|EU|SERIES|SERIE|SÉRIES|SÉRIE|SRS|LIVE|MOVIES|MOVIE|FILMS|FILM)\\s*[|\\]):•\\-]+\\s*",
            RegexOption.IGNORE_CASE,
        )

        /** "VOD | FR - NETFLIX" -> "NETFLIX". */
        fun cleanCategory(raw: String): String {
            var s = raw.trim()
            repeat(3) { s = CATEGORY_PREFIX.replace(s, "") } // "TV | EU | FR | GÉNÉRAL" -> "FR | GÉNÉRAL"
            return cleanName(s, fallback = "Général")
        }

        private val NON_ALNUM = Regex("[^a-z0-9]")

        /** Clé de dédoublonnage : "TF1 HD" et "TF1 4K" donnent la même. */
        fun dedupKey(cleanedName: String) = NON_ALNUM.replace(searchKey(cleanedName), "")
    }
}
