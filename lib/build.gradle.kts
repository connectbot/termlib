import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
    alias(libs.plugins.spotless)
    alias(libs.plugins.release)
    `maven-publish`
    signing
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
        singleVariant("debug") {
            withSourcesJar()
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    java {
        target(
            fileTree(".") {
                include("**/*.java")
                exclude("**/build", "**/out")
            },
        )
        removeUnusedImports()
        trimTrailingWhitespace()

        replaceRegex("class-level javadoc indentation fix", "^\\*", " *")
        replaceRegex("method-level javadoc indentation fix", "\t\\*", "\t *")
    }

    kotlinGradle {
        target(
            fileTree(".") {
                include("**/*.gradle.kts")
                exclude("**/build", "**/out")
            },
        )
        ktlint()
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val gitHubUrl = "https://github.com/connectbot/terminal"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "org.connectbot"
                artifactId = "terminal"

                pom {
                    name.set("terminal")
                    description.set("A Jetpack Compose terminal emulator component for Android using libvterm")
                    url.set(gitHubUrl)
                    licenses {
                        license {
                            name.set("Apache 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            name.set("Kenny Root")
                            email.set("kenny@the-b.org")
                        }
                    }
                    scm {
                        connection.set("$gitHubUrl.git")
                        developerConnection.set("$gitHubUrl.git")
                        url.set(gitHubUrl)
                    }
                }
            }
        }
    }

    signing {
        setRequired({
            gradle.taskGraph.hasTask("publish")
        })
        sign(publishing.publications["release"])
    }
}
