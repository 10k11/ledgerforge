#!/usr/bin/env bash
# Run the API from source with the dev profile (demo data, localhost webhooks).
# Requires Java 21 and PostgreSQL (`docker compose up -d postgres`). Reads ../../.env automatically.
set -euo pipefail
cd "$(dirname "$0")/../apps/api"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
