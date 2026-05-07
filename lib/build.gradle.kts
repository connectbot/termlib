import com.vanniktech.maven.publish.DeploymentValidation
import org.jetbrains.dokka.gradle.formats.DokkaFormatPlugin
import org.jetbrains.dokka.gradle.internal.InternalDokkaGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.publish)
    alias(libs.plugins.metalava)
    alias(libs.plugins.dokka)
}

@OptIn(InternalDokkaGradlePluginApi::class)
abstract class DokkaMarkdownPlugin : DokkaFormatPlugin(formatName = "markdown") {
    override fun DokkaFormatPlugin.DokkaFormatPluginContext.configure() {
        project.dependencies {
            dokkaPlugin(dokka("gfm-plugin"))
            formatDependencies.dokkaPublicationPluginClasspathApiOnly.dependencies.addLater(
                dokka("gfm-template-processing-plugin"),
            )
        }
    }
}

apply<DokkaMarkdownPlugin>()

val hostJniDir = layout.buildDirectory.dir("host-jni")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")

val cmakeConfigureHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the CMake host build of jni_cb_term"
    inputs.dir(cppSourceDir)
    outputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "-S",
        cppSourceDir.asFile.absolutePath,
        "-B",
        hostJniDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Debug",
    )
}

val cmakeBuildHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libjni_cb_term for the host JVM"
    dependsOn(cmakeConfigureHost)
    inputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "--build",
        hostJniDir.get().asFile.absolutePath,
        "--target",
        "jni_cb_term",
    )
    outputs.dir(hostJniDir)
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
            debugSymbolLevel = "full"
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

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { testTask ->
                testTask.dependsOn(cmakeBuildHost)
                testTask.jvmArgs("-Djava.library.path=${hostJniDir.get().asFile.absolutePath}")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.ui)

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
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.compose)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.mockk)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(file("src/test/roborazzi"))
}

val gitHubUrl = "https://github.com/connectbot/termlib"

metalava {
    additionalSourceSets.from(file("src/main/java"))
}

dokka {
    moduleName.set("ConnectBot Terminal")

    dokkaSourceSets.configureEach {
        sourceLink {
            includes.from("README.md")
            localDirectory.set(file("./"))
            remoteUrl.set(uri("$gitHubUrl/blob/main"))
            remoteLineSuffix.set("#L")
        }
    }

    pluginsConfiguration {
        html {
            footerMessage.set("Copyright Kenny Root")
            templatesDir.set(file("dokka/templates"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    signAllPublications()

    coordinates(groupId = "org.connectbot", artifactId = "termlib")

    pom {
        name.set("termlib")
        description.set("ConnectBot's terminal emulator Android Compose component using libvterm")
        inceptionYear.set("2025")
        url.set(gitHubUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kruton")
                name.set("Kenny Root")
                url.set("https://github.com/kruton/")
            }
        }
        scm {
            connection.set("scm:git:$gitHubUrl.git")
            developerConnection.set("$gitHubUrl.git")
            url.set(gitHubUrl)
        }
    }
}
