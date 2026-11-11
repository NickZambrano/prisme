package com.fluxfr.tv.data.files

import com.fluxfr.tv.data.MiniJson
import java.io.ByteArrayOutputStream

/**
 * Partage réseau (SMB) enregistré : hôte (nom ou IP), chemin de départ facultatif (« Films/HD », vide = liste
 * des partages de l'hôte), identifiants facultatifs (vides = invité). [allowSmb1] : vieux NAS en SMB1 seulement.
 * Le mot de passe n'apparaît jamais dans [toString] (journaux), ni dans les URI de lecture ([SmbPaths]).
 */
data class SmbShare(
    val id: String,
    val label: String,
    val host: String,
    val path: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val allowSmb1: Boolean = false,
) {
    val guest: Boolean get() = user.isBlank()
    val displayName: String get() = label.ifBlank { (listOf(host) + SmbPaths.segments(path)).joinToString("/") }
    /** Segments du chemin de départ (le premier est le nom du partage). */
    val rootSegments: List<String> get() = SmbPaths.segments(path)

    override fun toString(): String =
        "SmbShare(id=$id, label=$label, host=$host, path=$path, user=$user, password=${if (password.isEmpty()) "" else "***"}, domain=$domain, allowSmb1=$allowSmb1)"

    companion object {
        /** Liste enregistrée (JSON) ; le mot de passe est stocké dans les préférences privées de l'appli. */
        fun encodeList(list: List<SmbShare>): String = MiniJson.write(
            list.map {
                mapOf(
                    "id" to it.id, "label" to it.label, "host" to it.host, "path" to it.path, "user" to it.user,
                    "password" to it.password, "domain" to it.domain, "smb1" to it.allowSmb1,
                )
            }
        )

        fun decodeList(text: String?): List<SmbShare> {
            if (text.isNullOrBlank()) return emptyList()
            val l = runCatching { MiniJson.parse(text) as? List<*> }.getOrNull() ?: return emptyList()
            return l.mapNotNull { o ->
                val m = o as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val host = (m["host"] as? String)?.trim().orEmpty()
                if (id.isBlank() || host.isBlank()) return@mapNotNull null
                SmbShare(
                    id = id, label = m["label"] as? String ?: "", host = host, path = m["path"] as? String ?: "",
                    user = m["user"] as? String ?: "", password = m["password"] as? String ?: "",
                    domain = m["domain"] as? String ?: "", allowSmb1 = m["smb1"] as? Boolean ?: false,
                )
            }
        }
    }
}

/**
 * Chemins SMB et URI internes de lecture. Un fichier d'un partage est désigné, pour le lecteur et la reprise,
 * par `prisme-smb://<id du partage>/<partage>/<dossier>/<fichier>` : jamais d'identifiants ni d'hôte dans l'URI,
 * résolus à l'ouverture ([com.fluxfr.tv.player.SmbDataSource]).
 */
object SmbPaths {
    const val SCHEME = "prisme-smb"

    /** « Films/HD », « \Films\HD\ », « /Films//HD » → [Films, HD]. */
    fun segments(path: String): List<String> =
        path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

    /**
     * Saisie de l'hôte : « nas », « 192.168.1.20 », « \\nas\Films », « smb://nas/Films/HD ».
     * Renvoie (hôte, chemin) ; le chemin éventuel est ajouté devant [extraPath].
     */
    fun parseHostInput(input: String, extraPath: String = ""): Pair<String, String> {
        var s = input.trim()
        if (s.startsWith("smb://", ignoreCase = true)) s = s.substring(6)
        s = s.replace('\\', '/').trimStart('/')
        val host = s.substringBefore('/').trim()
        val inline = if ('/' in s) segments(s.substringAfter('/')) else emptyList()
        return host to (inline + segments(extraPath)).joinToString("/")
    }

    /** Clé stable (reprise de lecture) : « smb:<id>/<partage>/<chemin> ». */
    fun progressKey(shareId: String, segs: List<String>): String = "smb:$shareId/" + segs.joinToString("/")

    fun buildUri(shareId: String, segs: List<String>): String =
        "$SCHEME://" + encode(shareId) + "/" + segs.joinToString("/") { encode(it) }

    /** (id du partage, segments) d'une URI [buildUri] ; null si ce n'est pas une URI SMB interne. */
    fun parseUri(uri: String): Pair<String, List<String>>? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix, ignoreCase = true)) return null
        val rest = uri.substring(prefix.length).substringBefore('?').substringBefore('#')
        val parts = rest.split('/')
        val id = decode(parts.first())
        if (id.isEmpty()) return null
        val segs = parts.drop(1).filter { it.isNotEmpty() }.map { decode(it) }
        if (segs.isEmpty()) return null
        return id to segs
    }

    /** URL jcifs d'un hôte (« smb://nas/ ») ; les segments sont ajoutés un par un ([SmbClient]). */
    fun hostUrl(host: String): String = "smb://" + host.trim().trim('/') + "/"

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** Encodage « pourcent » d'un segment (UTF-8) : seuls les caractères non réservés restent tels quels. */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in UNRESERVED) sb.append(c)
            else sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun decode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString("UTF-8")
    }
}

/** Messages d'erreur SMB en français (codes NT des réponses, ou cause réseau). */
object SmbErrors {
    const val LOGON_FAILURE = 0xC000006D.toInt()
    const val WRONG_PASSWORD = 0xC000006A.toInt()
    const val ACCOUNT_RESTRICTION = 0xC000006E.toInt()
    const val ACCOUNT_DISABLED = 0xC0000072.toInt()
    const val ACCOUNT_LOCKED_OUT = 0xC0000234.toInt()
    const val PASSWORD_EXPIRED = 0xC0000071.toInt()
    const val ACCESS_DENIED = 0xC0000022.toInt()
    const val NETWORK_ACCESS_DENIED = 0xC00000CA.toInt()
    const val BAD_NETWORK_NAME = 0xC00000CC.toInt()
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()
    const val OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()
    const val NOT_SUPPORTED = 0xC00000BB.toInt()

    const val MSG_AUTH = "Identifiants refusés : vérifie l'utilisateur et le mot de passe."
    const val MSG_DENIED = "Accès refusé à ce dossier pour cet utilisateur."
    const val MSG_SHARE = "Partage introuvable : vérifie le nom du partage."
    const val MSG_PATH = "Dossier ou fichier introuvable sur le partage."
    const val MSG_HOST = "Hôte injoignable : vérifie l'adresse et que l'appareil est sur le même réseau."
    const val MSG_TIMEOUT = "Le partage ne répond pas (délai dépassé)."
    const val MSG_PROTOCOL = "Version SMB non prise en charge par ce partage (essaie « Autoriser SMB1 »)."

    /**
     * [status] : code NT (0 si inconnu) ; [chain] : noms simples des classes de la chaîne de causes ;
     * [messages] : leurs messages. Renvoie un message prêt à afficher.
     */
    fun describe(status: Int, chain: List<String>, messages: List<String>): String {
        when (status) {
            LOGON_FAILURE, WRONG_PASSWORD, ACCOUNT_RESTRICTION, ACCOUNT_DISABLED, ACCOUNT_LOCKED_OUT, PASSWORD_EXPIRED ->
                return MSG_AUTH
            ACCESS_DENIED, NETWORK_ACCESS_DENIED -> return MSG_DENIED
            BAD_NETWORK_NAME -> return MSG_SHARE
            OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND -> return MSG_PATH
            NOT_SUPPORTED -> return MSG_PROTOCOL
        }
        val names = chain.map { it.lowercase() }
        val text = messages.joinToString(" ").lowercase()
        return when {
            names.any { "unknownhost" in it } || "unknownhost" in text -> MSG_HOST
            names.any { "sockettimeout" in it } || "timed out" in text || "timeout" in text -> MSG_TIMEOUT
            names.any { "connect" in it || "noroutetohost" in it || "portunreachable" in it } ||
                "failed to connect" in text || "connection refused" in text || "unreachable" in text -> MSG_HOST
            "logon failure" in text || "authentication" in text -> MSG_AUTH
            "access is denied" in text || "access denied" in text -> MSG_DENIED
            "bad network name" in text -> MSG_SHARE
            "cannot find the file" in text || "not found" in text -> MSG_PATH
            "dialect" in text || "protocol" in text -> MSG_PROTOCOL
            else -> "Erreur du partage réseau." + (messages.firstOrNull { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }
    }
}
