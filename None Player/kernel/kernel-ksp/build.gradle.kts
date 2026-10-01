plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.api)
    // 仅引用扩展点 FQN 常量，保证注解/接口改名时编译期即可发现
    implementation(project(":kernel:kernel-api"))
}
