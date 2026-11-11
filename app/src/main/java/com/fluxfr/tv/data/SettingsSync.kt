package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/**
 * Réglages partagés entre les TV de la maison : définitions des profils, catégories masquées, chaînes
 * favorites de chaque profil et préférences non liées à l'appareil. Les comptes et les identifiants
 * IPTV ne sont jamais partagés.
 *
 * Chaque domaine porte sa date de modification ([touch]) ; à la fusion, la version la plus récente de
 * chaque domaine l'emporte. Transport : réseau local (voir [PrismeNode]).
 */
object SettingsSync {
    const val PROFILES = "profiles"
    const val HIDDEN = "hidden"
    const val FAVORITES = "favorites"
    const val PREFS_DOMAIN = "prefs"
    val DOMAINS = listOf(PROFILES, HIDDEN, FAVORITES, PREFS_DOMAIN)

    private const val PREFS = "fluxfr"
    private const val KEY_ENABLED = "syncEnabled"
    private const val KEY_STATUS_AT = "syncStatusAt"
    private const val KEY_STATUS_SRC = "syncStatusSource"

    /** Clés des préférences partagées (même nom dans l'instantané et dans les préférences « fluxfr »). */
    private val SHARED_STRINGS = listOf("fr", "exclude", "ua")
    private val SHARED_BOOLS = listOf("strictVod", "unknownLive", "uhdLive", "askProfileAtStart")
    private val BOOL_DEFAULTS = mapOf("askProfileAtStart" to true)

    data class Domain(val updatedAt: Long, val data: Any?)
    data class Snapshot(val device: String, val domains: Map<String, Domain>)

    /** Dernière synchronisation réussie : quand, avec quelle TV. */
    data class Status(val at: Long, val source: String)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Message ponctuel de la dernière synchronisation manuelle (« Aucune autre TV trouvée »…). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Incrémenté à chaque application de réglages reçus : l'écran Réglages relit ses valeurs. */
    private val _applied = MutableStateFlow(0)
    val applied: StateFlow<Int> = _applied.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var pushJob: Job? = null
    @Volatile private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val p = prefs(ctx)
        val at = p.getLong(KEY_STATUS_AT, 0L)
        if (at > 0) _status.value = Status(at, p.getString(KEY_STATUS_SRC, "") ?: "")
    }

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, true)

    /** Synchronisation réellement active : option activée ET TV associée à au moins une autre ([Household]). */
    fun isActive(ctx: Context) = isEnabled(ctx) && Household.isPaired(ctx)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) syncNowAsync(ctx)
    }

    // ------------------------------------------------------------------------- Modifications locales

    /** À appeler après chaque modification locale d'un domaine : date le domaine et prévient les autres TV. */
    fun touch(ctx: Context, domain: String) {
        prefs(ctx).edit().putLong(atKey(domain), System.currentTimeMillis()).apply()
        if (!isActive(ctx)) return
        val app = ctx.applicationContext
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(1_500)
            notifyPeers(app, PrismeNode.knownPeers())
        }
    }

    private fun atKey(domain: String) = "syncAt_$domain"

    fun localTimes(ctx: Context): Map<String, Long> = DOMAINS.associateWith { prefs(ctx).getLong(atKey(it), 0L) }

    // ------------------------------------------------------------------------- Instantané

    fun snapshot(ctx: Context): Snapshot {
        Profiles.load(ctx)
        val p = prefs(ctx)
        val times = localTimes(ctx)
        val profiles = Profiles.list.value
        val data = mapOf(
            PROFILES to Profiles.toMaps(profiles),
            HIDDEN to (p.getStringSet("hiddenCategories", emptySet()) ?: emptySet()).sorted(),
            FAVORITES to profiles.associate { pr ->
                pr.id to (p.getString("favorites_" + pr.id, "") ?: "").split('\n').filter { it.startsWith("live:") }
            },
            PREFS_DOMAIN to buildMap<String, Any> {
                for (k in SHARED_STRINGS) p.getString(k, null)?.let { put(k, it) }
                for (k in SHARED_BOOLS) if (p.contains(k)) put(k, p.getBoolean(k, BOOL_DEFAULTS[k] ?: false))
            },
        )
        return Snapshot(PrismeNode.deviceName(ctx), DOMAINS.associateWith { Domain(times[it] ?: 0L, data[it]) })
    }

    internal fun encode(s: Snapshot): String = MiniJson.write(
        mapOf(
            "v" to 1,
            "device" to s.device,
            "domains" to s.domains.mapValues { (_, d) -> mapOf("at" to d.updatedAt, "data" to d.data) },
        )
    )

    internal fun decode(text: String): Snapshot? = runCatching {
        val m = MiniJson.parse(text) as Map<*, *>
        val domains = (m["domains"] as Map<*, *>).entries.mapNotNull { (k, v) ->
            val o = v as? Map<*, *> ?: return@mapNotNull null
            val at = (o["at"] as? Number)?.toLong() ?: return@mapNotNull null
            k.toString() to Domain(at, o["data"])
        }.toMap()
        Snapshot(m["device"] as? String ?: "", domains)
    }.getOrNull()

    // ------------------------------------------------------------------------- Validation d'un instantané reçu

    /** Date acceptée au plus 24 h dans le futur (horloge déréglée ou valeur forgée qui bloquerait le domaine). */
    const val MAX_FUTURE_MS = 24 * 3_600_000L
    private const val MAX_UA = 200
    private const val MAX_PREF_STRING = 500
    private const val MAX_HIDDEN = 5_000
    private const val MAX_FAVORITES = 1_000
    private const val MAX_ITEM = 300

    /** Texte ASCII imprimable (0x20–0x7E) de [MAX_UA] caractères au plus : seule forme acceptée pour l'user-agent. */
    internal fun validUserAgent(s: String): Boolean = s.length <= MAX_UA && s.all { it in ' '..'~' }

    private fun cleanText(s: String, max: Int): String? = s.takeIf { it.length <= max && it.none { c -> c.isISOControl() } }

    /**
     * Instantané reçu, assaini : domaines inconnus, dates négatives ou à plus de 24 h dans le futur écartés ;
     * profils assainis ([Profiles.fromMaps]) ; listes et textes bornés ; user-agent ASCII imprimable ;
     * préférences inconnues ignorées.
     */
    internal fun sanitize(s: Snapshot, now: Long = System.currentTimeMillis()): Snapshot {
        val device = s.device.filter { !it.isISOControl() }.trim().take(40)
        val domains = HashMap<String, Domain>()
        for ((k, d) in s.domains) {
            if (k !in DOMAINS || d.updatedAt < 0 || d.updatedAt > now + MAX_FUTURE_MS) continue
            val data: Any = when (k) {
                PROFILES -> Profiles.toMaps(Profiles.fromMaps(d.data as? List<*> ?: continue))
                HIDDEN -> (d.data as? List<*> ?: continue).asSequence().filterIsInstance<String>()
                    .mapNotNull { cleanText(it, MAX_ITEM) }.take(MAX_HIDDEN).toList()
                FAVORITES -> (d.data as? Map<*, *> ?: continue).entries.asSequence()
                    .filter { (id, _) -> id is String && id.length <= 40 && id.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
                    .take(Profiles.MAX_PROFILES * 4)
                    .associate { (id, v) ->
                        id as String to (v as? List<*>).orEmpty().asSequence().filterIsInstance<String>()
                            .filter { it.startsWith("live:") }.mapNotNull { cleanText(it, MAX_ITEM) }.take(MAX_FAVORITES).toList()
                    }
                PREFS_DOMAIN -> {
                    val m = d.data as? Map<*, *> ?: continue
                    buildMap<String, Any> {
                        for (key in SHARED_STRINGS) {
                            val v = m[key] as? String ?: continue
                            val ok = if (key == "ua") validUserAgent(v) else cleanText(v, MAX_PREF_STRING) != null
                            if (ok) put(key, v)
                        }
                        for (key in SHARED_BOOLS) (m[key] as? Boolean)?.let { put(key, it) }
                    }
                }
                else -> continue
            }
            domains[k] = Domain(d.updatedAt, data)
        }
        return Snapshot(device, domains)
    }

    /** Domaines du distant strictement plus récents que les nôtres (ceux à appliquer). */
    internal fun newerDomains(local: Map<String, Long>, remote: Snapshot): Map<String, Domain> =
        remote.domains.filter { (k, d) -> k in DOMAINS && d.data != null && d.updatedAt > (local[k] ?: 0L) }

    /** Le distant a-t-il au moins un domaine plus ancien que le nôtre (à lui renvoyer) ? */
    internal fun remoteIsStale(local: Map<String, Long>, remote: Snapshot): Boolean =
        DOMAINS.any { (local[it] ?: 0L) > (remote.domains[it]?.updatedAt ?: 0L) }

    /**
     * « Ma liste » locale d'un profil avec les chaînes favorites du distant : films et séries gardés tels
     * quels, chaînes retirées si absentes du distant, nouvelles chaînes ajoutées en tête (ordre du distant).
     */
    internal fun mergeLiveFavorites(local: List<String>, remoteLive: List<String>): List<String> {
        val remote = remoteLive.filter { it.startsWith("live:") }.distinct()
        val remoteSet = remote.toSet()
        val kept = local.filter { !it.startsWith("live:") || it in remoteSet }
        val keptSet = kept.toSet()
        return remote.filter { it !in keptSet } + kept
    }

    // ------------------------------------------------------------------------- Application

    /** Fusionne un instantané reçu ; renvoie true si quelque chose a été appliqué. */
    suspend fun merge(ctx: Context, received: Snapshot, source: String): Boolean = mutex.withLock {
        if (!isActive(ctx)) return@withLock false
        val remote = sanitize(received)
        val newer = newerDomains(localTimes(ctx), remote)
        if (newer.isNotEmpty()) apply(ctx, newer)
        setStatus(ctx, Status(System.currentTimeMillis(), source))
        newer.isNotEmpty()
    }

    private fun apply(ctx: Context, newer: Map<String, Domain>) {
        val p = prefs(ctx)
        // Les profils d'abord : les favoris en dépendent.
        newer[PROFILES]?.let { d ->
            val list = Profiles.fromMaps(d.data as? List<*>)
            if (list.isNotEmpty()) {
                Profiles.replaceAll(ctx, list)
                p.edit().putLong(atKey(PROFILES), d.updatedAt).apply()
            }
        }
        newer[HIDDEN]?.let { d ->
            val set = (d.data as? List<*>).orEmpty().filterIsInstance<String>().toSet()
            HiddenCategories.replaceAll(ctx, set)
            p.edit().putLong(atKey(HIDDEN), d.updatedAt).apply()
        }
        newer[FAVORITES]?.let { d ->
            val m = d.data as? Map<*, *> ?: return@let
            for (profile in Profiles.list.value) {
                val remoteLive = (m[profile.id] as? List<*>)?.filterIsInstance<String>() ?: continue
                Favorites.replaceLive(ctx, profile.id) { mergeLiveFavorites(it, remoteLive) }
            }
            p.edit().putLong(atKey(FAVORITES), d.updatedAt).apply()
        }
        newer[PREFS_DOMAIN]?.let { d ->
            val m = d.data as? Map<*, *> ?: return@let
            val before = prefsValues(p)
            val e = p.edit()
            for (k in SHARED_STRINGS) (m[k] as? String)?.let { e.putString(k, it) }
            for (k in SHARED_BOOLS) (m[k] as? Boolean)?.let { e.putBoolean(k, it) }
            e.putLong(atKey(PREFS_DOMAIN), d.updatedAt).commit()
            val changed = changedKeys(before, prefsValues(p))
            // User-Agent : appliqué tout de suite aux requêtes suivantes.
            if ("ua" in changed) Http.userAgent = Settings(ctx).userAgent
            // Filtres du catalogue (mots français / exclus, qualité) : rechargement forcé, seulement s'ils ont changé.
            if (changed.any { it in CATALOG_KEYS }) {
                val app = ctx.applicationContext
                scope.launch { runCatching { CatalogRepository.load(app, force = true) } }
            }
        }
        _applied.value = _applied.value + 1
    }

    /** Préférences partagées qui changent le contenu du catalogue (un changement impose un rechargement). */
    internal val CATALOG_KEYS = setOf("fr", "exclude", "strictVod", "unknownLive", "uhdLive")

    private fun prefsValues(p: android.content.SharedPreferences): Map<String, Any?> =
        (SHARED_STRINGS + SHARED_BOOLS).associateWith { p.all[it] }

    /** Clés dont la valeur diffère entre [before] et [after]. */
    internal fun changedKeys(before: Map<String, Any?>, after: Map<String, Any?>): Set<String> =
        (before.keys + after.keys).filterTo(HashSet()) { before[it] != after[it] }

    /** « Synchronisé avec Salon il y a 2 min ». */
    fun describe(s: Status, now: Long = System.currentTimeMillis()): String =
        "Synchronisé avec ${s.source} " + ago(s.at, now)

    internal fun ago(at: Long, now: Long): String {
        val sec = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "à l'instant"
            sec < 3600 -> "il y a ${sec / 60} min"
            sec < 86_400 -> "il y a ${sec / 3600} h"
            else -> "il y a ${sec / 86_400} j"
        }
    }

    private fun setStatus(ctx: Context, s: Status) {
        _status.value = s
        prefs(ctx).edit().putLong(KEY_STATUS_AT, s.at).putString(KEY_STATUS_SRC, s.source).apply()
    }

    // ------------------------------------------------------------------------- Réseau local

    /**
     * Récupère et fusionne les réglages de chaque TV associée trouvée (requête signée avec la clé du foyer,
     * réponse dont la signature est vérifiée), puis renvoie les nôtres à celles en retard. Une TV d'un autre
     * foyer (ou non associée) répond 403/404 et est simplement ignorée.
     */
    suspend fun syncWithPeers(ctx: Context, peers: List<PeerInfo>): Int {
        if (!isActive(ctx)) return 0
        val key = Household.key(ctx)
        var ok = 0
        for (peer in peers) {
            val path = "/prisme/settings"
            val headers = HouseholdAuth.sign(key, "GET", path, ByteArray(0))
            val r = runCatching { PrismeNode.exchange(peer.url(path), "GET", headers = headers) }.getOrNull() ?: continue
            if (r.code != 200) continue
            val sig = HouseholdAuth.responseSig(key, headers.getValue(HouseholdAuth.H_NONCE), r.body)
            if (!LanCrypto.equalHex(r.header(HouseholdAuth.H_SIG), sig)) continue
            val remote = decode(r.text) ?: continue
            ok++
            merge(ctx, remote, peer.device)
            if (remoteIsStale(localTimes(ctx), remote)) notifyPeers(ctx, listOf(peer))
        }
        return ok
    }

    /** Envoie notre instantané, signé avec la clé du foyer, aux TV connues (sans attendre de réponse utile). */
    private fun notifyPeers(ctx: Context, peers: List<PeerInfo>) {
        if (peers.isEmpty() || !isActive(ctx)) return
        val key = Household.key(ctx)
        val body = encode(snapshot(ctx)).toByteArray(Charsets.UTF_8)
        val path = "/prisme/settings/changed"
        for (peer in peers) runCatching {
            PrismeNode.exchange(peer.url(path), "POST", body, HouseholdAuth.sign(key, "POST", path, body), timeoutMs = 4_000)
        }
    }

    /** Reçu d'une autre TV (POST /prisme/settings/changed, signature déjà vérifiée par [PrismeNode]). */
    fun onRemoteChanged(ctx: Context, body: String, fallbackSource: String) {
        if (!isActive(ctx)) return
        val remote = decode(body) ?: return
        scope.launch { merge(ctx, remote, remote.device.ifBlank { fallbackSource }) }
    }

    // ------------------------------------------------------------------------- Synchronisation complète

    /** « Synchroniser maintenant » : recherche des TV (≈ 4 s), puis fusion. */
    suspend fun syncNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!isActive(app)) return
        _busy.value = true
        _message.value = null
        try {
            val peers = PrismeNode.discover(app)
            Updater.onPeers(app, peers)
            val lan = syncWithPeers(app, peers)
            _message.value = if (lan > 0) null else "Aucune TV associée trouvée sur le réseau"
        } finally {
            _busy.value = false
        }
    }

    fun syncNowAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { syncNow(app) } }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Clé du foyer : 32 octets aléatoires créés au premier lancement, jamais transmis sauf lors d'une association
 * explicite entre deux TV (code à 6 chiffres, voir [PairingHost] et le modèle de menace dans LanAuth.kt).
 * Tant qu'une TV n'est associée à aucune autre, ses réglages ne sont ni servis ni acceptés (« Non associée »).
 * Associer B à A : B adopte la clé de A (si B était associée à une troisième TV, il faut ré-associer celle-ci).
 */
object Household {
    private const val PREFS = "fluxfr"
    private const val KEY_KEY = "householdKey"
    private const val KEY_PAIRED = "householdPaired"

    internal val host = PairingHost()

    private val _paired = MutableStateFlow<Boolean?>(null)
    /** null tant que l'état n'a pas été lu. */
    val paired: StateFlow<Boolean?> = _paired.asStateFlow()

    /** Code affiché sur cette TV pendant une association (null sinon) et son expiration. */
    data class HostState(val code: String, val expiresAt: Long)
    private val _hosting = MutableStateFlow<HostState?>(null)
    val hosting: StateFlow<HostState?> = _hosting.asStateFlow()

    /** Heure de la dernière association réussie côté hôte (0 si aucune). */
    private val _hostPairedAt = MutableStateFlow(0L)
    val hostPairedAt: StateFlow<Long> = _hostPairedAt.asStateFlow()

    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun key(ctx: Context): ByteArray {
        val p = prefs(ctx)
        LanCrypto.unhex(p.getString(KEY_KEY, null) ?: "")?.takeIf { it.size == 32 }?.let { return it }
        val k = LanCrypto.randomBytes(32)
        p.edit().putString(KEY_KEY, LanCrypto.hex(k)).apply()
        return k
    }

    fun isPaired(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PAIRED, false).also { _paired.value = it }

    private fun setPaired(ctx: Context, key: ByteArray?) {
        val e = prefs(ctx).edit().putBoolean(KEY_PAIRED, true)
        if (key != null) e.putString(KEY_KEY, LanCrypto.hex(key))
        e.apply()
        _paired.value = true
    }

    /** « Dissocier cette TV » : nouvelle clé, plus de synchronisation tant qu'elle n'est pas ré-associée. */
    fun unpair(ctx: Context) {
        prefs(ctx).edit().putString(KEY_KEY, LanCrypto.hex(LanCrypto.randomBytes(32))).putBoolean(KEY_PAIRED, false).apply()
        _paired.value = false
    }

    // ------------------------------------------------------------------------- Côté hôte (affiche le code)

    fun startHosting(ctx: Context) {
        key(ctx) // créée avant la première demande
        val code = host.start()
        _hosting.value = HostState(code, host.expiresAt)
    }

    fun stopHosting() {
        host.stop()
        _hosting.value = null
    }

    /** POST /prisme/pair/claim (fil du serveur). */
    internal fun onClaim(ctx: Context, c: String?, n: String?, proof: String?): PairingHost.Result {
        val r = host.claim(c, n, proof, key(ctx))
        when (r) {
            is PairingHost.Result.Ok -> {
                setPaired(ctx, null)
                _hosting.value = null
                _hostPairedAt.value = System.currentTimeMillis()
                SettingsSync.syncNowAsync(ctx)
            }
            is PairingHost.Result.Denied -> if (r.codeChanged) host.code?.let { _hosting.value = HostState(it, host.expiresAt) }
            PairingHost.Result.Inactive -> Unit
        }
        return r
    }

    // ------------------------------------------------------------------------- Côté invitée (saisit le code)

    /** Associe cette TV à celle qui affiche [code] ; null si c'est fait, sinon le message d'erreur. */
    suspend fun join(ctx: Context, code: String): String? {
        val app = ctx.applicationContext
        val clean = code.filter { it.isDigit() }
        if (!PairingCrypto.isCode(clean)) return "Le code fait 6 chiffres"
        val peers = PrismeNode.discover(app)
        if (peers.isEmpty()) return "Aucune autre TV avec Prisme trouvée sur le réseau"
        val ck = PairingCrypto.codeKey(clean)
        var sawHost = false
        for (peer in peers) {
            val hello = runCatching { PrismeNode.exchange(peer.url("/prisme/pair/hello"), "POST", ByteArray(0)) }.getOrNull() ?: continue
            if (hello.code != 200) continue
            sawHost = true
            val c = runCatching { (MiniJson.parse(hello.text) as Map<*, *>)["c"] as? String }.getOrNull() ?: continue
            val n = LanCrypto.hex(LanCrypto.randomBytes(16))
            val body = MiniJson.write(mapOf("c" to c, "n" to n, "p" to PairingCrypto.proof(ck, c, n))).toByteArray(Charsets.UTF_8)
            val r = runCatching { PrismeNode.exchange(peer.url("/prisme/pair/claim"), "POST", body) }.getOrNull() ?: continue
            if (r.code == 403) return "Code incorrect"
            if (r.code != 200) continue
            val m = runCatching { MiniJson.parse(r.text) as Map<*, *> }.getOrNull() ?: continue
            val k = PairingCrypto.unwrap(ck, c, n, m["k"] as? String ?: continue, m["t"] as? String ?: continue)
                ?: return "Réponse invalide de ${peer.device}"
            setPaired(app, k)
            SettingsSync.syncNowAsync(app)
            return null
        }
        return if (sawHost) "Association impossible, réessaie" else "Aucune TV n'affiche de code d'association"
    }

    fun joinAsync(ctx: Context, code: String, onResult: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (_joining.value) return
        _joining.value = true
        scope.launch {
            val msg = runCatching { join(app, code) }.getOrElse { "Association impossible, réessaie" }
            _joining.value = false
            onResult(msg)
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Base64 sans dépendance (android.util.Base64 n'existe pas sur la JVM des tests, java.util.Base64 demande l'API 26). */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filter { it in ALPHABET }
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in clean) {
            buf = (buf shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
