package com.fluxfr.tv.data

import com.fluxfr.tv.tvhome.CatalogAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class LanSecurityTest {

    // ------------------------------------------------------------------ Type de build

    private fun peer(v: Int, flavour: String) = PeerInfo("192.168.1.$v", 8687, "TV$v", v, "v$v", flavour)

    @Test
    fun updaterIgnoresPeersOfAnotherFlavour() {
        val peers = listOf(peer(30, Flavour.PUBLIC), peer(25, Flavour.PERSONAL), peer(40, ""), peer(20, Flavour.PERSONAL))
        assertEquals(25, Flavour.pickUpdate(peers, 22, Flavour.PERSONAL)?.versionCode)
        assertEquals(30, Flavour.pickUpdate(peers, 22, Flavour.PUBLIC)?.versionCode)
        assertNull(Flavour.pickUpdate(peers, 25, Flavour.PERSONAL))
        assertNull(Flavour.pickUpdate(listOf(peer(40, "")), 22, Flavour.PERSONAL))
    }

    @Test
    fun archiveFlavourMustMatch() {
        assertNull(Flavour.archiveError(Flavour.PERSONAL, Flavour.PERSONAL))
        assertNull(Flavour.archiveError(Flavour.PUBLIC, Flavour.PUBLIC))
        assertEquals("Cette version n'est pas du même type que celle installée", Flavour.archiveError(Flavour.PUBLIC, Flavour.PERSONAL))
        assertNotNull(Flavour.archiveError(Flavour.PERSONAL, Flavour.PUBLIC))
        // APK d'avant l'information (pas de meta-data) : refusé.
        assertNotNull(Flavour.archiveError(null, Flavour.PERSONAL))
        assertNotNull(Flavour.archiveError("", Flavour.PUBLIC))
    }

    // ------------------------------------------------------------------ Preuve APK

    private fun parse(q: String) = LanHttp.queryParams("/prisme/apk?$q")

    @Test
    fun apkProofAcceptsSameCredentials() {
        val key = ApkProof.key("http://srv:8080", "u", "p")
        val cache = NonceCache()
        assertTrue(ApkProof.verify(ApkProof.key("http://srv:8080", "u", "p"), 22, parse(ApkProof.query(key, 22)), cache))
    }

    @Test
    fun apkProofRejectsOtherCredentialsVersionOrGarbage() {
        val key = ApkProof.key("http://srv:8080", "u", "p")
        val cache = NonceCache()
        assertFalse(ApkProof.verify(key, 22, parse(ApkProof.query(ApkProof.key("http://srv:8080", "u", "x"), 22)), cache))
        assertFalse(ApkProof.verify(key, 22, parse(ApkProof.query(ApkProof.key("", "", ""), 22)), cache))
        assertFalse(ApkProof.verify(key, 22, parse(ApkProof.query(key, 21)), cache))
        assertFalse(ApkProof.verify(key, 22, emptyMap(), cache))
        assertFalse(ApkProof.verify(key, 22, parse("nonce=zz&v=22&mac=00"), cache))
        val q = parse(ApkProof.query(key, 22))
        assertFalse(ApkProof.verify(key, 22, q + ("mac" to q.getValue("mac").replaceRange(0, 1, if (q.getValue("mac")[0] == 'a') "b" else "a")), cache))
    }

    @Test
    fun apkProofRejectsReplay() {
        val key = ApkProof.key("s", "u", "p")
        val cache = NonceCache()
        val q = parse(ApkProof.query(key, 22))
        assertTrue(ApkProof.verify(key, 22, q, cache))
        assertFalse(ApkProof.verify(key, 22, q, cache))
    }

    @Test
    fun nonceCacheIsBounded() {
        val c = NonceCache(2)
        assertTrue(c.firstUse("a")); assertTrue(c.firstUse("b")); assertFalse(c.firstUse("A"))
        assertTrue(c.firstUse("c")) // « a » évincé
        assertTrue(c.firstUse("a"))
    }

    // ------------------------------------------------------------------ Requêtes signées (foyer)

    @Test
    fun householdSignatureAcceptRejectReplay() {
        val key = LanCrypto.randomBytes(32)
        val body = "{\"x\":1}".toByteArray()
        val now = 1_700_000_000_000L
        val h = HouseholdAuth.sign(key, "POST", "/prisme/settings/changed", body, now)
        val get: (String) -> String? = { n -> h.entries.firstOrNull { it.key.equals(n, true) }?.value }
        val cache = NonceCache()
        assertFalse(HouseholdAuth.verify(LanCrypto.randomBytes(32), "POST", "/prisme/settings/changed", body, get, cache, now))
        assertFalse(HouseholdAuth.verify(key, "POST", "/prisme/settings", body, get, cache, now))
        assertFalse(HouseholdAuth.verify(key, "POST", "/prisme/settings/changed", "{}".toByteArray(), get, cache, now))
        assertFalse(HouseholdAuth.verify(key, "POST", "/prisme/settings/changed", body, get, cache, now + 11 * 60_000L))
        assertTrue(HouseholdAuth.verify(key, "POST", "/prisme/settings/changed", body, get, cache, now + 60_000L))
        assertFalse(HouseholdAuth.verify(key, "POST", "/prisme/settings/changed", body, get, cache, now)) // rejeu
        assertFalse(HouseholdAuth.verify(key, "GET", "/prisme/settings", ByteArray(0), { null }, cache, now))
    }

    // ------------------------------------------------------------------ Association

    @Test
    fun pairingWithRightCodeTransfersKey() {
        var t = 0L
        val host = PairingHost(now = { t }, newCode = { "123456" })
        val household = LanCrypto.randomBytes(32)
        host.start()
        val c = host.hello()!!
        val n = LanCrypto.hex(LanCrypto.randomBytes(16))
        val ck = PairingCrypto.codeKey("123456")
        val r = host.claim(c, n, PairingCrypto.proof(ck, c, n), household) as PairingHost.Result.Ok
        assertArrayEquals(household, PairingCrypto.unwrap(ck, c, n, r.wrapped, r.tag))
        // Mauvais code côté invitée : étiquette refusée.
        assertNull(PairingCrypto.unwrap(PairingCrypto.codeKey("654321"), c, n, r.wrapped, r.tag))
        // Association terminée : plus de défi.
        assertNull(host.hello())
        t += 1
        assertEquals(PairingHost.Result.Inactive, host.claim(c, n, "00", household))
    }

    @Test
    fun pairingRegeneratesCodeAfterFiveFailures() {
        var next = 100_000
        val host = PairingHost(now = { 0L }, newCode = { (next++).toString() })
        val household = LanCrypto.randomBytes(32)
        val first = host.start()
        val wrong = PairingCrypto.codeKey("999999")
        repeat(4) {
            val c = host.hello()!!
            val n = LanCrypto.hex(LanCrypto.randomBytes(16))
            assertEquals(PairingHost.Result.Denied(false), host.claim(c, n, PairingCrypto.proof(wrong, c, n), household))
        }
        val c = host.hello()!!
        val n = LanCrypto.hex(LanCrypto.randomBytes(16))
        assertEquals(PairingHost.Result.Denied(true), host.claim(c, n, PairingCrypto.proof(wrong, c, n), household))
        assertTrue(host.code != first)
        // L'ancien code ne marche plus.
        val c2 = host.hello()!!
        val old = PairingCrypto.codeKey(first)
        assertTrue(host.claim(c2, n, PairingCrypto.proof(old, c2, n), household) is PairingHost.Result.Denied)
    }

    @Test
    fun pairingExpiresAndChallengeIsSingleUse() {
        var t = 0L
        val host = PairingHost(now = { t }, newCode = { "111111" })
        host.start()
        val ck = PairingCrypto.codeKey("111111")
        val c = host.hello()!!
        val n = LanCrypto.hex(LanCrypto.randomBytes(16))
        // Défi inconnu : refusé, et le défi courant est consommé.
        assertTrue(host.claim("ab".repeat(16), n, PairingCrypto.proof(ck, c, n), ByteArray(32)) is PairingHost.Result.Denied)
        assertTrue(host.claim(c, n, PairingCrypto.proof(ck, c, n), ByteArray(32)) is PairingHost.Result.Denied)
        t = PairingHost.VALIDITY_MS + 1
        assertNull(host.hello())
        assertEquals(PairingHost.Result.Inactive, host.claim(c, n, "x", ByteArray(32)))
    }

    // ------------------------------------------------------------------ Host / Origin

    @Test
    fun hostHeaderMustBeIpLiteralWithPort() {
        assertTrue(LanHttp.hostAllowed("192.168.1.20:8686", 8686))
        assertTrue(LanHttp.hostAllowed("127.0.0.1:8687", 8687))
        assertTrue(LanHttp.hostAllowed("[fe80::1]:8687", 8687))
        assertTrue(LanHttp.hostAllowed("[fd00::abcd]:8686", 8686))
        assertFalse(LanHttp.hostAllowed("192.168.1.20:8687", 8686))
        assertFalse(LanHttp.hostAllowed("192.168.1.20", 8686))
        assertFalse(LanHttp.hostAllowed("tv.local:8686", 8686))
        assertFalse(LanHttp.hostAllowed("evil.example:8686", 8686))
        assertFalse(LanHttp.hostAllowed("192.168.1.300:8686", 8686))
        assertFalse(LanHttp.hostAllowed("1.2.3:8686", 8686))
        assertFalse(LanHttp.hostAllowed("[localhost]:8686", 8686))
        assertFalse(LanHttp.hostAllowed(null, 8686))
        assertFalse(LanHttp.hostAllowed("", 8686))
    }

    @Test
    fun originMustMatchHost() {
        assertTrue(LanHttp.originAllowed(null, "192.168.1.20:8686"))
        assertTrue(LanHttp.originAllowed("http://192.168.1.20:8686", "192.168.1.20:8686"))
        assertFalse(LanHttp.originAllowed("http://evil.example", "192.168.1.20:8686"))
        assertFalse(LanHttp.originAllowed("null", "192.168.1.20:8686"))
        assertFalse(LanHttp.originAllowed("https://192.168.1.20:8686", "192.168.1.20:8686"))
        assertFalse(LanHttp.originAllowed("http://192.168.1.20:9999", "192.168.1.20:8686"))
    }

    // ------------------------------------------------------------------ Instantané reçu

    @Test
    fun sanitizeDropsFutureDomainsAndBadUserAgent() {
        val now = 1_700_000_000_000L
        val s = SettingsSync.Snapshot(
            "Salon\u0007",
            mapOf(
                SettingsSync.HIDDEN to SettingsSync.Domain(now + 25 * 3_600_000L, listOf("live|X")),
                SettingsSync.FAVORITES to SettingsSync.Domain(now + 3_600_000L, mapOf("main" to listOf("live:1", "movie:2"), "bad id!" to listOf("live:3"))),
                SettingsSync.PREFS_DOMAIN to SettingsSync.Domain(
                    now, mapOf("ua" to "Mozilla\r\nX-Evil: 1", "fr" to "FR", "strictVod" to true, "unknown" to "x", "uhdLive" to "yes"),
                ),
                "tokens" to SettingsSync.Domain(now, "secret"),
            ),
        )
        val out = SettingsSync.sanitize(s, now)
        assertEquals("Salon", out.device)
        assertEquals(setOf(SettingsSync.FAVORITES, SettingsSync.PREFS_DOMAIN), out.domains.keys)
        assertEquals(mapOf("main" to listOf("live:1")), out.domains[SettingsSync.FAVORITES]!!.data)
        assertEquals(mapOf("fr" to "FR", "strictVod" to true), out.domains[SettingsSync.PREFS_DOMAIN]!!.data)
    }

    @Test
    fun userAgentValidation() {
        assertTrue(SettingsSync.validUserAgent("VLC/3.0.20 LibVLC/3.0.20"))
        assertFalse(SettingsSync.validUserAgent("a\tb"))
        assertFalse(SettingsSync.validUserAgent("é"))
        assertFalse(SettingsSync.validUserAgent("x".repeat(201)))
        assertTrue(SettingsSync.validUserAgent("x".repeat(200)))
    }

    @Test
    fun sanitizeProfiles() {
        val hash = PinHash.hash("1234")
        val list = listOf(
            mapOf("id" to "main", "name" to "A\nB".repeat(20), "color" to 9999, "kids" to false, "pin" to "1234"),
            mapOf("id" to "../x", "name" to "bad"),
            mapOf("id" to "kids", "name" to "Enfants", "kids" to true, "pinHash" to hash, "extra" to 1),
            mapOf("id" to "k2", "name" to "K", "kids" to true, "pinHash" to "p1\$99999999\$AAAAAAAAAAAAAAAAAAAAAA==\$" + "A".repeat(43) + "="),
        )
        val s = SettingsSync.sanitize(SettingsSync.Snapshot("x", mapOf(SettingsSync.PROFILES to SettingsSync.Domain(1L, list))), 10L)
        val back = Profiles.fromMaps(s.domains[SettingsSync.PROFILES]!!.data as List<*>)
        assertEquals(listOf("main", "kids", "k2"), back.map { it.id })
        assertTrue(back[0].name.length in 19..20)
        assertEquals(back[0].name, Profiles.cleanName(back[0].name)) // stable : relu sans changement
        assertFalse(back[0].name.contains('\n'))
        assertEquals(64, back[0].color)
        // Un code en clair venant d'une autre TV n'est jamais accepté ; une empreinte aberrante non plus.
        assertEquals("", back[0].pinHash)
        assertEquals(hash, back[1].pinHash)
        assertEquals("", back[2].pinHash)
    }

    @Test
    fun prefsChangedKeys() {
        assertEquals(setOf("fr", "ua"), SettingsSync.changedKeys(mapOf("fr" to "A", "ua" to null, "uhdLive" to true), mapOf("fr" to "B", "ua" to "X", "uhdLive" to true)))
        assertTrue(SettingsSync.CATALOG_KEYS.containsAll(listOf("fr", "exclude")))
        assertFalse("ua" in SettingsSync.CATALOG_KEYS)
    }

    // ------------------------------------------------------------------ Code parental

    @Test
    fun pbkdf2MatchesJdk() {
        val salt = ByteArray(16) { it.toByte() }
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("1234".toCharArray(), salt, 1000, 256)).encoded
        assertArrayEquals(jdk, PinHash.pbkdf2("1234", salt, 1000))
    }

    @Test
    fun pinHashVerify() {
        val h = PinHash.hash("4321")
        assertTrue(h.startsWith("p1\$60000\$"))
        assertTrue(PinHash.isValid(h))
        assertTrue(PinHash.verify("4321", h))
        assertFalse(PinHash.verify("1234", h))
        assertFalse(h.contains("4321"))
        assertTrue(h != PinHash.hash("4321")) // sel aléatoire
        assertFalse(PinHash.verify("4321", "4321"))
        assertFalse(PinHash.verify("4321", ""))
        val p = Profile("k", "K", 1, true, h)
        assertTrue(p.hasPin && p.checkPin("4321") && !p.checkPin("0000"))
        assertFalse(Profile("k", "K", 1, true).checkPin(""))
    }

    @Test
    fun legacyPlaintextPinsAreMigratedLocally() {
        val raw = """[{"id":"main","name":"Principal","color":0,"kids":false,"pin":""},{"id":"kids","name":"Enfants","color":1,"kids":true,"pin":"2468"}]"""
        val list = Profiles.decode(raw, migrateLegacyPins = true)!!
        assertEquals("", list[0].pinHash)
        assertTrue(list[1].checkPin("2468"))
        assertFalse(Profiles.encode(list).contains("2468"))
        // Sans migration (données d'une autre TV) : pas de code.
        assertEquals("", Profiles.decode(raw)!![1].pinHash)
    }

    @Test
    fun parentalPinRequiredFromAnyKidsProfile() {
        val adult = Profile("main", "A", 0, false)
        val kidsPin = Profile("kids", "E", 1, true, PinHash.hash("1111"))
        val kidsFree = Profile("k2", "F", 2, true)
        val all = listOf(adult, kidsPin, kidsFree)
        assertTrue(Profiles.needsParentalPin(kidsPin, adult, all))
        // Contournement : passer par un profil Enfants sans code.
        assertTrue(Profiles.needsParentalPin(kidsFree, adult, all))
        assertFalse(Profiles.needsParentalPin(kidsPin, kidsFree, all))
        assertFalse(Profiles.needsParentalPin(adult, kidsPin, all))
        assertFalse(Profiles.needsParentalPin(kidsFree, adult, listOf(adult, kidsFree)))
        assertTrue(Profiles.checkParentalPin("1111", all))
        assertFalse(Profiles.checkParentalPin("2222", all))
    }

    // ------------------------------------------------------------------ Liens profonds en profil Enfants

    @Test
    fun kidsDeepLinkFilter() {
        val m = Movie("7", "Dessin", "ENFANTS", null, Quality.HD, "u", 0, null)
        val ep = Episode("e1", 1, 1, "t", "u", null, null, 0)
        val s = Series("s1", "Série", "ENFANTS", null, Quality.HD, null, 0, listOf(ep))
        val x = Series("s2", "Autre", "ENFANTS", null, Quality.HD, null, 0)
        val kids = Catalog(emptyList(), listOf(m), listOf(s, x), emptyList(), listOf("ENFANTS"), listOf("ENFANTS"), 0)
        assertTrue(CatalogAccess.kidsAllows(kids, "movie:7", null))
        assertFalse(CatalogAccess.kidsAllows(kids, "movie:8", null))
        assertTrue(CatalogAccess.kidsAllows(kids, "ep:e1", null))
        assertTrue(CatalogAccess.kidsAllows(kids, "ep:e9", "s2")) // épisodes Xtream chargés à la demande
        assertFalse(CatalogAccess.kidsAllows(kids, "ep:e9", "s3"))
        assertFalse(CatalogAccess.kidsAllows(kids, "ep:e9", null))
        assertFalse(CatalogAccess.kidsAllows(kids, "live:1", null))
        assertFalse(CatalogAccess.kidsAllows(null, "movie:7", null))
    }

    @Test
    fun queryParamsDecode() {
        assertEquals(mapOf("a" to "1 2", "b" to "x&y"), LanHttp.queryParams("/p?a=1+2&b=x%26y&a=3&=z"))
        assertTrue(LanHttp.queryParams("/p").isEmpty())
    }
}
