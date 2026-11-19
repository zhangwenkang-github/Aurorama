plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.zhangwenkang.cinefin.player.core"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }
}

dependencies {
    implementation(libs.timber)
    // 版本与 app/phone 的 kotlinx-coroutines-test 对齐（catalog 暂无 coroutines 条目）。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
