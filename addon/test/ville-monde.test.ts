// L'île principale côté monde : le plan de blocs de la ville (plateformes de zone, préfabriqués, enseignes) rejoué sur un
// faux monde qui refuse d'écrire hors de la zone chargée, puis le constructeur qui le pose zone par zone et bâtiment par
// bâtiment. Aucun @minecraft/* : le monde, les marques et le temps sont des faux.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { applyOp, createIslandBuilder } from "../src/monde/constructeur";
import { ILES, VILLE } from "../src/monde/definitions";
import { surfaceY, type Pos, type Xz } from "../src/monde/geometrie";
import {
  buildingKey,
  buildingMark,
  buildingSegments,
  cityCorner,
  cityWorks,
  doorArrival,
  isKnownBuilding,
  ownedZoneCount,
  parcelAt,
  zoneCorner,
  zoneKey,
  zoneMark,
  zoneSegments,
  type Segment,
} from "../src/monde/ville";
import type { BuildingRotation, CityBuilding, CityState } from "../src/protocol";
import { BATIMENT_IDS, batimentName } from "../src/ui/textes";
import { factory, recordingLogger } from "./helpers";
import { fakeMarks, fakeWorld, virtualTime, type FakeMarks, type FakeWorld } from "./monde-helpers";
import { cityBuilding, cityState, emptyCity, house } from "./ville-helpers";

const ORIGINE: Xz = { x: 0, z: 0 };
const AUTRE_ORIGINE: Xz = { x: 600, z: 0 };
/** Le coin de l'île principale pour l'origine (0, 0) : 300 − 256. */
const COIN = 44;
const ROTATIONS: readonly BuildingRotation[] = [0, 90, 180, 270];

const M = ILES.materiaux;

/** Rejoue des segments sur le monde, chacun dans sa zone chargée : une écriture hors de sa boîte fait échouer le test. */
async function replay(world: FakeWorld, segments: readonly Segment[]): Promise<void> {
  for (const segment of segments) {
    await world.withLoadedArea(segment.box, async () => {
      for (const op of segment.ops) applyOp(world, op);
    });
  }
}

async function built(building: CityBuilding, origin: Xz = ORIGINE, world: FakeWorld = fakeWorld()): Promise<FakeWorld> {
  await replay(world, buildingSegments(ILES, VILLE, origin, building));
  return world;
}

/** Une ville de test avec une zone de plus à acheter. */
const withNext = (zoneIndex: number | null, buildings: CityBuilding[]): CityState =>
  cityState({
    buildings,
    nextZone: zoneIndex === null ? null : { zoneIndex, priceLingots: 5, mairieLevelRequired: 2, activeFactoriesRequired: 0 },
  });

// ---------------------------------------------------------------- où est quoi

describe("où est la ville", () => {
  it("le coin de l'île principale suit l'origine de l'archipel", () => {
    assert.deepEqual(cityCorner(ORIGINE, ILES), { x: COIN, z: COIN });
    assert.deepEqual(cityCorner(AUTRE_ORIGINE, ILES), { x: 600 + COIN, z: COIN });
  });

  it("combien de zones sont à l'entreprise : celles avant la prochaine, toutes quand il n'y en a plus à acheter", () => {
    assert.equal(ownedZoneCount(emptyCity()), 0);
    assert.equal(ownedZoneCount(withNext(2, [])), 1);
    assert.equal(ownedZoneCount(withNext(3, [])), 2);
    assert.equal(ownedZoneCount(withNext(16, [])), 15);
    assert.equal(ownedZoneCount(withNext(null, [])), 16);
  });

  it("le coin d'une zone vient de zones.ordre : colonne et rangée, une zone de 128 blocs", () => {
    assert.deepEqual(zoneCorner(ORIGINE, ILES, VILLE, 1), { x: COIN + 128, z: COIN + 128 });
    assert.deepEqual(zoneCorner(ORIGINE, ILES, VILLE, 2), { x: COIN + 256, z: COIN + 128 });
    assert.deepEqual(zoneCorner(ORIGINE, ILES, VILLE, 16), { x: COIN, z: COIN });
    assert.deepEqual(zoneCorner(AUTRE_ORIGINE, ILES, VILLE, 1), { x: 600 + COIN + 128, z: COIN + 128 });
  });

  it("une zone qu'aucune entrée de zones.ordre ne décrit n'a pas de coin", () => {
    assert.equal(zoneCorner(ORIGINE, ILES, VILLE, 0), undefined);
    assert.equal(zoneCorner(ORIGINE, ILES, VILLE, 17), undefined);
    assert.equal(zoneCorner(ORIGINE, ILES, VILLE, -3), undefined);
  });

  it("les seize zones couvrent l'île de 512 blocs sans se chevaucher", () => {
    const corners = new Set<string>();
    for (let zone = 1; zone <= 16; zone += 1) {
      const corner = zoneCorner(ORIGINE, ILES, VILLE, zone);
      assert.ok(corner !== undefined);
      assert.equal((corner.x - COIN) % 128, 0);
      assert.equal((corner.z - COIN) % 128, 0);
      assert.ok(corner.x >= COIN && corner.x <= COIN + 384 && corner.z >= COIN && corner.z <= COIN + 384);
      corners.add(`${corner.x},${corner.z}`);
    }
    assert.equal(corners.size, 16);
  });
});

describe("la parcelle sous les pieds", () => {
  const at = (x: number, z: number, y = 102): Pos => ({ x, y, z });

  it("arrondit vers le bas à la grille de 16 blocs, depuis le coin de l'île", () => {
    assert.deepEqual(parcelAt(at(COIN + 192, COIN + 192), ORIGINE, ILES, VILLE), { kind: "on", x: 192, z: 192 });
    assert.deepEqual(parcelAt(at(COIN + 192 + 15.9, COIN + 192 + 15.99), ORIGINE, ILES, VILLE), { kind: "on", x: 192, z: 192 });
    assert.deepEqual(parcelAt(at(COIN + 192 + 16, COIN + 192 - 0.1), ORIGINE, ILES, VILLE), { kind: "on", x: 208, z: 176 });
  });

  it("les deux bords de l'île comptent, un bloc plus loin non", () => {
    assert.deepEqual(parcelAt(at(COIN, COIN), ORIGINE, ILES, VILLE), { kind: "on", x: 0, z: 0 });
    assert.deepEqual(parcelAt(at(COIN + 511.9, COIN + 511.9), ORIGINE, ILES, VILLE), { kind: "on", x: 496, z: 496 });
    assert.deepEqual(parcelAt(at(COIN + 512, COIN + 10), ORIGINE, ILES, VILLE), { kind: "off" });
    assert.deepEqual(parcelAt(at(COIN + 10, COIN + 512), ORIGINE, ILES, VILLE), { kind: "off" });
    assert.deepEqual(parcelAt(at(COIN - 0.5, COIN + 10), ORIGINE, ILES, VILLE), { kind: "off" });
    assert.deepEqual(parcelAt(at(COIN + 10, COIN - 1), ORIGINE, ILES, VILLE), { kind: "off" });
  });

  it("l'altitude ne compte pas, l'origine de l'archipel oui", () => {
    assert.deepEqual(parcelAt(at(COIN + 20, COIN + 40, -60), ORIGINE, ILES, VILLE), { kind: "on", x: 16, z: 32 });
    assert.deepEqual(parcelAt(at(COIN + 20, COIN + 40, 900), ORIGINE, ILES, VILLE), { kind: "on", x: 16, z: 32 });
    assert.deepEqual(parcelAt(at(600 + COIN + 20, COIN + 40), AUTRE_ORIGINE, ILES, VILLE), { kind: "on", x: 16, z: 32 });
    assert.deepEqual(parcelAt(at(600 + COIN + 20, COIN + 40), ORIGINE, ILES, VILLE), { kind: "off" });
  });

  it("toute parcelle rendue est sur la grille que le service exige", () => {
    for (let x = 0; x < 600; x += 37) {
      for (let z = 0; z < 600; z += 41) {
        const reading = parcelAt(at(x + 0.3, z + 0.7), ORIGINE, ILES, VILLE);
        if (reading.kind === "on") {
          assert.equal(reading.x % 16, 0);
          assert.equal(reading.z % 16, 0);
        }
      }
    }
  });
});

// ---------------------------------------------------------------- la plateforme d'une zone

describe("la plateforme d'une zone", () => {
  it("est carrée et pleine sur 128 blocs, avec les couches des mini-îles", async () => {
    const world = fakeWorld();
    await replay(world, zoneSegments(ILES, VILLE, ORIGINE, 1));
    const x0 = COIN + 128;
    const z0 = COIN + 128;
    assert.equal(world.count(M.herbe), 128 * 128);
    assert.equal(world.count(M.terre), 2 * 128 * 128);
    assert.equal(world.count(M.pierre), 128 * 128);
    assert.equal(world.blocks.size, 4 * 128 * 128);
    assert.equal(world.blockAt(x0, ILES.hauteur, z0), M.herbe);
    assert.equal(world.blockAt(x0 + 127, ILES.hauteur, z0 + 127), M.herbe);
    assert.equal(world.blockAt(x0, ILES.hauteur - 1, z0), M.terre);
    assert.equal(world.blockAt(x0, ILES.hauteur - 2, z0), M.terre);
    assert.equal(world.blockAt(x0, ILES.hauteur - 3, z0), M.pierre);
    assert.equal(world.blockAt(x0, ILES.hauteur - 4, z0), undefined);
    assert.equal(world.blockAt(x0, ILES.hauteur + 1, z0), undefined);
  });

  it("ne déborde pas de la zone : un bloc de plus à chaque bord reste vide", async () => {
    const world = fakeWorld();
    await replay(world, zoneSegments(ILES, VILLE, ORIGINE, 1));
    const x0 = COIN + 128;
    const z0 = COIN + 128;
    assert.equal(world.blockAt(x0 - 1, ILES.hauteur, z0 + 5), undefined);
    assert.equal(world.blockAt(x0 + 128, ILES.hauteur, z0 + 5), undefined);
    assert.equal(world.blockAt(x0 + 5, ILES.hauteur, z0 - 1), undefined);
    assert.equal(world.blockAt(x0 + 5, ILES.hauteur, z0 + 128), undefined);
  });

  it("se pose par bandes de deux chunks, chacune dans sa propre boîte", () => {
    const segments = zoneSegments(ILES, VILLE, ORIGINE, 1);
    assert.equal(segments.length, 4);
    segments.forEach((segment, index) => {
      assert.deepEqual(segment.box.from, { x: COIN + 128, y: ILES.hauteur - 3, z: COIN + 128 + index * 32 });
      assert.deepEqual(segment.box.to, { x: COIN + 255, y: ILES.hauteur, z: COIN + 128 + index * 32 + 31 });
      assert.equal(segment.ops.length, ILES.plateforme.couches.length);
    });
  });

  it("la zone 16 touche le coin de l'île, la zone 2 se pose à côté de la zone 1", async () => {
    const world = fakeWorld();
    await replay(world, zoneSegments(ILES, VILLE, ORIGINE, 16));
    await replay(world, zoneSegments(ILES, VILLE, ORIGINE, 2));
    assert.equal(world.blockAt(COIN, ILES.hauteur, COIN), M.herbe);
    assert.equal(world.blockAt(COIN + 256, ILES.hauteur, COIN + 128), M.herbe);
    assert.equal(world.blockAt(COIN + 255, ILES.hauteur, COIN + 128), undefined);
    assert.equal(world.count(M.herbe), 2 * 128 * 128);
  });

  it("suit l'origine de l'archipel", async () => {
    const world = fakeWorld();
    await replay(world, zoneSegments(ILES, VILLE, AUTRE_ORIGINE, 1));
    assert.equal(world.blockAt(600 + COIN + 128, ILES.hauteur, COIN + 128), M.herbe);
    assert.equal(world.blockAt(COIN + 128, ILES.hauteur, COIN + 128), undefined);
  });

  it("une zone inconnue de zones.ordre ne pose rien", () => {
    assert.deepEqual(zoneSegments(ILES, VILLE, ORIGINE, 0), []);
    assert.deepEqual(zoneSegments(ILES, VILLE, ORIGINE, 17), []);
  });
});

// ---------------------------------------------------------------- les préfabriqués

describe("la mairie", () => {
  // Parcelle (192, 192) : coin du monde (236, 236), centre (243, 243), 11 blocs de large : x et z de 238 à 248.
  const hall = cityBuilding();

  it("a quatre murs de pierre, une porte de deux blocs au sud, un toit de dalles et une lanterne", async () => {
    const world = await built(hall);
    assert.equal(world.blockAt(238, 101, 243), M.pierre);
    assert.equal(world.blockAt(248, 106, 243), M.pierre);
    assert.equal(world.blockAt(243, 101, 238), M.pierre);
    assert.equal(world.blockAt(242, 101, 248), M.pierre);
    assert.equal(world.blockAt(244, 101, 248), M.pierre);
    assert.equal(world.blockAt(243, 101, 248), undefined);
    assert.equal(world.blockAt(243, 102, 248), undefined);
    assert.equal(world.blockAt(243, 103, 248), M.pierre);
    assert.equal(world.blockAt(243, 107, 243), M.toit);
    assert.equal(world.blockAt(238, 107, 248), M.toit);
    assert.equal(world.blockAt(243, 108, 243), M.lumiere);
    assert.equal(world.blockAt(243, 101, 243), undefined);
    assert.equal(world.blockAt(243, 106, 243), undefined);
    assert.equal(world.blockAt(243, 108, 238), undefined);
  });

  it("porte une enseigne au-dessus de la porte, tournée vers l'extérieur, avec son nom et son niveau, sans ligne de bonus", async () => {
    const world = await built(hall);
    assert.equal(world.blockAt(243, 103, 249), M.enseigne);
    assert.deepEqual(world.blocks.get("243,103,249")?.states, { facing_direction: 3 });
    assert.equal(world.texts.get("243,103,249"), "Mairie\nNiveau 1");
    assert.equal(world.count(M.enseigne), 1);
  });

  it("prend un étage tous les deux niveaux : le toit monte avec le niveau", async () => {
    const heights = new Map<number, number>();
    for (const level of [1, 2, 3, 4, 5, 16]) {
      const world = await built(cityBuilding({ level }));
      let top = 0;
      for (const key of world.blocks.keys()) {
        const [x, y, z] = key.split(",").map(Number);
        if (x === 238 && z === 243 && y !== undefined) top = Math.max(top, y);
      }
      heights.set(level, top);
    }
    assert.deepEqual([...heights.entries()], [[1, 107], [2, 107], [3, 108], [4, 108], [5, 109], [16, 114]]);
  });

  it("l'enseigne suit le niveau", async () => {
    const world = await built(cityBuilding({ level: 3 }));
    assert.equal(world.texts.get("243,103,249"), "Mairie\nNiveau 3");
  });
});

describe("les bâtiments à quatre murs", () => {
  // Une maison sur la parcelle (128, 128) : coin du monde (172, 172), centre (179, 179), 7 blocs : de 176 à 182.
  const at = (rotation: BuildingRotation): CityBuilding => house({ rotation });

  it("la porte est sur la face que dit la rotation, le mur d'en face est plein", async () => {
    const expected: Record<BuildingRotation, { door: [number, number]; wall: [number, number]; sign: [number, number]; facing: number }> = {
      0: { door: [179, 182], wall: [179, 176], sign: [179, 183], facing: 3 },
      90: { door: [176, 179], wall: [182, 179], sign: [175, 179], facing: 4 },
      180: { door: [179, 176], wall: [179, 182], sign: [179, 175], facing: 2 },
      270: { door: [182, 179], wall: [176, 179], sign: [183, 179], facing: 5 },
    };
    for (const rotation of ROTATIONS) {
      const world = await built(at(rotation));
      const { door, wall, sign, facing } = expected[rotation];
      assert.equal(world.blockAt(door[0], 101, door[1]), undefined, `porte basse ${rotation}`);
      assert.equal(world.blockAt(door[0], 102, door[1]), undefined, `porte haute ${rotation}`);
      assert.equal(world.blockAt(door[0], 103, door[1]), M.planches, `linteau ${rotation}`);
      assert.equal(world.blockAt(wall[0], 101, wall[1]), M.planches, `mur plein ${rotation}`);
      assert.equal(world.blockAt(wall[0], 102, wall[1]), M.planches);
      assert.equal(world.blockAt(sign[0], 103, sign[1]), M.enseigne, `enseigne ${rotation}`);
      assert.deepEqual(world.blocks.get(`${sign[0]},103,${sign[1]}`)?.states, { facing_direction: facing });
      assert.equal(world.texts.get(`${sign[0]},103,${sign[1]}`), "Maison\nNiveau 1\nLogements 1");
      assert.equal(world.count(M.enseigne), 1);
    }
  });

  it("la maison n'a pas de lanterne, le commerce en a une", async () => {
    assert.equal((await built(house())).count(M.lumiere), 0);
    const commerce = cityBuilding({ buildingId: 3, buildingType: "commerce", maxLevel: 4, bonusType: "PRODUCTION_DONAR", bonus: 0.5, x: 192, z: 192 });
    assert.equal((await built(commerce)).count(M.lumiere), 1);
  });

  it("un bâtiment plus long que large se tourne avec sa rotation", async () => {
    // Le commerce fait 11 sur 9 : large de 11 en x à 0° et 180°, de 9 en x à 90° et 270°.
    const commerce = (rotation: BuildingRotation) =>
      cityBuilding({ buildingId: 3, buildingType: "commerce", maxLevel: 4, bonusType: "PRODUCTION_DONAR", bonus: 0.5, x: 192, z: 192, rotation });
    const droit = await built(commerce(0));
    assert.equal(droit.blockAt(238, 101, 243), M.planches);
    assert.equal(droit.blockAt(248, 101, 243), M.planches);
    assert.equal(droit.blockAt(243, 101, 239), M.planches);
    assert.equal(droit.blockAt(243, 101, 238), undefined);
    const tourne = await built(commerce(90));
    assert.equal(tourne.blockAt(239, 101, 241), M.planches);
    assert.equal(tourne.blockAt(239, 101, 243), undefined, "la porte est sur le côté ouest");
    assert.equal(tourne.blockAt(238, 101, 241), undefined);
    assert.equal(tourne.blockAt(243, 101, 238), M.planches);
    assert.equal(tourne.blockAt(243, 101, 248), M.planches);
  });

  it("l'enseigne rend le bonus tel que le service le donne", async () => {
    const commerce = cityBuilding({ buildingId: 3, buildingType: "commerce", level: 2, maxLevel: 4, bonusType: "PRODUCTION_DONAR", bonus: 1, x: 192, z: 192 });
    assert.deepEqual([...(await built(commerce)).texts.values()], ["Commerce\nNiveau 2\nDonar +1 %"]);
    const garage = cityBuilding({ buildingId: 4, buildingType: "garage", level: 3, maxLevel: 3, bonusType: "LIVRAISON_VITESSE", bonus: 15, x: 192, z: 192 });
    assert.deepEqual([...(await built(garage)).texts.values()], ["Garage\nNiveau 3\nLivraison +15 %"]);
  });

  it("l'immeuble prend deux blocs par niveau, la maison un", async () => {
    const top = async (building: CityBuilding): Promise<number> => {
      const world = await built(building);
      return Math.max(...[...world.blocks.keys()].map((key) => Number(key.split(",")[1])));
    };
    const immeuble = (level: number) => cityBuilding({ buildingId: 5, buildingType: "immeuble", level, maxLevel: 4, x: 192, z: 192 });
    // Murs de 6, étage de 2 : toit à wallTop + 1, lanterne absente.
    assert.equal(await top(immeuble(1)), 107);
    assert.equal(await top(immeuble(4)), 113);
    assert.equal(await top(house({ level: 1 })), 105);
    assert.equal(await top(house({ level: 3 })), 107);
  });

  it("un type que l'add-on ne connaît pas devient une boîte de planches qui porte son nom", async () => {
    const inconnu = cityBuilding({ buildingId: 9, buildingType: "entrepot", level: 2, maxLevel: 3, x: 192, z: 192 });
    const world = await built(inconnu);
    assert.equal(world.blockAt(240, 101, 243), M.planches);
    assert.equal(world.blockAt(246, 101, 243), M.planches);
    assert.equal(world.blockAt(247, 101, 243), undefined);
    assert.equal(world.texts.get("243,103,247"), `${batimentName("entrepot")}\nNiveau 2`);
  });

  it("reste dans sa parcelle, quels que soient le type, la rotation et le niveau", async () => {
    for (const buildingType of BATIMENT_IDS) {
      for (const rotation of ROTATIONS) {
        for (const level of [1, 2, 5, 16]) {
          const building = cityBuilding({ buildingId: 6, buildingType, level, maxLevel: 16, x: 304, z: 96, rotation });
          const parcel = { x: COIN + 304, z: COIN + 96 };
          const segments = buildingSegments(ILES, VILLE, ORIGINE, building);
          assert.equal(segments.length, 1);
          for (const segment of segments) {
            assert.ok(segment.box.from.x >= parcel.x && segment.box.to.x <= parcel.x + 15, `${buildingType} ${rotation} x`);
            assert.ok(segment.box.from.z >= parcel.z && segment.box.to.z <= parcel.z + 15, `${buildingType} ${rotation} z`);
            assert.ok(segment.box.from.y >= ILES.hauteur);
          }
          // Le faux monde refuse toute écriture hors de la boîte chargée : rejouer suffit à le prouver.
          await replay(fakeWorld(), segments);
        }
      }
    }
  });

  it("refaire un bâtiment sur sa parcelle efface l'ancien : même résultat qu'une pose sur un sol vide", async () => {
    const world = await built(house({ level: 1, rotation: 0 }));
    await built(house({ level: 3, rotation: 90 }), ORIGINE, world);
    const fresh = await built(house({ level: 3, rotation: 90 }));
    assert.deepEqual(world.snapshot(), fresh.snapshot());
    assert.deepEqual([...world.texts.entries()], [...fresh.texts.entries()]);
  });

  it("la mairie améliorée recouvre l'ancienne : plus d'ancienne lanterne ni d'ancienne enseigne", async () => {
    const world = await built(cityBuilding({ level: 1 }));
    await built(cityBuilding({ level: 5 }), ORIGINE, world);
    const fresh = await built(cityBuilding({ level: 5 }));
    assert.deepEqual(world.snapshot(), fresh.snapshot());
    assert.equal(world.count(M.lumiere), 1);
    assert.equal(world.count(M.enseigne), 1);
  });
});

describe("la route", () => {
  // Parcelle (208, 192) : coin du monde (252, 236), axe au milieu : x de 259 à 260 pour la plus étroite.
  const road = (level: number, rotation: BuildingRotation = 0) =>
    cityBuilding({ buildingId: 8, buildingType: "route", level, maxLevel: 2, x: 208, z: 192, rotation });

  it("est une bande de gravats au ras du sol, sans mur ni enseigne", async () => {
    const world = await built(road(1));
    assert.equal(world.blocks.size, 2 * 16);
    assert.equal(world.count(M.gravats), 2 * 16);
    assert.equal(world.blockAt(259, ILES.hauteur, 236), M.gravats);
    assert.equal(world.blockAt(260, ILES.hauteur, 251), M.gravats);
    assert.equal(world.blockAt(258, ILES.hauteur, 240), undefined);
    assert.equal(world.blockAt(261, ILES.hauteur, 240), undefined);
    assert.equal(world.count(M.enseigne), 0);
    assert.equal(world.texts.size, 0);
  });

  it("s'élargit de deux blocs par niveau autour du même axe, jusqu'à huit", async () => {
    const widths: number[] = [];
    let previous = new Set<string>();
    for (const level of [1, 2, 3, 4, 5, 9]) {
      const world = await built(road(level));
      widths.push(world.blocks.size / 16);
      const now = new Set(world.blocks.keys());
      for (const key of previous) assert.ok(now.has(key), `le niveau ${level} recouvre le précédent`);
      previous = now;
    }
    assert.deepEqual(widths, [2, 4, 6, 8, 8, 8]);
  });

  it("court dans le sens de la rotation : nord-sud à 0° et 180°, est-ouest à 90° et 270°", async () => {
    for (const rotation of [0, 180] as const) {
      const world = await built(road(1, rotation));
      assert.equal(world.blockAt(259, ILES.hauteur, 236), M.gravats);
      assert.equal(world.blockAt(252, ILES.hauteur, 243), undefined);
    }
    for (const rotation of [90, 270] as const) {
      const world = await built(road(1, rotation));
      assert.equal(world.blockAt(252, ILES.hauteur, 243), M.gravats);
      assert.equal(world.blockAt(267, ILES.hauteur, 244), M.gravats);
      assert.equal(world.blockAt(259, ILES.hauteur, 236), undefined);
    }
  });

  it("ne sort pas de sa parcelle, même au plus large", async () => {
    const [segment] = buildingSegments(ILES, VILLE, ORIGINE, road(4));
    assert.ok(segment !== undefined);
    assert.ok(segment.box.from.x >= 252 && segment.box.to.x <= 267);
    assert.ok(segment.box.from.z >= 236 && segment.box.to.z <= 251);
    await replay(fakeWorld(), [segment]);
  });
});

describe("les types de bâtiment", () => {
  it("les six du catalogue ont un aspect, rien d'autre", () => {
    for (const id of BATIMENT_IDS) assert.equal(isKnownBuilding(id), true, id);
    for (const inconnu of ["entrepot", "", "Mairie", "toString", "constructor", "__proto__", "hasOwnProperty"]) {
      assert.equal(isKnownBuilding(inconnu), false, inconnu);
    }
  });
});

describe("où se tenir devant la porte", () => {
  it("deux blocs devant la porte, au niveau du sol, le regard sur la porte", () => {
    const hall = cityBuilding();
    const expected: Record<BuildingRotation, { position: Pos; facing: Pos }> = {
      0: { position: { x: 243.5, y: surfaceY(ILES), z: 250.5 }, facing: { x: 243.5, y: surfaceY(ILES) + 1.6, z: 248.5 } },
      90: { position: { x: 236.5, y: surfaceY(ILES), z: 243.5 }, facing: { x: 238.5, y: surfaceY(ILES) + 1.6, z: 243.5 } },
      180: { position: { x: 243.5, y: surfaceY(ILES), z: 236.5 }, facing: { x: 243.5, y: surfaceY(ILES) + 1.6, z: 238.5 } },
      270: { position: { x: 250.5, y: surfaceY(ILES), z: 243.5 }, facing: { x: 248.5, y: surfaceY(ILES) + 1.6, z: 243.5 } },
    };
    for (const rotation of ROTATIONS) {
      assert.deepEqual(doorArrival(ORIGINE, ILES, VILLE, { ...hall, rotation }), expected[rotation], `rotation ${rotation}`);
    }
  });

  it("la place est libre dans le bâtiment posé, sur la parcelle, et la porte aussi", async () => {
    for (const rotation of ROTATIONS) {
      for (const building of [cityBuilding({ rotation }), house({ rotation })]) {
        const world = await built(building);
        const { position, facing } = doorArrival(ORIGINE, ILES, VILLE, building);
        const x = Math.floor(position.x);
        const z = Math.floor(position.z);
        const parcel = { x: COIN + building.x, z: COIN + building.z };
        assert.ok(x >= parcel.x && x <= parcel.x + 15 && z >= parcel.z && z <= parcel.z + 15, "sur la parcelle");
        assert.equal(world.blockAt(x, surfaceY(ILES), z), undefined, "pieds libres");
        assert.equal(world.blockAt(x, surfaceY(ILES) + 1, z), undefined, "tête libre");
        assert.equal(world.blockAt(Math.floor(facing.x), surfaceY(ILES), Math.floor(facing.z)), undefined, "la porte est ouverte");
      }
    }
  });

  it("suit l'origine de l'archipel", () => {
    const sud = doorArrival(AUTRE_ORIGINE, ILES, VILLE, cityBuilding());
    assert.equal(sud.position.x, 600 + 243.5);
  });
});

// ---------------------------------------------------------------- les travaux

describe("les travaux de la ville", () => {
  it("les zones possédées d'abord, puis les bâtiments, la mairie en tête", () => {
    const city = withNext(3, [house({ buildingId: 4 }), cityBuilding(), house({ buildingId: 2, x: 144 })]);
    const works = cityWorks(ILES, VILLE, ORIGINE, city);
    assert.deepEqual(
      works.map((work) => work.markKey),
      [zoneKey(1), zoneKey(2), buildingKey(1), buildingKey(4), buildingKey(2)],
    );
    assert.deepEqual(
      works.map((work) => work.kind),
      ["zone", "zone", "bâtiment", "bâtiment", "bâtiment"],
    );
    assert.deepEqual(works.map((work) => work.ref), ["1", "2", "1", "4", "2"]);
  });

  it("ne réordonne pas la liste du service", () => {
    const city = withNext(2, [house({ buildingId: 4 }), cityBuilding()]);
    cityWorks(ILES, VILLE, ORIGINE, city);
    assert.deepEqual(
      city.buildings.map((building) => building.buildingId),
      [4, 1],
    );
  });

  it("un bâtiment attend la plateforme de sa zone, une plateforme n'attend personne", () => {
    const city = withNext(3, [cityBuilding(), house({ buildingId: 2, zoneIndex: 2, x: 256, z: 128 })]);
    const works = cityWorks(ILES, VILLE, ORIGINE, city);
    assert.deepEqual(
      works.map((work) => work.after),
      [undefined, undefined, zoneKey(1), zoneKey(2)],
    );
  });

  it("sans zone à vendre, les seize sont posées ; sans zone possédée ni bâtiment, rien", () => {
    assert.equal(cityWorks(ILES, VILLE, ORIGINE, withNext(null, [])).length, 16);
    assert.deepEqual(cityWorks(ILES, VILLE, ORIGINE, emptyCity()), []);
  });

  it("ne dépasse pas ce que zones.ordre décrit", () => {
    const court = { ...VILLE, zones: { ...VILLE.zones, ordre: VILLE.zones.ordre.slice(0, 3) } };
    assert.equal(cityWorks(ILES, court, ORIGINE, withNext(null, [])).length, 3);
    assert.equal(cityWorks(ILES, court, ORIGINE, withNext(12, [])).length, 3);
  });

  it("les marques changent avec le niveau, la parcelle, la rotation et l'origine, et seulement avec eux", () => {
    const base = buildingMark(cityBuilding(), ORIGINE);
    assert.equal(base, "mairie:1:192,192:0@0,0");
    const marks = new Set([
      base,
      buildingMark(cityBuilding({ level: 2 }), ORIGINE),
      buildingMark(cityBuilding({ x: 208 }), ORIGINE),
      buildingMark(cityBuilding({ z: 208 }), ORIGINE),
      buildingMark(cityBuilding({ rotation: 90 }), ORIGINE),
      buildingMark(cityBuilding(), AUTRE_ORIGINE),
    ]);
    assert.equal(marks.size, 6);
    assert.equal(buildingMark(cityBuilding({ bonus: 3, nextLevel: null, maxLevel: 3 }), ORIGINE), base);
    assert.equal(zoneMark(ORIGINE), "ok@0,0");
    assert.equal(zoneMark(AUTRE_ORIGINE), "ok@600,0");
  });

  it("les clés de la ville ne se confondent pas avec un identifiant d'usine", () => {
    assert.equal(zoneKey(3), "ville:zone:3");
    assert.equal(buildingKey(12), "ville:b:12");
    for (const usine of ILES.usines) assert.ok(!usine.id.startsWith("ville:"));
  });

  it("calcule les blocs à la demande seulement", () => {
    const [zone, batiment] = cityWorks(ILES, VILLE, ORIGINE, withNext(2, [cityBuilding()]));
    assert.equal(zone?.segments().length, 4);
    assert.equal(batiment?.segments().length, 1);
  });
});

// ---------------------------------------------------------------- le constructeur

interface Banc {
  readonly temps: ReturnType<typeof virtualTime>;
  readonly monde: FakeWorld;
  readonly marques: FakeMarks;
  readonly log: ReturnType<typeof recordingLogger>;
  readonly constructeur: ReturnType<typeof createIslandBuilder>;
}

function banc(options: { marques?: FakeMarks } = {}): Banc {
  const temps = virtualTime();
  const monde = fakeWorld(temps.clock);
  const marques = options.marques ?? fakeMarks();
  const log = recordingLogger();
  const constructeur = createIslandBuilder({
    defs: ILES,
    writer: monde,
    marks: marques,
    scheduler: temps.scheduler,
    clock: temps.clock,
    log,
    settings: config.world,
  });
  return { temps, monde, marques, log, constructeur };
}

const marksOf = (b: Banc, companyId = 7): Record<string, string> => b.marques.stored.get(companyId) ?? {};

describe("poser la ville", () => {
  it("pose la plateforme de la zone puis la mairie, une zone chargée à la fois, et note leurs marques", async () => {
    const b = banc();
    const city = withNext(2, [cityBuilding()]);
    assert.equal(b.constructeur.syncCity(7, ORIGINE, city), 2);
    await b.temps.advance(10);
    assert.equal(b.constructeur.pending(), 0);
    assert.deepEqual(marksOf(b), { "ville:zone:1": "ok@0,0", "ville:b:1": "mairie:1:192,192:0@0,0" });
    assert.equal(b.monde.maxOpenAreas, 1);
    assert.equal(b.monde.areas.length, 5);
    assert.equal(b.monde.count(M.herbe), 128 * 128);
    assert.equal(b.monde.texts.get("243,103,249"), "Mairie\nNiveau 1");
    const zone = b.log.infos.findIndex((line) => line.startsWith("zone 7/1 posée"));
    const mairie = b.log.infos.findIndex((line) => line.startsWith("bâtiment 7/1 posé"));
    assert.ok(zone >= 0 && mairie > zone, b.log.infos.join(" | "));
  });

  it("espace les bandes d'une plateforme de la pause entre lots, sans figer le serveur", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, []));
    await b.temps.advance(10);
    assert.equal(b.monde.attemptTimes.length, 4);
    for (let index = 1; index < b.monde.attemptTimes.length; index += 1) {
      const gap = (b.monde.attemptTimes[index] ?? 0) - (b.monde.attemptTimes[index - 1] ?? 0);
      assert.ok(gap >= config.world.batchPauseSeconds * 1000, `écart ${gap} ms`);
    }
  });

  it("ne repose rien quand la ville n'a pas changé", async () => {
    const b = banc();
    const city = withNext(2, [cityBuilding()]);
    b.constructeur.syncCity(7, ORIGINE, city);
    await b.temps.advance(10);
    const ops = b.monde.ops;
    const writes = b.marques.writes;
    assert.equal(b.constructeur.syncCity(7, ORIGINE, city), 0);
    await b.temps.advance(10);
    assert.equal(b.monde.ops, ops);
    assert.equal(b.marques.writes, writes);
  });

  it("une demande répétée pendant la pose ne double pas le chantier", async () => {
    const b = banc();
    const city = withNext(2, [cityBuilding()]);
    assert.equal(b.constructeur.syncCity(7, ORIGINE, city), 2);
    assert.equal(b.constructeur.syncCity(7, ORIGINE, city), 2);
    await b.temps.advance(10);
    assert.equal(b.log.infos.filter((line) => line.startsWith("zone 7/1 posée")).length, 1);
    assert.equal(b.log.infos.filter((line) => line.startsWith("bâtiment 7/1 posé")).length, 1);
  });

  it("une amélioration ne refait que le bâtiment : nouvelle marque, nouvelle enseigne, plateforme intouchée", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(10);
    const areas = b.monde.areas.length;
    assert.equal(b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding({ level: 2 })])), 1);
    await b.temps.advance(10);
    assert.equal(b.monde.areas.length, areas + 1);
    assert.equal(marksOf(b)["ville:b:1"], "mairie:2:192,192:0@0,0");
    assert.equal(b.monde.texts.get("243,103,249"), "Mairie\nNiveau 2");
    assert.equal(b.log.infos.filter((line) => line.startsWith("zone 7/1 posée")).length, 1);
    assert.equal(b.log.infos.filter((line) => line.startsWith("bâtiment 7/1 posé")).length, 2);
  });

  it("une nouvelle zone n'ajoute que sa plateforme", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(10);
    assert.equal(b.constructeur.syncCity(7, ORIGINE, withNext(3, [cityBuilding()])), 1);
    await b.temps.advance(10);
    assert.equal(marksOf(b)["ville:zone:2"], "ok@0,0");
    assert.equal(b.monde.blockAt(COIN + 256, ILES.hauteur, COIN + 128), M.herbe);
    assert.equal(b.monde.count(M.herbe), 2 * 128 * 128);
    assert.ok(b.log.infos.some((line) => line.startsWith("zone 7/2 posée")));
  });

  it("une nouvelle origine d'archipel refait tout au nouvel endroit, sans rien démolir ailleurs", async () => {
    const b = banc();
    const city = withNext(2, [cityBuilding()]);
    b.constructeur.syncCity(7, ORIGINE, city);
    await b.temps.advance(10);
    assert.equal(b.constructeur.syncCity(7, AUTRE_ORIGINE, city), 2);
    await b.temps.advance(10);
    assert.deepEqual(marksOf(b), { "ville:zone:1": "ok@600,0", "ville:b:1": "mairie:1:192,192:0@600,0" });
    assert.equal(b.monde.blockAt(600 + COIN + 128, ILES.hauteur, COIN + 128), M.herbe);
    assert.equal(b.monde.blockAt(COIN + 128, ILES.hauteur, COIN + 128), M.herbe);
    assert.equal(b.monde.count(M.enseigne), 2);
  });

  it("un bâtiment qui disparaît de la liste n'est pas démoli", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding(), house()]));
    await b.temps.advance(10);
    const blocks = b.monde.blocks.size;
    assert.equal(b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()])), 0);
    await b.temps.advance(10);
    assert.equal(b.monde.blocks.size, blocks);
    assert.equal(marksOf(b)["ville:b:2"], "maison:1:128,128:0@0,0");
  });

  it("deux entreprises ont chacune leurs marques et leur île", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    b.constructeur.syncCity(8, AUTRE_ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(20);
    assert.equal(marksOf(b, 7)["ville:b:1"], "mairie:1:192,192:0@0,0");
    assert.equal(marksOf(b, 8)["ville:b:1"], "mairie:1:192,192:0@600,0");
    assert.equal(b.monde.count(M.enseigne), 2);
    assert.equal(b.monde.maxOpenAreas, 1);
  });
});

describe("la ville face aux échecs", () => {
  it("un bâtiment attend la plateforme de sa zone : tant qu'elle échoue, il ne se pose pas", async () => {
    const b = banc();
    b.monde.failLoads = 2;
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(1);
    assert.equal(b.monde.attemptTimes.length, 1, "seule la plateforme a essayé");
    assert.equal(b.constructeur.pending(), 2);
    assert.deepEqual(marksOf(b), {});
    assert.equal(b.monde.blocks.size, 0);
    // Premier essai raté à t = 0, deuxième à 2 s (raté), troisième 4 s plus tard.
    await b.temps.advance(30);
    assert.equal(b.constructeur.pending(), 0);
    assert.deepEqual(Object.keys(marksOf(b)).sort(), ["ville:b:1", "ville:zone:1"]);
    assert.ok(b.log.warns.some((line) => line.includes("zone 7/1")), b.log.warns.join(" | "));
    const zone = b.log.infos.findIndex((line) => line.startsWith("zone 7/1 posée"));
    const mairie = b.log.infos.findIndex((line) => line.startsWith("bâtiment 7/1 posé"));
    assert.ok(zone >= 0 && mairie > zone);
    // Rien du bâtiment n'a été écrit avant la fin de la plateforme.
    assert.equal(b.monde.count(M.herbe), 128 * 128);
  });

  it("la pause entre deux essais double à chaque échec, jusqu'au plafond", async () => {
    const b = banc();
    b.monde.failLoads = 4;
    b.constructeur.syncCity(7, ORIGINE, withNext(2, []));
    await b.temps.advance(60);
    const t = b.monde.attemptTimes;
    assert.ok(t.length >= 5);
    const gaps = t.slice(1, 5).map((time, index) => ((time - (t[index] ?? 0)) / 1000));
    assert.deepEqual(gaps, [2, 4, 8, 16]);
  });

  it("un bâtiment dont la zone n'est pas dans les travaux ne reste pas à attendre", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [house({ zoneIndex: 5, x: 304, z: 96 })]));
    await b.temps.advance(10);
    assert.equal(marksOf(b)["ville:b:2"], "maison:1:304,96:0@0,0");
  });

  it("un type inconnu est signalé une seule fois, et posé en boîte de planches", async () => {
    const b = banc();
    const inconnu = cityBuilding({ buildingId: 9, buildingType: "entrepot", x: 192, z: 192 });
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [inconnu]));
    await b.temps.advance(10);
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [{ ...inconnu, level: 2 }]));
    b.constructeur.syncCity(7, AUTRE_ORIGINE, withNext(2, [{ ...inconnu, level: 2 }]));
    await b.temps.advance(10);
    assert.equal(b.log.warns.filter((line) => line.includes("entrepot")).length, 1);
    assert.equal(b.monde.blockAt(240, 101, 243), M.planches);
  });
});

describe("attendre un bâtiment", () => {
  it("whenReady résout dès que le bâtiment est posé", async () => {
    const b = banc();
    let answer: boolean | undefined;
    void b.constructeur.whenReady(7, buildingKey(1), 60).then((ready) => {
      answer = ready;
    });
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(0.06);
    assert.equal(answer, undefined, "la plateforme n'est pas finie");
    await b.temps.advance(5);
    assert.equal(answer, true);
  });

  it("résout tout de suite pour un bâtiment déjà posé", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(10);
    assert.equal(await b.constructeur.whenReady(7, buildingKey(1), 60), true);
    assert.equal(await b.constructeur.whenReady(7, zoneKey(1), 60), true);
  });

  it("résout faux au bout du délai si rien ne se pose", async () => {
    const b = banc();
    b.monde.failLoads = 100;
    let answer: boolean | undefined;
    void b.constructeur.whenReady(7, buildingKey(1), 5).then((ready) => {
      answer = ready;
    });
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(4);
    assert.equal(answer, undefined);
    await b.temps.advance(2);
    assert.equal(answer, false);
  });

  it("une amélioration en cours n'est pas « prête » : le joueur attend la nouvelle enseigne", async () => {
    const b = banc();
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(10);
    b.monde.failLoads = 1;
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding({ level: 2 })]));
    let answer: boolean | undefined;
    void b.constructeur.whenReady(7, buildingKey(1), 60).then((ready) => {
      answer = ready;
    });
    await b.temps.advance(1);
    assert.equal(answer, undefined, "l'ancienne mairie est encore là");
    assert.equal(b.monde.texts.get("243,103,249"), "Mairie\nNiveau 1");
    await b.temps.advance(5);
    assert.equal(answer, true);
    assert.equal(b.monde.texts.get("243,103,249"), "Mairie\nNiveau 2");
  });
});

describe("la ville et les mini-îles dans le même constructeur", () => {
  it("partagent les marques d'une entreprise sans s'effacer", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [factory({ factoryId: "carriere", state: "ACTIVE" })]);
    b.constructeur.syncCity(7, ORIGINE, withNext(2, [cityBuilding()]));
    await b.temps.advance(20);
    assert.equal(b.constructeur.pending(), 0);
    assert.deepEqual(Object.keys(marksOf(b)).sort(), ["carriere", "ville:b:1", "ville:zone:1"]);
    assert.equal(marksOf(b)["carriere"], "ACTIVE@0,0");
    assert.equal(b.monde.maxOpenAreas, 1);
    assert.ok(b.log.infos.some((line) => line.startsWith("île 7/carriere posée")));
  });

  it("un redémarrage retrouve les marques et ne repose rien", async () => {
    const premier = banc();
    const city = withNext(2, [cityBuilding(), house()]);
    premier.constructeur.syncCity(7, ORIGINE, city);
    await premier.temps.advance(20);
    assert.equal(premier.constructeur.pending(), 0);

    const apres = banc({ marques: premier.marques });
    assert.equal(apres.constructeur.syncCity(7, ORIGINE, city), 0);
    await apres.temps.advance(20);
    assert.equal(apres.monde.ops, 0);
    assert.equal(apres.monde.blocks.size, 0);
  });
});
