plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.snagar.easynotes"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // No third-party runtime dependencies: JSON import/export uses a small
    // in-house reader/writer (com.snagar.easynotes.io.Json), keeping the plugin
    // jar tiny and free of bundled libraries.

    intellijPlatform {
        // Pick the target IDE:
        //  - On CI (no local IDE install) build against a downloadable IntelliJ
        //    IDEA Community that matches our sinceBuild. Override the version with
        //    `-PplatformVersion=...`.
        //  - Locally, build against the installed Android Studio for an accurate
        //    sandbox. The path is portable across macOS, Windows and Linux via:
        //      `-PlocalIdePath=/path/to/IDE`, the LOCAL_IDE_PATH env var, or the
        //      conventional per-OS Android Studio location (default below).
        if (providers.environmentVariable("CI").isPresent) {
            intellijIdeaCommunity(providers.gradleProperty("platformVersion").getOrElse("2024.2"))
        } else {
            val osName = System.getProperty("os.name").lowercase()
            val defaultIdePath = when {
                osName.contains("mac") -> "/Applications/Android Studio.app/Contents"
                osName.contains("win") -> "C:\\Program Files\\Android\\Android Studio"
                else -> "/opt/android-studio" // common Linux install location
            }
            val localIdePath = providers.gradleProperty("localIdePath")
                .orElse(providers.environmentVariable("LOCAL_IDE_PATH"))
                .getOrElse(defaultIdePath)
            local(localIdePath)
        }

        pluginVerifier()
    }

    // Lightweight unit tests for the pure logic (model / io / service).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // UiDataProvider (used by NotesPanel) exists from 2024.2 (242).
            sinceBuild = "242"
            // No hard upper bound so the plugin keeps working on newer IDEs.
            untilBuild = provider { null }
        }
    }
    // Small plugin with no searchable settings: skip the extra build step.
    buildSearchableOptions = false

    // This plugin is pure Kotlin with no .form UI files and no @NotNull byte-code
    // instrumentation, so the `instrumentCode` step is unnecessary. Disabling it
    // also drops the `java-compiler-ant-tasks` download (a JetBrains CDN artifact
    // that can fail to resolve on restricted networks), so the build is faster
    // and more reliable.
    instrumentCode = false

    // `verifyPlugin` checks binary/API compatibility against real IDE builds.
    // `recommended()` selects the IDEs that match our since/until range.
    pluginVerification {
        ides {
            recommended()
        }
    }

    // Marketplace plugin signing. `signPlugin` runs automatically before
    // `publishPlugin` when these secrets are provided (via env vars in CI or
    // locally); otherwise it is skipped.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Deploys to the JetBrains Marketplace via `publishPlugin`. The token comes
    // from your Marketplace profile (My Tokens) and must never be committed.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Derive the release channel from the version suffix: e.g. 1.0.0-beta.1
        // publishes to the "beta" channel; a plain 1.0.0 goes to "default".
        channels = listOf(
            version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" }
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    exclude("**/.DS_Store")
}

tasks.withType<Jar>().configureEach {
    exclude("**/.DS_Store")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
