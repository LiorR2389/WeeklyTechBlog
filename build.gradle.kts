plugins {
    kotlin("jvm") version "2.1.20"
}

group = "tech.blog"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}