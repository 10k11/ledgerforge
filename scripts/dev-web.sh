#!/usr/bin/env bash
# Run the web app with Vite; /api is proxied to http://localhost:8080.
set -euo pipefail
cd "$(dirname "$0")/../apps/web"
[ -d node_modules ] || npm ci
exec npm run dev
