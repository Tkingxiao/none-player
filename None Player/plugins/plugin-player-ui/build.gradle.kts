plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.player.ui"
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
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.androidx.core.ktx)
    // M2 共享封面：飞行帧要渲染与卡片同一张封面（与 plugin-library 同一个 Coil，不增体积）
    implementation(libs.coil.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)

    // 编译期插件发现：由 kernel-ksp 生成 PlayerUiPluginIndex
    ksp(project(":kernel:kernel-ksp"))

    testImplementation(libs.junit)
}
