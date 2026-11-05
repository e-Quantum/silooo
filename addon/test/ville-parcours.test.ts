// Les parcours du menu dans la ville : ouvrir la ville, acheter une zone, améliorer, construire et poser, voyager vers
// l'île principale. Le menu est monté comme main.ts le monte, avec un faux service, un faux présentateur de formulaires,
// et une position de joueur que le test déplace. Rien ici n'importe @minecraft/*.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { Pos } from "../src/monde/geometrie";
import type { TravelOutcome } from "../src/monde/voyage";
import type { CompanyState, CompanyUpdated } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { createMenu, type MenuDeps } from "../src/ui/menu";
import { createMissions } from "../src/ui/missions";
import { BULLES, VILLE_TEXTES, refusalBubble, renderBubble } from "../src/ui/textes";
import { balances, companyState, ok, recordingLogger } from "./helpers";
import {
  PLAYER,
  XUID,
  bare,
  close,
  failure,
  fakeClock,
  fakeCompany,
  fakeMissionsApi,
  fakePresenter,
  press,
  refused,
  sequentialIds,
  storeWith,
  unreachable,
  type Reply,
  type Script,
} from "./ui-helpers";
import { cityBuilding, cityState, emptyCity, house } from "./ville-helpers";

type Travel = NonNullable<MenuDeps<typeof PLAYER>["travelCity"]>;

/** Le corner de l'île principale d'une entreprise dont l'archipel est à (0, 0) : 0 + 352 - 256, soit 96 (definitions/iles.json). */
const CORNER = 96;

/** Une position sur la parcelle (x, z) de l'île principale, vue du monde. */
const onParcel = (x: number, z: number): Pos => ({ x: CORNER + x + 5, y: 102, z: CORNER + z + 3 });

interface Extras {
  readonly travelCity?: Travel;
  readonly position?: { current: Pos | undefined } | "absente";
  readonly originOf?: MenuDeps<typeof PLAYER>["originOf"];
}

function setup(replies: readonly Reply[], script: Script = {}, state: Partial<CompanyState> = {}, extras: Extras = {}) {
  const clock = fakeClock(0);
  const store = storeWith({ version: 1, island: { x: 0, z: 0 }, city: cityState(), ...state }, { lingots: 20 });
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const missions = createMissions({ api: fakeMissionsApi(), players: store, say: () => undefined, log });
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log, afterCollect: missions.refresh });
  const presenter = fakePresenter(replies);
  const position = extras.position === undefined ? { current: onParcel(128, 144) as Pos | undefined } : extras.position;
  const menu = createMenu({
    views,
    missions,
    actions,
    presenter,
    clock,
    wait: async () => undefined,
    log,
    tilesPerScreen: 8,
    busyRetries: 3,
    busyRetrySeconds: 0.5,
    ...(extras.travelCity === undefined ? {} : { travelCity: extras.travelCity }),
    ...(position === "absente" ? {} : { where: () => position.current }),
    ...(extras.originOf === undefined ? {} : { originOf: extras.originOf }),
  });
  return { menu, presenter, api, views, log, store, position: position === "absente" ? { current: undefined } : position };
}

const titles = (presenter: { shown: Array<{ title: string }> }) => presenter.shown.map((spec) => spec.title);
const bodyAt = (presenter: { shown: Array<{ body: string }> }, index: number) => bare(presenter.shown[index]?.body ?? "");
const said = (bubble: Parameters<typeof renderBubble>[0]) => bare(renderBubble(bubble));

/** La réponse du service à un achat, une pose ou une amélioration : l'état d'après, et les soldes d'après. */
const updated = (city: ReturnType<typeof cityState>, version = 2): CompanyUpdated => ({
  state: companyState({ version, island: { x: 0, z: 0 }, city }),
  balances: balances({ lingots: 11 }),
});

// ---------------------------------------------------------------- ouvrir la ville

describe("parcours : ouvrir la ville", () => {
  it("la ville est relue à l'ouverture : l'écran montre l'état du service, pas celui du dernier join", async () => {
    const fresh = companyState({ version: 4, island: { x: 0, z: 0 }, city: cityState({ mairieLevel: 5 }) });
    const { menu, presenter, api, views } = setup([press("Ville"), close], { getCompany: [ok(fresh)] });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE"]);
    assert.match(bodyAt(presenter, 1), /^Mairie niveau 5/);
    assert.deepEqual(api.calls, [{ op: "getCompany", companyId: 7 }]);
    assert.equal(views.get(XUID)?.state.version, 4);
  });

  it("la relecture échoue : l'écran montre la dernière ville connue, avec la bulle d'Oooscar", async () => {
    const { menu, presenter } = setup([press("Ville"), close], { getCompany: [unreachable()] });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE"]);
    assert.ok(bodyAt(presenter, 1).startsWith(said(BULLES.serviceInjoignable)));
    assert.match(bodyAt(presenter, 1), /Mairie niveau 1/);
  });

  it("avant la première zone : la tuile s'ouvre, l'écran propose la zone 1", async () => {
    const { menu, presenter, api } = setup(
      [press("Ville"), press("Acheter la zone 1"), close],
      { getCompany: [ok(companyState({ version: 1, island: { x: 0, z: 0 }, city: emptyCity() }))], buyZone: [ok(updated(cityState()))] },
      { city: emptyCity() },
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "VILLE"]);
    assert.match(bodyAt(presenter, 1), /^Tu n'as pas encore de zone\./);
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany", "buyZone"]);
    assert.ok(bodyAt(presenter, 2).startsWith(said(VILLE_TEXTES.bulles.zoneAchetee)));
    assert.match(bodyAt(presenter, 2), /Mairie niveau 1/);
  });
});

// ---------------------------------------------------------------- acheter une zone

describe("parcours : acheter une zone", () => {
  const fresh = () => ok(companyState({ version: 1, island: { x: 0, z: 0 }, city: cityState() }));

  it("succès : la zone 2 est demandée, l'écran revient avec la bulle de joie et la ville du service", async () => {
    const after = cityState({ mairieLevel: 1, nextZone: { zoneIndex: 3, priceLingots: 9, mairieLevelRequired: 3, activeFactoriesRequired: 0 } });
    const { menu, presenter, api, views } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [ok(updated(after))] });
    await menu.open(PLAYER);
    assert.deepEqual(api.calls, [
      { op: "getCompany", companyId: 7 },
      { op: "buyZone", companyId: 7, zoneIndex: 2, action: { requestId: "id-1", actor: XUID } },
    ]);
    assert.ok(bodyAt(presenter, 2).startsWith(said(VILLE_TEXTES.bulles.zoneAchetee)));
    assert.match(bodyAt(presenter, 2), /Zone 3\n9 Lingots\nMairie niveau 3 requise$/);
    assert.equal(views.get(XUID)?.balances.lingots, 11);
  });

  it("la première zone, ZONE_VERROUILLEE : Oooscar demande plus d'usines actives", async () => {
    const empty = ok(companyState({ version: 1, island: { x: 0, z: 0 }, city: emptyCity() }));
    const { menu, presenter } = setup([press("Ville"), press("Acheter la zone 1"), close], { getCompany: [empty], buyZone: [refused("ZONE_VERROUILLEE")] }, { city: emptyCity() });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 2).startsWith(said(VILLE_TEXTES.bulles.premiereZone)));
  });

  it("une autre zone, ZONE_VERROUILLEE : Oooscar demande de monter la mairie", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [refused("ZONE_VERROUILLEE")] });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 2).startsWith(said(VILLE_TEXTES.bulles.mairieBasse)));
  });

  it("DEJA_POSSEDE : la zone est déjà à soi", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [refused("DEJA_POSSEDE")] });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 2).startsWith(said(VILLE_TEXTES.bulles.zoneDejaPossedee)));
  });

  it("PAS_ASSEZ_DE_LINGOTS : la bulle commune des refus, avec ce qui manque ; la ville ne bouge pas", async () => {
    const { menu, presenter, views } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [refused("PAS_ASSEZ_DE_LINGOTS", 3)] });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 2).startsWith(said(refusalBubble("PAS_ASSEZ_DE_LINGOTS", 3))));
    assert.equal(views.get(XUID)?.state.city?.nextZone?.zoneIndex, 2);
  });

  it("le service ne répond pas : la bulle de service injoignable, l'écran reste", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [unreachable()] });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "VILLE"]);
    assert.ok(bodyAt(presenter, 2).startsWith(said(BULLES.serviceInjoignable)));
  });

  it("une erreur du service : la bulle du problème, l'écran reste", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Acheter la zone 2"), close], { getCompany: [fresh()], buyZone: [failure(500, "ERREUR_INTERNE")] });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 2).startsWith(said(BULLES.problemeService)));
  });
});

// ---------------------------------------------------------------- les bâtiments

describe("parcours : les bâtiments et leur amélioration", () => {
  const withHouse = cityState({ buildings: [cityBuilding(), house()] });
  const refreshed = () => ok(companyState({ version: 1, island: { x: 0, z: 0 }, city: withHouse }));

  it("la liste, la fiche d'une maison, puis Améliorer : le niveau suivant est demandé et la fiche revient avec la bulle", async () => {
    const upgraded = cityState({ buildings: [cityBuilding(), house({ level: 2, bonus: 2, nextLevel: { priceLingots: 12, mairieLevelRequired: 5 } })] });
    const { menu, presenter, api } = setup(
      [press("Ville"), press("Bâtiments"), press("Maison niveau 1"), press("Améliorer"), close],
      { getCompany: [refreshed()], upgradeBuilding: [ok(updated(upgraded))] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "VILLE", "VILLE", "VILLE"]);
    assert.deepEqual(presenter.shown[2]?.buttons.map((text) => bare(text).split("\n")[0]), ["[V] Mairie niveau 1", "[V] Maison niveau 1", "Retour"]);
    assert.match(bodyAt(presenter, 3), /^Maison\nNiveau 1 sur 3\nLogements 1\nParcelle 128, 128\n\nNiveau suivant : 6 Lingots\nMairie niveau 3 requise$/);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.batimentAmeliore)));
    assert.match(bodyAt(presenter, 4), /Niveau 2 sur 3\nLogements 2/);
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany", "upgradeBuilding"]);
    assert.deepEqual(api.calls[1], { op: "upgradeBuilding", companyId: 7, buildingId: 2, action: { requestId: "id-1", actor: XUID } });
  });

  it("améliorer la mairie passe par le même appel", async () => {
    const { menu, api } = setup(
      [press("Ville"), press("Bâtiments"), press("Mairie niveau 1"), press("Améliorer"), close],
      { getCompany: [refreshed()], upgradeBuilding: [ok(updated(cityState({ mairieLevel: 2, buildings: [cityBuilding({ level: 2 }), house()] })))] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls[1], { op: "upgradeBuilding", companyId: 7, buildingId: 1, action: { requestId: "id-1", actor: XUID } });
  });

  it("ZONE_VERROUILLEE : Oooscar demande de monter la mairie ; la fiche reste", async () => {
    const { menu, presenter } = setup(
      [press("Ville"), press("Bâtiments"), press("Maison niveau 1"), press("Améliorer"), close],
      { getCompany: [refreshed()], upgradeBuilding: [refused("ZONE_VERROUILLEE")] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.mairieBasse)));
    assert.match(bodyAt(presenter, 4), /Niveau 1 sur 3/);
  });

  it("DEJA_POSSEDE : le bâtiment est déjà au niveau maximum", async () => {
    const { menu, presenter } = setup(
      [press("Ville"), press("Bâtiments"), press("Maison niveau 1"), press("Améliorer"), close],
      { getCompany: [refreshed()], upgradeBuilding: [refused("DEJA_POSSEDE")] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.niveauMax)));
  });

  it("un bâtiment qui disparaît de l'état pendant la fiche : retour à la liste, la bulle reste", async () => {
    const without = cityState({ buildings: [cityBuilding()] });
    const { menu, presenter } = setup(
      [press("Ville"), press("Bâtiments"), press("Maison niveau 1"), press("Améliorer"), close],
      { getCompany: [refreshed()], upgradeBuilding: [ok(updated(without))] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "VILLE", "VILLE", "VILLE"]);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.batimentAmeliore)));
    assert.deepEqual(presenter.shown[4]?.buttons.map((text) => bare(text).split("\n")[0]), ["[V] Mairie niveau 1", "Retour"]);
  });

  it("Retour depuis une fiche revient à la liste, Retour de la liste revient à la ville, Retour de la ville au menu", async () => {
    const { menu, presenter } = setup(
      [press("Ville"), press("Bâtiments"), press("Maison niveau 1"), press("Retour"), press("Retour"), press("Retour"), close],
      // La ville est relue chaque fois que le joueur y revient : une réponse pour chaque entrée.
      { getCompany: [refreshed(), refreshed()] },
      { city: withHouse },
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "VILLE", "VILLE", "VILLE", "VILLE", "MENU"]);
    assert.ok(presenter.shown[4]?.buttons.map(bare).some((text) => text.startsWith("[V] Maison niveau 1")));
    assert.match(bodyAt(presenter, 5), /^Mairie niveau 1\n\nBonus/);
  });
});

// ---------------------------------------------------------------- construire et poser

describe("parcours : construire et poser", () => {
  const fresh = () => ok(companyState({ version: 1, island: { x: 0, z: 0 }, city: cityState() }));
  const placed = () => updated(cityState({ buildings: [cityBuilding(), house({ buildingId: 5, x: 128, z: 144 })] }), 3);

  it("la maison sur la parcelle où se tient le joueur : type, parcelle et rotation partent, la ville revient avec la bulle", async () => {
    const { menu, presenter, api, views } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], {
      getCompany: [fresh()],
      placeBuilding: [ok(placed())],
    });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "CONSTRUIRE", "POSER", "VILLE"]);
    assert.match(bodyAt(presenter, 3), /^Maison\nPrix 3 Lingots\nParcelle 128, 144\nPorte au sud$/);
    assert.deepEqual(api.calls[1], {
      op: "placeBuilding",
      companyId: 7,
      action: { requestId: "id-1", actor: XUID },
      buildingType: "maison",
      x: 128,
      z: 144,
      rotation: 0,
    });
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.batimentPose)));
    assert.equal(presenter.shown[4]?.buttons.map(bare).some((text) => text.startsWith("Bâtiments\n2 bâtiments")), true);
    assert.equal(views.get(XUID)?.state.city?.buildings.length, 2);
  });

  it("Tourner change la porte à l'écran, et la rotation part avec la pose", async () => {
    const { menu, presenter, api } = setup([press("Ville"), press("Construire"), press("Maison"), press("Tourner"), press("Tourner"), press("Poser ici"), close], {
      getCompany: [fresh()],
      placeBuilding: [ok(placed())],
    });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "CONSTRUIRE", "POSER", "POSER", "POSER", "VILLE"]);
    assert.match(bodyAt(presenter, 4), /Porte à l'ouest$/);
    assert.match(bodyAt(presenter, 5), /Porte au nord$/);
    const call = api.calls[1];
    assert.equal(call?.op === "placeBuilding" ? call.rotation : undefined, 180);
  });

  it("une route dit son sens à la place de la porte", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Construire"), press("Route"), press("Tourner"), close], { getCompany: [fresh()] });
    await menu.open(PLAYER);
    assert.match(bodyAt(presenter, 3), /Sens nord-sud$/);
    assert.match(bodyAt(presenter, 4), /Sens est-ouest$/);
  });

  it("la parcelle est relue au clic : le joueur qui a bougé pose sur la parcelle où il se tient maintenant", async () => {
    const mover: Reply = (spec) => {
      position.current = onParcel(160, 176);
      return press("Poser ici")(spec);
    };
    const { menu, presenter, api, position } = setup([press("Ville"), press("Construire"), press("Maison"), mover, close], {
      getCompany: [fresh()],
      placeBuilding: [ok(placed())],
    });
    await menu.open(PLAYER);
    assert.match(bodyAt(presenter, 3), /Parcelle 128, 144/);
    const call = api.calls[1];
    assert.equal(call?.op === "placeBuilding" ? `${call.x},${call.z}` : undefined, "160,176");
  });

  it("le joueur est ailleurs que sur l'île principale : « Va sur l'île principale », rien n'est envoyé, l'écran reste", async () => {
    const { menu, presenter, api, position } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], { getCompany: [fresh()] });
    position.current = { x: 5, y: 101, z: 5 };
    await menu.open(PLAYER);
    assert.match(bodyAt(presenter, 3), /Place-toi sur l'île principale\./);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "CONSTRUIRE", "POSER", "POSER"]);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.pasSurLIle)));
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany"]);
  });

  it("la position du joueur est inconnue : « Je ne te trouve pas », rien n'est envoyé", async () => {
    const { menu, presenter, api, position } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], { getCompany: [fresh()] });
    position.current = undefined;
    await menu.open(PLAYER);
    assert.match(bodyAt(presenter, 3), /Je ne sais pas où tu te trouves\./);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.positionInconnue)));
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany"]);
  });

  it("le monde n'est pas branché (pas de position) : même réponse, rien n'est envoyé", async () => {
    const { menu, presenter, api } = setup(
      [press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close],
      { getCompany: [fresh()] },
      {},
      { position: "absente" },
    );
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.positionInconnue)));
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany"]);
  });

  it("la position est connue mais l'origine de l'archipel ne l'est pas : même réponse, rien n'est envoyé", async () => {
    const sansIle = ok(companyState({ version: 1, city: cityState() }));
    const { menu, presenter, api } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], { getCompany: [sansIle] });
    await menu.open(PLAYER);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.positionInconnue)));
    assert.deepEqual(api.calls.map((call) => call.op), ["getCompany"]);
  });

  it("l'origine de l'archipel manque à l'état : celle que le monde a mémorisée sert", async () => {
    const without = ok(companyState({ version: 1, city: cityState() }));
    const { menu, presenter, api } = setup(
      [press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close],
      { getCompany: [without], placeBuilding: [ok(placed())] },
      {},
      { originOf: () => ({ x: 1_000, z: 2_000 }), position: { current: { x: 1_000 + CORNER + 128 + 2, y: 102, z: 2_000 + CORNER + 144 + 2 } } },
    );
    await menu.open(PLAYER);
    assert.match(bodyAt(presenter, 3), /Parcelle 128, 144/);
    const call = api.calls[1];
    assert.equal(call?.op === "placeBuilding" ? `${call.x},${call.z}` : undefined, "128,144");
  });

  it("EMPLACEMENT_OCCUPE : « Un bâtiment est déjà là », l'écran de pose reste", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], {
      getCompany: [fresh()],
      placeBuilding: [refused("EMPLACEMENT_OCCUPE")],
    });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "CONSTRUIRE", "POSER", "POSER"]);
    assert.ok(bodyAt(presenter, 4).startsWith(said(VILLE_TEXTES.bulles.emplacementOccupe)));
  });

  it("ZONE_VERROUILLEE, DEJA_POSSEDE et le manque de Lingots ont chacun leur bulle", async () => {
    const cases: Array<[ApiResult<CompanyUpdated>, string]> = [
      [refused("ZONE_VERROUILLEE"), said(VILLE_TEXTES.bulles.parcelleFermee)],
      [refused("DEJA_POSSEDE"), said(VILLE_TEXTES.bulles.maximumAtteint)],
      [refused("PAS_ASSEZ_DE_LINGOTS", 2), said(refusalBubble("PAS_ASSEZ_DE_LINGOTS", 2))],
    ];
    for (const [answer, text] of cases) {
      const { menu, presenter } = setup([press("Ville"), press("Construire"), press("Maison"), press("Poser ici"), close], { getCompany: [fresh()], placeBuilding: [answer] });
      await menu.open(PLAYER);
      assert.ok(bodyAt(presenter, 4).startsWith(text), text);
    }
  });

  it("Retour de la pose revient au choix du bâtiment, Retour du choix revient à la ville", async () => {
    const { menu, presenter } = setup([press("Ville"), press("Construire"), press("Maison"), press("Retour"), press("Retour"), close], { getCompany: [fresh(), fresh()] });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "VILLE", "CONSTRUIRE", "POSER", "CONSTRUIRE", "VILLE"]);
  });
});

// ---------------------------------------------------------------- l'île principale

describe("parcours : aller à l'île principale depuis la carte", () => {
  function travelling(outcome: TravelOutcome | Error) {
    const calls: CompanyState[] = [];
    const travelCity: Travel = async (_player, state) => {
      calls.push(state);
      if (outcome instanceof Error) throw outcome;
      return outcome;
    };
    return { calls, travelCity };
  }

  it("arrivé : le menu se ferme, le voyage a reçu l'état que le service a rendu en dernier", async () => {
    const { calls, travelCity } = travelling("arrived");
    const { menu, presenter, api, views } = setup([press("Carte"), press("Île principale")], {}, {}, { travelCity });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "CARTE"]);
    assert.equal(calls.length, 1);
    assert.equal(calls[0], views.get(XUID)?.state);
    assert.deepEqual(api.calls, []);
  });

  it("l'entreprise n'a pas encore de zone : la tuile est fermée, aucun voyage", async () => {
    const { calls, travelCity } = travelling("arrived");
    const { menu, presenter } = setup([press("Carte"), press("Île principale"), close], {}, { city: emptyCity() }, { travelCity });
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "CARTE", "CARTE"]);
    assert.ok(bodyAt(presenter, 2).startsWith(said(BULLES.bientot)));
    assert.deepEqual(calls, []);
  });

  it("île pas prête ou introuvable : la carte revient avec la bulle, le joueur peut réessayer", async () => {
    const waiting = travelling("waiting");
    const first = setup([press("Carte"), press("Île principale"), press("Île principale"), close], {}, {}, { travelCity: waiting.travelCity });
    await first.menu.open(PLAYER);
    assert.deepEqual(titles(first.presenter), ["MENU", "CARTE", "CARTE", "CARTE"]);
    assert.ok(bodyAt(first.presenter, 2).startsWith(said(BULLES.ilePasPrete)));
    assert.equal(waiting.calls.length, 2);

    const missing = travelling("unavailable");
    const second = setup([press("Carte"), press("Île principale"), close], {}, {}, { travelCity: missing.travelCity });
    await second.menu.open(PLAYER);
    assert.ok(bodyAt(second.presenter, 2).startsWith(said(BULLES.ileIntrouvable)));
  });

  it("joueur parti pendant l'attente : le menu se ferme sans bulle", async () => {
    const { travelCity } = travelling("gone");
    const { menu, presenter } = setup([press("Carte"), press("Île principale")], {}, {}, { travelCity });
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 2);
  });

  it("le monde n'est pas branché : « Pas encore disponible », la carte reste", async () => {
    const { menu, presenter } = setup([press("Carte"), press("Île principale"), close], {});
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter), ["MENU", "CARTE", "CARTE"]);
    assert.ok(bodyAt(presenter, 2).startsWith(said(BULLES.bientot)));
  });

  it("le voyage lève : le menu le journalise et se ferme, sans propager l'erreur", async () => {
    const { travelCity } = travelling(new Error("monde coupé"));
    const { menu, log } = setup([press("Carte"), press("Île principale")], {}, {}, { travelCity });
    await menu.open(PLAYER);
    assert.match(log.warns.join("\n"), /monde coupé/);
  });
});
