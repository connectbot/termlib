// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.release)
}

spotless {
    ratchetFrom = "origin/main"

    kotlinGradle {
        target(
            fileTree(".") {
                include("**/*.gradle.kts")
                exclude("**/build", "**/out")
            },
        )
        ktlint("1.8.0")
    }

    kotlin {
        target(
            fileTree(".") {
                include("*/src/**/*.kt")
                exclude("**/build", "**/out")
            },
        )
        ktlint("1.8.0")
    }

    format("xml") {
        target(
            fileTree(".") {
                include("config/**/*.xml", "lib/**/*.xml", "test-app/**/*.xml")
                exclude("**/build", "**/out")
            },
        )
    }

    format("misc") {
        target("**/.gitignore")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
