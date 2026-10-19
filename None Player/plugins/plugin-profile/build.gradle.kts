plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.profile"
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
    implementation(project(":kernel:kernel-ui-api"))
    implementation(project(":core:database"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    // "最近添加"横滑需要真实封面。Coil 已在 :app 的依赖图里（plugin-library 用同一份），
    // 这里引它不增加 APK 体积，只是让本模块编译期可见；
    // 走应用级 ImageLoaderFactory 才能拿到 VideoFrameDecoder（视频封面抽帧）。
    implementation(libs.coil.compose)
    // 同上：ProfileScreen 的「最近添加」封面用 coil.request.videoFrameMillis(0)（coil-video）
    implementation(libs.coil.video)

    // 编译期插件发现：由 kernel-ksp 生成 ProfilePluginIndex
    ksp(project(":kernel:kernel-ksp"))
}
