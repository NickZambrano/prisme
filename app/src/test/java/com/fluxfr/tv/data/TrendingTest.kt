package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendingTest {
    @Test fun matchesTitlesWithYearAndPunctuation() {
        val catalog = listOf("Permis de détruire - 2026", "Cat's Eyes", "LUPIN", "Dune (2021)", "Un job mortel", "Autre film")
        val ranked = listOf("Lupin", "Introuvable", "Permis de détruire", "Cat’s Eyes", "Un Job Mortel", "Dune")
        val got = Trending.match(ranked, catalog, { it })
        assertEquals(listOf("LUPIN", "Permis de détruire - 2026", "Cat's Eyes", "Un job mortel", "Dune (2021)"), got)
    }

    @Test fun rejectsOldVersionsOfRecentFilms() {
        val catalog = listOf("Les Misérables - 2019", "Le Fantôme de l'Opéra - 1943", "Karma - 2026", "Lupin")
        val sep = Trending.SEP
        val ranked = listOf("Les Misérables${sep}min:2025", "Le Fantôme de l'Opéra${sep}min:2025", "Karma${sep}min:2025")
        assertEquals(listOf("Karma - 2026"), Trending.match(ranked, catalog, { it }))
        assertEquals(listOf("Lupin"), Trending.match(listOf("Lupin${sep}2021"), catalog, { it }))
    }
}
