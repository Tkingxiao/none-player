plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.noneplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noneplayer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    // 装配依赖：内核实现 + 所有插件（铁律：app 只做装配，无业务逻辑）
    implementation(project(":kernel:kernel"))
    implementation(project(":kernel:kernel-ui-api"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":plugins:plugin-library"))
    implementation(project(":plugins:plugin-scanner-local"))
    implementation(project(":plugins:plugin-player-exo"))
    implementation(project(":plugins:plugin-player-ui"))
    implementation(project(":plugins:plugin-profile"))
    implementation(project(":plugins:plugin-enricher-filename"))
    implementation(project(":plugins:plugin-danmaku"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // E10：Application 实现 coil.ImageLoaderFactory（含 VideoFrameDecoder）
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
