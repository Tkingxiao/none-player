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

// 版本类 lint（GradleDependency / NewerVersionAvailable / OldTargetApi）为刻意锁版：
// compileSdk 36、Compose BOM 2024.12、Kotlin 2.2.10 均为受控升级节奏，统一豁免。
// ObsoleteSdkInt 唯一命中点是 mipmap-anydpi-v26——AGP 9 的 AAPT2 对 adaptive-icon
// 资源强制要求 -v26 目录限定符，无法按建议合并，一并豁免。
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi", "ObsoleteSdkInt")
            }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi", "ObsoleteSdkInt")
            }
        }
    }
}
