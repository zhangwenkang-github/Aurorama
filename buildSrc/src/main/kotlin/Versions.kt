import org.gradle.api.JavaVersion

object Versions {
    // W71（2026-10-05）：首个发布版本。后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md）。
    const val APP_CODE = 1
    const val APP_NAME = "1.0.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
