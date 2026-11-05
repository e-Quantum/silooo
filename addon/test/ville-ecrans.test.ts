// Les écrans de la ville (ui/ville.ts) : des fonctions pures, une vue de l'entreprise entre, un écran sort. Chaque chiffre
// affiché doit venir de CompanyState.city ou de definitions/ville.json : les tests changent l'un ou l'autre et regardent
// l'écran suivre. Rien ici n'importe @minecraft/*.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { VilleDefinitions } from "../src/generated/definitions";
import { VILLE } from "../src/monde/definitions";
import type { CompanyState } from "../src/protocol";
import type { ActionOutcome } from "../src/ui/actions";
import { COLOR } from "../src/ui/apparence";
import type { CompanyView } from "../src/ui/etat";
import { noticeOf } from "../src/ui/menu";
import { BATIMENT_IDS, BATIMENT_NOMS, CARTE_TEXTES, refusalBubble, USINES_TEXTES, VILLE_TEXTES, batimentName } from "../src/ui/textes";
import {
  batimentScreen,
  batimentsScreen,
  construireScreen,
  hallOf,
  ilePrincipaleTile,
  nextRotation,
  poserScreen,
  villeNotice,
  villeScreen,
  villeTile,
} from "../src/ui/ville";
import { XUID, bare, fakeClock, viewsWith } from "./ui-helpers";
import { cityBuilding, cityState, emptyCity, house, noBonuses } from "./ville-helpers";

function viewOf(state: Partial<CompanyState> = {}): CompanyView {
  const view = viewsWith(state, fakeClock(0)).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

const cityView = (city: NonNullable<CompanyState["city"]>) => viewOf({ city });

/** La ville d'une entreprise bien avancée : mairie 3, deux maisons, un commerce, une route, des bonus. */
function grownCity() {
  return cityState({
    mairieLevel: 3,
    nextZone: { zoneIndex: 4, priceLingots: 1_250, mairieLevelRequired: 4, activeFactoriesRequired: 0 },
    buildings: [
      house({ buildingId: 2, level: 2, bonus: 2, x: 128, z: 128 }),
      cityBuilding({ buildingId: 1, level: 3, nextLevel: { priceLingots: 3, mairieLevelRequired: 1 } }),
      cityBuilding({ buildingId: 3, buildingType: "route", level: 1, maxLevel: 2, x: 144, z: 128, bonusType: null, bonus: null, nextLevel: { priceLingots: 2, mairieLevelRequired: 3 } }),
      cityBuilding({ buildingId: 4, buildingType: "commerce", level: 1, maxLevel: 4, x: 160, z: 128, bonusType: "PRODUCTION_DONAR", bonus: 0.5, nextLevel: { priceLingots: 15, mairieLevelRequired: 4 } }),
    ],
    bonuses: noBonuses({ housingPlaces: 2, housedStaff: 1, donarProductionPercent: 0.5, deliverySpeedPercent: 0 }),
  });
}

const targetsOf = (screen: { targets: readonly { kind: string }[] }) => screen.targets.map((target) => target.kind);
const route = (screen: { targets: readonly unknown[] }, index: number) => {
  const target = screen.targets[index] as { kind: string; route?: unknown };
  return target.kind === "go" ? target.route : target;
};

// ---------------------------------------------------------------- les tuiles

describe("la tuile Ville du menu", () => {
  it("sans ville dans l'état : fermée, grise, avec les usines que la définition demande", () => {
    const tile = villeTile(viewOf());
    assert.equal(tile.target.kind, "soon");
    assert.ok(tile.text.startsWith(COLOR.locked));
    assert.ok(bare(tile.text).includes("[X]"));
    assert.match(bare(tile.text), /Ville\nIl faut 2 usines actives\.$/);
  });

  it("le nombre d'usines de la tuile fermée suit definitions/ville.json", () => {
    const ville: VilleDefinitions = { ...VILLE, zones: { ...VILLE.zones, premiere: { usinesActivesMin: 5 } } };
    assert.match(bare(villeTile(viewOf(), ville).text), /Il faut 5 usines actives\.$/);
    const un: VilleDefinitions = { ...VILLE, zones: { ...VILLE.zones, premiere: { usinesActivesMin: 1 } } };
    assert.match(bare(villeTile(viewOf(), un).text), /Il faut 1 usine active\.$/);
  });

  it("avant la première zone : ouverte, avec les usines actives que le service demande", () => {
    const tile = villeTile(cityView(emptyCity()));
    assert.deepEqual(tile.target, { kind: "go", route: { screen: "ville" } });
    assert.ok(!tile.text.startsWith(COLOR.locked));
    assert.match(bare(tile.text), /Ville\nPremière zone : 2 usines actives$/);
    const trois = emptyCity({ nextZone: { zoneIndex: 1, priceLingots: 1, mairieLevelRequired: 1, activeFactoriesRequired: 3 } });
    assert.match(bare(villeTile(cityView(trois)).text), /Première zone : 3 usines actives$/);
  });

  it("avec une zone : ouverte, avec le niveau de la mairie du service", () => {
    assert.match(bare(villeTile(cityView(cityState({ mairieLevel: 4 }))).text), /Ville\nMairie niveau 4$/);
    assert.deepEqual(villeTile(cityView(cityState())).target, { kind: "go", route: { screen: "ville" } });
  });

  it("toutes les zones à soi : ouverte, niveau de la mairie", () => {
    const tile = villeTile(cityView(cityState({ mairieLevel: 16, nextZone: null })));
    assert.match(bare(tile.text), /Mairie niveau 16$/);
  });
});

describe("la tuile Île principale de la carte", () => {
  const label = CARTE_TEXTES.ilePrincipale;
  const locked = CARTE_TEXTES.ilePrincipaleVerrouillee;

  it("sans ville : fermée, avec ce qu'il faut pour l'ouvrir", () => {
    const tile = ilePrincipaleTile(viewOf(), label, locked);
    assert.equal(tile.target.kind, "soon");
    assert.ok(tile.text.startsWith(COLOR.locked));
    assert.ok(bare(tile.text).endsWith(locked));
  });

  it("avant la première zone : fermée", () => {
    assert.equal(ilePrincipaleTile(cityView(emptyCity()), label, locked).target.kind, "soon");
  });

  it("une zone mais pas de mairie dans l'état : fermée", () => {
    assert.equal(ilePrincipaleTile(cityView(cityState({ buildings: [house()] })), label, locked).target.kind, "soon");
  });

  it("une zone et la mairie : un voyage, avec le niveau de la mairie", () => {
    const tile = ilePrincipaleTile(cityView(cityState({ mairieLevel: 2 })), label, locked);
    assert.deepEqual(tile.target, { kind: "travelCity" });
    assert.match(bare(tile.text), /Île principale\nMairie niveau 2$/);
  });

  it("hallOf trouve la mairie par son type", () => {
    assert.equal(hallOf(undefined), undefined);
    assert.equal(hallOf(emptyCity()), undefined);
    assert.equal(hallOf(grownCity())?.buildingId, 1);
  });
});

// ---------------------------------------------------------------- la ville

describe("l'écran Ville", () => {
  it("sans ville dans l'état : pas d'écran", () => {
    assert.equal(villeScreen(viewOf()), undefined);
  });

  it("avant la première zone : une phrase, la zone 1 avec ses usines, un bouton Acheter, pas de bâtiments", () => {
    const screen = villeScreen(cityView(emptyCity()));
    assert.ok(screen);
    assert.equal(screen.spec.title, "VILLE");
    const body = bare(screen.spec.body);
    assert.match(body, /^Tu n'as pas encore de zone\./);
    assert.match(body, /Zone 1\n1 Lingots\nMairie niveau 1 requise\n2 usines actives requises$/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Acheter la zone 1\n1 Lingots", "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "buyZone", zoneIndex: 1 });
    assert.deepEqual(route(screen, 1), { screen: "main" });
  });

  it("avec des zones : la mairie en gras, les bonus du service, la prochaine zone et quatre boutons", () => {
    const screen = villeScreen(cityView(grownCity()));
    assert.ok(screen);
    assert.ok(screen.spec.body.startsWith(`${COLOR.bold}Mairie niveau 3${COLOR.reset}`));
    const body = bare(screen.spec.body);
    assert.match(body, /Bonus\nPersonnel logé 1 sur 2\nDonar \+0,5 %\n/);
    assert.doesNotMatch(body, /Livraison/);
    assert.match(body, /Prochaine zone\nZone 4\n1 250 Lingots\nMairie niveau 4 requise$/);
    assert.doesNotMatch(body, /usines actives requises/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Acheter la zone 4\n1 250 Lingots", "Bâtiments\n4 bâtiments", "Construire", "Retour"]);
    assert.deepEqual(targetsOf(screen), ["buyZone", "go", "go", "go"]);
    assert.deepEqual(screen.targets[0], { kind: "buyZone", zoneIndex: 4 });
    assert.deepEqual(route(screen, 1), { screen: "batiments", page: 0 });
    assert.deepEqual(route(screen, 2), { screen: "construire" });
    assert.deepEqual(route(screen, 3), { screen: "main" });
  });

  it("chaque bonus du service s'affiche, et seulement ceux qui ne sont pas nuls", () => {
    const city = cityState({ bonuses: noBonuses({ housingPlaces: 6, housedStaff: 6, donarProductionPercent: 2.5, deliverySpeedPercent: 15 }) });
    const body = bare(villeScreen(cityView(city))?.spec.body ?? "");
    assert.match(body, /Personnel logé 6 sur 6\nDonar \+2,5 %\nLivraison \+15 %/);
  });

  it("aucun bonus : la phrase qui le dit", () => {
    const body = bare(villeScreen(cityView(cityState()))?.spec.body ?? "");
    assert.match(body, /Bonus\nAucun bonus pour l'instant\./);
  });

  it("les chiffres suivent le service : un autre prix, une autre mairie, une autre zone", () => {
    const city = cityState({ mairieLevel: 9, nextZone: { zoneIndex: 12, priceLingots: 777, mairieLevelRequired: 11, activeFactoriesRequired: 0 } });
    const screen = villeScreen(cityView(city));
    assert.match(bare(screen?.spec.body ?? ""), /^Mairie niveau 9/);
    assert.match(bare(screen?.spec.body ?? ""), /Zone 12\n777 Lingots\nMairie niveau 11 requise$/);
    assert.deepEqual(screen?.targets[0], { kind: "buyZone", zoneIndex: 12 });
  });

  it("toutes les zones à l'entreprise : pas de bouton Acheter, la phrase qui le dit", () => {
    const screen = villeScreen(cityView(cityState({ mairieLevel: 16, nextZone: null })));
    assert.ok(screen);
    assert.match(bare(screen.spec.body), /Toutes les zones sont à toi\.$/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Bâtiments\n1 bâtiment", "Construire", "Retour"]);
    assert.ok(screen.targets.every((target) => target.kind !== "buyZone"));
  });

  it("une bulle d'Oooscar passe avant le corps", () => {
    const screen = villeScreen(cityView(cityState()), { bubble: VILLE_TEXTES.bulles.zoneAchetee });
    assert.match(bare(screen?.spec.body ?? ""), /^Oooscar \[joie\] Nouvelle zone achetée !\n\nMairie niveau 1/);
  });

  it("tient dans les huit tuiles d'un écran", () => {
    for (const city of [emptyCity(), cityState(), grownCity(), cityState({ nextZone: null })]) {
      assert.ok((villeScreen(cityView(city))?.spec.buttons.length ?? 0) <= 8);
    }
  });
});

// ---------------------------------------------------------------- les bâtiments

describe("la liste des bâtiments", () => {
  it("sans ville : pas d'écran", () => {
    assert.equal(batimentsScreen(viewOf(), 0, 8), undefined);
  });

  it("la mairie d'abord, puis les autres dans l'ordre du service, avec leur nom, leur niveau et leur bonus", () => {
    const screen = batimentsScreen(cityView(grownCity()), 0, 8);
    assert.ok(screen);
    assert.equal(screen.spec.title, "VILLE");
    assert.deepEqual(screen.spec.buttons.map(bare), [
      "[V] Mairie niveau 3\nParcelle 192, 192",
      "[V] Maison niveau 2\nLogements 2",
      "[V] Route niveau 1\nParcelle 144, 128",
      "[V] Commerce niveau 1\nDonar +0,5 %",
      "Retour",
    ]);
    assert.deepEqual(screen.targets.slice(0, 4).map((target) => (target.kind === "go" ? target.route : target)), [
      { screen: "batiment", buildingId: 1, page: 0 },
      { screen: "batiment", buildingId: 2, page: 0 },
      { screen: "batiment", buildingId: 3, page: 0 },
      { screen: "batiment", buildingId: 4, page: 0 },
    ]);
    assert.deepEqual(route(screen, 4), { screen: "ville" });
  });

  it("pagine : Suite, Précédent, et chaque fiche garde la page d'où l'on vient", () => {
    const view = cityView(grownCity());
    const first = batimentsScreen(view, 0, 2);
    assert.ok(first);
    assert.match(bare(first.spec.body), new RegExp(USINES_TEXTES.page(1, 2).replace(/[()]/g, "\\$&")));
    assert.deepEqual(first.spec.buttons.map((text) => bare(text).split("\n")[0]), ["[V] Mairie niveau 3", "[V] Maison niveau 2", USINES_TEXTES.suite, "Retour"]);
    assert.deepEqual(route(first, 2), { screen: "batiments", page: 1 });
    const second = batimentsScreen(view, 1, 2);
    assert.ok(second);
    assert.deepEqual(second.spec.buttons.map((text) => bare(text).split("\n")[0]), ["[V] Route niveau 1", "[V] Commerce niveau 1", USINES_TEXTES.precedent, "Retour"]);
    assert.deepEqual(route(second, 0), { screen: "batiment", buildingId: 3, page: 1 });
    assert.deepEqual(route(second, 2), { screen: "batiments", page: 0 });
  });

  it("une page trop loin est ramenée à la dernière", () => {
    const screen = batimentsScreen(cityView(grownCity()), 9, 2);
    assert.deepEqual(route(screen ?? { targets: [] }, 0), { screen: "batiment", buildingId: 3, page: 1 });
  });

  it("une liste vide le dit", () => {
    const screen = batimentsScreen(cityView(emptyCity()), 0, 8);
    assert.match(bare(screen?.spec.body ?? ""), /^Aucun bâtiment pour l'instant\.$/);
    assert.deepEqual(screen?.spec.buttons.map(bare), ["Retour"]);
  });

  it("un type que l'add-on ne connaît pas porte un nom lisible, pas un identifiant brut", () => {
    const view = cityView(cityState({ buildings: [cityBuilding(), house({ buildingId: 8, buildingType: "tour-radio", bonusType: null, bonus: null })] }));
    const lignes = (batimentsScreen(view, 0, 8)?.spec.buttons ?? []).map((text) => bare(text).split("\n")[0]);
    assert.ok(lignes.includes("[V] Tour radio niveau 1"), JSON.stringify(lignes));
  });
});

describe("la fiche d'un bâtiment", () => {
  const view = cityView(grownCity());

  it("un bâtiment disparu : pas d'écran", () => {
    assert.equal(batimentScreen(view, 99, 0), undefined);
    assert.equal(batimentScreen(viewOf(), 1, 0), undefined);
  });

  it("nom, niveau sur maximum, bonus, parcelle, niveau suivant avec sa mairie requise, et Améliorer", () => {
    const screen = batimentScreen(view, 2, 0);
    assert.ok(screen);
    assert.equal(screen.spec.title, "VILLE");
    const body = bare(screen.spec.body);
    assert.match(body, /^Maison\nNiveau 2 sur 3\nLogements 2\nParcelle 128, 128\n\nNiveau suivant : 6 Lingots\nMairie niveau 3 requise$/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Améliorer\n6 Lingots", "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "upgrade", buildingId: 2 });
  });

  it("le prix et la mairie requise sont ceux du service, pas ceux du catalogue", () => {
    const changed = cityView(cityState({ buildings: [house({ nextLevel: { priceLingots: 4_321, mairieLevelRequired: 7 } })] }));
    const screen = batimentScreen(changed, 2, 0);
    assert.match(bare(screen?.spec.body ?? ""), /Niveau suivant : 4 321 Lingots\nMairie niveau 7 requise$/);
    assert.match(bare(screen?.spec.buttons[0] ?? ""), /^Améliorer\n4 321 Lingots$/);
  });

  it("la mairie et les routes n'ont pas de ligne de bonus", () => {
    assert.doesNotMatch(bare(batimentScreen(view, 1, 0)?.spec.body ?? ""), /Logements|Donar|Livraison/);
    assert.match(bare(batimentScreen(view, 1, 0)?.spec.body ?? ""), /^Mairie\nNiveau 3 sur 16\nParcelle 192, 192/);
    assert.doesNotMatch(bare(batimentScreen(view, 3, 0)?.spec.body ?? ""), /Logements|Donar|Livraison/);
  });

  it("au dernier niveau : « Niveau maximum. » et plus de bouton Améliorer", () => {
    const top = cityView(cityState({ buildings: [house({ level: 3, maxLevel: 3, bonus: 3, nextLevel: null })] }));
    const screen = batimentScreen(top, 2, 0);
    assert.ok(screen);
    assert.match(bare(screen.spec.body), /Niveau maximum\.$/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Retour"]);
    const { nextLevel: _absent, ...sansNextLevel } = cityBuilding({ buildingId: 5, level: 16 });
    const sansSuivant = cityView(cityState({ buildings: [sansNextLevel] }));
    assert.deepEqual(batimentScreen(sansSuivant, 5, 0)?.spec.buttons.map(bare), ["Retour"]);
  });

  it("Retour mène à la liste, à la page d'où l'on vient", () => {
    const screen = batimentScreen(view, 2, 3);
    assert.deepEqual(route(screen ?? { targets: [] }, 1), { screen: "batiments", page: 3 });
  });

  it("une bulle d'Oooscar passe avant le nom", () => {
    const screen = batimentScreen(view, 2, 0, { bubble: VILLE_TEXTES.bulles.batimentAmeliore });
    assert.match(bare(screen?.spec.body ?? ""), /^Oooscar \[joie\] Bâtiment amélioré !\n\nMaison/);
  });
});

// ---------------------------------------------------------------- construire et poser

describe("l'écran Construire", () => {
  it("un bouton par bâtiment que le joueur pose, avec le prix et le bonus du niveau 1 de definitions/ville.json", () => {
    const screen = construireScreen();
    assert.equal(screen.spec.title, "CONSTRUIRE");
    assert.deepEqual(screen.spec.buttons.map(bare), [
      "Route\n1 Lingots",
      "Maison\n3 Lingots · Logements 1",
      "Immeuble\n10 Lingots · Logements 4",
      "Commerce\n8 Lingots · Donar +0,5 %",
      "Garage\n12 Lingots · Livraison +5 %",
      "Retour",
    ]);
    assert.deepEqual(route(screen, 1), { screen: "poser", buildingType: "maison", rotation: 0 });
    assert.deepEqual(route(screen, 5), { screen: "ville" });
    assert.match(bare(screen.spec.body), /^Choisis un bâtiment à poser\.$/);
  });

  it("la mairie, que le joueur ne pose pas, n'y figure pas", () => {
    assert.ok(!construireScreen().targets.some((target) => target.kind === "go" && target.route.screen === "poser" && target.route.buildingType === "mairie"));
  });

  it("les prix et les bonus suivent definitions/ville.json, rien n'est écrit en dur", () => {
    const changed: VilleDefinitions = {
      ...VILLE,
      batiments: VILLE.batiments.map((building) =>
        building.id === "maison" ? { ...building, niveaux: [{ ...building.niveaux[0]!, prixLingots: 9_999, bonus: 7 }, ...building.niveaux.slice(1)] } : building,
      ),
    };
    const lignes = construireScreen(undefined, changed).spec.buttons.map(bare);
    assert.ok(lignes.includes("Maison\n9 999 Lingots · Logements 7"), JSON.stringify(lignes));
  });

  it("un bâtiment que le catalogue ne donne plus à poser disparaît de la liste", () => {
    const changed: VilleDefinitions = { ...VILLE, batiments: VILLE.batiments.map((building) => (building.id === "garage" ? { ...building, posable: false } : building)) };
    assert.ok(!construireScreen(undefined, changed).spec.buttons.map(bare).some((text) => text.startsWith("Garage")));
  });

  it("tient dans les huit tuiles d'un écran", () => {
    assert.ok(construireScreen().spec.buttons.length <= 8);
  });
});

describe("l'écran Poser", () => {
  const on = { kind: "on", x: 128, z: 144 } as const;

  it("un type que le joueur ne pose pas : pas d'écran", () => {
    assert.equal(poserScreen("mairie", 0, on), undefined);
    assert.equal(poserScreen("nexiste-pas", 0, on), undefined);
  });

  it("le nom, le prix du niveau 1, la parcelle où se tient le joueur, la porte, et trois boutons", () => {
    const screen = poserScreen("maison", 0, on);
    assert.ok(screen);
    assert.equal(screen.spec.title, "POSER");
    assert.match(bare(screen.spec.body), /^Maison\nPrix 3 Lingots\nParcelle 128, 144\nPorte au sud$/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Poser ici", "Tourner", "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "place", buildingType: "maison", rotation: 0 });
    assert.deepEqual(route(screen, 1), { screen: "poser", buildingType: "maison", rotation: 90 });
    assert.deepEqual(route(screen, 2), { screen: "construire" });
  });

  it("hors de l'île principale ou sans position : la phrase qui le dit", () => {
    assert.match(bare(poserScreen("maison", 0, { kind: "off" })?.spec.body ?? ""), /Place-toi sur l'île principale\./);
    assert.match(bare(poserScreen("maison", 0, undefined)?.spec.body ?? ""), /Je ne sais pas où tu te trouves\./);
  });

  it("le prix suit definitions/ville.json", () => {
    const changed: VilleDefinitions = {
      ...VILLE,
      batiments: VILLE.batiments.map((building) => (building.id === "garage" ? { ...building, niveaux: [{ ...building.niveaux[0]!, prixLingots: 1_500 }, ...building.niveaux.slice(1)] } : building)),
    };
    assert.match(bare(poserScreen("garage", 0, on, undefined, changed)?.spec.body ?? ""), /Prix 1 500 Lingots/);
  });

  it("la porte suit la rotation ; une route dit son sens à la place de la porte", () => {
    const portes = ([0, 90, 180, 270] as const).map((rotation) => bare(poserScreen("maison", rotation, on)?.spec.body ?? "").split("\n").at(-1));
    assert.deepEqual(portes, ["Porte au sud", "Porte à l'ouest", "Porte au nord", "Porte à l'est"]);
    const sens = ([0, 90, 180, 270] as const).map((rotation) => bare(poserScreen("route", rotation, on)?.spec.body ?? "").split("\n").at(-1));
    assert.deepEqual(sens, ["Sens nord-sud", "Sens est-ouest", "Sens nord-sud", "Sens est-ouest"]);
  });

  it("Tourner fait un quart de tour puis revient à zéro", () => {
    assert.deepEqual([0, 90, 180, 270].map((rotation) => nextRotation(rotation as 0)), [90, 180, 270, 0]);
  });

  it("une bulle d'Oooscar passe avant le nom", () => {
    const screen = poserScreen("maison", 0, on, { bubble: VILLE_TEXTES.bulles.pasSurLIle });
    assert.match(bare(screen?.spec.body ?? ""), /^Oooscar \[oups\] Va sur l'île principale\.\n\nMaison/);
  });
});

// ---------------------------------------------------------------- ce que dit Oooscar

describe("villeNotice : les refus du service dans la ville", () => {
  const refused = (reason: string, missing?: number): ActionOutcome => (missing === undefined ? { kind: "refused", reason } : { kind: "refused", reason, missing });
  const B = VILLE_TEXTES.bulles;

  it("ZONE_VERROUILLEE : les usines pour la zone 1, la mairie pour les autres zones et les améliorations, la zone fermée pour une pose", () => {
    assert.deepEqual(villeNotice(refused("ZONE_VERROUILLEE"), "buyZone", 1), { bubble: B.premiereZone });
    assert.deepEqual(villeNotice(refused("ZONE_VERROUILLEE"), "buyZone", 2), { bubble: B.mairieBasse });
    assert.deepEqual(villeNotice(refused("ZONE_VERROUILLEE"), "upgradeBuilding"), { bubble: B.mairieBasse });
    assert.deepEqual(villeNotice(refused("ZONE_VERROUILLEE"), "placeBuilding"), { bubble: B.parcelleFermee });
  });

  it("EMPLACEMENT_OCCUPE ne parle que de la pose", () => {
    assert.deepEqual(villeNotice(refused("EMPLACEMENT_OCCUPE"), "placeBuilding"), { bubble: B.emplacementOccupe });
    assert.equal(villeNotice(refused("EMPLACEMENT_OCCUPE"), "buyZone", 2), undefined);
    assert.equal(villeNotice(refused("EMPLACEMENT_OCCUPE"), "upgradeBuilding"), undefined);
  });

  it("DEJA_POSSEDE : la zone déjà à soi, le maximum du catalogue, le dernier niveau", () => {
    assert.deepEqual(villeNotice(refused("DEJA_POSSEDE"), "buyZone", 3), { bubble: B.zoneDejaPossedee });
    assert.deepEqual(villeNotice(refused("DEJA_POSSEDE"), "placeBuilding"), { bubble: B.maximumAtteint });
    assert.deepEqual(villeNotice(refused("DEJA_POSSEDE"), "upgradeBuilding"), { bubble: B.niveauMax });
  });

  it("les autres refus et les autres issues sont laissés à noticeOf", () => {
    for (const action of ["buyZone", "placeBuilding", "upgradeBuilding"] as const) {
      assert.equal(villeNotice(refused("PAS_ASSEZ_DE_LINGOTS", 3), action, 2), undefined);
      assert.equal(villeNotice(refused("CONFLIT_REESSAYER"), action, 2), undefined);
      assert.equal(villeNotice({ kind: "zoneBought" }, action, 2), undefined);
      assert.equal(villeNotice({ kind: "busy" }, action, 2), undefined);
      assert.equal(villeNotice({ kind: "failed", failure: "network", reason: "RESEAU" }, action, 2), undefined);
    }
  });

  it("noticeOf dit les trois réussites de la ville et laisse les Lingots qui manquent à refusalBubble", () => {
    assert.deepEqual(noticeOf({ kind: "zoneBought" }), { bubble: B.zoneAchetee });
    assert.deepEqual(noticeOf({ kind: "buildingPlaced" }), { bubble: B.batimentPose });
    assert.deepEqual(noticeOf({ kind: "buildingUpgraded" }), { bubble: B.batimentAmeliore });
    assert.deepEqual(noticeOf(refused("PAS_ASSEZ_DE_LINGOTS", 3)), { bubble: refusalBubble("PAS_ASSEZ_DE_LINGOTS", 3) });
    assert.deepEqual(noticeOf(refused("CONFLIT_REESSAYER")), { bubble: refusalBubble("CONFLIT_REESSAYER") });
  });

  it("les bulles de la ville tiennent en six mots au plus", () => {
    for (const bubble of Object.values(B)) {
      assert.ok(bubble.text.split(/\s+/).length <= 6, bubble.text);
    }
  });
});

// ---------------------------------------------------------------- les noms

describe("les noms des bâtiments", () => {
  it("les noms recopiés dans l'add-on sont ceux du catalogue de definitions/ville.json, ni plus ni moins", () => {
    assert.deepEqual([...BATIMENT_IDS].sort(), VILLE.batiments.map((building) => building.id).sort());
  });

  it("chaque bâtiment du catalogue a un nom, et un type inconnu en reçoit un lisible", () => {
    for (const building of VILLE.batiments) {
      assert.equal(batimentName(building.id), BATIMENT_NOMS[building.id as keyof typeof BATIMENT_NOMS], building.id);
    }
    assert.equal(batimentName("tour-radio"), "Tour radio");
  });
});
