plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.candy.tick"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.candy.tick"
        minSdk = 29
        targetSdk = 37
        versionCode = 16
        versionName = "0.8.0"
    }

    // 正式签名（keystore.properties 不入库，本地读取）
    val ksFile = rootProject.file("keystore.properties")
    val ksMap = if (ksFile.exists()) {
        ksFile.readLines().filter { it.contains("=") }.associate {
            val idx = it.indexOf('=')
            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }
    } else emptyMap<String, String>()
    val ksPath = ksMap["storeFile"]
    val ksPass = ksMap["storePassword"] ?: ""
    val ksAlias = ksMap["keyAlias"] ?: ""
    val ksKeyPass = ksMap["keyPassword"] ?: ""

    signingConfigs {
        create("release") {
            if (ksPath != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            // Optional identifier for locally shared builds, visible in Android's app info.
            versionNameSuffix = providers.gradleProperty("candyTickBuildLabel").orNull?.let { "+$it" }
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            // 真机双架构；x86/x86_64 仅供模拟器，release 不带（debug 不受限）
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // 应用内只维护中英两语，裁掉依赖库携带的其他语言资源
        localeFilters += listOf("zh", "en")
        // OkHttp 公共域名后缀库（132KB）：不用 Cookie 纯冗余；OkHttp 惰性加载缺失时静默降级。
        // jar 内 assets 不走 packaging.resources，须在 aapt 资产合并层忽略（保留 aapt 默认排除串）
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!PublicSuffixDatabase.list"
    }

    packaging {
        dex {
            // GitHub Release 直接分发 APK，开启 dex 压缩可减半下载体积（~4MB → ~2MB）
            useLegacyPackaging = true
        }
        resources {
            // 仅剔除构建与调试期元数据，保留所有开源 LICENSE / NOTICE 合规声明
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "kotlin/**",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.glass)
    // 更新日志 Markdown 解析（GFM 表格/删除线/自动链接）——ReleaseNotes 渲染内核
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.autolink)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
}
