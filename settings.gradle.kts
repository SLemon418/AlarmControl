pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // These repositories are used at BUILD time only to download dependencies.
    // They are unrelated to the app's runtime: the shipped app declares no INTERNET
    // permission and makes no network calls (CLAUDE.md §3).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AlarmControl"

include(":app")
include(":core")
include(":data")
include(":ml")
include(":notifications")
include(":automation")
include(":baselineprofile")
