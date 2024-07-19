plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "dev.tildejustin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // https://mvnrepository.com/artifact/org.eclipse.jgit/org.eclipse.jgit
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    // https://mvnrepository.com/artifact/io.github.z4kn4fein/semver
    implementation("io.github.z4kn4fein:semver:2.0.0")
}

kotlin {
    jvmToolchain(21)
}
