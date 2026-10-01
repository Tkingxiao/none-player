plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noneplayer.core.media.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 依赖铁律：core-* 只依赖 kernel-api 之外的公共库；本模块提供内核升级前
    // 的格式预探测（E39），与 :core:media 的纯 Kotlin 域模型分离
    api(project(":core:media"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.common)
    implementation(libs.media3.extractor)
    implementation(libs.media3.datasource)
}
