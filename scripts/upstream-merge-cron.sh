#!/usr/bin/env bash
set -euo pipefail

# Cron wrapper for the weekly upstream merge (crontab entry documented in
# docs/TESTING.md). Provides the environment cron does not, prevents
# overlapping runs with flock, and appends the full run (with a date
# header) to upstream-merge.log in the repo root.

export PATH="$HOME/.local/bin:$HOME/bin:$HOME/.opencode/bin:$PATH"
export JAVA_HOME="$HOME/jdk/jdk-17.0.21+9"
export ANDROID_HOME="$HOME/android-sdk"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# Same lock file as scripts/upstream-merge.sh, so cron runs and manual runs
# exclude each other.
LOCK_FILE="$REPO_DIR/.git/upstream-merge.lock"
LOG_FILE="$REPO_DIR/upstream-merge.log"

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "ERROR: another upstream-merge-cron run holds $LOCK_FILE; exiting" >&2
    exit 1
fi

status=0
{
    echo
    echo "======================================================================="
    echo "=== upstream-merge-cron start $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "======================================================================="
    # UPSTREAM_MERGE_LOCK_HELD: we already hold the repo lock (the same file
    #   the script locks itself), so the child must not try to take it again.
    # UPSTREAM_MERGE_LOG_FILE: tells the child to skip its tee (its output
    #   already lands in this log via the redirection below), so our end
    #   banner cannot race tee's final flush.
    UPSTREAM_MERGE_LOCK_HELD=1 UPSTREAM_MERGE_LOG_FILE="$LOG_FILE" \
        scripts/upstream-merge.sh || status=$?
    echo "=== upstream-merge-cron end $(date -u +%Y-%m-%dT%H:%M:%SZ) status=$status ==="
} >> "$LOG_FILE" 2>&1

exit "$status"
