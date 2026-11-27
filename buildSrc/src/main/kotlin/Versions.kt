import org.gradle.api.JavaVersion

object Versions {
    // 2.0.3：体验修复波次（播放内核自愈/首页回顶/详情入口/播放器界面/更新下载无反应）。
    // 后续每次发布 versionCode +1（见 docs/RELEASE_PLAN.md §6）。
    const val APP_CODE = 10
    const val APP_NAME = "2.0.3"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28
    const val BUILD_TOOLS = "37.0.0"

    val JAVA = JavaVersion.VERSION_21
}
