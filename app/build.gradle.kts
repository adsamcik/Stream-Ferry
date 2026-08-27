import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so the standalone org.jetbrains.kotlin.android plugin is
    // no longer applied (it errors out under AGP >= 9.0). The Compose and serialization Kotlin
    // compiler plugins are still required and attach to AGP's built-in Kotlin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

private val strictSemVer = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

private fun versionCodeFor(version: String): Int {
    val match = strictSemVer.matchEntire(version)
        ?: error("versionName must be MAJOR.MINOR.PATCH without leading zeroes (was: $version)")
    val (majorText, minorText, patchText) = match.destructured
    val major = majorText.toLong()
    val minor = minorText.toLong()
    val patch = patchText.toLong()
    require(minor < 1_000 && patch < 1_000) {
        "versionName minor and patch must be below 1000 (was: $version)"
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    require(code in 1L..2_100_000_000L) {
        "versionName produces an invalid Android versionCode (was: $version)"
    }
    return code.toInt()
}

val resolvedVersionName = providers.gradleProperty("versionName").get()
val resolvedVersionCode = versionCodeFor(resolvedVersionName)
val demoEnvironmentEnabled = providers.gradleProperty("demoEnvironment")
    .map { value -> value.toBooleanStrictOrNull() ?: error("demoEnvironment must be true or false") }
    .getOrElse(false)
val demoRendererUrl = providers.gradleProperty("demoRendererUrl")
    .getOrElse("http://10.0.2.2:8097/device.xml")
val quotedDemoRendererUrl = "\"" + demoRendererUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

android {
    namespace = "com.adsamcik.streamferry"
    // compileSdk 37 = Android 17. Platform + build-tools 37 are installed on the build host.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adsamcik.streamferry"
        minSdk = 34          // Android 14
        targetSdk = 37       // Android 17
        // This is the sole version-code calculation, shared by Android Studio and CI.
        // CI supplies only a validated vMAJOR.MINOR.PATCH versionName.
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("boolean", "DEMO_ENVIRONMENT", "false")
        buildConfigField("String", "DEMO_RENDERER_URL", "\"\"")
    }

    signingConfigs {
        // Release signing is supplied via a non-committed keystore.properties (never in VCS).
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            create("release") {
                val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "DEMO_ENVIRONMENT", demoEnvironmentEnabled.toString())
            buildConfigField("String", "DEMO_RENDERER_URL", quotedDemoRendererUrl)
            // Debug-only network_security_config enables a user trust anchor for LAN testing.
            // It must NEVER ship in release (see src/release manifest + docs/NETWORK_SECURITY.md).
        }
        release {
            isMinifyEnabled = true            // R8
            isShrinkResources = true          // resource shrinking
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // A developer may build locally without a release keystore, but the public-release
            // workflow always requires the real key. Debug fallback is intentionally local-only.
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":source:api"))
    implementation(project(":source:local"))
    implementation(project(":source:jellyfin"))
    implementation(project(":playback"))
    implementation(project(":ui"))

    // --- AndroidX / Compose ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Google Cast Sender SDK ---
    implementation(libs.play.services.cast.framework)

    // --- Networking / serialization ---
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- Image loading (in-app posters; shares the app OkHttp client, header auth, memory-only cache) ---
    implementation(libs.coil.compose)

    // --- On-device hardware video transcoding (AndroidX Media3 Transformer, from google()) ---
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)

    // --- Unit tests ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)

}
