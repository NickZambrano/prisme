package com.fluxfr.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    @Test fun ratingOneDecimalWithComma() {
        assertEquals("7,6", HomeRows.formatRating("7.62"))
        assertEquals("7,6", HomeRows.formatRating("7,6"))
        assertEquals("8,0", HomeRows.formatRating("8"))
        assertEquals("7,6", HomeRows.formatRating("76"))
        assertNull(HomeRows.formatRating("0"))
        assertNull(HomeRows.formatRating(""))
        assertNull(HomeRows.formatRating(null))
        assertNull(HomeRows.formatRating("n/a"))
    }

    @Test fun providerCategoriesAreNotGenres() {
        assertEquals(emptyList<String>(), HomeRows.heroGenres(null, "LATEST MOVIES"))
        assertEquals(emptyList<String>(), HomeRows.heroGenres(emptyList(), "AMAZON PRIME"))
        assertEquals(emptyList<String>(), HomeRows.heroGenres(null, "FILMS"))
        assertEquals(listOf("Thriller", "Drame"), HomeRows.heroGenres(listOf("Thriller", "Drame", "Crime"), "FILMS"))
        assertEquals(listOf("Action"), HomeRows.heroGenres(null, "Action"))
    }

    @Test fun metaText() {
        assertEquals("2023  •  Thriller, Drame  •  ★ 7,6", HomeRows.metaText(null, 2023, listOf("Thriller", "Drame"), "7.6"))
        assertEquals("Série  •  2019", HomeRows.metaText("Série", 2019, emptyList(), "0"))
        assertNull(HomeRows.metaText(null, null, emptyList(), null))
    }

    @Test fun dedupeAcrossRowsInDisplayOrder() {
        val seen = hashSetOf("m:1")
        val row = (1..8).map { "m:$it" }
        val kept = HomeRows.dedupeRow(row, seen, { listOf(it) })
        assertEquals((2..8).map { "m:$it" }, kept)
        assertTrue("m:5" in seen)
        // Rangée suivante : tout est déjà montré sauf 2 titres → moins de 6 : masquée, [seen] inchangé.
        val next = (5..10).map { "m:$it" }
        assertEquals(emptyList<String>(), HomeRows.dedupeRow(next, seen, { listOf(it) }))
        assertFalse("m:9" in seen)
    }

    @Test fun sameTitleDifferentVersions() {
        assertEquals(HomeRows.titleKey('m', "Finestkind - 2023"), HomeRows.titleKey('m', "FINESTKIND (2023) [4K]"))
    }

    @Test fun placeholderChannels() {
        assertTrue(HomeRows.isPlaceholderChannel("PSG VS OM", "FR| SPORT"))
        assertTrue(HomeRows.isPlaceholderChannel("MULTI SCREEN 3", "FR"))
        assertTrue(HomeRows.isPlaceholderChannel("LIGUE 1 20:45 Lens - Lille", "FR"))
        assertTrue(HomeRows.isPlaceholderChannel("PPV 12", "FR"))
        assertTrue(HomeRows.isPlaceholderChannel("DAZN 3", "FR | SPORTS EVENTS"))
        assertFalse(HomeRows.isPlaceholderChannel("TF1 HD", "FR| GENERALISTE"))
        assertFalse(HomeRows.isPlaceholderChannel("beIN SPORTS 1 FHD", "FR| SPORTS"))
        assertFalse(HomeRows.isPlaceholderChannel("France 24", "FR| INFO"))
    }

    @Test fun shownTitleDropsLeftoverYearAndTags() {
        assertEquals("Finestkind", HomeRows.shownTitle("Finestkind - 2023"))
        assertEquals("Avatar 3", HomeRows.shownTitle("Avatar 3 - 2026 - VF"))
        assertEquals("Avatar 3", HomeRows.shownTitle("Avatar 3 (2026) MULTI 4K"))
        assertEquals("Blade Runner 2049", HomeRows.shownTitle("Blade Runner 2049"))
        assertEquals("Blade Runner 2049", HomeRows.shownTitle("Blade Runner 2049 - 2017"))
        assertEquals("1917", HomeRows.shownTitle("1917"))
    }

    @Test fun rowOrderStaysStableWhileRowsArrive() {
        val order = ArrayList<String>()
        assertEquals(listOf("Crime", "Drame"), HomeRows.stableOrder(order, listOf("Crime", "Drame")) { it })
        // Le classement continue : les comptes changent l'ordre « naturel », pas l'ordre affiché.
        assertEquals(
            listOf("Crime", "Drame", "Science-fiction"),
            HomeRows.stableOrder(order, listOf("Science-fiction", "Drame", "Crime")) { it },
        )
        // Une rangée disparue laisse les autres à leur place relative.
        assertEquals(listOf("Crime", "Science-fiction"), HomeRows.stableOrder(order, listOf("Science-fiction", "Crime")) { it })
    }
}
