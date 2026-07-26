import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so the standalone org.jetbrains.kotlin.android plugin is
    // no longer applied (it errors out under AGP >= 9.0). The Compose and serialization Kotlin
    // compiler plugins are still required and attach to AGP's built-in Kotlin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsamcik.streamferry"
    // compileSdk 37 = Android 17. Platform + build-tools 37 are installed on the build host.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adsamcik.streamferry"
        minSdk = 34          // Android 14
        targetSdk = 37       // Android 17
        // Local builds keep the checked-in version. The release workflow supplies both values
        // from its validated v<major>.<minor>.<patch> tag so every Play upload gets a new,
        // monotonically increasing versionCode.
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 3000
        versionName = providers.gradleProperty("versionName").orNull ?: "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
    // --- AndroidX / Compose ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Google Cast Sender SDK ---
    implementation(libs.play.services.cast.framework)

    // --- Jellyfin official Kotlin SDK (LGPL-3.0) ---
    implementation(libs.jellyfin.core)

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

    // --- Instrumented / UI tests ---
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
