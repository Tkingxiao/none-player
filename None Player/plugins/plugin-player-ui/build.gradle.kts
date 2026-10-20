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
    testOptions {
        unitTests {
            // R1 会话级回归：Robolectric 下构造真实 Context/AppSettings/Room 历史库，
            // 用假引擎驱动 PlaybackSession 的会话一致性断言。
            isIncludeAndroidResources = true
        }
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
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.androidx.core.ktx)
    // M2 共享封面：飞行帧要渲染与卡片同一张封面（与 plugin-library 同一个 Coil，不增体积）
    implementation(libs.coil.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    // PlayerScreen 的底栏/返回等图标（Icons.AutoMirrored / ArrowBack / PlayArrow）；
    // 此前由 core:ui 的 api 传递，属隐式依赖，改在本模块显式声明。
    implementation(libs.compose.material.icons)

    // 编译期插件发现：由 kernel-ksp 生成 PlayerUiPluginIndex
    ksp(project(":kernel:kernel-ksp"))

    testImplementation(libs.junit)
    // R1 会话级回归：Robolectric（SDK 34）+ 协程测试 + AndroidX 测试基础
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
