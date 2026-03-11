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

## Project structure (current)

```
ntivo/
├── docker-compose.yml       ← Neo4j + Qdrant for local dev
├── src/main/kotlin/
│   ├── Application.kt      ← Ktor server entry point, ContentNegotiation setup
│   ├── Routing.kt           ← HTTP routes (GET /health returns {"status":"ok"})
│   └── SimpleAgent.kt       ← Standalone Koog agent with Gemini (interactive REPL)
├── src/main/resources/
│   ├── application.yaml     ← Ktor config (port 8080, module reference)
│   └── logback.xml
├── build.gradle.kts
├── gradle.properties        ← kotlin 2.3.0, ktor 3.4.0, logback 1.4.14
└── settings.gradle.kts
```

Package: `io.ntivo`. All Kotlin files use this package.

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

## Conventions

- Kotlin code style: JetBrains official (`kotlin.code.style=official` in gradle.properties)
- Data classes use `@Serializable` for JSON responses
- Ktor routes defined as `Application.configureX()` extension functions
- Environment variables for secrets: `NTIVO_GEMINI_API_KEY`, `NTIVO_ANTHROPIC_API_KEY`, `NTIVO_NEO4J_URI`, `NTIVO_NEO4J_PASSWORD`, `NTIVO_QDRANT_URL`
- Secrets loaded from environment, never hardcoded, never committed
- Graph node IDs: `{type}:{orgId}:{sourceId}` e.g. `CODE:acme:BiometricViewModel#authenticate`
- Local dev defaults: Neo4j at `bolt://localhost:7687` (user: `neo4j`, pass: `ntivo_dev_password`), Qdrant at `http://localhost:6333` (gRPC: `localhost:6334`), Neo4j browser at `http://localhost:7474`

## Commands

```bash
./gradlew build                    # Build everything
./gradlew run                      # Start Ktor server on :8080
./gradlew compileKotlin            # Compile check only
curl http://localhost:8080/health   # Verify server is running

# Run the standalone agent (separate Gradle task):
NTIVO_GEMINI_API_KEY="..." ./gradlew runAgent

# Docker services (Neo4j + Qdrant):
docker compose up -d               # Start Neo4j + Qdrant (background)
docker compose down                # Stop services (data preserved)
docker compose down -v             # Stop + wipe all data (fresh start)
docker compose ps                  # Check service status
```

## Current phase

**Phase 1 — Getting comfortable with the stack.** No production deployment. Focus is:

1. ~~Ktor project with GET /health~~ ✅
2. ~~Koog agent talking to Gemini~~ ✅
3. ~~Neo4j + Qdrant running locally via Docker Compose~~ ✅
4. First embedding with `gemini-embedding-001`
5. First Tree-sitter parse of a Kotlin file

Don't build: auth, rate limiting, multi-region, CI/CD, monitoring, admin UI. Not yet.

## What NOT to do

- Don't add Python. Ollama has a REST API — call it from Ktor client if local models are needed.
- Don't add LangChain4j or any non-Koog agent/RAG framework. Koog handles agents AND RAG.
- Don't use TypeScript or React for the web UI — it's Kotlin Multiplatform (Compose for Web).
- Don't share Neo4j sessions or Qdrant collections across orgIds.
- Don't call embedding APIs without specifying a `taskType`.
- Don't add complexity not needed for current phase.
- Don't use Android Studio — this is a backend project, use IntelliJ IDEA.
