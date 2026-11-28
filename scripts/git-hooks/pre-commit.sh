#!/usr/bin/env bash
# Managed by AgentsTerm's scripts/install-git-hooks.sh
set -eu
if [[ $(git symbolic-ref -q HEAD) == refs/heads/main ]]; then
    echo "Direct commits to main are blocked. Create a worktree with git worktree add -b <branch> <path>." >&2
    exit 1
fi
