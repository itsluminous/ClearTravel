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

rootProject.name = "ClearTravel"

// ALL modules are declared up front (milestone 1) so later parallel agents
// never need to touch this file — a module they own already exists here.
include(":app")
include(":core:designsystem")
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:notifications")
include(":core:google")
include(":core:scrape")
include(":core:ocr")
include(":core:testing")
include(":feature:trains")
include(":feature:flights")
include(":feature:itinerary")
include(":feature:checklist")
include(":feature:documents")
include(":feature:menu")
