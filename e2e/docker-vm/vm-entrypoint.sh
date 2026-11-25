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
  busybox httpd -p "127.0.0.1:${RELEASE_HTTP_PORT}" -h /opt/agentsterm-release
  # Append to Ubuntu's default /etc/skel/.profile (which checks $BASH_VERSION) instead of
  # overwriting it, so bootstrap tests exercise real login-profile sourcing under dash/sh.
  cat >> /home/vmuser/.profile <<EOF
export AGENTSTERM_RELEASE_BASE_URL="http://127.0.0.1:${RELEASE_HTTP_PORT}"
export PATH="\$HOME/.local/bin:\$PATH"
EOF
  chown vmuser:vmuser /home/vmuser/.profile
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
    {"slug": "gpt-5.3-codex", "display_name": "GPT-5.3 Codex", "priority": 1},
    {"slug": "o4-mini", "display_name": "o4-mini", "priority": 2}
  ]
}
EOF

cat > /home/vmuser/.gemini/antigravity-cli/settings.json <<'EOF'
{"model": "gemini-4-pro"}
EOF

chown -R vmuser:vmuser /home/vmuser/.claude /home/vmuser/.codex /home/vmuser/.gemini

cron

socat "TCP-LISTEN:${BRIDGE_PORT},bind=0.0.0.0,fork,reuseaddr" "TCP:127.0.0.1:${DAEMON_PORT}" &

exec /usr/sbin/sshd -D -e
