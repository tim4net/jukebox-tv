#!/usr/bin/env node
// Merge-gate mutation job: enforce Bob's threshold from PIT reports.
//
// Reads the PIT XML reports (one per module, written by the Gradle
// `pitestMutate` task) plus the targets file from pitest-targets.mjs, and
// fails the gate when:
//   1. any surviving mutant sits on a changed line of a changed class, or
//   2. any mutant on a changed line has NO_COVERAGE — the changed code is
//      not executed by any test at all (PIT never runs such mutants, so
//      they do not "survive"; untested changed code still fails the gate), or
//   3. the mutation score on changed classes is below the configured
//      minimum (only used when changed-line data is unavailable).
// Survivors on UNCHANGED lines are listed but do not fail the gate.
// Classes with no mutants at all (e.g. no runnable test) are listed as
// uncovered and fail the gate — a change with no mutation coverage is not
// "covered".
//
// Mutated classes are matched to their outer class (PIT reports nested
// classes as Outer$Inner; the Gradle task targets Outer$* too, so a change
// inside a nested class is checked against the outer class's changed
// lines).
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
const targetsEq = args.find((a) => a.startsWith("--targets="));
const targetsIdx = args.indexOf("--targets");
const TARGETS_PATH = targetsEq
  ? targetsEq.slice("--targets=".length)
  : targetsIdx >= 0 && args[targetsIdx + 1]
    ? args[targetsIdx + 1]
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

// Minimal XML scan for PIT's <mutation detected=.. status=..> element
// (status is an ATTRIBUTE on <mutation>; mutatedClass and lineNumber are
// child elements). PIT's mutations.xml is flat; a regex scan is enough and
// keeps this dependency-free.
const MUT_RE =
  /<mutation[^>]*\bstatus=["']([^"']+)["'][^>]*>[\s\S]*?<mutatedClass>([^<]+)<\/mutatedClass>[\s\S]*?<lineNumber>(\d+)<\/lineNumber>[\s\S]*?<\/mutation>/g;

let killed = 0;
let survived = 0;
let total = 0;
const survivorsOnChangedLines = [];
const noCoverageOnChangedLines = [];
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
    const [, status, cls, lineStr] = m;
    const line = Number(lineStr);
    // PIT reports nested classes as Outer$Inner; match them to the outer
    // class the targets file names.
    const outer = cls.split("$")[0];
    total++;
    seenClasses.add(outer);
    const onChangedLine = targetClasses.has(outer) && inChangedLines(outer, line);
    if (status === "SURVIVED") {
      survived++;
      const entry = { class: cls, line };
      if (onChangedLine) {
        survivorsOnChangedLines.push(entry);
      } else {
        survivorsElsewhere.push(entry);
      }
    } else if (status === "KILLED") {
      killed++;
    } else if (status === "NO_COVERAGE" && onChangedLine) {
      // Untested changed code: PIT never runs these mutants, so they do
      // not show up as survivors — fail closed instead.
      noCoverageOnChangedLines.push({ class: cls, line });
    }
    // TIMED_OUT / MEMORY_ERROR / RUN_ERROR / NON_VIABLE count as
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
if (noCoverageOnChangedLines.length > 0) {
  console.log("NO-COVERAGE MUTANTS ON CHANGED LINES (fail — changed code no test executes):");
  for (const s of noCoverageOnChangedLines) {
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
if (noCoverageOnChangedLines.length > 0) {
  console.error(
    `pitest-check: FAIL — ${noCoverageOnChangedLines.length} mutant(s) on changed lines have no coverage (changed code no test executes).`,
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
