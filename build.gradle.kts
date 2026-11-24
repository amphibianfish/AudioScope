// Top-level build file for AudioScope.
// Note: AGP 9.0+ includes Built-in Kotlin support by default, so org.jetbrains.kotlin.android is intentionally omitted.
buildscript {
    dependencies {
        // Aligns AGP 9 Built-in Kotlin compiler version with Compose Compiler & KSP
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
