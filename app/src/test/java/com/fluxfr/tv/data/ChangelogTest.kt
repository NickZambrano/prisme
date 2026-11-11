package com.fluxfr.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {
    private val text = """
        # commentaire
        ## 3.6
        - C
        - D
        ## 3.5.1
        - B
        ## 3.5
        - A
    """.trimIndent()

    @Test fun parses() {
        val e = Changelog.parse(text)
        assertEquals(listOf("3.6", "3.5.1", "3.5"), e.map { it.version })
        assertEquals(listOf("C", "D"), e[0].items)
    }

    @Test fun showsOnlyNewerVersions() {
        val e = Changelog.parse(text)
        assertEquals(listOf("3.6", "3.5.1"), Changelog.since(e, "3.5").map { it.version })
        assertEquals(emptyList<String>(), Changelog.since(e, "3.6").map { it.version })
        // Version vue inconnue (ancienne installation sans journal) : seulement la plus récente.
        assertEquals(listOf("3.6"), Changelog.since(e, "").map { it.version })
    }

    /** Le journal livré dans l'appli commence par la version en cours de compilation. */
    @Test fun bundledChangelogStartsWithCurrentVersion() {
        val bundled = Changelog.parse(File("src/main/assets/changelog.txt").readText())
        assertTrue(bundled.isNotEmpty())
        assertEquals(com.fluxfr.tv.BuildConfig.VERSION_NAME, bundled.first().version)
    }
}
