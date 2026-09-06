import java.util.Properties

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

// Included builds do not inherit the root build's local.properties. Android Studio normally
// configures only this root, leaving Tracebox without an SDK (and D8 sync transforms without
// their boot classpath). Bootstrap the vendored build using only sdk.dir, never other local data.
// An external Tracebox checkout owns its own SDK configuration.
if (!providers.gradleProperty("traceboxSourceDir").isPresent) {
    val rootLocalProperties = Properties().apply {
        providers.fileContents(layout.settingsDirectory.file("local.properties")).asText.orNull
            ?.reader()?.use { load(it) }
    }
    val traceboxLocalFile = file("$traceboxSource/local.properties")
    val traceboxLocalProperties = Properties().apply {
        if (traceboxLocalFile.isFile) traceboxLocalFile.inputStream().use { load(it) }
    }
    val sdkDir = rootLocalProperties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    if (sdkDir != null && traceboxLocalProperties.getProperty("sdk.dir").isNullOrBlank()) {
        traceboxLocalProperties.setProperty("sdk.dir", file(sdkDir).absolutePath)
        traceboxLocalFile.outputStream().use {
            traceboxLocalProperties.store(it, "Local SDK configuration for the included Tracebox build; do not commit.")
        }
    }
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
