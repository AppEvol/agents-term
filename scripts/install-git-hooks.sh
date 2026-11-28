#!/usr/bin/env bash
set -euo pipefail
source_dir=$(cd -- "$(dirname -- "$0")/git-hooks" && pwd)
if git config --get core.hooksPath >/dev/null; then
    echo "A custom core.hooksPath is configured. Integrate the scripts in $source_dir into those hooks." >&2
    exit 1
fi
hooks=$(git rev-parse --path-format=absolute --git-path hooks)
mkdir -p -- "$hooks"
for hook in pre-commit pre-push; do
    target=$hooks/$hook
    if [[ -L $target || ( -e $target && ! -f $target ) ]] ||
        { [[ -e $target ]] && ! grep -Fxq "# Managed by AgentsTerm's scripts/install-git-hooks.sh" "$target"; }; then
        echo "Existing hook at $target. Integrate the scripts in $source_dir into your hooks." >&2
        exit 1
    fi
done
temp=$(mktemp -d "$hooks/.install-XXXXXX")
trap 'rm -rf -- "$temp"' EXIT
for hook in pre-commit pre-push; do
    install -m 755 "$source_dir/$hook.sh" "$temp/$hook"
    mv -f -- "$temp/$hook" "$hooks/$hook"
done
echo "Installed pre-commit and pre-push hooks in $hooks"
