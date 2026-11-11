package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.util.TimeZone

/** Historique Prime Video (fichier du bouton « Exporter Prime Video ») et bouton lui-même. */
class PrimeImportTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")

    private val csv = "﻿Title,Date,Type,Season,Episode,EpisodeTitle\n" +
        "\"A Teacher's Obsession\",\"2026-11-05\",\"movie\",\"\",\"\",\"\"\n" +
        "\"56 Days\",\"2026-11-04\",\"episode\",\"1\",\"3\",\"The Plan\"\n" +
        "\"56 Days\",\"2026-11-04\",\"episode\",\"1\",\"2\",\"\"\n" +
        "\"The Boys\",\"2026-10-01\",\"season\",\"4\",\"\",\"\"\n" +
        "\"The Boys\",\"2026-09-01\",\"season\",\"4\",\"\",\"\"\n" +
        "\"Reacher\",\"2026-08-01\",\"season\",\"2\",\"\",\"\"\n" +
        "\"Reacher\",\"2026-08-01\",\"episode\",\"2\",\"1\",\"\"\n" +
        "\"\",\"2026-08-01\",\"movie\",\"\",\"\",\"\"\n"

    @Test fun detectsPrimeHeader() {
        assertTrue(PrimeCsv.isPrime(csv))
        assertTrue(PrimeCsv.isPrime("Title,Date,Type,Season,Episode,EpisodeTitle\r\n"))
        assertFalse(PrimeCsv.isPrime("Title,Date\n\"Atlas\",\"1/2/25\"\n"))
        assertFalse(PrimeCsv.isPrime(""))
    }

    @Test fun parsesRows() {
        val v = PrimeCsv.parse(csv, paris)
        assertEquals(7, v.size)
        assertEquals(PvView("A Teacher's Obsession", NetflixCsv.noon(NfDate(2026, 11, 5), paris), PrimeCsv.MOVIE), v[0])
        // Même jour : la ligne du dessus (plus récente) une seconde plus tard.
        val day = NetflixCsv.noon(NfDate(2026, 11, 4), paris)
        assertEquals(PvView("56 Days", day + 1000, PrimeCsv.EPISODE, 1, 3, "The Plan"), v[1])
        assertEquals(PvView("56 Days", day, PrimeCsv.EPISODE, 1, 2, null), v[2])
        assertEquals(PvView("The Boys", NetflixCsv.noon(NfDate(2026, 10, 1), paris), PrimeCsv.SEASON, 4), v[3])
    }

    @Test fun groupsMoviesEpisodesAndSeasons() {
        val g = PrimeCsv.group(PrimeCsv.parse(csv, paris))
        assertEquals(listOf("A Teacher's Obsession"), g.films.values.map { it.title })
        val days = g.shows.values.associateBy { it.name }
        // Épisodes : saison et numéro directement, aucune analyse de titre.
        val d = days.getValue("56 Days")
        assertEquals(setOf(1 to 3, 1 to 2), d.watches.map { it.season to it.episode }.toSet())
        assertEquals("Saison 1", d.watches[0].label)
        assertEquals(0, d.seasonsOnly)
        assertEquals(2, d.weight)
        // Saison sans détail : aucune ligne d'épisode inventée, graine d'environ deux épisodes.
        val b = days.getValue("The Boys")
        assertTrue(b.watches.isEmpty())
        assertEquals(1, b.seasonsOnly)
        assertEquals(2, b.weight)
        assertEquals(NetflixCsv.noon(NfDate(2026, 10, 1), paris), b.at)
        // Saison vue avec un épisode connu : pas comptée comme « sans détail ».
        assertEquals(0, days.getValue("Reacher").seasonsOnly)
        // Placement direct dans le catalogue.
        val cat = listOf(EpRef(1, 1, "a"), EpRef(1, 2, "b"), EpRef(1, 3, "c"), EpRef(2, 1, "d"))
        val placed = NetflixTitles.mapEpisodes(d.watches, cat)
        assertEquals(setOf(1 to 3, 1 to 2), placed.values.toSet())
    }

    @Test fun seedsKeepTheirSource() {
        val s = listOf(NfSeed("tv:1", "The Boys", 5, 2, Progress.PRIME), NfSeed("movie:2", "Atlas", 3))
        val back = NetflixSeeds.decode(NetflixSeeds.encode(s))
        assertEquals(s, back)
        assertEquals(Progress.NETFLIX, NetflixSeeds.decode("[{\"t\":\"movie:9\",\"n\":\"X\",\"at\":1}]")[0].source)
        val m = NfImportMeta(10, "PrimeVideoViewingHistory.csv", 1, 2, 3, 3, Progress.PRIME)
        assertEquals(m, NetflixImportMeta.decode(NetflixImportMeta.encode(m)))
        assertEquals("p1|prime", NetflixImportMeta.key("p1", Progress.PRIME))
        assertEquals("p1", NetflixImportMeta.key("p1", Progress.NETFLIX))
    }

    @Test fun undoRemovesBothSources() {
        fun row(key: String, source: String?) = Progress(
            key = key, type = Progress.MOVIE, itemId = key, title = key, subtitle = null, poster = null, url = null,
            position = 1, duration = 1, updated = 1, seriesId = null, season = 0, episode = 0, source = source,
        )
        val rows = listOf(row("nf", Progress.NETFLIX), row("pv", Progress.PRIME), row("prisme", null), row("x", "autre"))
        assertEquals(listOf("nf", "pv"), ImportRules.importedKeys(rows, Progress.IMPORTED_SOURCES))
        assertEquals(listOf("pv"), ImportRules.importedKeys(rows, Progress.PRIME))
        assertEquals(" (Netflix 10 · Prime Video 2)", NetflixImport.sourcesLabel(mapOf(Progress.PRIME to 2, Progress.NETFLIX to 10)))
        assertEquals("", NetflixImport.sourcesLabel(mapOf(Progress.PRIME to 2, Progress.NETFLIX to 0)))
    }

    @Test fun importedPrimeRowsLeaveContinueAfter45Days() {
        val now = 1_800_000_000_000L
        val old = Progress(
            key = "movie:1", type = Progress.MOVIE, itemId = "1", title = "A", subtitle = null, poster = null, url = null,
            position = 60_000, duration = 7_200_000, updated = now - 60L * 24 * 3600 * 1000, seriesId = null,
            season = 0, episode = 0, source = Progress.PRIME,
        )
        assertTrue(ImportRules.continueWatching(listOf(old), now, 10).isEmpty())
    }

    private val script = "/*\n * en-tête\n */\n(async () => {\n  // commentaire\n  const a = 'x y';\n  say('Terminé : ' + a);\n})();\n"

    @Test fun bookmarkletUrlIsEncoded() {
        assertEquals("(async () => { const a = 'x y'; say('Terminé : ' + a); })();", PrimeExport.minify(script))
        val url = PrimeExport.bookmarkletUrl(script)
        assertTrue(url.startsWith("javascript:"))
        assertFalse(url.contains('+'))
        assertFalse(url.contains(' '))
        assertTrue(url.contains("%20"))
        assertTrue(url.contains("%2B")) // le « + » du code reste un plus
        assertEquals("void " + PrimeExport.minify(script), URLDecoder.decode(url.removePrefix("javascript:"), "UTF-8"))
    }

    private fun asset(): File? = listOf(File("src/main/assets/prime-export.js"), File("app/src/main/assets/prime-export.js"))
        .firstOrNull { it.exists() }

    @Test fun realScriptMinifiesToOneLine() {
        val f = asset() ?: return
        val min = PrimeExport.minify(f.readText())
        assertFalse(min.contains('\n'))
        assertNull(Regex("""(^|\s)//\s""").find(min))
        assertTrue(min.startsWith("(async () => {"))
        assertTrue(min.endsWith("})();"))
        // La copie de l'asset suit le script de tools/.
        val tools = File(f.parentFile, "../../../../tools/prime-export.js")
        if (tools.exists()) assertEquals(tools.readText(), f.readText())
    }

    /** Régénère tools/prime-export-install.html (page autonome à ouvrir depuis le PC) quand le script change. */
    @Test fun standaloneInstallPageUpToDate() {
        val f = asset() ?: return
        val html = PrimeExport.installPage(f.readText())
        assertTrue(html.contains("href=\"javascript:"))
        assertTrue(html.contains("Copier le code"))
        val out = File(f.parentFile, "../../../../tools/prime-export-install.html")
        if (!out.parentFile.exists()) return
        if (!out.exists() || out.readText() != html) out.writeText(html)
    }
}
