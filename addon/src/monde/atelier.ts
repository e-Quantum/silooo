// Présentation de l'atelier d'après CompanyState : aucune monnaie, aucun prix, aucune embauche implicite.
import type { CompanyState } from "../protocol";
import type { IlesDefinitions } from "../generated/definitions";
import { frameOfIsland, viewPoint, type Frame, type Pos, type Xz } from "./geometrie";
import type { Clock } from "../runtime";
import { workshopBlock } from "./carriere";

export interface WorkshopWorld {
  machine(at: Pos, type: string, working: boolean): boolean;
  worker(previous: string | undefined, companyId: number, at: Pos, facing: Pos, working: boolean): string;
  /**
   * Une entité animée par le pack (ailes du moulin, pilons, salarié de décor) : apparaît une fois, tournée de `yaw`
   * degrés, et ses propriétés d'entité prennent les valeurs données.
   */
  fixture(previous: string | undefined, companyId: number, type: string, at: Pos, yaw: number, properties: Readonly<Record<string, boolean | string>>): string;
  /** Un son ou une particule du pack, joué une fois au point donné. */
  effect(kind: "son" | "particule", id: string, at: Pos): void;
  loaded(at: Pos): boolean;
  cargo(previous: string | undefined, companyId: number, at: Pos, working: boolean, reset?: boolean): string;
  remove(id: string): void;
}

/** Une marque de l'ancien décor ne prouve pas que le nouvel atelier est construit. */
export function currentWorkshopMarks(marks: Readonly<Record<string, string>>, savedRevision: unknown, defs: IlesDefinitions): Record<string, string> {
  const current = { ...marks };
  if (savedRevision !== defs.atelier.revision) delete current[defs.apparition.usine];
  return current;
}

export function workshopRunning(state: CompanyState, factoryId: string): boolean {
  const factory = state.factories.find((entry) => entry.factoryId === factoryId);
  return factory?.state === "ACTIVE" && factory.ratePerHour > 0 && factory.stoppedNoPower !== true;
}

/** L'état d'une Carrière pour ses entités de décor (iles.json, atelier.machinerie[].proprietes). */
export type WorkshopLook = "marche" | "arret" | "ruine";

/** Les coins du trajet des pierres, dans le monde. */
function routeOf(frame: Frame, defs: IlesDefinitions): Pos[] {
  return defs.atelier.transport.trajet.map((point) => viewPoint(frame, point, defs));
}

function lengthOf(points: readonly Pos[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i += 1) {
    total += Math.hypot(points[i]!.x - points[i - 1]!.x, points[i]!.y - points[i - 1]!.y, points[i]!.z - points[i - 1]!.z);
  }
  return total;
}

/** La longueur du trajet des pierres, en blocs : aucune pierre n'en sort, à un bloc près. */
export function transportReach(defs: IlesDefinitions): number {
  return lengthOf(defs.atelier.transport.trajet.map((point) => ({ x: point.avant, y: point.haut, z: point.droite }))) + 1;
}

/** Le point à `distance` blocs du départ, en suivant les coins. */
function along(points: readonly Pos[], distance: number): Pos {
  let left = distance;
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1]!, b = points[i]!;
    const step = Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z);
    if (left <= step && step > 0) {
      const k = left / step;
      return { x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k, z: a.z + (b.z - a.z) * k };
    }
    left -= step;
  }
  return points.at(-1)!;
}

export function createWorkshop(defs: IlesDefinitions, world: WorkshopWorld, clock: Clock) {
  const workers = new Map<number, string>();
  /** Par entreprise : l'entité de chaque pièce de `atelier.machinerie`, à son rang, absente dans les états qui ne la citent pas. */
  const fixtures = new Map<number, Array<string | undefined>>();
  /** Par entreprise : le repère de sa Carrière tant qu'elle tourne, et le dernier passage de chaque effet d'ambiance. */
  const ambiance = new Map<number, { frame: Frame; last: number[] }>();
  function playAmbiance(company: number): void {
    const track = ambiance.get(company);
    if (track === undefined) return;
    const now = clock.now();
    defs.atelier.ambiance.forEach((effet, index) => {
      if (now - (track.last[index] ?? -Infinity) < effet.periode * 1000) return;
      const at = viewPoint(track.frame, effet.position, defs);
      if (!world.loaded(at)) return;
      track.last[index] = now;
      world.effect(effet.effet, effet.id, at);
    });
  }
  function placeFixtures(company: number, frame: Frame, look: WorkshopLook): void {
    const before = fixtures.get(company) ?? [];
    fixtures.set(company, defs.atelier.machinerie.map((piece, index) => {
      const properties = piece.proprietes[look];
      const previous = before[index];
      if (properties === undefined) {
        if (previous !== undefined) world.remove(previous);
        return undefined;
      }
      return world.fixture(previous, company, piece.entite, viewPoint(frame, piece.position, defs), piece.lacet, properties);
    }));
  }
  function removeFixtures(company: number): void {
    for (const id of fixtures.get(company) ?? []) if (id !== undefined) world.remove(id);
    fixtures.delete(company);
  }
  const cargo = new Map<number, { ids: string[]; laps: number[]; frame: Frame; working: boolean; elapsed: number; last: number }>();
  function removeCargo(company: number): void {
    for (const id of cargo.get(company)?.ids ?? []) world.remove(id);
    cargo.delete(company);
  }
  function updateCargo(company: number): void {
    const track = cargo.get(company);
    if (track === undefined) return;
    const now = clock.now();
    if (track.working) track.elapsed += Math.max(0, now - track.last) / 1000;
    track.last = now;
    const route = routeOf(track.frame, defs);
    const end = route.at(-1)!;
    if (!world.loaded(route[0]!) || !world.loaded(end)) return;
    const total = lengthOf(route);
    if (total <= 0) return;
    const { vitesse, pierres, arrivee } = defs.atelier.transport;
    let arrived = false;
    for (let i = 0; i < pierres; i += 1) {
      const travelled = track.elapsed * vitesse + i * total / pierres;
      const lap = Math.floor(travelled / total);
      if (track.laps[i] !== undefined && lap > track.laps[i]!) arrived = true;
      track.laps[i] = lap;
      track.ids[i] = world.cargo(track.ids[i], company, along(route, travelled - lap * total), track.working, i === 0 && track.ids.length === 0);
    }
    // Une pierre tombe dans le coffre de vente : miettes et bruit du coffre (iles.json, transport.arrivee).
    if (arrived && track.working) for (const effet of arrivee) world.effect(effet.effet, effet.id, end);
  }
  function forget(company: number): void {
    const previous = workers.get(company);
    if (previous !== undefined) world.remove(previous);
    workers.delete(company);
    removeCargo(company);
    removeFixtures(company);
    ambiance.delete(company);
  }
  return {
    sync(state: CompanyState, origin: Xz): void {
      const definition = defs.usines.find((entry) => entry.id === defs.apparition.usine);
      if (definition === undefined) { forget(state.companyId); return; }
      const factory = state.factories.find((entry) => entry.factoryId === defs.apparition.usine);
      const frame = frameOfIsland(origin, definition, defs);
      if (factory?.state !== "ACTIVE") {
        // La ruine : ni salarié, ni pierres, ni bruit ; le moulin garde une aile, un pilon est tombé (pack, silooo:ruine).
        const previous = workers.get(state.companyId);
        if (previous !== undefined) world.remove(previous);
        workers.delete(state.companyId);
        removeCargo(state.companyId);
        ambiance.delete(state.companyId);
        let standing = true;
        for (const machine of defs.atelier.machines) {
          if (machine.enRuine === true) standing = world.machine(workshopBlock(frame, defs.hauteur, machine.position), machine.bloc, false) && standing;
        }
        if (standing) placeFixtures(state.companyId, frame, "ruine");
        return;
      }
      const working = workshopRunning(state, definition.id);
      let ready = true;
      for (const machine of defs.atelier.machines) {
        ready = world.machine(workshopBlock(frame, defs.hauteur, machine.position), machine.bloc, working) && ready;
      }
      if (!ready) return;
      const actor = world.worker(workers.get(state.companyId), state.companyId,
        viewPoint(frame, defs.atelier.salarie.position, defs), viewPoint(frame, defs.atelier.salarie.regard, defs), working);
      workers.set(state.companyId, actor);
      if (working) {
        if (!ambiance.has(state.companyId)) ambiance.set(state.companyId, { frame, last: [] });
        else ambiance.get(state.companyId)!.frame = frame;
        playAmbiance(state.companyId);
      } else ambiance.delete(state.companyId);
      placeFixtures(state.companyId, frame, working ? "marche" : "arret");
      const previous = cargo.get(state.companyId);
      if (previous === undefined) cargo.set(state.companyId, { ids: [], laps: [], frame, working, elapsed: 0, last: clock.now() });
      else { updateCargo(state.companyId); previous.working = working; previous.frame = frame; }
      updateCargo(state.companyId);
    },
    tick(): void {
      for (const company of cargo.keys()) updateCargo(company);
      for (const company of ambiance.keys()) playAmbiance(company);
    },
    reap(onlineCompanies: ReadonlySet<number>): void {
      for (const company of new Set([...workers.keys(), ...fixtures.keys(), ...cargo.keys()])) {
        if (!onlineCompanies.has(company)) forget(company);
      }
    },
  };
}
