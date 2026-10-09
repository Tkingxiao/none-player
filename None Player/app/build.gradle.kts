import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// E35：release 签名走 local.properties **可选**配置，密钥永不入库（.gitignore 已排除）：
//   noneplayer.release.storeFile=<keystore 绝对路径或相对工程根路径>
//   noneplayer.release.storePassword=…
//   noneplayer.release.keyAlias=…
//   noneplayer.release.keyPassword=…
// 配齐后 assembleRelease 出正式签名包；未配齐时显式回落 debug 签名（与旧行为一致），
// 保证"可安装可跑"的门禁不因缺签名而断——但会打日志提醒，避免悄悄带着 debug 签名出包。
val releaseSigningProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
val releaseStoreFile = releaseSigningProps.getProperty("noneplayer.release.storeFile")
val releaseStorePassword = releaseSigningProps.getProperty("noneplayer.release.storePassword")
val releaseKeyAlias = releaseSigningProps.getProperty("noneplayer.release.keyAlias")
val releaseKeyPassword = releaseSigningProps.getProperty("noneplayer.release.keyPassword")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.noneplayer.app"
    // E36：与全部 library 模块对齐在受控基线 36（此前 app 独自 37，是无记录的漂移）。
    // targetSdk 升到 36（Android 16）：edge-to-edge 本就按 enableEdgeToEdge + safeDrawing
    // insets 实现，为合规前置；预测式返回/窗口 resize 的行为验证见审核文档 §10 批次 7 行。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noneplayer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "E35 提醒：local.properties 未配置 noneplayer.release.*，assembleRelease 将使用 debug 签名。",
                )
                signingConfigs.getByName("debug")
            }
        }
    }
    // E35：lint 口径显式化——当前 13/13 模块 0 issue，**不生成攒账式 baseline 文件**，
    // abortOnError 保持开启：新增 error 直接打断门禁。若未来引入大量第三方依赖产生
    // 不可修 error，再用 `lint { baseline = file("lint-baseline.xml") }` 一次性冻结存量。
    lint {
        abortOnError = true
        checkReleaseBuilds = true
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
    // A2-9（E51 余项）：androidx.window 提供折叠/铰链与窗口布局真值，
    // 喂给形态判定层的 hasFoldingFeature（此前该形参零调用方喂值，折叠屏语义从未生效）
    implementation(libs.androidx.window)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // E10：Application 实现 coil.ImageLoaderFactory（含 VideoFrameDecoder）
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
