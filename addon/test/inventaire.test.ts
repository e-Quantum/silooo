import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { currentFactoryRoute, installNavigation, NAVIGATION, navigationInPlace, type NavigationItem } from "../src/ui/inventaire";
import { companyState, factory } from "./helpers";
import { ILES } from "../src/monde/definitions";
import { islandCenter } from "../src/monde/geometrie";

function inventory(contents: Array<NavigationItem | undefined>) {
  return { size: contents.length, getItem: (slot: number) => contents[slot], setItem: (slot: number, item?: NavigationItem) => { contents[slot] = item; } };
}
const make = (entry: typeof NAVIGATION[number]): NavigationItem => ({ typeId: entry.id, nameTag: entry.name });

describe("navigation depuis l'inventaire", () => {
  it("pose les quatre objets dans leurs cases, retire l'ancienne boussole et conserve les objets du joueur", () => {
    const stone = { typeId: "minecraft:stone" };
    const compass = { typeId: "minecraft:compass" };
    const contents: Array<NavigationItem | undefined> = [stone, { typeId: "minecraft:compass", nameTag: "Menu" }, undefined, undefined, compass, undefined, undefined, undefined, undefined];
    const inv = inventory(contents);
    assert.equal(installNavigation(inv, make), true);
    assert.deepEqual(contents.slice(0, 4).map((item) => item?.typeId), ["silooo:usine", "silooo:carte", "silooo:objectif", "silooo:ville"]);
    assert.equal(contents.filter((item) => item === stone).length, 1);
    assert.equal(contents.filter((item) => item === compass).length, 1);
    assert.equal(installNavigation(inv, make), true);
    assert.equal(contents.filter((item) => item?.typeId === "silooo:usine").length, 1);
  });

  it("ne modifie rien si aucun déplacement sûr n'est possible", () => {
    const contents = Array.from({ length: 9 }, (_, index) => ({ typeId: `minecraft:objet_${index}` }));
    const before = [...contents];
    assert.equal(installNavigation(inventory(contents), make), false);
    assert.deepEqual(contents, before);
  });

  it("ouvre l'usine où se trouve le joueur, même si elle n'est pas la première de sa liste", () => {
    const state = companyState({ island: { x: 0, z: 0 }, factories: [factory({ factoryId: "carriere" }), factory({ factoryId: "scierie" })] });
    const definition = ILES.usines.find((entry) => entry.id === "scierie")!;
    const position = { ...islandCenter(state.island!, definition, ILES), y: ILES.hauteur + 1 };
    assert.deepEqual(currentFactoryRoute(state, position), { screen: "fiche", factoryId: "scierie", page: 0 });
    assert.deepEqual(currentFactoryRoute(state, { x: -4000, z: 0, y: 100 }), { screen: "carte", page: 0 });
  });
});

describe("objets de navigation sans verrou", () => {
  it("dit qu'il faut les remettre quand un objet manque, a changé de case ou traîne en double", () => {
    const placed = (): Array<NavigationItem | undefined> => [...NAVIGATION.map(make), undefined, { typeId: "minecraft:stone" }, undefined, undefined, undefined];
    assert.equal(navigationInPlace(inventory(placed())), true);

    const dropped = placed();
    dropped[2] = undefined;
    assert.equal(navigationInPlace(inventory(dropped)), false, "un objet jeté");

    const moved = placed();
    [moved[1], moved[6]] = [moved[6], moved[1]];
    assert.equal(navigationInPlace(inventory(moved)), false, "un objet rangé ailleurs");

    const twice = placed();
    twice[7] = make(NAVIGATION[0]);
    assert.equal(navigationInPlace(inventory(twice)), false, "un double ramassé par terre");

    assert.equal(installNavigation(inventory(moved), make), true);
    assert.equal(navigationInPlace(inventory(moved)), true, "après la remise en place, tout est à sa case");
  });
});
