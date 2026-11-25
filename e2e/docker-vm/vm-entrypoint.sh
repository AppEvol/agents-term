#!/usr/bin/env bash
set -euo pipefail

DAEMON_PORT="${DAEMON_PORT:-7591}"
BRIDGE_PORT="${BRIDGE_PORT:-17591}"
RELEASE_HTTP_PORT="${RELEASE_HTTP_PORT:-18080}"

ssh-keygen -A >/dev/null 2>&1

if [ -f /mnt/ssh-pubkey ]; then
  install -d -m 700 -o vmuser -g vmuser /home/vmuser/.ssh
  install -m 600 -o vmuser -g vmuser /mnt/ssh-pubkey /home/vmuser/.ssh/authorized_keys
fi

# Serve prebuilt static Rust daemon release artifacts over local HTTP if mounted.
# Point vmuser's ~/.profile at the local mirror so the unmodified Android bootstrap script
# fetches the freshly-built PR binary without touching GitHub.
if [ -d /mnt/release ]; then
  mkdir -p /opt/agentsterm-release
  cp -a /mnt/release/. /opt/agentsterm-release/
  chown -R vmuser:vmuser /opt/agentsterm-release
  if command -v httpd >/dev/null 2>&1 && httpd --help 2>&1 | grep -qi busybox; then
    httpd -p "127.0.0.1:${RELEASE_HTTP_PORT}" -h /opt/agentsterm-release
  elif command -v busybox >/dev/null 2>&1 && busybox httpd --help >/dev/null 2>&1; then
    busybox httpd -p "127.0.0.1:${RELEASE_HTTP_PORT}" -h /opt/agentsterm-release
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m http.server "${RELEASE_HTTP_PORT}" --bind 127.0.0.1 --directory /opt/agentsterm-release >/dev/null 2>&1 &
  else
    echo "ERROR: no local HTTP server available (need busybox httpd or python3)" >&2
    exit 1
  fi

  # Append to the distro's default ~/.profile (e.g. Ubuntu's /etc/skel/.profile which checks
  # $BASH_VERSION) instead of overwriting it, so bootstrap tests exercise real login-profile
  # sourcing under dash/ash/bash.
  touch /home/vmuser/.profile
  cat >> /home/vmuser/.profile <<EOF
export AGENTSTERM_RELEASE_BASE_URL="http://127.0.0.1:${RELEASE_HTTP_PORT}"
export PATH="\$HOME/.local/bin:\$PATH"
EOF
  chown vmuser:vmuser /home/vmuser/.profile
fi

# Optional legacy git mirror for Python -> Rust live upgrade testing.
if [ -d /mnt/repo ] && command -v git >/dev/null 2>&1; then
  git config --system --add safe.directory '*'
  rm -rf /opt/agentsterm-repo
  git clone --bare /mnt/repo /opt/agentsterm-repo >/dev/null
  chown -R vmuser:vmuser /opt/agentsterm-repo
  git config --system url."file:///opt/agentsterm-repo".insteadOf "https://github.com/nedn/agents-term.git"
fi

# Seed realistic model configs for claude, codex, and agy discovery tests.
install -d -o vmuser -g vmuser \
  /home/vmuser/.claude \
  /home/vmuser/.codex \
  /home/vmuser/.gemini/antigravity-cli

cat > /home/vmuser/.claude/settings.json <<'EOF'
{"model": "sonnet"}
EOF

cat > /home/vmuser/.codex/config.toml <<'EOF'
model = "gpt-5.3-codex"
EOF

cat > /home/vmuser/.codex/models_cache.json <<'EOF'
{
  "models": [
    {"slug": "o4-mini", "display_name": "o4-mini", "priority": 2},
    {"slug": "hidden-internal-model", "display_name": "Hidden", "priority": 0, "visibility": "hide"},
    {"slug": "gpt-5.3-codex", "display_name": "GPT-5.3 Codex", "priority": 1}
  ]
}
EOF

cat > /home/vmuser/.gemini/antigravity-cli/settings.json <<'EOF'
{"model": "gemini-4-pro"}
EOF

chown -R vmuser:vmuser /home/vmuser/.claude /home/vmuser/.codex /home/vmuser/.gemini

# Start the distro's cron daemon if present (Ubuntu/Debian: cron; RHEL/Alpine: crond;
# Fedora/Amazon Linux 2023 intentionally have no cron to exercise detached-only fallback).
if command -v cron >/dev/null 2>&1; then
  cron
elif command -v crond >/dev/null 2>&1; then
  crond
fi

socat "TCP-LISTEN:${BRIDGE_PORT},bind=0.0.0.0,fork,reuseaddr" "TCP:127.0.0.1:${DAEMON_PORT}" &

exec /usr/sbin/sshd -D -e
