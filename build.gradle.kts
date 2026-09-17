plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.example.notes"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // JSON (de)serialization for import/export. Bundled into the plugin jar.
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        // Build against a locally installed IDE. The path is configurable so the
        // build is portable across machines and CI:
        //   - `-PlocalIdePath=/path/to/IDE.app/Contents`, or
        //   - the LOCAL_IDE_PATH environment variable, or
        //   - the conventional macOS Android Studio location (default below).
        // For CI without a local IDE, swap this for a downloadable target, e.g.:
        //   androidStudio("2024.3.1.14")  // or intellijIdeaCommunity("2024.3")
        val localIdePath = providers.gradleProperty("localIdePath")
            .orElse(providers.environmentVariable("LOCAL_IDE_PATH"))
            .getOrElse("/Applications/Android Studio.app/Contents")
        local(localIdePath)

        // Kotlin plugin is required because this plugin is written in Kotlin.
        bundledPlugin("org.jetbrains.kotlin")
    }

    // Lightweight unit tests for the pure logic (model / io / service).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
