package com.fluxfr.tv.tvhome

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.fluxfr.tv.DeepLinks
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.VodMeta
import com.fluxfr.tv.data.kidsOnly
import com.fluxfr.tv.data.searchKey

/**
 * Suggestions pour la recherche globale / vocale de Google TV (films et séries du catalogue en cache).
 * Chaque résultat ouvre la fiche via prisme://movie/<id> ou prisme://series/<id>.
 */
class SearchSuggestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val ctx = context ?: return cursor
        runCatching {
            val raw = selectionArgs?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.takeIf { it != SearchManager.SUGGEST_URI_PATH_QUERY }
                ?: return cursor
            val words = searchKey(raw).split(' ').filter { it.isNotBlank() }
            if (words.sumOf { it.length } < 2) return cursor
            val limit = (uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull() ?: LIMIT).coerceIn(1, LIMIT)

            Profiles.load(ctx)
            val full = CatalogAccess.get(ctx) ?: return cursor
            val catalog = if (Profiles.active.value.kids) full.kidsOnly() else full
            VodMeta.load(ctx)
            fun match(key: String) = words.all { key.contains(it) }
            // Titres qui commencent par la recherche d'abord.
            val first = words.first()
            val movies = catalog.movies.filter { match(it.key) }.sortedBy { if (it.key.startsWith(first)) 0 else 1 }
            val series = catalog.series.filter { match(it.key) }.sortedBy { if (it.key.startsWith(first)) 0 else 1 }

            var row = 0L
            var mi = 0; var si = 0
            // Alterne films et séries pour garder les deux types dans les premiers résultats.
            while (row < limit && (mi < movies.size || si < series.size)) {
                if (mi < movies.size && (si >= series.size || mi <= si)) {
                    val m = movies[mi++]
                    val year = VodMeta.get(VodMeta.movieKey(m.id))?.year
                    cursor.addRow(arrayOf<Any?>(
                        row++, m.name, listOfNotNull("Film", year?.toString()).joinToString(" · "), m.poster,
                        "video/mp4", year, null, Intent_VIEW, DeepLinks.movieUri(m.id).toString(),
                    ))
                } else {
                    val s = series[si++]
                    val year = VodMeta.get(VodMeta.seriesKey(s.id))?.year
                    cursor.addRow(arrayOf<Any?>(
                        row++, s.name, listOfNotNull("Série", year?.toString()).joinToString(" · "), s.poster,
                        "video/mp4", year, null, Intent_VIEW, DeepLinks.seriesUri(s.id).toString(),
                    ))
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val LIMIT = 20
        private const val Intent_VIEW = "android.intent.action.VIEW"
        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
        )
    }
}
