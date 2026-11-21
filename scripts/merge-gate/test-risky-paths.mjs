// Unit tests for the merge gate's risky-path matching (Bob's exact globs).
// Run: node --test scripts/merge-gate/test-risky-paths.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { matchRiskyPaths } from "./lib.mjs";

const config = JSON.parse(
  readFileSync(
    new URL("../../.github/merge-gate.config.json", import.meta.url),
    "utf8",
  ),
);
const categories = config.riskyPaths.categories;
const match = (files) => matchRiskyPaths(files, categories);
const names = (files) => match(files).map((m) => `${m.file}:${m.category}`);

test("each Bob glob category flags its own files", () => {
  assert.deepEqual(names([".github/workflows/build-apks.yml"]), [
    ".github/workflows/build-apks.yml:ci-publish",
  ]);
  assert.deepEqual(names(["scripts/make-update-manifest.sh"]), [
    "scripts/make-update-manifest.sh:ci-publish",
  ]);
  assert.deepEqual(names(["keystore.properties"]), [
    "keystore.properties:signing",
  ]);
  assert.deepEqual(names(["foo/bar.jks"]), ["foo/bar.jks:signing"]);
  assert.deepEqual(names(["smarttubetv/build.gradle"]), [
    "smarttubetv/build.gradle:signing",
  ]);
  assert.deepEqual(names(["common/build.gradle"]), [
    "common/build.gradle:build-config",
  ]);
  assert.deepEqual(names(["gradle.properties"]), [
    "gradle.properties:build-config",
  ]);
  assert.deepEqual(
    names(["common/src/stjukebox/res/values/update_urls.xml"]),
    ["common/src/stjukebox/res/values/update_urls.xml:updater"],
  );
  assert.deepEqual(
    names(["common/src/stjukebox/java/net/tfour/jukebox/room/JukeboxRoomClient.java"]),
    [
      "common/src/stjukebox/java/net/tfour/jukebox/room/JukeboxRoomClient.java:architecture",
    ],
  );
  assert.deepEqual(
    names(["smarttubetv/src/stjukebox/AndroidManifest.xml"]),
    ["smarttubetv/src/stjukebox/AndroidManifest.xml:architecture"],
  );
});

test("a test file on a risky path still flags (Bob's spec)", () => {
  assert.deepEqual(
    names(["common/src/stjukebox/java/net/tfour/jukebox/room/JukeboxProtocolTest.java"]),
    [
      "common/src/stjukebox/java/net/tfour/jukebox/room/JukeboxProtocolTest.java:architecture",
    ],
  );
});

test("upstream SmartTube paths do not flag", () => {
  assert.deepEqual(
    match([
      "common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/data/Video.java",
      "smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main/MainActivity.java",
      "README.md",
    ]),
    [],
  );
});

test("plain Jukebox source changes do not flag", () => {
  assert.deepEqual(
    match([
      "common/src/stjukebox/java/net/tfour/jukebox/update/JukeboxUpdate.java",
      "common/src/stjukebox/java/net/tfour/jukebox/JukeboxApp.java",
    ]),
    [],
  );
});

test("the gate's own diff flags (workflows are ci-publish)", () => {
  assert.deepEqual(names([".github/workflows/merge-gate.yml"]), [
    ".github/workflows/merge-gate.yml:ci-publish",
  ]);
});
