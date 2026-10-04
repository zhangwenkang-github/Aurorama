import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

/*
 * W71 · release 签名（2026-10-05）。
 *
 * 配置放**仓库根** `keystore.properties`（gitignored，不入库；密码只由 Gradle 读取，不打印）：
 *     storeFile=.../aurorama-release.jks
 *     storePassword=...
 *     keyAlias=...
 *     keyPassword=...
 *
 * 行为：存在且四个字段齐全 → release 变体启用签名（staging 保持不签名）；
 *      缺失或不完整 → 打印清晰提示并跳过签名（产物为未签名 APK），不中断构建。
 * 位置与备份说明见 docs/RELEASE_PLAN.md。
 */
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningConfigured =
    releaseKeystorePropertiesFile.isFile &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { key ->
            !releaseKeystoreProperties.getProperty(key).isNullOrBlank()
        }

/*
 * W71 · 发布产物开关：默认沿用 ABI 分包（armeabi-v7a / arm64-v8a / x86 / x86_64）。
 * 打发布包时加 `-Paurorama.universalApk=true` 追加整包 universal APK（GitHub Releases 用）。
 * 默认关闭，避免日常 assembleDebug / 单测被整包打包拖慢。
 */
val buildUniversalApk =
    providers.gradleProperty("aurorama.universalApk").orNull?.toBoolean() == true

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

        testInstrumentationRunner = "com.zhangwenkang.cinefin.HiltTestRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                // PKCS12（RSA 4096）；storeFile 由 properties 给出绝对路径。
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        named("debug") { applicationIdSuffix = ".debug" }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        register("staging") {
            initWith(getByName("release"))
            // staging 是本地调试身份，保持不签名（沿用 W71 之前的产物形态）。
            signingConfig = null
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
            isUniversalApk = buildUniversalApk
        }
    }

    androidResources {
        // W40：MiSansVF.ttf 以 assets 原文件入库，加载走 Typeface.Builder(assets, path)（mmap / openFd）。
        // 压缩存储会退化为「整包读入直接内存」（20 MB/字重），API 28 上还可能拿不到文件描述符。
        noCompress += "ttf"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    packaging {
        jniLibs {
            /*
             * W16：字幕渲染引入 ass-kt（libass）后，ass-kt 与 libmpv 各自打包一份 libc++_shared.so，
             * AGP 9 对同名原生库直接报错，所以先去重；具体保留哪一份由下方「合并后打补丁」决定——
             * 必须保留 libmpv 的新版（见文件末尾 merge*NativeLibs 的补丁说明）。
             */
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.data)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.setup)
    implementation(projects.modes.film)
    implementation(projects.modes.book)
    implementation(projects.modes.music)
    implementation(projects.settings)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    ksp(libs.kotlin.metadata.jvm)
    compileOnly(libs.libmpv)
    implementation(libs.material)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.timber)

    coreLibraryDesugaring(libs.android.desugar.jdk)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)

    // 版本目录暂无 junit 别名（与 core 同口径）：只用于本模块的纯逻辑单测
    testImplementation("junit:junit:4.13.2")
}

/*
 * W16 · libc++_shared.so 合并补丁。
 *
 * 背景：字幕渲染新增 ass-kt（libass）后，它和 libmpv 各自打包一份 libc++_shared.so，
 * AGP 9 对同名原生库直接报错 → 上面用 packaging.jniLibs.pickFirsts 去重。但 pickFirst **固定**
 * 取到 ass-kt 的旧版（实测与声明顺序无关），而 libmpv.so 需要新版里的
 * `__from_chars_floating_point` —— 真机上是 `UnsatisfiedLinkError: dlopen failed: cannot locate symbol`
 * （Pad 5 / Android 13 复现）。ass-kt 自 0.3.0 起各版本带的是同一份旧 libc++，换版本解决不了。
 *
 * 做法：原生库合并任务跑完后，用 libmpv AAR 里的新版 libc++_shared.so **覆盖**合并结果（逐个 ABI）。
 * 新版 libc++ 向下兼容旧符号，libass 侧继续可用（真机验证见 PLAYER_PLAN §19）。
 * 校验：APK 内 lib/arm64-v8a/libc++_shared.so 的 sha256 应等于 libmpv AAR 内同名文件。
 */
val libmpvNativeAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // 只要 libmpv 自己的 AAR：带上传递依赖会把 jar（kotlin-stdlib 等）拉进来，和 artifactType=aar 冲突
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
