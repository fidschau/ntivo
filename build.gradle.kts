val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.4.0"
}

group = "io.ntivo"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Koog AI agent framework
    implementation("ai.koog:koog-agents:0.6.4")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

// Separate task to run the standalone Koog agent (without starting the Ktor server)
tasks.register<JavaExec>("runAgent") {
    description = "Run the standalone Koog agent REPL"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.ntivo.SimpleAgentKt"
    standardInput = System.`in`
}
