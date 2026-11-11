package com.fluxfr.tv.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbModelTest {
    @Test fun pathSegments() {
        assertEquals(listOf("Films", "HD"), SmbPaths.segments("Films/HD"))
        assertEquals(listOf("Films", "HD"), SmbPaths.segments("\\Films\\HD\\"))
        assertEquals(listOf("Films", "HD"), SmbPaths.segments("/Films//./HD/../"))
        assertEquals(emptyList<String>(), SmbPaths.segments(""))
    }

    @Test fun hostInput() {
        assertEquals("nas" to "", SmbPaths.parseHostInput("nas"))
        assertEquals("192.168.1.20" to "Films", SmbPaths.parseHostInput(" 192.168.1.20 ", "Films"))
        assertEquals("NAS" to "Films/HD", SmbPaths.parseHostInput("\\\\NAS\\Films", "HD"))
        assertEquals("nas.local" to "Video/Séries", SmbPaths.parseHostInput("smb://nas.local/Video/Séries"))
    }

    @Test fun uriRoundTripWithSpecialCharacters() {
        val segs = listOf("Films HD", "Été 2024 #1", "Amélie (2001) 100%.mkv")
        val uri = SmbPaths.buildUri("abc123", segs)
        assertTrue(uri.startsWith("prisme-smb://abc123/"))
        assertFalse(uri.contains(' '))
        assertFalse(uri.contains('#'))
        assertEquals("abc123" to segs, SmbPaths.parseUri(uri))
    }

    @Test fun uriParsingRejectsOtherSchemes() {
        assertNull(SmbPaths.parseUri("http://abc/x.mkv"))
        assertNull(SmbPaths.parseUri("prisme-smb://abc"))
        assertNull(SmbPaths.parseUri("prisme-smb:///x.mkv"))
    }

    @Test fun progressKeyHasNoCredentials() {
        val share = SmbShare("id1", "", "nas", "Films", "bob", "s3cret", "")
        val key = SmbPaths.progressKey(share.id, listOf("Films", "a.mkv"))
        assertEquals("smb:id1/Films/a.mkv", key)
        assertFalse(SmbPaths.buildUri(share.id, listOf("Films", "a.mkv")).contains("s3cret"))
        assertFalse(share.toString().contains("s3cret"))
    }

    @Test fun shareListRoundTrip() {
        val list = listOf(
            SmbShare("a", "Salon", "192.168.1.2", "Films", "bob", "p\"w,d", "WORKGROUP", allowSmb1 = true),
            SmbShare("b", "", "nas", ""),
        )
        assertEquals(list, SmbShare.decodeList(SmbShare.encodeList(list)))
        assertEquals(emptyList<SmbShare>(), SmbShare.decodeList("pas du json"))
        assertEquals(emptyList<SmbShare>(), SmbShare.decodeList(null))
        assertTrue(list[1].guest)
        assertEquals("nas", list[1].displayName)
        assertEquals("Salon", list[0].displayName)
    }

    @Test fun errorMessages() {
        assertEquals(SmbErrors.MSG_AUTH, SmbErrors.describe(SmbErrors.LOGON_FAILURE, emptyList(), emptyList()))
        assertEquals(SmbErrors.MSG_SHARE, SmbErrors.describe(SmbErrors.BAD_NETWORK_NAME, emptyList(), emptyList()))
        assertEquals(SmbErrors.MSG_DENIED, SmbErrors.describe(SmbErrors.ACCESS_DENIED, emptyList(), emptyList()))
        assertEquals(SmbErrors.MSG_PATH, SmbErrors.describe(SmbErrors.OBJECT_PATH_NOT_FOUND, emptyList(), emptyList()))
        assertEquals(
            SmbErrors.MSG_HOST,
            SmbErrors.describe(0, listOf("SmbException", "ConnectException"), listOf("Failed to connect: nas", "Connection refused")),
        )
        assertEquals(SmbErrors.MSG_HOST, SmbErrors.describe(0, listOf("UnknownHostException"), listOf("nas")))
        assertEquals(SmbErrors.MSG_TIMEOUT, SmbErrors.describe(0, listOf("SocketTimeoutException"), listOf("Read timed out")))
        assertTrue(SmbErrors.describe(0, listOf("SmbException"), listOf("Bizarre")).contains("Bizarre"))
    }
}
