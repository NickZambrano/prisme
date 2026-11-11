package com.fluxfr.tv.data

/** Une chaîne TNT retrouvée dans le flux IPTV, avec son numéro habituel. */
data class TntChannel(val number: Int, val channel: Channel)

/**
 * Les chaînes nationales de la TNT, retrouvées par leur nom dans le catalogue IPTV
 * (déjà filtré FR + HD/4K, avec ses sources de secours).
 */
object Tnt {
    private class Entry(val number: Int, vararg aliases: String) {
        val keys = aliases.map { ContentFilter.dedupKey(it) }.toSet()
    }

    // Numérotation de la TNT depuis la réorganisation de juin 2025 (fin de C8 et NRJ 12, départ de Canal+,
    // arrivée de T18 et Novo19, chaînes d'info regroupées de 13 à 16).
    private val ENTRIES = listOf(
        Entry(1, "TF1"),
        Entry(2, "France 2"),
        Entry(3, "France 3", "France 3 National", "France 3 Nationale"),
        Entry(4, "France 4"),
        Entry(5, "France 5"),
        Entry(6, "M6"),
        Entry(7, "Arte", "Arte France"),
        Entry(8, "LCP", "LCP Public Sénat", "LCP Assemblée Nationale", "Public Sénat", "LCP AN"),
        Entry(9, "W9"),
        Entry(10, "TMC"),
        Entry(11, "TFX"),
        Entry(12, "Gulli"),
        Entry(13, "BFM TV", "BFMTV"),
        Entry(14, "CNews", "C News"),
        Entry(15, "LCI"),
        Entry(16, "franceinfo", "France Info", "franceinfo TV", "France Info TV"),
        Entry(17, "CStar", "C Star"),
        Entry(18, "T18"),
        Entry(19, "Novo19", "Novo 19"),
        Entry(20, "TF1 Séries Films", "TF1 Serie Films", "TF1 Series Films", "TF1 SF"),
        Entry(21, "L'Équipe", "L Equipe", "L'Equipe TV", "La Chaîne L'Équipe"),
        Entry(22, "6ter", "6 ter"),
        Entry(23, "RMC Story"),
        Entry(24, "RMC Découverte", "RMC Decouverte"),
        Entry(25, "Chérie 25", "Cherie 25"),
    )

    fun find(catalog: Catalog): List<TntChannel> {
        // Le catalogue est déjà dédoublonné : une entrée par chaîne, avec sa version préférée en premier.
        val byKey = HashMap<String, Channel>()
        for (ch in catalog.unfiltered.channels) byKey.putIfAbsent(ContentFilter.dedupKey(ch.name), ch)
        return ENTRIES.mapNotNull { e ->
            e.keys.firstNotNullOfOrNull { byKey[it] }?.let { TntChannel(e.number, it) }
        }
    }
}
