plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("workbench.MainKt") }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
