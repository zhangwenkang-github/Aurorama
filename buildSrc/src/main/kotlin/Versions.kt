import org.gradle.api.JavaVersion

object Versions {
    // W77（2026-10-07）：1.2.0 阅读流式更新（EPUB / CBZ 远端先出页、按需预取、顶栏三态与进度）。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 3
    const val APP_NAME = "1.2.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
