// Présentation de l'atelier d'après CompanyState : aucune monnaie, aucun prix, aucune embauche implicite.
import type { CompanyState } from "../protocol";
import type { IlesDefinitions } from "../generated/definitions";
import { frameOfIsland, viewPoint, type Frame, type Pos, type Xz } from "./geometrie";
import type { Clock } from "../runtime";
import { workshopBlock } from "./carriere";

export interface WorkshopWorld {
  machine(at: Pos, type: string, working: boolean): boolean;
  worker(previous: string | undefined, companyId: number, at: Pos, facing: Pos, working: boolean): string;
  /** Une machine animée par le pack (ailes du moulin, pilons) : apparaît une fois, tournée de `yaw` degrés. */
  fixture(previous: string | undefined, companyId: number, type: string, at: Pos, yaw: number): string;
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

export function createWorkshop(defs: IlesDefinitions, world: WorkshopWorld, clock: Clock) {
  const workers = new Map<number, string>();
  const fixtures = new Map<number, string[]>();
  function removeFixtures(company: number): void {
    for (const id of fixtures.get(company) ?? []) world.remove(id);
    fixtures.delete(company);
  }
  const cargo = new Map<number, { ids: string[]; frame: Frame; working: boolean; elapsed: number; last: number }>();
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
    const belt = defs.atelier.machines.filter((machine) => machine.role === "convoyeur");
    const first = belt[0], last = belt.at(-1);
    if (first === undefined || last === undefined) return;
    const start = workshopBlock(track.frame, defs.hauteur, first.position), end = workshopBlock(track.frame, defs.hauteur, last.position);
    if (!world.loaded(start) || !world.loaded(end)) return;
    const { longueur, vitesse, pierres } = defs.atelier.transport;
    for (let i = 0; i < pierres; i += 1) {
      const ratio = ((track.elapsed * vitesse + i * longueur / pierres) % longueur) / longueur;
      const at = { x: start.x + 0.5 + (end.x - start.x) * ratio, y: start.y + 0.5, z: start.z + 0.5 + (end.z - start.z) * ratio };
      track.ids[i] = world.cargo(track.ids[i], company, at, track.working, i === 0 && track.ids.length === 0);
    }
  }
  return {
    sync(state: CompanyState, origin: Xz): void {
      const definition = defs.usines.find((entry) => entry.id === defs.apparition.usine);
      const factory = state.factories.find((entry) => entry.factoryId === defs.apparition.usine);
      if (definition === undefined || factory?.state !== "ACTIVE") {
        const previous = workers.get(state.companyId);
        if (previous !== undefined) world.remove(previous);
        workers.delete(state.companyId);
        removeCargo(state.companyId);
        removeFixtures(state.companyId);
        return;
      }
      const frame = frameOfIsland(origin, definition, defs);
      const working = workshopRunning(state, definition.id);
      let ready = true;
      for (const machine of defs.atelier.machines) {
        ready = world.machine(workshopBlock(frame, defs.hauteur, machine.position), machine.bloc, working) && ready;
      }
      if (!ready) return;
      const actor = world.worker(workers.get(state.companyId), state.companyId,
        viewPoint(frame, defs.atelier.salarie.position, defs), viewPoint(frame, defs.atelier.salarie.regard, defs), working);
      workers.set(state.companyId, actor);
      const before = fixtures.get(state.companyId) ?? [];
      fixtures.set(state.companyId, defs.atelier.machinerie.map((piece, index) =>
        world.fixture(before[index], state.companyId, piece.entite, viewPoint(frame, piece.position, defs), piece.lacet)));
      const previous = cargo.get(state.companyId);
      if (previous === undefined) cargo.set(state.companyId, { ids: [], frame, working, elapsed: 0, last: clock.now() });
      else { updateCargo(state.companyId); previous.working = working; previous.frame = frame; }
      updateCargo(state.companyId);
    },
    tick(): void { for (const company of cargo.keys()) updateCargo(company); },
    reap(onlineCompanies: ReadonlySet<number>): void {
      for (const [company, actor] of workers) if (!onlineCompanies.has(company)) {
        world.remove(actor);
        workers.delete(company);
        removeCargo(company);
        removeFixtures(company);
      }
    },
  };
}
