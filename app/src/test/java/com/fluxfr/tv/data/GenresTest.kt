package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GenresTest {
    private fun n(s: String?) = Genres.normalize(s)

    @Test fun singleGenresFrenchAndEnglish() {
        assertEquals(listOf("Comédie"), n("Comedy"))
        assertEquals(listOf("Comédie"), n("Comédies"))
        assertEquals(listOf("Comédie"), n("COMEDIE"))
        assertEquals(listOf("Drame"), n("Drama"))
        assertEquals(listOf("Horreur"), n("Épouvante"))
        assertEquals(listOf("Documentaire"), n("Documentaires"))
        assertEquals(listOf("Thriller"), n("Thrillers"))
        assertEquals(listOf("Western"), n("Westerns"))
        assertEquals(listOf("Téléréalité"), n("Télé-réalité"))
        assertEquals(listOf("Science-fiction"), n("Science-Fiction"))
        assertEquals(listOf("Science-fiction"), n("science fiction"))
        assertEquals(listOf("Jeunesse"), n("Kids"))
        assertEquals(listOf("Animation"), n("Dessins animés"))
    }

    @Test fun combosAreSplit() {
        assertEquals(listOf("Action", "Aventure"), n("Action, Aventure"))
        assertEquals(listOf("Action", "Aventure"), n("Action & Adventure"))
        assertEquals(listOf("Science-fiction", "Fantastique"), n("Sci-Fi & Fantasy"))
        assertEquals(listOf("Drame", "Romance"), n("Drame / Romance"))
        assertEquals(listOf("Animation", "Jeunesse"), n("Animation (jeunesse)"))
        assertEquals(listOf("Guerre"), n("War & Politics"))
        assertEquals(listOf("Science-fiction", "Fantastique"), n("Science-Fiction et Fantastique"))
        assertEquals(listOf("Action", "Thriller"), n("Action Thriller"))
        assertEquals(listOf("Comédie", "Drame"), n("Comédie dramatique"))
        assertEquals(listOf("Crime", "Drame", "Mystère"), n("Crime|Drame|Mystère"))
    }

    @Test fun unknownIsIgnored() {
        assertEquals(emptyList<String>(), n(null))
        assertEquals(emptyList<String>(), n(""))
        assertEquals(emptyList<String>(), n("Talk-show"))
        assertEquals(listOf("Drame"), n("Drame, Soap, News"))
    }

    @Test fun normalizeAllDeduplicates() {
        assertEquals(listOf("Action", "Drame"), Genres.normalizeAll(listOf("Action", "Drama, Action", null)))
    }
}
