plugins {
    id("com.android.test")
}

android {
    namespace = "org.connectbot.terminal.benchmark"
    compileSdk = 37
    targetProjectPath = ":test-app"
    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // Allow an updated official processor when a newer Android trace format
    // cannot be parsed by the binary bundled with AndroidX Benchmark.
    providers.gradleProperty("benchmarkTraceProcessorAssets").orNull?.let {
        sourceSets.getByName("benchmark").assets.srcDir(it)
    }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
