#!/usr/bin/env bash
# Managed by AgentsTerm's scripts/install-git-hooks.sh
# A failed sync must still allow the caller's push.
[[ $1 == origin ]] || exit 0
exec </dev/null
skip() { echo "Local main sync skipped: $*" >&2; exit 0; }

common=$(git rev-parse --path-format=absolute --git-common-dir) || skip "cannot locate repository"
exec 9<"$common" || skip "cannot open repository directory"
flock -n 9 || skip "another sync is running or the lock is unavailable"

# Git can export worktree-specific paths when invoking hooks.
mapfile -t local_vars < <(git rev-parse --local-env-vars)
unset "${local_vars[@]}"
main_tree=$(git for-each-ref --format='%(worktreepath)' refs/heads/main) || skip "cannot locate main"
git show-ref --verify --quiet refs/heads/main || skip "local main does not exist"
if [[ -z $main_tree ]]; then
    # Fetch enforces fast-forward and refuses to write a checked-out branch.
    git fetch --no-tags --no-write-fetch-head --no-recurse-submodules origin refs/heads/main:refs/heads/main ||
        skip "could not fast-forward the unoccupied main branch"
    exit 0
fi
cd -- "$main_tree" || skip "main worktree is unavailable"
[[ $(git symbolic-ref -q HEAD) == refs/heads/main ]] || skip "main worktree changed"
git_dir=$(git rev-parse --absolute-git-dir) || skip "cannot locate main's index"
for marker in index.lock MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD REBASE_HEAD rebase-merge rebase-apply sequencer BISECT_START; do
    [[ ! -e $git_dir/$marker ]] || skip "main has an operation in progress ($marker)"
done
changes=$(git status --porcelain --untracked-files=all --ignore-submodules=none) || skip "cannot inspect main"
[[ -z $changes ]] || skip "main has local changes"
git fetch --no-tags --no-write-fetch-head --no-recurse-submodules origin refs/heads/main:refs/remotes/origin/main ||
    skip "could not fetch origin/main"
[[ $(git symbolic-ref -q HEAD) == refs/heads/main ]] || skip "main worktree changed"
git merge --no-stat --ff-only --no-autostash --no-overwrite-ignore refs/remotes/origin/main >&2 ||
    skip "could not fast-forward main"
