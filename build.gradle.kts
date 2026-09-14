plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "dev.tildejustin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // https://mvnrepository.com/artifact/org.eclipse.jgit/org.eclipse.jgit
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    // https://mvnrepository.com/artifact/io.github.z4kn4fein/semver
    implementation("io.github.z4kn4fein:semver:3.1.0")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("dev.tildejustin.mcsr_meta.MainKt")
}