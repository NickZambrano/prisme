package com.fluxfr.tv.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File

/** Sauvegarde du catalogue déjà filtré : démarrage instantané de l'appli. */
object CatalogCache {
    private const val VERSION = 8 // à incrémenter quand les filtres changent : force le rechargement (8 : replay)

    /** Cache relu : catalogue + catégories manquantes au dernier rafraîchissement (vide = complet). */
    class Entry(val catalog: Catalog, val missing: List<String>, val account: String?)

    /**
     * [account] : empreinte du compte IPTV ([CatalogRepository.accountKey]) ; [missing] : catégories que le serveur
     * n'a pas données (cache incomplet, rafraîchi au prochain démarrage). Champs facultatifs.
     */
    fun write(file: File, c: Catalog, account: String? = null, missing: List<String> = emptyList()) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        JsonWriter(tmp.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("v").value(VERSION.toLong())
            w.name("at").value(c.fetchedAt)
            if (account != null) w.name("acct").value(account)
            if (missing.isNotEmpty()) { w.name("missing"); strings(w, missing) }
            w.name("chCats"); strings(w, c.channelCategories)
            w.name("mvCats"); strings(w, c.movieCategories)
            w.name("srCats"); strings(w, c.seriesCategories)
            w.name("channels").beginArray()
            for (ch in c.channels) {
                w.beginObject()
                w.name("i").value(ch.id).name("n").value(ch.name).name("c").value(ch.category)
                w.name("l").value(ch.logo).name("q").value(ch.quality.name).name("o").value(ch.num.toLong())
                if (ch.archiveDays > 0) w.name("ar").value(ch.archiveDays.toLong())
                w.name("s").beginArray()
                for (s in ch.sources) w.beginObject().name("u").value(s.url).name("q").value(s.quality.name).endObject()
                w.endArray()
                w.endObject()
            }
            w.endArray()
            w.name("movies").beginArray()
            for (m in c.movies) {
                w.beginObject()
                w.name("i").value(m.id).name("n").value(m.name).name("c").value(m.category)
                w.name("p").value(m.poster).name("q").value(m.quality.name).name("u").value(m.url)
                w.name("a").value(m.added).name("r").value(m.rating)
                w.endObject()
            }
            w.endArray()
            w.name("series").beginArray()
            for (s in c.series) {
                w.beginObject()
                w.name("i").value(s.id).name("n").value(s.name).name("c").value(s.category)
                w.name("p").value(s.poster).name("q").value(s.quality.name).name("d").value(s.plot)
                w.name("a").value(s.added)
                s.episodes?.let { eps ->
                    w.name("e").beginArray()
                    for (e in eps) {
                        w.beginObject()
                        w.name("i").value(e.id).name("s").value(e.season.toLong()).name("n").value(e.number.toLong())
                        w.name("t").value(e.title).name("u").value(e.url).name("p").value(e.image)
                        w.endObject()
                    }
                    w.endArray()
                }
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun strings(w: JsonWriter, list: List<String>) {
        w.beginArray(); list.forEach { w.value(it) }; w.endArray()
    }

    fun read(file: File): Catalog? = readEntry(file, null)?.catalog

    /**
     * Cache complet (catalogue + état). Avec [account], un cache écrit pour un AUTRE compte est ignoré (null) ;
     * un ancien cache sans compte noté est accepté.
     */
    fun readEntry(file: File, account: String?): Entry? {
        if (!file.exists()) return null
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { r ->
            var version = 0
            var at = 0L
            var acct: String? = null
            var missing = emptyList<String>()
            var chCats = emptyList<String>()
            var mvCats = emptyList<String>()
            var srCats = emptyList<String>()
            val channels = ArrayList<Channel>()
            val movies = ArrayList<Movie>()
            val series = ArrayList<Series>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "v" -> version = r.nextInt()
                    "at" -> at = r.nextLong()
                    "acct" -> acct = Json.primitive(r)
                    "missing" -> missing = readStrings(r)
                    "chCats" -> chCats = readStrings(r)
                    "mvCats" -> mvCats = readStrings(r)
                    "srCats" -> srCats = readStrings(r)
                    "channels" -> array(r) { channels += readChannel(r) }
                    "movies" -> array(r) {
                        val m = Json.readFlat(r)
                        movies += Movie(
                            m["i"]!!, m["n"]!!, m["c"] ?: "", m["p"], q(m["q"]), m["u"]!!,
                            m["a"]?.toLongOrNull() ?: 0L, m["r"],
                        )
                    }
                    "series" -> array(r) { series += readSeries(r) }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (version != VERSION) return null
            if (account != null && acct != null && acct != account) return null
            return Entry(Catalog(channels, movies, series, chCats, mvCats, srCats, at), missing, acct)
        }
    }

    private fun q(s: String?) = runCatching { Quality.valueOf(s ?: "") }.getOrDefault(Quality.UNKNOWN)

    private inline fun array(r: JsonReader, each: () -> Unit) {
        r.beginArray(); while (r.hasNext()) each(); r.endArray()
    }

    private fun readStrings(r: JsonReader): List<String> {
        val out = ArrayList<String>()
        array(r) { Json.primitive(r)?.let(out::add) }
        return out
    }

    private fun readChannel(r: JsonReader): Channel {
        var id = ""; var name = ""; var cat = ""; var logo: String? = null
        var quality = Quality.UNKNOWN; var num = 0; var archive = 0
        val sources = ArrayList<Source>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "s" -> array(r) { val m = Json.readFlat(r); sources += Source(m["u"]!!, q(m["q"])) }
                "i" -> id = Json.primitive(r) ?: ""
                "n" -> name = Json.primitive(r) ?: ""
                "c" -> cat = Json.primitive(r) ?: ""
                "l" -> logo = Json.primitive(r)
                "q" -> quality = q(Json.primitive(r))
                "o" -> num = Json.primitive(r)?.toIntOrNull() ?: 0
                "ar" -> archive = Json.primitive(r)?.toIntOrNull() ?: 0
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Channel(id, name, cat, logo, quality, sources, num, archive)
    }

    private fun readSeries(r: JsonReader): Series {
        var id = ""; var name = ""; var cat = ""; var poster: String? = null
        var quality = Quality.UNKNOWN; var plot: String? = null; var added = 0L
        var eps: MutableList<Episode>? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "e" -> {
                    if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                    val list = ArrayList<Episode>()
                    array(r) {
                        val m = Json.readFlat(r)
                        list += Episode(
                            m["i"]!!, m["s"]?.toIntOrNull() ?: 0, m["n"]?.toIntOrNull() ?: 0,
                            m["t"] ?: "", m["u"]!!, m["p"], null, 0L,
                        )
                    }
                    eps = list
                }
                "i" -> id = Json.primitive(r) ?: ""
                "n" -> name = Json.primitive(r) ?: ""
                "c" -> cat = Json.primitive(r) ?: ""
                "p" -> poster = Json.primitive(r)
                "q" -> quality = q(Json.primitive(r))
                "d" -> plot = Json.primitive(r)
                "a" -> added = Json.primitive(r)?.toLongOrNull() ?: 0L
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Series(id, name, cat, poster, quality, plot, added, eps)
    }
}
