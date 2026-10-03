plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.noneplayer.kernel.ui.api"
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
    api(project(":kernel:kernel-api"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // E52（§13.2）：NpWindowInfo 的字段类型是 WindowWidthSizeClass，必须 api 出去，
    // 否则消费方（各插件）拿不到该类型；adaptive-navigation-suite 供 KernelTabScaffold
    // 的导航栏形态分派用（E46），同样落本模块。
    api(libs.compose.material3.window.size)
    api(libs.compose.material3.adaptive.navigation.suite)
    api(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // hinge 避让（折叠屏）用的 WindowInfoTracker 只在实现内使用，不必外泄
    implementation(libs.androidx.window)
    // 外壳玻璃条（tabbar / chrome 槽位）的 backdrop blur（设计稿 blur(22px)）。
    // 只在实现侧使用（NpGlassStyle 经 core:ui 下发），不 api 出去。
    implementation(libs.haze.core)
    implementation(libs.haze.materials)
}
