package com.fluxfr.tv.data

import android.content.Context
import java.io.File

/**
 * Résultats mémorisés de la vérification des pistes audio des films "MULTI-LANG" :
 * F = piste française trouvée, X = pas de français, U = le fournisseur ne donne pas l'info.
 * Un film n'est vérifié qu'une seule fois.
 */
object MultiAudio {
    const val FRENCH = 'F'
    const val OTHER = 'X'
    const val UNKNOWN = 'U'

    /** Catégorie sous laquelle apparaissent les films MULTI dont la VF a été vérifiée. */
    const val CATEGORY = "Multi-langues (VF vérifiée)"

    private const val NO_INFO_KEY = "__noinfo"
    private const val NO_INFO_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    private fun file(ctx: Context) = File(ctx.filesDir, "audio_langs.txt")

    fun load(ctx: Context): MutableMap<String, Char> {
        val map = HashMap<String, Char>()
        runCatching {
            file(ctx).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0 && tab + 1 < line.length) map[line.substring(0, tab)] = line[tab + 1]
            }
        }
        return map
    }

    @Synchronized
    fun save(ctx: Context, map: Map<String, Char>) {
        runCatching {
            val tmp = File(ctx.filesDir, "audio_langs.tmp")
            tmp.bufferedWriter().use { w -> map.forEach { (k, v) -> w.append(k).append('\t').append(v).append('\n') } }
            if (!tmp.renameTo(file(ctx))) { file(ctx).delete(); tmp.renameTo(file(ctx)) }
        }
    }

    /** Le fournisseur n'indique pas les langues : on ne réessaie qu'au bout de 7 jours. */
    fun providerHasNoInfo(ctx: Context): Boolean {
        val f = File(ctx.filesDir, NO_INFO_KEY)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < NO_INFO_RETRY_MS
    }

    fun markProviderHasNoInfo(ctx: Context) {
        runCatching { File(ctx.filesDir, NO_INFO_KEY).writeText(System.currentTimeMillis().toString()) }
    }

    fun classify(langs: List<String>): Char = when {
        langs.isEmpty() -> UNKNOWN
        langs.any { it.lowercase().let { l -> l.startsWith("fr") || l == "vf" || l == "vff" } } -> FRENCH
        else -> OTHER
    }
}
