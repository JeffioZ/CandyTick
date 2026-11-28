package io.candy.tick.data.store

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import io.candy.tick.util.appDisplayName
import java.security.MessageDigest

/**
 * 本机已装应用扫描器：
 * - 关闭「检查系统应用」时，仅扫描带桌面图标的非系统应用，列表干净、开销极低；
 * - 开启「检查系统应用」时，扫描全量已安装包（含无桌面图标的后台系统组件，如智能服务等），
 *   自动剔除 APEX 与 RRO 资源叠加包，并将桌面应用排在前面、后台组件排在后面。
 */
object InstalledAppScanner {

    fun scan(
        context: Context,
        includeSystemApps: Boolean,
    ): List<LocalInstalledApp> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
        val launcherLabels = LinkedHashMap<String, String>(activities.size)
        for (ri in activities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == selfPkg || pkg in launcherLabels) continue
            val label = appDisplayName(pkg, runCatching { ri.loadLabel(pm) }.getOrNull()) ?: ""
            launcherLabels[pkg] = label
        }

        if (!includeSystemApps) {
            val result = ArrayList<LocalInstalledApp>(launcherLabels.size)
            for ((pkg, activityLabel) in launcherLabels) {
                @Suppress("DEPRECATION")
                val pkgInfo = runCatching {
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                }.getOrNull() ?: continue
                val appInfo = pkgInfo.applicationInfo
                val isSystem = isSystemPackage(appInfo)
                if (isSystem) continue
                val versionCode = pkgInfo.longVersionCode
                if (versionCode <= 0L) continue
                val label = activityLabel.ifBlank {
                    appDisplayName(pkg, runCatching { appInfo?.loadLabel(pm) }.getOrNull()) ?: pkg
                }
                val sigs = extractPrimarySignatureHashes(pkgInfo.signingInfo)
                result += LocalInstalledApp(
                    packageName = pkg,
                    displayName = label,
                    versionCode = versionCode,
                    versionName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: versionCode.toString(),
                    isSystemApp = false,
                    targetSdkVersion = appInfo?.targetSdkVersion?.takeIf { it > 0 } ?: 35,
                    signatureMd5 = sigs.md5,
                    signatureSha256 = sigs.sha256,
                    oppoLegacySignatureMd5 = sigs.oppoLegacyMd5,
                    splits = extractSplits(pkgInfo),
                    installedByXiaomiMarket = isInstalledByXiaomiMarket(pm, pkg),
                )
            }
            result.sortBy { it.displayName.lowercase() }
            return result
        }

        val queryFlags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
            PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val installedPackages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(queryFlags.toLong()))
            } else {
                pm.getInstalledPackages(queryFlags)
            }
        }.getOrDefault(emptyList())
        val launcherList = ArrayList<LocalInstalledApp>(launcherLabels.size)
        val headlessList = ArrayList<LocalInstalledApp>(installedPackages.size)
        val seen = HashSet<String>(installedPackages.size)
        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName
            if (pkg == selfPkg || !seen.add(pkg)) continue
            if (pkgInfo.isApex) continue
            val appInfo = pkgInfo.applicationInfo ?: continue
            if ((appInfo.flags and FLAG_INSTALLED_FOR_USER) == 0) continue
            if (isOverlayPackage(pkg, appInfo.sourceDir)) continue
            val versionCode = pkgInfo.longVersionCode
            if (versionCode <= 0L) continue
            val isSystem = isSystemPackage(appInfo)
            val activityLabel = launcherLabels[pkg]
            val hasLauncher = activityLabel != null
            val label = activityLabel?.takeIf { it.isNotBlank() }
                ?: appDisplayName(pkg, runCatching { appInfo.loadLabel(pm) }.getOrNull())
                ?: pkg
            val sigs = extractPrimarySignatureHashes(pkgInfo.signingInfo)
            val item = LocalInstalledApp(
                packageName = pkg,
                displayName = label,
                versionCode = versionCode,
                versionName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: versionCode.toString(),
                isSystemApp = isSystem,
                targetSdkVersion = appInfo.targetSdkVersion.takeIf { it > 0 } ?: 35,
                signatureMd5 = sigs.md5,
                signatureSha256 = sigs.sha256,
                oppoLegacySignatureMd5 = sigs.oppoLegacyMd5,
                splits = extractSplits(pkgInfo),
                installedByXiaomiMarket = isInstalledByXiaomiMarket(pm, pkg),
            )
            if (hasLauncher) launcherList += item else headlessList += item
        }
        launcherList.sortBy { it.displayName.lowercase() }
        headlessList.sortBy { it.displayName.lowercase() }
        return launcherList + headlessList
    }

    private data class SignatureHashes(
        val md5: String = "",
        val sha256: String = "",
        val oppoLegacyMd5: String = "",
    )

    private fun extractPrimarySignatureHashes(signingInfo: SigningInfo?): SignatureHashes {
        if (signingInfo == null) return SignatureHashes()
        val bytes = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            // signingCertificateHistory 按旧→新排列，取末项为当前生效签名证书
            signingInfo.signingCertificateHistory?.lastOrNull()?.toByteArray()
        } ?: return SignatureHashes()
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // OPPO UpgradeReqV2 field 7：先把证书 DER 字节转为大写十六进制串，再对字符串做 MD5 并输出大写 HEX
        val derUpperHex = bytes.joinToString("") { "%02X".format(it) }
        val oppoLegacyMd5 = MessageDigest.getInstance("MD5")
            .digest(derUpperHex.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        return SignatureHashes(md5 = md5, sha256 = sha256, oppoLegacyMd5 = oppoLegacyMd5)
    }

    private fun extractSplits(pkgInfo: PackageInfo): String {
        val names = pkgInfo.splitNames.orEmpty().filter { it.isNotBlank() }.sorted()
        return if (names.isEmpty()) "0" else names.joinToString(separator = "#", prefix = "1:[", postfix = "]")
    }

    @Suppress("DEPRECATION")
    private fun isInstalledByXiaomiMarket(pm: PackageManager, packageName: String): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(packageName)
        }
        installer == "com.xiaomi.market"
    }.getOrDefault(false)

    private fun isSystemPackage(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        val flags = appInfo.flags
        if ((flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return true
        val dir = appInfo.sourceDir.orEmpty()
        return SYSTEM_PATH_PREFIXES.any { dir.startsWith(it) }
    }

    private fun isOverlayPackage(packageName: String, sourceDir: String?): Boolean {
        val lower = packageName.lowercase()
        if (lower.contains(".overlay") || lower.contains("auto_generated_rro")) return true
        val dir = sourceDir?.lowercase().orEmpty()
        return dir.contains("/overlay/") || dir.contains("/product/overlay")
    }

    // ApplicationInfo.FLAG_INSTALLED（@hide）：配合 MATCH_UNINSTALLED_PACKAGES 过滤已卸载残留，保留当前用户已装（含停用待用）组件
    private const val FLAG_INSTALLED_FOR_USER = 1 shl 23
    private val SYSTEM_PATH_PREFIXES = listOf("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/oem/")
}
