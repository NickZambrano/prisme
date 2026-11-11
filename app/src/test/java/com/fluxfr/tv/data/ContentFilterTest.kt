package com.fluxfr.tv.data

import com.fluxfr.tv.data.ContentFilter.Companion.cleanCategory
import com.fluxfr.tv.data.ContentFilter.Companion.cleanName
import com.fluxfr.tv.data.ContentFilter.Companion.dedupKey
import com.fluxfr.tv.data.ContentFilter.Companion.quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {
    private val f = ContentFilter(Settings.DEFAULT_FR, Settings.DEFAULT_EXCLUDE)

    @Test fun names() {
        assertEquals("TF1", cleanName("|FR| TF1 FHD"))
        assertEquals("TF1", cleanName("FR: TF1 HD"))
        assertEquals("France 2", cleanName("FR: France 2 ᴴᴰ"))
        assertEquals("France 2", cleanName("France 2 4K"))
        assertEquals("Canal+ Sport", cleanName("FR - Canal+ Sport UHD"))
        assertEquals("Dune (2021)", cleanName("FR - Dune (2021) [4K]"))
        assertEquals("BFM TV", cleanName("FR BFM TV SD"))
        assertEquals("FRANCE", cleanCategory("FRANCE 4K"))
        assertEquals("SPORTS", cleanCategory("FR| SPORTS FHD"))
        assertEquals("NETFLIX", cleanCategory("VOD | FR - NETFLIX"))
        assertEquals("ACTION", cleanCategory("SERIES | FR | ACTION"))
        assertEquals(dedupKey(cleanName("|FR| TF1 FHD")), dedupKey(cleanName("FR: TF1 SD")))
        // Noms réels vus chez le fournisseur
        assertEquals("W9", cleanName("W9 ◉"))
        assertEquals("M6", cleanName("M6 [BK]"))
        assertEquals("TF1", cleanName("TF1 HD (backup)"))
        assertEquals("TF1", cleanName("TF1 (FR)"))
        assertEquals("TF1 +1", cleanName("TF1 +1"))
        assertEquals("GÉNÉRAL", cleanCategory("TV | EU | FR | GÉNÉRAL"))
        assertEquals("LIGUE 1", cleanCategory("TV | VIP | LIGUE 1"))
    }

    @Test fun qualities() {
        assertEquals(Quality.UHD, quality("TF1 4K"))
        assertEquals(Quality.UHD, quality("FR| UHD"))
        assertEquals(Quality.FHD, quality("M6 FHD"))
        assertEquals(Quality.FHD, quality("M6 FULL HD"))
        assertEquals(Quality.HD, quality("M6 ᴴᴰ"))
        assertEquals(Quality.LOW, quality("M6 SD"))
        assertEquals(Quality.UNKNOWN, quality("M6"))
        assertEquals(Quality.LOW, quality("TF1 SD HEVC"))
        assertEquals(Quality.HD, quality("TF1 HEVC"))
    }

    @Test fun lowQualityReleases() {
        for (n in listOf(
            "Dune 2 HDCAM 1080p", "Dune 2 (2024) CAM", "Dune 2 HD-CAM", "Dune 2 HD CAM", "Dune 2 CAMRip", "Dune 2 TS",
            "Dune 2 HDTS 720p", "Dune 2 HD-TS", "Dune 2 TELESYNC", "Dune 2 [TC]", "Dune 2 HDTC", "Dune 2 TELECINE",
            "Dune 2 SCR", "Dune 2 SCREENER", "Dune 2 DVDSCR", "Dune 2 BDSCR", "Dune 2 R5", "Dune 2 WORKPRINT",
            "Dune 2 VERSION CAM", "Dune 2 CAM VF", "FR| Dune 2 - HDCAM - 4K",
        )) assertEquals(n, Quality.LOW, f.vodQuality(n, "FR | FILMS"))
        // Pas de faux positifs : mots qui contiennent TS / TC / SCR / CAM.
        assertEquals(Quality.FHD, f.vodQuality("Tsunami 1080p", "FR | FILMS"))
        assertEquals(Quality.HD, f.vodQuality("Camping 3 HD", "FR | FILMS"))
        assertEquals(Quality.UNKNOWN, f.vodQuality("Les Bronzés font du ski", "FR | FILMS"))
        assertEquals(Quality.UNKNOWN, f.vodQuality("Scream", "FR | FILMS"))
        assertEquals(Quality.UHD, f.vodQuality("Cameron 4K", "FR | FILMS"))
        // Chaînes : jamais concernées par le filtre CAM / TS.
        assertEquals(Quality.HD, quality("FR| TS HD"))
        assertFalse(f.acceptVod(f.vodQuality("Dune 2 HDCAM 1080p", "FR | FILMS")))
        assertFalse(f.acceptVod(f.vodQuality("Dune 2 1080p", "FR | CAM / TS")))
        assertTrue(f.acceptVod(f.vodQuality("Dune 2 1080p", "FR | FILMS")))
    }

    @Test fun language() {
        assertTrue(f.isFrench("FR| GENERALISTES", "TF1"))
        assertTrue(f.isFrench("FRANCE 4K", "TF1"))
        assertTrue(f.isFrench("Films VF", "Dune"))
        assertTrue(f.isFrench("Divers", "FR: Arte"))
        assertTrue(f.isFrench("VOD | FR - NETFLIX", "Lupin"))
        assertFalse(f.isFrench("UK| SPORTS", "Sky Sports"))
        assertFalse(f.isFrench("AFRICA", "Channel"))
        assertFalse(f.isFrench("UK", "France 24 English"))
        // Cas réels vus chez le fournisseur : MULTI-LANG / MULTI-SUB et catégories EN.
        assertFalse(f.isFrench("VOD | MULTI-LANG 2024", "PH - Hello, Love, Again - 2024 [MULTI-SUB]"))
        assertFalse(f.isFrench("VOD | MULTI-LANG - NETFLIX", "Some Movie"))
        assertFalse(f.isFrench("VOD | EN - LATEST MOVIES", "Day Labor"))
        assertFalse(f.isFrench("VOD | EN - NETFLIX", "MULTI - Something"))
        assertTrue(f.isFrench("VOD | MULTI-LANG 2024", "Dune 2 [VF]"))
        assertTrue(f.isFrench("TV | EU | FR | GÉNÉRAL", "TF1"))
        assertTrue(f.isFrench("FR | FILMS DE NOËL", "Le Grinch"))
        assertTrue(f.isFrench("FR | AU CŒUR DE LA NATURE", "Planète"))
        assertFalse(f.isFrench("TV | EU | ES | GENERAL", "La 1"))
        assertFalse(f.isFrench("TV | EU | NL | 4K", "NPO 1"))
        assertTrue(f.isExcluded("FILMS VOSTFR", "x"))
        assertTrue(f.isExcluded("FR| ADULTE", "x"))
        assertFalse(f.isExcluded("FR| FILMS", "Dune"))
    }

    @Test fun placeholders() {
        assertTrue(f.isPlaceholder("##### FRENCH #####"))
        assertTrue(f.isPlaceholder("NO EVENT"))
        assertTrue(f.isPlaceholder("No Match"))
        assertTrue(f.isPlaceholder("-----"))
        assertFalse(f.isPlaceholder("RENNES VS LILLE 14:00 CET"))
        assertFalse(f.isPlaceholder("TF1"))
        assertFalse(f.isPlaceholder("MULTI SCREEN"))
        assertTrue(f.isPlaceholder("⭐ NO EVENT ⭐"))
        assertTrue(f.isPlaceholder("|FR| NO MATCH"))
    }

    @Test fun liveOrder() {
        // Par défaut la "4K" des chaînes passe après FHD et HD (flux souvent non officiels).
        assertTrue(f.liveRank(Quality.FHD) > f.liveRank(Quality.HD))
        assertTrue(f.liveRank(Quality.HD) > f.liveRank(Quality.UHD))
        assertTrue(f.liveRank(Quality.UHD) > f.liveRank(Quality.LOW))
        val uhdFirst = ContentFilter(Settings.DEFAULT_FR, Settings.DEFAULT_EXCLUDE, preferUhdLive = true)
        assertTrue(uhdFirst.liveRank(Quality.UHD) > uhdFirst.liveRank(Quality.FHD))
    }
}
