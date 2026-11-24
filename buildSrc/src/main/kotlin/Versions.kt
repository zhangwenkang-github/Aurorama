import org.gradle.api.JavaVersion

object Versions {
    // 2.0.0：V2 全波次（W0–W9）收口发布。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 7
    const val APP_NAME = "2.0.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
