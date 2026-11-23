// Unit tests for pitest-check.mjs (Bob's fail-closed rules).
// Run: node --test scripts/merge-gate/test-pitest-check.mjs
//
// Each test builds a synthetic targets file + a synthetic PIT
// mutations.xml in a fresh temp dir and runs the checker against them —
// no Gradle, no Android SDK, no shared state between tests.

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CHECKER = new URL("./pitest-check.mjs", import.meta.url).pathname;
const CONFIG = new URL(
  "../../.github/merge-gate.config.json",
  import.meta.url,
).pathname;

const TARGET_CLASS = "net.tfour.jukebox.room.JukeboxProtocol";
const TARGET_TEST = "net.tfour.jukebox.room.JukeboxProtocolTest";
// Changed lines from Bob's demo numbers: [307,1] and [315,3].
const CHANGED = [[307, 1], [315, 3]];

function targetsJson(changedLines) {
  return {
    modules: {
      common: {
        targetClasses: [TARGET_CLASS],
        targetTests: [TARGET_TEST],
        changedLines: { [TARGET_CLASS]: changedLines },
      },
      smarttubetv: { targetClasses: [], targetTests: [], changedLines: {} },
    },
  };
}

function mutationsXml(mutants) {
  const body = mutants
    .map(
      (m) => `<mutation detected="false" status="${m.status}">
  <sourceFile>JukeboxProtocol.java</sourceFile>
  <mutatedClass>${m.cls || TARGET_CLASS}</mutatedClass>
  <mutatedMethod>normalizeJoinCode</mutatedMethod>
  <lineNumber>${m.line}</lineNumber>
</mutation>`,
    )
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="false">\n${body}\n</mutations>\n`;
}

function runChecker(targets, mutants) {
  const dir = mkdtempSync(join(tmpdir(), "pitest-check-test-"));
  try {
    const targetsPath = join(dir, "pitest-targets.json");
    const reportPath = join(dir, "mutations.xml");
    writeFileSync(targetsPath, JSON.stringify(targets));
    writeFileSync(reportPath, mutationsXml(mutants));
    const r = spawnSync(process.execPath, [CHECKER, "--targets", targetsPath], {
      encoding: "utf8",
      env: { ...process.env, CONFIG, REPORTS: reportPath },
    });
    return { status: r.status, stdout: r.stdout, stderr: r.stderr };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("TIMED_OUT on a changed line fails closed (Bob's synthetic case)", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "TIMED_OUT", line: 307 },
  ]);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /INCONCLUSIVE MUTANTS ON CHANGED LINES/);
  assert.match(r.stdout, /TIMED_OUT/);
  assert.match(r.stderr, /inconclusive mutant\(s\) on changed lines/);
});

test("MEMORY_ERROR on a changed line fails closed", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "MEMORY_ERROR", line: 315 },
  ]);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /MEMORY_ERROR/);
  assert.match(r.stderr, /inconclusive mutant\(s\) on changed lines/);
});

test("RUN_ERROR on a changed line fails closed", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "RUN_ERROR", line: 316 },
  ]);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /RUN_ERROR/);
  assert.match(r.stderr, /inconclusive mutant\(s\) on changed lines/);
});

test("TIMED_OUT on an unchanged line is listed, does not fail", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "TIMED_OUT", line: 400 },
  ]);
  assert.equal(r.status, 0);
  assert.match(r.stdout, /Inconclusive mutants elsewhere/);
  assert.match(r.stdout, /pitest-check: PASS/);
});

test("SURVIVED on a changed line still fails", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "SURVIVED", line: 307 },
  ]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /surviving mutant\(s\) on changed lines/);
});

test("NO_COVERAGE on a changed line still fails", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "NO_COVERAGE", line: 315 },
  ]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /have no coverage/);
});

test("all changed-line mutants killed passes", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "KILLED", line: 307 },
    { status: "KILLED", line: 315 },
  ]);
  assert.equal(r.status, 0);
  assert.match(r.stdout, /pitest-check: PASS/);
});

test("killed on a changed line + survivor elsewhere passes", () => {
  const r = runChecker(targetsJson(CHANGED), [
    { status: "KILLED", line: 307 },
    { status: "SURVIVED", line: 400 },
  ]);
  assert.equal(r.status, 0);
  assert.match(r.stdout, /Survivors elsewhere/);
  assert.match(r.stdout, /pitest-check: PASS/);
});
