// La ville au niveau du contrat : la lecture de CompanyState.city, les chemins et le corps des trois routes,
// l'accord avec api/silooo-core.openapi.yaml et les trois appels de l'API. Rien ici n'importe @minecraft/*.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import { createCoreApi } from "../src/api";
import { createCoreClient } from "../src/http";
import {
  BUILDING_ROTATIONS,
  BUILDING_TYPE_MAX,
  buyZonePath,
  CITY_BONUS_TYPES,
  CITY_PATHS,
  parseCompanyState,
  placeBuildingBody,
  placeBuildingPath,
  upgradeBuildingPath,
  ZONE_MAX,
  ZONE_MIN,
} from "../src/protocol";
import { balances, companyState, json, scriptedTransport } from "./helpers";
import { cityBuilding, cityState, house, noBonuses } from "./ville-helpers";

const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "42" };

/** Un CompanyState tel que le service l'écrit : le JSON, pas l'objet du test. */
function wire(city: unknown): Record<string, unknown> {
  return { ...JSON.parse(JSON.stringify(companyState())), city };
}

describe("CompanyState.city : la lecture", () => {
  it("un état sans ville reste valide, et city est absente", () => {
    const parsed = parseCompanyState(JSON.parse(JSON.stringify(companyState())));
    assert.equal(parsed.ok, true);
    if (parsed.ok) assert.equal("city" in parsed.value, false);
  });

  it("une ville nulle est lue comme pas de ville", () => {
    const parsed = parseCompanyState(wire(null));
    assert.equal(parsed.ok, true);
    if (parsed.ok) assert.equal(parsed.value.city, undefined);
  });

  it("lit le niveau de la mairie, la prochaine zone, les bâtiments et les bonus", () => {
    const city = cityState({
      mairieLevel: 3,
      buildings: [cityBuilding({ level: 3 }), house({ buildingId: 9, level: 2, bonus: 4 })],
      bonuses: noBonuses({ housingPlaces: 4, housedStaff: 1, donarProductionPercent: 2.5, deliverySpeedPercent: 5 }),
    });
    const parsed = parseCompanyState(wire(JSON.parse(JSON.stringify(city))));
    assert.equal(parsed.ok, true);
    if (!parsed.ok) return;
    assert.deepEqual(parsed.value.city, city);
  });

  it("une prochaine zone nulle ou absente est lue comme nulle : toutes les zones sont à l'entreprise", () => {
    const base = JSON.parse(JSON.stringify(cityState()));
    for (const nextZone of [null, undefined]) {
      const city = { ...base, nextZone };
      if (nextZone === undefined) delete city.nextZone;
      const parsed = parseCompanyState(wire(city));
      assert.equal(parsed.ok, true);
      if (parsed.ok) assert.equal(parsed.value.city?.nextZone, null);
    }
  });

  it("les champs que l'add-on ne connaît pas sont ignorés, dans la ville comme dans ses bâtiments", () => {
    const city = JSON.parse(JSON.stringify(cityState({ buildings: [cityBuilding(), house()] })));
    city.surprise = 1;
    city.nextZone.surprise = 2;
    city.buildings[0].surprise = 3;
    city.buildings[1].nextLevel.surprise = 4;
    city.bonuses.surprise = 5;
    const parsed = parseCompanyState(wire(city));
    assert.equal(parsed.ok, true);
    if (!parsed.ok) return;
    assert.deepEqual(parsed.value.city, cityState({ buildings: [cityBuilding(), house()] }));
  });

  it("bonusType, bonus et nextLevel peuvent manquer ou être nuls", () => {
    const sans = JSON.parse(JSON.stringify(cityBuilding()));
    delete sans.bonusType;
    delete sans.bonus;
    delete sans.nextLevel;
    const nuls = { ...JSON.parse(JSON.stringify(cityBuilding())), bonusType: null, bonus: null, nextLevel: null };
    for (const building of [sans, nuls]) {
      const parsed = parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), buildings: [building] }));
      assert.equal(parsed.ok, true);
      if (!parsed.ok) continue;
      const lu = parsed.value.city?.buildings[0];
      assert.equal(lu?.bonusType ?? null, null);
      assert.equal(lu?.bonus ?? null, null);
      assert.equal(lu?.nextLevel ?? null, null);
    }
  });

  it("une ville sans bâtiment (liste vide) est valide", () => {
    const parsed = parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), buildings: [] }));
    assert.equal(parsed.ok, true);
    if (parsed.ok) assert.deepEqual(parsed.value.city?.buildings, []);
  });

  it("les formes hors contrat font échouer la lecture de l'état entier, avec le chemin de la faute", () => {
    const base = JSON.parse(JSON.stringify(cityState({ buildings: [cityBuilding()] })));
    const cas: Array<[string, (city: Record<string, any>) => void, RegExp]> = [
      ["ville qui n'est pas un objet", () => undefined, /city/],
      ["mairieLevel absent", (city) => delete city.mairieLevel, /mairieLevel/],
      ["mairieLevel à 0", (city) => (city.mairieLevel = 0), /mairieLevel/],
      ["mairieLevel décimal", (city) => (city.mairieLevel = 1.5), /mairieLevel/],
      ["buildings absent", (city) => delete city.buildings, /buildings/],
      ["bonuses absent", (city) => delete city.bonuses, /bonuses/],
      ["zone au-delà de 16", (city) => (city.nextZone.zoneIndex = 17), /zoneIndex/],
      ["zone à 0", (city) => (city.nextZone.zoneIndex = 0), /zoneIndex/],
      ["prix négatif", (city) => (city.nextZone.priceLingots = -1), /priceLingots/],
      ["rotation à 45", (city) => (city.buildings[0].rotation = 45), /rotation/],
      ["rotation en texte", (city) => (city.buildings[0].rotation = "90"), /rotation/],
      ["type vide", (city) => (city.buildings[0].buildingType = ""), /buildingType/],
      ["niveau à 0", (city) => (city.buildings[0].level = 0), /level/],
      ["bonusType inconnu", (city) => (city.buildings[0].bonusType = "OR"), /bonusType/],
      ["parcelle négative", (city) => (city.buildings[0].x = -16), /\.x/],
      ["places de logement négatives", (city) => (city.bonuses.housingPlaces = -1), /housingPlaces/],
      ["bonus de production en texte", (city) => (city.bonuses.donarProductionPercent = "2"), /donarProductionPercent/],
    ];
    for (const [nom, change, motif] of cas) {
      const city = JSON.parse(JSON.stringify(base));
      const corps = nom === "ville qui n'est pas un objet" ? wire(7) : (change(city), wire(city));
      const parsed = parseCompanyState(corps);
      assert.equal(parsed.ok, false, nom);
      if (!parsed.ok) assert.match(parsed.problem, motif, nom);
    }
  });

  it("les rotations et les types de bonus lus sont ceux de l'add-on", () => {
    assert.deepEqual([...BUILDING_ROTATIONS], [0, 90, 180, 270]);
    assert.deepEqual([...CITY_BONUS_TYPES], ["LOGEMENTS", "PRODUCTION_DONAR", "LIVRAISON_VITESSE"]);
    for (const rotation of BUILDING_ROTATIONS) {
      const parsed = parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), buildings: [{ ...cityBuilding(), rotation }] }));
      assert.equal(parsed.ok, true, String(rotation));
    }
  });
});

describe("la ville : chemins et corps des trois routes", () => {
  it("les chemins remplacent companyId, zoneIndex et buildingId", () => {
    assert.equal(buyZonePath(7, 1), "/v1/companies/7/city/zones/1");
    assert.equal(buyZonePath(7, 16), "/v1/companies/7/city/zones/16");
    assert.equal(placeBuildingPath(7), "/v1/companies/7/city/buildings");
    assert.equal(upgradeBuildingPath(7, 31), "/v1/companies/7/city/buildings/31");
    assert.equal(upgradeBuildingPath(7, 0), "/v1/companies/7/city/buildings/0");
  });

  it("une zone hors de 1 à 16 lève une erreur : rien ne part au service", () => {
    for (const zone of [0, 17, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(() => buyZonePath(7, zone), RangeError, String(zone));
    }
    assert.equal(ZONE_MIN, 1);
    assert.equal(ZONE_MAX, 16);
  });

  it("un identifiant de bâtiment ou d'entreprise illisible lève une erreur", () => {
    for (const id of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 60]) {
      assert.throws(() => upgradeBuildingPath(7, id), RangeError, String(id));
    }
    assert.throws(() => upgradeBuildingPath(1.5, 3), RangeError);
    assert.throws(() => placeBuildingPath(-1), RangeError);
    assert.throws(() => buyZonePath(Number.NaN, 2), RangeError);
  });

  it("le corps de la pose porte l'action, le type, la parcelle et la rotation, et rien d'autre", () => {
    assert.deepEqual(placeBuildingBody({ ...action, extra: 1 } as typeof action, "maison", 128, 144, 90), {
      ...action,
      buildingType: "maison",
      x: 128,
      z: 144,
      rotation: 90,
    });
  });

  it("un type vide ou trop long, une parcelle négative ou décimale, une rotation hors des quatre lèvent une erreur", () => {
    assert.throws(() => placeBuildingBody(action, "", 0, 0, 0), RangeError);
    assert.throws(() => placeBuildingBody(action, "x".repeat(BUILDING_TYPE_MAX + 1), 0, 0, 0), RangeError);
    assert.doesNotThrow(() => placeBuildingBody(action, "x".repeat(BUILDING_TYPE_MAX), 0, 0, 0));
    for (const bad of [-16, 1.5, Number.NaN]) {
      assert.throws(() => placeBuildingBody(action, "maison", bad, 0, 0), RangeError, `x ${bad}`);
      assert.throws(() => placeBuildingBody(action, "maison", 0, bad, 0), RangeError, `z ${bad}`);
    }
    assert.throws(() => placeBuildingBody(action, "maison", 0, 0, 45 as 0), RangeError);
  });
});

describe("accord avec api/silooo-core.openapi.yaml, la ville", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function after(marqueur: string, depuis = 0): number {
    const debut = yaml.indexOf(marqueur, depuis);
    assert.notEqual(debut, -1, `« ${marqueur} » introuvable dans l'OpenAPI`);
    return debut;
  }

  function requisesDe(marqueur: string): string[] {
    const ouvre = yaml.indexOf("required: [", after(marqueur));
    return yaml
      .slice(ouvre + "required: [".length, yaml.indexOf("]", ouvre))
      .split(",")
      .map((mot) => mot.trim());
  }

  function blocDe(chemin: string): string {
    const debut = after(`\n  ${chemin}:`);
    const fin = yaml.indexOf("\n  /v1/", debut + 1);
    return yaml.slice(debut, fin === -1 ? undefined : fin);
  }

  /** Retire chaque clé obligatoire du modèle, l'une après l'autre : la lecture doit refuser. */
  function exigeToutes(marqueur: string, modele: Record<string, unknown>, lire: (corps: Record<string, unknown>) => { ok: boolean }): void {
    const requises = requisesDe(marqueur);
    assert.ok(requises.length > 0);
    assert.equal(lire(modele).ok, true, `le modèle de test de ${marqueur} doit être valide`);
    for (const cle of requises) {
      assert.ok(cle in modele, `le modèle de test n'a pas la clé obligatoire « ${cle} » de ${marqueur}`);
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(lire(sans).ok, false, `${marqueur} : la lecture accepte une réponse sans « ${cle} »`);
    }
  }

  it("les trois chemins existent, avec leurs opérations", () => {
    assert.ok(yaml.includes(`\n  ${CITY_PATHS.zone}:`));
    assert.ok(yaml.includes(`\n  ${CITY_PATHS.buildings}:`));
    assert.ok(yaml.includes(`\n  ${CITY_PATHS.building}:`));
    for (const [chemin, id] of [
      [CITY_PATHS.zone, "buyZone"],
      [CITY_PATHS.buildings, "placeBuilding"],
      [CITY_PATHS.building, "upgradeBuilding"],
    ] as const) {
      const bloc = blocDe(chemin);
      assert.match(bloc, /\n {4}post:\n/, chemin);
      assert.match(bloc, new RegExp(`operationId: ${id}\\n`), chemin);
    }
  });

  it("la zone va de 1 à 16 et le type de bâtiment tient en 32 caractères", () => {
    assert.match(blocDe(CITY_PATHS.zone), new RegExp(`name: zoneIndex, in: path, required: true, schema: \\{ type: integer, minimum: ${ZONE_MIN}, maximum: ${ZONE_MAX} \\}`));
    assert.match(blocDe(CITY_PATHS.buildings), new RegExp(`maxLength: ${BUILDING_TYPE_MAX}`));
    assert.match(blocDe(CITY_PATHS.buildings), /required: \[buildingType, x, z, rotation\]/);
    assert.match(blocDe(CITY_PATHS.buildings), /rotation: \{ type: integer, enum: \[0, 90, 180, 270\] \}/);
  });

  it("les rotations et les types de bonus lus sont ceux du contrat", () => {
    const debut = after("    CityBuilding:");
    const bonusType = yaml.slice(after("bonusType:", debut), yaml.indexOf("\n", after("bonusType:", debut)));
    const valeurs = bonusType.slice(bonusType.indexOf("enum: [") + "enum: [".length, bonusType.indexOf("]")).split(",").map((mot) => mot.trim());
    assert.deepEqual([...CITY_BONUS_TYPES], valeurs);
  });

  it("CompanyState déclare city, facultative", () => {
    const debut = after("    CompanyState:");
    const fin = yaml.indexOf("\n    Factory:", debut);
    const bloc = yaml.slice(debut, fin === -1 ? undefined : fin);
    assert.match(bloc, /\n {8}city:/);
    assert.equal(requisesDe("    CompanyState:").includes("city"), false);
  });

  it("CityState : toutes les clés obligatoires sont lues et exigées, nextZone ne l'est pas", () => {
    const requises = requisesDe("    CityState:");
    assert.deepEqual(requises, ["mairieLevel", "buildings", "bonuses"]);
    exigeToutes("    CityState:", JSON.parse(JSON.stringify(cityState())), (corps) => parseCompanyState(wire(corps)));
  });

  it("nextZone : les quatre clés obligatoires sont lues et exigées", () => {
    exigeToutes("          required: [zoneIndex, priceLingots", JSON.parse(JSON.stringify(cityState().nextZone)), (corps) =>
      parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), nextZone: corps })),
    );
  });

  it("CityBuilding : toutes les clés obligatoires sont lues et exigées", () => {
    exigeToutes("    CityBuilding:", JSON.parse(JSON.stringify(cityBuilding())), (corps) =>
      parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), buildings: [corps] })),
    );
  });

  it("nextLevel d'un bâtiment : prix et niveau de mairie sont exigés", () => {
    const debut = after("        nextLevel:", after("    CityBuilding:"));
    const bloc = yaml.slice(debut, debut + 400);
    assert.match(bloc, /required: \[priceLingots, mairieLevelRequired\]/);
    for (const cle of ["priceLingots", "mairieLevelRequired"]) {
      const next: Record<string, unknown> = { priceLingots: 2, mairieLevelRequired: 1 };
      delete next[cle];
      const parsed = parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), buildings: [{ ...JSON.parse(JSON.stringify(cityBuilding())), nextLevel: next }] }));
      assert.equal(parsed.ok, false, cle);
    }
  });

  it("CityBonuses : toutes les clés obligatoires sont lues et exigées", () => {
    exigeToutes("    CityBonuses:", { ...noBonuses() }, (corps) =>
      parseCompanyState(wire({ ...JSON.parse(JSON.stringify(cityState())), bonuses: corps })),
    );
  });

  it("les trois routes rendent un CompanyUpdated et un refus Refused, et l'annonce se lit dans payload.action", () => {
    for (const chemin of [CITY_PATHS.zone, CITY_PATHS.buildings, CITY_PATHS.building]) {
      const bloc = blocDe(chemin);
      assert.match(bloc, /'200': \{ \$ref: '#\/components\/responses\/CompanyUpdated' \}/, chemin);
      assert.match(bloc, /'409': \{ \$ref: '#\/components\/responses\/Refused' \}/, chemin);
    }
    assert.match(blocDe(CITY_PATHS.zone), /`payload\.action` vaut `buyZone`/);
    assert.match(blocDe(CITY_PATHS.buildings), /`payload\.action` vaut `placeBuilding`/);
    assert.match(blocDe(CITY_PATHS.building), /`payload\.action` vaut `upgradeBuilding`/);
  });
});

describe("l'API de la ville", () => {
  function setup(script: Parameters<typeof scriptedTransport>[0]) {
    const transport = scriptedTransport(script);
    const api = createCoreApi(
      createCoreClient({ transport, coreUrl: "http://core:8080/", apiKey: "cle-de-test", timeoutSeconds: 5, readRetries: 1 }),
    );
    return { transport, api };
  }

  function bodyOf(request: { body: string | undefined } | undefined): unknown {
    return JSON.parse(request?.body ?? "null");
  }

  const updated = () => json(200, { state: JSON.parse(JSON.stringify(companyState({ city: cityState({ mairieLevel: 2 }) }))), balances: balances({ lingots: 4 }) });

  it("buyZone poste l'action à l'adresse de la zone et lit la ville du nouvel état", async () => {
    const { transport, api } = setup([updated()]);
    const result = await api.buyZone(7, 2, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.body.state.city?.mairieLevel, 2);
      assert.equal(result.body.balances.lingots, 4);
    }
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/city/zones/2");
    assert.deepEqual(bodyOf(request), action);
  });

  it("placeBuilding poste le type, la parcelle et la rotation avec l'action", async () => {
    const { transport, api } = setup([updated()]);
    const result = await api.placeBuilding(7, action, "maison", 128, 144, 270);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/city/buildings");
    assert.deepEqual(bodyOf(request), { ...action, buildingType: "maison", x: 128, z: 144, rotation: 270 });
  });

  it("upgradeBuilding poste l'action à l'adresse du bâtiment, sans autre champ", async () => {
    const { transport, api } = setup([updated()]);
    const result = await api.upgradeBuilding(7, 31, action);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.url, "http://core:8080/v1/companies/7/city/buildings/31");
    assert.deepEqual(bodyOf(request), action);
  });

  it("les refus 409 gardent leur code, avec missing pour les Lingots qui manquent", async () => {
    for (const reason of ["ZONE_VERROUILLEE", "EMPLACEMENT_OCCUPE", "DEJA_POSSEDE", "CONFLIT_REESSAYER"]) {
      const { api } = setup([json(409, { reason })]);
      assert.deepEqual(await api.buyZone(7, 2, action), { kind: "refused", status: 409, reason });
    }
    const { api } = setup([json(409, { reason: "PAS_ASSEZ_DE_LINGOTS", missing: 3 })]);
    const result = await api.upgradeBuilding(7, 31, action);
    assert.equal(result.kind, "refused");
    if (result.kind === "refused") {
      assert.equal(result.reason, "PAS_ASSEZ_DE_LINGOTS");
      assert.equal(result.missing, 3);
    }
  });

  it("une erreur 400 du service est une erreur, jamais rejouée", async () => {
    const { transport, api } = setup([json(400, { reason: "REQUETE_INVALIDE" }), updated()]);
    const result = await api.placeBuilding(7, action, "maison", 130, 128, 0);
    assert.deepEqual(result, { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
    assert.equal(transport.requests.length, 1);
  });

  it("un argument que le contrat refuserait n'envoie aucune requête", async () => {
    const { transport, api } = setup([updated()]);
    for (const zone of [0, 17, 1.5, Number.NaN]) assert.equal((await api.buyZone(7, zone, action)).kind, "error");
    for (const id of [-1, 1.5, Number.NaN, 2 ** 60]) assert.equal((await api.upgradeBuilding(7, id, action)).kind, "error");
    assert.equal((await api.placeBuilding(7, action, "", 0, 0, 0)).kind, "error");
    assert.equal((await api.placeBuilding(7, action, "maison", -16, 0, 0)).kind, "error");
    assert.equal((await api.placeBuilding(7, action, "maison", 0, 0, 45 as 0)).kind, "error");
    assert.equal((await api.buyZone(Number.NaN, 2, action)).kind, "error");
    assert.equal(transport.requests.length, 0);
  });

  it("une réponse hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, { state: { companyId: 7 }, balances: balances() })]);
    const result = await api.buyZone(7, 2, action);
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REPONSE_INATTENDUE");
  });

  it("un réseau coupé devient un résultat network, sans rejeu (c'est une écriture)", async () => {
    const { transport, api } = setup([new Error("connexion refusée"), updated()]);
    const result = await api.buyZone(7, 2, action);
    assert.equal(result.kind, "network");
    assert.equal(transport.requests.length, 1);
  });
});
