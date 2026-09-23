plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "org.chronobench"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.chronobench.web.MainKt")
}

val ktorVersion = "3.0.3"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

kotlin {
    jvmToolchain(17)
}


tasks.register<JavaExec>("genCurves") {
    group = "verification"
    description = "Regenerate the bundled offline calibration curve CSVs"
    mainClass.set("org.chronobench.tool.GenCurvesKt")
    classpath = sourceSets.main.get().runtimeClasspath
}


tasks.register<JavaExec>("explore") {
    group = "verification"
    mainClass.set("org.chronobench.tool.ExploreKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
