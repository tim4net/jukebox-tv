# Merge gate

Tim's new PR flow (2026-11-20): a routine PR merges once CI is green, the
changed-line revert/mutation check passes, and there's a screenshot or live
check. Bob signs off only on risky changes. This gate automates the first
two checks for jukebox-tv.

## The two jobs

**1. Risky paths** (`.github/workflows/merge-gate.yml`, engine
`scripts/merge-gate/risky-paths.mjs`, globs in
`.github/merge-gate.config.json`).

Runs on every PR. When the PR touches payments, auth/2FA, secrets, CI
publish/release, migrations/schema or core architecture files (Bob's exact
globs — do not widen or narrow them without Bob), the job adds the
`needs-bob` label and posts one comment listing the files. It never fails
the gate — it routes the PR to Bob. A test-only change on a risky path
still flags. The engine is vendored from tim4net/partyq (same file, same
contract).

**2. PIT mutation check** (`.github/workflows/merge-gate-mutate.yml`,
engine `gradle/pitest-jukebox.gradle` + `scripts/merge-gate/pitest-targets.mjs`
+ `scripts/merge-gate/pitest-check.mjs`).

PIT 1.15.0 on the classes the PR changes under `net.tfour.jukebox` only
(upstream SmartTube packages are never mutated), run against the
stjukebox unit-test source sets (`:common:testStjukeboxDebugUnitTest`,
`:smarttubetv:testStjukeboxDebugUnitTest`). Threshold: no surviving mutant
on a changed line; if line-level data is unavailable, mutation score
>= 80% on the changed classes with every survivor listed. Survivors on
unchanged lines are listed but do not fail the gate.

Schedule (Bob's spec, hard): the `mutate` label and nightly — **never on
every push**, and never attached to `build-apks.yml`. Add the `mutate`
label to a PR to run the check there; nightly runs the same check over
the classes changed in the last 24h on the default branch, reusing PIT's
incremental history (cached between runs).

## Why PIT via the command line, not the Gradle plugin

The official plugin doesn't support Android modules and its Android fork
would force-upgrade this repo's AGP 7.4.2 to 8.5.1+ — out of scope for a
gate slice. So `gradle/pitest-jukebox.gradle` runs the same PIT engine
(org.pitest 1.15.0) through its command-line entry point; see the header
there. If the plugin situation changes, swap the task for the plugin; the
workflows, targets script and checker stay the same. Bob signs off on
this call (the gate touches CI publish).

## Local use

Install the pre-push hook once per clone (Bob's spec §b):

```
scripts/merge-gate/install-hooks.sh
```

Every push then runs the mutation check on the Jukebox classes it changes
(diffed against the merge-base with `origin/jukebox-p0-tv`). Skip it for
a docs-only push with `SKIP_MUTATION=1 git push` — CI on the `mutate`
label / nightly still runs the real check.

Run the pieces by hand:

```
# What would PIT target for this branch?
BASE_SHA=$(git merge-base HEAD origin/jukebox-p0-tv) node scripts/merge-gate/pitest-targets.mjs
# Run PIT for one module (lists come from pitest-targets.json):
./gradlew --no-daemon :common:pitestMutate \
  -PpitestTargetClasses=net.tfour.jukebox.room.JukeboxProtocol \
  -PpitestTargetTests=net.tfour.jukebox.room.JukeboxProtocolTest
# Enforce the threshold:
node scripts/merge-gate/pitest-check.mjs --targets pitest-targets.json
# Risky-path check for this branch:
BASE_SHA=$(git merge-base HEAD origin/jukebox-p0-tv) node scripts/merge-gate/risky-paths.mjs
# Risky-path unit tests:
node --test scripts/merge-gate/test-risky-paths.mjs
```

## Proof

- Demo PRs (linked in the gate PR): one passing case (a covered change —
  PIT green) and one deliberately failing case (an uncovered change —
  surviving mutant on a changed line). Both carry the `mutate` label and
  target this branch (not `jukebox-p0-tv`), so the gate runs on them
  without landing anything; they are closed unmerged.
- The gate PR itself demonstrates the risky-paths job: it touches
  `.github/workflows/**`, `smarttubetv/build.gradle` and
  `common/build.gradle`, so it gets `needs-bob` + a comment.

## Known gaps

- PIT + Robolectric: every stjukebox test runs under Robolectric, and PIT
  executes those tests in its own minion JVMs. The task forwards the
  unit-test task's system properties and classpath; if a class's tests
  cannot run under PIT at all, the checker lists the class as uncovered
  and fails the gate rather than silently passing it.
- Fork PRs can't write labels/comments with the default token, so the
  risky-paths job's label step is best-effort there.
- The ratchet issues (jscpd, CRAP, lint disables — jukebox-tv#48) are
  separate and out of scope here.
