// Root build script. Plugins are declared here (apply false) and applied in modules.
// AGP 9 provides built-in Kotlin support, so org.jetbrains.kotlin.android is intentionally not
// declared here (applying it errors out under AGP >= 9.0).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val checkSourceBoundaries by tasks.registering {
    group = "verification"
    description = "Reject concrete source dependencies outside the application composition root."

    val guardedSourceTrees = listOf("ui/src", "playback/src")
        .map { layout.projectDirectory.dir(it) }
    val sourceImplementations = listOf("source/jellyfin/src", "source/local/src")
        .map { layout.projectDirectory.dir(it) }
    val sharedApi = layout.projectDirectory.dir("source/api/src/main")
    val sharedCore = layout.projectDirectory.dir("core/src/main")
    inputs.files(guardedSourceTrees, sourceImplementations, sharedApi, sharedCore)
    inputs.files(
        layout.projectDirectory.file("ui/build.gradle.kts"),
        layout.projectDirectory.file("playback/build.gradle.kts"),
        layout.projectDirectory.file("source/jellyfin/build.gradle.kts"),
        layout.projectDirectory.file("source/local/build.gradle.kts"),
    )

    doLast {
        val violations = mutableListOf<String>()
        fun kotlinFiles(path: String) = fileTree(path) { include("**/*.kt") }.files

        val concreteImports = listOf(
            "com.adsamcik.streamferry.data.jellyfin",
            "com.adsamcik.streamferry.data.local",
            "com.adsamcik.streamferry.source.jellyfin",
            "com.adsamcik.streamferry.source.local",
        )
        (kotlinFiles("ui/src") + kotlinFiles("playback/src")).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.trimStart().startsWith("import ") && concreteImports.any(line::contains)) {
                    violations += "${file.relativeTo(projectDir)}:${index + 1}: $line"
                }
            }
        }

        val forbiddenSourceImports = mapOf(
            "source/jellyfin/src" to listOf(".data.local.", ".source.local.", ".ui."),
            "source/local/src" to listOf(".data.jellyfin.", ".source.jellyfin.", ".ui."),
        )
        forbiddenSourceImports.forEach { (path, fragments) ->
            kotlinFiles(path).forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.trimStart().startsWith("import ") && fragments.any(line::contains)) {
                        violations += "${file.relativeTo(projectDir)}:${index + 1}: $line"
                    }
                }
            }
        }

        listOf("ui", "playback").forEach { module ->
            val script = file("$module/build.gradle.kts")
            script.readLines().forEachIndexed { index, line ->
                if (line.contains("project(\":source:jellyfin\")") || line.contains("project(\":source:local\")")) {
                    violations += "${script.relativeTo(projectDir)}:${index + 1}: $line"
                }
            }
        }
        listOf("source/jellyfin", "source/local").forEach { module ->
            val other = if (module.endsWith("jellyfin")) ":source:local" else ":source:jellyfin"
            val script = file("$module/build.gradle.kts")
            script.readLines().forEachIndexed { index, line ->
                if (line.contains("project(\"$other\")")) {
                    violations += "${script.relativeTo(projectDir)}:${index + 1}: $line"
                }
            }
        }

        kotlinFiles("source/api/src/main").forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("Jellyfin", ignoreCase = true) || line.contains("Plex", ignoreCase = true)) {
                    violations += "${file.relativeTo(projectDir)}:${index + 1}: provider name in shared API"
                }
            }
        }

        val providerNamedDeclaration = Regex(
            """\b(data\s+class|enum\s+class|sealed\s+(class|interface)|class|interface|object|fun)\s+\w*(Jellyfin|Plex)\w*""",
            RegexOption.IGNORE_CASE,
        )
        kotlinFiles("core/src/main").forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (providerNamedDeclaration.containsMatchIn(line)) {
                    violations += "${file.relativeTo(projectDir)}:${index + 1}: provider name in shared core declaration"
                }
            }
        }

        check(violations.isEmpty()) {
            "Source-boundary violations:\n${violations.joinToString("\n")}"
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkSourceBoundaries)
    }
}
