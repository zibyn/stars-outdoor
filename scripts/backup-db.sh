#!/usr/bin/env bash
# Daily database and image backup (spec §3.2): pg_dump from the compose `db` service into deploy/backups/,
# keep 14 days locally, then rsync the folder to BACKUP_DEST (deploy/.env) off this server. No
# --delete: a wiped server must never prune the offsite copies; prune those by hand.
# Cron (deploy/README.md): 30 3 * * * /path/to/repo/scripts/backup-db.sh >> /var/log/stars-backup.log 2>&1
set -euo pipefail
DEPLOY=$(realpath "$(dirname "$0")/../deploy")
set -a; . "$DEPLOY/.env"; set +a
OUT="$DEPLOY/backups"
mkdir -p "$OUT"
f="$OUT/stars-$(date +%F).dump"
docker compose -f "$DEPLOY/compose.yaml" exec -T db pg_dump -U stars -Fc stars > "$f.tmp"
mv "$f.tmp" "$f"
find "$OUT" -name 'stars-*.dump' -mtime +13 -delete
if [[ -n "${BACKUP_DEST:-}" ]]; then
  rsync -a "$OUT/" "$BACKUP_DEST/"
  # 队伍对话 photos (§3.2), next to the dumps.
  if [[ -d "$DEPLOY/images" ]]; then rsync -a "$DEPLOY/images/" "$BACKUP_DEST/images/"; fi
else
  echo "BACKUP_DEST unset: $f stays on this server only" >&2
fi
echo "backup ok: $f ($(du -h "$f" | cut -f1))"
