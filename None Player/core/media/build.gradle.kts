plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // A6：领域模型只用 Compose 的 @Immutable 注解让消费端能跳过重组；
    // compileOnly 不把 Compose 运行时带进这一层。
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)
    testImplementation(libs.junit)
}
