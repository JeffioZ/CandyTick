// AGP 9 内置 Kotlin：默认捆绑的 KGP 版本较低，通过 buildscript classpath
// 上探到 [versions.kotlin]（buildscript 块读不到 version catalog，改动时两处同步）
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
