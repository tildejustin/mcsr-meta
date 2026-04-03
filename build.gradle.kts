plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "dev.tildejustin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // https://mvnrepository.com/artifact/org.eclipse.jgit/org.eclipse.jgit
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
    // https://mvnrepository.com/artifact/io.github.z4kn4fein/semver
    implementation("io.github.z4kn4fein:semver:3.0.0")
}

kotlin {
    jvmToolchain(25)
}
