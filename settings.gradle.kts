// This block configures where Gradle looks for plugins.
pluginManagement {
    repositories {
        // Specifies the Google Maven repository for Android plugins.
        google {
            content {
                // Includes plugins from specific Google and Android groups.
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Specifies the Maven Central repository for general-purpose plugins.
        mavenCentral()
        // Specifies the Gradle Plugin Portal for community plugins.
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
// This block configures where Gradle looks for project dependencies.
dependencyResolutionManagement {
    // Enforces that all repositories are defined in this block, not in individual build.gradle files.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Specifies the Google Maven repository for Android libraries.
        google()
        // Specifies the Maven Central repository for general-purpose libraries.
        mavenCentral()
    }
}

// Sets the name of the root project.
rootProject.name = "PageVox"
// Includes the 'app' module in the project.
include(":app")
