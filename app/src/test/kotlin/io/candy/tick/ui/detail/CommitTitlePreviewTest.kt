package io.candy.tick.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitTitlePreviewTest {
    @Test fun preservesFirstLineIncludingAnExactlyFullTitle() {
        assertEquals("", commitTitlePreview("\nbody"))
        assertEquals("修复", commitTitlePreview("修复\r\n正文"))
        assertEquals("fix", commitTitlePreview("fix\rbody"))
        val full = "a".repeat(512)
        assertEquals(full, commitTitlePreview(full))
        assertEquals(full, commitTitlePreview(full + "\nbody"))
    }

    @Test fun longTitleHasABoundedPreviewAndAnExplicitEllipsis() {
        assertEquals("a".repeat(511) + "…", commitTitlePreview("a".repeat(40_000)))
    }

    @Test fun cutsOnlyBetweenCompleteGraphemes() {
        for ((width, cluster) in listOf(510 to "😀", 510 to "e\u0301", 509 to "🇨🇳", 507 to "👩🏽‍💻")) {
            val prefix = "x".repeat(width)
            assertEquals(prefix + "…", commitTitlePreview(prefix + cluster + " follow-up"))
        }
        val exact = "a".repeat(509) + "😀"
        assertEquals(exact + "…", commitTitlePreview(exact + " even longer"))
    }
}
