#!/usr/bin/env bash
# Nightly backup of the TOMS database and the uploaded LTFRB scans.
# Run it from anywhere; it works from the deploy folder next to docker-compose.prod.yml.
#
#   bash backup.sh                 # one backup now
#   30 2 * * * bash /home/deploy/toms/deploy/backup.sh >> /var/log/toms-backup.log 2>&1   # nightly, in crontab
#
# Optional: set OFFSITE to a second place (another server or your PC over SSH) and the backups are copied there too.
#   OFFSITE=user@your-pc:/backups/toms
set -euo pipefail

cd "$(dirname "$0")"
DEST="${BACKUP_DIR:-/var/backups/toms}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"

# 1. The database, dumped inside the running database container (consistent, custom format for pg_restore).
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$DEST/toms-db-$STAMP.dump"

# 2. The uploaded LTFRB fare documents (read-only copy of the uploads volume).
docker run --rm -v toms_uploads:/data:ro -v "$DEST":/backup alpine \
  tar -czf "/backup/toms-uploads-$STAMP.tar.gz" -C /data .

# 3. Keep only the last KEEP_DAYS days on this server.
find "$DEST" -name 'toms-*' -type f -mtime +"$KEEP_DAYS" -delete

# 4. Optional copy off the server.
if [ -n "${OFFSITE:-}" ]; then
  rsync -a "$DEST/" "$OFFSITE/"
fi

echo "backup ok: $STAMP"
