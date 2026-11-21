#!/usr/bin/env node
// Merge-gate job 2: risky-path rules.
//
// Vendored from tim4net/partyq (scripts/merge-gate/) — same engine, same
// contract; the config here carries this repo's globs (Bob's exact spec).
// Match the PR's changed files against the risky-path categories in the
// config. Prints the matches as JSON and writes `risky` / `matches` to
// $GITHUB_OUTPUT for the workflow to label + comment on. Always exits 0 —
// flagging a PR for Bob is informational, not a failure.
//
// Usage (run from the repo root):
//   BASE_SHA=<base sha> node scripts/merge-gate/risky-paths.mjs
//
// Env:
//   BASE_SHA  base revision to diff against (required)
//   CONFIG    config path (default .github/merge-gate.config.json)

import { spawnSync } from "node:child_process";
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { matchRiskyPaths } from "./lib.mjs";

const BASE = process.env.BASE_SHA;
if (!BASE) {
  console.error("risky-paths: BASE_SHA is required");
  process.exit(2);
}
const CONFIG_PATH =
  process.env.CONFIG || ".github/merge-gate.config.json";
const config = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
const categories = (config.riskyPaths || {}).categories || [];

const diff = spawnSync("git", ["diff", "--name-only", `${BASE}...HEAD`], {
  encoding: "utf8",
});
if (diff.status !== 0) {
  console.error(`risky-paths: git diff failed: ${diff.stderr}`);
  process.exit(2);
}
const files = diff.stdout.split("\n").map((s) => s.trim()).filter(Boolean);
const matches = matchRiskyPaths(files, categories);
const risky = matches.length > 0;

console.log(JSON.stringify({ risky, matches }, null, 2));

if (process.env.GITHUB_OUTPUT) {
  appendFileSync(
    process.env.GITHUB_OUTPUT,
    `risky=${risky}\nmatches=${JSON.stringify(matches)}\n`,
  );
}
if (process.env.GITHUB_STEP_SUMMARY) {
  const lines = [
    "## Merge gate — risky paths",
    "",
    risky
      ? "⚠️ This PR touches paths that need Bob's sign-off:"
      : "No risky paths touched. ✅",
    "",
    ...matches.map(
      (m) =>
        `- \`${m.file}\` — **${m.category}**${m.description ? `: ${m.description}` : ""}`,
    ),
  ];
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join("\n") + "\n");
}
if (risky) {
  // Comment body for the workflow to post (dedupe marker included).
  const body = [
    "<!-- merge-gate-risky -->",
    "### ⚠️ Merge gate: needs Bob's sign-off",
    "",
    "This PR touches paths the merge gate flags as risky. Bob signs off before it merges.",
    "",
    ...matches.map(
      (m) =>
        `- \`${m.file}\` — **${m.category}**${m.description ? `: ${m.description}` : ""}`,
    ),
    "",
    "_Posted by the merge gate (`.github/merge-gate.config.json` — Bob's exact globs, gate spec 2026-11-20)._",
    "",
  ].join("\n");
  writeFileSync(
    process.env.RISKY_COMMENT_FILE || "merge-gate-risky-comment.md",
    body,
  );
}
process.exit(0);
