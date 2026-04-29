import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
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

val libNativeHostJniDir = project(":lib-native").layout.buildDirectory.dir("host-jni")

tasks.named("jmh") {
    dependsOn(project(":lib-native").tasks.named("cmakeBuildHost"))
}

jmh {
    warmupIterations = 3
    iterations = 5
    fork = 1
    benchmarkMode = listOf("thrpt", "avgt")
    timeUnit = "ms"
    resultFormat = "JSON"
    jvmArgs = listOf("-Djava.library.path=${libNativeHostJniDir.get().asFile.absolutePath}")
}

dependencies {
    jmh(project(":lib-native"))
    jmh(project(":lib-wasm"))
}
