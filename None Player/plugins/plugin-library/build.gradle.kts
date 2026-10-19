plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.library"
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
    implementation(project(":core:media"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    // 详情页的 HDR 标识按需调 FormatProbe（与起播探测同一份缓存与判读口径）
    implementation(project(":core:media-android"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
    // 卡片封面取视频首帧：`coil.request.videoFrameMillis(0)` 是 coil-video 的扩展，
    // 属直接引用，必须显式声明（此前只经 :app 的运行时依赖图传递）
    implementation(libs.coil.video)

    // 编译期插件发现：由 kernel-ksp 生成 LibraryPluginIndex
    ksp(project(":kernel:kernel-ksp"))
    testImplementation(libs.junit)
}