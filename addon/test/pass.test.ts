import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { BOUTIQUE } from "../src/monde/definitions";
import type { PassState } from "../src/protocol";
import type { ActionOutcome, CatalogOutcome, PassOutcome } from "../src/ui/actions";
import { ICON } from "../src/ui/apparence";
import type { Screen } from "../src/ui/ecran";
import { claimableCount, cosmeticName, palierScreen, paliersScreen, passNotice, passOf, passScreen, passTile, rewardState } from "../src/ui/pass";
import { BOUTIQUE_TEXTES, PASS_TEXTES, USINES_TEXTES } from "../src/ui/textes";
import { passClaimed, passState, shopCatalog } from "./helpers";
import { bare } from "./ui-helpers";

const passOutcome = (overrides: Partial<PassState> = {}): PassOutcome => ({ kind: "pass", pass: passState(overrides) });
const FAILED: PassOutcome = { kind: "failed", failure: "network", reason: "RESEAU" };
const catalogOf = (changes: Parameters<typeof shopCatalog>[0] = {}): CatalogOutcome => ({ kind: "catalog", items: shopCatalog(changes) });

const labelsOf = (screen: Screen): string[] => screen.spec.buttons.map(bare);
const kindsOf = (screen: Screen): string[] => screen.targets.map((target) => (target.kind === "go" ? `go:${target.route.screen}` : target.kind));

function must<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("valeur absente");
  return value;
}

describe("rewardState : où en est une récompense d'un palier", () => {
  // passState() : 120 points, premium non, le palier 1 gratuit réclamé ; paliers à 40, 100 et 180 points.
  const pass = passState();
  const [un, deux, trois] = pass.tiers.map(must);

  it("rien sur la piste : aucune", () => {
    assert.equal(rewardState(pass, must(deux), "PREMIUM"), "aucune");
  });

  it("une récompense dans la liste des réclamés du service : réclamée", () => {
    assert.equal(rewardState(pass, must(un), "GRATUIT"), "reclame");
  });

  it("des points qui suffisent : à réclamer ; des points qui manquent : pas atteint, même sur la piste premium", () => {
    assert.equal(rewardState(pass, must(deux), "GRATUIT"), "aReclamer");
    assert.equal(rewardState(pass, must(trois), "GRATUIT"), "pasAtteint");
    assert.equal(rewardState(passState({ premium: true }), must(trois), "PREMIUM"), "pasAtteint");
  });

  it("la piste premium sans l'avoir achetée : le premium est requis ; achetée, la récompense est à réclamer", () => {
    assert.equal(rewardState(pass, must(un), "PREMIUM"), "premiumRequis");
    assert.equal(rewardState(passState({ premium: true }), must(un), "PREMIUM"), "aReclamer");
  });

  it("un palier réclamé sur la piste premium reste réclamé", () => {
    assert.equal(rewardState(passState({ premium: true, claimed: ["1-PREMIUM"] }), must(un), "PREMIUM"), "reclame");
  });

  it("un palier atteint pile au seuil est à réclamer", () => {
    assert.equal(rewardState(passState({ points: 100 }), must(deux), "GRATUIT"), "aReclamer");
    assert.equal(rewardState(passState({ points: 99 }), must(deux), "GRATUIT"), "pasAtteint");
  });
});

describe("claimableCount, passOf et cosmeticName", () => {
  it("compte les récompenses à réclamer sur les deux pistes", () => {
    assert.equal(claimableCount(passState()), 1);
    assert.equal(claimableCount(passState({ premium: true })), 2);
    assert.equal(claimableCount(passState({ points: 0 })), 0);
    assert.equal(claimableCount(passState({ points: 1_000, premium: true, claimed: [] })), 5);
    assert.equal(claimableCount(passState({ tiers: [] })), 0);
  });

  it("passOf rend la lecture du pass, ou rien quand elle manque ou a échoué", () => {
    assert.equal(passOf(passOutcome())?.seasonId, "saison-1");
    assert.equal(passOf(FAILED), undefined);
    assert.equal(passOf(undefined), undefined);
  });

  it("cosmeticName rend le nom du JSON, ou l'identifiant quand le JSON ne le connaît pas", () => {
    const connu = must(BOUTIQUE.cosmetiques.find((cosmetic) => cosmetic.id === "s1-fanion"));
    assert.equal(cosmeticName("s1-fanion"), connu.nom);
    assert.equal(cosmeticName("inconnu-du-json"), "inconnu-du-json");
  });
});

describe("passTile", () => {
  it("sans lecture du pass, la tuile n'a pas de ligne d'info ; lu, elle dit combien de récompenses attendent", () => {
    for (const outcome of [undefined, FAILED]) {
      assert.equal(bare(passTile(outcome).text), `${ICON.pass} ${BOUTIQUE_TEXTES.rayons.pass}`);
    }
    assert.equal(bare(passTile(passOutcome()).text), `${ICON.pass} ${BOUTIQUE_TEXTES.rayons.pass}\n${PASS_TEXTES.aReclamer(1)}`);
    assert.equal(bare(passTile(passOutcome({ points: 0 })).text).split("\n")[1], PASS_TEXTES.aReclamer(0));
    assert.deepEqual(passTile(passOutcome()).target, { kind: "go", route: { screen: "pass" } });
  });

  it("aReclamer accorde le singulier et le pluriel", () => {
    assert.equal(PASS_TEXTES.aReclamer(0), "Rien à réclamer");
    assert.equal(PASS_TEXTES.aReclamer(1), "1 récompense à réclamer");
    assert.equal(PASS_TEXTES.aReclamer(4), "4 récompenses à réclamer");
  });
});

describe("passScreen", () => {
  it("sans lecture du pass : undefined", () => {
    assert.equal(passScreen(undefined, catalogOf()), undefined);
    assert.equal(passScreen(FAILED, catalogOf()), undefined);
  });

  it("la saison, ses dates en français, les points et l'état de la piste premium, d'après le service", () => {
    const screen = must(passScreen(passOutcome({ name: "Saison 1", points: 1_250 }), catalogOf()));
    assert.equal(screen.spec.title, PASS_TEXTES.titre);
    const body = bare(screen.spec.body);
    assert.ok(body.includes("Saison 1"), body);
    assert.ok(body.includes(PASS_TEXTES.dates("05/11/2026", "31/01/2027")), body);
    assert.ok(body.includes(PASS_TEXTES.points("1 250")), body);
    assert.ok(body.includes(PASS_TEXTES.premium.inactif), body);
    assert.ok(!body.includes(PASS_TEXTES.pasEnCours), body);
  });

  it("un jour qui n'a pas la forme du service est rendu tel quel", () => {
    const body = bare(must(passScreen(passOutcome({ startsOn: "bientôt" }), catalogOf())).spec.body);
    assert.ok(body.includes("Du bientôt au 31/01/2027"), body);
  });

  it("les boutons : les paliers (avec ce qui attend), Passer premium au prix du catalogue, Retour", () => {
    const screen = must(passScreen(passOutcome(), catalogOf({ "pass-premium": { gemmes: 321 } })));
    assert.deepEqual(labelsOf(screen), [
      `${ICON.pass} ${PASS_TEXTES.paliers.page}\n${PASS_TEXTES.aReclamer(1)}`,
      `${ICON.gemmes} ${PASS_TEXTES.passerPremium}\n${PASS_TEXTES.prixPremium("321")}`,
      "Retour",
    ]);
    assert.deepEqual(screen.targets, [
      { kind: "go", route: { screen: "paliers", page: 0 } },
      { kind: "buyItem", itemId: "pass-premium" },
      { kind: "go", route: { screen: "boutique" } },
    ]);
  });

  it("pas de Passer premium quand la piste est déjà ouverte, quand le catalogue dit qu'elle est possédée ou qu'elle n'est pas en vente, ou quand on ne le sait pas", () => {
    const sans = (screen: Screen | undefined) => kindsOf(must(screen));
    assert.deepEqual(sans(passScreen(passOutcome({ premium: true }), catalogOf())), ["go:paliers", "go:boutique"]);
    assert.deepEqual(sans(passScreen(passOutcome(), catalogOf({ "pass-premium": { owned: true } }))), ["go:paliers", "go:boutique"]);
    assert.deepEqual(sans(passScreen(passOutcome(), catalogOf({ "pass-premium": { available: false } }))), ["go:paliers", "go:boutique"]);
    assert.deepEqual(sans(passScreen(passOutcome(), undefined)), ["go:paliers", "go:boutique"]);
    assert.deepEqual(sans(passScreen(passOutcome(), { kind: "failed", failure: "network", reason: "RESEAU" })), ["go:paliers", "go:boutique"]);
  });

  it("la piste premium ouverte est marquée ; une saison hors dates le dit avec le cadenas", () => {
    const ouverte = bare(must(passScreen(passOutcome({ premium: true }), catalogOf())).spec.body);
    assert.ok(ouverte.includes(`${ICON.possede} ${PASS_TEXTES.premium.actif}`), ouverte);
    const finie = bare(must(passScreen(passOutcome({ active: false }), catalogOf())).spec.body);
    assert.ok(finie.includes(`${ICON.cadenas} ${PASS_TEXTES.pasEnCours}`), finie);
  });

  it("la bulle de l'action vient en tête du corps", () => {
    const screen = must(passScreen(passOutcome(), catalogOf(), { bubble: PASS_TEXTES.bulles.palierReclame, lines: ["Reçu : 1 Lingot"] }));
    const body = bare(screen.spec.body);
    assert.ok(body.indexOf(PASS_TEXTES.bulles.palierReclame.text) < body.indexOf("Reçu : 1 Lingot"));
    assert.ok(body.indexOf("Reçu : 1 Lingot") < body.indexOf("Saison 1"));
  });
});

describe("paliersScreen", () => {
  it("sans lecture du pass : undefined", () => {
    assert.equal(paliersScreen(undefined, 0, 8), undefined);
    assert.equal(paliersScreen(FAILED, 0, 8), undefined);
  });

  it("une tuile par palier, dans l'ordre du service, avec son repère : à réclamer, réclamé, premium requis, pas atteint", () => {
    // 120 points : palier 1 (gratuit réclamé, premium à acheter), palier 2 (gratuit à réclamer), palier 3 (pas atteint).
    const screen = must(paliersScreen(passOutcome(), 0, 8));
    assert.equal(screen.spec.title, PASS_TEXTES.paliers.titre);
    assert.deepEqual(labelsOf(screen), [
      `${ICON.cadenas} Palier 1\n${PASS_TEXTES.etats.premiumRequis}`,
      `${ICON.palierAReclamer} Palier 2\n${PASS_TEXTES.etats.aReclamer}`,
      `${ICON.cadenas} Palier 3\n${PASS_TEXTES.tuile.des("180")}`,
      "Retour",
    ]);
    assert.deepEqual(screen.targets[0], { kind: "go", route: { screen: "palier", tier: 1, page: 0 } });
    assert.deepEqual(screen.targets[3], { kind: "go", route: { screen: "pass" } });
    assert.ok(bare(screen.spec.body).includes(PASS_TEXTES.points("120")));
  });

  it("un palier dont tout est réclamé est marqué fait, pas à réclamer", () => {
    const screen = must(paliersScreen(passOutcome({ premium: true, claimed: ["1-GRATUIT", "1-PREMIUM"] }), 0, 8));
    assert.equal(labelsOf(screen)[0], `${ICON.palierFait} Palier 1\n${PASS_TEXTES.etats.reclame}`);
  });

  it("des pages : chaque tuile sait sur quelle page elle tombe, la navigation vient après", () => {
    const premiere = must(paliersScreen(passOutcome(), 0, 2));
    assert.deepEqual(kindsOf(premiere), ["go:palier", "go:palier", "go:paliers", "go:pass"]);
    assert.deepEqual(labelsOf(premiere).slice(2), ["Suite", "Retour"]);
    assert.ok(bare(premiere.spec.body).includes(USINES_TEXTES.page(1, 2)));
    const seconde = must(paliersScreen(passOutcome(), 99, 2));
    assert.deepEqual(seconde.targets[0], { kind: "go", route: { screen: "palier", tier: 3, page: 1 } });
    assert.deepEqual(labelsOf(seconde).slice(1), ["Précédent", "Retour"]);
  });
});

describe("palierScreen", () => {
  it("sans lecture du pass, ou pour un palier qui n'existe plus : undefined", () => {
    assert.equal(palierScreen(undefined, 1, 0), undefined);
    assert.equal(palierScreen(FAILED, 1, 0), undefined);
    assert.equal(palierScreen(passOutcome(), 99, 0), undefined);
  });

  it("le seuil, les deux pistes avec leur récompense et leur état, un bouton pour la récompense à réclamer", () => {
    const screen = must(palierScreen(passOutcome(), 2, 0));
    assert.equal(screen.spec.title, PASS_TEXTES.palier.titre);
    const body = bare(screen.spec.body);
    assert.ok(body.includes("Palier 2"), body);
    assert.ok(body.includes(PASS_TEXTES.seuil("100")), body);
    assert.ok(body.includes(PASS_TEXTES.ligne("Gratuit", "1 Lingot")), body);
    assert.ok(body.includes(`${ICON.palierAReclamer} ${PASS_TEXTES.etats.aReclamer}`), body);
    assert.ok(body.includes(PASS_TEXTES.ligne("Premium", PASS_TEXTES.etats.aucune)), body);
    assert.deepEqual(labelsOf(screen), [PASS_TEXTES.reclamer.GRATUIT, "Retour"]);
    assert.deepEqual(screen.targets, [
      { kind: "claim", tier: 2, track: "GRATUIT" },
      { kind: "go", route: { screen: "paliers", page: 0 } },
    ]);
  });

  it("un palier réclamé n'a plus de bouton, et le premium sans achat n'en a pas non plus, mais dit ce qu'il faut", () => {
    const screen = must(palierScreen(passOutcome(), 1, 3));
    const body = bare(screen.spec.body);
    assert.ok(body.includes(`${ICON.palierFait} ${PASS_TEXTES.etats.reclame}`), body);
    assert.ok(body.includes(PASS_TEXTES.ligne("Premium", cosmeticName("s1-fanion"))), body);
    assert.ok(body.includes(`${ICON.cadenas} ${PASS_TEXTES.etats.premiumRequis}`), body);
    assert.deepEqual(screen.targets, [{ kind: "go", route: { screen: "paliers", page: 3 } }]);
  });

  it("avec le premium, la récompense de la piste premium se réclame, après celle de la piste gratuite", () => {
    const screen = must(palierScreen(passOutcome({ premium: true, claimed: [] }), 1, 0));
    assert.deepEqual(labelsOf(screen), [PASS_TEXTES.reclamer.GRATUIT, PASS_TEXTES.reclamer.PREMIUM, "Retour"]);
    assert.deepEqual(screen.targets.slice(0, 2), [
      { kind: "claim", tier: 1, track: "GRATUIT" },
      { kind: "claim", tier: 1, track: "PREMIUM" },
    ]);
  });

  it("un palier pas atteint dit qu'il ne l'est pas, sans bouton", () => {
    const screen = must(palierScreen(passOutcome(), 3, 0));
    assert.ok(bare(screen.spec.body).includes(`${ICON.cadenas} ${PASS_TEXTES.etats.pasAtteint}`));
    assert.deepEqual(kindsOf(screen), ["go:paliers"]);
  });
});

describe("passNotice", () => {
  const claimed = (overrides: Parameters<typeof passClaimed>[0] = {}): ActionOutcome => ({ kind: "passClaimed", claimed: passClaimed(overrides) });

  it("des Lingots versés : la bulle, puis ce qui a été reçu, tel que le service l'a dit", () => {
    assert.deepEqual(passNotice(claimed({ rewardLingots: 3 })), {
      bubble: PASS_TEXTES.bulles.palierReclame,
      lines: [PASS_TEXTES.recu("3 Lingots")],
    });
    assert.deepEqual(passNotice(claimed({ rewardLingots: 1 }))?.lines, [PASS_TEXTES.recu("1 Lingot")]);
  });

  it("un cosmétique : son nom du JSON", () => {
    assert.deepEqual(passNotice(claimed({ track: "PREMIUM", rewardLingots: 0, cosmeticId: "s1-lanterne" })), {
      bubble: PASS_TEXTES.bulles.palierReclame,
      lines: [PASS_TEXTES.recu(cosmeticName("s1-lanterne"))],
    });
  });

  it("rien de versé : la bulle seule ; un autre résultat : undefined, le menu prend sa bulle", () => {
    assert.deepEqual(passNotice(claimed({ rewardLingots: 0, cosmeticId: null }))?.lines, []);
    assert.equal(passNotice({ kind: "busy" }), undefined);
    assert.equal(passNotice({ kind: "refused", reason: "DEJA_RECLAME" }), undefined);
  });
});
