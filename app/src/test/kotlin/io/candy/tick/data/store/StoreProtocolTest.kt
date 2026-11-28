package io.candy.tick.data.store

import io.candy.tick.data.download.SignatureVerdict
import io.candy.tick.data.model.StoreReleaseInfo
import io.candy.tick.data.model.StoreSignatureRecord
import io.candy.tick.data.store.appchina.AppchinaProtocol
import io.candy.tick.data.store.coolapk.CoolapkProtocol
import io.candy.tick.data.store.fdroid.FdroidProtocol
import io.candy.tick.data.store.honor.HonorProtocol
import io.candy.tick.data.store.huawei.HuaweiProtocol
import io.candy.tick.data.store.oppo.OppoProtocol
import io.candy.tick.data.store.samsung.SamsungProtocol
import io.candy.tick.data.store.taptap.TapTapProtocol
import io.candy.tick.data.store.tencent.TencentProtocol
import io.candy.tick.data.store.vivo.VivoProtocol
import io.candy.tick.data.store.wandoujia.WandoujiaProtocol
import io.candy.tick.data.store.xiaomi.XiaomiProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreProtocolTest {

    @Test
    fun `selectBestCandidate prefers highest version then compatible signature then priority`() {
        val c1 = StoreReleaseInfo(StoreCatalog.ID_TENCENT, 200, "2.0.0")
        val c2 = StoreReleaseInfo(StoreCatalog.ID_XIAOMI, 200, "2.0.0")
        val c3 = StoreReleaseInfo(StoreCatalog.ID_WANDOUJIA, 210, "2.1.0")
        val c4 = StoreReleaseInfo(StoreCatalog.ID_OPPO, 205, "2.0.5")
        // 排除 DIFFERENT 后，先比 versionCode（205 > 200）
        val newest = StoreCatalog.selectBestCandidate(
            candidates = listOf(c1, c2, c3, c4),
            enabledOrder = listOf(StoreCatalog.ID_XIAOMI, StoreCatalog.ID_TENCENT, StoreCatalog.ID_OPPO, StoreCatalog.ID_WANDOUJIA),
            verdictOf = { id ->
                when (id) {
                    StoreCatalog.ID_WANDOUJIA -> SignatureVerdict.DIFFERENT
                    StoreCatalog.ID_TENCENT -> SignatureVerdict.MATCH
                    else -> SignatureVerdict.UNKNOWN
                }
            },
        )
        assertEquals(StoreCatalog.ID_OPPO, newest?.storeId)

        // 同版本号时，优先签名已知一致 (MATCH)，再按优先级
        val sameVer = StoreCatalog.selectBestCandidate(
            candidates = listOf(c1, c2),
            enabledOrder = listOf(StoreCatalog.ID_XIAOMI, StoreCatalog.ID_TENCENT),
            verdictOf = { id -> if (id == StoreCatalog.ID_TENCENT) SignatureVerdict.MATCH else SignatureVerdict.UNKNOWN },
        )
        assertEquals(StoreCatalog.ID_TENCENT, sameVer?.storeId)
    }

    @Test
    fun `sortByDefaultPriority puts device OEM store first then catalog order`() {
        val sorted = StoreCatalog.sortByDefaultPriority(
            storeIds = listOf(StoreCatalog.ID_FDROID, StoreCatalog.ID_WANDOUJIA, StoreCatalog.ID_OPPO, StoreCatalog.ID_XIAOMI),
            deviceOemStoreId = StoreCatalog.ID_OPPO,
        )
        assertEquals(
            listOf(StoreCatalog.ID_OPPO, StoreCatalog.ID_XIAOMI, StoreCatalog.ID_WANDOUJIA, StoreCatalog.ID_FDROID),
            sorted,
        )
    }

    @Test
    fun `TencentProtocol parses valid detail and upgrades http url`() {
        val json = """
            {
              "ret": 0,
              "app_detail_records": {
                "com.demo.app": {
                  "app_info": { "package_name": "com.demo.app", "online_status": 1 },
                  "apk_all_data": {
                    "app_id": 12345,
                    "package_name": "com.demo.app",
                    "version_code": 500,
                    "version_name": "5.0.0",
                    "size_byte": 10485760,
                    "url": "http://cdn.example.com/demo.apk",
                    "apk_md5": "900150983cd24fb0d6963f7d28e17f72",
                    "feature": "Bug fixes"
                  }
                }
              }
            }
        """.trimIndent()
        val parsed = TencentProtocol.parseDetail(json, "com.demo.app")
        assertNotNull(parsed)
        assertEquals(500L, parsed!!.versionCode)
        assertEquals("https://cdn.example.com/demo.apk", parsed.downloadUrl)
        assertEquals("Bug fixes", parsed.changelog)
    }

    @Test
    fun `WandoujiaProtocol M90 roundtrip preserves bytes`() {
        val key = "d861a5214f1849a1".toByteArray()
        val plain = """{"hello":"world","n":12345}""".toByteArray()
        val enc = WandoujiaProtocol.encodeM90(plain, key, seed = 42)
        val dec = WandoujiaProtocol.decodeM90(enc, key)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun `XiaomiProtocol parses update list and download url`() {
        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val updateJson = """
            {
              "listApp": [
                { "appId": 999, "packageName": "com.demo.app", "versionCode": 20, "versionName": "2.0", "apkSize": 2048, "changeLog": "New UI" }
              ]
            }
        """.trimIndent()
        val updates = XiaomiProtocol.parseUpdateResponse(updateJson, installed)
        assertEquals(20L, updates["com.demo.app"]?.versionCode)

        val dlJson = """
            {
              "hosts": ["http://dl.example.com/download/"],
              "apks": [ { "type": "base", "apk": "app/demo.apk", "size": 2048 } ]
            }
        """.trimIndent()
        val dl = XiaomiProtocol.parseDownloadUrl(dlJson)
        assertEquals("https://dl.example.com/download/app/demo.apk", dl?.first)
        assertEquals(2048L, dl?.second)
    }

    @Test
    fun `FdroidProtocol streams index-v2 packages and selects newest version`() {
        val index = """
            {
              "repo": { "timestamp": 1700000000 },
              "packages": {
                "org.other.app": { "versions": {} },
                "org.demo.app": {
                  "versions": {
                    "old": {
                      "file": { "name": "/old.apk", "sha256": "${"1".repeat(64)}", "size": 100 },
                      "manifest": { "versionCode": 5, "versionName": "0.5" }
                    },
                    "new": {
                      "file": { "name": "/new.apk", "sha256": "${"2".repeat(64)}", "size": 200 },
                      "manifest": { "versionCode": 12, "versionName": "1.2" },
                      "whatsNew": { "zh-CN": "性能优化" }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val installed = mapOf("org.demo.app" to LocalInstalledApp("org.demo.app", "Demo", 8, "0.8", false))
        val res = FdroidProtocol.scanUpdates(
            input = ByteArrayInputStream(index.toByteArray()),
            storeId = StoreCatalog.ID_FDROID,
            repoBase = "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo",
            installedByPkg = installed,
        )
        val info = res["org.demo.app"]
        assertNotNull(info)
        assertEquals(12L, info!!.versionCode)
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/new.apk", info.downloadUrl)
        assertEquals("性能优化", info.changelog)
        assertNull(res["org.other.app"])
    }

    @Test
    fun `VivoProtocol parses batch updates`() {
        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val json = """
            {
              "value": [
                {
                  "id": 77,
                  "package_name": "com.demo.app",
                  "version_name": "2.1",
                  "version_code": 21,
                  "size": 1024,
                  "md5": "900150983cd24fb0d6963f7d28e17f72",
                  "update_des": "Faster",
                  "download_url": "http://cdn.vivo.com.cn/a.apk"
                }
              ]
            }
        """.trimIndent()
        val parsed = VivoProtocol.parseUpdateResponse(json, installed)["com.demo.app"]
        assertNotNull(parsed)
        assertEquals(21L, parsed!!.versionCode)
        assertEquals("https://cdn.vivo.com.cn/a.apk", parsed.downloadUrl)
        assertEquals(1024L * 1024L, parsed.sizeBytes)
    }

    @Test
    fun `AppchinaProtocol parses search response versionCode`() {
        val json = """
            {
              "list": [
                { "packageName": "com.demo.app", "size": "12.5M", "apkUrl": "http://d.appchina.com/package_321.apk", "offShelf": false }
              ]
            }
        """.trimIndent()
        val parsed = AppchinaProtocol.parseSearchMatch(json, "com.demo.app")
        assertNotNull(parsed)
        assertEquals(321L, parsed!!.versionCode)
        assertEquals("https://d.appchina.com/package_321.apk", parsed.downloadUrl)
    }

    @Test
    fun `CoolapkProtocol parses detail response`() {
        val json = """
            {
              "data": {
                "id": 888,
                "apkname": "com.demo.app",
                "apkversionname": "3.0",
                "apkversioncode": 300,
                "apklength": 4096,
                "apkmd5": "900150983cd24fb0d6963f7d28e17f72",
                "changelog": "Line1<br/>Line2"
              }
            }
        """.trimIndent()
        val parsed = CoolapkProtocol.parseDetail(json, "com.demo.app")
        assertNotNull(parsed)
        assertEquals(300L, parsed!!.versionCode)
        assertEquals("Line1\nLine2", parsed.changelog)
    }

    @Test
    fun `TapTapProtocol signs form and parses batch updates and apk detail`() {
        val xUa = TapTapProtocol.buildXUa("uid-123")
        val signed = TapTapProtocol.signForm(mapOf("identifiers" to "com.demo.game"), xUa, epochSeconds = 1700000000L, nonce = "abc12")
        assertEquals(32, signed["sign"]?.length)

        val installed = mapOf("com.demo.game" to LocalInstalledApp("com.demo.game", "Game", 10, "1.0", false))
        val batchJson = """
            {
              "data": {
                "list": [
                  {
                    "identifier": "com.demo.game",
                    "download": {
                      "apk_id": 5566,
                      "apk": { "version_code": 25, "version_name": "2.5", "size": 99999, "md5": "900150983cd24fb0d6963f7d28e17f72" }
                    },
                    "whatsnew": { "text": "New season" }
                  }
                ]
              }
            }
        """.trimIndent()
        val updates = TapTapProtocol.parseBatchUpdateResponse(batchJson, installed)
        val rel = updates["com.demo.game"]
        assertNotNull(rel)
        assertEquals(25L, rel!!.versionCode)
        assertEquals(5566L, rel.appId)
        assertEquals("New season", rel.changelog)

        val detailJson = """
            { "data": { "apk": { "download": "http://dl.taptap.com/game.apk", "size": 99999, "md5": "900150983cd24fb0d6963f7d28e17f72" } } }
        """.trimIndent()
        val detail = TapTapProtocol.parseApkDetailResponse(detailJson)
        assertEquals("https://dl.taptap.com/game.apk", detail?.url)
    }

    @Test
    fun `SamsungProtocol builds XML and parses update and download responses`() {
        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val xmlReq = SamsungProtocol.buildUpdateXml(installed.values.toList(), "deadbeef", epochMillis = 1700000000000L)
        assertTrue(xmlReq.contains("getUpdateList"))
        assertTrue(xmlReq.contains("com.demo.app@@10@1@N"))

        val updateXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SamsungProtocol>
              <response returnCode="0">
                <list>
                  <value name="GUID">com.demo.app</value>
                  <value name="productID">000004667205</value>
                  <value name="versionCode">30</value>
                  <value name="version">3.0.0</value>
                  <value name="realContentSize">123456</value>
                </list>
              </response>
            </SamsungProtocol>
        """.trimIndent()
        val parsed = SamsungProtocol.parseUpdateResponse(updateXml, installed)["com.demo.app"]
        assertNotNull(parsed)
        assertEquals(30L, parsed!!.release.versionCode)
        assertEquals("000004667205", parsed.productId)

        val dlXml = """
            <SamsungProtocol><response returnCode="0"><list>
              <value name="downLoadURI"><![CDATA[http://cdn.samsung.com/a.apk?x=1&y=2]]></value>
              <value name="fileSize">123456</value>
            </list></response></SamsungProtocol>
        """.trimIndent()
        val dl = SamsungProtocol.parseDownloadResponse(dlXml)
        assertEquals("https://cdn.samsung.com/a.apk?x=1&y=2", dl?.url)
    }

    @Test
    fun `HuaweiProtocol parses session sign and diffUpgrade2 updates`() {
        assertEquals("abc-sign", HuaweiProtocol.parseSessionSign("""{"sign":"abc-sign"}"""))
        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val body = """
            {
              "list": [
                {
                  "package": "com.demo.app",
                  "versionCode": 42,
                  "version": "4.2",
                  "fullSize": 654321,
                  "fullDownUrl": "http://cdn.huawei.com/app.apk",
                  "sha256": "${"a".repeat(64)}",
                  "newFeatures": "Fixed crash"
                }
              ]
            }
        """.trimIndent()
        val entry = HuaweiProtocol.parseDiffUpgradeResponse(body, installed)["com.demo.app"]
        assertNotNull(entry)
        assertEquals(42L, entry!!.release.versionCode)
        assertEquals("https://cdn.huawei.com/app.apk", entry.downloadUrl)
        assertEquals("Fixed crash", entry.release.changelog)
    }

    @Test
    fun `HonorProtocol signs request and parses split apk detail`() {
        val sig = HonorProtocol.sign(HonorProtocol.UPDATE_PATH, 1700000000000L, "trace123", "{}")
        assertEquals(64, sig.length)

        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val updateJson = """
            { "data": { "appLst": [ { "pName": "com.demo.app", "ver": 50, "versionName": "5.0", "fileSize": 1000, "verUptDes": "Hi" } ] } }
        """.trimIndent()
        val upd = HonorProtocol.parseUpdateResponse(updateJson, installed)["com.demo.app"]
        assertEquals(50L, upd?.versionCode)

        val detailJson = """
            {
              "data": {
                "apps": [
                  {
                    "pName": "com.demo.app",
                    "apks": [
                      { "bundle_type": 0, "downUrl": "http://cdn.honor.com/base.apk", "fileSize": 800 },
                      { "bundle_type": 1, "downUrl": "http://cdn.honor.com/split_arm64.apk", "fileSize": 200 }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val spec = HonorProtocol.parseDetailDownload(detailJson, "com.demo.app")
        assertNotNull(spec)
        assertEquals("https://cdn.honor.com/base.apk", spec!!.url)
        assertEquals(1, spec.extraParts.size)
        assertEquals("https://cdn.honor.com/split_arm64.apk", spec.extraParts.first().url)
    }

    @Test
    fun `OppoProtocol signs headers and parses upgrade and split download protobuf`() {
        val headers = OppoProtocol.buildHeaders("POST", OppoProtocol.UPDATE_URL, "inst-1", timestamp = 1700000000000L)
        assertEquals(32, headers["sign"]?.length)
        assertTrue((headers["sg"]?.length ?: 0) in setOf(32, 40))

        // 构造一个最小 Protobuf 响应（外层 field 3 嵌套 resource：field 1=appId, 7=pkg, 8=verName, 9=verCode, 10=size, 22=url）
        fun writeVarint(out: ByteArrayOutputStream, v: Long) {
            var x = v
            while ((x and 0x7FL.inv()) != 0L) {
                out.write((x.toInt() and 0x7F) or 0x80)
                x = x ushr 7
            }
            out.write(x.toInt() and 0x7F)
        }
        fun writeStr(out: ByteArrayOutputStream, f: Int, s: String) {
            val b = s.toByteArray()
            writeVarint(out, ((f shl 3) or 2).toLong())
            writeVarint(out, b.size.toLong())
            out.write(b)
        }
        fun writeLong(out: ByteArrayOutputStream, f: Int, v: Long) {
            writeVarint(out, ((f shl 3) or 0).toLong())
            writeVarint(out, v)
        }
        val resBuf = ByteArrayOutputStream().apply {
            writeLong(this, 1, 777L)
            writeStr(this, 7, "com.demo.app")
            writeStr(this, 8, "6.0")
            writeLong(this, 9, 60L)
            writeLong(this, 10, 50000L)
            writeStr(this, 22, "http://cdn.oppo.com/app.apk")
        }.toByteArray()
        val wrapBuf = ByteArrayOutputStream().apply {
            writeVarint(this, ((3 shl 3) or 2).toLong())
            writeVarint(this, resBuf.size.toLong())
            write(resBuf)
        }.toByteArray()
        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val parsed = OppoProtocol.parseUpgradeResponse(wrapBuf, installed)["com.demo.app"]
        assertNotNull(parsed)
        assertEquals(60L, parsed!!.release.versionCode)
        assertEquals("http://cdn.oppo.com/app.apk", parsed.rawUrl)
    }

    @Test
    fun `StoreSystemAppPolicy blocks cross-brand and unknown-brand OEM updates for system apps`() {
        val sysEmail = LocalInstalledApp("com.android.email", "Email", 10, "1.0", isSystemApp = true)
        val userApp = LocalInstalledApp("com.example.chat", "Chat", 10, "1.0", isSystemApp = false)
        val preloadedThirdParty = LocalInstalledApp("com.tencent.mm", "WeChat", 10, "1.0", isSystemApp = true)

        val xiaomiOem = StoreSystemAppPolicy.deviceOemStoreId(brand = "Redmi", manufacturer = "Xiaomi")
        assertEquals(StoreCatalog.ID_XIAOMI, xiaomiOem)
        assertTrue(StoreSystemAppPolicy.isStoreEligibleForApp(sysEmail, StoreCatalog.ID_XIAOMI, xiaomiOem))
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(sysEmail, StoreCatalog.ID_OPPO, xiaomiOem))
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(sysEmail, StoreCatalog.ID_TENCENT, xiaomiOem))
        assertTrue(StoreSystemAppPolicy.isStoreEligibleForApp(preloadedThirdParty, StoreCatalog.ID_TENCENT, xiaomiOem))
        assertTrue(StoreSystemAppPolicy.isStoreEligibleForApp(userApp, StoreCatalog.ID_OPPO, xiaomiOem))

        // 可卸载的 AOSP 系统包名（如部分 ROM 中 FLAG_SYSTEM=0 的 com.android.email）：仍仅允许本机品牌商店
        val removableEmail = LocalInstalledApp("com.android.email", "Email", 10, "1.0", isSystemApp = false)
        assertTrue(StoreSystemAppPolicy.isStoreEligibleForApp(removableEmail, StoreCatalog.ID_XIAOMI, xiaomiOem))
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(removableEmail, StoreCatalog.ID_OPPO, xiaomiOem))
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(removableEmail, StoreCatalog.ID_TENCENT, xiaomiOem))

        // 未知品牌（如 Pixel / 检测不到品牌）：所有厂商商店对系统应用均拦截
        val unknownOem = StoreSystemAppPolicy.deviceOemStoreId(brand = "google", manufacturer = "Google")
        assertNull(unknownOem)
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(sysEmail, StoreCatalog.ID_XIAOMI, unknownOem))
        assertFalse(StoreSystemAppPolicy.isStoreEligibleForApp(sysEmail, StoreCatalog.ID_OPPO, unknownOem))
        assertTrue(StoreSystemAppPolicy.isStoreEligibleForApp(userApp, StoreCatalog.ID_XIAOMI, unknownOem))
    }

    @Test
    fun `XiaomiProtocol parses multi-part split apks`() {
        val dlJson = """
            {
              "hosts": ["http://dl.example.com/download/"],
              "apks": [
                { "type": "split", "name": "config.arm64_v8a.apk", "apk": "app/split_arm64.apk", "size": 512 },
                { "type": "base", "name": "base.apk", "apk": "app/base.apk", "size": 2048 }
              ]
            }
        """.trimIndent()
        val spec = XiaomiProtocol.parseDownloadSpec(dlJson)
        assertNotNull(spec)
        assertEquals("https://dl.example.com/download/app/base.apk", spec!!.url)
        assertEquals(1, spec.extraParts.size)
        assertEquals("https://dl.example.com/download/app/split_arm64.apk", spec.extraParts.first().url)
    }

    @Test
    fun `OppoProtocol flags incomplete dm and incfs URLs`() {
        assertTrue(OppoProtocol.isIncompletePackageUrl("https://cdn.oppo.com/app/test.dm?token=1"))
        assertTrue(OppoProtocol.isIncompletePackageUrl("https://cdn.oppo.com/incfs/app.apk"))
        assertFalse(OppoProtocol.isIncompletePackageUrl("https://cdn.oppo.com/app/full.apk"))
    }

    @Test
    fun `HonorProtocol parses dispatch uriList and HuaweiProtocol parses sSha2`() {
        val dispatchResp = """{"data":{"uriList":["http://cdn.honor.com/signed/app.apk"]}}"""
        assertEquals("https://cdn.honor.com/signed/app.apk", HonorProtocol.parseDispatchHttpsUrl(dispatchResp))

        val installed = mapOf("com.demo.app" to LocalInstalledApp("com.demo.app", "Demo", 10, "1.0", false))
        val cert = "b".repeat(64)
        val hwBody = """
            {"list":[{"package":"com.demo.app","versionCode":20,"version":"2.0","fullSize":100,"fullDownUrl":"https://hw.example/a.apk","sSha2":["$cert"]}]}
        """.trimIndent()
        val hwEntry = HuaweiProtocol.parseDiffUpgradeResponse(hwBody, installed)["com.demo.app"]
        assertEquals(listOf(cert), hwEntry?.release?.signerSha256s)
    }

    @Test
    fun `HonorProtocol rejects mismatched package in detail response`() {
        val detailJson = """
            {"data":{"apps":[{"pName":"com.other.app","downUrl":"https://cdn.honor.com/other.apk","fileSize":100}]}}
        """.trimIndent()
        assertTrue(HonorProtocol.parseDetailCandidates(detailJson, "com.demo.app").isEmpty())
    }

    @Test
    fun `FdroidProtocol filters incompatible nativecode ABI`() {
        val pkgJson = """
            {
              "versions": {
                "x86": {
                  "file": { "name": "/x86.apk", "sha256": "${"1".repeat(64)}", "size": 100 },
                  "manifest": { "versionCode": 20, "versionName": "2.0-x86", "nativecode": ["x86_64"] }
                },
                "arm": {
                  "file": { "name": "/arm.apk", "sha256": "${"2".repeat(64)}", "size": 100 },
                  "manifest": { "versionCode": 19, "versionName": "2.0-arm", "nativecode": ["arm64-v8a"] }
                }
              }
            }
        """.trimIndent()
        val best = FdroidProtocol.parseBestVersion(
            packageJson = pkgJson,
            storeId = StoreCatalog.ID_FDROID,
            repoBase = "https://f-droid.org/repo",
            installedVersionCode = 10L,
            supportedAbis = setOf("arm64-v8a", "armeabi-v7a"),
        )
        assertNotNull(best)
        assertEquals(19L, best!!.versionCode)
        assertEquals("https://f-droid.org/repo/arm.apk", best.downloadUrl)
    }

    @Test
    fun `StoreUpdateChecker invalidates signature verdict when local certificate changes`() {
        val rec = StoreSignatureRecord(verdict = "DIFFERENT", localCertSha256 = "aaa")
        assertEquals(SignatureVerdict.DIFFERENT, StoreUpdateChecker.validSignatureVerdict(rec, "aaa"))
        assertEquals(SignatureVerdict.UNKNOWN, StoreUpdateChecker.validSignatureVerdict(rec, "bbb"))
    }
}
