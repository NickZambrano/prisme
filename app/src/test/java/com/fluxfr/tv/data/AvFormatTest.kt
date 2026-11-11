package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvFormatTest {
    @Test fun streamsHdr10AtmosSurround() {
        val av = AvFormat.fromStreams(
            listOf("codec_name" to "hevc", "color_transfer" to "smpte2084"),
            listOf("codec_name" to "eac3", "profile" to "Dolby Digital Plus + Dolby Atmos", "channels" to "6"),
        )
        assertEquals("HDR10", av.hdr)
        assertTrue(av.atmos)
        assertEquals("5.1", av.channels)
    }

    @Test fun streamsDolbyVisionAndHlg() {
        val dv = AvFormat.fromStreams(
            listOf("color_transfer" to "smpte2084", "side_data_type" to "DOVI configuration record"),
            listOf("codec_name" to "truehd", "channel_layout" to "7.1"),
        )
        assertEquals(AvFormat.DOLBY_VISION, dv.hdr)
        assertFalse(dv.atmos)
        assertEquals("7.1", dv.channels)
        assertEquals("HLG", AvFormat.fromStreams(listOf("color_transfer" to "arib-std-b67"), emptyList()).hdr)
        assertTrue(AvFormat.fromStreams(emptyList(), listOf("channels" to "2")).isEmpty)
    }

    @Test fun fromName() {
        assertEquals(AvFormat.DOLBY_VISION, AvFormat.fromName("Dune 4K DV ATMOS").hdr)
        assertTrue(AvFormat.fromName("Dune 4K DV ATMOS").atmos)
        assertEquals("HDR10", AvFormat.fromName("Oppenheimer HDR10 2160p").hdr)
        assertNull(AvFormat.fromName("Le Parrain").hdr)
        val merged = AvFormat(hdr = "HDR10").merge(AvFormat.fromName("Film DOVI"))
        assertEquals(AvFormat.DOLBY_VISION, merged.hdr)
    }
}
