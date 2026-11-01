#!/usr/bin/env bash
# Nightly backup of the TOMS database and the uploaded LTFRB scans.
# Run it from anywhere; it works from the deploy folder next to docker-compose.prod.yml.
#
#   bash backup.sh                 # one backup now
#   30 2 * * * bash /home/deploy/toms/src/deploy/backup.sh >> /home/deploy/toms-backup.log 2>&1   # nightly, in crontab
#
# Optional: OFFSITE copies the backups to a second place (another server or your PC over SSH).
#   OFFSITE=user@your-pc:/backups/toms
set -euo pipefail

cd "$(dirname "$0")"
# This file is not named compose.yaml, so tell Compose which one to use.
export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
DEST="${BACKUP_DIR:-/var/backups/toms}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
# Partial files from a run that was stopped part way. Remove them before starting.
rm -f "$DEST"/.partial-*

# Both files are written under hidden names and renamed together at the end, so a run that fails part way
# leaves nothing that looks like a complete backup.
PART_DUMP="$DEST/.partial-db-$STAMP.dump"
PART_UPLOADS="$DEST/.partial-uploads-$STAMP.tar.gz"
trap 'rm -f "$PART_DUMP" "$PART_UPLOADS"' ERR

# 1. The database, dumped inside the running database container (consistent, custom format for pg_restore).
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$PART_DUMP"

# 2. The uploaded LTFRB fare documents, streamed from the running API container. This does not depend on the
#    name of the Compose project or its volume.
docker compose exec -T api tar -czf - -C /app/uploads . > "$PART_UPLOADS"

# 3. Both are complete: rename them into place.
mv "$PART_DUMP" "$DEST/toms-db-$STAMP.dump"
mv "$PART_UPLOADS" "$DEST/toms-uploads-$STAMP.tar.gz"

# 4. Keep only the last KEEP_DAYS days on this server.
find "$DEST" -name 'toms-*' -type f -mtime +"$KEEP_DAYS" -delete

# 5. Optional copy off the server. Partial files are never copied.
if [ -n "${OFFSITE:-}" ]; then
  rsync -a --exclude='.partial-*' "$DEST/" "$OFFSITE/"
fi

echo "backup ok: $STAMP"
