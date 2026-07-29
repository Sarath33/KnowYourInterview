#!/bin/sh
# Runs as root (see Dockerfile — no USER directive before this). Railway mounts the
# proof-storage persistent volume at $PROOF_STORAGE_DIR fresh on every container start,
# owned by root, which shadows the `chown -R kyi:kyi /app` baked into the image at build
# time. Without this fix, LocalProofStorageService.store()'s
# Files.createDirectories(target.getParent()) throws AccessDeniedException for every
# proof upload, because the non-root kyi user has no write permission on the mount.
#
# Fix: chown the mount here, every boot, then drop from root to kyi via su-exec before
# exec'ing the JVM — so the app itself never actually runs as root.
set -e

PROOF_DIR="${PROOF_STORAGE_DIR:-/app/uploads/proof}"
mkdir -p "$PROOF_DIR"
chown -R kyi:kyi "$PROOF_DIR"

exec su-exec kyi java -jar app.jar --server.port="${PORT:-8080}"
