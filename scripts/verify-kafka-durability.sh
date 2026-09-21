#!/usr/bin/env bash
set -euo pipefail
# Broker failures run only in the disposable ingestion stack, never in the local development cluster.
exec bash "$(dirname "$0")/verify-ingestion.sh" "$@"
