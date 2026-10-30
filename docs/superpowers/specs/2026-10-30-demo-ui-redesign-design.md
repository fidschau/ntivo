# Ntivo demo UI redesign

Date: 2026-10-30. Status: implemented (wave 1). Wave 2 is not started: Ruby, TypeScript/JavaScript, zip upload, paste, LLM-graded eval and a GitHub App connection. The end-to-end run on the real SportCity repositories is the last open check for wave 1.

## Intent

Replace the five-tab developer console with a product-like interface that a mixed technical and tech-adjacent audience understands in one sitting. The interface has one job: show that Ntivo reads a team's codebases once, turns them into knowledge an AI can use, and then answers questions about how the product actually works, across repositories, in plain language, with evidence.

The owner describes the product as "the team's guru, as a service": the one person who has read every repo, except it never leaves and anyone can ask it. The demo must make three things obvious without explaining knowledge graphs or vectors by name:

1. Code goes in and becomes something an AI can search by meaning, not by keyword.
2. Questions get answered from several places at once, with the exact code cited.
3. Code is one source. Tickets, docs and logs can join the same space later.

The visual reference is GitHub: white canvas, grey panels, blue links, green primary action, system font, 6px corners, dense but calm.

## Goals

- A first-time viewer can follow a demo of: connect a repository, watch it being read, ask a cross-repository question, see how the answer was produced.
- The pipeline (fetch, split, capture meaning, map connections) is visible while it runs, with one plain-English line per stage.
- Every answer shows the pieces of code it used, with similarity scores, and the connections it followed, with confidence.
- Answers say what they could not see (a source still indexing, a repo not connected).
- A whole repository can be connected by GitHub URL or uploaded as a folder or zip, and indexing time is shown so the owner can judge what fits in a demo.
- Kotlin, Swift, Ruby and TypeScript/JavaScript sources are parsed into functions and classes.
- The existing developer tabs remain reachable under "Under the hood" for debugging, out of the main path.

## Non-goals

- Dark mode.
- Authentication, multi-user accounts, multi-tenancy.
- Jira, Confluence or Datadog ingestion. They appear in the sources list as "coming next" and nothing more.
- A hosted deployment. This runs on the owner's laptop against local Neo4j and Qdrant.
- Mobile layout. Desktop widths only (1280px and up); narrower screens may collapse the side panels but need not be polished.

## Anti-goals

- A dashboard of counts and charts. Numbers appear only where they explain something (how many pieces, how long a stage took, how similar a piece was).
- Explaining the mechanism in jargon. No "vector", "embedding", "RAG", "knowledge graph" or "Tree-sitter" in the main interface. Those words may appear under "Under the hood".
- Answers that read like search results. Each answer is prose that takes a position, followed by evidence.
- A wizard that forces the same path every time. Once sources exist, the chat is the home screen.

## The interface

Three columns under a slim top bar. All three are open by default; the left and right columns collapse. The middle column always has priority for width.

### Top bar (56px)

Left: the Ntivo mark and name, then a workspace label (for the demo, "SportCity"). Right: "Under the hood" (opens the old developer tabs as a separate page), "New chat", and a static avatar.

### Left column: Sources (272px, collapses to 56px)

Two sections, "Code" and "Coming next".

Each code source is a card: repository name, language and piece count, last sync, and a state label (Ready, Indexing with a progress bar, Failed with a reason). Below the list, a dashed "Connect a repository" affordance.

"Coming next" lists Jira and Confluence at reduced opacity with a "Soon" label. They are not clickable.

Collapsed: each source becomes a 32px icon tile with a coloured status dot and a tooltip holding the name and state. The "Connect" affordance becomes a plus tile. The collapse control sits at the bottom of the column.

### Middle column: Chat

A scrolling stream of turns, 720px max width, centred. Two kinds of turn:

**System events** from Ntivo, such as "connected sportcity/backend", which carry an ingestion card. The card looks like a GitHub Actions job: a header with the repository name and state label, then four steps, each with a check, spinner or empty circle, a plain-English description, and a duration:

1. Fetched N files, from GitHub main branch or from upload.
2. Split into N pieces: one per function or method, so answers point at the exact code.
3. Capturing meaning: each piece becomes a point in a space where similar code sits close together.
4. Mapping connections: who calls what, so answers can follow the trail.

Steps update live while the job runs. When it finishes, the header label turns to Ready and the card stays in the stream as a record.

**Conversation turns.** The user's question, then Ntivo's answer. An answer has, in order: an optional verdict banner when the question has a yes/no or match/mismatch shape (yellow for mismatch or warning, green for confirmed, grey for neutral); prose; optional evidence table when comparing platforms or sources, each cell ending in a file citation (path and line range, monospace, blue); a closing "could not see" paragraph when any connected source was unavailable or the question touched an unconnected area; and a row of small actions: "How I answered", "Copy", "Open files".

Clicking a file citation or "Open files" shows the cited code in a drawer over the right column, read-only, with the cited lines highlighted.

**Composer** at the bottom: a bordered box with a textarea, a footer line saying which sources will be searched ("Searching 2 ready sources"), and a green "Ask" button. Under it, two or three example question chips tailored to the connected sources. Submitting with Enter asks; Shift+Enter adds a line.

Answers stream in as they are produced. While the agent works, the composer is disabled and the answer turn shows the current activity ("Searching android-app", "Following connections", "Writing answer").

### Right column: How it answered (340px, collapses to 40px)

Shows the trace of the most recent answer, or the one whose "How I answered" action was clicked. Sections:

- A short explanation box in plain words: "Your question was turned into a point in the same space as the code. Ntivo took the 3 closest pieces, then followed 2 connections from them."
- Closest pieces: name, repository and language, and a similarity bar with the score to two decimals.
- Connections followed: from, relation (reads, calls, inherits), to, and the confidence.
- Not available yet: sources that were indexing or missing.

Before any question is asked, the column shows a short empty state explaining what will appear there.

Collapsed: a thin vertical strip with a rotated label and the piece count badge. Clicking expands.

### Connect a repository (modal)

Three ways, shown as tabs in a modal:

1. **GitHub URL.** A text field for the repository URL, a branch field defaulting to the default branch, and an optional personal access token field for private repositories, with a one-line note that the token is kept in memory for this session only. "Connect" starts the job.
2. **Upload.** A drop zone accepting a folder (directory picker) or a .zip. Shows the file count and total size before "Connect" is pressed, so the owner can decide whether a whole codebase or a module fits the demo.
3. **Paste code.** A textarea and a name field. Creates a single-file source. This keeps the old quick-try path.

Every method creates a source card in Indexing state and an ingestion card in the chat, then proceeds through the four steps.

### Under the hood

The existing developer screens (RAG pipeline, vector search, parse, code graph) move to a separate route linked from the top bar. They keep working but receive no design attention in this project beyond adopting the same CSS tokens if that is cheap.

## Behaviour

### Sources and indexing

A source is a named codebase with a kind (github, upload, paste), a language mix, a state, counts, and timing per stage. Sources persist across server restarts (file-backed is fine).

Indexing runs server-side as a job with progress events. Stage 1 fetches (git clone of the branch into a working directory, or unpacking the upload). Stage 2 parses every supported file into functions and classes. Stage 3 embeds pieces into Qdrant, skipping pieces whose fingerprint is unchanged. Stage 4 builds the Neo4j graph for the source. Progress for stages 2 to 4 is reported as processed/total.

All indexed sources share one Qdrant collection and one Neo4j database; every piece and node carries its source id so answers can name the repository and so a source can be removed.

Indexing a whole mobile repository must be possible on a laptop. The owner will test with the full SportCity Android and iOS repositories and fall back to a module if too slow. Stage 3 dominates; batch embedding requests and show the running estimate.

### Parsing

Kotlin exists. Add Swift, Ruby and TypeScript/JavaScript using the same Tree-sitter JVM bindings already in use (io.github.bonede). For each language, map the grammar's function, method and class-like declarations to the existing FunctionChunk and ClassDecl model, including the enclosing container and the called names. Each language gets a parser test on a small fixture.

### Answering

The agent has two tools: search by meaning (existing) and query the graph (new): callers of a function, callees of a function, declarations in a file, with results scoped to ready sources. The agent decides which to use; the trace records every tool call in order with its inputs and a summary of results.

Answers are produced with the structure described under "Conversation turns". The model is asked for prose plus structured evidence (citations with source, path, line range) so the interface can render citations as links rather than parsing free text.

When a connected source is indexing or failed, the agent is told so in its context and must mention it in the answer when relevant.

### The demo question

The interface is built so this exchange works end to end on two real repositories:

> Do iOS and Android enforce the same friend limit in bring-a-friend?

Expected shape of a good answer: a verdict, one row per platform with the function and file cited, a consequence sentence, and a note about any source not yet available.

## Guardrails

The agent is non-deterministic and reads untrusted text, so the following are part of the build, not later hardening.

Grounding. The answer is produced as structured output: prose, an optional verdict, evidence rows, and citations. Each citation references a piece id that the server retrieved during this question. The server drops any citation to a piece that was not retrieved and marks the answer as "partly unverified" in the trace. Prose that makes a factual claim about the code without a supporting citation is a defect to be caught by the eval set.

Retrieval gates. Search results below a similarity threshold (start at 0.60, tune against the eval set) are discarded before the agent sees them. If nothing clears the threshold, the agent is told so and must answer that nothing relevant was found. The "could not see" paragraph is driven by server state: sources that are indexing, failed or absent are listed in the agent's context on every question, and the trace panel shows them from the same data.

Eagerness limits. Per question: at most 6 tool calls, graph queries limited to 3 hops and 50 results, a 60 second wall-clock budget, and a token budget. Tool descriptions state when not to use them (general programming questions, questions already answered by the previous tool result). When a limit is hit, the agent answers with what it has and the trace shows the limit was reached. All tools are read-only; there is no tool that writes, deletes or calls an external service other than the model.

Untrusted content. Retrieved code and comments are placed in the prompt inside clearly delimited data blocks with an instruction to treat them as data, never as instructions. API keys, tokens and environment variables never enter the prompt. Repository content is scanned for obvious secret patterns at ingestion and those pieces are indexed with the secret redacted.

Model settings. Temperature low (0.2 or the provider's equivalent), model id pinned in configuration, and the system prompt and tool descriptions kept in version control so behaviour changes are reviewable.

Measurement. An eval set of 10 to 15 questions with expected pieces and expected facts lives in the repository with a runner. It reports retrieval precision (were the expected pieces in the top results), citation validity (did every citation point at a retrieved piece), and answer correctness (does the answer contain the expected facts, graded by a second model call). It runs on demand and its results are kept per run so changes can be compared.

Observability. Every question records the full trace: tool calls in order with inputs, result summaries, timings, token use, and the final structured answer. The "How it answered" panel reads from this record. Traces are kept on disk for the session.

## Constraints

- Server: Kotlin, Ktor, Koog, Neo4j, Qdrant, Tree-sitter, as today.
- New interface: plain HTML, CSS and JavaScript served by Ktor from server resources. No framework, no build step, no TypeScript. The owner chose this over Compose for Web because the UI is not the learning goal and DOM rendering matches the GitHub reference. CLAUDE.md is updated to say so; the Compose module stays only as the "Under the hood" page until it is retired.
- Model for chat: Gemini 2.5 Flash now; switchable to Claude (Anthropic API key) by configuration. The owner will add a personal Anthropic key.
- Embeddings: Gemini embedding on the paid tier (owner enables billing), so repository text is not used for training. The embedder stays behind the existing TaskTypeEmbedder interface so Voyage or a local Ollama model can replace it later.
- Secrets from environment variables only. A GitHub token entered in the UI is held in server memory for the session and never written to disk.
- Local only. No public exposure, so no auth.

## Decisions already made

- Layout B: sources left, chat centre, trace right. All open by default, left and right collapsible.
- GitHub as the visual reference. No dark mode.
- Connect by GitHub URL, folder or zip upload, or paste.
- The demo scenario is iOS vs Android drift on the bring-a-friend friend limit, using SportCity's Android and iOS repositories.
- Languages: Kotlin, Swift, Ruby, TypeScript/JavaScript.
- Delivery in two waves. Wave 1 is the demo path: GitHub connect and folder upload, Kotlin and Swift parsing, the two-tool agent, the full three-column interface, guardrails, and the eval set. Wave 2 adds Ruby, TypeScript/JavaScript, zip upload and paste. Wave 1 is done when the bring-a-friend question works end to end on the real Android and iOS repositories.
- Both connect methods in wave 1: the SportCity repositories are on GitHub (private, via personal access token) and also checked out locally, and the owner wants to demo either way.

## Left to the builder

- Streaming transport for indexing progress and answer activity. Builder's call: server-sent events.
- Answer delivery. Builder's call: the answer is one structured response, not token streaming, because citations must be validated against retrieved pieces before anything is shown. While the agent works, the answer turn shows live activity from the trace ("Searching android-app", "Following 2 connections", "Writing answer"), so the wait is never silent.
- How the GitHub connection fetches code: a git clone with the token in the URL is the simplest; a GitHub App installation is more product-like but needs a hosted callback and is out of scope.
- Job persistence and resumption after a server restart (a failed state with a "Retry" is acceptable).
- Exact copy for stage descriptions, empty states and errors, following the register in the mockup: plain verbs, sentence case, no jargon.
- Whether "Open files" shows code from the working copy on disk or from the stored piece text (the stored text is enough for citations).
