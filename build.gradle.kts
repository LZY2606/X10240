plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "org.chrono"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"
val logbackVersion = "1.5.18"
val sqliteVersion = "3.53.4.0"
val junitVersion = "5.11.4"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.chrono.workbench.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    jvmToolchain(17)
}
