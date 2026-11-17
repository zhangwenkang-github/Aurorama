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
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = Versions.MIN_SDK
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++11"
            }
        }
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    externalNativeBuild {
        cmake {
            path = file("../mpv-binding/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../mpv-binding/src/main/java")
            jniLibs.srcDir("../mpv-binding/src/main/jniLibs")
        }
        getByName("test").kotlin.srcDir("../mpv-binding/src/test/java")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
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
    // mpv-binding 在 player/core 之后、ass-kt 之前提供新版 libc++_shared.so。
    implementation(libs.ass.kt)
    implementation(libs.timber)

    testImplementation("junit:junit:4.13.2")
}
