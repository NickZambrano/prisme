package com.fluxfr.tv.data

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Mini serveur web local, actif uniquement pendant l'écran de connexion :
 * on saisit ses identifiants depuis le téléphone au lieu de la télécommande.
 */
class PhoneSetupServer(
    private val page: Page = Page.ACCOUNT,
    private val onReceived: (Map<String, String>) -> Unit,
) {
    /**
     * ACCOUNT : abonnement IPTV ; EXTRAS : nom d'un nouveau profil (depuis les réglages) ;
     * UPDATE : envoi d'un APK de Prisme depuis le téléphone (voir [uploadDir] et [onApk]).
     */
    enum class Page { ACCOUNT, EXTRAS, UPDATE, NETFLIX }

    /** Page NETFLIX : profils Prisme proposés (identifiant, nom), le premier ou [netflixDefault] présélectionné. */
    @Volatile var netflixProfiles: () -> List<Pair<String, String>> = { emptyList() }
    @Volatile var netflixDefault: String? = null

    /**
     * Page NETFLIX : CSV reçu (profil Prisme, nom du fichier, texte) → message d'erreur, null si l'import a
     * démarré sur la TV.
     */
    @Volatile var onNetflixCsv: ((String, String, String) -> String?)? = null

    /** Page NETFLIX : état de l'import en JSON (sondé par la page pendant le traitement). */
    @Volatile var netflixStatus: () -> String = { "{}" }

    /**
     * Page NETFLIX : profils ayant un import en place, en JSON
     * (`[{profile, name, rows, label, at, file}]`, voir [NetflixImport.importsJson]).
     */
    @Volatile var netflixImports: () -> String = { "[]" }

    /** Page NETFLIX : « Annuler les imports » d'un profil → message d'erreur, null si c'est fait. */
    @Volatile var onNetflixUndo: ((String) -> String?)? = null

    /** Page NETFLIX : script du bouton « Exporter Prime Video » (asset [PrimeExport.ASSET]), pour GET /prime-export. */
    @Volatile var primeScript: () -> String? = { null }

    /** Page UPDATE : dossier où l'APK reçu est écrit (cacheDir de l'appli). */
    @Volatile var uploadDir: File? = null

    /** Page UPDATE : appelé (fil du serveur) avec l'APK reçu, complet. */
    @Volatile var onApk: ((File) -> Unit)? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    /** stop() déjà appelé : un listen() encore en cours referme son port au lieu de rester ouvert. */
    @Volatile private var stopped = false

    /**
     * Jeton aléatoire de cette ouverture de page : toutes les adresses sont sous /<jeton>/ (QR code et
     * adresse affichée le contiennent) ; une requête sans le jeton reçoit 404. Change à chaque panneau.
     */
    internal val token: String = LanCrypto.urlToken(16)

    /** Démarre et renvoie l'adresse à ouvrir sur le téléphone, jeton compris (null si pas de réseau local). */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        // Écoute seulement sur l'interface du réseau local (Wi-Fi / Ethernet), sinon sur toutes.
        val bind = runCatching { InetAddress.getByName(ip) }.getOrNull()
        val port = listen(bind) ?: return null
        return "http://$ip:$port/$token/"
    }

    /**
     * Ouvre le port (8686, sinon aléatoire) sur [bindAddress] (toutes les interfaces si null) et lance la
     * boucle d'accueil ; chaque connexion est traitée par un petit groupe de fils (un client lent ne bloque
     * pas les autres). Renvoie le port, null en cas d'échec.
     */
    internal fun listen(bindAddress: InetAddress? = null): Int? {
        fun open(port: Int) = ServerSocket(port, 16, bindAddress)
        val s = runCatching { open(PORT) }.getOrElse { runCatching { open(0) }.getOrNull() } ?: return null
        val workers = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "phone-setup").apply { isDaemon = true } }
        synchronized(this) {
            // stop() appelé (fil principal) avant la fin de l'ouverture (fil IO) : on referme aussitôt.
            if (stopped) {
                runCatching { s.close() }
                workers.shutdown()
                return null
            }
            server = s
            pool = workers
        }
        thread(name = "phone-setup-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                val ok = runCatching {
                    workers.execute {
                        runCatching { handle(client, s.localPort) }
                        runCatching { client.close() }
                    }
                }.isSuccess
                if (!ok) runCatching { client.close() }
            }
        }
        return s.localPort
    }

    fun stop() {
        synchronized(this) {
            stopped = true
            runCatching { server?.close() }
            server = null
            pool?.shutdown()
            pool = null
        }
    }

    /** Chemin sous le jeton (« /<jeton>/status?x » → « /status?x ») ; null si le jeton manque ou diffère. */
    internal fun route(rawPath: String): String? = routeUnder(token, rawPath)

    private fun handle(socket: Socket, port: Int) {
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        if (!LanHttp.isLanAddress(socket.inetAddress)) {
            LanHttp.respondText(out, 403, "Réservé au réseau local")
            return
        }
        val input = BufferedInputStream(socket.getInputStream())
        val raw = LanHttp.readRequest(input) ?: return
        if (!LanHttp.requestAllowed(raw, port)) {
            LanHttp.respondText(out, 403, "")
            return
        }
        val routed = route(raw.path)
        if (routed == null) {
            LanHttp.respondText(out, 404, "")
            return
        }
        val req = LanHttp.Request(raw.method, routed, raw.headers, raw.input)
        if (page == Page.UPDATE) {
            handleUpdate(req, out)
            return
        }
        if (page == Page.NETFLIX) {
            handleNetflix(req, out)
            return
        }
        val length = req.contentLength
        if (req.method == "POST" && length in 1..10_000) {
            val bytes = LanHttp.readBody(input, length.toInt())
            val form = String(bytes, Charsets.UTF_8).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else decode(it.substring(0, i)) to decode(it.substring(i + 1))
            }.toMap()
            respond(out, page(done = true))
            onReceived(form)
        } else {
            respond(out, page(done = false))
        }
    }

    /** Page UPDATE : GET → formulaire ; POST multipart → APK écrit dans [uploadDir] puis [onApk]. */
    private fun handleUpdate(req: LanHttp.Request, out: OutputStream) {
        if (req.method != "POST") {
            respond(out, page(done = false))
            return
        }
        val boundary = LanHttp.boundaryOf(req.header("content-type"))
        val dir = uploadDir
        if (boundary == null || dir == null) {
            respond(out, page(done = false, message = "Envoi invalide : choisis un fichier .apk."), 400)
            return
        }
        if (req.contentLength > MAX_APK_BYTES + 64 * 1024) {
            respond(out, page(done = false, message = "Fichier trop volumineux (150 Mo au plus)."), 413)
            return
        }
        dir.mkdirs()
        val tmp = File.createTempFile("phone-upload", ".part", dir)
        val part = try {
            tmp.outputStream().buffered().use { LanHttp.copyFirstFilePart(req.input, boundary, it, MAX_APK_BYTES) }
        } catch (e: LanHttp.TooLarge) {
            tmp.delete()
            respond(out, page(done = false, message = "Fichier trop volumineux (150 Mo au plus)."), 413)
            return
        } catch (e: Exception) {
            tmp.delete()
            respond(out, page(done = false, message = "Envoi interrompu, réessaie."), 400)
            return
        }
        if (part == null || part.size < 1024 || !part.fileName.endsWith(".apk", ignoreCase = true)) {
            tmp.delete()
            respond(out, page(done = false, message = "Ce n'est pas un fichier .apk."), 400)
            return
        }
        val apk = File(dir, UPLOAD_NAME)
        apk.delete()
        if (!tmp.renameTo(apk)) {
            tmp.delete()
            respond(out, page(done = false, message = "Impossible d'enregistrer le fichier sur la TV."), 500)
            return
        }
        respond(out, page(done = true))
        runCatching { onApk?.invoke(apk) }
    }

    private fun json(out: OutputStream, code: Int, body: String) =
        LanHttp.respond(out, code, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun jsonError(out: OutputStream, code: Int, msg: String) =
        json(out, code, MiniJson.write(mapOf("ok" to false, "error" to msg)))

    /**
     * Page NETFLIX : GET / → formulaire ; GET /status → état de l'import (JSON) ;
     * POST /upload?profile=<id> (multipart, un fichier .csv de 5 Mo au plus) → import lancé sur la TV ;
     * GET /imports → profils ayant un import (JSON) ; POST /undo?profile=<id> → imports du profil annulés ;
     * GET /prime-export → page d'installation du bouton « Exporter Prime Video » (à ouvrir sur ordinateur).
     */
    private fun handleNetflix(req: LanHttp.Request, out: OutputStream) {
        val path = req.path.substringBefore('?')
        if (req.method == "GET" && path == "/prime-export") {
            val js = runCatching { primeScript() }.getOrNull()
            if (js.isNullOrBlank()) LanHttp.respondText(out, 503, "Script indisponible sur la TV.")
            else respond(out, PrimeExport.installPage(js))
            return
        }
        if (req.method == "GET" && path == "/status") {
            json(out, 200, netflixStatus())
            return
        }
        if (req.method == "GET" && path == "/imports") {
            json(out, 200, runCatching { netflixImports() }.getOrDefault("[]"))
            return
        }
        val profile = req.path.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("profile=") }?.let { decode(it.substringAfter('=')) }.orEmpty()
        if (req.method == "POST" && path == "/undo") {
            val undo = onNetflixUndo ?: return jsonError(out, 503, "La TV n'est pas prête.")
            if (profile.isEmpty()) return jsonError(out, 400, "Profil inconnu.")
            val err = runCatching { undo(profile) }.getOrElse { "Annulation impossible." }
            if (err != null) jsonError(out, 409, err) else json(out, 200, MiniJson.write(mapOf("ok" to true)))
            return
        }
        if (req.method != "POST" || path != "/upload") {
            respond(out, page(done = false))
            return
        }
        val boundary = LanHttp.boundaryOf(req.header("content-type"))
            ?: return jsonError(out, 400, "Envoi invalide : choisis un fichier .csv.")
        if (req.contentLength > NetflixImport.MAX_BYTES + 64 * 1024) return jsonError(out, 413, "Fichier trop volumineux (5 Mo au plus).")
        val buf = java.io.ByteArrayOutputStream()
        val part = try {
            LanHttp.copyFirstFilePart(req.input, boundary, buf, NetflixImport.MAX_BYTES)
        } catch (e: LanHttp.TooLarge) {
            return jsonError(out, 413, "Fichier trop volumineux (5 Mo au plus).")
        } catch (e: Exception) {
            return jsonError(out, 400, "Envoi interrompu, réessaie.")
        }
        if (part == null || part.size == 0L || !part.fileName.endsWith(".csv", ignoreCase = true)) {
            return jsonError(out, 400, "Ce n'est pas un fichier .csv.")
        }
        val handler = onNetflixCsv ?: return jsonError(out, 503, "La TV n'est pas prête.")
        val err = runCatching { handler(profile, part.fileName, String(buf.toByteArray(), Charsets.UTF_8)) }
            .getOrElse { "Fichier illisible." }
        if (err != null) jsonError(out, 400, err) else json(out, 200, MiniJson.write(mapOf("ok" to true)))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun netflixForm(): String {
        val def = netflixDefault
        val options = netflixProfiles().joinToString("") { (id, name) ->
            "<option value=\"${esc(id)}\"${if (id == def) " selected" else ""}>${esc(name)}</option>"
        }
        return """
<h2>Importer un historique (Netflix ou Prime Video)</h2>
<p><b>Netflix</b> : netflix.com → Compte → Profil → Activité de visionnage → <b>Télécharger tout</b>
(fichier <b>NetflixViewingHistory.csv</b>).</p>
<p><b>Prime Video</b> : sur ordinateur, installe le bouton « Exporter Prime Video »
(<a href="prime-export" id="pvl" style="color:#3D7BFF">page d'installation</a> : <b id="pvu">/prime-export</b>)
puis clique dessus sur primevideo.com → Paramètres → Historique de visionnage
(fichier <b>PrimeVideoViewingHistory.csv</b>).</p>
<p>Envoie ensuite le fichier (5 Mo au plus) : les films et épisodes trouvés dans le catalogue sont marqués vus,
et « Continuer à regarder » propose l'épisode suivant.</p>
<form id="nf">
<label>Profil Prisme</label><select name="profile" id="pf">$options</select>
<input type="file" name="csv" accept=".csv,text/csv" required>
<progress id="pg" max="100" value="0"></progress>
<button type="submit" id="go">Envoyer à la TV</button>
</form>
<div id="st"></div>
<div id="imps"></div>
<script>
document.getElementById('pvu').textContent=document.getElementById('pvl').href;
var st=document.getElementById('st');
function line(t,c){var p=document.createElement('p');p.textContent=t;if(c)p.className=c;st.appendChild(p);}
function el(tag,t,c){var e=document.createElement(tag);if(t)e.textContent=t;if(c)e.className=c;return e;}
function imports(){var x=new XMLHttpRequest();x.open('GET','imports');x.onload=function(){
var l;try{l=JSON.parse(x.responseText);}catch(e){return;}
var box=document.getElementById('imps');box.innerHTML='';if(!l.length)return;
box.appendChild(el('h3','Imports en place'));
l.forEach(function(i){var d=el('div',null,'imp');
d.appendChild(el('b',i.name));
var info=i.label;if(i.at)info+=' · le '+new Date(i.at).toLocaleDateString('fr-FR');
d.appendChild(el('p',info,'dim'));if(i.file)d.appendChild(el('p','Fichier : '+i.file,'dim'));
var b=el('button','Annuler les imports','danger');
b.onclick=function(){if(!confirm('Supprimer les films et épisodes importés depuis Netflix et Prime Video pour « '+i.name+' » ? Ton historique Prisme n\'est pas touché.'))return;
b.disabled=true;var u=new XMLHttpRequest();u.open('POST','undo?profile='+encodeURIComponent(i.profile));
u.onload=function(){var r;try{r=JSON.parse(u.responseText);}catch(e){r={ok:false,error:'Réponse inattendue de la TV.'};}
st.innerHTML='';if(r.ok)line('Imports annulés pour « '+i.name+' ».');else{line(r.error||'Erreur','err');b.disabled=false;}imports();};
u.onerror=function(){b.disabled=false;line('TV injoignable, réessaie.','err');};u.send();};
d.appendChild(b);box.appendChild(d);});};x.send();}
imports();
function poll(){var x=new XMLHttpRequest();x.open('GET','status');x.onload=function(){
var s;try{s=JSON.parse(x.responseText);}catch(e){setTimeout(poll,1500);return;}
st.innerHTML='';
if(s.error){line(s.error,'err');return;}
if(s.summary){imports();line(s.summary.done||('Import terminé pour « '+s.summary.profile+' »'));s.summary.lines.forEach(function(l){line(l);});
if(s.summary.notFoundCount>0){line('Titres non trouvés ('+s.summary.notFoundCount+') : '+s.summary.notFound.join(', ')+(s.summary.notFoundCount>s.summary.notFound.length?'…':''),'dim');}
return;}
line((s.phase||'Import en cours…')+(s.total?' '+s.done+' / '+s.total:''));setTimeout(poll,1000);};
x.onerror=function(){setTimeout(poll,2000);};x.send();}
document.getElementById('nf').onsubmit=function(e){e.preventDefault();
var f=this.csv.files[0];if(!f)return false;
if(f.size>$MAX_NETFLIX){alert('Fichier trop volumineux (5 Mo au plus).');return false;}
var x=new XMLHttpRequest(),pg=document.getElementById('pg');pg.style.display='block';st.innerHTML='';
x.upload.onprogress=function(ev){if(ev.lengthComputable)pg.value=ev.loaded*100/ev.total;};
x.onload=function(){pg.style.display='none';var r;try{r=JSON.parse(x.responseText);}catch(e){r={ok:false,error:'Réponse inattendue de la TV.'};}
if(r.ok){line('Fichier reçu, la TV traite l\'historique…');poll();}else line(r.error||'Erreur','err');};
x.onerror=function(){pg.style.display='none';line('Envoi interrompu, réessaie.','err');};
var fd=new FormData();fd.append('csv',f);
x.open('POST','upload?profile='+encodeURIComponent(document.getElementById('pf').value));x.send(fd);return false;};
</script>
"""
    }

    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun respond(out: OutputStream, html: String, code: Int = 200) =
        LanHttp.respond(out, code, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))

    private fun page(done: Boolean, message: String? = null): String = """
<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Prisme</title>
<base href="/$token/">
<style>
body{font-family:system-ui,sans-serif;background:#0E0F13;color:#fff;margin:0;padding:24px;max-width:480px}
h1{margin:0 0 4px}p{color:#A0A6B4}label{display:block;margin:16px 0 6px;color:#A0A6B4;font-size:14px}
input[type=text],input[type=password],input[type=url]{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;
border:1px solid #2A2E3A;background:#1B1E26;color:#fff;font-size:16px}
.tabs{display:flex;gap:8px;margin-top:16px}.tabs label{margin:0;flex:1;text-align:center;padding:10px;border-radius:8px;
background:#1B1E26;color:#fff;cursor:pointer}.tabs input{display:none}.tabs input:checked+span{color:#3D7BFF;font-weight:600}
button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:8px;background:#3D7BFF;color:#fff;font-size:17px}
#m3u{display:none}
details{margin-top:24px;padding:12px 14px;border-radius:8px;background:#15171E}summary{cursor:pointer;font-weight:600}
small{color:#A0A6B4}.err{color:#FF6B6B}.dim{font-size:14px}
select{width:100%;padding:12px;border-radius:8px;border:1px solid #2A2E3A;background:#1B1E26;color:#fff;font-size:16px}
#st p{color:#fff;margin:8px 0}#st p.err{color:#FF6B6B}#st p.dim{color:#A0A6B4}
input[type=file]{width:100%;margin-top:12px;color:#fff}
progress{width:100%;height:14px;margin-top:16px;display:none}
.imp{margin-top:12px;padding:12px 14px;border-radius:8px;background:#15171E}.imp p{margin:4px 0}.imp p.dim{color:#A0A6B4}
button.danger{margin-top:12px;padding:10px;font-size:15px;background:#3A1D22;color:#FF6B6B}button:disabled{opacity:.5}
</style></head><body>
<h1>Prisme</h1>
${message?.let { "<p class=\"err\">$it</p>" } ?: ""}
${when {
        done && page == Page.UPDATE -> "<p>APK reçu&nbsp;! Regarde ta TV : elle vérifie le fichier puis lance l'installation (confirme sur la TV).</p>"
        done && page == Page.EXTRAS -> "<p>C'est envoyé&nbsp;! Regarde ta TV.</p>"
        done -> "<p>C'est envoyé&nbsp;! Regarde ta TV : la connexion est en cours.</p>"
        page == Page.UPDATE -> updateForm()
        page == Page.NETFLIX -> netflixForm()
        page == Page.EXTRAS -> extrasForm()
        else -> accountForm()
    }}
</body></html>
"""

    private fun extrasForm() = """
<p>Réglages de la TV depuis ton téléphone.</p>
<form method="post">
<details open><summary>Nouveau profil</summary>
<label>Nom du profil à créer</label><input type="text" name="profileName" maxlength="20">
</details>
<button type="submit">Envoyer à la TV</button>
</form>
"""

    private fun updateForm() = """
<h2>Mettre à jour Prisme</h2>
<p>Choisis le fichier .apk de la nouvelle version (150 Mo au plus). La TV vérifie qu'il s'agit bien de Prisme,
signé avec la même clé, avant de l'installer.</p>
<form id="up" method="post" enctype="multipart/form-data">
<input type="file" name="apk" accept=".apk,application/vnd.android.package-archive" required>
<progress id="pg" max="100" value="0"></progress>
<button type="submit">Envoyer à la TV</button>
</form>
<script>
document.getElementById('up').onsubmit=function(e){
var f=this.apk.files[0];if(!f||!window.FormData)return true;e.preventDefault();
if(f.size>$MAX_APK_BYTES){alert('Fichier trop volumineux (150 Mo au plus).');return false;}
var x=new XMLHttpRequest(),pg=document.getElementById('pg');pg.style.display='block';
x.upload.onprogress=function(ev){if(ev.lengthComputable)pg.value=ev.loaded*100/ev.total;};
x.onload=function(){document.open();document.write(x.responseText);document.close();};
x.onerror=function(){alert('Envoi interrompu, réessaie.');pg.style.display='none';};
x.open('POST',location.pathname);x.send(new FormData(this));return false;};
</script>
"""

    private fun accountForm() = """
<p>Configure ton abonnement IPTV, la TV se connectera toute seule.</p>
<form method="post">
<div class="tabs">
<label><input type="radio" name="mode" value="xtream" checked onchange="sw()"><span>Xtream Codes</span></label>
<label><input type="radio" name="mode" value="m3u" onchange="sw()"><span>Playlist M3U</span></label>
</div>
<div id="xt">
<label>Serveur (ex. http://monserveur.com:8080)</label><input type="url" name="server" autocapitalize="off" autocorrect="off">
<label>Nom d'utilisateur</label><input type="text" name="user" autocapitalize="off" autocorrect="off">
<label>Mot de passe</label><input type="password" name="pass">
</div>
<div id="m3u"><label>URL de la playlist M3U</label><input type="url" name="m3u" autocapitalize="off"></div>
<button type="submit">Envoyer à la TV</button>
</form>
<script>function sw(){var m=document.querySelector('input[name=mode]:checked').value;
document.getElementById('xt').style.display=m=='xtream'?'block':'none';
document.getElementById('m3u').style.display=m=='m3u'?'block':'none';}</script>
"""

    companion object {
        const val PORT = 8686
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        const val MAX_NETFLIX = NetflixImport.MAX_BYTES
        const val UPLOAD_NAME = "phone-upload.apk"
        private const val WORKERS = 4

        /** Routage sous le jeton (comparaison à temps constant) ; voir [route]. */
        internal fun routeUnder(token: String, rawPath: String): String? {
            if (!rawPath.startsWith("/")) return null
            val path = rawPath.substringBefore('?')
            val query = rawPath.substring(path.length)
            val first = path.substring(1).substringBefore('/')
            if (!LanCrypto.equal(first.toByteArray(), token.toByteArray())) return null
            val rest = path.substring(1 + first.length)
            return rest.ifEmpty { "/" } + query
        }

        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
