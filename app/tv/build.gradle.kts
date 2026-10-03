import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.zhangwenkang.cinefin"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        // W41（方案 A）：只改运行时身份，Kotlin namespace 保持不动。
        applicationId = "io.github.zhangwenkang.aurorama"
        minSdk = Versions.MIN_SDK
        targetSdk = Versions.TARGET_SDK

        versionCode = Versions.APP_CODE
        versionName = Versions.APP_NAME
    }

    buildTypes {
        named("debug") { applicationIdSuffix = ".debug" }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        register("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        register("libre") {
            dimension = "variant"
            isDefault = true
        }
    }

    splits {
        abi {
            // Detect app bundle and conditionally disable split abis
            // This is needed due to a "Multiple shrunk-resources files found in directory" error
            // present since AGP 8.9.0, for more info see:
            // https://issuetracker.google.com/issues/402800800
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    androidResources {
        // W40：与 app:phone 同步 —— core 的 MiSansVF.ttf 需未压缩存储，Typeface.Builder 才能 mmap。
        noCompress += "ttf"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        /*
         * W16：字幕渲染引入 ass-kt（libass）后，ass-kt 与 libmpv 各自打包一份 libc++_shared.so，
         * AGP 9 对同名原生库直接报错，所以先去重；保留哪一份由文件末尾的合并补丁决定——
         * 必须保留 libmpv 的新版（CI 在根 assembleDebug 里构建 TV 时暴露过此问题）。
         */
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.data)
    implementation(projects.setup)
    implementation(projects.modes.film)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.settings)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    coreLibraryDesugaring(libs.android.desugar.jdk)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

/*
 * W16 · libc++_shared.so 合并补丁（与 app/phone 同源，TV 端必须保持一致）。
 *
 * 背景：ass-kt（libass）与 libmpv 各带一份 libc++_shared.so；pickFirst 固定取到 ass-kt 的旧版，
 * 而 libmpv.so 需要新版里的 `__from_chars_floating_point`（真机 dlopen 失败）。做法：原生库合并
 * 任务跑完后，用 libmpv AAR 里的新版覆盖合并结果（逐个 ABI）；新版向下兼容旧符号。
 */
val libmpvNativeAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // 只要 libmpv 自己的 AAR：带上传递依赖会把 jar 拉进来，与 artifactType=aar 冲突
    isTransitive = false
    attributes {
        attribute(
            org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Usage::class.java, "java-runtime"),
        )
        attribute(
            org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.Category::class.java, "library"),
        )
        attribute(
            org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            "aar",
        )
    }
}

dependencies { add(libmpvNativeAar.name, libs.libmpv) }

tasks
    .matching { task -> task.name.startsWith("merge") && task.name.endsWith("NativeLibs") }
    .configureEach {
        doLast {
            val aarFiles: Set<File> = libmpvNativeAar.resolve()
            val aar: File? = aarFiles.firstOrNull { file -> file.name.startsWith("libmpv") }
            if (aar == null) {
                logger.warn("W16 原生库补丁：未找到 libmpv AAR，libc++_shared 可能仍是 ass-kt 旧版")
                return@doLast
            }
            val zip = ZipFile(aar)
            try {
                val roots: Set<File> = outputs.files.files
                for (root in roots) {
                    if (!root.isDirectory) continue
                    for (target in root.walkTopDown().filter { it.name == "libc++_shared.so" }) {
                        val abi = target.parentFile?.name ?: continue
                        val entry = zip.getEntry("jni/$abi/libc++_shared.so") ?: continue
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        logger.lifecycle("W16 原生库补丁：libc++_shared.so ($abi) 用 libmpv 版本覆盖")
                    }
                }
            } finally {
                zip.close()
            }
        }
    }
