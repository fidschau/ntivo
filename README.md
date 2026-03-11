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

🚧 **Early development.** The repo is currently being set up. Core architecture and
stack decisions are in place — active building starts now.

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

### Quick Start

```bash
# Start the backend
./gradlew run
```

The server starts at `http://localhost:8080`.

### With Docker (Neo4j + Qdrant)

```bash
# Coming soon — docker-compose.yml will spin up Neo4j + Qdrant + Ntivo API
docker compose up
```

---

## Roadmap (high level)

- [ ] Core ingestion — parse and embed a codebase, store in knowledge graph
- [ ] Jira integration — link code to tickets and epics
- [ ] Confluence integration — connect specs and decisions to code
- [ ] Natural language query agent
- [ ] Datadog integration — automated incident enrichment
- [ ] MCP server — expose Ntivo as a tool for Cursor, Claude, and other AI clients
- [ ] Web UI — KMP/Compose for Web dashboard
- [ ] Docker Compose setup for self-hosting

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
