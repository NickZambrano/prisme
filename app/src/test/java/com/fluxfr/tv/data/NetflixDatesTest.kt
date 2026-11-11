package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Détection J/M ou M/J d'après TOUT le fichier (petits fichiers ambigus compris). */
class NetflixDatesTest {
    private val today = NfDate(2026, 11, 11)

    @Test fun secondNumberAbove12MeansUs() {
        assertEquals(
            listOf(NfDate(2026, 3, 25), NfDate(2026, 3, 2)),
            NetflixCsv.parseDates(listOf("3/25/26", "3/2/26"), today),
        )
    }

    @Test fun firstNumberAbove12MeansDayFirst() {
        assertEquals(
            listOf(NfDate(2026, 3, 2), NfDate(2026, 2, 25)),
            NetflixCsv.parseDates(listOf("02/03/2026", "25/02/2026"), today),
        )
    }

    @Test fun ambiguousSmallFilePrefersNewestFirstOrder() {
        // M/J donnerait 2026-01-05 puis 2026-02-03 (croissant) ; J/M donne 2026-05-01 puis 2026-03-02 (décroissant).
        assertEquals(
            listOf(NfDate(2026, 5, 1), NfDate(2026, 3, 2)),
            NetflixCsv.parseDates(listOf("01/05/2026", "02/03/2026"), today),
        )
        // Et l'inverse : M/J décroissant, J/M croissant → M/J.
        assertEquals(
            listOf(NfDate(2026, 5, 1), NfDate(2026, 3, 2)),
            NetflixCsv.parseDates(listOf("5/1/26", "3/2/26"), today),
        )
    }

    @Test fun ambiguousAvoidsFutureDates() {
        // M/J : 2026-12-01 (futur) ; J/M : 2026-01-12.
        assertEquals(listOf(NfDate(2026, 1, 12)), NetflixCsv.parseDates(listOf("12/1/26"), today))
    }

    @Test fun fullyAmbiguousKeepsUsDefault() {
        assertEquals(listOf(NfDate(2026, 4, 4)), NetflixCsv.parseDates(listOf("4/4/26"), today))
        assertEquals(
            listOf(NfDate(2026, 11, 7), NfDate(2026, 1, 2)),
            NetflixCsv.parseDates(listOf("11/7/26", "1/2/26"), today),
        )
    }

    @Test fun neverProducesFutureDates() {
        // Format imposé (premier nombre > 12) mais date dans le futur : ignorée.
        assertEquals(
            listOf(NfDate(2026, 10, 30), null),
            NetflixCsv.parseDates(listOf("30/10/2026", "01/12/2026"), today),
        )
        assertEquals(listOf<NfDate?>(null), NetflixCsv.parseDates(listOf("2027-01-01"), today))
        // Demain : toléré (décalage de fuseau).
        assertEquals(listOf(NfDate(2026, 11, 12)), NetflixCsv.parseDates(listOf("2026-11-12"), today))
    }
}
