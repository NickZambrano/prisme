package com.fluxfr.tv.data.files

import android.content.Context
import com.fluxfr.tv.data.Profiles
import com.fluxfr.tv.data.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Onglet « Fichiers » : option d'affichage ([com.fluxfr.tv.data.Settings.filesTab], préférences « fluxfr », propre
 * à l'appareil comme « afr » : absente de SettingsSync), partages réseau enregistrés et dossiers choisis par
 * l'utilisateur (Storage Access Framework), dans un fichier séparé jamais synchronisé (mots de passe).
 */
object FilesStore {
    private const val PREFS = "fluxfr_files"
    private const val APP_PREFS = "fluxfr"
    private const val KEY_ENABLED = "filesTab"
    private const val KEY_SHARES = "smbShares"
    private const val KEY_TREES = "safTrees"

    private val _enabled = MutableStateFlow(false)
    /** Onglet « Fichiers » affiché dans le menu. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _shares = MutableStateFlow<List<SmbShare>>(emptyList())
    val shares: StateFlow<List<SmbShare>> = _shares.asStateFlow()

    private val _trees = MutableStateFlow<List<String>>(emptyList())
    /** Dossiers choisis (URI d'arborescence SAF, permission conservée). */
    val trees: StateFlow<List<String>> = _trees.asStateFlow()

    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun appPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    /** Lecture unique des préférences (rapide : petit fichier). */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(ctx)
            _enabled.value = appPrefs(ctx).getBoolean(KEY_ENABLED, false)
            _shares.value = SmbShare.decodeList(p.getString(KEY_SHARES, null))
            _trees.value = (p.getString(KEY_TREES, "") ?: "").split('\n').filter { it.isNotBlank() }
            loaded = true
        }
    }

    fun isEnabled(ctx: Context): Boolean { load(ctx); return _enabled.value }

    fun setEnabled(ctx: Context, on: Boolean) {
        load(ctx)
        appPrefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun share(ctx: Context, id: String): SmbShare? { load(ctx); return _shares.value.firstOrNull { it.id == id } }

    /** Ajoute ou remplace (même id) un partage. */
    fun saveShare(ctx: Context, share: SmbShare) {
        load(ctx)
        val list = _shares.value.filter { it.id != share.id } + share
        writeShares(ctx, list)
        SmbClient.forget(share.id)
    }

    fun deleteShare(ctx: Context, id: String) {
        load(ctx)
        writeShares(ctx, _shares.value.filter { it.id != id })
        SmbClient.forget(id)
    }

    private fun writeShares(ctx: Context, list: List<SmbShare>) {
        prefs(ctx).edit().putString(KEY_SHARES, SmbShare.encodeList(list)).apply()
        _shares.value = list
    }

    fun addTree(ctx: Context, uri: String) {
        load(ctx)
        if (uri in _trees.value) return
        val list = _trees.value + uri
        prefs(ctx).edit().putString(KEY_TREES, list.joinToString("\n")).apply()
        _trees.value = list
    }

    fun removeTree(ctx: Context, uri: String) {
        load(ctx)
        val list = _trees.value - uri
        prefs(ctx).edit().putString(KEY_TREES, list.joinToString("\n")).apply()
        _trees.value = list
    }

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
}

/**
 * Reprise de lecture des fichiers (local, réseau), par profil, à part de [com.fluxfr.tv.data.ProgressStore] :
 * ces lignes n'ont rien à faire dans « Continuer à regarder », dans Google TV ni sur Google Drive (chemins propres
 * à cet appareil). Au plus [MAX] entrées par profil (les plus anciennes sont oubliées).
 */
object FileResume {
    private const val PREFS = "fluxfr_files_resume"
    private const val MAX = 300

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        Profiles.load(ctx)
        return ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private fun prefKey(key: String) = Profiles.current.value + "|" + key

    /** À appeler hors du fil principal. */
    fun get(ctx: Context, key: String): Progress? {
        val raw = prefs(ctx).getString(prefKey(key), null) ?: return null
        val (pos, dur, at) = decode(raw) ?: return null
        return Progress(
            key = key, type = MediaFiles.TYPE, itemId = key, title = "", subtitle = null, poster = null, url = null,
            position = pos, duration = dur, updated = at, seriesId = null, season = 0, episode = 0,
        )
    }

    /** À appeler hors du fil principal. */
    fun save(ctx: Context, p: Progress) {
        val sp = prefs(ctx)
        val k = prefKey(p.key)
        val e = sp.edit()
        val prefix = Profiles.current.value + "|"
        val others = sp.all.filterKeys { it.startsWith(prefix) && it != k }
        if (others.size >= MAX) {
            others.entries.sortedBy { (it.value as? String)?.let(::decode)?.third ?: 0L }
                .take(others.size - MAX + 1).forEach { e.remove(it.key) }
        }
        e.putString(k, encode(p.position, p.duration, p.updated)).apply()
    }

    internal fun encode(position: Long, duration: Long, updated: Long) = "$position,$duration,$updated"

    internal fun decode(raw: String): Triple<Long, Long, Long>? {
        val p = raw.split(',')
        if (p.size != 3) return null
        return Triple(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null)
    }
}
