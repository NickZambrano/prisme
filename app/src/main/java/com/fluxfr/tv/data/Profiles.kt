package com.fluxfr.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Un profil : nom, couleur d'avatar (index dans la palette de l'interface), profil Enfants
 * (catalogue jeunesse seulement) et code facultatif qui protège la sortie d'un profil Enfants.
 * Le code n'est jamais gardé en clair : [pinHash] est son empreinte salée ([PinHash]), vide si aucun code.
 * Le compte Google du profil est gardé à part (voir [GoogleAuth]).
 */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,
    val kids: Boolean,
    val pinHash: String = "",
) {
    val hasPin: Boolean get() = pinHash.isNotEmpty()

    /** Le code saisi est-il le bon ? (false s'il n'y a pas de code). */
    fun checkPin(entered: String): Boolean = hasPin && PinHash.verify(entered, pinHash)
}

/**
 * Empreinte d'un code parental : PBKDF2-HMAC-SHA256, sel aléatoire par profil, 60 000 itérations.
 * Format : « p1$<itérations>$<sel base64>$<empreinte base64> ». Implémentation directe (SecretKeyFactory
 * « PBKDF2WithHmacSHA256 » n'existe qu'à partir d'Android 8).
 */
object PinHash {
    const val ITERATIONS = 60_000
    private const val PREFIX = "p1"
    private val FORMAT = Regex("""p1\$(\d{4,7})\$([A-Za-z0-9+/=]{16,64})\$([A-Za-z0-9+/=]{40,64})""")
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000

    fun hash(pin: String, salt: ByteArray = LanCrypto.randomBytes(16), iterations: Int = ITERATIONS): String =
        listOf(PREFIX, iterations.toString(), Base64Codec.encode(salt), Base64Codec.encode(pbkdf2(pin, salt, iterations)))
            .joinToString("\$")

    /** Empreinte au bon format, avec un nombre d'itérations raisonnable (une empreinte reçue ne peut pas bloquer la TV). */
    fun isValid(stored: String): Boolean {
        val m = FORMAT.matchEntire(stored) ?: return false
        val it = m.groupValues[1].toIntOrNull() ?: return false
        return it in MIN_ITERATIONS..MAX_ITERATIONS
    }

    fun verify(pin: String, stored: String): Boolean {
        if (!isValid(stored)) return false
        val parts = stored.split('$')
        val salt = Base64Codec.decode(parts[2])
        val expected = Base64Codec.decode(parts[3])
        return LanCrypto.equal(pbkdf2(pin, salt, parts[1].toInt()), expected)
    }

    /** PBKDF2-HMAC-SHA256, un seul bloc de 32 octets (RFC 8018). */
    internal fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val pw = password.toByteArray(Charsets.UTF_8)
        // HMAC refuse une clé vide : le code n'est de toute façon jamais vide.
        mac.init(javax.crypto.spec.SecretKeySpec(if (pw.isEmpty()) ByteArray(1) else pw, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }
}

/**
 * Profils de l'appli (« Qui regarde ? »). Chaque profil a ses reprises de lecture, sa liste et ses
 * chaînes récentes ; il peut être lié à son propre compte Google (synchro Google Drive).
 */
object Profiles {
    const val MAIN = "main"
    const val KIDS = "kids"
    const val MAX_PROFILES = 5
    private const val PREFS = "fluxfr"
    private const val KEY_LIST = "profiles"
    private const val KEY_CURRENT = "profile"
    private const val KEY_ASK = "askProfileAtStart"

    /** Mots qui désignent une catégorie jeunesse. */
    val KIDS_WORDS = setOf(
        "ENFANT", "ENFANTS", "KIDS", "KID", "JEUNESSE", "JUNIOR", "ANIMATION", "ANIME", "ANIMES", "DESSIN",
        "DESSINS", "FAMILLE", "FAMILY", "DISNEY", "PIXAR", "CARTOON", "CARTOONS", "GULLI", "NICKELODEON", "TIJI",
    )

    private val DEFAULT_MAIN = Profile(MAIN, "Principal", 0, kids = false)

    private val _list = MutableStateFlow(listOf(DEFAULT_MAIN))
    val list: StateFlow<List<Profile>> = _list.asStateFlow()

    /** Identifiant du profil actif. */
    private val _current = MutableStateFlow(MAIN)
    val current: StateFlow<String> = _current.asStateFlow()

    /** Profil actif complet (suit les modifications : nom, couleur, Enfants…). */
    private val _active = MutableStateFlow(DEFAULT_MAIN)
    val active: StateFlow<Profile> = _active.asStateFlow()

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(ctx)
            val raw = p.getString(KEY_LIST, null)
            val stored = raw?.let { decode(it, migrateLegacyPins = true) }
            // Codes enregistrés en clair par une version précédente : remplacés une fois pour toutes par leur empreinte.
            if (!stored.isNullOrEmpty() && raw != null && hasLegacyPins(raw)) p.edit().putString(KEY_LIST, encode(stored)).apply()
            val profiles = if (stored.isNullOrEmpty()) migrate(ctx) else stored
            _list.value = profiles
            val cur = p.getString(KEY_CURRENT, MAIN) ?: MAIN
            _current.value = if (profiles.any { it.id == cur }) cur else profiles.first().id
            refreshActive()
            loaded = true
        }
    }

    /**
     * Première ouverture avec les profils multiples : les données existantes deviennent celles de
     * « Principal », et l'ancien mode Enfants devient le profil « Enfants » (avec son code).
     */
    private fun migrate(ctx: Context): List<Profile> {
        val p = prefs(ctx)
        val oldPin = p.getString("kidsPin", "") ?: ""
        val hash = if (isLegacyPin(oldPin)) PinHash.hash(oldPin) else ""
        val profiles = listOf(DEFAULT_MAIN, Profile(KIDS, "Enfants", 1, kids = true, pinHash = hash))
        val e = p.edit().putString(KEY_LIST, encode(profiles)).remove("kidsPin")
        // Listes à clé par profil : on recopie l'ancienne liste commune pour Principal (et Enfants, qui la partageait).
        for (key in listOf("favorites", "recentChannels")) {
            val old = p.getString(key, null) ?: continue
            if (!p.contains(key + "_" + MAIN)) e.putString(key + "_" + MAIN, old)
            if (key == "favorites" && !p.contains(key + "_" + KIDS)) e.putString(key + "_" + KIDS, old)
        }
        e.apply()
        return profiles
    }

    fun get(id: String): Profile? = _list.value.firstOrNull { it.id == id }

    /**
     * Passer de [active] à [target] demande-t-il le code parental ? Oui dès qu'on quitte un profil Enfants
     * (avec ou sans code) pour un profil adulte, si AU MOINS UN profil Enfants a un code : sinon un enfant
     * passerait par un profil Enfants sans code pour atteindre un profil adulte.
     */
    fun needsParentalPin(active: Profile, target: Profile, all: List<Profile>): Boolean =
        active.kids && !target.kids && target.id != active.id && all.any { it.kids && it.hasPin }

    /** Code parental : celui de n'importe quel profil Enfants qui en a un est accepté. */
    fun checkParentalPin(entered: String, all: List<Profile>): Boolean = all.any { it.kids && it.checkPin(entered) }

    /** Change de profil : listes, reprises et rangées se rechargent. */
    fun set(ctx: Context, id: String) {
        load(ctx)
        if (get(id) == null) return
        prefs(ctx).edit().putString(KEY_CURRENT, id).apply()
        if (_current.value == id) return
        _current.value = id
        refreshActive()
        Favorites.reload(ctx)
        RecentChannels.reload(ctx)
        ProgressStore.notifyChanged()
    }

    fun add(ctx: Context, name: String, color: Int, kids: Boolean): Profile? {
        load(ctx)
        if (_list.value.size >= MAX_PROFILES) return null
        val p = Profile("p" + System.currentTimeMillis().toString(36), cleanName(name), color, kids)
        save(ctx, _list.value + p)
        return p
    }

    fun update(ctx: Context, profile: Profile) {
        load(ctx)
        save(ctx, _list.value.map { if (it.id == profile.id) profile.copy(name = cleanName(profile.name)) else it })
    }

    /** Supprime un profil et toutes ses données (le dernier profil ne peut pas être supprimé). */
    fun delete(ctx: Context, id: String) {
        load(ctx)
        val rest = _list.value.filter { it.id != id }
        if (rest.isEmpty() || rest.size == _list.value.size) return
        if (_current.value == id) set(ctx, (rest.firstOrNull { !it.kids } ?: rest.first()).id)
        save(ctx, rest)
        prefs(ctx).edit().remove("favorites_$id").remove("recentChannels_$id").apply()
        runCatching { ProgressStore.get(ctx).deleteProfile(id) }
        GoogleAuth.clear(ctx, id)
        // Synchro Drive du profil : fichier, version, état, puis horodatages et suppressions (préférences « drive_sync »).
        runCatching { DriveSync.forget(ctx, id) }
        runCatching {
            val e = ctx.applicationContext.getSharedPreferences("drive_sync", Context.MODE_PRIVATE).edit()
            for (k in listOf("dirty", "progdel", "progrev", "favts", "favdel", "status", "file", "version", "last")) e.remove(k + "_" + id)
            e.apply()
        }
        // Recommandations et historique importé (Netflix / Prime Video) du profil.
        runCatching { Reco.invalidate(ctx, id) }
        runCatching { NetflixSeeds.delete(ctx, id) }
        runCatching { NetflixImportMeta.clear(ctx, id) }
    }

    /** Demander « Qui regarde ? » au démarrage (s'il y a au moins 2 profils). */
    fun askAtStart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ASK, true)
    fun setAskAtStart(ctx: Context, v: Boolean) {
        if (prefs(ctx).contains(KEY_ASK) && askAtStart(ctx) == v) return
        prefs(ctx).edit().putBoolean(KEY_ASK, v).apply()
        SettingsSync.touch(ctx, SettingsSync.PREFS_DOMAIN)
    }

    /**
     * Remplace la liste des profils par celle reçue d'une autre TV ([SettingsSync]) : pas de nouvelle
     * synchronisation, les données des profils disparus sont gardées (au cas où ils reviendraient).
     */
    internal fun replaceAll(ctx: Context, profiles: List<Profile>) {
        load(ctx)
        if (profiles.isEmpty() || profiles == _list.value) return
        _list.value = profiles
        prefs(ctx).edit().putString(KEY_LIST, encode(profiles)).apply()
        if (profiles.none { it.id == _current.value }) {
            set(ctx, (profiles.firstOrNull { !it.kids } ?: profiles.first()).id)
        }
        refreshActive()
    }

    /** Nom affichable : sans caractères de contrôle, 20 caractères au plus. */
    internal fun cleanName(n: String) =
        n.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }.joinToString("")
            .trim().take(20).trim().ifBlank { "Profil" }

    private val ID = Regex("[A-Za-z0-9_-]{1,40}")
    private const val MAX_COLOR = 64

    /** Ancien code en clair (4 à 8 chiffres). */
    private fun isLegacyPin(s: String?) = s != null && s.length in 4..8 && s.all { it in '0'..'9' }

    private fun hasLegacyPins(raw: String): Boolean =
        runCatching { (MiniJson.parse(raw) as List<*>).any { (it as? Map<*, *>)?.get("pin") != null } }.getOrDefault(false)

    private fun save(ctx: Context, profiles: List<Profile>) {
        val changed = profiles != _list.value
        _list.value = profiles
        prefs(ctx).edit().putString(KEY_LIST, encode(profiles)).apply()
        refreshActive()
        if (changed) SettingsSync.touch(ctx, SettingsSync.PROFILES)
    }

    private fun refreshActive() {
        _active.value = get(_current.value) ?: _list.value.first()
    }

    internal fun encode(profiles: List<Profile>): String = MiniJson.write(toMaps(profiles))

    internal fun decode(s: String, migrateLegacyPins: Boolean = false): List<Profile>? =
        runCatching { fromMaps(MiniJson.parse(s) as List<*>, migrateLegacyPins) }.getOrNull()

    /** Forme stockée et partagée entre TV associées : l'empreinte du code seulement, jamais le code. */
    internal fun toMaps(profiles: List<Profile>): List<Map<String, Any>> =
        profiles.map { mapOf("id" to it.id, "name" to it.name, "color" to it.color, "kids" to it.kids, "pinHash" to it.pinHash) }

    /**
     * Profils lus (stockage local ou autre TV), assainis : identifiant [A-Za-z0-9_-] (40 au plus), nom
     * nettoyé, couleur bornée, empreinte au bon format (sinon pas de code), champs inconnus ignorés.
     * [migrateLegacyPins] : un ancien code en clair (« pin », stockage local seulement) est remplacé par son empreinte.
     */
    internal fun fromMaps(list: List<*>?, migrateLegacyPins: Boolean = false): List<Profile> = list.orEmpty().asSequence()
        .take(50)
        .mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? String)?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val stored = (m["pinHash"] as? String)?.takeIf { PinHash.isValid(it) }
            val legacy = (m["pin"] as? String)?.takeIf { migrateLegacyPins && isLegacyPin(it) }
            Profile(
                id = id,
                name = cleanName((m["name"] as? String)?.take(200) ?: "Profil"),
                color = ((m["color"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_COLOR),
                kids = m["kids"] as? Boolean ?: false,
                pinHash = stored ?: legacy?.let { PinHash.hash(it) } ?: "",
            )
        }.distinctBy { it.id }.take(MAX_PROFILES).toList()

    /** Catégorie (ou nom de chaîne) jeunesse ? Sert à filtrer le catalogue en profil Enfants. */
    fun isKids(category: String, name: String = ""): Boolean {
        val t = ContentFilter.tokens(category) + ContentFilter.tokens(name).take(3)
        return t.any { it in KIDS_WORDS || it.startsWith("ENFANT") || it.startsWith("DESSIN") }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Vue « Enfants » du catalogue : seulement les catégories jeunesse. */
fun Catalog.kidsOnly(): Catalog {
    val ch = channels.filter { Profiles.isKids(it.category, it.name) }
    val chCats = ch.mapTo(HashSet()) { it.category }
    return Catalog(
        channels = ch,
        movies = movies.filter { Profiles.isKids(it.category) },
        series = series.filter { Profiles.isKids(it.category) },
        channelCategories = channelCategories.filter { it in chCats },
        movieCategories = movieCategories.filter { Profiles.isKids(it) },
        seriesCategories = seriesCategories.filter { Profiles.isKids(it) },
        fetchedAt = fetchedAt,
        full = unfiltered,
    )
}
