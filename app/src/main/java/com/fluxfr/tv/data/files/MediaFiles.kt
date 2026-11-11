package com.fluxfr.tv.data.files

import java.util.Locale

/**
 * Élément d'un dossier parcouru dans l'onglet « Fichiers » (disque local, dossier choisi, partage réseau).
 * [ref] : localisation propre à la source (chemin absolu, URI de document, chemin SMB relatif au partage).
 */
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val ref: String,
)

/** Règles pures de l'onglet « Fichiers » : types de fichiers, tri, tailles (testées sans Android). */
object MediaFiles {
    /** Type des éléments de lecture lancés depuis l'onglet « Fichiers » (PlayItem.type). */
    const val TYPE = "file"

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg",
    )

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun isSubtitle(name: String): Boolean = extension(name) == "srt"

    /** Nom sans extension (« Film.2020.mkv » → « Film.2020 ») ; un nom sans point est gardé tel quel. */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** Fichiers cachés (« .nomedia », « .Trash »…) et dossiers système des NAS : jamais affichés. */
    fun isHidden(name: String): Boolean =
        name.startsWith(".") || name.startsWith("@") || name.equals("\$RECYCLE.BIN", ignoreCase = true) ||
            name.equals("System Volume Information", ignoreCase = true) || name == "#recycle"

    /** Dossiers et vidéos seulement, sans fichiers cachés. */
    fun visible(e: FileEntry): Boolean = !isHidden(e.name) && (e.isDir || isVideo(e.name))

    /**
     * Tri « naturel » insensible à la casse et aux accents : « Episode 2 » avant « Episode 10 ».
     * Les suites de chiffres sont comparées par valeur (zéros de tête ignorés, puis plus courte d'abord).
     */
    val naturalOrder: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    fun naturalCompare(a: String, b: String): Int {
        val c = compareFolded(fold(a), fold(b))
        // Égaux sans casse ni accents : ordre stable sur le texte d'origine.
        return if (c != 0) c else a.compareTo(b)
    }

    private fun compareFolded(x: String, y: String): Int {
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val nx = x.substring(si, i).trimStart('0')
                val ny = y.substring(sj, j).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                // Même valeur : « 1 » avant « 01 ».
                val z = (i - si) - (j - sj)
                if (z != 0) return z
            } else {
                if (cx != cy) return cx.compareTo(cy)
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    private val MARKS = Regex("\\p{M}+")

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(MARKS, "")

    /** Dossiers d'abord, puis fichiers, chacun par nom (tri naturel ; noms normalisés une seule fois). */
    fun sorted(list: List<FileEntry>): List<FileEntry> =
        list.map { it to fold(it.name) }
            .sortedWith { (a, fa), (b, fb) ->
                when {
                    a.isDir != b.isDir -> if (a.isDir) -1 else 1
                    else -> compareFolded(fa, fb).takeIf { it != 0 } ?: a.name.compareTo(b.name)
                }
            }
            .map { it.first }

    /** Contenu affiché d'un dossier : filtré ([visible]) puis trié ([sorted]). */
    fun prepare(list: List<FileEntry>): List<FileEntry> = sorted(list.filter(::visible))

    /** Sous-titre .srt de même nom que la vidéo (« Film.mkv » → « Film.srt », ou « Film.fr.srt »). */
    fun subtitleFor(video: String, names: Collection<String>): String? {
        val base = baseName(video).lowercase(Locale.ROOT)
        val subs = names.filter { isSubtitle(it) }
        subs.firstOrNull { baseName(it).lowercase(Locale.ROOT) == base }?.let { return it }
        return subs.firstOrNull { baseName(it).lowercase(Locale.ROOT).startsWith("$base.") }
    }

    /** Taille lisible en français : « 850 o », « 12 Ko », « 1,4 Go ». */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var v = bytes.toDouble() / 1024
        var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        val txt = if (v >= 100) v.toLong().toString() else String.format(Locale.FRANCE, "%.1f", v).removeSuffix(",0")
        return "$txt ${units[u]}"
    }

    /**
     * Racine d'un volume à partir d'un dossier d'appli ([android.content.Context.getExternalFilesDirs]) :
     * « /storage/ABCD-1234/Android/data/com.app/files » → « /storage/ABCD-1234 ». null si le chemin ne contient pas
     * « /Android/data/ ».
     */
    fun volumeRootOf(appFilesDir: String): String? {
        val i = appFilesDir.indexOf("/Android/data/")
        if (i <= 0) return null
        return appFilesDir.substring(0, i)
    }
}

/**
 * Lecture d'un fichier distant par plages (source de données du lecteur) : nombre d'octets à lire depuis
 * [position] pour une demande de [requested] octets ([UNBOUNDED] = jusqu'à la fin) dans un fichier de [size] octets.
 */
object ByteRange {
    const val UNBOUNDED = -1L

    /** Octets restants ; IllegalArgumentException si la position dépasse la fin du fichier. */
    fun remaining(position: Long, requested: Long, size: Long): Long {
        require(position >= 0 && position <= size) { "Position $position hors du fichier ($size octets)" }
        val left = size - position
        return if (requested == UNBOUNDED) left else minOf(requested, left)
    }

    /** Taille d'une lecture : au plus [want], au plus ce qui reste ([remaining], [UNBOUNDED] = inconnu). */
    fun chunk(want: Int, remaining: Long): Int =
        if (remaining == UNBOUNDED) want else minOf(want.toLong(), remaining).toInt()
}
