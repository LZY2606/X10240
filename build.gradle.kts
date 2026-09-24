plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("workbench.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.2.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.2.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.2.3")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("test-tmp").get().asFile.absolutePath)
}

kotlin {
    jvmToolchain(17)
}
