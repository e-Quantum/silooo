// Garde-fous sur la forme du paquet : les règles du dépôt qu'un compilateur ne voit pas.

import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, it } from "node:test";
import { config } from "../src/config";

// npm lance les tests depuis addon/ ; tester.mjs fixe le dossier courant.
const racine = resolve(".");

function sources(dossier: string): string[] {
  return readdirSync(dossier, { withFileTypes: true }).flatMap((entree) => {
    const chemin = join(dossier, entree.name);
    if (entree.isDirectory()) return sources(chemin);
    return entree.name.endsWith(".ts") ? [chemin] : [];
  });
}

const fichiers = sources(join(racine, "src")).map((chemin) => ({
  nom: relative(join(racine, "src"), chemin).replaceAll("\\", "/"),
  texte: readFileSync(chemin, "utf8"),
}));

/** Les noms de module importés par un fichier : « from "x" », « import "x" » et « import("x") ». */
function importsDe(texte: string): string[] {
  const noms: string[] = [];
  for (const m of texte.matchAll(/(?:^|\n)\s*(?:import|export)\s[^;]*?\sfrom\s*["']([^"']+)["']/g)) noms.push(m[1] ?? "");
  for (const m of texte.matchAll(/(?:^|\n)\s*import\s*["']([^"']+)["']/g)) noms.push(m[1] ?? "");
  for (const m of texte.matchAll(/\bimport\(\s*["']([^"']+)["']\s*\)/g)) noms.push(m[1] ?? "");
  return noms;
}

describe("imports de src/", () => {
  it("les trouve (le contrôle ne passe pas à vide)", () => {
    assert.ok(fichiers.length >= 10);
    assert.ok(importsDe(fichiers.find((f) => f.nom === "main.ts")?.texte ?? "").includes("@minecraft/server"));
  });

  it("seul beta.ts importe server-net et server-admin", () => {
    for (const { nom, texte } of fichiers) {
      const bêta = importsDe(texte).filter((i) => i === "@minecraft/server-net" || i === "@minecraft/server-admin");
      if (nom === "beta.ts") assert.deepEqual([...new Set(bêta)].sort(), ["@minecraft/server-admin", "@minecraft/server-net"]);
      else assert.deepEqual(bêta, [], `${nom} importe un module bêta`);
    }
  });

  it("seuls main.ts et beta.ts importent @minecraft/* ; les tests n'ont donc jamais à les charger", () => {
    for (const { nom, texte } of fichiers) {
      const minecraft = importsDe(texte).filter((i) => i.startsWith("@minecraft/"));
      if (nom !== "main.ts" && nom !== "beta.ts") assert.deepEqual(minecraft, [], `${nom} importe @minecraft/*`);
    }
  });

  it("aucune API de Node dans le code de l'add-on", () => {
    for (const { nom, texte } of fichiers) {
      const noeud = importsDe(texte).filter((i) => i.startsWith("node:") || /^(fs|path|os|http|https|crypto|child_process|buffer|url|util)$/.test(i));
      assert.deepEqual(noeud, [], `${nom} importe un module de Node`);
      assert.doesNotMatch(texte, /\bprocess\.|\brequire\(|\bBuffer\b|\bsetTimeout\(|\bsetInterval\(/, `${nom} utilise une API absente de Bedrock`);
    }
  });

  it("un fichier généré n'importe rien", () => {
    for (const { nom, texte } of fichiers.filter((f) => f.nom.startsWith("generated/"))) {
      assert.deepEqual(importsDe(texte), [], `${nom} importe quelque chose`);
    }
  });
});

describe("hygiène du secret", () => {
  it("aucun fichier de src/ ne contient de clé, de jeton ni de « .env »", () => {
    for (const { nom, texte } of fichiers) {
      assert.doesNotMatch(texte, /Bearer\s+[A-Za-z0-9]/, nom);
      assert.doesNotMatch(texte, /\.env\b/, nom);
      assert.doesNotMatch(texte, /(?:api[_-]?key|token|password)\s*[:=]\s*["'][^"']{8,}["']/i, nom);
    }
  });

  it("la clé n'est lue qu'une fois, dans beta.ts, par secrets.get", () => {
    const lecteurs = fichiers.filter((f) => /\bsecrets\.get\(/.test(f.texte)).map((f) => f.nom);
    assert.deepEqual(lecteurs, ["beta.ts"]);
  });
});

describe("manifest.json", () => {
  const manifeste = JSON.parse(readFileSync(join(racine, "manifest.json"), "utf8")) as {
    format_version: number;
    header: { name: string; description: string; uuid: string; version: number[]; min_engine_version: number[] };
    modules: Array<{ type: string; language: string; uuid: string; version: number[]; entry: string }>;
    dependencies: Array<{ module_name: string; version: string }>;
  };
  const paquet = JSON.parse(readFileSync(join(racine, "package.json"), "utf8")) as {
    version: string;
    devDependencies: Record<string, string>;
  };
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  it("suit le relevé de docs/bedrock.md, fait 7", () => {
    assert.equal(manifeste.format_version, 2);
    assert.deepEqual(manifeste.header.min_engine_version, [1, 26, 60]);
    assert.equal(manifeste.modules.length, 1);
    const module = manifeste.modules[0];
    assert.equal(module?.type, "script");
    assert.equal(module?.language, "javascript");
    assert.equal(module?.entry, "scripts/main.js");
  });

  it("a deux uuid valides et différents", () => {
    const entete = manifeste.header.uuid;
    const module = manifeste.modules[0]?.uuid ?? "";
    assert.match(entete, uuid);
    assert.match(module, uuid);
    assert.notEqual(entete, module);
  });

  it("la version du pack suit celle du paquet npm", () => {
    assert.equal(manifeste.header.version.join("."), paquet.version);
    assert.equal(manifeste.modules[0]?.version.join("."), paquet.version);
  });

  it("déclare les quatre modules aux versions du relevé, celles des types installés pour les stables", () => {
    const versions = Object.fromEntries(manifeste.dependencies.map((d) => [d.module_name, d.version]));
    assert.deepEqual(versions, {
      "@minecraft/server": "2.11.0",
      "@minecraft/server-ui": "2.3.0",
      "@minecraft/server-admin": "1.0.0-beta",
      "@minecraft/server-net": "1.0.0-beta",
    });
    assert.equal(paquet.devDependencies["@minecraft/server"], versions["@minecraft/server"]);
    assert.equal(paquet.devDependencies["@minecraft/server-ui"], versions["@minecraft/server-ui"]);
    for (const nom of ["@minecraft/server-admin", "@minecraft/server-net"]) {
      assert.match(paquet.devDependencies[nom] ?? "", /^1\.0\.0-beta\.1\.26\.\d+-stable$/, `${nom} doit être épinglé, jamais latest`);
    }
  });

  it("le nom et la description sont non vides", () => {
    assert.ok(manifeste.header.name.length > 0);
    assert.ok(manifeste.header.description.length > 0);
  });
});

describe("config", () => {
  it("les réglages sont des durées positives, groupées dans config.ts", () => {
    for (const valeur of [
      config.requestTimeoutSeconds,
      config.startupDelaySeconds,
      config.heartbeatIntervalSeconds,
      config.events.pollIntervalSeconds,
      config.events.drainDelaySeconds,
      config.events.maxBackoffSeconds,
    ]) {
      assert.ok(Number.isFinite(valeur) && valeur > 0);
    }
    assert.ok(config.readRetries >= 0);
  });

});
