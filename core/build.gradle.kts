plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
