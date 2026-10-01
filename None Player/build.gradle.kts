// 根构建脚本：仅声明插件版本，不在根模块应用
// AGP 9 起内置 Kotlin 支持（android-application / android-library 自带 KGP 2.2.10+），
// 故 Android 模块不再应用 kotlin-android（重复应用会报
// "Cannot add extension with name 'kotlin'"）；仅纯 JVM 模块仍用 kotlin-jvm。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// media3 1.11.x 的 POM 传递依赖 kotlin-stdlib 2.2.10；显式对齐到项目自带的
// KGP 版本，避免依赖图上出现两个 stdlib（KGP 2.2.10 起元数据可读，无需再降级）。
allprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        }
    }
}
