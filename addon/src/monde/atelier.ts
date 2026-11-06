// Présentation de l'atelier d'après CompanyState : aucune monnaie, aucun prix, aucune embauche implicite.
import type { CompanyState } from "../protocol";
import type { IlesDefinitions } from "../generated/definitions";
import { frameOfIsland, viewPoint, type Pos, type Xz } from "./geometrie";
import { workshopBlock } from "./carriere";

export interface WorkshopWorld {
  machine(at: Pos, type: string, working: boolean): boolean;
  worker(previous: string | undefined, companyId: number, at: Pos, facing: Pos, working: boolean): string;
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

export function createWorkshop(defs: IlesDefinitions, world: WorkshopWorld) {
  const workers = new Map<number, string>();
  return {
    sync(state: CompanyState, origin: Xz): void {
      const definition = defs.usines.find((entry) => entry.id === defs.apparition.usine);
      const factory = state.factories.find((entry) => entry.factoryId === defs.apparition.usine);
      if (definition === undefined || factory?.state !== "ACTIVE") {
        const previous = workers.get(state.companyId);
        if (previous !== undefined) world.remove(previous);
        workers.delete(state.companyId);
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
    },
    reap(onlineCompanies: ReadonlySet<number>): void {
      for (const [company, actor] of workers) if (!onlineCompanies.has(company)) {
        world.remove(actor);
        workers.delete(company);
      }
    },
  };
}
