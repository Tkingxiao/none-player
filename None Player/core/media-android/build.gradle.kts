plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.noneplayer.core.media.android"
    compileSdk = 36

    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        // §12.9.2 QPel 探测 harness（androidTest）所需
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 纯 JVM 测试驱动 Extractor（Wmv2AviExtractorIndexTest 等）：android.util.Log
    // 走默认 no-op 而不是抛 "not mocked"。模块 JVM 测试只覆盖纯 Kotlin/extractor 面，
    // 真机语义仍在 androidTest。
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    // FormatProbe 用 `String.toUri()`（core-ktx）：直接引用须显式声明，与 :core:ui 同一口径，
    // 否则 lint 的 UseKtx 会提示且依赖关系是隐式的
    implementation(libs.androidx.core.ktx)
    // AVI 解封装增强（AviExtractorsFactory）：探测链必须与播放链同源，
    // 否则"探测说没视频轨、播放却能出画"（或反之）——见 FormatProbe 注释
    implementation(libs.media3avi)
    // 内置 FFmpeg 软解（NextLib）：探测"有没有解码器"时必须把这条软解链算进去，
    // 否则 AV1 / MPEG-1/2 这类平台无解码器、FFmpeg 可解的编码会被误判为不可播
    implementation(libs.nextlib.media3ext)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
