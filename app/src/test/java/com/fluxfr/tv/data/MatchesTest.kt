package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchesTest {
    private fun d(title: String, desc: String? = null, sports: Boolean = false) =
        MatchDetector.detect(title, desc, sports)

    @Test fun competitionWithTeams() {
        val g = d("Football : Ligue 1 - PSG / OM")!!
        assertEquals("PSG – OM", g.title)
        assertEquals("Ligue 1", g.competition)

        val c = d("Ligue des champions : Real Madrid - Manchester City")!!
        assertEquals("Real Madrid – Manchester City", c.title)
        assertEquals("Ligue des champions", c.competition)

        assertEquals("Top 14", d("Rugby - Top 14 - Toulouse / Racing 92")!!.competition)
        assertEquals("Toulouse – Racing 92", d("Rugby - Top 14 - Toulouse / Racing 92")!!.title)
    }

    @Test fun versusAloneIsEnough() {
        val g = d("Lakers vs Celtics")!!
        assertEquals("Lakers – Celtics", g.title)
        assertEquals("Sport", g.competition)
    }

    @Test fun teamsOnSportsChannelOrWithSportDescription() {
        assertNotNull(d("Lens - Lille", sports = true))
        assertNull(d("Lens - Lille"))
        assertEquals("Football", d("Lens - Lille", desc = "Match de football comptant pour la 12e journée")!!.competition)
    }

    @Test fun keywordsWithoutTeams() {
        assertEquals("Formule 1", d("Formule 1 : Grand Prix de Monaco")!!.competition)
        assertEquals("Roland-Garros", d("Roland-Garros : 1/4 de finale")!!.competition)
        assertEquals("NBA", d("NBA")!!.competition)
        assertEquals("Six Nations", d("Tournoi des Six Nations : France / Irlande")!!.competition)
        assertEquals("France – Irlande", d("Tournoi des Six Nations : France / Irlande")!!.title)
        assertEquals("Euro", d("Euro 2028 : Allemagne - France")!!.competition)
        assertEquals("Premier League", d("Premier League : Arsenal v. Chelsea")!!.competition)
    }

    @Test fun multiplexIsAllowed() {
        assertEquals("Ligue 1", d("Multiplex Ligue 1")!!.competition)
        assertNotNull(d("Multiplex", sports = true))
    }

    @Test fun magazinesAreExcluded() {
        assertNull(d("Téléfoot"))
        assertNull(d("Canal Football Club", sports = true))
        assertNull(d("Jour de foot", sports = true))
        assertNull(d("L'Équipe du soir", sports = true))
        assertNull(d("Ligue 1 : le résumé", sports = true))
    }

    @Test fun ordinaryProgramsAreNotMatches() {
        assertNull(d("Les Simpson - Épisode 3"))
        assertNull(d("Joséphine, ange gardien - Le cadeau"))
        assertNull(d("Journal de 20 heures"))
        assertNull(d("Euromillions"))
        assertNull(d("Koh-Lanta : la finale"))
        assertNull(d("Match Point")) // un film sur une chaîne généraliste
    }

    @Test fun genericSportWordOnlyOnSportsChannels() {
        assertNull(d("Football"))
        assertEquals("Football", d("Football", sports = true)!!.competition)
    }

    @Test fun teamSidesRejectRoundWords() {
        // « Demi-finale » n'est pas une équipe : on garde la paire précédente.
        assertEquals("France – Italie", d("Coupe du monde : France / Italie - Demi-finale")!!.title)
        assertNull(MatchDetector.teams("2024 - 2025"))
    }

    @Test fun sportsChannelNames() {
        assertTrue(MatchDetector.isSportsChannelName("CANAL+ SPORT"))
        assertTrue(MatchDetector.isSportsChannelName("CANAL+"))
        assertTrue(MatchDetector.isSportsChannelName("beIN SPORTS 1"))
        assertTrue(MatchDetector.isSportsChannelName("RMC Sport 2"))
        assertTrue(MatchDetector.isSportsChannelName("Eurosport 1"))
        assertTrue(MatchDetector.isSportsChannelName("L'Équipe"))
        assertTrue(MatchDetector.isSportsChannelName("DAZN 1"))
        assertFalse(MatchDetector.isSportsChannelName("Canal J"))
        assertFalse(MatchDetector.isSportsChannelName("TF1"))
    }

    @Test fun dedupeKeepsFirstChannel() {
        fun ch(id: String) = Channel(id, id, "Sport", null, Quality.HD, emptyList(), 0)
        val start = 1_700_000_000_000L
        val a = Match("PSG – OM", "Ligue 1", start, start + 7_200_000, ch("canal"))
        val b = Match("PSG - OM", "Ligue 1", start + 60_000, start + 7_200_000, ch("bein"))
        val out = Matches.dedupe(listOf(a, b))
        assertEquals(listOf(a), out)
    }
}
