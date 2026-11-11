package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTitleTest {
    @Test fun stripsYearSuffix() {
        assertEquals("Finestkind", DisplayTitle.of("Finestkind - 2023"))
        assertEquals("Vaiana, la légende du bout du monde", DisplayTitle.of("Vaiana, la légende du bout du monde (2026)"))
        assertEquals(2023, DisplayTitle.year("Finestkind - 2023"))
        assertEquals(2026, DisplayTitle.year("Vaiana (2026)"))
    }

    @Test fun stripsLanguagePrefixAndTags() {
        assertEquals("Megalomaniac", DisplayTitle.of("EN - Megalomaniac [MULTI-SUB] - 2023"))
        assertEquals(2023, DisplayTitle.year("EN - Megalomaniac [MULTI-SUB] - 2023"))
        assertEquals("Dune", DisplayTitle.of("Dune [4K] - 2021"))
    }

    @Test fun keepsRealTitles() {
        // Un nombre qui fait partie du titre n'est pas une année de sortie finale.
        assertEquals("Blade Runner 2049", DisplayTitle.of("Blade Runner 2049"))
        assertEquals("1917", DisplayTitle.of("1917"))
        assertEquals("Toy Story 4", DisplayTitle.of("Toy Story 4 - 2019"))
        assertEquals("Mission : Impossible", DisplayTitle.of("Mission : Impossible"))
        assertNull(DisplayTitle.year("Lupin"))
    }
}
