// None Player — 插件化微内核播放器
// 模块依赖铁律（见 docs/设计文档.md §5）：
//   plugin-*  → kernel-api, kernel-ui-api, core-*（禁止依赖其他 plugin-*）
//   kernel    → kernel-api, kernel-ui-api, core-*
//   app       → kernel, 所有 plugin-*（仅用于装配）
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Media3Avi（AVI 解封装增强 + MJPEG 软解渲染器）只有 JitPack 产物，
        // 官方 README 明示 2.3.0 起改用 jitpack.io。用内容过滤把它限定在本仓库，
        // 避免 Gradle 对其他依赖也去 JitPack 探一遍。
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.dburckh")
            }
        }
    }
}

rootProject.name = "None Player"

include(":app")
include(":kernel:kernel-api")
include(":kernel:kernel-ui-api")
include(":kernel:kernel-ksp")
include(":kernel:kernel")
include(":core:common")
include(":core:media")
include(":core:media-android")
include(":core:data")
include(":core:database")
include(":core:ui")
include(":plugins:plugin-library")
include(":plugins:plugin-scanner-local")
include(":plugins:plugin-player-exo")
include(":plugins:plugin-player-ui")
include(":plugins:plugin-profile")
include(":plugins:plugin-enricher-filename")
include(":plugins:plugin-danmaku")
