#!/usr/bin/env bash
# NeoForge cell: starts the server, waits until it accepts connections, then starts the clients.
set -uo pipefail

# Usage: scripts/dev-neoforge.sh; PVP_CLIENTS=3 adds a spectator; on Windows use dev-neoforge.cmd.
# Readiness wait, FIFO, cleanup order and port guard all live in dev-duel.sh.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec env CELL=1.21.1-neoforge "$ROOT/scripts/dev-duel.sh" "$@"
