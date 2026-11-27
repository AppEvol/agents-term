#!/usr/bin/env bash
# Dumps the tail of the CI Docker VM container logs, removes the container,
# and cleans up its temporary snapshot directory if set.
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-agentsterm-ci-vm}"
docker logs "$CONTAINER_NAME" 2>&1 | tail -n 50 || true
docker rm -f "$CONTAINER_NAME" || true
if [ -n "${SNAPSHOT_DIR:-}" ]; then
  rm -rf "$SNAPSHOT_DIR"
fi
