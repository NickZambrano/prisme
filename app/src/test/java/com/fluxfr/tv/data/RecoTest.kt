package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoTest {
    private val now = 1_800_000_000_000L
    private val day = RecoEngine.DAY_MS

    private fun movie(id: String, name: String, q: Quality = Quality.HD, added: Long = 0) =
        Movie(id, name, "Films", null, q, "u", added, null)

    private fun series(id: String, name: String, q: Quality = Quality.HD) = Series(id, name, "Séries", null, q, null, 0)

    private fun movieProgress(id: String, fraction: Double, daysAgo: Int) = Progress(
        Progress.movieKey(id), Progress.MOVIE, id, "Film $id", null, null, null,
        (fraction * 6_000_000).toLong(), 6_000_000, now - daysAgo * day, null, 0, 0,
    )

    private fun episode(seriesId: String, ep: Int, fraction: Double, daysAgo: Int) = Progress(
        Progress.episodeKey("$seriesId-$ep"), Progress.EPISODE, "$seriesId-$ep", "Série $seriesId", null, null, null,
        (fraction * 2_400_000).toLong(), 2_400_000, now - daysAgo * day, seriesId, 1, ep,
    )

    private fun hit(id: String, title: String, genres: Set<Int> = setOf(18), type: String = Tmdb.MOVIE) =
        TmdbHit(type, id, title, null, 2020, genres)

    @Test fun decayHalvesEveryThirtyDays() {
        assertEquals(1.0, RecoEngine.decay(0), 1e-9)
        assertEquals(0.5, RecoEngine.decay(30 * day), 1e-9)
        assertEquals(0.25, RecoEngine.decay(60 * day), 1e-9)
        assertEquals(1.0, RecoEngine.decay(-5 * day), 1e-9)
        assertTrue(RecoEngine.rankScore(0) > RecoEngine.rankScore(1))
        assertEquals(1_600_000_000_000L, RecoEngine.addedMs(1_600_000_000L))
    }

    @Test fun seedsWeightByCompletionAndRecency() {
        val rows = listOf(
            movieProgress("a", 1.0, 0),      // terminé aujourd'hui
            movieProgress("b", 1.0, 30),     // terminé il y a 30 jours
            movieProgress("c", 0.5, 0),      // vu à moitié
            movieProgress("d", 0.2, 0),      // à peine commencé : pas un titre de départ
            episode("s1", 1, 1.0, 2), episode("s1", 2, 0.6, 1),   // 2 épisodes : oui
            episode("s2", 1, 0.4, 1),                             // 1 épisode entamé : non
            episode("s3", 1, 1.0, 3),                             // 1 épisode terminé : oui
        )
        val seeds = RecoEngine.seeds(rows, listOf("movie:fav", "live:x"), now) { k -> "T $k" }
        val byKey = seeds.associateBy { it.key }
        assertEquals(1.0, byKey.getValue("movie:a").weight, 1e-9)
        assertEquals(0.5, byKey.getValue("movie:b").weight, 1e-9)
        assertTrue(byKey.getValue("movie:c").weight in 0.4..0.7)
        assertFalse("movie:d" in byKey)
        assertTrue("series:s1" in byKey)
        assertFalse("series:s2" in byKey)
        assertTrue("series:s3" in byKey)
        val fav = byKey.getValue("movie:fav")
        assertTrue(fav.favorite)
        assertEquals(RecoEngine.FAVORITE_WEIGHT, fav.weight, 1e-9)
        assertFalse(seeds.any { it.key.startsWith("live:") })
        assertEquals("movie:a", seeds.first().key)
        assertEquals(Tmdb.TV, byKey.getValue("series:s1").type)
    }

    @Test fun coldStartGivesNothing() {
        assertTrue(RecoEngine.seeds(emptyList(), emptyList(), now) { it }.isEmpty())
        assertTrue(RecoEngine.score(emptyList(), Taste.EMPTY, { null }, emptySet(), { null }, now).isEmpty())
        assertTrue(RecoEngine.because(emptyList(), emptyList(), { null }, emptySet()).isEmpty())
        assertTrue(RecoEngine.genreRows(emptyList(), { listOf(Genres.COMEDIE) }, emptyList(), emptySet(), emptyMap(), now).isEmpty())
    }

    @Test fun excludesWatchedAndInProgress() {
        val rows = listOf(
            movieProgress("done", 1.0, 1),
            movieProgress("late", 0.8, 1),
            movieProgress("mid", 0.4, 1),
            Progress(Progress.movieKey("tiny"), Progress.MOVIE, "tiny", "x", null, null, null, 5_000, 6_000_000, now, null, 0, 0),
            episode("s1", 1, 0.1, 1),
        )
        val ex = RecoEngine.excluded(rows)
        assertTrue("movie:done" in ex)
        assertTrue("movie:late" in ex)
        assertTrue("movie:mid" in ex)      // dans « Continuer à regarder »
        assertFalse("movie:tiny" in ex)    // 5 s : pas vraiment commencé
        assertTrue("series:s1" in ex)

        val items = mapOf(
            "1" to RecoItem.M(movie("done", "Done")),
            "2" to RecoItem.M(movie("new", "New")),
            "3" to RecoItem.S(series("s1", "S1")),
        )
        val seed = RecoSeed("movie:seed", "Seed", Tmdb.MOVIE, 1.0, now)
        val data = listOf(SeedData(seed, listOf(hit("1", "Done"), hit("2", "New"), hit("3", "S1")), emptyList(), null))
        val scored = RecoEngine.score(data, Taste.EMPTY, { items[it.id] }, ex, { null }, now)
        assertEquals(listOf("movie:new"), scored.map { it.item.key })
    }

    @Test fun scoringRewardsConsensusRankAndTaste() {
        val items = (1..6).associate { "$it" to RecoItem.M(movie("m$it", "Film $it")) }
        val a = RecoSeed("movie:a", "A", Tmdb.MOVIE, 1.0, now)
        val b = RecoSeed("movie:b", "B", Tmdb.MOVIE, 0.5, now)
        val data = listOf(
            SeedData(a, listOf(hit("1", "Un"), hit("2", "Deux"), hit("3", "Trois", setOf(27))), emptyList(),
                TmdbInfo(setOf(18), setOf("c1"), setOf(5))),
            SeedData(b, listOf(hit("2", "Deux")), listOf(hit("4", "Quatre")), TmdbInfo(setOf(18), emptySet(), emptySet())),
        )
        val taste = RecoEngine.taste(data)
        assertEquals(1.0, taste.genres.getValue(18), 1e-9)
        val scored = RecoEngine.score(data, taste, { items[it.id] }, emptySet(), { null }, now)
        val s = scored.associate { it.item.key to it.score }
        // « Deux » est recommandé par les deux titres de départ : il passe devant « Un ».
        assertEquals("movie:m2", scored.first().item.key)
        // Rang et genre : « Trois » (horreur, rang 3) derrière « Un ».
        assertTrue(s.getValue("movie:m1") > s.getValue("movie:m3"))
        // Le titre de départ le plus contributeur est retenu.
        assertEquals("movie:a", scored.first { it.item.key == "movie:m2" }.seed)
        // Acteurs / mots-clés en commun : proximité plus forte.
        val withPeople = RecoEngine.tasteSim(taste, setOf(18), TmdbInfo(setOf(18), setOf("c1"), setOf(5)))
        val without = RecoEngine.tasteSim(taste, setOf(18), TmdbInfo(setOf(18), emptySet(), emptySet()))
        assertTrue(withPeople > without)
    }

    @Test fun diversityBreaksUpFranchises() {
        fun sc(id: String, title: String, score: Double, genres: Set<Int>, group: String = "t:" + RecoEngine.franchiseKey(title)) =
            RecoScored(RecoItem.M(movie(id, title)), score, genres, group, null, "movie:$id")
        assertEquals(RecoEngine.franchiseKey("Fast & Furious 7"), RecoEngine.franchiseKey("Fast & Furious 8"))
        assertEquals(RecoEngine.franchiseKey("Harry Potter et la chambre des secrets"), RecoEngine.franchiseKey("Harry Potter et le prince de sang-mêlé"))
        val list = listOf(
            sc("1", "Fast & Furious 7", 1.0, setOf(28)),
            sc("2", "Fast & Furious 8", 0.98, setOf(28)),
            sc("3", "Fast & Furious 9", 0.96, setOf(28)),
            sc("4", "Amélie", 0.7, setOf(35)),
            sc("5", "Le Prénom", 0.65, setOf(35, 18)),
        )
        val out = RecoEngine.diversify(list, 3)
        assertEquals("movie:1", out[0].item.key)
        // La deuxième place ne revient pas au même film de la saga.
        assertEquals("movie:4", out[1].item.key)
        assertEquals(3, out.size)
        assertTrue(RecoEngine.diversify(list, 10).size == 5)
        assertTrue(RecoEngine.diversify(emptyList(), 10).isEmpty())
    }

    @Test fun becauseRowsNeedEnoughTitlesAndSkipFavorites() {
        val items = (1..20).associate { "$it" to RecoItem.M(movie("m$it", "Film $it")) }
        val strong = RecoSeed("movie:a", "Inception", Tmdb.MOVIE, 1.0, now)
        val weak = RecoSeed("movie:b", "Petit", Tmdb.MOVIE, 0.4, now)
        val fav = RecoSeed("movie:c", "Liste", Tmdb.MOVIE, 0.9, 0, favorite = true)
        val data = listOf(
            SeedData(weak, (1..3).map { hit("$it", "F$it") }, emptyList(), null),
            SeedData(strong, (1..8).map { hit("$it", "F$it") }, emptyList(), null),
            SeedData(fav, (9..20).map { hit("$it", "F$it") }, emptyList(), null),
        )
        val rows = RecoEngine.because(data, emptyList(), { items[it.id] }, setOf("movie:m1"))
        assertEquals(1, rows.size)
        assertEquals("Inception", rows[0].first)
        assertEquals(7, rows[0].second.size)
        assertFalse(rows[0].second.any { it.key == "movie:m1" })
    }

    @Test fun genreRowsFromFavoriteGenre() {
        val seeds = listOf(RecoSeed("movie:a", "A", Tmdb.MOVIE, 1.0, now))
        val genres = HashMap<String, List<String>>()
        genres["movie:a"] = listOf(Genres.COMEDIE)
        val universe = (1..12).map { RecoItem.M(movie("c$it", "Comédie " + ('A' + it), added = 1_700_000_000L + it)) } +
            RecoItem.M(movie("x", "Drame"))
        universe.forEach { genres.putIfAbsent(it.key, if (it.name.startsWith("Comédie")) listOf(Genres.COMEDIE) else listOf(Genres.DRAME)) }
        val rows = RecoEngine.genreRows(seeds, { genres[it].orEmpty() }, universe, setOf("movie:c1"), mapOf("movie:c5" to 3.0), now)
        assertEquals(1, rows.size)
        assertEquals("Comédies pour toi", rows[0].first)
        assertEquals("movie:c5", rows[0].second.first().key)
        assertFalse(rows[0].second.any { it.key == "movie:c1" || it.key == "movie:x" })
    }

    @Test fun parsesTmdbResponses() {
        val list = """{"page":1,"results":[{"id":27205,"title":"Inception","original_title":"Inception","release_date":"2010-07-15",
            "genre_ids":[28,878],"popularity":80.5,"vote_average":8.4,"vote_count":35000},{"id":"x"}]}"""
        val hits = TmdbParse.hits(list, Tmdb.MOVIE)
        assertEquals(1, hits.size)
        assertEquals("27205", hits[0].id)
        assertEquals(2010, hits[0].year)
        assertNull(hits[0].original)
        val tv = TmdbParse.hits("""{"results":[{"id":1,"name":"Lupin","original_name":"Lupin","first_air_date":"2021-01-08","genre_ids":[10759]}]}""", Tmdb.TV)
        assertEquals(setOf(28, 12), tv[0].genres)
        val info = TmdbParse.info(
            """{"id":27205,"genres":[{"id":28,"name":"Action"}],"belongs_to_collection":null,
            "credits":{"cast":[{"id":6193,"order":0},{"id":24045,"order":1}],"crew":[{"id":525,"job":"Director"},{"id":1,"job":"Writer"}]},
            "keywords":{"keywords":[{"id":1566,"name":"dream"}]}}""",
            Tmdb.MOVIE,
        )!!
        assertEquals(setOf("c6193", "c24045", "d525"), info.people)
        assertEquals(setOf(1566), info.keywords)
        assertNull(info.collection)
        assertNull(TmdbParse.info("{}", Tmdb.MOVIE))
        assertEquals("27205", TmdbParse.searchBest(list, Tmdb.MOVIE, 2011))
        assertNull(TmdbParse.searchBest(list, Tmdb.MOVIE, 1990))
    }
}
