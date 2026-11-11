package com.fluxfr.tv.data

import java.text.Normalizer

enum class Quality(val rank: Int, val label: String) {
    LOW(-1, "SD"), UNKNOWN(0, ""), HD(1, "HD"), FHD(2, "FHD"), UHD(3, "4K");

    /** Un badge est affiché (QualityBadge) : ni inconnue ni SD. */
    val hasBadge: Boolean get() = label.isNotEmpty() && this != LOW
}

private val MARKS = Regex("\\p{Mn}+")

/** Clé de recherche : minuscules, sans accents. */
fun searchKey(s: String): String =
    MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase()

/** Une version d'une chaîne (même chaîne, autre qualité / autre flux). */
data class Source(val url: String, val quality: Quality)

data class Channel(
    val id: String,
    val name: String,
    val category: String,
    val logo: String?,
    val quality: Quality,
    /** Triées de la meilleure à la moins bonne : servent de secours. */
    val sources: List<Source>,
    val num: Int,
    /** Replay du fournisseur (Xtream tv_archive / tv_archive_duration) : nombre de jours, 0 = aucun. */
    val archiveDays: Int = 0,
) {
    val key: String by lazy { searchKey(name) }
}

data class Movie(
    val id: String,
    val name: String,
    val category: String,
    val poster: String?,
    val quality: Quality,
    val url: String,
    val added: Long,
    val rating: String?,
) {
    val key: String by lazy { searchKey(name) }
}

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val image: String?,
    val plot: String?,
    val durationSec: Long,
)

data class Series(
    val id: String,
    val name: String,
    val category: String,
    val poster: String?,
    val quality: Quality,
    val plot: String?,
    val added: Long,
    /** Rempli uniquement pour les listes M3U (Xtream charge les épisodes à la demande). */
    val episodes: List<Episode>? = null,
) {
    val key: String by lazy { searchKey(name) }
}

/**
 * Format d'image et de son d'un film : HDR (« HDR10 », « HDR10+ », « HLG », « Dolby Vision »),
 * Dolby Atmos et disposition des canaux (« 5.1 », « 7.1 »).
 */
data class AvFormat(val hdr: String? = null, val atmos: Boolean = false, val channels: String? = null) {
    val isEmpty get() = hdr == null && !atmos && channels == null

    /** Complète ce format par [other] (ce qui est déjà connu prime, sauf Dolby Vision qui l'emporte). */
    fun merge(other: AvFormat): AvFormat = AvFormat(
        hdr = if (other.hdr == DOLBY_VISION) other.hdr else hdr ?: other.hdr,
        atmos = atmos || other.atmos,
        channels = channels ?: other.channels,
    )

    companion object {
        const val DOLBY_VISION = "Dolby Vision"
        val NONE = AvFormat()
        private val SPLIT = Regex("[^A-Z0-9+]+")
        private val LAYOUT = Regex("([5-9])\\.([0-4])")

        /** D'après le nom (et la catégorie) : HDR, HDR10, HDR10+, HLG, DV, DOVI, DOLBY VISION, ATMOS. */
        fun fromName(vararg texts: String?): AvFormat {
            var hdr: String? = null
            var atmos = false
            var channels: String? = null
            for (s in texts) {
                if (s.isNullOrBlank()) continue
                val up = searchKey(s).uppercase()
                val t = up.split(SPLIT).filter { it.isNotEmpty() }
                when {
                    "DV" in t || "DOVI" in t || up.contains("DOLBY VISION") || up.contains("DOLBYVISION") -> hdr = DOLBY_VISION
                    hdr == null && ("HDR10+" in t || "HDR10PLUS" in t) -> hdr = "HDR10+"
                    hdr == null && ("HDR10" in t || "HDR" in t) -> hdr = "HDR10"
                    hdr == null && "HLG" in t -> hdr = "HLG"
                }
                if ("ATMOS" in t) atmos = true
                if (channels == null && ("7.1" in s || "5.1" in s)) channels = if ("7.1" in s) "7.1" else "5.1"
            }
            return AvFormat(hdr, atmos, channels)
        }

        /** D'après l'analyse ffprobe (paires clé → valeur aplaties de info.video et info.audio). */
        fun fromStreams(video: List<Pair<String, String>>, audio: List<Pair<String, String>>): AvFormat {
            fun v(key: String) = video.filter { it.first == key }.map { it.second.lowercase() }
            val all = video.joinToString(" ") { it.second.lowercase() }
            val transfer = v("color_transfer")
            val hdr = when {
                "dovi" in all || "dolby vision" in all || v("codec_tag_string").any { it.startsWith("dvh") || it.startsWith("dva") } ->
                    DOLBY_VISION
                all.contains("hdr10+") || all.contains("smpte2094") -> "HDR10+"
                transfer.any { it.contains("smpte2084") } -> "HDR10"
                transfer.any { it.contains("arib-std-b67") } -> "HLG"
                else -> null
            }
            val audioAll = audio.joinToString(" ") { it.second.lowercase() }
            val codec = audio.filter { it.first == "codec_name" }.map { it.second.lowercase() }
            val atmos = "atmos" in audioAll && (codec.isEmpty() || codec.any { it == "eac3" || it == "truehd" || it == "ac4" })
            val layout = audio.filter { it.first == "channel_layout" }.firstNotNullOfOrNull { LAYOUT.find(it.second)?.value }
            val channels = layout ?: audio.filter { it.first == "channels" }.firstNotNullOfOrNull {
                when (it.second.trim().toIntOrNull()) {
                    6 -> "5.1"; 8 -> "7.1"; else -> null
                }
            }
            return AvFormat(hdr, atmos, channels)
        }
    }
}

class Catalog(
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
    val channelCategories: List<String>,
    val movieCategories: List<String>,
    val seriesCategories: List<String>,
    val fetchedAt: Long,
    /** Catalogue complet quand celui-ci est une vue filtrée (catégories masquées). */
    internal val full: Catalog? = null,
) {
    val unfiltered: Catalog get() = full ?: this

    val channelsByCat by lazy { channels.groupBy { it.category } }
    val moviesByCat by lazy { movies.groupBy { it.category } }
    val seriesByCat by lazy { series.groupBy { it.category } }
    val movieById by lazy { movies.associateBy { it.id } }
    val seriesById by lazy { series.associateBy { it.id } }
}
