import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
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

mavenPublishing {
    coordinates(groupId = "org.connectbot", artifactId = "termlib-intf")

    pom {
        name.set("termlib-intf")
        description.set("ConnectBot terminal library interfaces")
        inceptionYear.set("2026")
    }
}
