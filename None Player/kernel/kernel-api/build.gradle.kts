plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 扩展点签名直接暴露 MediaItem / Flow，必须用 api 传递给所有插件
    api(libs.kotlinx.coroutines.core)
    api(project(":core:media"))
    // A6：同 core:media——只为 @Immutable 注解，不引入 Compose 运行时。
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)
    testImplementation(libs.junit)
}
