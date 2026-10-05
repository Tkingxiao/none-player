plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.danmaku"
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
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))

    // 编译期插件发现：由 kernel-ksp 生成 DanmakuPluginIndex
    ksp(project(":kernel:kernel-ksp"))

    // JVM 单测补充：org.xmlpull / org.json 在 android.jar 里是运行时桩，
    // 本地单测（JVM）需要真实实现——只进 test classpath，不进 APK。
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.android.org.json)
}
