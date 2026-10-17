#!/usr/bin/env bash
# Deploy a new version: pull the code (if this is a git checkout), rebuild, and restart what changed.
# Database migrations run automatically when each service starts (Flyway).
#   sudo ./deploy/update.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -d .git ]; then
    echo "==> Pulling"
    # The checkout usually belongs to the login user, not root.
    owner=$(stat -c %U .)
    if [ "$owner" != "root" ] && [ "$(id -u)" -eq 0 ]; then sudo -u "$owner" git pull --ff-only; else git pull --ff-only; fi
else
    echo "==> Not a git checkout: using the files as they are (copy the new version here first)"
fi

echo "==> Backing up before the update"
bash deploy/backup.sh

echo "==> Building"
bash deploy/build-jars.sh
bash deploy/rb build

echo "==> Restarting changed services"
bash deploy/rb up -d --remove-orphans
docker image prune -f >/dev/null
echo "==> Done. Services take a few minutes to come back; watch with ./deploy/rb ps"
