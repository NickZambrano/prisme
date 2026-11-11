package com.fluxfr.tv.ui

import com.fluxfr.tv.data.DisplayTitle
import com.fluxfr.tv.data.Genres
import com.fluxfr.tv.data.searchKey
import java.util.Locale

/**
 * Règles pures de l'accueil (testées) : dédoublonnage des rangées de recommandations, chaînes « bouche-trou »
 * exclues de la rangée « Chaînes », mise en forme de la ligne d'infos du grand en-tête ([HeroMeta]).
 */
object HomeRows {
    /** Une rangée de recommandations dédoublonnée qui garde moins de titres que ça disparaît. */
    const val MIN_RECO_ROW = 6

    /** Clé « même titre » : type (m / s) + titre affiché normalisé (versions HD / 4K d'un même film confondues). */
    fun titleKey(kind: Char, name: String): String =
        kind + ":" + searchKey(DisplayTitle.of(name)).replace(NON_ALNUM, "")

    /**
     * Rangée de recommandations sans les titres déjà montrés plus haut ([seen], dans l'ordre d'affichage)
     * ni doublons internes. Moins de [min] titres restants : rangée vide (masquée) et [seen] inchangé ;
     * sinon les clés gardées rejoignent [seen].
     */
    fun <T> dedupeRow(items: List<T>, seen: MutableSet<String>, keys: (T) -> List<String>, min: Int = MIN_RECO_ROW): List<T> {
        val local = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (t in items) {
            val k = keys(t)
            if (k.any { it in seen || it in local }) continue
            local += k
            out += t
        }
        if (out.size < min) return emptyList()
        seen += local
        return out
    }

    // ------------------------------------------------------------ Chaînes « bouche-trou »

    private val NON_ALNUM = Regex("[^a-z0-9]")

    /** Flux d'événements : « PSG VS OM », « MULTI SCREEN 3 », « PPV 12 », horodatés (« 20:45 », « 12/05 »)… */
    private val EVENT_NAME = Regex(
        "(^|[^a-z0-9])(vs|v/s)([^a-z0-9]|$)|multi\\s*-?\\s*screen|multiscreen|multi\\s*cam|(^|[^a-z0-9])ppv([^a-z]|$)|" +
            "(^|[^a-z0-9])events?([^a-z]|$)|evenement|no\\s*event|placeholder|(^|[^a-z0-9])feed\\s*\\d|" +
            "(^|[^0-9])\\d{1,2}\\s*[:h]\\s*\\d{2}([^0-9]|$)|(^|[^0-9])\\d{1,2}[/.]\\d{1,2}([^0-9]|$)",
    )

    /** Catégories d'événements (et non de chaînes) : « FR | SPORTS EVENTS », « PPV », « Événements »… */
    private val EVENT_CATEGORY = Regex("(^|[^a-z0-9])(events?|ppv|evenements?|live\\s*events?|match\\s*center)([^a-z]|$)")

    /** Flux d'événement ou bouche-trou (à exclure de la rangée générale « Chaînes » de l'accueil). */
    fun isPlaceholderChannel(name: String, category: String?): Boolean {
        val n = searchKey(name)
        if (EVENT_NAME.containsMatchIn(n)) return true
        val c = searchKey(category.orEmpty())
        return c.isNotEmpty() && EVENT_CATEGORY.containsMatchIn(c)
    }

    // ------------------------------------------------------------ Ligne d'infos (en-tête)

    /** Note « 7,6 » (une décimale, virgule) ; null si absente, nulle ou illisible. « 76 » (sur 100) → « 7,6 ». */
    fun formatRating(raw: String?): String? {
        val v = raw?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (v.isNaN() || v <= 0.0) return null
        val ten = if (v > 10.0 && v <= 100.0) v / 10.0 else v
        if (ten > 10.0) return null
        return String.format(Locale.FRANCE, "%.1f", ten)
    }

    /**
     * Genres à afficher (2 au plus) : ceux de la fiche enrichie ([metaGenres]) sinon ceux qu'on reconnaît
     * dans la catégorie du fournisseur. Une catégorie qui n'est pas un genre (« LATEST MOVIES »,
     * « AMAZON PRIME », « FILMS ») ne donne rien.
     */
    fun heroGenres(metaGenres: List<String>?, category: String?): List<String> =
        (metaGenres?.takeIf { it.isNotEmpty() } ?: Genres.normalize(category)).take(2)

    /** Morceaux de la ligne d'infos, dans l'ordre : [leading], année, genres, [trailing] (la note est à part). */
    fun metaParts(leading: String?, year: Int?, genres: List<String>, trailing: String? = null): List<String> =
        listOfNotNull(
            leading?.takeIf { it.isNotBlank() },
            year?.takeIf { it in 1900..2100 }?.toString(),
            genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
            trailing?.takeIf { it.isNotBlank() },
        )

    const val META_SEPARATOR = "  •  "

    // ------------------------------------------------------------ Ordre stable des rangées

    /**
     * Ordre d'affichage stable pendant une session ([order] : ids déjà montrés, dans l'ordre, complété ici) :
     * une rangée déjà montrée garde sa place (son contenu peut changer), une nouvelle s'ajoute à la fin, une
     * rangée disparue est simplement absente. Évite que les rangées changent de place sous le doigt pendant
     * le classement par genre en arrière-plan.
     */
    fun <T> stableOrder(order: MutableList<String>, rows: List<T>, id: (T) -> String): List<T> {
        val byId = LinkedHashMap<String, T>()
        for (r in rows) byId.putIfAbsent(id(r), r)
        for (k in byId.keys) if (k !in order) order += k
        return order.mapNotNull { byId[it] }
    }

    // ------------------------------------------------------------ Titres affichés

    /** Année suivie de mentions en capitales (« - 2026 - VF », « (2026) MULTI 4K ») restée après [DisplayTitle.of]. */
    private val LEFTOVER_YEAR =
        Regex("""\s+[-–—|(\[]\s*(?:19|20)\d{2}\s*[)\]]?(?:\s*[-–—|]?\s*[A-Z0-9+]{1,10}){0,3}\s*$""")

    /**
     * Titre affiché d'un nom du catalogue : [DisplayTitle.of], puis sans année suivie de mentions techniques
     * (« Titre - 2026 - VF » → « Titre »). Une année qui fait partie du titre (« Blade Runner 2049 ») reste.
     */
    fun shownTitle(name: String): String {
        val t = DisplayTitle.of(name)
        val u = LEFTOVER_YEAR.replace(t, "").trim()
        return if (u.isEmpty()) t else DisplayTitle.of(u)
    }

    /** Version texte (téléphone) : « 2023  •  Thriller, Drame  •  ★ 7,6 ». */
    fun metaText(leading: String?, year: Int?, genres: List<String>, rating: String?, trailing: String? = null): String? {
        val parts = metaParts(leading, year, genres, trailing) + listOfNotNull(formatRating(rating)?.let { "★ $it" })
        return parts.takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR)
    }
}
