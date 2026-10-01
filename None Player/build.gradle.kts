// 根构建脚本：仅声明插件版本，不在根模块应用
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// media3 1.11.x 的 POM 传递依赖 kotlin-stdlib 2.2.10，其元数据超出当前
// Kotlin 2.0.21 编译器可读范围；media3 本体为纯 Java，约束回项目自带版本即可
allprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        }
    }
}
