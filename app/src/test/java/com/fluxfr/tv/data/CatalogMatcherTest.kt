package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogMatcherTest {
    private fun movie(id: String, name: String, q: Quality = Quality.HD) = Movie(id, name, "Films", null, q, "u", 0, null)
    private fun series(id: String, name: String, q: Quality = Quality.HD) = Series(id, name, "Séries", null, q, null, 0)
    private fun hit(type: String, id: String, title: String, year: Int?, original: String? = null) =
        TmdbHit(type, id, title, original, year, emptySet())

    @Test fun matchesByTmdbIdThenTitleAndYear() {
        val movies = listOf(
            movie("1", "FR - Le Chevalier noir (2008)"),
            movie("2", "Dune (2021)", Quality.FHD),
            movie("3", "Dune - 2021", Quality.UHD),
            movie("4", "Dune - 1984"),
            movie("5", "Intouchables HDCAM", Quality.LOW),
        )
        val meta = mapOf(VodMeta.movieKey("1") to Meta(tmdbId = "155", year = 2008), VodMeta.movieKey("2") to Meta(year = 2021))
        val m = CatalogMatcher(movies, listOf(series("9", "Lupin")), { meta[it] })
        // Par identifiant TMDB, même si le titre TMDB est différent.
        assertEquals("1", (m.find(hit(Tmdb.MOVIE, "155", "The Dark Knight", 2008)) as RecoItem.M).movie.id)
        // Par titre + année (meilleure qualité).
        assertEquals("3", (m.find(hit(Tmdb.MOVIE, "438631", "Dune", 2021)) as RecoItem.M).movie.id)
        assertEquals("4", (m.find(hit(Tmdb.MOVIE, "841", "Dune", 1984)) as RecoItem.M).movie.id)
        assertNull(m.find(hit(Tmdb.MOVIE, "1", "Dune", 2000)))
        // Titre original quand le titre français ne correspond pas ; jamais une copie basse qualité.
        assertEquals("9", (m.find(hit(Tmdb.TV, "96677", "Lupin : Dans l'ombre", 2021, original = "Lupin")) as RecoItem.S).series.id)
        assertNull(m.find(hit(Tmdb.MOVIE, "77338", "Intouchables", 2011)))
        // Un titre TMDB « film » ne retrouve pas une série.
        assertNull(m.find(hit(Tmdb.MOVIE, "5", "Lupin", 2021)))
    }

    @Test fun picksBestQualityForSameTmdbIdAndNeverLow() {
        val movies = listOf(
            movie("10", "Oppenheimer HDCAM", Quality.LOW),
            movie("11", "Oppenheimer (2023)", Quality.HD),
            movie("12", "Oppenheimer - 2023", Quality.UHD),
            movie("13", "Barbie", Quality.LOW),
            movie("14", "Intouchables", Quality.FHD),
        )
        val meta = mapOf(VodMeta.movieKey("11") to Meta(tmdbId = "872585", year = 2023))
        val m = CatalogMatcher(movies, emptyList(), { meta[it] })
        // Par id TMDB (version HD) : la version 4K du même titre est préférée.
        assertEquals("12", (m.find(hit(Tmdb.MOVIE, "872585", "Oppenheimer", 2023)) as RecoItem.M).movie.id)
        assertNull(m.find(hit(Tmdb.MOVIE, "346698", "Barbie", 2023)))
        // Titre anglais introuvable : titre original.
        assertNull(m.find(hit(Tmdb.MOVIE, "77338", "The Intouchables", 2011)))
        assertEquals("14", (m.find(hit(Tmdb.MOVIE, "77339", "The Intouchables", 2011, original = "Intouchables")) as RecoItem.M).movie.id)
    }

    @Test fun listsKeepTmdbOrderWithoutDuplicates() {
        val movies = listOf(movie("1", "Dune (2021)"), movie("2", "Barbie (2023)"), movie("3", "Wish (2023)"))
        val m = CatalogMatcher(movies, listOf(series("9", "Lupin")), { null })
        val hits = listOf(
            hit(Tmdb.MOVIE, "a", "Barbie", 2023), hit(Tmdb.MOVIE, "b", "Inconnu", 2023),
            hit(Tmdb.MOVIE, "c", "Dune", 2021), hit(Tmdb.MOVIE, "d", "Dune", 2022), hit(Tmdb.MOVIE, "e", "Wish", 2023),
        )
        assertEquals(listOf("2", "1", "3"), m.movies(hits, 30).map { it.id })
        assertEquals(listOf("2", "1"), m.movies(hits, 2).map { it.id })
        assertEquals(listOf("9"), m.series(listOf(hit(Tmdb.TV, "x", "Lupin", null)), 30).map { it.id })
    }

    @Test fun cleansCatalogTitles() {
        assertEquals(2021, CatalogTitles.yearIn("FR - Dune (2021)"))
        assertNull(CatalogTitles.yearIn("Lupin"))
    }

    @Test fun miniJsonRoundTrip() {
        val v = MiniJson.parse("""{"a":[1,2.5,"x\"yé"],"b":{"c":true,"d":null},"e":-3}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, "x\"yé"), v["a"])
        assertEquals(true, (v["b"] as Map<*, *>)["c"])
        assertEquals(-3.0, v["e"])
        assertEquals("""{"movie":{"title":"Dune","year":2021},"progress":12.5}""",
            MiniJson.write(mapOf("movie" to mapOf("title" to "Dune", "year" to 2021), "progress" to 12.5)))
    }

    @Test fun profilesRoundTrip() {
        val list = listOf(Profile("main", "Principal", 0, false), Profile("kids", "Enfants \"petits\"", 1, true, PinHash.hash("1234")))
        assertEquals(list, Profiles.decode(Profiles.encode(list)))
        assertNull(Profiles.decode("pas du json"))
    }

    @Test fun estimatedProgressResumesAtSamePercentage() {
        val p = Progress("movie:1", Progress.MOVIE, "1", "D", null, null, null, 60 * 60_000L, 120 * 60_000L, 0, null, 0, 0, estimated = true)
        assertEquals(50 * 60_000L, p.resumeAt(100 * 60_000L))
        assertEquals(60 * 60_000L, p.resumeAt(0))
        assertEquals(60 * 60_000L, p.copy(estimated = false).resumeAt(100 * 60_000L))
        assertEquals(0L, p.copy(position = 119 * 60_000L).resumeAt(100 * 60_000L))
    }
}
