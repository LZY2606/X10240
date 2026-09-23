plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.1.3")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("workbench.MainKt") }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
