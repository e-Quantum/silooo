// definitions/iles.json : le fichier réel passe toutes les règles de fond, et chaque règle attrape bien son cas.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import type { IlesDefinitions } from "../src/generated/definitions";
import { ILES, verifierIles } from "../src/monde/definitions";
import { FACTORY_NAMES } from "../src/ui/textes";

function lire<T>(nom: string): T {
  return JSON.parse(readFileSync(resolve("..", "definitions", `${nom}.json`), "utf8")) as T;
}

const TAILLE_DE_LA_CASE = lire<{ archipel: { ecartEntreCases: number } }>("monde").archipel.ecartEntreCases;

/** Une copie profonde modifiable des définitions réelles. */
function variante(change: (copie: Mutable) => void): IlesDefinitions {
  const copie = structuredClone(ILES) as unknown as Mutable;
  change(copie);
  return copie as unknown as IlesDefinitions;
}

interface Mutable {
  hauteur: number;
  centreDansLaCase: { x: number; z: number };
  anneau: { ilePrincipaleDemiCote: number; demiCote: number };
  plateforme: { rayon: number; rayonMax: number; couches: Array<{ materiau: string; retrait: number }> };
  apparition: { usine: string; distance: number; oooscar: { avant: number; droite: number } };
  usines: Array<{ id: string; angle: number; murs: string; largeur: number; profondeur: number; hauteur: number }>;
  cinematique: { plans: Array<{ duree: number }> };
}

function problemesDe(defs: IlesDefinitions): string {
  return verifierIles(defs, TAILLE_DE_LA_CASE).join(" | ");
}

describe("definitions/iles.json", () => {
  it("passe les règles de fond", () => {
    assert.deepEqual(verifierIles(ILES, TAILLE_DE_LA_CASE), []);
  });

  it("est le fichier lu sur le disque, pas une copie qui dérive", () => {
    assert.deepEqual(JSON.parse(JSON.stringify(ILES)), lire("iles"));
  });

  it("a une mini-île pour chaque usine de usines.json, et rien d'autre", () => {
    const catalogue = lire<{ usines: Array<{ id: string }> }>("usines").usines.map((usine) => usine.id).sort();
    assert.deepEqual(ILES.usines.map((usine) => usine.id).sort(), catalogue);
    assert.deepEqual(Object.keys(FACTORY_NAMES).sort(), catalogue);
  });

  it("place les usines autour du cercle, chacune à son angle et jamais deux au même", () => {
    const angles = ILES.usines.map((usine) => usine.angle);
    assert.equal(new Set(angles).size, angles.length);
    for (const angle of angles) assert.ok(angle >= 0 && angle < 360, `${angle}`);
  });

  it("donne l'usine de départ du design : la Carrière", () => {
    assert.equal(ILES.apparition.usine, "carriere");
  });

  it("tient la cinématique en quatre plans et vingt secondes", () => {
    assert.equal(ILES.cinematique.plans.length, 4);
    assert.equal(
      ILES.cinematique.plans.reduce((somme, plan) => somme + plan.duree, 0),
      20,
    );
  });

  it("garde les mini-îles dans la case de chaque entreprise (monde.json), même à leur plus grande taille", () => {
    const { x, z } = ILES.centreDansLaCase;
    const reach = ILES.anneau.demiCote + ILES.plateforme.rayonMax;
    assert.ok(x - reach >= 0 && x + reach < TAILLE_DE_LA_CASE);
    assert.ok(z - reach >= 0 && z + reach < TAILLE_DE_LA_CASE);
  });
});

describe("verifierIles attrape ses cas", () => {
  it("une usine en double", () => {
    assert.match(
      problemesDe(variante((d) => void (d.usines[1] = { ...structuredClone(d.usines[0]), angle: 24 } as Mutable["usines"][number]))),
      /apparaît deux fois/,
    );
  });

  it("une usine de départ inconnue", () => {
    assert.match(problemesDe(variante((d) => void (d.apparition.usine = "ailleurs"))), /n'est pas dans usines/);
  });

  it("une plateforme dont le dessus n'est pas de l'herbe, ou dont une couche s'élargit", () => {
    assert.match(problemesDe(variante((d) => void (d.plateforme.couches[0] = { materiau: "terre", retrait: 0 }))), /herbe/);
    assert.match(problemesDe(variante((d) => void (d.plateforme.couches[2] = { materiau: "terre", retrait: 0 }))), /plus large/);
    assert.match(problemesDe(variante((d) => void (d.plateforme.couches[0] = { materiau: "herbe", retrait: 1 }))), /retrait de 0/);
  });

  it("un anneau qui touche l'île principale ou qui sort de la case", () => {
    assert.match(problemesDe(variante((d) => void (d.anneau.demiCote = 270))), /touchent l'île principale/);
    assert.match(problemesDe(variante((d) => void (d.anneau.demiCote = 335))), /sort de la case/);
  });

  it("une île qui, à sa plus grande taille, toucherait l'île principale ou sortirait de la case", () => {
    assert.match(problemesDe(variante((d) => void (d.plateforme.rayonMax = 44))), /touchent l'île principale/);
    assert.match(problemesDe(variante((d) => {
      d.anneau.demiCote = 320;
      d.plateforme.rayonMax = 34;
    })), /sort de la case/);
    assert.equal(problemesDe(variante((d) => void (d.anneau.demiCote = 320))), "", "au rayon de départ, le même anneau tient");
  });

  it("une plus grande taille plus petite que la taille de départ", () => {
    assert.match(problemesDe(variante((d) => void (d.plateforme.rayonMax = 6))), /rayonMax est plus petit/);
  });

  it("un centre d'île principale qui n'est pas au milieu de la case", () => {
    assert.match(problemesDe(variante((d) => void (d.centreDansLaCase = { x: 330, z: 352 }))), /pas au milieu/);
  });

  it("deux mini-îles trop proches", () => {
    assert.match(problemesDe(variante((d) => void (d.usines[1] = { ...structuredClone(d.usines[1]), angle: 3 } as Mutable["usines"][number]))), /trop proches/);
  });

  it("un bâtiment de dimension paire ou qui déborde de la plateforme", () => {
    assert.match(problemesDe(variante((d) => void (d.usines[0] = { ...structuredClone(d.usines[0]), largeur: 6 } as Mutable["usines"][number]))), /impaires/);
    assert.match(problemesDe(variante((d) => void (d.usines[0] = { ...structuredClone(d.usines[0]), largeur: 15 } as Mutable["usines"][number]))), /débordent/);
  });

  it("un joueur qui apparaîtrait dans le bâtiment ou hors de la plateforme", () => {
    assert.match(problemesDe(variante((d) => void (d.apparition.distance = 2))), /dans le bâtiment/);
    assert.match(problemesDe(variante((d) => void (d.apparition.distance = 9))), /hors de la plateforme/);
  });

  it("une cinématique qui n'a pas quatre plans ou qui ne dure pas vingt secondes", () => {
    assert.match(problemesDe(variante((d) => void d.cinematique.plans.pop())), /3 plans/);
    assert.match(problemesDe(variante((d) => void (d.cinematique.plans[0] = { ...structuredClone(d.cinematique.plans[0]), duree: 6 } as Mutable["cinematique"]["plans"][number]))), /dure 21 s/);
  });
});
