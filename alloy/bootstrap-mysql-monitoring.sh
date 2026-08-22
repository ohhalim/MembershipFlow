#!/usr/bin/env bash
set -euo pipefail

readonly mysql_host="mysql"
readonly monitor_user="${MYSQL_MONITORING_USERNAME:?MYSQL_MONITORING_USERNAME is required}"
readonly monitor_password="${MYSQL_MONITORING_PASSWORD:?MYSQL_MONITORING_PASSWORD is required}"

if [[ ! "${monitor_user}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "ERROR: MYSQL_MONITORING_USERNAME must contain only letters, numbers, and underscore"
  exit 1
fi

if [[ ! "${monitor_password}" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
  echo "ERROR: MYSQL_MONITORING_PASSWORD must be at least 32 URL-safe characters"
  exit 1
fi

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"

mysql --protocol=TCP --host="${mysql_host}" --user=root <<SQL
CREATE USER IF NOT EXISTS '${monitor_user}'@'%' IDENTIFIED BY '${monitor_password}'
  WITH MAX_USER_CONNECTIONS 3;
ALTER USER '${monitor_user}'@'%' IDENTIFIED BY '${monitor_password}'
  WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO '${monitor_user}'@'%';
GRANT SELECT ON performance_schema.* TO '${monitor_user}'@'%';
FLUSH PRIVILEGES;
SQL

echo "MySQL observability user configured: ${monitor_user}"
