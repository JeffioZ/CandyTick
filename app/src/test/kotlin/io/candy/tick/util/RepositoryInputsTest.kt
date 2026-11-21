package io.candy.tick.util

import io.candy.tick.data.model.ApkAsset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryInputsTest {
    @Test fun parsesSharedRepoLinksAndBareNames() {
        for (text in listOf(
            "owner/repo", " github.com/owner/repo/ ", "https://github.com/owner/repo/releases/tag/v1",
            "https://github.com/owner/repo?tab=readme", "https://github.com/owner/repo#install",
        )) {
            assertEquals(text, "owner" to "repo", UrlParser.parse(text))
        }
        assertNull(UrlParser.parse("https://example.com/owner/repo"))
        assertNull(UrlParser.parse("https://github.com/owner/../releases"))
    }

    @Test fun comparesEveryPaddedComponentAndPrerelease() {
        assertTrue(VersionCompare.compare("1.2.0.1", "1.2")!! > 0)
        assertTrue(VersionCompare.compare("1.2", "1.2.0.1")!! < 0)
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0.0"))
        assertTrue(VersionCompare.compare("1.0~rc1", "1.0")!! < 0)
        assertTrue(VersionCompare.compare("1.0-rc.1", "1.0-rc")!! > 0)
        assertTrue(VersionCompare.compare("1.99999999999999999999", "1.99999999999999999998")!! > 0)
        assertNull(VersionCompare.compare("latest", "1.0"))
    }

    @Test fun prefersExactDeviceArchitectureWithoutPartialAliasMatches() {
        val files = listOf("wallet-arm64.apk", "client-x86_64.apk", "client-x86.apk", "client-universal.apks")
            .map { ApkAsset(it, "") }
        assertEquals(files[2], ApkPicker.pick(files, listOf("x86")).first)
        assertEquals(files[3], ApkPicker.pick(files, listOf("armeabi-v7a")).first)
        assertEquals(files[1], ApkPicker.pick(files, listOf("x86_64", "x86")).first)
        assertNull(ApkPicker.pick(files.take(2), listOf("x86")).first)
    }

    @Test fun rendersEmojiInProseAndPreservesLiteralCodeAndLinkTargets() {
        val target = "https://example.org/:bug:/notes"
        val notes = ReleaseNotes.annotated(":bug: `:bug:` [notes]($target)", Color.Black)
        assertEquals("🐛 :bug: notes", notes.text)
        assertEquals(target, (notes.getLinkAnnotations(0, notes.length).single().item as LinkAnnotation.Url).url)
    }

    @Test fun releaseTasksAndCrossRepoReferencesRetainLabelsAndTargets() {
        val notes = ReleaseNotes.annotated("- [x] Fixed Tools/Core#74\n- [ ] Follow #7\n- `[x] #13`", Color.Black, "acme/app")
        assertEquals("☑ Fixed Tools/Core#74\n☐ Follow #7\n· [x] #13", notes.text)
        val links = notes.getLinkAnnotations(0, notes.length)
        assertEquals(listOf("Tools/Core#74", "#7"), links.map { notes.text.substring(it.start, it.end) })
        assertEquals(listOf("https://github.com/Tools/Core/issues/74", "https://github.com/acme/app/issues/7"),
            links.map { (it.item as LinkAnnotation.Url).url })
    }

    @Test fun preservesDetailsContentsWithAndWithoutBlankLines() {
        for (gap in listOf("\n", "\n\n")) {
            val md = "<details open>\n<summary>Changes</summary>${gap}- [X] Fixed a bug${gap}</details>"
            val notes = ReleaseNotes.annotated(md, Color.Black)
            assertEquals("Changes\n\n☑ Fixed a bug", notes.text)
        }
    }

    @Test fun aFormattedLinkIsOneAccessibleTargetAndOtherSchemesStayText() {
        val md = "[Read **the guide**](https://example.org) [other](file:///secret)"
        val notes = ReleaseNotes.annotated(md, Color.Black)
        val link = notes.getLinkAnnotations(0, notes.length).single()
        assertEquals("Read the guide", notes.text.substring(link.start, link.end))
        assertEquals("Read the guide other", notes.text)
    }
}
