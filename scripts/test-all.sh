#!/usr/bin/env bash
# Run every backend and frontend check that CI runs (Docker required for Testcontainers).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$root/apps/api" && ./mvnw -B spotless:check verify)
(cd "$root/apps/web" && npm ci && npm run lint && npm run typecheck && npm test && npm run build)
echo "All checks passed."
