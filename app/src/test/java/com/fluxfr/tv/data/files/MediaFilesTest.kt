package com.fluxfr.tv.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilesTest {
    @Test fun videoExtensions() {
        for (n in listOf("a.mkv", "B.MP4", "c.avi", "d.m4v", "e.MOV", "f.ts", "g.m2ts", "h.webm", "i.wmv", "j.flv", "k.mpg", "l.mpeg")) {
            assertTrue(n, MediaFiles.isVideo(n))
        }
        for (n in listOf("a.srt", "b.mp3", "c.nfo", "mkv", ".mkv", "film.", "film", "x.mkv.part", "cover.jpg")) {
            assertFalse(n, MediaFiles.isVideo(n))
        }
    }

    @Test fun visibleKeepsFoldersAndVideosOnly() {
        val list = listOf(
            FileEntry("Films", true, -1, "1"), FileEntry(".cache", true, -1, "2"), FileEntry("@eaDir", true, -1, "3"),
            FileEntry("film.mkv", false, 10, "4"), FileEntry("film.srt", false, 1, "5"), FileEntry(".hidden.mp4", false, 1, "6"),
            FileEntry("\$RECYCLE.BIN", true, -1, "7"),
        )
        assertEquals(listOf("Films", "film.mkv"), MediaFiles.prepare(list).map { it.name })
    }

    @Test fun naturalSortNumbers() {
        val names = listOf("Episode 10.mkv", "Episode 2.mkv", "episode 1.mkv", "Épisode 3.mkv", "Episode 02b.mkv")
        val sorted = names.sortedWith(MediaFiles.naturalOrder)
        assertEquals(listOf("episode 1.mkv", "Episode 2.mkv", "Episode 02b.mkv", "Épisode 3.mkv", "Episode 10.mkv"), sorted)
    }

    @Test fun naturalSortLeadingZerosAndCase() {
        assertTrue(MediaFiles.naturalCompare("S01E09", "S01E10") < 0)
        assertTrue(MediaFiles.naturalCompare("file1", "file01") < 0)
        assertTrue(MediaFiles.naturalCompare("abc", "ABD") < 0)
        assertTrue(MediaFiles.naturalCompare("a", "a1") < 0)
        assertEquals(0, MediaFiles.naturalCompare("same", "same"))
        // Très grands nombres (pas de débordement).
        assertTrue(MediaFiles.naturalCompare("x99999999999999999999", "x100000000000000000000") < 0)
    }

    @Test fun foldersFirstThenFiles() {
        val list = listOf(
            FileEntry("b.mkv", false, 1, "1"), FileEntry("Z dossier", true, -1, "2"),
            FileEntry("a.mkv", false, 1, "3"), FileEntry("A dossier", true, -1, "4"),
        )
        assertEquals(listOf("A dossier", "Z dossier", "a.mkv", "b.mkv"), MediaFiles.sorted(list).map { it.name })
    }

    @Test fun subtitleSameBaseName() {
        val names = listOf("Film.mkv", "film.srt", "Autre.srt")
        assertEquals("film.srt", MediaFiles.subtitleFor("Film.mkv", names))
        assertEquals("Film.fr.srt", MediaFiles.subtitleFor("Film.mkv", listOf("Film.fr.srt")))
        assertNull(MediaFiles.subtitleFor("Film.mkv", listOf("Autre.srt", "Film.mkv")))
    }

    @Test fun sizes() {
        assertEquals("850 o", MediaFiles.formatSize(850))
        assertEquals("1 Ko", MediaFiles.formatSize(1024))
        assertEquals("1,5 Ko", MediaFiles.formatSize(1536))
        assertEquals("1,4 Go", MediaFiles.formatSize(1_500_000_000))
        assertEquals("700 Mo", MediaFiles.formatSize(700L * 1024 * 1024))
        assertEquals("", MediaFiles.formatSize(-1))
    }

    @Test fun volumeRoots() {
        assertEquals("/storage/ABCD-1234", MediaFiles.volumeRootOf("/storage/ABCD-1234/Android/data/com.fluxfr.tv/files"))
        assertEquals("/storage/emulated/0", MediaFiles.volumeRootOf("/storage/emulated/0/Android/data/x/files"))
        assertNull(MediaFiles.volumeRootOf("/data/user/0/com.fluxfr.tv/files"))
    }

    @Test fun byteRanges() {
        assertEquals(1000L, ByteRange.remaining(0, ByteRange.UNBOUNDED, 1000))
        assertEquals(400L, ByteRange.remaining(600, ByteRange.UNBOUNDED, 1000))
        assertEquals(100L, ByteRange.remaining(600, 100, 1000))
        assertEquals(400L, ByteRange.remaining(600, 5000, 1000))
        assertEquals(0L, ByteRange.remaining(1000, ByteRange.UNBOUNDED, 1000))
        assertEquals(512, ByteRange.chunk(512, ByteRange.UNBOUNDED))
        assertEquals(100, ByteRange.chunk(512, 100))
        assertEquals(512, ByteRange.chunk(512, 10_000_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun byteRangePastEnd() {
        ByteRange.remaining(1001, ByteRange.UNBOUNDED, 1000)
    }

    @Test fun resumeEncoding() {
        assertEquals(Triple(120_000L, 5_400_000L, 42L), FileResume.decode(FileResume.encode(120_000, 5_400_000, 42)))
        assertNull(FileResume.decode("1,2"))
        assertNull(FileResume.decode("a,b,c"))
    }
}
