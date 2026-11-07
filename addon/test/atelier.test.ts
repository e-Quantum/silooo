import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createWorkshop, currentWorkshopMarks, workshopRunning, type WorkshopWorld } from "../src/monde/atelier";
import { ILES } from "../src/monde/definitions";
import { planIsland } from "../src/monde/plan";
import { arrivalOf, viewPoint, islandRadius, usineIle } from "../src/monde/geometrie";
import { applyOp } from "../src/monde/constructeur";
import { fakeWorld } from "./monde-helpers";
import { companyState, factory } from "./helpers";

describe("atelier de la Carrière", () => {
  it("comporte une vraie chaîne de production et un bureau, tous dans la zone chargée", () => {
    const definition = usineIle(ILES, "carriere")!;
    assert.ok(islandRadius(ILES, definition) > ILES.plateforme.rayon);
    const plan = planIsland(ILES, definition, { x: 0, z: 0 }, "ACTIVE");
    const machines = plan.state.filter((op) => op.kind === "set" && op.block.startsWith("silooo:"));
    for (const type of ["silooo:broyeur", "silooo:convoyeur", "silooo:bac_pierre", "silooo:bureau_administration"]) {
      assert.ok(machines.some((op) => op.kind === "set" && op.block === type), type);
    }
    for (const op of plan.state) {
      const points = op.kind === "fill" ? [op.from, op.to] : [op.at];
      for (const at of points) for (const axis of ["x", "y", "z"] as const) {
        assert.ok(at[axis] >= plan.box.from[axis] && at[axis] <= plan.box.to[axis], `${op.kind} ${axis}`);
      }
    }
    const ruin = planIsland(ILES, definition, { x: 0, z: 0 }, "EN_RUINE");
    assert.ok(!ruin.state.some((op) => op.kind === "set" && op.block === "silooo:convoyeur"));
  });

  it("cadre le bureau à travers son entrée sans placer la caméra face à un mur", async () => {
    const origin = { x: 0, z: 0 };
    const plan = planIsland(ILES, usineIle(ILES, "carriere")!, origin, "ACTIVE");
    const world = fakeWorld();
    await world.withLoadedArea(plan.box, async () => { for (const op of [...plan.base, ...plan.state]) applyOp(world, op); });
    const shot = ILES.cinematique.plans.at(-1)!;
    const frame = arrivalOf(origin, ILES)!.frame;
    const camera = viewPoint(frame, shot.arrivee, ILES), target = viewPoint(frame, shot.regard, ILES);
    for (let step = 0; step < 90; step += 1) {
      const fraction = step / 100;
      const x = Math.floor(camera.x + (target.x - camera.x) * fraction);
      const y = Math.floor(camera.y + (target.y - camera.y) * fraction);
      const z = Math.floor(camera.z + (target.z - camera.z) * fraction);
      assert.equal(world.blockAt(x, y, z), undefined, `vue obstruée en ${x},${y},${z}`);
    }
  });

  it("suit l'arrêt réel de l'usine sans créer une embauche ni changer la réponse du service", () => {
    const state = companyState({ factories: [factory({ factoryId: "carriere", state: "ACTIVE", ratePerHour: 2400 })] });
    const before = JSON.stringify(state);
    assert.equal(workshopRunning(state, "carriere"), true);
    assert.equal(workshopRunning({ ...state, factories: [factory({ factoryId: "carriere", incidentId: 9, ratePerHour: 1200 })] }, "carriere"), true, "un incident peut ralentir sans arrêter la production");
    assert.equal(workshopRunning({ ...state, factories: [factory({ factoryId: "carriere", stoppedNoPower: true })] }, "carriere"), false);
    assert.equal(workshopRunning({ ...state, factories: [factory({ factoryId: "carriere", ratePerHour: 0 })] }, "carriere"), false);
    assert.equal(JSON.stringify(state), before);
  });

  it("attend les blocs avant de poser le salarié, réutilise le même et le retire au départ", () => {
    let ready = false, count = 0, cargoCount = 0;
    const removed: string[] = [];
    const working: boolean[] = [];
    const world: WorkshopWorld = {
      machine: () => ready,
      loaded: () => true,
      cargo(previous) { return previous ?? `cargo-${++cargoCount}`; },
      worker(previous, _company, _at, _facing, active) { working.push(active); return previous ?? `worker-${++count}`; },
      remove: (id) => { removed.push(id); },
    };
    const workshop = createWorkshop(ILES, world, { now: () => 0 });
    const state = companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] });
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(count, 0);
    ready = true;
    workshop.sync(state, { x: 0, z: 0 });
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(count, 1);
    workshop.sync({ ...state, factories: [factory({ factoryId: "carriere", incidentId: 3, ratePerHour: 0 })] }, { x: 0, z: 0 });
    assert.deepEqual(working, [true, true, false]);
    workshop.reap(new Set());
    assert.deepEqual(removed, ["worker-1", "cargo-1", "cargo-2", "cargo-3"]);
  });

  it("reconstruit une ancienne Carrière une seule fois, en gardant les autres îles et la ville", () => {
    const previous = { carriere: "ACTIVE@0,0", scierie: "ACTIVE@0,0", "ville:zone:1": "0,0" };
    assert.deepEqual(currentWorkshopMarks(previous, undefined, ILES), { scierie: "ACTIVE@0,0", "ville:zone:1": "0,0" });
    assert.deepEqual(currentWorkshopMarks(previous, ILES.atelier.revision, ILES), previous);
    assert.equal(previous.carriere, "ACTIVE@0,0");
  });
  it("déplace les pierres sur le convoyeur, les fige à l'arrêt et attend les chunks", () => {
    let now = 0, loaded = true, created = 0;
    const seen: Array<{ id: string; at: { x: number; y: number; z: number }; working: boolean }> = [];
    const removed: string[] = [];
    const native: WorkshopWorld = {
      machine: () => true, loaded: () => loaded,
      worker: (old) => old ?? "worker",
      cargo(previous, _company, at, working) { const id = previous ?? `pierre-${++created}`; seen.push({ id, at, working }); return id; },
      remove: (id) => { removed.push(id); },
    };
    const workshop = createWorkshop(ILES, native, { now: () => now });
    const state = companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] });
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(created, ILES.atelier.transport.pierres);
    const first = seen[0]!;
    now = 1000; workshop.tick();
    const moved = seen.findLast((entry) => entry.id === first.id)!;
    assert.ok(Math.abs(Math.hypot(moved.at.x - first.at.x, moved.at.z - first.at.z) - ILES.atelier.transport.vitesse) < 0.001);
    workshop.sync({ ...state, factories: [factory({ factoryId: "carriere", ratePerHour: 0, stoppedNoPower: true })] }, { x: 0, z: 0 });
    now = 2000; workshop.tick();
    const stopped = seen.findLast((entry) => entry.id === first.id)!;
    assert.deepEqual(stopped.at, moved.at);
    assert.equal(stopped.working, false);
    loaded = false;
    const count = seen.length;
    now = 3000; workshop.tick();
    assert.equal(seen.length, count, "aucune création ni téléportation dans des chunks déchargés");
    loaded = true;
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(created, ILES.atelier.transport.pierres, "les mêmes pierres sont réutilisées");
    workshop.reap(new Set());
    assert.equal(new Set(removed).size, ILES.atelier.transport.pierres + 1);
  });

});
