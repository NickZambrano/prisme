package com.fluxfr.tv.data

import android.content.Context
import com.fluxfr.tv.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Petit client HTTP pour les API Google (OAuth et Drive). Ne journalise jamais ni les jetons,
 * ni le secret client, ni l'adresse e-mail du compte.
 */
internal object GoogleHttp {
    class Response(val code: Int, val body: String, val etag: String?)

    fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        // HttpURLConnection ne connaît pas PATCH : les API Google acceptent X-HTTP-Method-Override.
        val realMethod = if (method == "PATCH") "POST" else method
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = realMethod
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("User-Agent", "Prisme/1.0 (Android TV)")
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    fun form(vararg fields: Pair<String, String>): ByteArray =
        fields.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray(Charsets.UTF_8)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    const val FORM = "application/x-www-form-urlencoded"
}

/** Lecture des réponses OAuth Google (logique pure, testée). */
object GoogleOAuthParse {
    class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    class Tokens(
        val access: String,
        val refresh: String?,
        val expiresInSec: Long,
        val scope: String?,
        val email: String?,
    )

    fun deviceCode(text: String): DeviceCode {
        val m = MiniJson.parse(text) as Map<*, *>
        return DeviceCode(
            deviceCode = m["device_code"] as String,
            userCode = (m["user_code"] as String).trim().uppercase(),
            verificationUrl = (m["verification_url"] as? String) ?: (m["verification_uri"] as? String)
                ?: "https://www.google.com/device",
            expiresInSec = (m["expires_in"] as? Number)?.toInt() ?: 1800,
            intervalSec = (m["interval"] as? Number)?.toInt() ?: 5,
        )
    }

    fun tokens(text: String): Tokens {
        val m = MiniJson.parse(text) as Map<*, *>
        return Tokens(
            access = m["access_token"] as String,
            refresh = (m["refresh_token"] as? String)?.takeIf { it.isNotEmpty() },
            expiresInSec = (m["expires_in"] as? Number)?.toLong() ?: 3600,
            scope = m["scope"] as? String,
            email = (m["id_token"] as? String)?.let(::emailFromIdToken),
        )
    }

    /** Code d'erreur OAuth ("authorization_pending", "slow_down", "invalid_grant"…), null si absent. */
    fun error(text: String): String? =
        runCatching { (MiniJson.parse(text) as? Map<*, *>)?.get("error") as? String }.getOrNull()

    /** Adresse e-mail lue dans la partie centrale du jeton d'identité (JWT), sans vérification de signature. */
    fun emailFromIdToken(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val json = String(base64UrlDecode(payload), Charsets.UTF_8)
        (MiniJson.parse(json) as? Map<*, *>)?.get("email") as? String
    }.getOrNull()

    /** Décodeur base64url (java.util.Base64 n'existe qu'à partir d'Android 8). */
    internal fun base64UrlDecode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = s.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) throw IllegalArgumentException("base64")
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    sealed interface Poll {
        data class Authorized(val tokens: Tokens) : Poll
        data object Pending : Poll
        data object SlowDown : Poll
        data object Denied : Poll
        data object Expired : Poll
        data class Failed(val message: String) : Poll
    }

    fun poll(code: Int, text: String): Poll {
        if (code in 200..299) return runCatching { Poll.Authorized(tokens(text)) }
            .getOrElse { Poll.Failed("Réponse Google illisible") }
        return when (error(text)) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "access_denied" -> Poll.Denied
            "expired_token" -> Poll.Expired
            "invalid_client", "unauthorized_client" -> Poll.Failed("Application Google refusée (identifiant client)")
            else -> if (code == 428) Poll.Pending else Poll.Failed("Google a répondu HTTP $code")
        }
    }
}

/**
 * Compte Google de chaque profil (connexion par code « device » : QR code + code à saisir sur le téléphone).
 * Seul le jeton de rafraîchissement et l'adresse affichée sont gardés, dans un fichier de préférences
 * privé « google_auth » ; le jeton d'accès reste en mémoire et se renouvelle tout seul.
 */
object GoogleAuth {
    const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val SCOPES = "openid email $DRIVE_SCOPE"
    private const val PREFS = "google_auth"

    class AuthError(val code: Int, val error: String?) : IOException("Google : HTTP $code")

    private class Access(val token: String, val expiresAt: Long)
    private val access = HashMap<String, Access>()

    fun isConfigured(): Boolean = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

    fun isLinked(ctx: Context, profile: String): Boolean =
        isConfigured() && !prefs(ctx).getString("refresh_$profile", null).isNullOrEmpty()

    fun email(ctx: Context, profile: String): String? = prefs(ctx).getString("email_$profile", null)?.takeIf { it.isNotBlank() }

    /** Demande un code (réseau : hors du fil principal). */
    fun deviceCode(): GoogleOAuthParse.DeviceCode {
        val r = GoogleHttp.request(
            "POST", DEVICE_URL,
            GoogleHttp.form("client_id" to BuildConfig.GOOGLE_CLIENT_ID, "scope" to SCOPES), GoogleHttp.FORM,
        )
        if (r.code !in 200..299) throw AuthError(r.code, GoogleOAuthParse.error(r.body))
        return GoogleOAuthParse.deviceCode(r.body)
    }

    fun poll(deviceCode: String): GoogleOAuthParse.Poll {
        val r = GoogleHttp.request(
            "POST", TOKEN_URL,
            GoogleHttp.form(
                "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
            GoogleHttp.FORM,
        )
        return GoogleOAuthParse.poll(r.code, r.body)
    }

    /** Enregistre la connexion d'un profil ; false si Google n'a pas donné de jeton de rafraîchissement. */
    fun save(ctx: Context, profile: String, t: GoogleOAuthParse.Tokens): Boolean {
        val refresh = t.refresh ?: return false
        prefs(ctx).edit()
            .putString("refresh_$profile", refresh)
            .putString("email_$profile", t.email ?: "")
            .apply()
        synchronized(access) { access[profile] = Access(t.access, System.currentTimeMillis() + t.expiresInSec * 1000) }
        return true
    }

    /** Le jeton accorde-t-il l'accès à Drive ? (l'utilisateur peut décocher la case sur l'écran de consentement) */
    fun hasDriveScope(t: GoogleOAuthParse.Tokens): Boolean = t.scope == null || t.scope.split(' ').contains(DRIVE_SCOPE)

    /**
     * Jeton d'accès valide (renouvelé une minute avant expiration), ou null si le profil n'est pas lié.
     * Accès révoqué (invalid_grant) : le profil est délié et [onRevoked] est appelé.
     * Lève IOException sur erreur réseau.
     */
    fun validToken(ctx: Context, profile: String, force: Boolean = false, onRevoked: () -> Unit = {}): String? {
        if (!isConfigured()) return null
        val refresh = prefs(ctx).getString("refresh_$profile", null)?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(access) {
            val a = access[profile]
            if (!force && a != null && a.expiresAt - System.currentTimeMillis() > 60_000) return a.token
        }
        val r = GoogleHttp.request(
            "POST", TOKEN_URL,
            GoogleHttp.form(
                "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            ),
            GoogleHttp.FORM,
        )
        if (r.code in 200..299) {
            val t = GoogleOAuthParse.tokens(r.body)
            synchronized(access) { access[profile] = Access(t.access, System.currentTimeMillis() + t.expiresInSec * 1000) }
            // Google peut renvoyer un nouveau jeton de rafraîchissement : on le garde.
            if (t.refresh != null && t.refresh != refresh) prefs(ctx).edit().putString("refresh_$profile", t.refresh).apply()
            return t.access
        }
        val err = GoogleOAuthParse.error(r.body)
        if (err == "invalid_grant" || err == "unauthorized_client") {
            clear(ctx, profile)
            onRevoked()
            return null
        }
        throw AuthError(r.code, err)
    }

    /** Déconnexion : révoque l'accès chez Google (réseau, hors du fil principal ; erreurs ignorées) puis oublie tout. */
    fun disconnect(ctx: Context, profile: String) {
        val refresh = prefs(ctx).getString("refresh_$profile", null)
        if (!refresh.isNullOrEmpty()) runCatching {
            GoogleHttp.request("POST", REVOKE_URL, GoogleHttp.form("token" to refresh), GoogleHttp.FORM)
        }
        clear(ctx, profile)
    }

    fun clear(ctx: Context, profile: String) {
        synchronized(access) { access.remove(profile) }
        prefs(ctx).edit().remove("refresh_$profile").remove("email_$profile").apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
