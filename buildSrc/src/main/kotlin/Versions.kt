import org.gradle.api.JavaVersion

object Versions {
    // W84–W89（2026-10-25）：1.4.0 更新、搜索、元数据、字幕与离线体验更新。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 5
    const val APP_NAME = "1.4.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
