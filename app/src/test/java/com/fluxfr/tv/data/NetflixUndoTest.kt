package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Annuler l'import Netflix » puis réimport : lignes retirées, suppressions et recréations via Google Drive. */
class NetflixUndoTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    private fun row(key: String, updated: Long, source: String? = Progress.NETFLIX) = Progress(
        key = key, type = Progress.EPISODE, itemId = key.substringAfter(':'), title = "Série", subtitle = "S01 E01",
        poster = null, url = null, position = 2_700_000, duration = 2_700_000, updated = updated,
        seriesId = "s1", season = 1, episode = 1, source = source,
    )

    private fun doc(
        vararg rows: Progress,
        del: Map<String, Long> = emptyMap(),
        rev: Map<String, Long> = emptyMap(),
    ) = DriveDoc(rows.associateBy { it.key }, del, progressRevived = rev)

    @Test fun undoRemovesOnlyNetflixRows() {
        val rows = listOf(
            row("ep:nf1", now - 300 * day),
            row("ep:nf2", now - 200 * day),
            // Importée puis reprise dans Prisme : la lecture locale a remis l'origine à null.
            row("ep:played", now - day, source = null),
            row("movie:prisme", now - 2 * day, source = null),
            row("ep:other", now - 3 * day, source = "autre"),
        )
        assertEquals(listOf("ep:nf1", "ep:nf2"), ImportRules.importedKeys(rows, Progress.NETFLIX))
        assertTrue(ImportRules.importedKeys(rows.filter { it.source == null }, Progress.NETFLIX).isEmpty())
    }

    @Test fun undoTombstonesDeleteOldImportedRowsOnOtherTv() {
        val undoAt = now - day
        // TV A : import annulé (suppressions datées de l'annulation) ; TV B : a toujours les lignes importées.
        val tvA = doc(row("ep:prisme", now - 2 * day, source = null), del = mapOf("ep:nf1" to undoAt))
        val tvB = doc(row("ep:nf1", now - 300 * day), row("ep:prisme", now - 2 * day, source = null))
        val m = DriveMerge.merge(tvB, tvA, now)
        assertNull(m.progress["ep:nf1"])
        assertTrue("ep:prisme" in m.progress)
        assertEquals(setOf("ep:nf1"), DriveMerge.localChanges(tvB, m, emptyList()).delete)
    }

    @Test fun reimportAfterUndoSurvivesDriveMerge() {
        val undoAt = now - 2 * day
        val reimportAt = now - day
        val watched = now - 300 * day // date Netflix : bien plus ancienne que l'annulation
        // Drive (et TV B) : suppressions de l'annulation.
        val drive = doc(del = mapOf("ep:nf1" to undoAt, "ep:nf2" to undoAt))
        val tvBLocal = doc(del = mapOf("ep:nf1" to undoAt, "ep:nf2" to undoAt))

        // TV A : réimport de ep:nf1 seulement ; suppression locale oubliée, recréation datée.
        val (dels, revs) = DriveMerge.revive(mapOf("ep:nf1" to undoAt, "ep:nf2" to undoAt), emptyMap(), listOf("ep:nf1"), reimportAt)
        assertEquals(mapOf("ep:nf2" to undoAt), dels)
        assertEquals(mapOf("ep:nf1" to reimportAt), revs)
        val tvA = doc(row("ep:nf1", watched), del = dels, rev = revs)

        // Fusion TV A ↔ Drive : la ligne réimportée reste, malgré la suppression plus récente que sa date.
        val m1 = DriveMerge.merge(tvA, drive, now)
        assertTrue("ep:nf1" in m1.progress)
        assertFalse("ep:nf1" in m1.progressDeleted)
        assertEquals(now - 2 * day, m1.progressDeleted["ep:nf2"])
        assertEquals(reimportAt, m1.progressRevived["ep:nf1"])
        assertTrue(DriveMerge.localChanges(tvA, m1, emptyList()).delete.isEmpty())

        // Aller-retour JSON (fichier Drive) : la recréation est conservée.
        val onDrive = DriveCodec.decode(DriveCodec.encode(m1))
        assertEquals(reimportAt, onDrive.progressRevived["ep:nf1"])

        // TV B (encore la suppression locale) : récupère la ligne réimportée.
        val m2 = DriveMerge.merge(tvBLocal, onDrive, now)
        assertTrue("ep:nf1" in m2.progress)
        val chB = DriveMerge.localChanges(tvBLocal, m2, emptyList())
        assertEquals(listOf("ep:nf1"), chB.save.map { it.key })
        assertEquals(Progress.NETFLIX, chB.save.single().source)
        // Stable : nouvelle fusion sans changement (pas de ping-pong).
        assertTrue(DriveCodec.sameContent(m2, DriveMerge.merge(doc(row("ep:nf1", watched), del = tvBLocal.progressDeleted), m2, now)))

        // Nouvelle annulation après le réimport : la suppression (plus récente que la recréation) gagne.
        val m3 = DriveMerge.merge(doc(del = mapOf("ep:nf1" to now)), onDrive, now)
        assertNull(m3.progress["ep:nf1"])
        assertEquals(now, m3.progressDeleted["ep:nf1"])
    }

    @Test fun revivedFieldAbsentWhenEmpty() {
        val json = DriveCodec.encode(doc(row("ep:x", now)))
        assertFalse(json.contains("progressRevived"))
        assertTrue(DriveCodec.decode(json).progressRevived.isEmpty())
    }

    @Test fun importMetaRoundTripAndLabel() {
        val m = NfImportMeta(now, "NetflixViewingHistory.csv", 12, 1010, 40, 1022)
        assertEquals(m, NetflixImportMeta.decode(NetflixImportMeta.encode(m)))
        assertNull(NetflixImportMeta.decode("pas du json"))
        assertEquals("1 022 titres importés", NetflixImport.titlesLabel(1022))
        assertEquals("1 titre importé", NetflixImport.titlesLabel(1))
        assertEquals("1 234 567", NetflixImport.groupDigits(1_234_567))
    }
}
