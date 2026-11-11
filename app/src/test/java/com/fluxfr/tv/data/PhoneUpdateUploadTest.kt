package com.fluxfr.tv.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PhoneUpdateUploadTest {
    private val received = LinkedBlockingQueue<File>()
    private lateinit var dir: File
    private lateinit var server: PhoneSetupServer
    private var port = 0
    private val boundary = "xYzBoundary42"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("prisme-up").toFile()
        server = PhoneSetupServer(PhoneSetupServer.Page.UPDATE) { }
        server.uploadDir = dir
        server.onApk = { received.put(it) }
        port = server.listen() ?: error("could not bind")
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun exchange(head: String, body: ByteArray): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        s.soTimeout = 15_000
        s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        val buf = ByteArrayOutputStream()
        s.getInputStream().copyTo(buf)
        buf.toString("UTF-8")
    }

    private fun upload(fileName: String, data: ByteArray): String {
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"$fileName\"\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return exchange(
            "POST /${server.token}/ HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: ${body.size}\r\n\r\n",
            body,
        )
    }

    @Test
    fun getServesUploadForm() {
        val resp = exchange("GET /${server.token}/ HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n", ByteArray(0))
        assertTrue(resp.startsWith("HTTP/1.1 200"))
        assertTrue(resp.contains("type=\"file\""))
        assertTrue(resp.contains("multipart/form-data"))
    }

    @Test
    fun uploadWritesApkAndCallsBack() {
        val data = Random(1).nextBytes(300_000)
        val resp = upload("Prisme-1.9.apk", data)
        assertTrue(resp, resp.startsWith("HTTP/1.1 200"))
        val f = received.poll(5, TimeUnit.SECONDS) ?: error("callback not called")
        assertArrayEquals(data, f.readBytes())
    }

    @Test
    fun rejectsNonApk() {
        val resp = upload("photo.jpg", Random(2).nextBytes(5_000))
        assertTrue(resp, resp.startsWith("HTTP/1.1 400"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun rejectsOversizedDeclaredLength() {
        val resp = exchange(
            "POST /${server.token}/ HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: ${PhoneSetupServer.MAX_APK_BYTES + 1_000_000}\r\n\r\n",
            ByteArray(0),
        )
        assertTrue(resp, resp.startsWith("HTTP/1.1 413"))
    }
}
