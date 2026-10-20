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
    // R4-17/REV-14：Robolectric 跑 Compose 需要 AAR 资源（含 ui-test-manifest 声明的
    // Activity）进到单测资源里，否则 createComposeRule 起不来。与 kernel-ui-api 同款配置。
    testOptions {
        unitTests {
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
    // 设计系统整体提供给插件使用，故用 api
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.androidx.activity.compose)
    // Theme.kt 的主题切换过渡用 `createBitmap`（core-ktx）：此前只经 compose 传递依赖，
    // 直接引用须显式声明，否则 lint 的 UseKtx 会提示且依赖关系是隐式的
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // R4-17/REV-14：选中/开关语义的行为级回归（Robolectric + Compose 测试床）。
    // compose ui-test-junit4 会传递依赖 kotlinx-coroutines-test 1.7.3；离线仓库只有项目统一定义
    // 的 1.9.0（catalog），显式声明把冲突版本抬到 1.9.0，否则离线构建无法解析 1.7.3 的 jar。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
