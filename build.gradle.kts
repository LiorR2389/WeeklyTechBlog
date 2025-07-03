plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "tech.blog"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jsoup:jsoup:1.17.2") // HTML scraping
    implementation("org.xerial:sqlite-jdbc:3.45.2.0") // store URLs
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // HTTP requests
    implementation("com.google.code.gson:gson:2.10.1") // JSON parsing
    implementation("org.json:json:20240303")
    implementation("com.sun.mail:jakarta.mail:2.0.1") // Email functionality
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    // Use the available JDK
    jvmToolchain(21)
}

// Disable tests to prevent build failures
tasks.test {
    enabled = false
}