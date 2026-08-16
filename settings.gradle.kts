pluginManagement {
    repositories {
        // Supply-chain policy (§14): only Google + Maven Central. No JitPack/jcenter/mavenLocal.
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
    // Forbid project-declared repositories; fail the build if any module adds one (§14).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StreamFerry"
include(":app")
include(":core")
include(":source:api")
include(":source:local")
include(":source:jellyfin")
