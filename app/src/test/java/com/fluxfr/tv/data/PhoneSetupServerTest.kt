package com.fluxfr.tv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneSetupServerTest {
    private val received = LinkedBlockingQueue<Map<String, String>>()
    private lateinit var server: PhoneSetupServer
    private var serverPort = 0

    @Before
    fun setUp() {
        server = PhoneSetupServer { received.put(it) }
        serverPort = server.listen() ?: error("could not bind")
    }

    @After
    fun tearDown() = server.stop()

    private fun connect() = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", serverPort), 5_000)
        soTimeout = 15_000
    }

    /** Envoie chaque morceau séparément (flush + pause) puis lit la réponse jusqu'à la fermeture. */
    private fun exchange(vararg chunks: String): String = connect().use { s ->
        val out = s.getOutputStream()
        chunks.forEachIndexed { i, c ->
            out.write(c.toByteArray(Charsets.UTF_8))
            out.flush()
            if (i < chunks.lastIndex) Thread.sleep(150)
        }
        val buf = ByteArrayOutputStream()
        s.getInputStream().copyTo(buf)
        buf.toString("UTF-8")
    }

    /** Requête GET de la page (sous le jeton, Host en adresse IP). */
    private val get get() = "GET /${server.token}/ HTTP/1.1\r\nHost: 127.0.0.1:$serverPort\r\n\r\n"

    private fun post(body: String) =
        "POST /${server.token}/ HTTP/1.1\r\nHost: 127.0.0.1:$serverPort\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"

    private fun awaitForm() = received.poll(5, TimeUnit.SECONDS) ?: error("callback not called")

    private fun bodyOf(resp: String) = resp.substringAfter("\r\n\r\n")

    private fun assertOkHtml(resp: String) {
        assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(resp, resp.contains("Content-Type: text/html; charset=utf-8"))
        val declared = Regex("Content-Length: (\\d+)").find(resp)!!.groupValues[1].toInt()
        assertEquals(declared, bodyOf(resp).toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun getServesForm() {
        val resp = exchange(get)
        assertOkHtml(resp)
        val body = bodyOf(resp)
        assertTrue(body.contains("<base href=\"/${server.token}/\">"))
        assertTrue(body.contains("<form"))
        assertTrue(body.contains("name=\"server\""))
        assertTrue(body.contains("name=\"m3u\""))
        assertTrue(received.isEmpty())
    }

    @Test
    fun postXtreamDecodesSpecialCharacters() {
        val resp = exchange(post("mode=xtream&server=http%3A%2F%2Fa.b%3A8080&user=u%40x&pass=p%26%3D%C3%A9"))
        assertOkHtml(resp)
        assertTrue(!bodyOf(resp).contains("<form"))
        assertTrue(bodyOf(resp).contains("envoyé"))
        assertEquals(
            mapOf("mode" to "xtream", "server" to "http://a.b:8080", "user" to "u@x", "pass" to "p&=é"),
            awaitForm(),
        )
    }

    @Test
    fun postXtreamWithUnencodedUrlCharacters() {
        // Certains clients n'encodent pas ':' et '/' : la valeur doit rester intacte.
        exchange(post("mode=xtream&server=http://a.b:8080&user=u%40x&pass=a+b"))
        assertEquals(
            mapOf("mode" to "xtream", "server" to "http://a.b:8080", "user" to "u@x", "pass" to "a b"),
            awaitForm(),
        )
    }

    @Test
    fun postM3u() {
        val resp = exchange(post("mode=m3u&m3u=http%3A%2F%2Fh.example%2Fget.php%3Fusername%3Da%26password%3Db%26type%3Dm3u_plus"))
        assertOkHtml(resp)
        val form = awaitForm()
        assertEquals("m3u", form["mode"])
        assertEquals("http://h.example/get.php?username=a&password=b&type=m3u_plus", form["m3u"])
    }

    @Test
    fun bodySplitAcrossSeveralWrites() {
        val body = "mode=xtream&server=http%3A%2F%2Fa.b%3A8080&user=u%40x&pass=p%26%3D%C3%A9"
        val head = post(body).removeSuffix(body)
        val resp = exchange(head.substring(0, 20), head.substring(20), body.substring(0, 17), body.substring(17, 40), body.substring(40))
        assertOkHtml(resp)
        assertEquals("p&=é", awaitForm()["pass"])
    }

    @Test
    fun servesSeveralSequentialClients() {
        assertOkHtml(exchange(get))
        exchange(post("mode=m3u&m3u=x"))
        assertNotNull(awaitForm())
        assertOkHtml(exchange(get))
    }

    @Test
    fun requestsWithoutTokenAreNotFound() {
        val host = "Host: 127.0.0.1:$serverPort\r\n"
        assertTrue(exchange("GET / HTTP/1.1\r\n$host\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(exchange("GET /status HTTP/1.1\r\n$host\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(exchange("GET /${server.token}x/ HTTP/1.1\r\n$host\r\n").startsWith("HTTP/1.1 404"))
        // POST sans jeton : le formulaire n'est pas transmis.
        val body = "mode=m3u&m3u=x"
        exchange("POST / HTTP/1.1\r\n${host}Content-Length: ${body.length}\r\n\r\n$body")
        assertTrue(received.poll(300, TimeUnit.MILLISECONDS) == null)
        // Sans barre finale : servi aussi (la balise <base> rétablit les chemins relatifs).
        assertOkHtml(exchange("GET /${server.token} HTTP/1.1\r\n$host\r\n"))
    }

    @Test
    fun rejectsDnsNameHostAndForeignOrigin() {
        val path = "/${server.token}/"
        assertTrue(exchange("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(exchange("GET $path HTTP/1.1\r\nHost: evil.example:$serverPort\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(exchange("GET $path HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(exchange("GET $path HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n").startsWith("HTTP/1.1 403"))
        val body = "mode=m3u&m3u=x"
        val csrf = exchange(
            "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$serverPort\r\nOrigin: http://evil.example\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
        )
        assertTrue(csrf.startsWith("HTTP/1.1 403"))
        assertTrue(received.poll(300, TimeUnit.MILLISECONDS) == null)
        // Même origine que la page : accepté.
        exchange(
            "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$serverPort\r\nOrigin: http://127.0.0.1:$serverPort\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
        )
        assertNotNull(awaitForm())
    }

    @Test
    fun slowClientDoesNotBlockOthers() {
        connect().use { idle ->
            idle.getOutputStream().write("GET /${server.token}/ HTTP/1.1\r\n".toByteArray())
            idle.getOutputStream().flush()
            // Le premier client n'a pas fini sa requête : un second est servi quand même.
            assertOkHtml(exchange(get))
        }
    }

    @Test
    fun routeUnderToken() {
        val t = "AbC_-123"
        assertEquals("/", PhoneSetupServer.routeUnder(t, "/$t"))
        assertEquals("/", PhoneSetupServer.routeUnder(t, "/$t/"))
        assertEquals("/status", PhoneSetupServer.routeUnder(t, "/$t/status"))
        assertEquals("/undo?profile=a", PhoneSetupServer.routeUnder(t, "/$t/undo?profile=a"))
        assertEquals("/?x=1", PhoneSetupServer.routeUnder(t, "/$t?x=1"))
        assertEquals(null, PhoneSetupServer.routeUnder(t, "/"))
        assertEquals(null, PhoneSetupServer.routeUnder(t, "/status"))
        assertEquals(null, PhoneSetupServer.routeUnder(t, "/${t}x/status"))
        assertEquals(null, PhoneSetupServer.routeUnder(t, "/AbC_-12/"))
        assertEquals(null, PhoneSetupServer.routeUnder(t, "http://h/$t/"))
    }

    @Test
    fun stopBeforeListenLeavesNoServer() {
        val other = PhoneSetupServer { }
        other.stop()
        assertEquals(null, other.listen())
    }

    @Test
    fun tokensAreRandomAndUrlSafe() {
        val a = PhoneSetupServer { }.token
        val b = PhoneSetupServer { }.token
        assertTrue(a != b)
        assertTrue(a, a.matches(Regex("[A-Za-z0-9_-]{22}")))
    }

    @Test
    fun stopClosesPort() {
        assertOkHtml(exchange(get))
        server.stop()
        try {
            connect().close()
            fail("port $serverPort still accepting connections after stop()")
        } catch (_: ConnectException) {
            // attendu
        }
    }

    @Test
    fun startWithoutLanReturnsNullOrUrl() {
        val other = PhoneSetupServer { }
        try {
            val url = other.start()
            if (PhoneSetupServer.lanAddress() == null) assertEquals(null, url)
            else assertTrue(url!!, url.matches(Regex("http://[0-9.]+:\\d+/[A-Za-z0-9_-]{22}/")))
        } finally {
            other.stop()
        }
    }
}
