plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.player.exo"
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
    // 插件依赖铁律：只允许 kernel-api / kernel-ui-api / core-*，禁止依赖其他 plugin-*
    implementation(project(":kernel:kernel-api"))
    implementation(project(":core:common"))
    implementation(project(":core:media-android"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // AVI：AviExtractorsFactory（解封装）+ MjpegRenderersFactory（MJPEG 软解渲染）
    implementation(libs.media3avi)
    // FFmpeg 扩展渲染器（NextRenderersFactory）+ DecoderManager：硬解优先、硬解不支持时软解兜底
    implementation(libs.nextlib.media3ext)
    implementation(libs.kotlinx.coroutines.core)

    // 编译期插件发现：由 kernel-ksp 生成 ExoPlayerPluginIndex
    ksp(project(":kernel:kernel-ksp"))
}
