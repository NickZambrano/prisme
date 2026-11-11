package com.fluxfr.tv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    @Volatile var userAgent: String = Settings.DEFAULT_UA

    /**
     * Ouvre une connexion ; si le certificat HTTPS est invalide, réessaie en HTTP. Ce repli est VOULU (le
     * fournisseur du propriétaire présente un certificat qui ne correspond pas à son nom) : ne pas le retirer.
     */
    fun open(url: String): HttpURLConnection = try {
        openOnce(url)
    } catch (e: javax.net.ssl.SSLException) {
        if (!url.startsWith("https://", ignoreCase = true)) throw e
        openOnce("http://" + url.substringAfter("://"))
    }

    /**
     * Suit les redirections. Une redirection https → http n'est suivie que si l'adresse demandée était déjà
     * en http (serveur configuré en http, ou repli ci-dessus) : sinon un intermédiaire pourrait faire
     * repasser en clair, sans bruit, une requête qui porte les identifiants dans l'URL.
     */
    private fun openOnce(url: String): HttpURLConnection {
        var current = url
        var redirects = 0
        val allowDowngrade = !url.startsWith("https://", ignoreCase = true)
        while (true) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 90_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
            }
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("Redirection invalide")
                val next = URL(URL(current), loc)
                if (!allowDowngrade && current.startsWith("https://", ignoreCase = true) && next.protocol.equals("http", ignoreCase = true)) {
                    throw IOException("Redirection de HTTPS vers HTTP refusée")
                }
                current = next.toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("Le serveur a répondu HTTP $code")
            }
            return c
        }
    }
}

/** Petits utilitaires de lecture JSON en flux (léger en mémoire, même pour 50 000 films). */
object Json {
    fun primitive(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    /** Lit un objet en ne gardant que les valeurs simples. */
    fun readFlat(r: JsonReader): Map<String, String?> {
        val m = HashMap<String, String?>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            m[k] = primitive(r)
        }
        r.endObject()
        return m
    }

    fun forEachObject(r: JsonReader, fn: (Map<String, String?>) -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonToken.BEGIN_OBJECT) fn(readFlat(r)) else r.skipValue()
        }
        r.endArray()
    }
}
