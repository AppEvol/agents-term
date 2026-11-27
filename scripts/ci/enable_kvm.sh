#!/usr/bin/env bash
# Grants world read/write permissions to /dev/kvm via udev so the Android
# emulator can use hardware acceleration on GitHub Actions runners.
set -euo pipefail

echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
  | sudo tee /etc/udev/rules.d/99-kvm4all.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=kvm
