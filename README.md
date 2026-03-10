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

## Planned Stack

- **Language** — Kotlin (primary), TypeScript (web UI)
- **Agent framework** — [Koog](https://github.com/JetBrains/koog) (JetBrains OSS, Apache 2.0)
- **API layer** — Ktor
- **Knowledge graph** — Neo4j
- **Vector store** — Qdrant
- **Code parsing** — Tree-sitter (JVM bindings)
- **Embeddings / RAG** — LangChain4j
- **Build tool** — Gradle

---

## Roadmap (high level)

- [ ] Core ingestion — parse and embed a codebase, store in knowledge graph
- [ ] Jira integration — link code to tickets and epics
- [ ] Confluence integration — connect specs and decisions to code
- [ ] Natural language query agent
- [ ] Datadog integration — automated incident enrichment
- [ ] MCP server — expose Ntivo as a tool for Cursor, Claude, and other AI clients
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
