package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AllocineTrailerTest {
    private fun card(id: String, title: String) =
        """<a href="/video/player_gen_cmedia=$id&amp;cfilm=1.html"><span>2:29</span><span>$title</span><span>19 904 vues</span></a>"""

    @Test fun prefersFrenchTrailer() {
        val html = card("1", "Film Teaser VO STFR") + card("2", "Film Bande-annonce VO") +
            card("3", "Film Bande-annonce VF") + card("4", "Film Extrait vidéo VF")
        assertEquals("3", AllocineTrailer.bestVideo(html, "2"))
    }

    @Test fun subtitledBeforeOriginal() {
        val html = card("1", "Film Bande-annonce VO") + card("2", "Film Teaser VOSTFR")
        assertEquals("2", AllocineTrailer.bestVideo(html, null))
    }

    @Test fun keepsDefaultWhenOnlyOriginal() {
        val html = card("1", "Film Extrait vidéo VO") + card("2", "Film Bande-annonce VO")
        assertEquals("9", AllocineTrailer.bestVideo(html, "9"))
    }
}
