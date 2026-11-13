import org.gradle.api.JavaVersion

object Versions {
    // 1.4.1：播放稳定性收尾。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 6
    const val APP_NAME = "1.4.1"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
