#!/bin/sh
# Backs up the database before an update, in case the new version's schema upgrade has to be undone.
# The Jenkinsfile runs this in a container of the new image while the app is stopped:
#
#     docker run --rm -v organizer-data:/data --entrypoint /app/backup.sh organizer
#
# Backups go to /data/backups/<date and time>; the newest KEEP_BACKUPS (default 5) are kept.
set -e

[ -e /data/organizer.db ] || exit 0

backup=/data/backups/$(date +%Y-%m-%d_%H-%M-%S)
mkdir -p "$backup"
# The -wal file holds the latest changes; a backup needs both files together.
cp /data/organizer.db "$backup/"
if [ -e /data/organizer.db-wal ]; then cp /data/organizer.db-wal "$backup/"; fi
echo "Backed up the database to $backup in the volume."

ls -1d /data/backups/*/ | sort -r | tail -n +$((${KEEP_BACKUPS:-5} + 1)) | xargs -r rm -rf
