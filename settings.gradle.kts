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

rootProject.name = "Chirp"

include(":app")
include(":core")
include(":wear")
// PHASE 2: add `include(":wear")` here — a Wear OS module that depends on :core
// for SessionState/SessionCommand/WearContract and talks to the phone via the
// Data Layer API. See :core/wear/WearContract.kt and ConversationService.
