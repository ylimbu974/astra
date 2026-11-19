#!/bin/sh
# Nightly copy of the registry database to a PC (Data & Tech Stack Sketch, Section 5).
# Usage (crontab -e on the Pi):  15 3 * * * /home/pi/astra/registry/backup.sh user@desktop-pc:astra-backups/
set -eu
DEST="${1:?Give a scp destination like user@desktop-pc:astra-backups/}"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date +%Y%m%d)"
# sqlite3 .backup takes a consistent copy even while the registry is running.
sqlite3 "$HERE/data/registry.db" ".backup '$HERE/data/registry-$STAMP.db'"
scp -q "$HERE/data/registry-$STAMP.db" "$DEST"
rm -f "$HERE/data/registry-$STAMP.db"
