plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.plugins.scanner.local"
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
            // 扫描级回归（R0）：Robolectric 下构造真实 Context/ContentResolver，
            // 用假 ContentProvider 注入 MediaStore / DocumentsProvider 故障。
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
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 编译期插件发现：由 kernel-ksp 生成 LocalScannerPluginIndex
    ksp(project(":kernel:kernel-ksp"))

    testImplementation(libs.junit)
    // R0 扫描数据保护回归：Robolectric（SDK 34）+ 协程测试 + AndroidX 测试基础
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
