enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "cinefin"

include(":app:phone")
include(":app:tv")
include(":core")
include(":data")
include(":player:core")
include(":player:local")
include(":setup")
include(":modes:film")
include(":modes:book")
include(":modes:music")
include(":settings")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
