package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SagasTest {
    private var n = 0
    private fun m(name: String, q: Quality = Quality.HD) =
        Movie("id${n++}", name, "Films", null, q, "http://x/$n.mp4", 0L, null)

    private fun compute(vararg movies: Movie) = Sagas.compute(movies.toList()) { Sagas.yearFromName(it.name) }

    @Test fun harryPotter() {
        val idx = compute(
            m("Harry Potter et la Chambre des secrets - 2002"),
            m("Harry Potter à l'école des sorciers - 2001"),
            m("Harry Potter et le Prisonnier d'Azkaban (2004)"),
            m("Harry Potter et la Coupe de feu - 2005"),
            m("Harry Potter et les Reliques de la Mort : 1re partie - 2010"),
            m("Harry Potter et les Reliques de la Mort : 2e partie - 2011"),
            m("Le Roi Lion"),
        )
        assertEquals(1, idx.sagas.size)
        val s = idx.sagas[0]
        assertEquals("Harry Potter", s.name)
        assertEquals(6, s.movies.size)
        assertTrue(s.movies.first().name.startsWith("Harry Potter à l'école"))
        assertTrue(s.movies.last().name.contains("2e partie"))
    }

    @Test fun starWarsEpisodes() {
        val idx = compute(
            m("Star Wars : Episode III - La Revanche des Sith"),
            m("Star Wars : Episode I - La Menace fantôme"),
            m("Star Wars : Episode II - L'Attaque des clones"),
            m("Star Wars : Episode IV - Un nouvel espoir"),
            m("Star Wars : Episode IX - L'Ascension de Skywalker"),
        )
        assertEquals(1, idx.sagas.size)
        val s = idx.sagas[0]
        assertEquals("Star Wars", s.name)
        assertEquals(
            listOf("I", "II", "III", "IV", "IX"),
            s.movies.map { it.name.substringAfter("Episode ").substringBefore(" ") },
        )
    }

    @Test fun leParrain() {
        val idx = compute(m("Le Parrain 3"), m("Le Parrain"), m("Le Parrain 2"), m("Le Parrain 2", Quality.UHD))
        assertEquals(1, idx.sagas.size)
        val s = idx.sagas[0]
        assertEquals("Le Parrain", s.name)
        assertEquals(listOf("Le Parrain", "Le Parrain 2", "Le Parrain 3"), s.movies.map { it.name })
        // La version 4K est gardée, l'autre version renvoie aussi à la saga.
        assertEquals(Quality.UHD, s.movies[1].quality)
        assertEquals(4, idx.byMovieId.size)
    }

    @Test fun missionImpossible() {
        val idx = compute(
            m("Mission : Impossible - 1996"),
            m("Mission : Impossible 2 - 2000"),
            m("Mission : Impossible - Fallout - 2018"),
            m("Mission : Impossible - Protocole fantôme - 2011"),
        )
        assertEquals(1, idx.sagas.size)
        assertEquals("Mission : Impossible", idx.sagas[0].name)
        assertEquals(4, idx.sagas[0].movies.size)
    }

    @Test fun genericPrefixesAreNotSagas() {
        val idx = compute(
            m("Le Roi Lion"), m("Le Roi Arthur"), m("Le Roi Scorpion"),
            m("Les Aventures de Tintin"), m("Les Aventures de Rabbi Jacob"), m("Les Aventures de Paddington"),
            m("La Vie est belle"), m("La Vie d'Adèle"), m("La Vie scolaire"),
        )
        assertTrue(idx.sagas.isEmpty())
    }

    @Test fun sameFirstWordWithDifferentComplementsIsNotASaga() {
        val idx = compute(
            m("L'Odyssée - 2016"),
            m("L'odyssée de Pi - 2012"),
            m("L'Odyssée de Choum - 2020"),
            m("L'odyssée d'Alice Tremblay - 2002"),
            m("L'odyssée d'Alice Tremblay"),
        )
        assertTrue(idx.sagas.isEmpty())
        assertTrue(idx.byMovieId.isEmpty())
    }

    @Test fun singleWordPrefixNeedsSequelMarkers() {
        val idx = compute(
            m("Scream - 1996"), m("Scream 2 - 1997"), m("Scream 3 - 2000"),
            m("Scream des profondeurs"), m("Scream de minuit"),
        )
        assertEquals(1, idx.sagas.size)
        assertEquals(listOf("Scream - 1996", "Scream 2 - 1997", "Scream 3 - 2000"), idx.sagas[0].movies.map { it.name })
    }

    @Test fun sameFilmTwiceIsShownOnceWithItsPoster() {
        val noPoster = m("Le Parrain 2")
        val withPoster = Movie("pp", "Le Parrain 2 - 1974", "Films", "http://x/p.jpg", Quality.HD, "http://x/pp.mp4", 0L, null)
        val idx = compute(m("Le Parrain - 1972"), noPoster, withPoster, m("Le Parrain 3 - 1990"))
        assertEquals(1, idx.sagas.size)
        val s = idx.sagas[0]
        assertEquals(3, s.movies.size)
        assertEquals("pp", s.movies[1].id)
        // L'exemplaire sans affiche renvoie quand même à la saga.
        assertEquals(s, idx.byMovieId[noPoster.id])
    }

    @Test fun knownTmdbCollectionsWin() {
        val movies = listOf(
            m("Le Parrain - 1972"), m("Le Parrain 2 - 1974"), m("Le Parrain 3 - 1990"), m("Le Parrain 4 - 2026"),
        )
        // Le 4e « Parrain » (homonyme) n'est pas dans la saga TMDB ; les 3 autres, si.
        val coll = mapOf(movies[0].id to "230", movies[1].id to "230", movies[2].id to "230", movies[3].id to "")
        val idx = Sagas.compute(movies, collectionOf = { coll[it.id] }) { Sagas.yearFromName(it.name) }
        assertEquals(1, idx.sagas.size)
        assertEquals(3, idx.sagas[0].movies.size)
        assertNull(idx.byMovieId[movies[3].id])
        // Rien de connu : heuristique des titres seule.
        val none = Sagas.compute(movies, collectionOf = { null }) { Sagas.yearFromName(it.name) }
        assertEquals(4, none.sagas[0].movies.size)
    }

    @Test fun twoFilmsAreNotEnough() {
        val idx = compute(m("Dune - 2021"), m("Dune : Deuxième partie - 2024"))
        assertTrue(idx.sagas.isEmpty())
        assertNull(idx.byMovieId["id0"])
    }
}
