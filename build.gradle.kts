plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "me.delyfss"
val envVersion = System.getenv("VERSION")?.takeIf { it.isNotBlank() }
version = envVersion ?: "1.6"

repositories {
    mavenCentral()
    maven("https://repo.nekroplex.com/releases")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    implementation(kotlin("reflect"))
    implementation("gg.aquatic:QuickMiniMessage:26.0.3")
    implementation("net.kyori:adventure-text-minimessage:4.25.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.25.0")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("io.lettuce:lettuce-core:6.4.0.RELEASE")

    // JDBC drivers: SQLite is bundled (runtime); MySQL/MariaDB are brought in by
    // consuming plugins so we do not force a specific driver version.
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testImplementation("net.kyori:adventure-text-serializer-gson:4.25.0")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("cocal")
    // Relocate shaded dependencies so other plugins shipping different versions
    // of HikariCP / Lettuce / Netty / Typesafe Config cannot clash with cocal.
    relocate("com.zaxxer.hikari", "me.delyfss.cocal.shaded.hikari")
    relocate("io.lettuce", "me.delyfss.cocal.shaded.lettuce")
    relocate("io.netty", "me.delyfss.cocal.shaded.netty")
    relocate("com.typesafe.config", "me.delyfss.cocal.shaded.typesafe")
    mergeServiceFiles()
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Opt in to the future default (KT-73255): annotations on data-class
        // constructor parameters apply to both the parameter AND the property,
        // which is what cocal's reflection loader already assumes.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
