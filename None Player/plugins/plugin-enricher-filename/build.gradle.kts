plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.enricher.filename"
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
    // 插件依赖铁律：只允许 kernel-api / kernel-ui-api / core-*
    implementation(project(":kernel:kernel-api"))
    implementation(project(":core:media"))

    // 编译期插件发现：由 kernel-ksp 生成 FilenameEnricherPluginIndex
    ksp(project(":kernel:kernel-ksp"))

    testImplementation(libs.junit)
}
