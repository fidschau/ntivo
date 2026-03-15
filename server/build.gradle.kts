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

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Koog AI agent framework
    implementation("ai.koog:koog-agents:0.6.4")

    // Ktor HTTP client (for Gemini embedding REST API)
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")

    // Qdrant vector database client (gRPC-based)
    implementation("io.qdrant:client:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.1")

    // Tree-sitter code parser (supports x86_64 + aarch64 on macOS/Linux/Windows)
    implementation("io.github.bonede:tree-sitter:0.24.4")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

// Copy the Compose for Web production build into server resources so a single
// `./gradlew :server:run` serves both API and UI.
val copyWebDist by tasks.registering(Copy::class) {
    description = "Copy Compose for Web production build to server static resources"
    group = "build"
    dependsOn(":web:wasmJsBrowserProductionWebpack")
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    into(layout.buildDirectory.dir("resources/main/static/compose"))
}

tasks.named("processResources") {
    dependsOn(copyWebDist)
}

// Separate task to run the standalone Koog agent (without starting the Ktor server)
tasks.register<JavaExec>("runAgent") {
    description = "Run the standalone Koog agent REPL"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.ntivo.SimpleAgentKt"
    standardInput = System.`in`
}

// Run the embedding demo (embeds code, stores in Qdrant, queries it back)
tasks.register<JavaExec>("runEmbeddingDemo") {
    description = "Run the Gemini embedding + Qdrant demo"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.ntivo.EmbeddingDemoKt"
}

// Run the Tree-sitter parsing demo (parses Kotlin source, extracts functions)
tasks.register<JavaExec>("runTreeSitterDemo") {
    description = "Run the Tree-sitter Kotlin parsing demo"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.ntivo.TreeSitterDemoKt"
}
