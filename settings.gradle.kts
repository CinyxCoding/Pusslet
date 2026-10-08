rootProject.name = "Pusslet"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Android application (flavors, widgets, workers, platform integrations)
include(":composeApp")

// Shared Kotlin Multiplatform modules (Android + iOS)
include(":shared:core")
include(":shared:data")
include(":shared:ui")
include(":shared:app")
include(":shared:feature-today")
include(":shared:feature-calendar")
include(":shared:feature-meals")
include(":shared:feature-shopping")
include(":shared:feature-tasks")
include(":shared:feature-family")
include(":shared:feature-settings")
