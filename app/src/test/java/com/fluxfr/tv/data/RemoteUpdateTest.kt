package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUpdateTest {
    private val sha = "a".repeat(64)
    private fun json(code: Int = 30, apk: String = "https://drive.usercontent.google.com/download?id=x", flavour: String = "public", s: String = sha) =
        """{"versionCode":$code,"versionName":"3.5","apk":"$apk","sha256":"$s","size":123,"flavour":"$flavour"}"""

    @Test fun parsesValidManifest() {
        val m = RemoteUpdate.parse(json())
        assertNotNull(m)
        assertEquals(30, m!!.versionCode)
        assertEquals("3.5", m.versionName)
        assertEquals(sha, m.sha256)
    }

    @Test fun rejectsBadManifests() {
        assertNull(RemoteUpdate.parse("""{"versionCode":0}"""))           // fichier vide de l'initialisation
        assertNull(RemoteUpdate.parse(json(apk = "http://exemple/apk")))   // jamais en clair
        assertNull(RemoteUpdate.parse(json(s = "1234")))                    // empreinte invalide
        assertNull(RemoteUpdate.parse("pas du json"))
    }

    @Test fun onlyNewerSameFlavour() {
        val m = RemoteUpdate.parse(json(code = 30))!!
        assertTrue(RemoteUpdate.isNewer(m, 29, "public"))
        assertFalse(RemoteUpdate.isNewer(m, 30, "public"))
        assertFalse(RemoteUpdate.isNewer(m, 29, "personal"))
    }
}
