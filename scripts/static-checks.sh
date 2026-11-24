#!/usr/bin/env bash
# Static checks for the whole repo. CI runs exactly this script; run it
# locally before pushing.
#
#   scripts/static-checks.sh          check everything, exit non-zero on findings
#   scripts/static-checks.sh --fix    apply the automatic fixes (formatting,
#                                     import order, safe lint fixes) first
#
# Checks:
#   Python   ruff (lint, import order, formatting), vulture (dead code)
#   Kotlin   ktlint (formatting, import order, unused imports),
#            detekt (code smells, unused private code)
#   Shell    shellcheck
#   CI       actionlint (workflow syntax + embedded shell)
#
# Android Lint is the one static check not in here: it needs the Android SDK
# and a compiled app, so it runs as `gradle :app:lintDebug`.
#
# Tools are pinned below and downloaded into a cache directory on first use.
# Requirements: bash, curl, tar, a JDK on PATH (for ktlint and detekt), and
# either uv or python3 with the venv module.
set -euo pipefail

RUFF_VERSION=0.17.6
VULTURE_VERSION=2.16
KTLINT_VERSION=1.8.0
DETEKT_VERSION=1.23.8
SHELLCHECK_VERSION=0.11.0
ACTIONLINT_VERSION=1.7.12

fix=false
case "${1:-}" in
  "") ;;
  --fix) fix=true ;;
  *)
    echo "usage: $0 [--fix]" >&2
    exit 2
    ;;
esac

cd "$(dirname "$0")/.."
cache="${STATIC_CHECKS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/agentsterm-static-checks}"
bin="$cache/bin"
mkdir -p "$bin"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) sc_platform=linux.x86_64 al_platform=linux_amd64 ;;
  Linux-aarch64) sc_platform=linux.aarch64 al_platform=linux_arm64 ;;
  Darwin-x86_64) sc_platform=darwin.x86_64 al_platform=darwin_amd64 ;;
  Darwin-arm64) sc_platform=darwin.aarch64 al_platform=darwin_arm64 ;;
  *)
    echo "unsupported platform: $(uname -s)-$(uname -m)" >&2
    exit 2
    ;;
esac

fetch() { # fetch <url> <destination>
  echo "  downloading $1" >&2
  curl -fsSL --retry 3 -o "$2.tmp" "$1"
  mv "$2.tmp" "$2"
}

# Python tools live in a private virtualenv so they never touch the daemon's
# standard-library-only runtime.
venv="$cache/venv-ruff$RUFF_VERSION-vulture$VULTURE_VERSION"
if [ ! -x "$venv/bin/ruff" ] || [ ! -x "$venv/bin/vulture" ]; then
  rm -rf "$venv"
  if command -v uv >/dev/null; then
    uv venv --quiet "$venv"
    uv pip install --quiet --python "$venv/bin/python" \
      "ruff==$RUFF_VERSION" "vulture==$VULTURE_VERSION"
  else
    python3 -m venv "$venv"
    "$venv/bin/pip" install --quiet --disable-pip-version-check \
      "ruff==$RUFF_VERSION" "vulture==$VULTURE_VERSION"
  fi
fi

ktlint="$bin/ktlint-$KTLINT_VERSION"
if [ ! -x "$ktlint" ]; then
  fetch "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint" "$ktlint"
  chmod +x "$ktlint"
fi

detekt="$bin/detekt-cli-$DETEKT_VERSION-all.jar"
if [ ! -f "$detekt" ]; then
  fetch "https://github.com/detekt/detekt/releases/download/v$DETEKT_VERSION/detekt-cli-$DETEKT_VERSION-all.jar" "$detekt"
fi

shellcheck="$bin/shellcheck-$SHELLCHECK_VERSION"
if [ ! -x "$shellcheck" ]; then
  fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/shellcheck-v$SHELLCHECK_VERSION.$sc_platform.tar.xz" "$shellcheck.tar.xz"
  tar -xJf "$shellcheck.tar.xz" -C "$bin" --strip-components=1 "shellcheck-v$SHELLCHECK_VERSION/shellcheck"
  mv "$bin/shellcheck" "$shellcheck"
  rm "$shellcheck.tar.xz"
fi

actionlint="$bin/actionlint-$ACTIONLINT_VERSION"
if [ ! -x "$actionlint" ]; then
  fetch "https://github.com/rhysd/actionlint/releases/download/v$ACTIONLINT_VERSION/actionlint_${ACTIONLINT_VERSION}_$al_platform.tar.gz" "$actionlint.tar.gz"
  tar -xzf "$actionlint.tar.gz" -C "$bin" actionlint
  mv "$bin/actionlint" "$actionlint"
  rm "$actionlint.tar.gz"
fi

failed=()
run() { # run <name> <command...>
  local name="$1"
  shift
  echo "==> $name"
  if ! "$@"; then
    failed+=("$name")
  fi
}

kotlin_sources=('app/src/**/*.kt' '*.kts' 'app/*.kts')
mapfile -t shell_scripts < <(git ls-files '*.sh')

if $fix; then
  run "ruff check --fix" "$venv/bin/ruff" check --fix .
  run "ruff format" "$venv/bin/ruff" format .
  # ktlint exits non-zero if anything is left that it cannot fix itself; the
  # check below reports those.
  echo "==> ktlint --format"
  "$ktlint" --format "${kotlin_sources[@]}" >/dev/null 2>&1 || true
fi

run "ruff check (Python lint, import order)" "$venv/bin/ruff" check .
run "ruff format (Python formatting)" "$venv/bin/ruff" format --check .
run "vulture (Python dead code)" "$venv/bin/vulture"
run "ktlint (Kotlin formatting, import order)" "$ktlint" "${kotlin_sources[@]}"
run "detekt (Kotlin code smells, unused code)" java -jar "$detekt" \
  --input app/src \
  --build-upon-default-config \
  --config config/detekt/detekt.yml \
  --baseline config/detekt/baseline.xml
run "shellcheck (shell scripts)" "$shellcheck" "${shell_scripts[@]}"
run "actionlint (GitHub workflows)" "$actionlint" -shellcheck "$shellcheck"

echo
if [ ${#failed[@]} -gt 0 ]; then
  echo "Static checks FAILED:"
  printf '  - %s\n' "${failed[@]}"
  echo "Formatting and import-order findings can be fixed with: scripts/static-checks.sh --fix"
  exit 1
fi
echo "All static checks passed."
