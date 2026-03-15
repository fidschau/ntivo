# CLAUDE.md — Ntivo

Ntivo is an open source knowledge graph that connects codebases (backend, iOS, Android, web) with project management tools (Jira, Confluence) and observability (Datadog). Engineers query their systems in plain English. Kotlin-native, Gemini-powered, self-hosted from day one.

## Stack

- **Language:** Kotlin. No Python. No TypeScript. All code is Kotlin unless a JVM library literally doesn't exist.
- **AI agents:** Koog 0.6.4 (JetBrains, Apache 2.0) — stateful agent graphs. Koog also provides RAG and embedding modules.
- **LLM:** Gemini via Koog's `simpleGoogleAIExecutor`. Model: `GoogleModels.Gemini2_5Flash` (free tier). Claude/Anthropic is available via `simpleAnthropicExecutor` if needed later.
- **Embedding model:** `gemini-embedding-001` (text-only, 3072 dimensions, 2048 token input). Upgrade path: `gemini-embedding-2-preview` (multimodal, 8192 token input) — but embedding spaces are incompatible, so switching requires re-embedding everything.
- **Always specify task type when embedding:** `RETRIEVAL_DOCUMENT` for indexing, `CODE_RETRIEVAL_QUERY` for code queries, `SEMANTIC_SIMILARITY` for drift detection.
- **Knowledge graph:** Neo4j (Docker in dev, per-tenant `org_{orgId}` database)
- **Vector store:** Qdrant (Docker in dev, per-tenant `chunks_{orgId}` collection)
- **Code parsing:** Tree-sitter (JVM bindings)
- **API:** Ktor with kotlinx.serialization for JSON
- **Web UI:** Kotlin Multiplatform (Compose for Web / Kotlin/Wasm) — not TypeScript, not React
- **Build:** Gradle with Kotlin DSL, JDK 21
- **Serialization:** kotlinx.serialization (already configured with `plugin.serialization`)

## Project structure (multi-module KMP)

```
ntivo/
├── build.gradle.kts              ← Root: all plugin versions (apply false)
├── settings.gradle.kts           ← Includes :shared, :server, :web
├── gradle.properties             ← kotlin 2.3.0, ktor 3.4.0, compose 1.10.2
├── docker-compose.yml            ← Neo4j + Qdrant for local dev
├── shared/                       ← KMP module (jvm + wasmJs)
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/io/ntivo/shared/
│       └── ApiModels.kt          ← All @Serializable request/response models (shared contract)
├── server/                       ← JVM module (Ktor backend)
│   ├── build.gradle.kts          ← depends on :shared
│   └── src/main/
│       ├── kotlin/io/ntivo/
│       │   ├── ApiRouting.kt     ← REST API: /api/chat, /api/embed, /api/store, /api/search, /api/parse
│       │   ├── Application.kt    ← Ktor entry point, ContentNegotiation + CORS
│       │   ├── EmbeddingDemo.kt  ← Gemini embedding + Qdrant demo (seeds 6 snippets)
│       │   ├── GeminiEmbedder.kt ← Custom Embedder with taskType support
│       │   ├── Routing.kt        ← GET /health
│       │   ├── SimpleAgent.kt    ← Standalone Koog agent REPL
│       │   ├── StaticContent.kt  ← Serves Compose for Web production build
│       │   └── TreeSitterDemo.kt ← Tree-sitter parser demo
│       └── resources/
│           ├── application.yaml
│           └── logback.xml
└── web/                          ← KMP module (Compose for Web / Kotlin/Wasm)
    ├── build.gradle.kts          ← wasmJs target, webpack proxy to :8080
    └── src/wasmJsMain/
        ├── kotlin/io/ntivo/web/
        │   ├── Main.kt           ← ComposeViewport entry point
        │   ├── App.kt            ← Root composable (theme + tabs + routing)
        │   ├── theme/NtivoTheme.kt
        │   ├── components/       ← NtivoHeader, NtivoTabs, ResultPanel
        │   ├── screens/          ← HealthScreen, ChatScreen, EmbedScreen, SearchScreen, ParseScreen
        │   └── api/NtivoApiClient.kt ← Ktor client using shared models
        └── resources/index.html  ← Minimal HTML host for Wasm canvas
```

Packages: `io.ntivo` (server), `io.ntivo.shared` (shared models), `io.ntivo.web` (web UI).

## Architecture rules

- **Multi-tenant from day one.** All Neo4j queries scoped to `org_{orgId}` database. All Qdrant operations scoped to `chunks_{orgId}` collection. Never mix org data.
- **Knowledge graph + RAG hybrid.** Graph for relationships (WHY), vector search for semantics (WHAT). Most queries use both.
- **Chunk at function/method level**, not file level. Tree-sitter extracts individual functions.
- **Confidence on every graph edge.** Store `LinkMethod` enum + `confidence: Float` on all code↔ticket edges.
- **Embedding layer must be swappable.** Don't couple core logic to Gemini specifically — abstract behind an interface so Ollama/local models can be swapped in.
- **Incremental indexing.** SHA-256 fingerprint per chunk. Skip re-embedding if unchanged.

## Koog usage

Koog is the ONLY framework for agent orchestration, tool composition, and RAG. Do NOT add LangChain4j, LangChain, or any other agent/RAG framework.

- Agent creation: `AIAgent(promptExecutor = simpleGoogleAIExecutor(key), llmModel = GoogleModels.Gemini2_5Flash, systemPrompt = "...")`
- Agents run with: `agent.run("input")` (suspend function, needs coroutine scope)
- Tool registration: `ToolRegistry { tool(::functionName) }` with `@Tool` and `@LLMDescription` annotations
- Koog includes: agents-core, agents-tools, rag-base, embeddings modules — use these before reaching for external libraries
- Imports: `ai.koog.agents.core.agent.AIAgent`, `ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor`, `ai.koog.prompt.executor.clients.google.GoogleModels`
- Koog's built-in `GoogleLLMClient` does NOT pass `taskType` to the Gemini embedding API. Use our custom `GeminiEmbedder` instead — it implements Koog's `Embedder` interface and adds `taskType` support.
- Qdrant Java client (`io.qdrant:client`) uses gRPC on port 6334. Use `kotlinx-coroutines-guava` for `.await()` on `ListenableFuture` returns.

## Tree-sitter usage

- JVM binding: `io.github.bonede:tree-sitter:0.24.4` + `io.github.bonede:tree-sitter-kotlin:0.3.8.1` (supports x86_64 + aarch64 on macOS/Linux/Windows)
- Package: `org.treesitter` (not `io.github.bonede` — Maven group ≠ Java package)
- Key classes: `TSParser`, `TSNode`, `TSTree`, `TSPoint`, `TreeSitterKotlin`
- Kotlin grammar AST nodes: `function_declaration` (with children: `simple_identifier`, `user_type` for receiver, `function_value_parameters`, `function_body`), `class_declaration` (with `type_identifier`)
- Extension functions: detected by a `user_type` child + `.` child before the function name
- Cleanup: no `close()` needed — uses GC-based cleanup via `CleanerRunner`

## Conventions

- Kotlin code style: JetBrains official (`kotlin.code.style=official` in gradle.properties)
- Data classes use `@Serializable` for JSON responses
- Ktor routes defined as `Application.configureX()` extension functions (`configureRouting()`, `configureApiRouting()`, `configureStaticContent()`)
- Static content served last in Application.module() so explicit routes take priority
- Web UI: Compose for Web (Kotlin/Wasm) in `web/` module. Production build served by Ktor at `/`.
- API models: `@Serializable` data classes in `shared/src/commonMain/kotlin/io/ntivo/shared/ApiModels.kt` — shared between server and web modules
- API endpoints under `/api/` prefix, route handlers in `server/.../ApiRouting.kt`
- Environment variables for secrets: `NTIVO_GEMINI_API_KEY`, `NTIVO_ANTHROPIC_API_KEY`, `NTIVO_NEO4J_URI`, `NTIVO_NEO4J_PASSWORD`, `NTIVO_QDRANT_URL`
- Secrets loaded from environment, never hardcoded, never committed
- Graph node IDs: `{type}:{orgId}:{sourceId}` e.g. `CODE:acme:BiometricViewModel#authenticate`
- Local dev defaults: Neo4j at `bolt://localhost:7687` (user: `neo4j`, pass: `ntivo_dev_password`), Qdrant at `http://localhost:6333` (gRPC: `localhost:6334`), Neo4j browser at `http://localhost:7474`

## Commands

```bash
# Build
./gradlew build                              # Build all modules (shared + server + web)
./gradlew :server:compileKotlin              # Compile server only
./gradlew :web:compileKotlinWasmJs           # Compile web module only
./gradlew :shared:build                      # Build shared module (jvm + wasmJs)

# Run (production — single command, builds web + serves at :8080)
./gradlew :server:run                        # Builds Compose for Web → serves at http://localhost:8080

# Run (development — two terminals, hot reload)
./gradlew :server:run                        # Terminal 1: Ktor server on :8080
./gradlew :web:wasmJsBrowserDevelopmentRun   # Terminal 2: Compose for Web on :8081 (hot reload)
# Webpack proxy routes /api/* and /health from :8081 → :8080

# Run (dev script — manages both)
./dev.sh                                     # Full stack: Docker + server + web (hot reload)
./dev.sh --prod                              # Build web + run server (single process)
./dev.sh --quick                             # Server + web only, no Docker
./dev.sh --server                            # Server only

# Health check
curl http://localhost:8080/health

# Standalone demos (Gradle tasks in server module)
NTIVO_GEMINI_API_KEY="..." ./gradlew :server:runAgent
NTIVO_GEMINI_API_KEY="..." ./gradlew :server:runEmbeddingDemo
./gradlew :server:runTreeSitterDemo

# Docker services (Neo4j + Qdrant)
docker compose up -d               # Start Neo4j + Qdrant (background)
docker compose down                # Stop services (data preserved)
docker compose down -v             # Stop + wipe all data (fresh start)
docker compose ps                  # Check service status
```

## Current phase

**Phase 1 — Getting comfortable with the stack.** ✅ COMPLETE. All 5 steps done:

1. ~~Ktor project with GET /health~~ ✅
2. ~~Koog agent talking to Gemini~~ ✅
3. ~~Neo4j + Qdrant running locally via Docker Compose~~ ✅
4. ~~First embedding with `gemini-embedding-001`~~ ✅
5. ~~First Tree-sitter parse of a Kotlin file~~ ✅

**Bonus: Web Dev Console** — Compose for Web (Kotlin/Wasm) dev console with dark theme, 5 fully functional tabs (Health, Agent Chat, Embed + Store, Vector Search, Tree-sitter Parse). Served at `http://localhost:8080` via production build. API endpoints under `/api/` for all Phase 1 demos.

Don't build: auth, rate limiting, multi-region, CI/CD, monitoring, admin UI. Not yet.

## What NOT to do

- Don't add Python. Ollama has a REST API — call it from Ktor client if local models are needed.
- Don't add LangChain4j or any non-Koog agent/RAG framework. Koog handles agents AND RAG.
- Don't use TypeScript or React for the web UI — it's Kotlin Multiplatform (Compose for Web).
- Don't share Neo4j sessions or Qdrant collections across orgIds.
- Don't call embedding APIs without specifying a `taskType`.
- Don't add complexity not needed for current phase.
- Don't use Android Studio — this is a backend project, use IntelliJ IDEA.
