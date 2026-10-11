#!/usr/bin/env bash
# Remove lane worktrees and local branches whose pull request has been merged.
#
#   tools/cleanup-worktrees.sh [--dry-run]
#
# For every linked worktree (never the main checkout or the one you are in) and
# every other local branch except main, it looks up the branch's PRs with gh.
# It removes the worktree and deletes the local branch only when all hold:
#   - the branch has a merged PR and no open PR;
#   - the local tip is the merged PR's head commit (nothing committed since);
#   - the worktree has no modified or untracked files (gitignored files such
#     as mod/run/ and .local/ are removed with it).
# Anything else is kept and the reason printed. Worktrees are removed through
# Orca (orca-ide worktree rm) so its sidebar stays in step, falling back to
# git worktree remove when Orca is unavailable or does not track the path.
# Remote branches are not touched (gh pr merge --delete-branch removes them).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

dry_run=0
for arg in "$@"; do
  case "$arg" in
    -n|--dry-run) dry_run=1 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $arg" ;;
  esac
done
require git gh

# Same resolution as the roadmap-workflow skill, minus the bare Linux `orca`
# (a screen reader).
orca=
if [[ -n "${ORCA_CLI_COMMAND:-}" ]]; then
  orca="$ORCA_CLI_COMMAND"
elif [[ -n "${ORCA_DEV_REPO_ROOT:-}" ]] && command -v orca-dev >/dev/null; then
  orca=orca-dev
elif command -v orca-ide >/dev/null; then
  orca=orca-ide
fi

main_root="$(git -C "$repo_root" worktree list --porcelain | awk '/^worktree /{print substr($0, 10); exit}')"
here="$(git rev-parse --show-toplevel 2>/dev/null || true)"

# Prints "merged <head sha>" when the branch has a merged PR and no open one,
# otherwise the reason to keep it.
pr_state() {
  local branch=$1 open merged
  open="$(gh pr list --repo "$repo" --head "$branch" --state open --json number -q 'length')"
  ((open == 0)) || { echo "open PR"; return; }
  merged="$(gh pr list --repo "$repo" --head "$branch" --state merged --limit 1 --json headRefOid -q '.[0].headRefOid // ""')"
  [[ -n "$merged" ]] && echo "merged $merged" || echo "no merged PR"
}

repo="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" || die "gh cannot resolve the GitHub repository"
removed=0
kept=0
declare -A in_worktree=()

while IFS=$'\t' read -r path branch; do
  [[ -n "$branch" ]] && in_worktree[$branch]=1
  [[ "$path" == "$main_root" ]] && continue
  if [[ -z "$branch" ]]; then
    log "keep $path: detached HEAD"; kept=$((kept + 1)); continue
  fi
  if [[ "$path" == "$here" ]]; then
    log "keep $path ($branch): it is the current worktree"; kept=$((kept + 1)); continue
  fi
  read -r state sha <<<"$(pr_state "$branch")"
  if [[ "$state" != merged ]]; then
    log "keep $path ($branch): $state${sha:+ $sha}"; kept=$((kept + 1)); continue
  fi
  if [[ "$(git -C "$path" rev-parse HEAD)" != "$sha" ]]; then
    log "keep $path ($branch): commits after the merged PR head"; kept=$((kept + 1)); continue
  fi
  if [[ -n "$(git -C "$path" status --porcelain)" ]]; then
    log "keep $path ($branch): uncommitted or untracked files"; kept=$((kept + 1)); continue
  fi
  if ((dry_run)); then
    log "would remove $path and branch $branch"; removed=$((removed + 1)); continue
  fi
  if [[ -n "$orca" ]]; then
    "$orca" worktree rm --worktree "path:$path" --force --json >/dev/null 2>&1 || true
  fi
  [[ -e "$path" ]] && git -C "$main_root" worktree remove --force "$path"
  git -C "$main_root" worktree prune
  git -C "$main_root" branch -D "$branch" >/dev/null
  log "removed $path and branch $branch"
  removed=$((removed + 1))
done < <(git -C "$repo_root" worktree list --porcelain | awk '
  /^worktree / { if (p != "") print p "\t" b; p = substr($0, 10); b = "" }
  /^branch / { b = substr($0, 8); sub(/^refs\/heads\//, "", b) }
  END { if (p != "") print p "\t" b }')

# Local branches with no worktree, such as status/* branches merged by auto-merge.
while IFS= read -r branch; do
  [[ "$branch" == main || -n "${in_worktree[$branch]:-}" ]] && continue
  read -r state sha <<<"$(pr_state "$branch")"
  if [[ "$state" != merged ]]; then
    continue
  fi
  if [[ "$(git -C "$main_root" rev-parse "refs/heads/$branch")" != "$sha" ]]; then
    log "keep branch $branch: commits after the merged PR head"; kept=$((kept + 1)); continue
  fi
  if ((dry_run)); then
    log "would delete branch $branch"
  else
    git -C "$main_root" branch -D "$branch" >/dev/null
    log "deleted branch $branch"
  fi
  removed=$((removed + 1))
done < <(git -C "$main_root" for-each-ref --format='%(refname:short)' refs/heads/)

log "Done: $removed removed$( ((dry_run)) && echo " (dry run)"), $kept kept"
