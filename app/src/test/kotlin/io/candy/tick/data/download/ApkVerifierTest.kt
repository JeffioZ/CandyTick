package io.candy.tick.data.download

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)

    @Test
    fun `computeFileHash matches known MD5 and SHA256`() {
        val file = tempFolder.newFile("sample.bin").apply { writeText("abc") }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ApkVerifier.computeFileHash(file, HashAlgorithm.MD5))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkVerifier.computeFileHash(file, HashAlgorithm.SHA256),
        )
    }

    @Test
    fun `compareSignatures handles match mismatch case and unknown`() {
        assertEquals(SignatureVerdict.MATCH, ApkVerifier.compareSignatures(setOf(certA), setOf(certA.uppercase())))
        assertEquals(SignatureVerdict.DIFFERENT, ApkVerifier.compareSignatures(setOf(certA), setOf(certB)))
        assertEquals(SignatureVerdict.UNKNOWN, ApkVerifier.compareSignatures(emptySet(), setOf(certA)))
        assertEquals(SignatureVerdict.UNKNOWN, ApkVerifier.compareSignatures(setOf(certA), setOf("not-a-hash")))
    }

    @Test
    fun `extractApkAbis reads native library directories from zip`() {
        val apk = tempFolder.newFile("demo.apk")
        ZipOutputStream(apk.outputStream()).use { zos ->
            listOf(
                "AndroidManifest.xml",
                "lib/armeabi-v7a/libcore.so",
                "lib/arm64-v8a/libcore.so",
                "lib/arm64-v8a/libextra.so",
            ).forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        assertEquals(setOf("armeabi-v7a", "arm64-v8a"), ApkVerifier.extractApkAbis(apk))
    }

    @Test
    fun `is32BitOnlyOn64Bit detects 32bit only package on arm64 device`() {
        val arm64Device = listOf("arm64-v8a", "armeabi-v7a")
        assertTrue(ApkVerifier.is32BitOnlyOn64Bit(setOf("armeabi-v7a"), arm64Device))
        assertFalse(ApkVerifier.is32BitOnlyOn64Bit(setOf("arm64-v8a", "armeabi-v7a"), arm64Device))
        assertFalse(ApkVerifier.is32BitOnlyOn64Bit(emptySet(), arm64Device))
        assertFalse(ApkVerifier.is32BitOnlyOn64Bit(setOf("armeabi-v7a"), listOf("armeabi-v7a")))
    }

    @Test
    fun `evaluateMetadata rejects package version and signature mismatches`() {
        val base = ArchiveSnapshot(
            packageName = "com.example.app",
            versionCode = 200L,
            versionName = "2.0.0",
            certSha256s = setOf(certA),
            nativeAbis = setOf("armeabi-v7a"),
        )
        assertEquals(
            VerificationResult.Failed(VerificationFailure.PACKAGE_MISMATCH),
            ApkVerifier.evaluateMetadata(base, expectedPackage = "com.other.app"),
        )
        assertEquals(
            VerificationResult.Failed(VerificationFailure.VERSION_NOT_NEWER),
            ApkVerifier.evaluateMetadata(base, expectedPackage = "com.example.app", installedVersionCode = 200L),
        )
        assertEquals(
            VerificationResult.Failed(VerificationFailure.SIGNATURE_MISMATCH),
            ApkVerifier.evaluateMetadata(
                archive = base,
                expectedPackage = "com.example.app",
                installedVersionCode = 100L,
                installedCertSha256s = setOf(certB),
            ),
        )
        val passed = ApkVerifier.evaluateMetadata(
            archive = base,
            expectedPackage = "com.example.app",
            installedVersionCode = 100L,
            installedCertSha256s = setOf(certA),
            deviceSupportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals(
            VerificationResult.Passed(
                packageName = "com.example.app",
                versionCode = 200L,
                versionName = "2.0.0",
                signature = SignatureVerdict.MATCH,
                is32BitOnlyOn64BitDevice = true,
            ),
            passed,
        )
    }
}
