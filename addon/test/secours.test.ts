import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createFallRescue } from "../src/monde/secours";
import { ILES } from "../src/monde/definitions";
import { arrivalOf, type Pos } from "../src/monde/geometrie";

interface Joueur { readonly name: string; at: Pos; busy?: boolean; origin?: { x: number; z: number } }

describe("chute sous les îles", () => {
  function monde(joueurs: Joueur[]) {
    const poses: Array<{ name: string; at: Pos; facing: Pos }> = [];
    const rescue = createFallRescue<Joueur>({
      defs: ILES,
      players: () => joueurs,
      busy: (p) => p.busy === true,
      originOf: (p) => p.origin,
      world: {
        isOnline: () => true,
        locate: (p) => p.at,
        teleport: (p, at, facing) => { poses.push({ name: p.name, at, facing }); p.at = at; },
      },
    });
    return { rescue, poses };
  }

  it("repose à l'arrivée de sa Carrière un joueur tombé sous les îles", () => {
    const origin = { x: 600, z: 0 };
    const tombe: Joueur = { name: "kr", at: { x: 900, y: ILES.hauteur - ILES.secours.sousLesIles - 1, z: 300 }, origin };
    const { rescue, poses } = monde([tombe]);
    assert.equal(rescue.tick(), 1);
    const arrivee = arrivalOf(origin, ILES)!;
    assert.deepEqual(poses, [{ name: "kr", at: arrivee.position, facing: arrivee.facing }]);
    assert.equal(rescue.tick(), 0, "une fois reposé, il ne bouge plus");
  });

  it("laisse le joueur qui saute d'un bloc, celui de la cinématique et celui dont on ne connaît pas l'île", () => {
    const bas = ILES.hauteur - ILES.secours.sousLesIles - 5;
    const { rescue, poses } = monde([
      { name: "sur l'île", at: { x: 0, y: ILES.hauteur - 3, z: 0 }, origin: { x: 0, z: 0 } },
      { name: "cinématique", at: { x: 0, y: bas, z: 0 }, origin: { x: 0, z: 0 }, busy: true },
      { name: "inconnu", at: { x: 0, y: bas, z: 0 } },
    ]);
    assert.equal(rescue.tick(), 0);
    assert.deepEqual(poses, []);
  });
});
