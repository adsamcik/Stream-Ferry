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

// A reviewed source revision supplies the history API until its next Tracebox release.
// Override only for coordinated local library development; CI uses the pinned submodule.
val traceboxSource = providers.gradleProperty("traceboxSourceDir").orNull ?: "vendor/tracebox"
check(file("$traceboxSource/settings.gradle.kts").isFile) {
    "Tracebox sources are missing. Run git submodule update --init --recursive."
}
includeBuild(traceboxSource) {
    dependencySubstitution {
        listOf("tracebox", "tracebox-api", "tracebox-core", "tracebox-storage", "tracebox-directboot",
            "tracebox-anr-exit", "tracebox-export", "tracebox-export-ui", "tracebox-ui-compose", "tracebox-native")
            .forEach { name -> substitute(module("io.github.tracebox:$name")).using(project(":android:$name")) }
    }
}
include(":app")
include(":core")
include(":source:api")
include(":source:local")
include(":source:jellyfin")
include(":playback")
include(":ui")
