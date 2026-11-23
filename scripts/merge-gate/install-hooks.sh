#!/bin/sh
# Install the merge-gate git hooks for this clone (Bob's spec §b).
# The hooks live in .githooks/ (checked in); this points git at them.
# Run once per clone:
#   scripts/merge-gate/install-hooks.sh
set -e
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/pre-push
echo "merge-gate hooks installed (core.hooksPath=.githooks)."
echo "Pre-push runs the PIT mutation check on changed Jukebox classes."
echo "Skip it for a docs-only push with: SKIP_MUTATION=1 git push"
