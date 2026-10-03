plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.noneplayer.core.ui"
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
    // 设计系统整体提供给插件使用，故用 api
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.androidx.activity.compose)
    // 玻璃 blur 实现载体（Haze）：core:ui 下发样式与 CompositionLocal，
    // kernel-ui-api 的外壳与插件的迷你条各自挂 effect 节点
    api(libs.haze.core)
    api(libs.haze.materials)
    // Theme.kt 的主题切换过渡用 `createBitmap`（core-ktx）：此前只经 compose 传递依赖，
    // 直接引用须显式声明，否则 lint 的 UseKtx 会提示且依赖关系是隐式的
    implementation(libs.androidx.core.ktx)
    debugApi(libs.compose.ui.tooling)
}
