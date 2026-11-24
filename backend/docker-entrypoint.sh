#!/bin/sh
set -eu

STORAGE_DIR="${TVI_STORAGE_DIR:-/data/videos}"
mkdir -p "$STORAGE_DIR"

# Railway mounts volumes as root. Initialise only the mount directories, then
# run the API as the unprivileged app account (existing uploaded files are
# already owned by that account from previous container starts).
chown app:app /data "$STORAGE_DIR"

exec runuser -u app -- uvicorn app.main:app \
    --host 0.0.0.0 \
    --port "${PORT:-8000}" \
    --workers 1
