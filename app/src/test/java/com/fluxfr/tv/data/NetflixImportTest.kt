package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NetflixImportTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")

    // ------------------------------------------------------------------ CSV

    @Test
    fun csvHandlesQuotesCommasBomAndCrlf() {
        val text = "﻿Title,Date\r\n\"Film, avec virgule\",\"3/4/25\"\r\n\"Dit \"\"bonjour\"\"\",3/5/25\r\n\r\n"
        val rows = NetflixCsv.rows(text)
        assertEquals(listOf("Title", "Date"), rows[0])
        assertEquals(listOf("Film, avec virgule", "3/4/25"), rows[1])
        assertEquals(listOf("Dit \"bonjour\"", "3/5/25"), rows[2])
        assertEquals(3, rows.size)
    }

    @Test
    fun csvMultilineQuotedField() {
        val rows = NetflixCsv.rows("a,\"b\nc\"\nd,e")
        assertEquals(listOf(listOf("a", "b\nc"), listOf("d", "e")), rows)
    }

    @Test
    fun datesUsByDefaultDayFirstWhenNeeded() {
        assertEquals(listOf(NfDate(2026, 11, 7), NfDate(2026, 1, 2)), NetflixCsv.parseDates(listOf("11/7/26", "1/2/26")))
        // Un premier nombre > 12 : tout le fichier est en J/M/AAAA.
        assertEquals(
            listOf(NfDate(2025, 3, 25), NfDate(2025, 4, 1)),
            NetflixCsv.parseDates(listOf("25/03/2025", "01/04/2025")),
        )
        assertEquals(listOf(NfDate(2024, 12, 31)), NetflixCsv.parseDates(listOf("2024-12-31")))
        assertEquals(listOf<NfDate?>(null), NetflixCsv.parseDates(listOf("hier")))
    }

    @Test
    fun parseKeepsOrderWithinADay() {
        val text = "Title,Date\n\"B: Saison 1: Épisode 2\",\"5/2/25\"\n\"B: Saison 1: Épisode 1\",\"5/2/25\"\n\"A\",\"5/1/25\"\n"
        val v = NetflixCsv.parse(text, paris)
        assertEquals(3, v.size)
        val noon = NetflixCsv.noon(NfDate(2025, 5, 2), paris)
        assertEquals(noon + 1000, v[0].at) // vu après l'épisode 1, le même jour
        assertEquals(noon, v[1].at)
        assertTrue(v[2].at < v[1].at)
        // Même fichier → mêmes instants (réimport sans doublon).
        assertEquals(v, NetflixCsv.parse(text, paris))
    }

    @Test
    fun parseWithoutHeaderAndBadRows() {
        val v = NetflixCsv.parse("\"Film\",\"1/2/25\"\n\"\",\"1/2/25\"\n\"Autre\",\"???\"\n", paris)
        assertEquals(listOf("Film"), v.map { it.title })
    }

    // ------------------------------------------------------------------ Titres

    @Test
    fun segmentsKeepFrenchSpacedColon() {
        assertEquals(listOf("Mission : Impossible"), NetflixTitles.segments("Mission : Impossible"))
        assertEquals(
            listOf("Avatar : Le maître", "Saison 2", "La brisure"),
            NetflixTitles.segments("Avatar : Le maître: Saison 2: La brisure"),
        )
    }

    @Test
    fun classifySeasonMarkers() {
        assertEquals(NfTitle.Ep("Lupin", "Partie 4", 4, 5, "Chapitre 5"), NetflixTitles.classify("Lupin: Partie 4: Chapitre 5"))
        assertEquals(NfTitle.Ep("Échange", "Mini-série", 1, 4, "Épisode 4"), NetflixTitles.classify("Échange: Mini-série: Épisode 4"))
        assertEquals(
            NfTitle.Ep("La Diplomate", "Saison 4", 4, null, "Navires de guerre"),
            NetflixTitles.classify("La Diplomate: Saison 4: Navires de guerre"),
        )
        assertEquals(NfTitle.Ep("Show", "Limited Series", 1, 1, "Episode 1"), NetflixTitles.classify("Show: Limited Series: Episode 1"))
        val st = NetflixTitles.classify("Choses: Choses 4: Chapitre un : Le début") as NfTitle.Ep
        assertEquals(4, st.season)
        assertEquals(1, st.episode)
        assertEquals("Choses", st.series)
        val av = NetflixTitles.classify("Avatar : Le maître: Saison 2: La brisure") as NfTitle.Ep
        assertEquals("Avatar : Le maître", av.series)
        assertEquals(2, av.season)
        assertEquals(3, (NetflixTitles.classify("X: Livre III: Le feu") as NfTitle.Ep).season)
        // Netflix écrit « Saison 4 » avec une espace insécable.
        assertEquals(
            NfTitle.Ep("Série", "Partie 4", 4, 5, "Chapitre 5"),
            NetflixTitles.classify("Série: Partie 4: Chapitre 5"),
        )
        assertEquals("A: B: Saison 1: C", "A: B: Saison 1: C")
        assertEquals("A: B", (NetflixTitles.classify("A: B: Saison 1: C") as NfTitle.Ep).series)
    }

    @Test
    fun classifyFilmsAndSeasonlessSeries() {
        assertEquals(NfTitle.Film("Atlas"), NetflixTitles.classify("Atlas"))
        assertEquals(NfTitle.Film("Mission : Impossible"), NetflixTitles.classify("Mission : Impossible"))
        assertEquals(NfTitle.Film("Jeu: un aperçu"), NetflixTitles.classify("Jeu: un aperçu"))
        // « Épisode N » sans saison.
        assertEquals(NfTitle.Ep("GIGN", null, null, 6, "Épisode 6"), NetflixTitles.classify("GIGN: Épisode 6"))
        // Série du catalogue (plus long préfixe).
        assertEquals(
            NfTitle.Ep("Recalé", null, null, null, "Traqué"),
            NetflixTitles.classify("Recalé: Traqué", isSeries = { it == "Recalé" }),
        )
        // Préfixe répété dans le fichier.
        val rep = NetflixTitles.repeatedPrefixes(listOf("Lionnes: Une somme", "Lionnes: Les armes", "Atlas"))
        assertTrue(NetflixTitles.classify("Lionnes: Une somme", repeated = rep) is NfTitle.Ep)
        // … sauf si le titre complet est un film du catalogue.
        assertTrue(NetflixTitles.classify("Lionnes: Une somme", isMovie = { true }, repeated = rep) is NfTitle.Film)
    }

    @Test
    fun numbersAndEpisodeNumbers() {
        assertEquals(4, NetflixTitles.number("IV"))
        assertEquals(7, NetflixTitles.number("sept"))
        assertEquals(12, NetflixTitles.number("12"))
        assertNull(NetflixTitles.number("Navires"))
        assertEquals(9, NetflixTitles.episodeNumber("Chapitre neuf : L'infiltration"))
        assertEquals(3, NetflixTitles.episodeNumber("Épisode 3"))
        assertNull(NetflixTitles.episodeNumber("Le dernier épisode"))
        assertEquals(2, NetflixTitles.seasonOf("Season 2"))
        assertEquals(1, NetflixTitles.seasonOf("Mini-série"))
        assertNull(NetflixTitles.seasonOf("Les fleurs"))
    }

    // ------------------------------------------------------------------ Épisodes

    private val cat = (1..2).flatMap { s -> (1..4).map { n -> EpRef(s, n, "Épisode $n") } }

    @Test
    fun mapEpisodesByNumberNameAndOrder() {
        val names = mapOf(2 to listOf(1 to "Le départ", 2 to "Les fleurs de la passion", 3 to "Racine magique", 4 to "La fin"))
        val a = EpWatch("Saison 2", 2, null, "Racine magique", 30)
        val b = EpWatch("Saison 2", 2, null, "Les fleurs de la passion", 20)
        val c = EpWatch("Saison 1", 1, 3, "Épisode 3", 10)
        val d1 = EpWatch("Saison 1", 1, null, "Titre inconnu A", 1)
        val d2 = EpWatch("Saison 1", 1, null, "Titre inconnu B", 2)
        val r = NetflixTitles.mapEpisodes(listOf(a, b, c, d1, d2), cat, { names[it].orEmpty() })
        assertEquals(2 to 3, r[a])
        assertEquals(2 to 2, r[b])
        assertEquals(1 to 3, r[c])
        // Sans numéro ni nom reconnu : ordre chronologique sur les numéros libres.
        assertEquals(1 to 1, r[d1])
        assertEquals(1 to 2, r[d2])
    }

    @Test
    fun mapEpisodesUnknownSeasonByName() {
        val names = mapOf(1 to listOf(1 to "Alpha"), 2 to listOf(1 to "Bravo", 2 to "Charlie"))
        val w = EpWatch("Une histoire", null, null, "Charlie", 5)
        val lost = EpWatch("Une histoire", null, null, "Zoulou", 6)
        val r = NetflixTitles.mapEpisodes(listOf(w, lost), cat, { names[it].orEmpty() }, { listOf(1, 2) })
        assertEquals(2 to 2, r[w])
        assertFalse(r.containsKey(lost))
        // Sans marqueur, une seule saison au catalogue : saison 1.
        val one = listOf(EpRef(1, 1, "x"), EpRef(1, 2, "y"))
        val s = EpWatch(null, null, null, "Seize ans", 1)
        assertEquals(1 to 1, NetflixTitles.mapEpisodes(listOf(s), one)[s])
    }

    @Test
    fun fuzzyEpisodeMatch() {
        val c = listOf(1 to "La mère araignée", 2 to "Mauvaise graine", 3 to "Un de moins")
        assertEquals(1, NetflixTitles.fuzzyEpisode("La Mere Araignee", c))
        assertEquals(2, NetflixTitles.fuzzyEpisode("Mauvaise graine (partie 1)", c))
        assertNull(NetflixTitles.fuzzyEpisode("Rien à voir", c))
    }

    @Test
    fun titleKeyStripsCatalogPrefixAccentsAndArticles() {
        assertEquals("bal", NetflixTitles.titleKey("Mon École - S01E08 - Le Bal"))
        assertEquals("petite epee", NetflixTitles.titleKey("La Petite Épée !"))
        assertEquals(NetflixTitles.titleKey("L'aura, l'aura pas"), NetflixTitles.titleKey("L aura l aura pas"))
    }

    @Test
    fun twoSegmentTitlesSearchAllSeasons() {
        // « Série: Titre » sans saison : noms cherchés dans toutes les saisons (titres du catalogue préfixés).
        // Saison 2 : 1 « Le retour », 2 « La tempête », 3 « Le phare ».
        val names = listOf("Le départ", "La forêt", "Le pont", "Le retour", "La tempête", "Le phare")
        val cat = (1..2).flatMap { s -> (1..3).map { n -> EpRef(s, n, "Ma Série - S0${s}E0$n - ${names[(s - 1) * 3 + n - 1]}") } }
        val a = EpWatch(null, null, null, "Le retour", 10)
        val b = EpWatch(null, null, null, "Titre inconnu", 20)
        val c = EpWatch(null, null, null, "la tempete", 5)
        val r = NetflixTitles.mapEpisodes(listOf(c, b, a), cat)
        assertEquals(2 to 1, r[a])
        assertEquals(2 to 2, r[c])
        // Sans nom reconnu : la saison des autres épisodes, après le plus haut épisode vu avant lui.
        assertEquals(2 to 3, r[b])
    }

    @Test
    fun fuzzyMatchFrenchAndEnglishTmdbNames() {
        val cat = (1..4).map { EpRef(1, it, "Episode $it") }
        val fr = mapOf(1 to listOf(1 to "La perfection", 2 to "Papa est rentré", 3 to "La pendaison de crémaillère", 4 to "Le grand plongeon"))
        val en = mapOf(1 to listOf(1 to "It's Perfect", 2 to "Daddy's Home", 3 to "Housewarming", 4 to "The Plunge"))
        val w1 = EpWatch("Saison 1", 1, null, "Pendaison de cremaillere", 1)
        val w2 = EpWatch("Saison 1", 1, null, "Daddys Home", 2)
        val w3 = EpWatch("Saison 1", 1, null, "The Plunge", 3)
        val r = NetflixTitles.mapEpisodes(listOf(w1, w2, w3), cat, { fr[it].orEmpty() }, { listOf(1) }, { en[it].orEmpty() })
        assertEquals(1 to 3, r[w1])
        assertEquals(1 to 2, r[w2])
        assertEquals(1 to 4, r[w3])
        assertTrue(NetflixTitles.similarity(NetflixTitles.titleKey("Le grand plongeon"), NetflixTitles.titleKey("Le grand plongeons")) >= NetflixTitles.FUZZY_MIN)
        assertNull(NetflixTitles.fuzzyEpisode("Le grand saut", fr.getValue(1)))
    }

    @Test
    fun countBasedSeasonCompletion() {
        val cat = (1..4).map { EpRef(1, it, "Episode $it") } + EpRef(2, 1, "Episode 1")
        val names = mapOf(1 to listOf(1 to "Alpha", 2 to "Le grand plongeon", 3 to "Gamma", 4 to "Delta"))
        // Deux visionnages désignent le même épisode : sans le compte, un épisode resterait non vu.
        val ws = listOf(
            EpWatch("Saison 1", 1, null, "Le grand plongeon", 10),
            EpWatch("Saison 1", 1, null, "Grand plongeon !", 20),
            EpWatch("Saison 1", 1, null, "Inconnu A", 30),
            EpWatch("Saison 1", 1, null, "Inconnu B", 40),
        )
        val r = NetflixTitles.mapEpisodes(ws, cat, { names[it].orEmpty() }, seasonCount = { if (it == 1) 4 else null })
        assertEquals(setOf(1, 2, 3, 4), r.values.filter { it.first == 1 }.map { it.second }.toSet())
        // Moins de visionnages que d'épisodes : pas de complétion.
        val r2 = NetflixTitles.mapEpisodes(ws.take(3), cat, { names[it].orEmpty() }, seasonCount = { 4 })
        assertEquals(2, r2.values.map { it.second }.toSet().size)
    }

    @Test
    fun homonymPickedByEpisodeNamesThenRecency() {
        assertEquals(1, NetflixTitles.pickHomonym(listOf(0 to 1987, 6 to 2026), 2026))
        assertEquals(1, NetflixTitles.pickHomonym(listOf(0 to 1987, 0 to 2026), 2026))
        assertEquals(0, NetflixTitles.pickHomonym(listOf(0 to 1987, 0 to 2030), 2026))
    }

    // ------------------------------------------------------------------ Fusion et « Continuer »

    private fun ep(key: String, sid: String, s: Int, e: Int, updated: Long, finished: Boolean = true, source: String? = Progress.NETFLIX) =
        Progress(key, Progress.EPISODE, key, "T", null, null, null, if (finished) 100 else 10, 100, updated, sid, s, e, source = source)

    @Test
    fun finishedSeriesLeaveContinueWatching() {
        val now = 10_000L
        val last = mapOf("done" to ImportRules.code(1, 8), "partial" to ImportRules.code(1, 10), "newSeason" to ImportRules.code(2, 3))
        val rows = listOf(
            ep("ep:d8", "done", 1, 8, 900),
            ep("ep:p6", "partial", 1, 6, 800),
            ep("ep:u5", "unknown", 1, 5, 700),
            ep("ep:n8", "newSeason", 1, 8, 600),
            ep("ep:d7", "done", 1, 7, 500),
        )
        val maxDone = rows.groupBy { it.seriesId!! }.mapValues { (_, l) -> l.maxOf { ImportRules.code(it.season, it.episode) } }
        val out = ImportRules.continueWatching(rows, now, 10) { p -> ImportRules.seriesFinished(p, maxDone[p.seriesId], last[p.seriesId]) }
        // Terminée (dernier épisode connu vu) : absente ; partielle, inconnue, nouvelle saison au catalogue : présentes.
        assertEquals(listOf("ep:p6", "ep:u5", "ep:n8"), out.map { it.key })
        // Dernier épisode entamé (pas fini) : la série reste à reprendre.
        assertFalse(ImportRules.seriesFinished(ep("ep:x", "done", 1, 8, 1, finished = false), ImportRules.code(1, 8), last["done"]))
        // Saison complète (épisode le plus haut vu = dernier) même si la ligne la plus récente est plus tôt.
        assertTrue(ImportRules.seriesFinished(ep("ep:y", "done", 1, 3, 1), ImportRules.code(1, 8), last["done"]))
    }

    @Test
    fun reimportReplacesWrongMapping() {
        val existing = listOf(
            ep("ep:wrong", "s1", 1, 1, 100),                          // import précédent : mauvais épisode
            ep("ep:right", "s1", 1, 8, 100),                          // même clé que la nouvelle correspondance
            ep("ep:local", "s1", 1, 2, 50, source = null),            // lecture dans Prisme : jamais supprimée
            ep("ep:prime", "s1", 1, 3, 50, source = Progress.PRIME),  // autre origine : gardée
            ep("ep:other", "s2", 1, 1, 100),                          // autre série : gardée
        )
        val incoming = listOf(ep("ep:right", "s1", 1, 8, 100), ep("ep:local", "s1", 1, 2, 40))
        val plan = ImportRules.reimportPlan(existing, incoming, setOf("s1"), Progress.NETFLIX)
        assertEquals(listOf("ep:wrong", "ep:right"), plan.delete)
        assertEquals(listOf("ep:right"), plan.write.map { it.key }) // ligne locale plus récente : pas écrasée
        assertEquals(listOf("ep:wrong"), plan.removed)
        // Sans série à remplacer : comportement d'avant (même date → rien d'écrit).
        assertTrue(ImportRules.reimportPlan(existing, incoming, emptySet(), Progress.NETFLIX).write.isEmpty())
    }

    // ------------------------------------------------------------------ Fusion et « Continuer » (suite)

    private fun row(key: String, updated: Long, source: String? = Progress.NETFLIX, type: String = Progress.EPISODE, sid: String? = "s1") =
        Progress(key, type, key, "T", null, null, null, 100, 100, updated, sid, 1, 1, source = source)

    @Test
    fun mergeNeverOverwritesNewerOrSame() {
        val imp = row("ep:1", 1_000)
        assertTrue(ImportRules.shouldWrite(null, imp))
        assertFalse(ImportRules.shouldWrite(row("ep:1", 1_000), imp)) // réimport : identique
        assertFalse(ImportRules.shouldWrite(row("ep:1", 2_000, source = null), imp)) // ligne locale plus récente
        assertTrue(ImportRules.shouldWrite(row("ep:1", 500, source = null), imp))
    }

    @Test
    fun continueSkipsOldImports() {
        val day = 86_400_000L
        val now = 100 * day
        val rows = listOf(
            row("ep:a", now - 2 * day, sid = "recent"),
            row("ep:b", now - 60 * day, sid = "old"),
            row("ep:c", now - 61 * day, source = null, sid = "recent"),
            row("ep:d", now - 70 * day, source = null, sid = "localOld"),
        )
        val out = ImportRules.continueWatching(rows, now, 10)
        assertEquals(listOf("ep:a", "ep:d"), out.map { it.key })
    }

    @Test
    fun driveCodecKeepsSource() {
        val p = row("ep:x", 5)
        assertEquals(Progress.NETFLIX, DriveCodec.rowOf(DriveCodec.rowMap(p))?.source)
        assertFalse(DriveCodec.rowMap(row("ep:y", 5, source = null)).containsKey("source"))
    }

    // ------------------------------------------------------------------ Graines

    @Test
    fun seedsRoundTripMergeAndWeights() {
        val a = NfSeed("movie:1", "A", 100)
        val b = NfSeed("tv:2", "B", 200, 5)
        val merged = NetflixSeeds.merge(listOf(a), listOf(a.copy(at = 300), b))
        assertEquals(2, merged.size)
        assertEquals(300L, merged.first { it.tmdb == "movie:1" }.at)
        assertEquals(merged, NetflixSeeds.decode(NetflixSeeds.encode(merged)))
        val seeds = NetflixSeeds.recoSeeds(listOf(NfSeed("movie:1", "A", 0), NfSeed("tv:2", "B", 0, 2)), 0)
        assertEquals("tmdb:movie:1", seeds[0].key)
        assertEquals(1.0, seeds[0].weight, 1e-9)
        assertEquals(0.7, seeds[1].weight, 1e-9)
    }
}
