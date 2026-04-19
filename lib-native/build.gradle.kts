import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val hostJniDir = layout.buildDirectory.dir("host-jni")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")

val cmakeConfigureHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the CMake host build of jni_cb_term"
    outputs.dir(hostJniDir)
    commandLine(
        "cmake",
        "-S",
        cppSourceDir.asFile.absolutePath,
        "-B",
        hostJniDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val cmakeBuildHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libjni_cb_term for the host JVM"
    dependsOn(cmakeConfigureHost)
    commandLine(
        "cmake",
        "--build",
        hostJniDir.get().asFile.absolutePath,
        "--target",
        "jni_cb_term",
    )
    outputs.dir(hostJniDir)
}

tasks.withType<Test> {
    dependsOn(cmakeBuildHost)
    jvmArgs("-Djava.library.path=${hostJniDir.get().asFile.absolutePath}")
}

dependencies {
    api(project(":lib-intf"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
