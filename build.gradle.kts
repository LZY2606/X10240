plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-netty:3.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("app.MainKt") }

tasks.test { useJUnitPlatform() }
