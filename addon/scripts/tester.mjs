#!/usr/bin/env node
// Lance les tests de test/*.test.ts avec « node --test ». esbuild compile chaque fichier de test
// (avec le code de src/ qu'il importe) vers un dossier temporaire, puis Node les exécute : aucune
// dépendance de test, et les modules @minecraft/* ne sont jamais chargés.
//
// Usage : node scripts/tester.mjs [motif]   (le motif filtre les noms de fichiers de test)

import { build } from "esbuild";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const motif = process.argv[2] ?? "";

const tests = readdirSync(join(racine, "test"))
  .filter((nom) => nom.endsWith(".test.ts") && nom.includes(motif))
  .sort();
if (tests.length === 0) {
  console.error(`tester: aucun fichier test/*.test.ts${motif ? ` pour le motif « ${motif} »` : ""}`);
  process.exit(1);
}

const temporaire = mkdtempSync(join(tmpdir(), "silooo-addon-tests-"));
let code = 1;
try {
  await build({
    absWorkingDir: racine,
    entryPoints: tests.map((nom) => `test/${nom}`),
    outdir: temporaire,
    outbase: "test",
    outExtension: { ".js": ".mjs" },
    bundle: true,
    format: "esm",
    platform: "node",
    target: "node22",
    sourcemap: "inline",
    tsconfig: "tsconfig.test.json",
    logLevel: "warning",
  });
  const fichiers = tests.map((nom) => join(temporaire, nom.replace(/\.ts$/, ".mjs")));
  const resultat = spawnSync(process.execPath, ["--test", "--enable-source-maps", ...fichiers], {
    cwd: racine,
    stdio: "inherit",
  });
  code = resultat.status ?? 1;
} catch (erreur) {
  console.error(`tester: ${erreur instanceof Error ? erreur.message : erreur}`);
} finally {
  rmSync(temporaire, { recursive: true, force: true });
}
process.exit(code);
