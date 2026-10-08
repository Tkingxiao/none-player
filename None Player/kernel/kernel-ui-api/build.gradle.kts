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
    // Rail 顶部的品牌块复用设计系统里的 NoneMark（⊘ 签名标记），
    // 与启动图 / 空态 / 关于页保持同一个记号。仅在实现内使用，不外泄。
    implementation(project(":core:ui"))

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

    // hasPointerSource 纯判据的单测（§14 #18 的判据钉死，设备枚举本身不进单测）
    testImplementation(libs.junit)
    // hinge 避让（折叠屏）用的 WindowInfoTracker 只在实现内使用，不必外泄
    implementation(libs.androidx.window)
}
