# Ntivo

> Open-source, Kotlin-native codebase intelligence. Connects code, Jira, Confluence,
> and Datadog into a knowledge graph — ask plain English questions about your systems
> end to end.

---

## What is Ntivo?

Engineers waste hours every week tracing context across siloed tools. A Datadog alert
fires — you manually find the relevant code, check Jira for history, guess at mobile
impact. A new engineer joins — it takes months to understand how features work end to end.

Ntivo connects the **why** (Jira, Confluence), the **what** (your code across every
platform), and the **what's happening** (Datadog) into a single queryable knowledge graph.

Ask questions like:
- *"What's the difference between the iOS and Android implementation of biometric auth?"*
- *"Which Jira ticket drove this change, and what was the reasoning?"*
- *"Which mobile clients are affected if this backend endpoint changes?"*
- *"Show me everything related to this Datadog alert — code, ticket, spec, blast radius."*

---

## Status

🟢 **Phase 1 complete.** All foundational pieces are in place: Ktor server, Koog AI agent with Gemini, Gemini embeddings with Qdrant vector storage, Tree-sitter code parsing, and a web dev console for visual testing.

Follow along on [Substack](#) for progress updates and technical write-ups.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin (primary) |
| Backend | Ktor |
| AI Agents | [Koog](https://github.com/JetBrains/koog) (JetBrains OSS, Apache 2.0) |
| Knowledge Graph | Neo4j |
| Vector Store | Qdrant |
| Code Parsing | Tree-sitter (JVM bindings) |
| Embeddings | Gemini (`gemini-embedding-001`, upgrade path to multimodal `gemini-embedding-2-preview`) |
| Web UI | Kotlin Multiplatform (Compose for Web / Kotlin/Wasm) |
| Build | Gradle (Kotlin DSL) |

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   Ntivo API (Ktor)               │
├────────┬────────┬───────────┬───────────────────┤
│ Ingest │ Search │ Graph     │ Agent (Koog)      │
│        │        │ Traversal │                   │
├────────┴────────┴───────────┴───────────────────┤
│   Neo4j (relationships)  │  Qdrant (embeddings) │
└──────────────────────────┴──────────────────────┘

Sources:  Git repos → Tree-sitter parsing → embeddings + graph nodes
          Jira → ticket nodes + code↔ticket edges
          Confluence → spec nodes + ticket↔spec edges
          Datadog → alert nodes + alert↔code edges

Clients:  Web UI (KMP/Compose for Web), MCP Server, REST API
```

---

## Running Locally

### Prerequisites

- JDK 21+
- Docker Desktop (for Neo4j and Qdrant)
- Gemini API key (free at [aistudio.google.com](https://aistudio.google.com)) for AI features

### Quick Start

```bash
# 1. Start infrastructure (Neo4j + Qdrant)
docker compose up -d

# 2. Start the Ktor server
./gradlew run

# 3. Open the web dev console
open http://localhost:8080
```

The server starts at `http://localhost:8080` with a built-in web dev console for testing all features visually.

### API Endpoints

| Endpoint | Description | Requires |
|---|---|---|
| `GET /health` | Health check | Nothing |
| `POST /api/chat` | Send a prompt to the Koog agent | `NTIVO_GEMINI_API_KEY` |
| `POST /api/embed` | Embed text with task type | `NTIVO_GEMINI_API_KEY` |
| `POST /api/search` | Vector search over Qdrant | `NTIVO_GEMINI_API_KEY` + Qdrant |
| `POST /api/parse` | Parse Kotlin code with Tree-sitter | Nothing |

### Standalone Demos

```bash
# Interactive AI agent REPL
NTIVO_GEMINI_API_KEY="..." ./gradlew runAgent

# Embedding + Qdrant storage demo (requires Docker)
NTIVO_GEMINI_API_KEY="..." ./gradlew runEmbeddingDemo

# Tree-sitter parsing demo (no API key needed)
./gradlew runTreeSitterDemo
```

### Docker Services

```bash
docker compose up -d        # Start Neo4j + Qdrant (background)
docker compose down          # Stop services (data preserved)
docker compose down -v       # Stop + wipe all data (fresh start)
docker compose ps            # Check service status
```

- **Neo4j Browser:** http://localhost:7474 (user: `neo4j`, password: `ntivo_dev_password`)
- **Qdrant Dashboard:** http://localhost:6333/dashboard

---

## Roadmap (high level)

- [x] Stack foundation — Ktor, Koog agents, Gemini, Qdrant, Tree-sitter, Docker Compose
- [x] Web dev console — visual testing for all features
- [ ] Core ingestion — parse and embed a codebase, store in knowledge graph
- [ ] Jira integration — link code to tickets and epics
- [ ] Confluence integration — connect specs and decisions to code
- [ ] Natural language query agent
- [ ] Datadog integration — automated incident enrichment
- [ ] MCP server — expose Ntivo as a tool for Cursor, Claude, and other AI clients
- [ ] Web UI — KMP/Compose for Web dashboard

---

## Self-hosting

Self-hosted from day one. BYOK (bring your own LLM key). No data leaves your
infrastructure unless you configure a cloud embedding provider — and that's your call.

Docker Compose setup coming in an early release.

---

## Contributing

Too early for contributions right now — the core isn't built yet. Watch the repo or
follow along on Substack to know when it's ready for outside input.

---

## License

Apache 2.0

## Name

*Ntivo* comes from the Xitsonga root word for "knowledge" (*ntivo*), as in *vutivi*. Parallel to *zivo* / *ruzivo* in Shona — both Bantu languages sharing the same linguistic heritage.
