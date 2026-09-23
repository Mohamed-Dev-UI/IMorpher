plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

val releaseVersion = providers.gradleProperty("VERSION_NAME").get()

mavenPublishing {
    coordinates("io.github.mohamed-dev-ui", "vector-morph-core", releaseVersion)
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("ImageVector Morph Core")
        description.set("Geometry normalization, path matching, and animation timelines for ImageVector morphing")
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
    namespace = "com.imorpher.vectormorph.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.animation)
    testImplementation(libs.junit)
}
