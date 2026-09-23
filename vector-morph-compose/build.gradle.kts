plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

val releaseVersion = providers.gradleProperty("VERSION_NAME").get()

mavenPublishing {
    coordinates("io.github.mohamed-dev-ui", "vector-morph-compose", releaseVersion)
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("ImageVector Morph Compose")
        description.set("ImageVector morphing and vector animation for Jetpack Compose")
        url.set("https://github.com/Mohamed-dev-ui/IMorpher")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("mohamed-dev-ui")
                name.set("Mohamed-dev-ui")
                url.set("https://github.com/Mohamed-dev-ui")
            }
        }
        scm {
            url.set("https://github.com/Mohamed-dev-ui/IMorpher")
            connection.set("scm:git:git://github.com/Mohamed-dev-ui/IMorpher.git")
            developerConnection.set("scm:git:ssh://git@github.com/Mohamed-dev-ui/IMorpher.git")
        }
    }
}

android {
    namespace = "com.imorpher.vectormorph.compose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":vector-morph-core"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.text)
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
