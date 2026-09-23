plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-netty:3.2.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.2.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("bench.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
