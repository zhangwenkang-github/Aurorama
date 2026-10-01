plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.zhangwenkang.cinefin.player.local"
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
    implementation(projects.player.core)
    implementation(projects.data)
    implementation(projects.settings)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    /*
     * W16：libmpv 与 ass-kt（libass）各带一份 libc++_shared.so，app 侧用 packaging.pickFirsts 去重，
     * **声明顺序决定保留哪一份**：libmpv 的是新 NDK libc++（含 __from_chars_floating_point），
     * ass-kt 的是旧版（真机实测会让 libmpv.so dlopen 失败），所以 libmpv 必须写在前面。
     */
    implementation(libs.libmpv)
    implementation(libs.ass.kt)
    implementation(libs.timber)

    testImplementation("junit:junit:4.13.2")
}
