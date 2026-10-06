import org.gradle.api.JavaVersion

object Versions {
    // W76（2026-10-06）：1.1.0 缺陷修复更新（18 条反馈 + 搜索 / 评分增强）。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 2
    const val APP_NAME = "1.1.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
