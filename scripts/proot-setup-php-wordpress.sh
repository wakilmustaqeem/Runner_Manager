#!/usr/bin/env bash
# Run INSIDE the Ubuntu PRoot rootfs only; never run on Android host.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "[1/4] Refresh package indexes (no full system upgrade)"
apt-get update

echo "[2/4] Install PHP, WordPress extensions, Composer, and utilities"
apt-get install -y --no-install-recommends \
  php-cli php-fpm \
  php-mysql php-curl php-gd php-mbstring php-xml php-zip php-intl \
  composer unzip wget curl ca-certificates \
  mariadb-server nginx

echo "[3/4] Verify required tools and PHP extensions"
php -v
php -m | grep -Eiq '^(mysqli|curl|gd|mbstring|SimpleXML|xml|zip|intl)$'
composer --version

echo "[4/4] Setup complete"
echo "This script does not start services or configure a WordPress database."
echo "PRoot commonly has no systemd; start/configure services manually if needed."
echo "Development server example (from the WordPress document root):"
echo "  php -S 127.0.0.1:8080"
