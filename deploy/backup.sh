#!/usr/bin/env bash
# Backs up the database (every service's schema, plus roles) and uploaded resumes to /var/backups/referboard,
# keeping 14 days. If BACKUP_REMOTE is set in .env (an rclone remote such as oci:referboard-backups) and
# rclone is installed, each backup is also copied off the server. Run nightly by cron (see deploy/setup.sh).
# Restore steps: docs/DEPLOY-ORACLE.md, "Restoring a backup".
set -euo pipefail
cd "$(dirname "$0")/.."

DEST=/var/backups/referboard
KEEP_DAYS=14
STAMP=$(date -u +%Y-%m-%dT%H%M%SZ)
DIR="$DEST/$STAMP"
mkdir -p "$DIR"
chmod 700 "$DEST"

echo "[$STAMP] backup started"
bash deploy/rb exec -T postgres pg_dumpall -U postgres --globals-only > "$DIR/roles.sql"
bash deploy/rb exec -T postgres pg_dump -U postgres -Fc referboard > "$DIR/referboard.dump"
bash deploy/rb exec -T profile-service tar -czf - -C /data/resumes . > "$DIR/resumes.tar.gz"
chmod 600 "$DIR"/*

# A dump that pg_restore can't list is not a backup.
bash deploy/rb exec -T postgres pg_restore --list < "$DIR/referboard.dump" > /dev/null
echo "[$STAMP] wrote $(du -sh "$DIR" | cut -f1) to $DIR"

REMOTE=$(grep -E '^BACKUP_REMOTE=' .env 2>/dev/null | cut -d= -f2- | awk '{print $1}' || true)
if [ -n "$REMOTE" ]; then
    if command -v rclone >/dev/null; then
        rclone copy "$DIR" "$REMOTE/$STAMP" --config /root/.config/rclone/rclone.conf
        rclone delete "$REMOTE" --min-age "${KEEP_DAYS}d" --config /root/.config/rclone/rclone.conf || true
        echo "[$STAMP] copied to $REMOTE/$STAMP"
    else
        echo "[$STAMP] BACKUP_REMOTE is set but rclone isn't installed; kept on this server only" >&2
    fi
fi

find "$DEST" -mindepth 1 -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} +
echo "[$STAMP] backup finished"
