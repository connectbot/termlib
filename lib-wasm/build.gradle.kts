import at.released.wasm2class.InterpreterFallback
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wasm2class)
    id("termlib-publish")
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

// -------------------------------------------------------------------------
// WASI build: compile libvterm + vterm_wasm.c → libvterm.wasm
//
// Prerequisites: wasi-sdk installed at /opt/wasi-sdk (or WASI_SDK_PREFIX env var).
// Run manually or as part of CI before the Gradle build:
//
//   ./gradlew :lib-wasm:buildWasm
//
// The produced wasm is checked in at src/main/resources/libvterm.wasm so that
// downstream consumers don't need wasi-sdk installed.
// -------------------------------------------------------------------------
val wasiSdkPrefix = System.getenv("WASI_SDK_PREFIX") ?: "/opt/wasi-sdk"
val wasmBuildDir = layout.buildDirectory.dir("wasm-cmake")
val wasmOutputFile = layout.projectDirectory.file("src/main/resources/libvterm.wasm")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")

val cmakeConfigureWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure CMake WASI build for libvterm.wasm"
    outputs.dir(wasmBuildDir)
    commandLine(
        "cmake",
        "-S",
        cppSourceDir.asFile.absolutePath,
        "-B",
        wasmBuildDir.get().asFile.absolutePath,
        "-DCMAKE_TOOLCHAIN_FILE=$wasiSdkPrefix/share/cmake/wasi-sdk-p1.cmake",
        "-DWASI_SDK_PREFIX=$wasiSdkPrefix",
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val buildWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libvterm.wasm using WASI SDK"
    dependsOn(cmakeConfigureWasm)
    commandLine(
        "cmake",
        "--build",
        wasmBuildDir.get().asFile.absolutePath,
        "--target",
        "vterm_wasm",
    )
    outputs.file(wasmOutputFile)
    doLast {
        val built = wasmBuildDir.get().file("vterm_wasm.wasm").asFile
        if (built.exists()) {
            built.copyTo(wasmOutputFile.asFile, overwrite = true)
        }
    }
}

// -------------------------------------------------------------------------
// wasm2class AOT compilation
// Converts the checked-in libvterm.wasm to JVM bytecode at Gradle build time.
// No wasi-sdk needed for this step — only the .wasm file is required.
// -------------------------------------------------------------------------
wasm2class {
    targetPackage = "org.connectbot.terminal.wasm.generated"
    modules {
        create("LibVterm") {
            wasm = wasmOutputFile.asFile
            // Large functions in libvterm may exceed JVM method size limit;
            // fail the build when that happens
            interpreterFallback = InterpreterFallback.FAIL
        }
    }
}

dependencies {
    compileOnly(project(":lib-intf"))
    implementation(libs.chicory.wasi)
    compileOnly(project(":lib-native"))
    compileOnly(libs.robolectric)
    annotationProcessor(libs.robolectric)

    testImplementation(project(":lib-intf"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)

    // Override the build-time Chicory compiler bundled with wasm2class (1.5.1) to match
    // the runtime version. The plugin uses defaultDependencies, so adding our own dependency
    // to the chicoryCompiler configuration before resolution takes precedence.
    add("chicoryCompiler", libs.chicory.buildTimeCompiler)
}

mavenPublishing {
    coordinates(groupId = "org.connectbot", artifactId = "termlib-host")

    pom {
        name.set("termlib-host")
        description.set("Robolectric host-testing support for termlib using a WASM backend (no native .so required)")
        inceptionYear.set("2026")
    }
}
