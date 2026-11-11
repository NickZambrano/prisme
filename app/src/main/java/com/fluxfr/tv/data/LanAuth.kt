package com.fluxfr.tv.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Authentification des échanges entre TV du réseau local (sans dépendance Android : testable sur la JVM).
 *
 * Modèle de menace : réseau domestique de confiance. Ces protections arrêtent un appareil quelconque du
 * réseau local (invité, objet connecté, appli curieuse) et les pages web malveillantes (CSRF, DNS
 * rebinding) ; elles ne prétendent pas résister à un attaquant actif placé sur le chemin (ARP spoofing…) :
 * le transport reste du HTTP en clair, et l'association par code à 6 chiffres n'est pas un vrai PAKE
 * (un espion passif qui capture l'échange peut retrouver le code hors ligne).
 */

/** Primitives : HMAC-SHA256, SHA-256, hexadécimal, aléa, comparaison à temps constant. */
internal object LanCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha256(s: String): ByteArray = sha256(s.toByteArray(Charsets.UTF_8))

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    fun hmac(key: ByteArray, data: String): ByteArray = hmac(key, data.toByteArray(Charsets.UTF_8))

    fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) {
            val v = x.toInt() and 0xFF
            append(HEX[v shr 4]); append(HEX[v and 15])
        }
    }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Comparaison à temps constant (ne révèle pas la position de la première différence). */
    fun equal(a: ByteArray?, b: ByteArray?): Boolean = a != null && b != null && MessageDigest.isEqual(a, b)
    fun equalHex(a: String?, b: String?): Boolean =
        a != null && b != null && equal(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    /** Jeton aléatoire pour une URL : [bytes] octets en base64 « URL » sans remplissage. */
    fun urlToken(bytes: Int = 16): String =
        Base64Codec.encode(randomBytes(bytes)).replace('+', '-').replace('/', '_').trimEnd('=')

    fun isHexNonce(s: String?, bytes: Int = 16): Boolean =
        s != null && s.length == bytes * 2 && s.all { Character.digit(it, 16) >= 0 }

    private const val HEX = "0123456789abcdef"
}

/** Nonces déjà vus (les plus récents) : un même nonce n'est accepté qu'une fois. */
internal class NonceCache(private val capacity: Int = 256) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** true la première fois que [nonce] est présenté, false ensuite (rejeu). */
    @Synchronized
    fun firstUse(nonce: String): Boolean {
        val k = nonce.lowercase()
        if (seen.containsKey(k)) return false
        seen[k] = Unit
        return true
    }
}

/**
 * Preuve pour GET /prisme/apk d'une version personnelle (APK avec identifiants IPTV intégrés) : seule une
 * TV qui possède les MÊMES identifiants intégrés peut la produire. La clé n'est jamais transmise.
 */
internal object ApkProof {
    fun key(server: String, user: String, pass: String): ByteArray = LanCrypto.sha256("prisme-apk|$server|$user|$pass")

    fun mac(key: ByteArray, nonce: String, versionCode: Int): String = LanCrypto.hex(LanCrypto.hmac(key, "$nonce|$versionCode"))

    /** Paramètres de requête à ajouter à l'URL de l'APK (nonce neuf à chaque appel). */
    fun query(key: ByteArray, versionCode: Int): String {
        val nonce = LanCrypto.hex(LanCrypto.randomBytes(16))
        return "nonce=$nonce&v=$versionCode&mac=" + mac(key, nonce, versionCode)
    }

    /** Vérifie la preuve (à temps constant) pour la version [servedVersion] ; un nonce rejoué est refusé. */
    fun verify(key: ByteArray, servedVersion: Int, query: Map<String, String>, cache: NonceCache): Boolean {
        val nonce = query["nonce"]
        val v = query["v"]?.toIntOrNull()
        val mac = query["mac"]
        if (!LanCrypto.isHexNonce(nonce) || v != servedVersion || mac == null) return false
        if (!LanCrypto.equalHex(mac, mac(key, nonce!!, v))) return false
        return cache.firstUse(nonce)
    }
}

/**
 * Requêtes signées avec la clé du foyer (TV associées) : en-têtes X-Prisme-Nonce, X-Prisme-Time et
 * X-Prisme-Auth = HMAC(clé, nonce|heure|méthode|chemin|SHA-256(corps)). La réponse porte X-Prisme-Sig =
 * HMAC(clé, "resp"|nonce|SHA-256(corps)) : le client sait qu'elle vient bien d'une TV du foyer.
 */
internal object HouseholdAuth {
    const val H_NONCE = "X-Prisme-Nonce"
    const val H_TIME = "X-Prisme-Time"
    const val H_AUTH = "X-Prisme-Auth"
    const val H_SIG = "X-Prisme-Sig"
    /** Décalage d'horloge toléré entre deux TV. */
    const val MAX_SKEW_MS = 10 * 60_000L

    private fun bodyHash(body: ByteArray) = LanCrypto.hex(LanCrypto.sha256(body))

    fun mac(key: ByteArray, nonce: String, time: Long, method: String, path: String, body: ByteArray): String =
        LanCrypto.hex(LanCrypto.hmac(key, "$nonce|$time|${method.uppercase()}|$path|${bodyHash(body)}"))

    /** En-têtes d'une requête signée. */
    fun sign(key: ByteArray, method: String, path: String, body: ByteArray, now: Long = System.currentTimeMillis()): Map<String, String> {
        val nonce = LanCrypto.hex(LanCrypto.randomBytes(16))
        return mapOf(H_NONCE to nonce, H_TIME to now.toString(), H_AUTH to mac(key, nonce, now, method, path, body))
    }

    /** Vérifie une requête reçue ; [header] lit un en-tête (nom insensible à la casse). */
    fun verify(
        key: ByteArray, method: String, path: String, body: ByteArray, header: (String) -> String?,
        cache: NonceCache, now: Long = System.currentTimeMillis(),
    ): Boolean {
        val nonce = header(H_NONCE)
        val time = header(H_TIME)?.toLongOrNull() ?: return false
        val auth = header(H_AUTH) ?: return false
        if (!LanCrypto.isHexNonce(nonce)) return false
        if (kotlin.math.abs(now - time) > MAX_SKEW_MS) return false
        if (!LanCrypto.equalHex(auth, mac(key, nonce!!, time, method, path, body))) return false
        return cache.firstUse(nonce)
    }

    fun responseSig(key: ByteArray, nonce: String, body: ByteArray): String =
        LanCrypto.hex(LanCrypto.hmac(key, "resp|$nonce|${bodyHash(body)}"))
}

/**
 * Association de deux TV par un code à 6 chiffres affiché pendant 2 minutes sur la TV « hôte ».
 *
 *  1. L'invitée demande un défi (POST /prisme/pair/hello) → `c` aléatoire.
 *  2. Elle répond avec son nonce `n` et la preuve HMAC(SHA-256("prisme-pair|"+code), "claim|c|n").
 *  3. L'hôte vérifie (5 essais au plus, puis nouveau code) et renvoie la clé du foyer masquée par
 *     HMAC(clé du code, "wrap|c|n"), avec une étiquette prouvant qu'il connaît aussi le code.
 *
 * Voir le modèle de menace en tête de fichier : protège contre un appareil du réseau qui tente sa chance,
 * pas contre un espion qui capture l'échange (le code se retrouve alors par force brute hors ligne).
 */
internal class PairingHost(
    private val now: () -> Long = System::currentTimeMillis,
    private val newCode: () -> String = { (100_000 + java.security.SecureRandom().nextInt(900_000)).toString() },
) {
    companion object {
        const val VALIDITY_MS = 2 * 60_000L
        const val MAX_ATTEMPTS = 5
    }

    var code: String? = null
        private set
    var expiresAt: Long = 0L
        private set
    private var attempts = 0
    private var challenge: String? = null

    @Synchronized
    fun start(): String {
        val c = newCode()
        code = c
        expiresAt = now() + VALIDITY_MS
        attempts = 0
        challenge = null
        return c
    }

    @Synchronized
    fun stop() {
        code = null
        challenge = null
    }

    @Synchronized
    fun active(): Boolean = code != null && now() < expiresAt

    /** Défi à transmettre à l'invitée (null si aucune association en cours). */
    @Synchronized
    fun hello(): String? {
        if (!active()) return null
        return challenge ?: LanCrypto.hex(LanCrypto.randomBytes(16)).also { challenge = it }
    }

    sealed interface Result {
        /** Clé du foyer masquée (hex) et étiquette de l'hôte. */
        data class Ok(val wrapped: String, val tag: String) : Result
        /** Mauvais code ; [codeChanged] si le nombre d'essais est épuisé (nouveau code affiché). */
        data class Denied(val codeChanged: Boolean) : Result
        data object Inactive : Result
    }

    @Synchronized
    fun claim(c: String?, n: String?, proof: String?, householdKey: ByteArray): Result {
        val code = this.code
        if (code == null || !active()) return Result.Inactive
        val expected = challenge
        // Le défi est à usage unique, réussi ou non.
        challenge = null
        if (expected == null || c == null || !LanCrypto.equalHex(c, expected) || !LanCrypto.isHexNonce(n) || proof == null) {
            return deny()
        }
        val k = PairingCrypto.codeKey(code)
        if (!LanCrypto.equalHex(proof, PairingCrypto.proof(k, c, n!!))) return deny()
        stop()
        val wrapped = PairingCrypto.wrap(k, c, n, householdKey)
        return Result.Ok(wrapped, PairingCrypto.tag(k, c, n, wrapped))
    }

    private fun deny(): Result {
        attempts++
        if (attempts >= MAX_ATTEMPTS) {
            start()
            return Result.Denied(codeChanged = true)
        }
        return Result.Denied(codeChanged = false)
    }
}

/** Calculs de l'association, partagés par l'hôte et l'invitée. */
internal object PairingCrypto {
    fun isCode(s: String) = s.length == 6 && s.all { it in '0'..'9' }

    fun codeKey(code: String): ByteArray = LanCrypto.sha256("prisme-pair|$code")

    fun proof(codeKey: ByteArray, c: String, n: String): String = LanCrypto.hex(LanCrypto.hmac(codeKey, "claim|$c|$n"))

    private fun mask(codeKey: ByteArray, c: String, n: String): ByteArray = LanCrypto.hmac(codeKey, "wrap|$c|$n")

    fun wrap(codeKey: ByteArray, c: String, n: String, key: ByteArray): String {
        require(key.size == 32)
        val m = mask(codeKey, c, n)
        return LanCrypto.hex(ByteArray(32) { (key[it].toInt() xor m[it].toInt()).toByte() })
    }

    fun tag(codeKey: ByteArray, c: String, n: String, wrapped: String): String =
        LanCrypto.hex(LanCrypto.hmac(codeKey, "resp|$c|$n|${wrapped.lowercase()}"))

    /** Côté invitée : vérifie l'étiquette de l'hôte et démasque la clé ; null si la réponse est fausse. */
    fun unwrap(codeKey: ByteArray, c: String, n: String, wrapped: String, tag: String): ByteArray? {
        if (!LanCrypto.equalHex(tag, tag(codeKey, c, n, wrapped))) return null
        val w = LanCrypto.unhex(wrapped)?.takeIf { it.size == 32 } ?: return null
        val m = mask(codeKey, c, n)
        return ByteArray(32) { (w[it].toInt() xor m[it].toInt()).toByte() }
    }
}
