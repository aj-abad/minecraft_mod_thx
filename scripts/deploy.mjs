#!/usr/bin/env node
// Build the 1.7.10 mod and copy the reobfuscated jar into the Minecraft mods
// folder.
//
//   npm run deploy            build, then copy
//   npm run build             build only         (--no-copy)
//   npm run copy              copy existing jar  (--no-build)
//
// Destination defaults to the standard per-OS .minecraft/mods; override it with
// the MINECRAFT_MODS environment variable.

import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync, mkdirSync, copyFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

const root = process.cwd();
const libs = path.join(root, "build", "libs");

const skipBuild = process.argv.includes("--no-build");
const skipCopy = process.argv.includes("--no-copy");

// A .bat wrapper needs a shell on Windows; pass the whole command as one string
// (not an args array) so shell:true doesn't trip Node's DEP0190 warning.
function run(command) {
  const res = spawnSync(command, { stdio: "inherit", shell: true, cwd: root });
  if (res.status !== 0) {
    console.error(`\n"${command}" failed (exit ${res.status ?? res.signal}).`);
    process.exit(res.status ?? 1);
  }
}

// Read the mod version straight from the Gradle build so the jar name we look
// for never drifts out of sync with it.
function projectVersion() {
  const txt = readFileSync(path.join(root, "build.gradle.kts"), "utf8");
  const m = txt.match(/^\s*version\s*=\s*"([^"]+)"/m);
  if (!m) throw new Error('Could not find `version = "..."` in build.gradle.kts');
  return m[1];
}

// The production artifact is the classifier-less jar (<name>-<version>.jar).
// The deobfuscated "-dev" jar would crash a real client, so never pick that one:
// matching the exact `-<version>.jar` suffix excludes every classifier.
function findReobfJar() {
  const suffix = `-${projectVersion()}.jar`;
  let jars;
  try {
    jars = readdirSync(libs).filter((f) => f.endsWith(suffix));
  } catch {
    throw new Error(`No build output in ${libs} - build first (npm run build).`);
  }
  if (jars.length === 0) {
    throw new Error(`No reobfuscated jar (*${suffix}) in ${libs}. Did the build succeed?`);
  }
  jars.sort((a, b) => statSync(path.join(libs, b)).mtimeMs - statSync(path.join(libs, a)).mtimeMs);
  return path.join(libs, jars[0]);
}

function modsDir() {
  if (process.env.MINECRAFT_MODS) return process.env.MINECRAFT_MODS;
  if (process.platform === "win32") {
    const appData = process.env.APPDATA ?? path.join(homedir(), "AppData", "Roaming");
    return path.join(appData, ".minecraft", "mods");
  }
  if (process.platform === "darwin") {
    return path.join(homedir(), "Library", "Application Support", "minecraft", "mods");
  }
  return path.join(homedir(), ".minecraft", "mods");
}

if (!skipBuild) {
  const gradlew = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
  console.log("Building mod...");
  run(`${gradlew} build`);
}

if (!skipCopy) {
  const jar = findReobfJar();
  const dest = modsDir();
  mkdirSync(dest, { recursive: true });
  const target = path.join(dest, path.basename(jar));
  copyFileSync(jar, target);
  console.log(`Copied ${path.basename(jar)} -> ${target}`);
}
