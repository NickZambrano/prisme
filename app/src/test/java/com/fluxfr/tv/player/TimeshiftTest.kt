package com.fluxfr.tv.player

import com.fluxfr.tv.data.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

class TimeIndexTest {
    /** 1 Mo/s reçu régulièrement pendant 10 s à partir de t = 1 000 000. */
    private fun steady(): TimeIndex {
        val idx = TimeIndex()
        for (s in 0..10) idx.record(1_000_000L + s * 1000, s * 1_000_000L)
        return idx
    }

    @Test fun interpolatesBothWays() {
        val idx = steady()
        assertEquals(1_002_500L, idx.wallAt(2_500_000))
        assertEquals(2_500_000L, idx.offsetAt(1_002_500))
        assertEquals(0L, idx.offsetAt(500_000)) // avant le début : borné
        assertEquals(10_000_000L, idx.offsetAt(2_000_000)) // après la fin : borné
        assertEquals(1_000_000L, idx.wallAt(0))
        assertEquals(1_010_000L, idx.wallAt(50_000_000))
    }

    @Test fun keepsOnePointPerSecondAndFollowsTail() {
        val idx = TimeIndex()
        idx.record(0, 0)
        for (i in 1..100) idx.record(i * 50L, i * 1000L) // 5 s, un appel toutes les 50 ms
        assertTrue("points: ${idx.size}", idx.size in 5..7)
        assertEquals(5000L, idx.lastWall)
        assertEquals(100_000L, idx.lastOffset)
    }

    @Test fun neverGoesBackwards() {
        val idx = TimeIndex()
        idx.record(1000, 100)
        idx.record(3000, 50) // horloge ou compteur incohérent
        assertEquals(100L, idx.lastOffset)
        assertEquals(3000L, idx.lastWall)
    }

    @Test fun seekSnapsToPreviousKeyframe() {
        val idx = steady()
        idx.addKeyframe(188L * 5000) // 940 000 o, ~0,94 s
        idx.addKeyframe(188L * 15_000) // 2 820 000 o, ~2,82 s
        assertEquals(188L * 15_000, idx.seekOffset(1_003_000))
        assertEquals(188L * 5000, idx.seekOffset(1_002_000))
        assertEquals(188L * 5000, idx.keyframeAtOrBefore(2_000_000))
        assertNull(idx.keyframeAtOrBefore(100))
    }

    @Test fun seekWithoutNearbyKeyframeAlignsOnPacket() {
        val idx = steady()
        idx.packetPhase = 4
        idx.addKeyframe(188L * 10 + 4)
        // Image clé à plus de 10 s : ignorée, début de paquet le plus proche.
        idx.record(1_030_000, 30_000_000)
        val off = idx.seekOffset(1_020_000)
        assertEquals(0L, (off - 4) % 188)
        assertTrue(off <= idx.offsetAt(1_020_000))
        assertTrue(idx.offsetAt(1_020_000) - off < 188)
    }

    @Test fun dropBeforeKeepsInterpolation() {
        val idx = steady()
        idx.addKeyframe(1_000_000)
        idx.addKeyframe(5_000_000)
        idx.dropBefore(3_500_000)
        assertEquals(3_500_000L, idx.firstOffset)
        assertEquals(1_003_500L, idx.firstWall)
        assertEquals(1_006_000L, idx.wallAt(6_000_000))
        assertEquals(1, idx.keyframeCount)
        assertEquals(5_000_000L, idx.keyframeAtOrBefore(9_000_000))
    }
}

class TsScannerTest {
    private fun packet(pid: Int, rai: Boolean): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (rai) {
            p[3] = 0x30 // champ d'adaptation + charge utile
            p[4] = 7
            p[5] = 0x40 // random_access_indicator
        } else p[3] = 0x10
        return p
    }

    @Test fun findsKeyframesAcrossChunksWithGarbagePrefix() {
        val found = ArrayList<Long>()
        val s = TsScanner({ found += it })
        val stream = java.io.ByteArrayOutputStream()
        stream.write(byteArrayOf(1, 2, 3)) // octets parasites avant la synchro
        for (i in 0 until 10) stream.write(packet(0x100, rai = i == 5))
        val all = stream.toByteArray()
        // Découpage arbitraire
        var off = 0
        for (n in listOf(50, 200, 1, 700, all.size)) {
            val len = minOf(n, all.size - off)
            if (len <= 0) break
            s.feed(all, off, len)
            off += len
        }
        assertTrue(s.everSynced)
        assertEquals(listOf(3L + 5 * 188), found)
    }

    @Test fun detectsPlaylist() {
        val b = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        assertTrue(TsScanner.looksLikePlaylist(b, b.size))
        assertFalse(TsScanner.looksLikePlaylist(packet(0x100, false), 188))
    }
}

class TimeshiftBufferTest {
    private fun tmp(): File = Files.createTempDirectory("ts").toFile()

    @Test fun writesReadsAndEvictsOldSegments() {
        val dir = tmp()
        var now = 10_000L
        val buf = TimeshiftBuffer(dir, maxBytes = 2_500, segmentBytes = 1_000, clock = { now })
        val data = ByteArray(4_200) { (it % 251).toByte() }
        for (i in 0 until 42) {
            buf.append(data, i * 100, 100)
            now += 100
        }
        assertEquals(4_200L, buf.end)
        // 4 200 o écrits, 2 500 au plus gardés, par segments entiers de 1 000 : début à 2 000.
        assertEquals(2_000L, buf.start)
        assertFalse(File(dir, "0.ts").exists())
        assertTrue(File(dir, "4.ts").exists())

        val seg = TimeshiftBuffer.OpenSegment()
        val out = ByteArray(1_500)
        var pos = 2_500L
        var got = 0
        while (pos < 4_000) {
            val n = buf.readAt(pos, out, got, (4_000 - pos).toInt(), seg)
            pos += n; got += n
        }
        for (k in 0 until got) assertEquals(data[2_500 + k], out[k])
        try {
            buf.readAt(1_000, out, 0, 10, seg)
            fail("position effacée")
        } catch (_: BehindWindowException) {
        }
        assertEquals(0L, buf.available(4_200, 1)) // rien de neuf
        seg.close() // Windows (tests) : un fichier ouvert ne peut pas être effacé
        buf.close(delete = true)
        assertEquals(-1L, buf.available(4_200, 1))
        assertFalse(dir.exists())
    }

    @Test fun evictsByDuration() {
        val dir = tmp()
        var now = 0L
        val buf = TimeshiftBuffer(dir, maxBytes = Long.MAX_VALUE, maxDurationMs = 5_000, segmentBytes = 1_000, clock = { now })
        val chunk = ByteArray(500)
        repeat(40) { // 20 000 o en 20 s (1 000 o/s)
            buf.append(chunk, 0, 500)
            now += 500
        }
        val window = buf.lastWall - buf.firstWall
        assertTrue("fenêtre $window", window in 5_000..6_500)
        buf.close(delete = true)
    }

    @Test fun failureReachesReaders() {
        val buf = TimeshiftBuffer(tmp(), maxBytes = 10_000)
        buf.fail(TimeshiftSourceException("HTTP 403", httpCode = 403))
        try {
            buf.available(0, 1)
            fail("erreur attendue")
        } catch (e: TimeshiftSourceException) {
            assertEquals(403, e.httpCode)
        }
        buf.close(delete = true)
    }

    @Test fun capFollowsFreeSpace() {
        assertEquals(0L, Timeshift.capBytes(900L shl 20))
        assertEquals(2L shl 30, Timeshift.capBytes(8L shl 30)) // 25 % de 8 Go
        assertEquals(Timeshift.DEFAULT_CAP_BYTES, Timeshift.capBytes(100L shl 30))
    }

    @Test fun eligibility() {
        assertTrue(Timeshift.isEligible("http://srv:8080/live/u/p/123.ts"))
        assertTrue(Timeshift.isEligible("https://srv/live/u/p/123"))
        assertFalse(Timeshift.isEligible("http://srv/live/u/p/123.m3u8"))
        assertFalse(Timeshift.isEligible("http://srv/play/index.m3u8?token=x"))
        assertFalse(Timeshift.isEligible("rtmp://srv/live"))
    }
}

class CatchupTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")

    @Test fun formatsStartInServerTimeZone() {
        // 2026-11-08 19:00 UTC = 20:00 à Paris (heure d'hiver)
        val start = 1_794_164_400_000L
        assertEquals("2026-11-08:20-00", Catchup.formatStart(start, paris))
        assertEquals("2026-11-08:19-00", Catchup.formatStart(start, TimeZone.getTimeZone("UTC")))
        // Été : UTC+2
        val summer = 1_783_008_000_000L // 2026-07-02 16:00 UTC
        assertEquals("2026-07-02:18-00", Catchup.formatStart(summer, paris))
    }

    @Test fun buildsXtreamTimeshiftUrl() {
        val start = 1_794_164_400_000L
        val end = start + 52 * 60_000L + 30_000 // 52 min 30 s → 53
        val url = Catchup.url("srv.example.com:8080/", "jean", "p@ss w", "4521", start, end, paris)
        assertEquals("http://srv.example.com:8080/timeshift/jean/p%40ss+w/53/2026-11-08:20-00/4521.ts", url)
    }

    @Test fun durationIsAtLeastOneMinute() {
        assertEquals(1L, Catchup.durationMinutes(0, 10_000))
        assertEquals(90L, Catchup.durationMinutes(0, 90 * 60_000L))
    }

    @Test fun replayableWindow() {
        val now = 1_794_164_400_000L
        val past = EpgProgram("Journal", null, now - 2 * 3600_000L, now - 3600_000L)
        assertTrue(Catchup.replayable(3, past, now))
        assertFalse(Catchup.replayable(0, past, now))
        val old = EpgProgram("Vieux", null, now - 4 * 24 * 3600_000L, now - 4 * 24 * 3600_000L + 3600_000L)
        assertFalse(Catchup.replayable(3, old, now))
        val justStarted = EpgProgram("Direct", null, now - 30_000, now + 3600_000L)
        assertFalse(Catchup.replayable(3, justStarted, now))
    }

    @Test fun parsesServerTimeZone() {
        assertEquals("Europe/Paris", AccountLimits.parseTimeZone("Europe/Paris")?.id)
        assertEquals("UTC", AccountLimits.parseTimeZone("UTC")?.id)
        assertNull(AccountLimits.parseTimeZone("Pas/UnFuseau"))
        assertNull(AccountLimits.parseTimeZone(""))
    }
}
