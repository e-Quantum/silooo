// Le dossier statique pack/ (l'entité Oooscar) et sa copie par scripts/construire.mjs dans dist/pack et le .mcpack.
// Ces tests lancent le vrai script de construction vers un dossier temporaire : dist/ n'est jamais touché.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { after, before, describe, it } from "node:test";
import { unzipSync } from "fflate";
import { config } from "../src/config";

// npm lance les tests depuis addon/ ; tester.mjs fixe le dossier courant.
const racine = resolve(".");
const script = join(racine, "scripts", "construire.mjs");
const source = join(racine, "pack", "entities", "oooscar.json");

/** Les fichiers du dossier statique pack/, en chemins relatifs avec des « / » : ce que le .mcpack doit contenir. */
function fichiersStatiques(dossier: string, prefixe = ""): string[] {
  return readdirSync(dossier).flatMap((nom) => {
    const complet = join(dossier, nom);
    return statSync(complet).isDirectory() ? fichiersStatiques(complet, `${prefixe}${nom}/`) : [`${prefixe}${nom}`];
  });
}

interface Sortie {
  readonly status: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

function construire(...options: string[]): Sortie {
  const resultat = spawnSync(process.execPath, [script, ...options], { cwd: racine, encoding: "utf8" });
  return { status: resultat.status, stdout: resultat.stdout, stderr: resultat.stderr };
}

const temporaires: string[] = [];

/** Un dossier neuf, effacé à la fin des tests. */
function dossierNeuf(nom: string): string {
  const parent = mkdtempSync(join(tmpdir(), "silooo-pack-test-"));
  temporaires.push(parent);
  return join(parent, nom);
}

after(() => {
  for (const dossier of temporaires) rmSync(dossier, { recursive: true, force: true });
});

interface EntiteDeComportement {
  readonly format_version: string;
  readonly "minecraft:entity": {
    readonly description: { readonly identifier: string; readonly is_spawnable?: boolean; readonly is_summonable?: boolean };
    readonly components: Record<string, unknown>;
  };
}

function entite(): EntiteDeComportement {
  return JSON.parse(readFileSync(source, "utf8")) as EntiteDeComportement;
}

describe("pack/entities/oooscar.json", () => {
  it("est l'entité que le code fait apparaître", () => {
    assert.equal(entite()["minecraft:entity"].description.identifier, config.world.oooscarType);
    assert.match(entite().format_version, /^1\.\d+\.\d+$/);
  });

  it("ne sort pas d'un œuf, mais se fait apparaître par le script", () => {
    const { description } = entite()["minecraft:entity"];
    assert.equal(description.is_spawnable, false);
    assert.equal(description.is_summonable, true);
  });

  it("ne bouge pas, ne pousse personne et ne se laisse pas pousser", () => {
    const { components } = entite()["minecraft:entity"];
    assert.deepEqual(components["minecraft:movement"], { value: 0 });
    assert.deepEqual(components["minecraft:pushable"], { is_pushable: false, is_pushable_by_piston: false });
    const comportements = Object.keys(components).filter((nom) => nom.startsWith("minecraft:behavior.") || nom.startsWith("minecraft:navigation."));
    assert.deepEqual(comportements, [], "aucun comportement ni déplacement");
  });

  it("n'est pas hostile : aucune attaque, aucune cible", () => {
    const { components } = entite()["minecraft:entity"];
    for (const nom of ["minecraft:attack", "minecraft:behavior.melee_attack", "minecraft:behavior.nearest_attackable_target", "minecraft:target_nearby_sensor"]) {
      assert.ok(!(nom in components), nom);
    }
  });

  it("ne reçoit aucun dégât, d'où qu'ils viennent", () => {
    const capteur = entite()["minecraft:entity"].components["minecraft:damage_sensor"] as { triggers: Array<{ cause: string; deals_damage: string }> };
    assert.ok(capteur.triggers.some((t) => t.cause === "all" && t.deals_damage === "no"));
  });

  it("ne laisse rien en mourant et n'est jamais persistante : elle se retire d'elle-même loin du joueur", () => {
    const texte = JSON.stringify(entite());
    assert.doesNotMatch(texte, /loot|drop/i);
    assert.doesNotMatch(texte, /persistent/i);
    const { components } = entite()["minecraft:entity"];
    assert.ok("minecraft:despawn" in components);
    for (const nom of ["minecraft:inventory", "minecraft:equipment", "minecraft:shareables", "minecraft:rideable", "minecraft:leashable"]) {
      assert.ok(!(nom in components), nom);
    }
  });
});

describe("scripts/construire.mjs et le dossier statique", () => {
  const sortie = dossierNeuf("sortie");
  let resultat: Sortie;

  before(() => {
    resultat = construire("--sortie", sortie);
  });

  it("construit sans erreur et dit ce qu'il a copié de pack/", () => {
    assert.equal(resultat.status, 0, resultat.stderr);
    assert.match(resultat.stdout, /entities\/oooscar\.json/);
  });

  it("copie pack/ tel quel dans dist/pack, à côté du manifeste et du script", () => {
    assert.ok(existsSync(join(sortie, "pack", "manifest.json")));
    assert.ok(existsSync(join(sortie, "pack", "scripts", "main.js")));
    const copie = readFileSync(join(sortie, "pack", "entities", "oooscar.json"));
    assert.deepEqual(copie, readFileSync(source));
  });

  it("met aussi pack/ dans le .mcpack, avec le manifeste et le script", () => {
    const entrees = unzipSync(new Uint8Array(readFileSync(join(sortie, "silooo-addon.mcpack"))));
    // Tout le dossier statique (Oooscar, blocs Silooo, structures de la Carrière), plus le manifeste et le script.
    const attendus = [...fichiersStatiques(join(racine, "pack")), "manifest.json", "scripts/main.js"].sort();
    assert.ok(attendus.includes("entities/oooscar.json"));
    assert.deepEqual(Object.keys(entrees).sort(), attendus);
    for (const name of ["carte", "objectif", "usine", "ville"]) {
      assert.equal(new TextDecoder().decode(entrees[`items/${name}.json`]), readFileSync(join(racine, "pack/items", `${name}.json`), "utf8"));
    }
    const entree = entrees["entities/oooscar.json"];
    assert.ok(entree !== undefined);
    assert.equal(new TextDecoder().decode(entree), readFileSync(source, "utf8"));
  });

  it("donne le même .mcpack à chaque construction", () => {
    const autre = dossierNeuf("autre");
    assert.equal(construire("--sortie", autre).status, 0);
    assert.deepEqual(readFileSync(join(autre, "silooo-addon.mcpack")), readFileSync(join(sortie, "silooo-addon.mcpack")));
  });

  it("refuse un fichier JSON invalide dans pack/, en le nommant", () => {
    const statique = dossierNeuf("statique");
    mkdirSync(join(statique, "entities"), { recursive: true });
    writeFileSync(join(statique, "entities", "casse.json"), "{ pas du json");
    const echec = construire("--sortie", dossierNeuf("sortie-casse"), "--statique", statique);
    assert.equal(echec.status, 1);
    assert.match(echec.stderr, /entities\/casse\.json n'est pas du JSON valide/);
  });

  it("refuse que pack/ remplace le manifeste ou le script de la construction", () => {
    for (const intrus of ["manifest.json", join("scripts", "main.js")]) {
      const statique = dossierNeuf("statique");
      mkdirSync(join(statique, "scripts"), { recursive: true });
      writeFileSync(join(statique, intrus), "{}");
      const echec = construire("--sortie", dossierNeuf("sortie-intrus"), "--statique", statique);
      assert.equal(echec.status, 1, intrus);
      assert.match(echec.stderr, /produits par la construction/);
    }
  });

  it("n'efface jamais un dossier de sortie choisi à la main qui n'est pas vide", () => {
    const occupe = dossierNeuf("occupe");
    mkdirSync(occupe, { recursive: true });
    const precieux = join(occupe, "precieux.txt");
    writeFileSync(precieux, "à garder");
    const echec = construire("--sortie", occupe);
    assert.equal(echec.status, 1);
    assert.match(echec.stderr, /n'est pas vide/);
    assert.equal(readFileSync(precieux, "utf8"), "à garder");
  });

  it("sans dossier statique, la construction reste possible", () => {
    const vide = dossierNeuf("vide");
    const ok = construire("--sortie", dossierNeuf("sortie-vide"), "--statique", vide);
    assert.equal(ok.status, 0, ok.stderr);
  });
});
