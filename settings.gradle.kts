pluginManagement {
    includeBuild("build-logic")
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Stash"

include(":app")

include(":core:ui")
include(":core:model")
include(":core:common")
include(":core:data")
include(":core:media")
include(":core:auth")
include(":core:network")

include(":data:spotify")
include(":data:ytmusic")
include(":data:download")
include(":data:lyrics")

include(":feature:home")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:sync")
include(":feature:settings")
include(":feature:search")
