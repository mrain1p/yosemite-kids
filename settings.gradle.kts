pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // NewPipeExtractor
    }
}

rootProject.name = "yosemitekids"
include(":app")
include(":core")
// The one crawler, shared by the app and the hub (docs/PLAN-crawl.md).
include(":crawl")
include(":hub")
