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
        exclusiveContent {
            forRepository {
                maven {
                    name = "TraceboxReleases"
                    url = uri("https://maven.pkg.github.com/adsamcik/tracebox")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.gradleProperty("GITHUB_OWNER"))
                            .orElse(providers.environmentVariable("TRACEBOX_USER"))
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.gradleProperty("GITHUB_TOKEN"))
                            .orElse(providers.environmentVariable("TRACEBOX_TOKEN"))
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
            filter { includeGroup("io.github.tracebox") }
        }
    }
}

rootProject.name = "StreamFerry"

include(":app")
include(":core")
include(":source:api")
include(":source:local")
include(":source:jellyfin")
include(":playback")
include(":ui")
