package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    private fun row(key: String, updated: Long, position: Long = 60_000, url: String? = null) = Progress(
        key = key, type = Progress.MOVIE, itemId = key.substringAfter(':'), title = "Titre $key", subtitle = null,
        poster = "http://img/p.jpg", url = url, position = position, duration = 6_000_000, updated = updated,
        seriesId = null, season = 0, episode = 0,
    )

    private fun doc(vararg rows: Progress, del: Map<String, Long> = emptyMap(), fav: Map<String, Long> = emptyMap(), favDel: Map<String, Long> = emptyMap()) =
        DriveDoc(rows.associateBy { it.key }, del, fav, favDel)

    @Test fun newerRowWins() {
        val local = doc(row("movie:1", now - 1000, position = 10_000), row("movie:2", now - 5000, position = 20_000))
        val remote = doc(row("movie:1", now - 500, position = 99_000), row("movie:2", now - 9000, position = 1_000))
        val m = DriveMerge.merge(local, remote, now)
        assertEquals(99_000L, m.progress["movie:1"]!!.position)
        assertEquals(20_000L, m.progress["movie:2"]!!.position)
        val ch = DriveMerge.localChanges(local, m, emptyList())
        assertEquals(listOf("movie:1"), ch.save.map { it.key })
        assertTrue(ch.delete.isEmpty())
    }

    @Test fun tieIsDeterministic() {
        val a = row("movie:1", now, position = 10_000)
        val b = row("movie:1", now, position = 30_000)
        assertEquals(30_000L, DriveMerge.merge(doc(a), doc(b), now).progress["movie:1"]!!.position)
        assertEquals(30_000L, DriveMerge.merge(doc(b), doc(a), now).progress["movie:1"]!!.position)
    }

    @Test fun tombstoneWinsOverOlderRow() {
        val local = doc(row("movie:1", now - 10_000))
        val remote = doc(del = mapOf("movie:1" to now - 5_000))
        val m = DriveMerge.merge(local, remote, now)
        assertNull(m.progress["movie:1"])
        assertEquals(now - 5_000, m.progressDeleted["movie:1"])
        assertEquals(setOf("movie:1"), DriveMerge.localChanges(local, m, emptyList()).delete)
    }

    @Test fun newerRowBeatsTombstone() {
        val local = doc(row("movie:1", now - 1_000), del = emptyMap())
        val remote = doc(del = mapOf("movie:1" to now - 5_000))
        val m = DriveMerge.merge(local, remote, now)
        assertTrue("movie:1" in m.progress)
        assertFalse("movie:1" in m.progressDeleted)
    }

    @Test fun oldTombstonesPruned() {
        val m = DriveMerge.merge(doc(del = mapOf("movie:old" to now - 61 * day, "movie:new" to now - day)), doc(), now)
        assertEquals(setOf("movie:new"), m.progressDeleted.keys)
    }

    @Test fun rowsCapped() {
        val rows = (0 until DriveMerge.MAX_ROWS + 50).map { row("movie:$it", now - it * 1000L) }
        val m = DriveMerge.merge(DriveDoc(rows.associateBy { it.key }), DriveDoc(), now)
        assertEquals(DriveMerge.MAX_ROWS, m.progress.size)
        assertTrue("movie:0" in m.progress)
        assertFalse("movie:${DriveMerge.MAX_ROWS + 49}" in m.progress)
    }

    @Test fun localUrlKeptButNeverSerialized() {
        val secret = "http://srv/movie/user/pass/1.mkv"
        val local = doc(row("movie:1", now - 9_000, url = secret))
        val remote = doc(row("movie:1", now - 1_000, position = 70_000))
        val m = DriveMerge.merge(local, remote, now)
        assertNull(m.progress["movie:1"]!!.url)
        // La ligne gagnante (distante) garde l'adresse locale sur cette TV…
        assertEquals(secret, DriveMerge.localChanges(local, m, emptyList()).save.single().url)
        // … mais aucune adresse n'est jamais écrite dans le fichier, même depuis un document local.
        val json = DriveCodec.encode(local.copy(deviceName = "Salon", savedAt = now))
        assertFalse(json.contains("pass"))
        assertFalse(json.contains("\"url\""))
    }

    @Test fun jsonRoundTrip() {
        val ep = Progress(
            "ep:42", Progress.EPISODE, "42", "Série é", "S01 E02", null, "http://x/secret", 1_000_000, 2_000_000,
            now, "s7", 1, 2, estimated = true,
        )
        val d = DriveDoc(
            progress = mapOf(ep.key to ep, "movie:1" to row("movie:1", now - 5)),
            progressDeleted = mapOf("movie:9" to now - 7),
            favorites = mapOf("movie:1" to now - 3, "series:s7" to 0L),
            favoritesDeleted = mapOf("series:old" to now - 2),
            deviceName = "Chambre", savedAt = now,
        )
        val back = DriveCodec.decode(DriveCodec.encode(d))
        assertEquals(d.copy(progress = d.progress.mapValues { it.value.copy(url = null) }), back)
        assertTrue(DriveCodec.sameContent(d, back))
        assertTrue(DriveCodec.encode(d).contains("\"finished\":false"))
    }

    @Test fun favoritesMerge() {
        val local = doc(fav = mapOf("movie:1" to now - 100, "series:2" to 0L))
        val remote = doc(fav = mapOf("movie:3" to now - 50), favDel = mapOf("series:2" to now - 10, "movie:1" to now - 1000))
        val m = DriveMerge.merge(local, remote, now)
        assertEquals(setOf("movie:1", "movie:3"), m.favorites.keys)
        assertEquals(setOf("series:2"), m.favoritesDeleted.keys)
        val ch = DriveMerge.localChanges(local, m, listOf("movie:1", "series:2"))
        assertEquals(listOf("movie:3", "movie:1"), ch.favorites)
        // Rien de neuf : la liste n'est pas réécrite.
        assertNull(DriveMerge.localChanges(m, m, listOf("movie:3", "movie:1")).favorites)
    }

    @Test fun mergeIsStableNoPingPong() {
        val a = doc(row("movie:1", now - 10), fav = mapOf("movie:1" to now - 10))
        val b = doc(row("movie:2", now - 20), del = mapOf("movie:3" to now - 30))
        val m1 = DriveMerge.merge(a, b, now)
        // L'autre TV relit le fichier fusionné : rien à renvoyer.
        val m2 = DriveMerge.merge(b.copy(progress = m1.progress, progressDeleted = m1.progressDeleted, favorites = m1.favorites), m1, now)
        assertTrue(DriveCodec.sameContent(m1, m2))
    }

    @Test fun parsesDeviceCode() {
        val dc = GoogleOAuthParse.deviceCode(
            """{"device_code":"AH-1Ng","user_code":"lxm-wrq-cdzm","expires_in":1800,"interval":5,
               "verification_url":"https://www.google.com/device"}"""
        )
        assertEquals("AH-1Ng", dc.deviceCode)
        assertEquals("LXM-WRQ-CDZM", dc.userCode)
        assertEquals("https://www.google.com/device", dc.verificationUrl)
        assertEquals(1800, dc.expiresInSec)
        assertEquals(5, dc.intervalSec)
    }

    @Test fun parsesPollResponses() {
        assertEquals(GoogleOAuthParse.Poll.Pending, GoogleOAuthParse.poll(428, """{"error":"authorization_pending"}"""))
        assertEquals(GoogleOAuthParse.Poll.SlowDown, GoogleOAuthParse.poll(403, """{"error":"slow_down"}"""))
        assertEquals(GoogleOAuthParse.Poll.Denied, GoogleOAuthParse.poll(403, """{"error":"access_denied"}"""))
        assertEquals(GoogleOAuthParse.Poll.Expired, GoogleOAuthParse.poll(400, """{"error":"expired_token"}"""))
        // id_token : en-tête.{"email":"prenom.nom@gmail.com"}.signature (base64url sans remplissage)
        val payload = "eyJlbWFpbCI6InByZW5vbS5ub21AZ21haWwuY29tIn0"
        val r = GoogleOAuthParse.poll(
            200,
            """{"access_token":"ya29.x","expires_in":3599,"refresh_token":"1//r","scope":"openid https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email","token_type":"Bearer","id_token":"eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"}""",
        )
        val t = (r as GoogleOAuthParse.Poll.Authorized).tokens
        assertEquals("ya29.x", t.access)
        assertEquals("1//r", t.refresh)
        assertEquals(3599L, t.expiresInSec)
        assertEquals("prenom.nom@gmail.com", t.email)
        assertTrue(GoogleAuth.hasDriveScope(t))
        val noDrive = GoogleOAuthParse.tokens("""{"access_token":"a","expires_in":10,"scope":"openid email"}""")
        assertFalse(GoogleAuth.hasDriveScope(noDrive))
        assertNull(noDrive.refresh)
    }
}
