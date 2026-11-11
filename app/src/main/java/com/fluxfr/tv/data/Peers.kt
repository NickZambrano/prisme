package com.fluxfr.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.fluxfr.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Une autre TV qui fait tourner Prisme, vue sur le réseau local. */
data class PeerInfo(
    val host: String,
    val port: Int,
    val device: String,
    val versionCode: Int,
    val versionName: String,
    /** « personal » ou « public » ([BuildConfig.FLAVOUR]) ; vide pour une TV d'avant cette information. */
    val flavour: String = "",
) {
    fun url(path: String): String = "http://" + (if (':' in host) "[$host]" else host) + ":" + port + path
}

/** Réponse HTTP brute d'une autre TV. */
class PeerResponse(val code: Int, val body: ByteArray, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
    val text: String get() = String(body, Charsets.UTF_8)
}

/**
 * Le « nœud » Prisme de cette TV : petit serveur HTTP sur le réseau local, annoncé en NSD (« _prisme._tcp »),
 * actif tant que le processus vit. Il sert aux autres TV :
 *  - GET  /prisme/version           → {versionCode, versionName, device, flavour}
 *  - GET  /prisme/apk               → l'APK installé (mise à jour d'une TV à l'autre). Version personnelle
 *    (identifiants IPTV intégrés) : seulement à une TV qui prouve avoir les mêmes identifiants ([ApkProof]).
 *  - GET  /prisme/settings          → instantané des réglages partagés ([SettingsSync]) } TV associées
 *  - POST /prisme/settings/changed  → instantané d'une autre TV après une modification } seulement ([HouseholdAuth])
 *  - POST /prisme/pair/hello, /prisme/pair/claim → association de deux TV par code ([PairingHost])
 * Seules les adresses du réseau local sont servies, avec un en-tête Host en adresse IP et sans Origin
 * étrangère ([LanHttp.requestAllowed]) : une page web ne peut pas interroger la TV.
 */
object PrismeNode {
    const val SERVICE_TYPE = "_prisme._tcp"
    const val PORT = 8687
    private const val PREFS = "fluxfr"
    private const val KEY_PEERS = "knownPeers"
    private const val MAX_SETTINGS_BODY = 1 shl 20
    private const val MAX_PAIR_BODY = 4096

    @Volatile private var app: Context? = null
    @Volatile private var server: ServerSocket? = null
    /** Adresse d'écoute (interface Wi-Fi / Ethernet active), null = toutes les interfaces. */
    @Volatile private var boundAddress: InetAddress? = null
    @Volatile var port: Int = 0
        private set
    private val apkNonces = NonceCache(256)
    private val settingsNonces = NonceCache(512)
    /** Clé de preuve de la version personnelle (jamais transmise). */
    private val apkKey: ByteArray by lazy {
        ApkProof.key(BuildConfig.DEFAULT_SERVER, BuildConfig.DEFAULT_USER, BuildConfig.DEFAULT_PASS)
    }
    /** Nom NSD réellement enregistré (le système peut ajouter « (2) » en cas de doublon). */
    @Volatile private var registeredName: String? = null
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "prisme-node").apply { isDaemon = true } }

    /**
     * Démarre le serveur et l'annonce NSD (une seule fois par processus), puis, après quelques secondes,
     * cherche les autres TV : mise à jour disponible ? réglages plus récents ? Sans effet si déjà démarré.
     */
    fun start(ctx: Context) {
        synchronized(this) {
            if (app != null) return
            app = ctx.applicationContext
        }
        val c = ctx.applicationContext
        SettingsSync.init(c)
        loadKnownPeers(c)
        scope.launch {
            val p = listen(c) ?: return@launch
            register(c, p)
            delay(3_000)
            val found = discover(c)
            Updater.onPeers(c, found)
            runCatching { SettingsSync.syncWithPeers(c, found) }
        }
    }

    fun knownPeers(): List<PeerInfo> = peers.values.toList()

    fun deviceName(ctx: Context): String {
        val n = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
        }.getOrNull()
        return (n?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "TV").trim().take(40)
    }

    // ------------------------------------------------------------------------- Serveur

    /**
     * Adresse IPv4 du réseau local de l'interface active (Wi-Fi ou Ethernet) ; null si on ne peut pas la
     * déterminer (on écoute alors sur toutes les interfaces, filtrées par [LanHttp.isLanAddress]).
     */
    private fun activeLanAddress(ctx: Context): InetAddress? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return null
        }
        cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && LanHttp.isLanAddress(it) }
    }.getOrNull()

    private fun listen(ctx: Context, preferredPort: Int = PORT): Int? {
        val addr = activeLanAddress(ctx)
        fun open(bind: InetAddress?, p: Int) = runCatching { ServerSocket(p, 50, bind) }.getOrNull()
        val s = open(addr, preferredPort) ?: open(null, preferredPort) ?: open(addr, 0) ?: return null
        boundAddress = if (s.inetAddress?.isAnyLocalAddress == true) null else s.inetAddress
        server = s
        port = s.localPort
        thread(name = "prisme-node-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                val ok = runCatching {
                    pool.execute {
                        runCatching { handle(ctx, client, s.localPort) }
                        runCatching { client.close() }
                    }
                }.isSuccess
                if (!ok) runCatching { client.close() }
            }
        }
        return s.localPort
    }

    /** L'adresse du réseau local a changé (nouveau bail DHCP, Wi-Fi ↔ Ethernet) : réécoute sur la nouvelle. */
    private fun rebindIfNeeded(ctx: Context) {
        val current = server ?: return
        val bound = boundAddress ?: return
        val now = activeLanAddress(ctx) ?: return
        if (now == bound) return
        val oldPort = current.localPort
        runCatching { current.close() }
        val p = listen(ctx, oldPort) ?: return
        if (p != oldPort) register(ctx, p)
    }

    private fun json(out: java.io.OutputStream, code: Int, value: Any?, headers: Map<String, String> = emptyMap()) =
        LanHttp.respond(out, code, "application/json; charset=utf-8", MiniJson.write(value).toByteArray(Charsets.UTF_8), headers)

    private fun handle(ctx: Context, socket: Socket, localPort: Int) {
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        val remote = socket.inetAddress
        if (!LanHttp.isLanAddress(remote)) {
            LanHttp.respondText(out, 403, "LAN only")
            return
        }
        val input = BufferedInputStream(socket.getInputStream())
        val req = LanHttp.readRequest(input) ?: return
        // Host en adresse IP + port, pas d'Origin étrangère : bloque CSRF et DNS rebinding.
        if (!LanHttp.requestAllowed(req, localPort)) {
            LanHttp.respondText(out, 403, "")
            return
        }
        val path = req.path.substringBefore('?')
        when {
            req.method == "GET" && path == "/prisme/version" -> json(
                out, 200,
                mapOf(
                    "app" to "prisme",
                    "versionCode" to BuildConfig.VERSION_CODE,
                    "versionName" to BuildConfig.VERSION_NAME,
                    "device" to deviceName(ctx),
                    "flavour" to BuildConfig.FLAVOUR,
                ),
            )
            req.method == "GET" && path == "/prisme/apk" -> {
                // Version publique : rien de secret dans l'APK. Version personnelle : preuve des mêmes identifiants.
                val allowed = BuildConfig.FLAVOUR == Flavour.PUBLIC ||
                    ApkProof.verify(apkKey, BuildConfig.VERSION_CODE, LanHttp.queryParams(req.path), apkNonces)
                val f = File(ctx.applicationInfo.sourceDir)
                if (!allowed || !f.canRead()) {
                    LanHttp.respondText(out, 404, "")
                    return
                }
                out.write(LanHttp.head(200, "application/vnd.android.package-archive", f.length()))
                f.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.flush()
            }
            req.method == "GET" && path == "/prisme/settings" -> {
                if (!SettingsSync.isActive(ctx)) return LanHttp.respondText(out, 404, "")
                val key = Household.key(ctx)
                if (!HouseholdAuth.verify(key, req.method, path, ByteArray(0), req::header, settingsNonces)) {
                    return LanHttp.respondText(out, 403, "")
                }
                val body = SettingsSync.encode(SettingsSync.snapshot(ctx)).toByteArray(Charsets.UTF_8)
                val sig = HouseholdAuth.responseSig(key, req.header(HouseholdAuth.H_NONCE)!!, body)
                LanHttp.respond(out, 200, "application/json; charset=utf-8", body, mapOf(HouseholdAuth.H_SIG to sig))
            }
            req.method == "POST" && path == "/prisme/settings/changed" -> {
                if (!SettingsSync.isActive(ctx)) return LanHttp.respondText(out, 404, "")
                val len = req.contentLength
                if (len !in 1..MAX_SETTINGS_BODY.toLong()) return LanHttp.respondText(out, 413, "")
                val bytes = LanHttp.readBody(input, len.toInt())
                if (!HouseholdAuth.verify(Household.key(ctx), req.method, path, bytes, req::header, settingsNonces)) {
                    return LanHttp.respondText(out, 403, "")
                }
                LanHttp.respond(out, 204, "text/plain", ByteArray(0))
                SettingsSync.onRemoteChanged(ctx, String(bytes, Charsets.UTF_8), remote.hostAddress ?: "TV")
            }
            req.method == "POST" && path == "/prisme/pair/hello" -> {
                val c = Household.host.hello() ?: return LanHttp.respondText(out, 404, "")
                json(out, 200, mapOf("c" to c, "device" to deviceName(ctx)))
            }
            req.method == "POST" && path == "/prisme/pair/claim" -> {
                val len = req.contentLength
                if (len !in 1..MAX_PAIR_BODY.toLong()) return LanHttp.respondText(out, 413, "")
                val m = runCatching { MiniJson.parse(String(LanHttp.readBody(input, len.toInt()), Charsets.UTF_8)) as Map<*, *> }
                    .getOrNull() ?: return LanHttp.respondText(out, 400, "")
                when (val r = Household.onClaim(ctx, m["c"] as? String, m["n"] as? String, m["p"] as? String)) {
                    is PairingHost.Result.Ok -> json(out, 200, mapOf("k" to r.wrapped, "t" to r.tag, "device" to deviceName(ctx)))
                    is PairingHost.Result.Denied -> LanHttp.respondText(out, 403, "")
                    PairingHost.Result.Inactive -> LanHttp.respondText(out, 404, "")
                }
            }
            else -> LanHttp.respondText(out, 404, "")
        }
    }

    // ------------------------------------------------------------------------- NSD

    private fun register(ctx: Context, port: Int) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Prisme-" + deviceName(ctx)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) { registeredName = s.serviceName }
                override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
            })
        }
    }

    /**
     * Cherche les autres TV pendant [timeoutMs] (verrou multicast Wi-Fi tenu seulement pendant la recherche),
     * vérifie chacune (GET /prisme/version) et complète avec les TV déjà connues (si mDNS est capricieux).
     */
    suspend fun discover(ctx: Context, timeoutMs: Long = 4_000): List<PeerInfo> = withContext(Dispatchers.IO) {
        val c = ctx.applicationContext
        runCatching { rebindIfNeeded(c) }
        val candidates = ConcurrentHashMap<String, Pair<String, Int>>()
        val nsd = c.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd != null) {
            val wifi = c.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = runCatching {
                wifi?.createMulticastLock("prisme-discovery")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            val queue = LinkedBlockingQueue<NsdServiceInfo>()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onServiceFound(s: NsdServiceInfo) {
                    if (s.serviceName != registeredName) queue.add(s)
                }
            }
            try {
                val started = runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
                if (started) {
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (true) {
                        val left = deadline - System.currentTimeMillis()
                        val s = (if (left > 0) queue.poll(left, TimeUnit.MILLISECONDS) else queue.poll()) ?: break
                        resolve(nsd, s)?.let { candidates[it.first + ":" + it.second] = it }
                    }
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
            } finally {
                runCatching { lock?.release() }
            }
        }
        // TV déjà vues : on les essaie aussi directement.
        for (p in peers.values) candidates.putIfAbsent(p.host + ":" + p.port, p.host to p.port)
        val own = ownAddresses()
        val found = candidates.values
            .filter { (h, p) -> !(p == port && h in own) }
            .mapNotNull { (h, p) -> probe(h, p) }
            .distinctBy { it.host + ":" + it.port }
        // Rien trouvé (TV éteinte, Wi-Fi coupé) : on garde les TV connues pour la prochaine fois.
        if (found.isNotEmpty()) {
            peers.clear()
            for (p in found) peers[p.host + ":" + p.port] = p
            saveKnownPeers(c)
        }
        found
    }

    /** Résolution NSD (une à la fois : obligatoire avant Android 14). */
    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, s: NsdServiceInfo): Pair<String, Int>? {
        val latch = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { latch.countDown() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val h = info.host?.hostAddress
                    if (h != null && LanHttp.isLanAddress(info.host)) result = h.substringBefore('%') to info.port
                    latch.countDown()
                }
            })
        }.onFailure { return null }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun probe(host: String, port: Int): PeerInfo? = runCatching {
        if (!LanHttp.isLanAddress(InetAddress.getByName(host))) return null
        val base = PeerInfo(host, port, "", 0, "")
        val m = MiniJson.parse(httpGet(base.url("/prisme/version"), timeoutMs = 2_000)) as Map<*, *>
        if (m["app"] != "prisme") return null
        base.copy(
            device = (m["device"] as? String)?.take(40) ?: host,
            versionCode = (m["versionCode"] as? Number)?.toInt() ?: 0,
            versionName = (m["versionName"] as? String)?.take(40) ?: "",
            flavour = (m["flavour"] as? String)?.take(20) ?: "",
        )
    }.getOrNull()

    private fun ownAddresses(): Set<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    }.getOrDefault(emptySet())

    private fun loadKnownPeers(ctx: Context) {
        val set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PEERS, emptySet()).orEmpty()
        for (e in set) {
            val parts = e.split('|')
            val port = parts.getOrNull(1)?.toIntOrNull() ?: continue
            peers[parts[0] + ":" + port] = PeerInfo(parts[0], port, parts.getOrNull(2) ?: parts[0], 0, "")
        }
    }

    private fun saveKnownPeers(ctx: Context) {
        val set = peers.values.map { it.host + "|" + it.port + "|" + it.device.replace("|", " ") }.toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_PEERS, set).apply()
    }

    // ------------------------------------------------------------------------- Client HTTP

    fun httpGet(url: String, timeoutMs: Int = 5_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Requête vers une autre TV avec en-têtes ; renvoie code, corps (1 Mo au plus) et en-têtes. Pas de
     * redirection suivie. Lève une exception si la TV est injoignable.
     */
    fun exchange(
        url: String, method: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 5_000,
    ): PeerResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        c.requestMethod = method
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_SETTINGS_BODY) throw IOException("Réponse trop volumineuse")
                }
                buf.toByteArray()
            } ?: ByteArray(0)
            val h = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && !v.isNullOrEmpty()) h[k.lowercase()] = v.first()
            return PeerResponse(code, bytes, h)
        } finally {
            c.disconnect()
        }
    }
}
