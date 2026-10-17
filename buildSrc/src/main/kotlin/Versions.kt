import org.gradle.api.JavaVersion

object Versions {
    // W78–W83（2026-10-17）：1.3.0 音乐、阅读、搜索、媒体库体验更新。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 4
    const val APP_NAME = "1.3.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
