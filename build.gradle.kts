// Top-level build file where you can add configuration options common to all sub-projects/modules.
// This file is the entry point for the Gradle build process for the entire project.
plugins {
    // Declares the Android Application plugin, but does not apply it.
    // This makes the plugin available to sub-projects (like the 'app' module).
    alias(libs.plugins.android.application) apply false

    // Declares the Kotlin Android plugin, but does not apply it.
    // This makes the plugin available for use in Android modules.
    alias(libs.plugins.kotlin.android) apply false

    // Declares the Kotlin Compose plugin, but does not apply it.
    // This is necessary for modules that use Jetpack Compose.
    alias(libs.plugins.kotlin.compose) apply false
}