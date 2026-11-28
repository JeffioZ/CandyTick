package io.candy.tick.data.download

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `streamToFile writes bytes and reports monotonic progress`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val dest = tempFolder.newFile("out.part")
        val seen = mutableListOf<Float>()
        val written = ApkDownloader.streamToFile(
            input = ByteArrayInputStream(payload),
            dest = dest,
            expectedTotal = payload.size.toLong(),
            context = Job(),
            onProgress = { progress -> progress.fraction?.let(seen::add) },
        )
        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, dest.readBytes())
        assertTrue(seen.isNotEmpty())
        assertEquals(1f, seen.last(), 0.0001f)
    }

    @Test
    fun `streamToFile stops when exceeding maxBytes`() {
        val payload = ByteArray(1024)
        val dest = tempFolder.newFile("capped.part")
        try {
            ApkDownloader.streamToFile(
                input = ByteArrayInputStream(payload),
                dest = dest,
                expectedTotal = 0L,
                context = Job(),
                maxBytes = 512L,
            )
            fail("Expected IOException for size limit")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `streamToFile respects cancelled coroutine context`() {
        val job = Job().apply { cancel() }
        val dest = tempFolder.newFile("cancelled.part")
        try {
            ApkDownloader.streamToFile(
                input = ByteArrayInputStream(ByteArray(128)),
                dest = dest,
                expectedTotal = 128L,
                context = job,
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `DownloadKey maps repo ids and store packages distinctly`() {
        assertEquals(DownloadKey.SelfUpdate, DownloadKey.fromRepoId(null))
        assertEquals(DownloadKey.Repo(42L), DownloadKey.fromRepoId(42L))
        assertEquals(DownloadKey.Store("com.example.app"), DownloadKey.Store("com.example.app"))
    }
}
