#!/usr/bin/env node
// Merge-gate mutation job: enforce Bob's threshold from PIT reports.
//
// Reads the PIT XML reports (one per module, written by the Gradle
// `pitestMutate` task) plus the targets file from pitest-targets.mjs, and
// fails the gate when:
//   1. any surviving mutant sits on a changed line of a changed class, or
//   2. the mutation score on changed classes is below the configured
//      minimum (only used when changed-line data is unavailable).
// Survivors on UNCHANGED lines are listed but do not fail the gate.
// Classes with no mutants at all (e.g. no runnable test) are listed as
// uncovered and fail the gate — a change with no mutation coverage is not
// "covered".
//
// Usage (run from the repo root):
//   node scripts/merge-gate/pitest-check.mjs [--targets pitest-targets.json]
//
// Env:
//   CONFIG   config path (default .github/merge-gate.config.json)
//   REPORTS  comma-separated PIT mutations.xml paths
//            (default common/build/reports/pitest/mutations.xml,
//             smarttubetv/build/reports/pitest/mutations.xml)

import { existsSync, readFileSync } from "node:fs";

const args = process.argv.slice(2);
const targetsFlag = args.find((a) => a.startsWith("--targets="));
const TARGETS_PATH = targetsFlag
  ? targetsFlag.slice("--targets=".length)
  : "pitest-targets.json";
const CONFIG_PATH = process.env.CONFIG || ".github/merge-gate.config.json";
const REPORT_PATHS = (process.env.REPORTS ||
  "common/build/reports/pitest/mutations.xml,smarttubetv/build/reports/pitest/mutations.xml")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

const config = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
const minScore = config.mutation?.minMutationScorePercent ?? 80;
const targets = JSON.parse(readFileSync(TARGETS_PATH, "utf8"));

// changedLines: fqcn -> [[start, count], ...]
const changedLines = {};
for (const m of Object.values(targets.modules || {})) {
  Object.assign(changedLines, m.changedLines || {});
}
const targetClasses = new Set(
  Object.values(targets.modules || {}).flatMap((m) => m.targetClasses || []),
);

function inChangedLines(fqcn, line) {
  for (const [start, count] of changedLines[fqcn] || []) {
    if (line >= start && line < start + count) return true;
  }
  return false;
}

// Minimal XML scan for <mutation ...><mutatedClass>..</mutatedClass>
// <lineNumber>..</lineNumber><status>SURVIVED|KILLED|...</status></mutation>.
// PIT's mutations.xml is flat; a regex scan is enough and keeps this
// dependency-free.
const MUT_RE =
  /<mutation[^>]*>[\s\S]*?<mutatedClass>([^<]+)<\/mutatedClass>[\s\S]*?<lineNumber>(\d+)<\/lineNumber>[\s\S]*?<status>([^<]+)<\/status>[\s\S]*?<\/mutation>/g;

let killed = 0;
let survived = 0;
let total = 0;
const survivorsOnChangedLines = [];
const survivorsElsewhere = [];
const seenClasses = new Set();
let reportsFound = 0;

for (const reportPath of REPORT_PATHS) {
  if (!existsSync(reportPath)) {
    console.log(`pitest-check: no report at ${reportPath} (module had no targets, skipping)`);
    continue;
  }
  reportsFound++;
  const xml = readFileSync(reportPath, "utf8");
  for (const m of xml.matchAll(MUT_RE)) {
    const [, cls, lineStr, status] = m;
    const line = Number(lineStr);
    total++;
    seenClasses.add(cls);
    if (status === "SURVIVED") {
      survived++;
      const entry = { class: cls, line };
      if (targetClasses.has(cls) && inChangedLines(cls, line)) {
        survivorsOnChangedLines.push(entry);
      } else {
        survivorsElsewhere.push(entry);
      }
    } else if (status === "KILLED") {
      killed++;
    }
    // NO_COVERAGE / TIMED_OUT / MEMORY_ERROR / RUN_ERROR count as
    // neither killed nor survived in the score; they are listed below.
  }
}

const uncovered = [...targetClasses].filter((c) => !seenClasses.has(c));
const score = total > 0 ? (100 * killed) / total : 0;

console.log(`pitest-check: ${reportsFound} report(s), ${total} mutant(s), ` +
  `${killed} killed, ${survived} survived, score ${score.toFixed(1)}% ` +
  `(minimum ${minScore}%)`);
if (survivorsOnChangedLines.length > 0) {
  console.log("SURVIVORS ON CHANGED LINES (fail):");
  for (const s of survivorsOnChangedLines) {
    console.log(`  - ${s.class}:${s.line}`);
  }
}
if (survivorsElsewhere.length > 0) {
  console.log("Survivors elsewhere (listed, do not fail the gate):");
  for (const s of survivorsElsewhere) {
    console.log(`  - ${s.class}:${s.line}`);
  }
}
if (uncovered.length > 0) {
  console.log("Changed classes with NO mutants (uncovered — fail):");
  for (const c of uncovered) console.log(`  - ${c}`);
}

const hasChangedLineData = Object.keys(changedLines).length > 0;
let failed = false;
if (survivorsOnChangedLines.length > 0) {
  console.error(
    `pitest-check: FAIL — ${survivorsOnChangedLines.length} surviving mutant(s) on changed lines.`,
  );
  failed = true;
}
if (uncovered.length > 0) {
  console.error(
    `pitest-check: FAIL — ${uncovered.length} changed class(es) produced no mutants (no runnable test covers them).`,
  );
  failed = true;
}
if (!hasChangedLineData && total > 0 && score < minScore) {
  console.error(
    `pitest-check: FAIL — no changed-line data, mutation score ${score.toFixed(1)}% < ${minScore}%.`,
  );
  failed = true;
}
if (!failed) console.log("pitest-check: PASS");
process.exit(failed ? 1 : 0);
