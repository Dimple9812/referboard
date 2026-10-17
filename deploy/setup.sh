#!/usr/bin/env bash
# One-time setup of ReferBoard on a fresh Ubuntu server (written for Oracle Cloud's Always Free Ampere A1,
# Ubuntu 22.04/24.04, but any 64-bit Ubuntu with ≥ 8 GB RAM works). Safe to run again: every step checks first.
#
#   sudo ./deploy/setup.sh your-domain.com you@example.com
#
# 1. 4 GB swap   2. Docker   3. firewall (80/443)   4. .env with generated secrets
# 5. checks images exist for this CPU   6. builds the jars once   7. starts everything behind HTTPS
# 8. nightly backups at 02:30 server time (UTC; 08:00 IST)
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
DOMAIN=${1:-}
EMAIL=${2:-}

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m!   %s\033[0m\n' "$*"; }
die()  { printf '\033[31mx   %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run with sudo: sudo ./deploy/setup.sh <domain> <email>"
if [ ! -f .env ]; then
    [ -n "$DOMAIN" ] && [ -n "$EMAIL" ] || die "Usage: sudo ./deploy/setup.sh your-domain.com you@example.com"
    [[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "'$DOMAIN' doesn't look like a domain (no https://, no slashes)"
fi

# ---------------------------------------------------------------------------------------------------------
say "1/8 Swap"
if [ "$(swapon --show --noheadings | wc -l)" -eq 0 ]; then
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo 'vm.swappiness=10' > /etc/sysctl.d/99-referboard.conf
    sysctl -q -p /etc/sysctl.d/99-referboard.conf
    echo "    4 GB swap added (a safety net; the app is sized to fit in memory)"
else
    echo "    swap already on"
fi

# ---------------------------------------------------------------------------------------------------------
say "2/8 Docker"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null
docker compose version
# Compose features used by deploy/compose.prod.yml (!reset, !override) need 2.24.4 or later.
CV=$(docker compose version --short | sed 's/^v//')
if [ "$(printf '%s\n2.24.4\n' "$CV" | sort -V | head -1)" != "2.24.4" ]; then
    die "Docker Compose $CV is too old (need 2.24.4+). Run: sudo apt-get install --only-upgrade docker-compose-plugin"
fi

# ---------------------------------------------------------------------------------------------------------
say "3/8 Firewall"
# Oracle's Ubuntu images ship iptables rules that reject everything but SSH. Open 80 and 443 before them.
if iptables -S INPUT 2>/dev/null | grep -q -- '-j REJECT'; then
    for rule in "-p tcp --dport 80" "-p tcp --dport 443" "-p udp --dport 443"; do
        # shellcheck disable=SC2086
        if ! iptables -C INPUT $rule -m state --state NEW -j ACCEPT 2>/dev/null; then
            at=$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" {print $1; exit}')
            # shellcheck disable=SC2086
            iptables -I INPUT "$at" $rule -m state --state NEW -j ACCEPT
        fi
    done
    if command -v netfilter-persistent >/dev/null; then
        netfilter-persistent save >/dev/null 2>&1
    else
        warn "netfilter-persistent not found: these rules won't survive a reboot. Run: sudo apt-get install -y iptables-persistent"
    fi
    echo "    ports 80 and 443 open on this server"
fi
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; ufw allow 443/udp >/dev/null
    echo "    ufw: 80 and 443 allowed"
fi
echo "    Also needed once in the Oracle console: an ingress rule for TCP 80 and 443 (see docs/DEPLOY-ORACLE.md, step 3)."

# ---------------------------------------------------------------------------------------------------------
say "4/8 Settings (.env)"
if [ -f .env ]; then
    echo "    .env exists: keeping it (delete it only on a fresh server; it holds the database passwords)"
    DOMAIN=$(grep -E '^DOMAIN=' .env | cut -d= -f2 | awk '{print $1}')
else
    gen() { openssl rand -base64 36 | tr -d '/+=\n' | cut -c1-32; }
    KEYDIR=$(mktemp -d)
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/private.pem" 2>/dev/null
    openssl pkey -in "$KEYDIR/private.pem" -pubout -out "$KEYDIR/public.pem"
    PRIV=$(base64 -w0 "$KEYDIR/private.pem"); PUB=$(base64 -w0 "$KEYDIR/public.pem")
    rm -rf "$KEYDIR"
    umask 077
    : > .env
    while IFS= read -r line; do
        while [[ "$line" == *__GEN__* ]]; do line=${line/__GEN__/$(gen)}; done
        line=${line//__DOMAIN__/$DOMAIN}
        line=${line//__EMAIL__/$EMAIL}
        line=${line/__JWT_PRIVATE__/$PRIV}
        line=${line/__JWT_PUBLIC__/$PUB}
        printf '%s\n' "$line" >> .env
    done < deploy/env.template
    umask 022
    chmod 600 .env
    echo "    .env written with generated passwords and signing keys"
fi
[ -n "$DOMAIN" ] || die "DOMAIN is empty in .env"

# The certificate request fails (and Let's Encrypt rate-limits retries) if DNS isn't pointing here yet.
MYIP=$(curl -4 -fsS --max-time 5 https://ifconfig.me 2>/dev/null || true)
DNSIP=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk 'NR==1{print $1}' || true)
if [ -n "$MYIP" ] && [ "$MYIP" != "$DNSIP" ]; then
    warn "$DOMAIN resolves to '${DNSIP:-nothing}', but this server is $MYIP."
    warn "Point an A record for $DOMAIN at $MYIP (DNS only, no proxy), wait a few minutes, then run this again."
    die  "Stopping before requesting a certificate."
fi
echo "    $DOMAIN → ${DNSIP:-?} (this server)"

# ---------------------------------------------------------------------------------------------------------
say "5/8 Base images for $(uname -m)"
for img in eclipse-temurin:21-jre maven:3.9-eclipse-temurin-21 node:22.20.0-alpine nginx:1.27-alpine \
           pgvector/pgvector:pg16 apache/kafka:3.9.1 redis:7-alpine caddy:2-alpine; do
    if docker pull -q "$img" >/dev/null 2>&1; then
        echo "    ok  $img"
    else
        die "Couldn't pull $img for $(uname -m). If the error says 'no matching manifest', that image has no build for this CPU; see docs/DEPLOY-ORACLE.md, Troubleshooting."
    fi
done

# ---------------------------------------------------------------------------------------------------------
say "6/8 Building (one Maven run for all services; 10–20 minutes the first time on 2 CPUs)"
bash "$ROOT/deploy/build-jars.sh"

# ---------------------------------------------------------------------------------------------------------
say "7/8 Starting"
bash "$ROOT/deploy/rb" up -d --build --remove-orphans
echo "    Waiting for the app at https://$DOMAIN (the first start takes 3–6 minutes) ..."
ok=""
for _ in $(seq 1 90); do
    if curl -fsS --max-time 5 "https://$DOMAIN/api/billing/plans" >/dev/null 2>&1; then ok=1; break; fi
    sleep 10
done
if [ -n "$ok" ]; then
    echo "    https://$DOMAIN is up"
else
    warn "Not answering yet. Check: ./deploy/rb ps   and   ./deploy/rb logs --tail=50 caddy api-gateway"
fi

# ---------------------------------------------------------------------------------------------------------
say "8/8 Nightly backups"
cat > /etc/cron.d/referboard-backup <<CRON
# ReferBoard: database and uploaded resumes, kept 14 days in /var/backups/referboard
30 2 * * * root /bin/bash $ROOT/deploy/backup.sh >> /var/log/referboard-backup.log 2>&1
CRON
chmod 644 /etc/cron.d/referboard-backup
echo "    daily at 02:30 UTC → /var/backups/referboard (log: /var/log/referboard-backup.log)"

say "Done"
cat <<EOF
    Site:      https://$DOMAIN
    Status:    ./deploy/rb ps
    Logs:      ./deploy/rb logs -f --tail=100 <service>
    Settings:  .env (then ./deploy/rb up -d)
    Update:    ./deploy/update.sh
    Next: sign up on the site, then make yourself admin (docs/DEPLOY-ORACLE.md, step 7).
EOF
