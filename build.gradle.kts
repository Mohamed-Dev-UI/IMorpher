// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
}

// Dokka v2 aggregation: the root project depends on the documentable modules and publishes
// one HTML tree. `dokkaGenerate` builds it into build/dokka/ (uploaded by the publish workflow).
dependencies {
    dokka(project(":vector-morph-core"))
    dokka(project(":vector-morph-compose"))
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka"))
        includes.from(layout.projectDirectory.file("README.md"))
    }
}
