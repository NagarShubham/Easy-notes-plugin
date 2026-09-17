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
        // Build against the locally installed Android Studio so the build matches
        // the IDE on this machine and avoids downloading a large distribution.
        local("/Applications/Android Studio.app/Contents")

        // Kotlin plugin is required because this plugin is written in Kotlin.
        bundledPlugin("org.jetbrains.kotlin")
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
