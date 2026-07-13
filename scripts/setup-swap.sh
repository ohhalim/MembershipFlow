#!/usr/bin/env bash
set -euo pipefail

SWAP_FILE="${SWAP_FILE:-/swapfile}"
SWAP_SIZE="${SWAP_SIZE:-2G}"

if [[ ! -f "$SWAP_FILE" ]]; then
  sudo fallocate -l "$SWAP_SIZE" "$SWAP_FILE"
  sudo chmod 600 "$SWAP_FILE"
  sudo mkswap "$SWAP_FILE"
fi

if ! swapon --show=NAME --noheadings | grep -qx "$SWAP_FILE"; then
  sudo swapon "$SWAP_FILE"
fi

if ! grep -qF "$SWAP_FILE none swap sw 0 0" /etc/fstab; then
  echo "$SWAP_FILE none swap sw 0 0" | sudo tee -a /etc/fstab >/dev/null
fi

echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-membershipflow-swap.conf >/dev/null
sudo sysctl -p /etc/sysctl.d/99-membershipflow-swap.conf >/dev/null

swapon --show
free -h
cat /proc/sys/vm/swappiness
