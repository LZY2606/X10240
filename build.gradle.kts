plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("workbench.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
