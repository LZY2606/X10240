plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "gsb"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.6.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.6.0")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.6.0")
    testImplementation("org.junit.platform:junit-platform-launcher:1.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-engine:1.14.4")
}

application {
    mainClass.set("gsb.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateCase") {
    group = "case"
    description = "Regenerates the bundled offline domain case pack under cases/ and resources."
    mainClass.set("gsb.case.CaseGeneratorKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
