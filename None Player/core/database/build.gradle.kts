plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noneplayer.core.database"
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

ksp {
    // KSP2 下 Room 处理器默认只吐 Java，需显式要求生成 Kotlin
    arg("room.generateKotlin", "true")
    // §8.3：schema 导出目录。与 NonePlayerDatabase 的 exportSchema = true 配套——
    // 导出的 <version>.json 是后续写 Migration 时的唯一比对依据（缺失会让 Room 直接报错）。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:media"))
    implementation(libs.kotlinx.coroutines.core)

    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
