// Les comptes d'Oooscar : une par joueur, jamais deux, retirée au départ, balayée au démarrage.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { Pos } from "../src/monde/geometrie";
import { createOooscarManager, type OooscarHandle, type OooscarWorld } from "../src/monde/oooscar";
import { recordingLogger } from "./helpers";

interface Entite {
  readonly handle: OooscarHandle;
  readonly at: Pos;
}

/** Un monde d'entités : on peut refuser les apparitions, faire échouer un retrait, faire disparaître une entité. */
function fakeOooscarWorld() {
  const entites = new Map<string, Entite>();
  let compteur = 0;
  const monde = {
    entites,
    refuseApparition: false,
    apparitionLeve: false,
    retraitLeve: false,
    spawns: 0,
    spawn(at: Pos): OooscarHandle | undefined {
      if (monde.apparitionLeve) throw new Error("chunk non chargé");
      if (monde.refuseApparition) return undefined;
      compteur += 1;
      monde.spawns += 1;
      const handle = { id: `e${compteur}` };
      entites.set(handle.id, { handle, at });
      return handle;
    },
    remove(handle: OooscarHandle): void {
      if (monde.retraitLeve) throw new Error("entité invalide");
      entites.delete(handle.id);
    },
    exists: (handle: OooscarHandle): boolean => entites.has(handle.id),
    all: (): readonly OooscarHandle[] => [...entites.values()].map((e) => e.handle),
    near: (center: Pos, radius: number): readonly OooscarHandle[] =>
      [...entites.values()].filter((e) => Math.hypot(e.at.x - center.x, e.at.y - center.y, e.at.z - center.z) <= radius).map((e) => e.handle),
    /** Une entité laissée par un plantage : le gestionnaire ne la connaît pas. */
    ajouteRodeuse(at: Pos): void {
      compteur += 1;
      const handle = { id: `e${compteur}` };
      entites.set(handle.id, { handle, at });
    },
  } satisfies OooscarWorld & Record<string, unknown>;
  return monde;
}

const ICI: Pos = { x: 100, y: 101, z: 100 };
const FACE: Pos = { x: 101, y: 102, z: 100 };

function gestionnaire() {
  const monde = fakeOooscarWorld();
  const log = recordingLogger();
  const manager = createOooscarManager({ world: monde, log, sweepRadius: 16 });
  return { monde, log, manager };
}

describe("Oooscar", () => {
  it("apparaît une fois pour un joueur", () => {
    const { monde, manager } = gestionnaire();
    assert.equal(manager.spawnFor("alex", ICI, FACE), true);
    assert.equal(monde.entites.size, 1);
    assert.equal(manager.count, 1);
  });

  it("ne s'accumule jamais : cinquante arrivées du même joueur, une seule Oooscar", () => {
    const { monde, manager } = gestionnaire();
    for (let i = 0; i < 50; i += 1) manager.spawnFor("alex", ICI, FACE);
    assert.equal(monde.entites.size, 1);
    assert.equal(manager.count, 1);
    assert.equal(monde.spawns, 50);
  });

  it("garde une Oooscar par joueur, et le départ de l'un ne retire que la sienne", () => {
    const { monde, manager } = gestionnaire();
    manager.spawnFor("alex", ICI, FACE);
    manager.spawnFor("bob", { x: 900, y: 101, z: 900 }, FACE);
    assert.equal(monde.entites.size, 2);
    assert.equal(manager.removeFor("alex"), true);
    assert.equal(monde.entites.size, 1);
    assert.equal(manager.count, 1);
    assert.equal(manager.removeFor("alex"), false);
    assert.equal(manager.removeFor("inconnu"), false);
  });

  it("retire celles qui traînent près du point d'apparition avant d'en faire apparaître une", () => {
    const { monde, manager } = gestionnaire();
    monde.ajouteRodeuse({ x: 103, y: 101, z: 100 });
    monde.ajouteRodeuse({ x: 90, y: 101, z: 100 });
    monde.ajouteRodeuse({ x: 5000, y: 101, z: 5000 });
    manager.spawnFor("alex", ICI, FACE);
    assert.equal(monde.entites.size, 2, "la nouvelle et la lointaine");
  });

  it("le balayage de départ retire toutes les Oooscar du monde, même inconnues, et oublie les comptes", () => {
    const { monde, manager } = gestionnaire();
    manager.spawnFor("alex", ICI, FACE);
    monde.ajouteRodeuse({ x: 5000, y: 101, z: 5000 });
    assert.equal(manager.sweep(), 2);
    assert.equal(monde.entites.size, 0);
    assert.equal(manager.count, 0);
    assert.equal(manager.sweep(), 0);
  });

  it("reap retire l'Oooscar d'un joueur qui n'est plus là", () => {
    const { monde, manager } = gestionnaire();
    manager.spawnFor("alex", ICI, FACE);
    manager.spawnFor("bob", { x: 900, y: 101, z: 900 }, FACE);
    assert.equal(manager.reap(new Set(["bob"])), 1);
    assert.equal(monde.entites.size, 1);
    assert.equal(manager.count, 1);
  });

  it("reap oublie une Oooscar que le monde a fait disparaître, et laisse les autres", () => {
    const { monde, manager } = gestionnaire();
    manager.spawnFor("alex", ICI, FACE);
    monde.entites.clear();
    assert.equal(manager.reap(new Set(["alex"])), 0);
    assert.equal(manager.count, 0);
    manager.spawnFor("alex", ICI, FACE);
    assert.equal(manager.reap(new Set(["alex"])), 0);
    assert.equal(manager.count, 1);
  });

  it("dit non, sans exception, quand le monde refuse ou lève", () => {
    const { monde, log, manager } = gestionnaire();
    monde.refuseApparition = true;
    assert.equal(manager.spawnFor("alex", ICI, FACE), false);
    monde.refuseApparition = false;
    monde.apparitionLeve = true;
    assert.equal(manager.spawnFor("alex", ICI, FACE), false);
    assert.equal(manager.count, 0);
    assert.equal(log.warns.length, 2);
  });

  it("un retrait qui échoue est journalisé, jamais lancé, et la nouvelle apparaît quand même", () => {
    const { monde, log, manager } = gestionnaire();
    manager.spawnFor("alex", ICI, FACE);
    monde.retraitLeve = true;
    assert.equal(manager.spawnFor("alex", ICI, FACE), true);
    assert.ok(log.warns.some((ligne) => ligne.includes("retrait impossible")));
    assert.equal(manager.removeFor("alex"), true);
    assert.equal(manager.sweep(), 0);
  });
});
