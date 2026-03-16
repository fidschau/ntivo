#!/usr/bin/env bash
# ──────────────────────────────────────────────
# Ntivo dev script — starts everything you need
# Usage:  ./dev.sh          (full stack: Docker + server + Compose for Web)
#         ./dev.sh --quick  (server + web only, no Docker)
#         ./dev.sh --server (server only)
#         ./dev.sh --prod   (build web + run server — single process, no hot reload)
# ──────────────────────────────────────────────

QUICK=false
SERVER_ONLY=false
PROD=false
for arg in "$@"; do
  case "$arg" in
    --quick|-q)  QUICK=true ;;
    --server|-s) SERVER_ONLY=true ;;
    --prod|-p)   PROD=true ;;
  esac
done

# ── Check for API key ──
if [ -z "${NTIVO_GEMINI_API_KEY:-}" ]; then
  echo "⚠️  NTIVO_GEMINI_API_KEY not set — Chat, Embed, and Search will be disabled."
  echo "   Set it with: export NTIVO_GEMINI_API_KEY=\"your-key\""
  echo ""
fi

# ── Start Docker services ──
if [ "$QUICK" = false ] && [ "$SERVER_ONLY" = false ]; then
  echo "🐳 Starting Docker services (Neo4j + Qdrant)..."
  docker compose up -d
  echo ""
fi

# ── Production mode: build web + run server as single process ──
if [ "$PROD" = true ]; then
  echo "📦 Building Compose for Web production bundle..."
  ./gradlew :server:processResources --quiet
  echo ""
  echo "🚀 Starting Ntivo server (production mode)..."
  echo "   Compose UI:  http://localhost:8080"
  echo "   Health:      http://localhost:8080/health"
  echo ""
  (sleep 4 && open http://localhost:8080 2>/dev/null || true) &
  exec ./gradlew :server:run
fi

# ── Server only mode ──
if [ "$SERVER_ONLY" = true ]; then
  echo "🚀 Starting Ntivo server only..."
  echo "   Console: http://localhost:8080"
  echo "   Health:  http://localhost:8080/health"
  echo ""
  (sleep 4 && open http://localhost:8080 2>/dev/null || true) &
  exec ./gradlew :server:run
fi

# ── Full dev mode: server + Compose for Web ──
echo "🚀 Starting Ntivo dev environment..."
echo "   Compose UI:  http://localhost:8081  (Kotlin/Wasm, hot reload)"
echo "   Server API:  http://localhost:8080"
echo "   Health:      http://localhost:8080/health"
if [ "$QUICK" = false ]; then
  echo "   Neo4j:       http://localhost:7474"
  echo "   Qdrant:      http://localhost:6333/dashboard"
fi
echo ""

# Full dev build so webpack has all artifacts + npm deps on clean builds
echo "📦 Pre-building modules (first run may take a minute)..."
./gradlew :shared:build :web:wasmJsBrowserDevelopmentWebpack --quiet 2>/dev/null
echo ""

SERVER_PID=""
WEB_PID=""

# Clean up background processes on exit
cleanup() {
  echo ""
  echo "🛑 Shutting down..."
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  [ -n "$WEB_PID" ] && kill "$WEB_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT INT TERM

# Start Ktor server in background
./gradlew :server:run &
SERVER_PID=$!

# Give the server a head start, then launch web dev server
sleep 3
./gradlew :web:wasmJsBrowserDevelopmentRun &
WEB_PID=$!

# Open Compose UI once webpack is likely ready
(sleep 20 && open http://localhost:8081 2>/dev/null || true) &

# Wait for both — if either dies, cleanup runs via trap
wait
