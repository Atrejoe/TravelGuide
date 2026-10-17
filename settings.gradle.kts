pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TravelGuide"

include(":core")

// The Android app needs an Android SDK. Pass -PcoreOnly=true (as the cheap CI job does)
// to work on the pure-Kotlin core module on machines without one.
val coreOnly = providers.gradleProperty("coreOnly").orNull == "true"
if (!coreOnly) {
    include(":app")
}
