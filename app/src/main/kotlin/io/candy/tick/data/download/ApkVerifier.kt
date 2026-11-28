package io.candy.tick.data.download

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class HashAlgorithm(val jcaName: String) {
    MD5("MD5"),
    SHA256("SHA-256"),
}

data class ExpectedHash(
    val algorithm: HashAlgorithm,
    val hex: String,
)

enum class SignatureVerdict {
    MATCH,
    DIFFERENT,
    UNKNOWN,
}

enum class VerificationFailure {
    FILE_MISSING,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    INVALID_ARCHIVE,
    PACKAGE_MISMATCH,
    VERSION_NOT_NEWER,
    SIGNATURE_MISMATCH,
}

sealed interface VerificationResult {
    data class Passed(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val signature: SignatureVerdict,
        val is32BitOnlyOn64BitDevice: Boolean,
    ) : VerificationResult

    data class Failed(val reason: VerificationFailure) : VerificationResult
}

data class ArchiveSnapshot(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val certSha256s: Set<String>,
    val nativeAbis: Set<String>,
)

/**
 * 安装包落盘校验器：核对文件完整性（大小 / 哈希）、包名、版本号、签名一致性以及 64/32 位架构兼容性。
 * 核心判定函数保持纯 Kotlin，方便 JVM 单元测试覆盖。
 */
object ApkVerifier {

    private val sha256Hex = Regex("^[a-f0-9]{64}$")
    private val arm64Abis = setOf("arm64-v8a")
    private val abi32Bit = setOf("armeabi-v7a", "armeabi", "x86")

    fun verifyFileIntegrity(
        file: File,
        expectedSize: Long = 0L,
        expectedHash: ExpectedHash? = null,
    ): VerificationFailure? {
        if (!file.isFile) return VerificationFailure.FILE_MISSING
        if (expectedSize > 0 && file.length() != expectedSize) {
            return VerificationFailure.SIZE_MISMATCH
        }
        if (expectedHash != null && expectedHash.hex.isNotBlank()) {
            val actual = runCatching { computeFileHash(file, expectedHash.algorithm) }.getOrNull()
            if (actual == null || !actual.equals(expectedHash.hex.trim(), ignoreCase = true)) {
                return VerificationFailure.HASH_MISMATCH
            }
        }
        return null
    }

    fun verifyApk(
        context: Context,
        file: File,
        expectedPackage: String? = null,
        installedVersionCode: Long? = null,
        expectedSize: Long = 0L,
        expectedHash: ExpectedHash? = null,
        requireSignatureMatch: Boolean = true,
    ): VerificationResult {
        verifyFileIntegrity(file, expectedSize, expectedHash)?.let {
            return VerificationResult.Failed(it)
        }
        val pm = context.packageManager
        val archiveInfo = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return VerificationResult.Failed(VerificationFailure.INVALID_ARCHIVE)

        val pkg = archiveInfo.packageName.takeIf { it.isNotBlank() }
            ?: return VerificationResult.Failed(VerificationFailure.INVALID_ARCHIVE)
        val archiveCerts = extractCertSha256s(archiveInfo.signingInfo)
        val installedCerts = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        }.getOrNull()?.let(::extractCertSha256s).orEmpty()

        val snapshot = ArchiveSnapshot(
            packageName = pkg,
            versionCode = archiveInfo.longVersionCode,
            versionName = archiveInfo.versionName,
            certSha256s = archiveCerts,
            nativeAbis = extractApkAbis(file),
        )
        return evaluateMetadata(
            archive = snapshot,
            expectedPackage = expectedPackage,
            installedVersionCode = installedVersionCode,
            installedCertSha256s = installedCerts,
            deviceSupportedAbis = Build.SUPPORTED_ABIS.toList(),
            requireSignatureMatch = requireSignatureMatch,
        )
    }

    internal fun evaluateMetadata(
        archive: ArchiveSnapshot,
        expectedPackage: String? = null,
        installedVersionCode: Long? = null,
        installedCertSha256s: Set<String> = emptySet(),
        deviceSupportedAbis: List<String> = emptyList(),
        requireSignatureMatch: Boolean = true,
    ): VerificationResult {
        if (expectedPackage != null && archive.packageName != expectedPackage) {
            return VerificationResult.Failed(VerificationFailure.PACKAGE_MISMATCH)
        }
        if (installedVersionCode != null && archive.versionCode <= installedVersionCode) {
            return VerificationResult.Failed(VerificationFailure.VERSION_NOT_NEWER)
        }
        val sig = compareSignatures(installedCertSha256s, archive.certSha256s)
        if (requireSignatureMatch && sig == SignatureVerdict.DIFFERENT) {
            return VerificationResult.Failed(VerificationFailure.SIGNATURE_MISMATCH)
        }
        return VerificationResult.Passed(
            packageName = archive.packageName,
            versionCode = archive.versionCode,
            versionName = archive.versionName,
            signature = sig,
            is32BitOnlyOn64BitDevice = is32BitOnlyOn64Bit(archive.nativeAbis, deviceSupportedAbis),
        )
    }

    fun compareSignatures(installed: Set<String>, candidate: Set<String>): SignatureVerdict {
        val local = installed.map { it.trim().lowercase() }.filter(sha256Hex::matches).toSet()
        val remote = candidate.map { it.trim().lowercase() }.filter(sha256Hex::matches).toSet()
        if (local.isEmpty() || remote.isEmpty()) return SignatureVerdict.UNKNOWN
        return if (remote.any { it in local }) SignatureVerdict.MATCH else SignatureVerdict.DIFFERENT
    }

    fun is32BitOnlyOn64Bit(apkAbis: Set<String>, deviceSupportedAbis: List<String>): Boolean {
        if (apkAbis.isEmpty()) return false
        val deviceHas64 = deviceSupportedAbis.any { it in arm64Abis || it == "x86_64" }
        if (!deviceHas64) return false
        val apkHas64 = apkAbis.any { it in arm64Abis || it == "x86_64" }
        return !apkHas64 && apkAbis.any { it in abi32Bit }
    }

    fun extractApkAbis(apkFile: File): Set<String> = runCatching {
        ZipFile(apkFile).use { zip ->
            val abis = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    val segments = name.split('/')
                    if (segments.size >= 3 && segments[1].isNotBlank()) {
                        abis += segments[1]
                    }
                }
            }
            abis
        }
    }.getOrDefault(emptySet())

    fun computeFileHash(file: File, algorithm: HashAlgorithm): String {
        val digest = MessageDigest.getInstance(algorithm.jcaName)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHexLower()
    }

    private fun extractCertSha256s(signingInfo: SigningInfo?): Set<String> {
        if (signingInfo == null) return emptySet()
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signers.map { sig -> digest.digest(sig.toByteArray()).toHexLower() }.toSet()
    }

    private fun ByteArray.toHexLower(): String = buildString(size * 2) {
        for (b in this@toHexLower) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }

    private const val HEX_CHARS = "0123456789abcdef"
}
