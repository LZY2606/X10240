plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "gsb"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.2.4"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

application {
    mainClass.set("gsb.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    from(rootDir.resolve("samples")) {
        into("samples")
    }
}

kotlin {
    jvmToolchain(17)
}
