plugins {
    kotlin("jvm") version "2.1.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "me.delyfss"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.typesafe:config:1.4.3")

    testImplementation(kotlin("test"))
}


tasks.build {
    dependsOn("shadowJar")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}