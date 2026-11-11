package com.fluxfr.tv.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Petits outils HTTP/1.1 pour les serveurs locaux de l'appli (page du téléphone, échanges entre TV).
 * Lecture octet par octet (le corps peut être binaire : APK envoyé depuis le téléphone). Sans dépendance
 * Android : testable sur la JVM.
 */
internal object LanHttp {
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val input: InputStream,
    ) {
        val contentLength: Long get() = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class TooLarge : IOException("Fichier trop volumineux")

    /** Lit la ligne de requête et les en-têtes ; null si la requête est vide ou invalide. */
    fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (++count > 100) return null
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Request(parts[0].uppercase(), parts[1], headers, input)
    }

    /** Une ligne terminée par LF (CR retiré), 8 Ko au plus ; null en fin de flux sans rien lu. */
    fun readLine(input: InputStream, max: Int = 8192): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (buf.size() >= max) throw IOException("Ligne trop longue")
            buf.write(b)
        }
        return String(buf.toByteArray(), Charsets.UTF_8).removeSuffix("\r")
    }

    /** Lit exactement [length] octets (moins si le flux se termine avant). */
    fun readBody(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }

    fun respond(out: OutputStream, code: Int, contentType: String, body: ByteArray, extraHeaders: Map<String, String> = emptyMap()) {
        out.write(head(code, contentType, body.size.toLong(), extraHeaders))
        out.write(body)
        out.flush()
    }

    fun respondText(out: OutputStream, code: Int, text: String, contentType: String = "text/plain; charset=utf-8") =
        respond(out, code, contentType, text.toByteArray(Charsets.UTF_8))

    fun head(code: Int, contentType: String, length: Long, extraHeaders: Map<String, String> = emptyMap()): ByteArray =
        ("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $contentType\r\n" +
            "Content-Length: $length\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            extraHeaders.entries.joinToString("") { (k, v) -> "$k: $v\r\n" } +
            "Connection: close\r\n\r\n").toByteArray()

    /** Paramètres d'une chaîne de requête (« a=1&b=2 », décodés) ; les doublons gardent la première valeur. */
    fun queryParams(path: String): Map<String, String> {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (part in q.split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val k = runCatching { java.net.URLDecoder.decode(part.substring(0, i), "UTF-8") }.getOrNull() ?: continue
            val v = runCatching { java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull() ?: continue
            m.putIfAbsent(k, v)
        }
        return m
    }

    // ------------------------------------------------------------------------- Host / Origin

    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

    /** Adresse IP littérale (IPv4 pointée, ou IPv6 sans crochets) — jamais un nom DNS. */
    fun isIpLiteral(host: String): Boolean {
        if (IPV4.matches(host)) return true
        val h = host.substringBefore('%') // zone IPv6 (fe80::1%wlan0)
        if (':' !in h || h.length > 45) return false
        if (!h.all { Character.digit(it, 16) >= 0 || it == ':' || it == '.' }) return false
        return runCatching { InetAddress.getByName(h) is Inet6Address }.getOrDefault(false)
    }

    /** « 192.168.1.5:8686 » ou « [fe80::1]:8686 » → (hôte, port) ; null si invalide ou sans port. */
    fun splitHostPort(value: String): Pair<String, Int>? {
        val v = value.trim()
        return if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 0 || v.getOrNull(end + 1) != ':') return null
            val port = v.substring(end + 2).toIntOrNull() ?: return null
            v.substring(1, end) to port
        } else {
            val i = v.lastIndexOf(':')
            if (i <= 0 || v.indexOf(':') != i) return null
            val port = v.substring(i + 1).toIntOrNull() ?: return null
            v.substring(0, i) to port
        }
    }

    /**
     * En-tête Host accepté : adresse IP littérale avec le port du serveur. Un nom DNS est refusé, ce qui
     * déjoue le « DNS rebinding » (une page web qui ferait pointer son propre nom vers la TV).
     */
    fun hostAllowed(host: String?, port: Int): Boolean {
        val hp = host?.let { splitHostPort(it) } ?: return false
        return hp.second == port && isIpLiteral(hp.first)
    }

    /**
     * En-tête Origin accepté : absent (appels de l'appli entre TV, navigation directe), ou exactement
     * « http://<Host> » (la page servie par la TV elle-même). Toute autre origine — y compris « null » —
     * est refusée : une page web ouverte sur le téléphone ne peut pas piloter la TV (CSRF).
     */
    fun originAllowed(origin: String?, host: String?): Boolean {
        if (origin == null) return true
        if (host == null) return false
        return origin.trim().equals("http://" + host.trim(), ignoreCase = true)
    }

    /** Contrôles communs à toutes les requêtes des serveurs locaux. */
    fun requestAllowed(req: Request, port: Int): Boolean =
        hostAllowed(req.header("host"), port) && originAllowed(req.header("origin"), req.header("host"))

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> if (code < 400) "OK" else "Error"
    }

    /**
     * Adresse du réseau local (ou de la machine elle-même) ? Les serveurs de l'appli ne répondent qu'à
     * celles-ci : l'APK contient les identifiants IPTV, il ne doit jamais partir vers Internet.
     */
    fun isLanAddress(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        // IPv6 « unique local » (fc00::/7).
        if (a is Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC
        return false
    }

    /** Délimiteur d'un en-tête Content-Type multipart/form-data, ou null. */
    fun boundaryOf(contentType: String?): String? {
        if (contentType == null || !contentType.trim().startsWith("multipart/form-data", ignoreCase = true)) return null
        val b = contentType.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
        return b?.takeIf { it.isNotEmpty() && it.length <= 200 }
    }

    class FilePart(val fieldName: String, val fileName: String, val size: Long)

    /**
     * Parcourt un corps multipart/form-data et copie le PREMIER champ fichier vers [out], sans tout
     * garder en mémoire. Les champs texte qui le précèdent sont ignorés. Lève [TooLarge] au-delà de
     * [limit] octets de fichier, [EOFException] si le corps est tronqué. Null s'il n'y a pas de fichier.
     */
    fun copyFirstFilePart(input: InputStream, boundary: String, out: OutputStream, limit: Long): FilePart? {
        val delimiter = "--$boundary"
        val marker = ("\r\n" + delimiter).toByteArray(Charsets.ISO_8859_1)
        val pin = PushbackInputStream(input, 64 * 1024 + marker.size)
        // Préambule : jusqu'au premier délimiteur.
        while (true) {
            val line = readLine(pin) ?: return null
            if (line == "$delimiter--") return null
            if (line == delimiter) break
        }
        while (true) {
            var name = ""
            var fileName: String? = null
            while (true) {
                val h = readLine(pin) ?: throw EOFException()
                if (h.isEmpty()) break
                if (h.startsWith("Content-Disposition:", ignoreCase = true)) {
                    name = param(h, "name") ?: ""
                    fileName = param(h, "filename")
                }
            }
            if (fileName != null) {
                val size = copyUntil(pin, marker, out, limit)
                return FilePart(name, fileName, size)
            }
            copyUntil(pin, marker, NullOut, 1L shl 20)
            // Reste de la ligne du délimiteur : "" (une autre partie suit) ou "--" (fin).
            val rest = readLine(pin) ?: return null
            if (rest.startsWith("--")) return null
        }
    }

    private fun param(header: String, key: String): String? =
        Regex("""(?:^|;)\s*${Regex.escape(key)}="([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
            ?: Regex("""(?:^|;)\s*${Regex.escape(key)}=([^;\s]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)

    /** Copie jusqu'à [marker] (exclu) ; les octets lus au-delà sont remis dans le flux. Renvoie la taille copiée. */
    private fun copyUntil(input: PushbackInputStream, marker: ByteArray, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024 + marker.size)
        var len = 0
        var total = 0L
        while (true) {
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) throw EOFException()
            len += n
            val idx = indexOf(buf, len, marker)
            if (idx >= 0) {
                total += idx
                if (total > limit) throw TooLarge()
                out.write(buf, 0, idx)
                val after = idx + marker.size
                if (after < len) input.unread(buf, after, len - after)
                return total
            }
            val safe = len - (marker.size - 1)
            if (safe > 0) {
                total += safe
                if (total > limit) throw TooLarge()
                out.write(buf, 0, safe)
                System.arraycopy(buf, safe, buf, 0, len - safe)
                len -= safe
            }
        }
    }

    internal fun indexOf(buf: ByteArray, len: Int, pattern: ByteArray): Int {
        val first = pattern[0]
        var i = 0
        val last = len - pattern.size
        outer@ while (i <= last) {
            if (buf[i] != first) { i++; continue }
            for (j in 1 until pattern.size) if (buf[i + j] != pattern[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
