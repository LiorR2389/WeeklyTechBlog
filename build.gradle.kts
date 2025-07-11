plugins {
    kotlin("jvm") version "1.9.10"
    application
}

group = "com.ainews"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin standard library
    implementation(kotlin("stdlib"))

    // OkHttp for HTTP requests (IMPORTANT: version 4.x for toRequestBody extension)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSoup for HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // JSON processing
    implementation("org.json:json:20231013")

    // Jakarta Mail for email (if needed later)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
}

application {
    mainClass.set("MainKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}