// Pure helpers for the merge gate (revert check + risky paths).
//
// Vendored from tim4net/partyq (scripts/merge-gate/lib.mjs), trimmed to
// what this repo uses: glob matching for the risky-paths job and
// name-status parsing for pitest-targets. The revert-check helpers
// (coveringTestsFor et al.) are NOT vendored — jukebox-tv uses PIT,
// not the revert harness. The config here carries this repo's globs
// (Bob's exact spec). Kept dependency-free (no node builtins) so it can
// be unit-tested anywhere — see scripts/merge-gate/test-risky-paths.mjs.

/** Convert a glob (`**`, `*`, `?`) to a RegExp anchored on the full path. */
export function globToRegExp(glob) {
  let re = "^";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        // `**` crosses `/`; a trailing `/**` also matches the bare dir.
        if (glob[i + 2] === "/") {
          re += "(?:.*/)?";
          i += 2;
        } else {
          re += ".*";
          i += 1;
        }
      } else {
        re += "[^/]*";
      }
    } else if (c === "?") {
      re += "[^/]";
    } else {
      re += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp(re + "$");
}

/** True when `path` matches any glob in `globs`. */
export function matchesAnyGlob(path, globs) {
  return (globs || []).some((g) => globToRegExp(g).test(path));
}

/**
 * Parse `git diff --name-status` output.
 * Returns [{ status: "A"|"M"|"D"|"R", path, oldPath? }].
 * Rename lines look like `R100\told\tnew`.
 */
export function parseNameStatus(output) {
  const rows = [];
  for (const line of (output || "").split("\n")) {
    if (!line.trim()) continue;
    const parts = line.split("\t");
    const code = parts[0];
    if (code.startsWith("R")) {
      rows.push({ status: "R", oldPath: parts[1], path: parts[2] });
    } else if (["A", "M", "D"].includes(code)) {
      rows.push({ status: code, path: parts[1] });
    }
    // Anything else (e.g. "T" typechange) is ignored by the gate.
  }
  return rows;
}

/**
 * Match changed files against risky-path categories.
 *
 * @param {string[]} files - repo-relative changed paths
 * @param {{name: string, description?: string, globs: string[]}[]} categories
 * @returns {{file: string, category: string, description?: string}[]}
 */
export function matchRiskyPaths(files, categories) {
  const hits = [];
  for (const file of files) {
    for (const cat of categories || []) {
      if (matchesAnyGlob(file, cat.globs)) {
        hits.push({
          file,
          category: cat.name,
          description: cat.description,
        });
      }
    }
  }
  return hits;
}
