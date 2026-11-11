package com.fluxfr.tv.data

import java.net.URLEncoder

/**
 * Bouton « Exporter Prime Video » : favori JavaScript (bookmarklet) tiré de l'asset prime-export.js
 * (copie de tools/prime-export.js), et page d'installation servie par la TV (GET /prime-export, voir
 * [PhoneSetupServer]). Une copie autonome de la page est dans tools/prime-export-install.html
 * (régénérée par PrimeExportTest).
 */
object PrimeExport {
    const val ASSET = "prime-export.js"
    const val LABEL = "Exporter Prime Video"

    private val HEADER_COMMENT = Regex("""^\s*/\*[\s\S]*?\*/""")

    /**
     * Code sur une seule ligne (un navigateur retire les sauts de ligne d'une adresse collée) : commentaire
     * d'en-tête et lignes de commentaire « // » retirés, lignes rognées puis jointes par une espace. Le script
     * doit donc terminer ses instructions par « ; » et n'avoir de commentaires « // » que sur des lignes seules.
     */
    fun minify(js: String): String = HEADER_COMMENT.replace(js.removePrefix("﻿"), "")
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.joinToString(" ")

    /**
     * Adresse du favori : `javascript:` + code encodé (UTF-8, espaces en %20 et non « + », qui serait lu
     * comme un plus). « void » : la page n'est pas remplacée par la valeur du script.
     */
    fun bookmarkletUrl(js: String): String =
        "javascript:" + URLEncoder.encode("void " + minify(js), "UTF-8").replace("+", "%20")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Page d'installation (HTML complet, aux couleurs de Prisme) pour le script [js]. */
    fun installPage(js: String): String = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Prisme · $LABEL</title>
<style>
body{font-family:system-ui,sans-serif;background:#0E0F13;color:#fff;margin:0 auto;padding:32px 24px;max-width:640px;line-height:1.5}
h1{margin:0 0 4px;font-size:28px}h2{margin:0 0 20px;font-size:20px;font-weight:600}
p,li{color:#A0A6B4}b{color:#fff}
ol{padding-left:22px}li{margin:10px 0}
.card{margin:20px 0;padding:18px 20px;border-radius:12px;background:#15171E}
.bm{display:inline-block;margin:8px 0;padding:12px 20px;border-radius:10px;background:#3D7BFF;color:#fff;font-weight:600;
text-decoration:none;cursor:grab;font-size:17px}
.bm:active{cursor:grabbing}
button{padding:10px 16px;border:0;border-radius:8px;background:#2A2E3A;color:#fff;font-size:15px;cursor:pointer}
button:hover{background:#353a49}
textarea{width:100%;box-sizing:border-box;height:72px;margin-top:10px;padding:8px;border-radius:8px;border:1px solid #2A2E3A;
background:#1B1E26;color:#A0A6B4;font:12px monospace;display:none}
#ok{color:#4CD07D;margin-left:10px}
small{color:#A0A6B4}
</style></head><body>
<h1>Prisme</h1>
<h2>Exporter l'historique Prime Video</h2>
<p>Prime Video ne propose pas de téléchargement de l'historique. Ce bouton à ajouter aux favoris de ton
navigateur (sur ordinateur) le lit sur la page « Historique de visionnage » et en fait un fichier pour Prisme.
Tout se passe dans ton navigateur : rien n'est envoyé ailleurs.</p>
<div class="card">
<ol>
<li>Affiche la barre des favoris (<b>Ctrl+Maj+B</b>, ou <b>Cmd+Maj+B</b> sur Mac).</li>
<li>Fais glisser ce bouton dans la barre des favoris :<br>
<a class="bm" id="bm" href="${esc(bookmarkletUrl(js))}" onclick="alert('Fais glisser ce bouton dans la barre des favoris, puis clique dessus sur primevideo.com.');return false;">$LABEL</a></li>
<li>Sur <b>primevideo.com</b> (connecté à ton compte) : <b>Paramètres → Historique de visionnage</b>.</li>
<li>Clique sur le favori <b>« $LABEL »</b> : la page défile toute seule, puis le fichier
<b>PrimeVideoViewingHistory.csv</b> est téléchargé.</li>
<li>Envoie ce fichier à la TV : Réglages → ton profil → <b>Importer un historique (Netflix ou Prime Video)</b>,
puis la page indiquée par la TV.</li>
</ol>
</div>
<div class="card">
<p><b>Impossible de faire glisser le bouton ?</b> Copie le code, crée un favori (sur n'importe quelle page),
modifie-le : nomme-le « $LABEL » et colle le code dans le champ <b>URL</b> (adresse).</p>
<button type="button" id="cp">Copier le code</button><span id="ok"></span>
<textarea id="code" readonly></textarea>
</div>
<p><small>Le bouton ne fonctionne que sur la page « Historique de visionnage » de Prime Video, dans un navigateur d'ordinateur.</small></p>
<script>
document.getElementById('cp').onclick=function(){
var code=document.getElementById('bm').getAttribute('href'),ta=document.getElementById('code'),ok=document.getElementById('ok');
ta.value=code;ta.style.display='block';ta.focus();ta.select();
function done(){ok.textContent='Code copié.';}
function manual(){ok.textContent='Sélectionne le code ci-dessous et copie-le (Ctrl+C).';}
var copied=false;try{copied=document.execCommand('copy');}catch(e){}
if(copied){done();return;}
if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(code).then(done,manual);}else manual();
};
</script>
</body></html>
"""
}
