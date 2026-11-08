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
    for (const type of ["silooo:engrenage_bois", "silooo:tapis_toile", "silooo:coffre_vente", "silooo:bureau_administration"]) {
      assert.ok(machines.some((op) => op.kind === "set" && op.block === type), type);
    }
    for (const op of plan.state) {
      const points = op.kind === "fill" ? [op.from, op.to] : [op.at];
      for (const at of points) for (const axis of ["x", "y", "z"] as const) {
        assert.ok(at[axis] >= plan.box.from[axis] && at[axis] <= plan.box.to[axis], `${op.kind} ${axis}`);
      }
    }
    const ruin = planIsland(ILES, definition, { x: 0, z: 0 }, "EN_RUINE");
    assert.ok(!ruin.state.some((op) => op.kind === "set" && op.block === "silooo:tapis_toile"));
    // Le préfabriqué : la Carrière en marche quand elle est active, sinon sa ruine, la face du dessus à la hauteur des îles.
    assert.equal(plan.structure?.id, ILES.atelier.structure);
    assert.equal(ruin.structure?.id, ILES.atelier.structureRuine);
    assert.equal(planIsland(ILES, definition, { x: 0, z: 0 }, "REMISE_EN_ETAT").structure?.id, ILES.atelier.structureRuine);
    assert.equal(plan.structure?.at.y, ILES.hauteur - ILES.atelier.solStructure);
    assert.ok(plan.box.from.y <= plan.structure!.at.y, "la zone chargée descend jusqu'au bas du préfabriqué");
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
    let ready = false, count = 0, cargoCount = 0, fixtureCount = 0;
    const removed: string[] = [];
    const working: boolean[] = [];
    const pieces: Array<{ type: string; yaw: number }> = [];
    const world: WorkshopWorld = {
      sweep: () => 0,
      machine: () => ready,
      loaded: () => true,
      cargo(previous) { return previous ?? `cargo-${++cargoCount}`; },
      worker(previous, _company, _at, _facing, active) { working.push(active); return previous ?? `worker-${++count}`; },
      fixture(previous, _company, type, _at, yaw) { pieces.push({ type, yaw }); return previous ?? `machine-${++fixtureCount}`; },
      effect: () => {},
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
    // Les ailes du moulin et les pilons apparaissent avec le salarié, une seule fois chacun, tournés comme iles.json le dit.
    const enMarche = ILES.atelier.machinerie.filter((m) => m.proprietes.marche !== undefined);
    assert.equal(fixtureCount, enMarche.length);
    assert.deepEqual(pieces.slice(0, enMarche.length), enMarche.map((m) => ({ type: m.entite, yaw: m.lacet })));
    workshop.reap(new Set());
    const stones = Array.from({ length: ILES.atelier.transport.pierres }, (_, i) => `cargo-${i + 1}`);
    assert.deepEqual(removed, ["worker-1", ...stones, ...enMarche.map((_, i) => `machine-${i + 1}`)]);
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
      sweep: () => 0,
      machine: () => true, loaded: () => loaded,
      worker: (old) => old ?? "worker",
      fixture: (old, _company, type) => old ?? type,
      effect: () => {},
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
    assert.ok(Math.abs(Math.hypot(moved.at.x - first.at.x, moved.at.y - first.at.y, moved.at.z - first.at.z) - ILES.atelier.transport.vitesse) < 0.001);
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
    assert.equal(new Set(removed).size, ILES.atelier.transport.pierres + 1 + new Set(ILES.atelier.machinerie.filter((m) => m.proprietes.marche !== undefined).map((m) => m.entite)).size);
  });

  it("joue les sons des machines et la fumée du poêle à leur période, seulement quand la Carrière tourne", () => {
    let now = 0;
    const joues: string[] = [];
    const world: WorkshopWorld = {
      sweep: () => 0,
      machine: () => true, loaded: () => true, worker: (old) => old ?? "worker", fixture: (old, _c, type) => old ?? type,
      cargo: (old) => old ?? "pierre", remove: () => {},
      // Seule l'ambiance compte ici : les arrivées au coffre ont leur propre test.
      effect: (kind, id) => { if (ILES.atelier.ambiance.some((effet) => effet.id === id)) joues.push(`${kind}:${id}`); },
    };
    const workshop = createWorkshop(ILES, world, { now: () => now });
    const state = companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] });
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(joues.length, ILES.atelier.ambiance.length, "chaque effet part une fois au démarrage");
    const plusCourte = Math.min(...ILES.atelier.ambiance.map((effet) => effet.periode));
    now = plusCourte * 1000 - 1; workshop.tick();
    assert.equal(joues.length, ILES.atelier.ambiance.length, "rien avant la fin de la période");
    now = Math.max(...ILES.atelier.ambiance.map((effet) => effet.periode)) * 1000; workshop.tick();
    assert.equal(joues.length, 2 * ILES.atelier.ambiance.length, "chaque effet repart à sa période");
    workshop.sync({ ...state, factories: [factory({ factoryId: "carriere", ratePerHour: 0, stoppedNoPower: true })] }, { x: 0, z: 0 });
    now = 600_000; workshop.tick();
    assert.equal(joues.length, 2 * ILES.atelier.ambiance.length, "à l'arrêt, plus de bruit ni de fumée");
  });

  it("en ruine, pose les machines cassées sans salarié ni pierres, puis les remet en marche au même endroit", () => {
    const pieces: Array<{ id: string; type: string; properties: Readonly<Record<string, boolean | string>> }> = [];
    const checked: string[] = [];
    const removed: string[] = [];
    let created = 0, workers = 0, stones = 0;
    const world: WorkshopWorld = {
      sweep: () => 0,
      machine(_at, type) { checked.push(type); return true; },
      loaded: () => true,
      worker: (old) => { workers += 1; return old ?? "worker"; },
      cargo: (old) => { stones += 1; return old ?? `pierre-${stones}`; },
      fixture(previous, _company, type, _at, _yaw, properties) { const id = previous ?? `piece-${++created}`; pieces.push({ id, type, properties }); return id; },
      effect: () => {},
      remove: (id) => { removed.push(id); },
    };
    const workshop = createWorkshop(ILES, world, { now: () => 0 });
    workshop.sync(companyState({ factories: [factory({ factoryId: "carriere", state: "EN_RUINE", ratePerHour: 0 })] }), { x: 0, z: 0 });
    const enRuine = ILES.atelier.machinerie.filter((piece) => piece.proprietes.ruine !== undefined);
    assert.ok(enRuine.length >= 2, "les ailes et les pilons sont aussi dans la ruine");
    assert.deepEqual(pieces.map((p) => [p.type, p.properties]), enRuine.map((piece) => [piece.entite, piece.proprietes.ruine]));
    assert.deepEqual(checked, ILES.atelier.machines.filter((m) => m.enRuine === true).map((m) => m.bloc), "seuls les blocs restés dans la ruine sont attendus");
    assert.equal(workers + stones, 0, "ni salarié ni pierres sur la ruine");
    pieces.length = 0;
    workshop.sync(companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] }), { x: 0, z: 0 });
    const marche = ILES.atelier.machinerie.filter((piece) => piece.proprietes.marche !== undefined);
    assert.deepEqual(pieces.map((p) => [p.type, p.properties]), marche.map((piece) => [piece.entite, piece.proprietes.marche]));
    const enRuineIds = new Map(ILES.atelier.machinerie.map((piece, i) => [i, piece.proprietes.ruine === undefined ? undefined : `piece-${enRuine.indexOf(piece) + 1}`]));
    const enMarche = ILES.atelier.machinerie.map((piece, i) => [i, piece] as const).filter(([, piece]) => piece.proprietes.marche !== undefined);
    for (const [k, [i]] of enMarche.entries()) {
      if (enRuineIds.get(i) !== undefined) assert.equal(pieces[k]!.id, enRuineIds.get(i), "les ailes et les pilons sont réutilisés");
    }
    assert.ok(pieces.some((p) => p.type === "silooo:salarie" && p.properties["silooo:geste"] === "pelleter"), "le second salarié pellette au bocard");
    assert.equal(pieces.filter((p) => p.type === "silooo:wagonnet").length, 2, "deux wagonnets chargés sur les rails");
    const seulementRuine = ILES.atelier.machinerie.flatMap((piece, i) => piece.proprietes.marche === undefined ? [enRuineIds.get(i)] : []);
    assert.deepEqual(removed, seulementRuine, "le wagonnet renversé part avec la ruine");
  });

  it("nettoie une fois les restes des versions précédentes autour de l'île, sans toucher à ce qu'il suit", () => {
    const sweeps: Array<{ company: number; radius: number; keep: string[] }> = [];
    const world: WorkshopWorld = {
      machine: () => true, loaded: () => true, worker: (old) => old ?? "ouvrier", fixture: (old, _c, type) => old ?? type,
      cargo: (old) => old ?? "pierre", remove: () => {}, effect: () => {},
      sweep(company, _around, radius, keep) { sweeps.push({ company, radius, keep: [...keep].sort() }); return 1; },
    };
    const workshop = createWorkshop(ILES, world, { now: () => 0 });
    const state = companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] });
    workshop.sync(state, { x: 0, z: 0 });
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(sweeps.length, 1, "une seule fois par démarrage");
    assert.ok(sweeps[0]!.radius > ILES.atelier.ancienneEmprise.demiCote, "jusqu'à l'ancienne emprise, où l'ouvrier de la 0.1.4 est resté");
    assert.ok(sweeps[0]!.keep.includes("ouvrier") && sweeps[0]!.keep.includes("pierre"));
    for (const piece of ILES.atelier.machinerie) if (piece.proprietes.marche !== undefined) assert.ok(sweeps[0]!.keep.includes(piece.entite));
    workshop.reap(new Set());
    workshop.sync(state, { x: 0, z: 0 });
    assert.equal(sweeps.length, 2, "de nouveau au retour du joueur");
  });

  it("vide l'emprise de l'ancienne Carrière avant de poser la nouvelle", () => {
    const definition = usineIle(ILES, "carriere")!;
    const plan = planIsland(ILES, definition, { x: 0, z: 0 }, "ACTIVE");
    const old = ILES.atelier.ancienneEmprise;
    assert.ok(plan.clear !== undefined && plan.clear.length === old.bas + old.haut + 1, "une couche par fill : jamais plus de 32 768 blocs");
    for (const op of plan.clear) {
      assert.equal(op.kind, "fill");
      if (op.kind !== "fill") continue;
      assert.equal(op.block, ILES.materiaux.vide);
      assert.equal(op.to.x - op.from.x, 2 * old.demiCote);
      for (const p of [op.from, op.to]) for (const axis of ["x", "y", "z"] as const) assert.ok(p[axis] >= plan.box.from[axis] && p[axis] <= plan.box.to[axis], axis);
    }
    assert.equal(Math.min(...plan.clear.map((op) => (op.kind === "fill" ? op.from.y : 0))), ILES.hauteur - old.bas);
    assert.equal(Math.max(...plan.clear.map((op) => (op.kind === "fill" ? op.to.y : 0))), ILES.hauteur + old.haut);
    assert.equal(planIsland(ILES, usineIle(ILES, "scierie")!, { x: 0, z: 0 }, "EN_RUINE").clear, undefined, "les autres îles n'ont rien à vider");
  });

  it("mène les pierres des pilons au coffre de vente, et chaque arrivée fait son bruit dans le coffre", () => {
    let now = 0;
    const effects: Array<{ id: string; at: { x: number; y: number; z: number } }> = [];
    const positions = new Map<string, Array<{ x: number; y: number; z: number }>>();
    const world: WorkshopWorld = {
      sweep: () => 0,
      machine: () => true, loaded: () => true, worker: (old) => old ?? "worker", fixture: (old, _c, type) => old ?? type, remove: () => {},
      cargo(previous, _company, at) { const id = previous ?? `pierre-${positions.size + 1}`; positions.set(id, [...(positions.get(id) ?? []), at]); return id; },
      effect: (_kind, id, at) => { effects.push({ id, at }); },
    };
    const definition = usineIle(ILES, "carriere")!;
    const frame = arrivalOf({ x: 0, z: 0 }, ILES)!.frame;
    const route = ILES.atelier.transport.trajet.map((point) => viewPoint(frame, point, ILES));
    const coffre = ILES.atelier.machines.find((m) => m.role === "stock")!;
    const pilons = ILES.atelier.machinerie.find((m) => m.entite === "silooo:pilons")!;
    const coffreBloc = planIsland(ILES, definition, { x: 0, z: 0 }, "ACTIVE").state.find((op) => op.kind === "set" && op.block === coffre.bloc);
    assert.ok(coffreBloc?.kind === "set");
    assert.deepEqual([route.at(-1)!.x, route.at(-1)!.z], [coffreBloc.at.x + 0.5, coffreBloc.at.z + 0.5], "le trajet finit au-dessus du coffre");
    assert.equal(route.at(-1)!.y, coffreBloc.at.y + 1, "posée sur le coffre");
    assert.deepEqual(route[0], viewPoint(frame, pilons.position, ILES), "il part sous les pilons");
    const workshop = createWorkshop(ILES, world, { now: () => now });
    const state = companyState({ factories: [factory({ factoryId: "carriere", ratePerHour: 2400 })] });
    workshop.sync(state, { x: 0, z: 0 });
    const ambiance = effects.length;
    let total = 0;
    for (let i = 1; i < route.length; i += 1) total += Math.hypot(route[i]!.x - route[i - 1]!.x, route[i]!.y - route[i - 1]!.y, route[i]!.z - route[i - 1]!.z);
    const arrivee = ILES.atelier.transport.arrivee.map((effet) => effet.id);
    const arrivals = () => effects.slice(ambiance).filter((effet) => arrivee.includes(effet.id));
    // Une pierre arrive tous les total / pierres blocs : on avance d'un dixième de bloc à la fois.
    const step = 100 / ILES.atelier.transport.vitesse;
    const tours = Math.floor(total / (step / 1000) / ILES.atelier.transport.vitesse);
    for (let i = 0; i < tours; i += 1) { now += step; workshop.tick(); }
    assert.equal(arrivals().length, arrivee.length * (ILES.atelier.transport.pierres - 1), "une arrivée par pierre au premier tour, sauf celle qui part du coffre");
    for (const effet of arrivals()) assert.deepEqual(effet.at, route.at(-1));
    // Chaque position d'une pierre est sur le trajet : jamais à travers la toile ni le toit de l'étal.
    for (const points of positions.values()) for (const at of points) {
      const close = route.slice(1).some((b, i) => {
        const a = route[i]!;
        const ab = { x: b.x - a.x, y: b.y - a.y, z: b.z - a.z };
        const len2 = ab.x ** 2 + ab.y ** 2 + ab.z ** 2;
        const k = Math.max(0, Math.min(1, ((at.x - a.x) * ab.x + (at.y - a.y) * ab.y + (at.z - a.z) * ab.z) / len2));
        return Math.hypot(a.x + ab.x * k - at.x, a.y + ab.y * k - at.y, a.z + ab.z * k - at.z) < 1e-6;
      });
      assert.ok(close, `pierre hors du trajet en ${at.x},${at.y},${at.z}`);
    }
    workshop.sync({ ...state, factories: [factory({ factoryId: "carriere", ratePerHour: 0, stoppedNoPower: true })] }, { x: 0, z: 0 });
    const before = arrivals().length;
    for (let i = 0; i < tours; i += 1) { now += step; workshop.tick(); }
    assert.equal(arrivals().length, before, "à l'arrêt, rien n'arrive au coffre");
  });
});
