package com.fluxfr.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import kotlin.random.Random

class LanHttpTest {
    private val boundary = "----WebKitFormBoundaryAbC123"

    private fun multipart(file: ByteArray, before: String = "", fileName: String = "prisme.apk"): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("preamble ignored\r\n")
        if (before.isNotEmpty()) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\n$before\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"$fileName\"\r\n")
        w("Content-Type: application/vnd.android.package-archive\r\n\r\n")
        out.write(file)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    /** Flux qui ne rend que quelques octets par lecture (comme un socket). */
    private class Trickle(input: InputStream, private val max: Int) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, max))
    }

    private fun binary(size: Int): ByteArray {
        val b = Random(7).nextBytes(size)
        // Glisse des débuts de délimiteur dans le contenu, pour piéger une recherche naïve.
        val trap = "\r\n--$boundary".toByteArray()
        if (size > 1000) {
            System.arraycopy(trap, 0, b, 500, trap.size - 1)
            b[size - 3] = '\r'.code.toByte(); b[size - 2] = '\n'.code.toByte(); b[size - 1] = '-'.code.toByte()
        }
        return b
    }

    @Test
    fun copiesBinaryFilePart() {
        for (size in listOf(0, 1, 100, 70_000, 200_003)) {
            val data = binary(size)
            val out = ByteArrayOutputStream()
            val part = LanHttp.copyFirstFilePart(Trickle(ByteArrayInputStream(multipart(data)), 997), boundary, out, Long.MAX_VALUE)
            assertEquals("prisme.apk", part!!.fileName)
            assertEquals("apk", part.fieldName)
            assertEquals(size.toLong(), part.size)
            assertArrayEquals(data, out.toByteArray())
        }
    }

    @Test
    fun skipsTextFieldsBeforeFile() {
        val data = binary(5000)
        val out = ByteArrayOutputStream()
        val part = LanHttp.copyFirstFilePart(ByteArrayInputStream(multipart(data, before = "bonjour\r\nla TV")), boundary, out, Long.MAX_VALUE)
        assertEquals("prisme.apk", part!!.fileName)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun noFilePartReturnsNull() {
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nv\r\n--$boundary--\r\n"
        assertNull(LanHttp.copyFirstFilePart(ByteArrayInputStream(body.toByteArray()), boundary, ByteArrayOutputStream(), 100))
    }

    @Test
    fun enforcesLimit() {
        try {
            LanHttp.copyFirstFilePart(ByteArrayInputStream(multipart(binary(300_000))), boundary, ByteArrayOutputStream(), 100_000)
            fail("limit not enforced")
        } catch (_: LanHttp.TooLarge) {
        }
    }

    @Test
    fun truncatedBodyThrows() {
        val full = multipart(binary(10_000))
        try {
            LanHttp.copyFirstFilePart(ByteArrayInputStream(full.copyOf(6_000)), boundary, ByteArrayOutputStream(), Long.MAX_VALUE)
            fail("truncation not detected")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun parsesBoundary() {
        assertEquals("abc", LanHttp.boundaryOf("multipart/form-data; boundary=abc"))
        assertEquals("a b", LanHttp.boundaryOf("multipart/form-data; charset=utf-8; boundary=\"a b\""))
        assertNull(LanHttp.boundaryOf("application/x-www-form-urlencoded"))
        assertNull(LanHttp.boundaryOf(null))
    }

    @Test
    fun readsRequestHeaders() {
        val req = LanHttp.readRequest(ByteArrayInputStream("GET /prisme/version?x=1 HTTP/1.1\r\nHost: tv\r\nContent-Length: 12\r\n\r\n".toByteArray()))!!
        assertEquals("GET", req.method)
        assertEquals("/prisme/version?x=1", req.path)
        assertEquals(12L, req.contentLength)
        assertEquals("tv", req.header("HOST"))
    }

    @Test
    fun lanAddresses() {
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("192.168.1.20")))
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("10.0.0.3")))
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("fe80::1")))
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("fd12::1")))
        assertFalse(LanHttp.isLanAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanHttp.isLanAddress(InetAddress.getByName("2001:4860::8888")))
        assertFalse(LanHttp.isLanAddress(null))
    }
}
