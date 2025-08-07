plugins {
    kotlin("jvm") version "1.9.20"
    application
}

group = "com.ainews"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io") // JitPack for Telegram libraries
}

dependencies {
    // EXISTING dependencies (unchanged)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.json:json:20231013")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    
    // NEW: Simple Telegram API (more reliable than TDLight)
    implementation("com.github.pengrad:java-telegram-bot-api:7.2.1")
    implementation("org.slf4j:slf4j-simple:1.7.36") // For logging
}

// EXISTING application task (unchanged)
application {
    mainClass.set("com.ainews.MainKt")
}

// NEW: Telegram scraper task
task("runTelegram", JavaExec::class) {
    group = "application"
    description = "Run the Telegram live news scraper"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ainews.TelegramMainKt")
    
    // Add JVM options for TDLight
    jvmArgs("-Djava.library.path=./natives")
}

kotlin {
    jvmToolchain(17)
}