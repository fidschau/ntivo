val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
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
    implementation(project(":shared"))

    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-sse")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Koog AI agent framework
    implementation("ai.koog:koog-agents:1.3.0")
    // Gemini support is a separate beta module in Koog 1.x (not in the stable koog-agents bundle)
    implementation("ai.koog:prompt-executor-google-client:1.3.0-beta")

    // Ktor HTTP client (for Gemini embedding REST API)
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")

    // Qdrant vector database client (gRPC-based)
    implementation("io.qdrant:client:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")

    // Tree-sitter code parser (supports x86_64 + aarch64 on macOS/Linux/Windows)
    implementation("io.github.bonede:tree-sitter:0.24.4")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-swift:0.5.0")

    // Git clone of GitHub sources
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")

    // Neo4j graph database driver (Bolt protocol)
    implementation("org.neo4j.driver:neo4j-java-driver:6.3.0")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Copy the Compose for Web production build into server resources so a single
// `./gradlew :server:run` serves both API and UI.
val copyWebDist by tasks.registering(Copy::class) {
    description = "Copy Compose for Web production build to server static resources"
    group = "build"
    dependsOn(":web:wasmJsBrowserDistribution")
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    into(layout.buildDirectory.dir("resources/main/static/compose"))
}

tasks.named("processResources") {
    dependsOn(copyWebDist)
}

