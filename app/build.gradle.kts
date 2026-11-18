plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.candy.tick"
    // 文档基线 36；OkHttp 5.5.1 / materialkolor 等 2026-11 依赖的 AAR 元数据要求 ≥37，
    // 按 design.md「开工时取最新」取当前 stable 37.2（本机已装 android-37.2）
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.candy.tick"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
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
            isMinifyEnabled = true
            // 真机双架构；x86/x86_64 仅供模拟器，release 不带（debug 不受限）
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)


    implementation(libs.androidx.work.runtime.ktx)
}
