// Root build script. Plugins are declared here (apply false) and applied in modules.
// AGP 9 provides built-in Kotlin support, so org.jetbrains.kotlin.android is intentionally not
// declared here (applying it errors out under AGP >= 9.0).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
