import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { it } from "node:test";
import type { MontrerDeps } from "../src/attente/accueil";
import { construireAffiche, IMAGES_AFFICHE, lienAnime, MARQUE_AFFICHE, montrerAffiche } from "../src/attente/affiche";
import { AFFICHE_TEXTES, LIEN_DISCORD } from "../src/attente/textes";
import type { ActionFormSpec, FormAnswer } from "../src/ui/formulaires";

const sansCodes = (texte: string): string => texte.replace(/§./g, "");

it("l'affiche : le marqueur dans le titre, trois boutons dans l'ordre bannière, QR, suite", () => {
  const spec = construireAffiche();
  assert.ok(spec.title.startsWith(`${MARQUE_AFFICHE}§r`));
  assert.equal(sansCodes(spec.title), "LE DISCORD DE SILOOO");
  assert.equal(spec.elements?.length, 3);
  assert.deepEqual(spec.buttons.map((t) => t.slice(0, 6)), ["§1§1§r", "§2§2§r", "§3§3§r"]);
  const icones = spec.elements?.map((e) => (e.kind === "button" ? e.icon : "faux"));
  assert.deepEqual(icones, [IMAGES_AFFICHE.banniere, IMAGES_AFFICHE.qr, undefined]);
  assert.ok(spec.body.includes(LIEN_DISCORD));
});

it("l'affiche : les images sont dans le pack de la salle d'attente, et le QR mène à silooo.com/discord/", () => {
  const racine = resolve("..", "resource-pack-attente");
  for (const chemin of Object.values(IMAGES_AFFICHE)) {
    assert.ok(existsSync(resolve(racine, `${chemin}.png`)), `${chemin}.png manque`);
  }
  const png = readFileSync(resolve(racine, `${IMAGES_AFFICHE.qr}.png`));
  assert.equal(png.readUInt32BE(16), png.readUInt32BE(20), "le QR est carré");
  assert.equal(png.readUInt32BE(16) % 8, 0, "un module = 8 pixels entiers");
  assert.equal(LIEN_DISCORD, "silooo.com/discord");
});

it("l'affiche : les textes sans emoji ni mot banni", () => {
  const tout = Object.values(AFFICHE_TEXTES).join(" ");
  assert.ok(!/\p{Extended_Pictographic}/u.test(tout));
  assert.ok(!/…|—|!/.test(tout));
});

it("la ligne animée : toujours « silooo.com/discord », un reflet qui passe puis une pause", () => {
  const vus = new Set<string>();
  for (let pas = 0; pas < 200; pas += 1) {
    const ligne = lienAnime(pas);
    assert.equal(sansCodes(ligne), LIEN_DISCORD);
    assert.ok(!ligne.includes("§l"));
    vus.add(ligne);
  }
  assert.ok(vus.size > LIEN_DISCORD.length, "le reflet traverse toutes les lettres");
  assert.equal(lienAnime(0), lienAnime(50), "le cycle fait 50 pas");
  const reposee = lienAnime(LIEN_DISCORD.length + 5);
  assert.equal(reposee, `§b${LIEN_DISCORD}`);
  assert.ok(lienAnime(3).includes("§f"));
  assert.equal(lienAnime(-1).length > 0, true);
});

function deps(reponses: FormAnswer[], vues: ActionFormSpec[] = []): MontrerDeps {
  return {
    show: (spec) => {
      vues.push(spec);
      return Promise.resolve(reponses.shift() ?? { kind: "closed" });
    },
    attendre: () => Promise.resolve(),
    essais: 3,
  };
}

it("montrerAffiche : vue même si le joueur la ferme, réessaie quand le jeu est occupé, renonce après les essais", async () => {
  const vues: ActionFormSpec[] = [];
  assert.equal(await montrerAffiche(deps([{ kind: "closed" }], vues)), true);
  assert.equal(vues.length, 1);
  assert.equal(await montrerAffiche(deps([{ kind: "busy" }, { kind: "selected", index: 2 }])), true);
  const occupe = deps([{ kind: "busy" }, { kind: "busy" }, { kind: "busy" }]);
  assert.equal(await montrerAffiche(occupe), false);
  assert.equal(await montrerAffiche({ ...deps([{ kind: "busy" }]), essais: 1 }), false);
});
