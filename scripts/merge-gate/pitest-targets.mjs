#!/usr/bin/env node
// Merge-gate mutation job: map a PR diff to PIT target classes.
//
// For every changed production file under a stjukebox source root, derive
// the fully-qualified class name, its covering test class(es), and the
// changed line ranges (new-file side). Only classes under
// net.tfour.jukebox are in scope (Bob's spec) — upstream SmartTube
// packages are never mutated.
//
// Usage (run from the repo root):
//   BASE_SHA=<base sha> node scripts/merge-gate/pitest-targets.mjs
//
// Env:
//   BASE_SHA  base revision to diff against (required)
//   OUT       output JSON path (default pitest-targets.json)
//
// Output JSON shape:
//   { "modules": {
//       "common": { "targetClasses": [...], "targetTests": [...],
//                   "changedLines": { "<fqcn>": [[start, count], ...] } },
//       "smarttubetv": { ... } } }

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseNameStatus } from "./lib.mjs";

const BASE = process.env.BASE_SHA;
if (!BASE) {
  console.error("pitest-targets: BASE_SHA is required");
  process.exit(2);
}
const OUT = process.env.OUT || "pitest-targets.json";

// Production source roots, per module. Only stjukebox flavour sources hold
// Jukebox code; main/ sources are upstream SmartTube and out of scope.
const SOURCE_ROOTS = {
  common: "common/src/stjukebox/java",
  smarttubetv: "smarttubetv/src/stjukebox/java",
};
const TEST_ROOTS = {
  common: "common/src/testStjukebox/java",
  smarttubetv: "smarttubetv/src/testStjukebox/java",
};

function git(args) {
  const r = spawnSync("git", args, { encoding: "utf8" });
  if (r.status !== 0) {
    console.error(`pitest-targets: git ${args.join(" ")} failed: ${r.stderr}`);
    process.exit(2);
  }
  return r.stdout;
}

function classNameFor(module, file) {
  const root = SOURCE_ROOTS[module];
  if (!file.startsWith(root + "/")) return null;
  const rel = file.slice(root.length + 1).replace(/\.(java|kt)$/, "");
  const fqcn = rel.split("/").join(".");
  // Scope guard (Bob's spec): only net.tfour.jukebox classes.
  if (!fqcn.startsWith("net.tfour.jukebox.")) return null;
  return fqcn;
}

/** Added-line ranges [[start, count], ...] on the new-file side of a diff. */
function changedLineRanges(file) {
  const diff = git(["diff", "-U0", `${BASE}...HEAD`, "--", file]);
  const ranges = [];
  for (const line of diff.split("\n")) {
    const m = line.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/);
    if (m) {
      const start = Number(m[1]);
      const count = m[2] === undefined ? 1 : Number(m[2]);
      if (count > 0) ranges.push([start, count]);
    }
  }
  return ranges;
}

function listTestFiles(root) {
  const out = [];
  if (!existsSync(root)) return out;
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(java|kt)$/.test(e.name)) out.push(p);
    }
  };
  walk(root);
  return out;
}

const status = parseNameStatus(
  git(["diff", "--name-status", `${BASE}...HEAD`]),
);

const modules = {};
for (const module of Object.keys(SOURCE_ROOTS)) {
  modules[module] = { targetClasses: [], targetTests: [], changedLines: {} };
}

// Covering tests: same-basename *Test in the same package, or any test file
// that imports the class. Test files are read once per module.
const testFilesByModule = {};
for (const module of Object.keys(TEST_ROOTS)) {
  testFilesByModule[module] = listTestFiles(TEST_ROOTS[module]).map((p) => ({
    path: p,
    content: readFileSync(p, "utf8"),
  }));
}

for (const row of status) {
  if (row.status === "D") continue; // deleted file: nothing to mutate
  const module = Object.keys(SOURCE_ROOTS).find((m) =>
    row.path.startsWith(SOURCE_ROOTS[m] + "/"),
  );
  if (!module) continue;
  const fqcn = classNameFor(module, row.path);
  if (!fqcn) continue;
  const m = modules[module];
  if (!m.targetClasses.includes(fqcn)) m.targetClasses.push(fqcn);
  m.changedLines[fqcn] = changedLineRanges(row.path);

  const simple = fqcn.split(".").pop();
  for (const t of testFilesByModule[module]) {
    const testBase = t.path.split("/").pop().replace(/\.(java|kt)$/, "");
    const importsClass = new RegExp(
      `^import\\s+${fqcn.replace(/\./g, "\\.")};`,
      "m",
    ).test(t.content);
    if (testBase === `${simple}Test` || importsClass) {
      const testFqcn = t.path
        .slice(TEST_ROOTS[module].length + 1)
        .replace(/\.(java|kt)$/, "")
        .split("/")
        .join(".");
      if (!m.targetTests.includes(testFqcn)) m.targetTests.push(testFqcn);
    }
  }
}

for (const module of Object.keys(modules)) {
  modules[module].targetClasses.sort();
  modules[module].targetTests.sort();
}

const result = { modules };
writeFileSync(OUT, JSON.stringify(result, null, 2) + "\n");
for (const [module, m] of Object.entries(modules)) {
  console.log(
    `${module}: ${m.targetClasses.length} target class(es), ` +
      `${m.targetTests.length} target test(s)`,
  );
  for (const c of m.targetClasses) console.log(`  - ${c}`);
}
