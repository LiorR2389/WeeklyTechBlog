plugins {
    kotlin("jvm") version "1.9.20"
    application
}

group = "com.ainews"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.json:json:20231013")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
}

application {
    mainClass.set("com.ainews.MainKt")
}

kotlin {
    jvmToolchain(17)
}