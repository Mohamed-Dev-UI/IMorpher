
This project is `ImageVector Morph`, a Jetpack Compose library for creating vector morphing animations. It compiles `ImageVector` assets into normalized cubic geometry for smooth, code-driven animations without requiring XML.

**Project Layout:**
- `app/`: Contains a demo application showcasing the library's features.
- `vector-morph-compose/`: The main library module providing Composable UI elements.
- `vector-morph-core/`: The core engine module for vector compilation, path matching, and animation planning.
- `build.gradle.kts`, `settings.gradle.kts`: Root-level Gradle build scripts.
- `gradle/libs.versions.toml`: Centralized version and dependency management.

**Build, Test, Run:**
- **Build:** `./gradlew build` (or `./gradlew :vector-morph-compose:build` for just the compose module).
- **Test:**
    - JVM unit tests: `./gradlew :vector-morph-core:testDebugUnitTest` and `./gradlew :vector-morph-compose:testDebugUnitTest`.
    - App unit tests: `./gradlew :app:testDebugUnitTest`.
    - Instrumented tests: `./gradlew :vector-morph-compose:connectedDebugAndroidTest` and `./gradlew :app:connectedDebugAndroidTest`.
- **Run:** Run the `app` configuration in Android Studio to see the demo.

**Conventions & Potential Pitfalls:**
- **Gradle Wrapper:** Use `./gradlew` (or `gradlew.bat` on Windows) for all Gradle commands.
- **Version Catalog:** Dependencies and versions are managed in `gradle/libs.versions.toml`. Use `libs.plugins.<plugin_name>` and `libs.<library_name>` syntax.
- **Compose Compiler:** Ensure the Kotlin Compose compiler plugin is enabled in consuming modules.
- **ImageVector Stability:** Keep `ImageVector`s and animation definitions stable (e.g., as top-level or `remember` values) for caching and performance.
- **Viewport Aspect Ratios:** Use matching aspect ratios for source and target viewports to avoid unwanted stretching.
- **Custom Callbacks:** Keep custom animation callbacks deterministic and lightweight; precompute complex geometry.
- **Publishing:** Publishing to Maven Central involves a one-time setup for credentials and signing keys, then bumping versions and pushing tags. The `.github/workflows/publish.yml` handles CI publishing.
