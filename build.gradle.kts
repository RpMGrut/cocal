plugins {
    kotlin("jvm") version "2.1.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "me.delyfss"
val envVersion = System.getenv("VERSION")?.takeIf { it.isNotBlank() }
version = envVersion ?: "1.4"

repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    implementation(kotlin("reflect"))
    implementation("net.kyori:adventure-text-minimessage:4.25.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.25.0")

    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
