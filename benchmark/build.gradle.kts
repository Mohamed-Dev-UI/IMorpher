plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.imorpher.vectormorph.benchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the benchmark variant is instrumented: release optimizations plus a signature
        // that lets AndroidX Benchmark report stable, comparable timings.
        testBuildType = "benchmark"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(project(":vector-morph-core"))
    implementation(project(":vector-morph-compose"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.benchmark.junit4)
}
