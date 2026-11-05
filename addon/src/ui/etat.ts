// Ce que les écrans savent de l'entreprise d'un joueur, et si le service répond. Deux petites pièces :
//
// - `ViewStore` garde, par joueur, la dernière réponse du service (état, soldes) et l'instant où elle est arrivée,
//   donc le point de départ du compteur. Il part de la réponse de `join` gardée par state.ts, puis chaque réponse
//   d'une action la remplace. Une réponse plus ancienne (version plus petite) est ignorée.
// - `LinkState` note si le dernier appel au service a abouti. Le HUD n'affiche que « hors ligne » quand il ne répond pas.
//
// Ce fichier ne décide de rien : il range ce que le service a dit.

import type { CoreApi } from "../api";
import type { ApiResult } from "../http";
import type { Balances, CompanyState, JoinResponse, Xuid } from "../protocol";
import type { Clock } from "../runtime";
import type { PlayerStore } from "../state";
import { anchorOf, type CounterAnchor } from "./compteur";

// ---------------------------------------------------------------- l'état d'une entreprise vu par les écrans

export interface CompanyView {
  readonly state: CompanyState;
  readonly balances: Balances;
  /** Le point de départ du compteur de Donar : le `donar` de cette réponse, à l'instant où elle est arrivée. */
  readonly anchor: CounterAnchor;
}

export interface ViewUpdate {
  readonly state: CompanyState;
  /** Absent quand la réponse n'a pas de soldes (getCompany) : les derniers soldes connus restent. */
  readonly balances?: Balances;
}

export interface ViewStore {
  /** La vue du joueur, ou undefined s'il n'est pas arrivé (pas de réponse de `join`) ou s'il est parti. */
  get(xuid: Xuid): CompanyView | undefined;
  /**
   * Range une réponse du service. Rend faux si elle est plus ancienne que celle qu'on a, si elle parle d'une autre
   * entreprise que celle de la vue, ou si le joueur n'est pas là.
   */
  apply(xuid: Xuid, update: ViewUpdate): boolean;
  /**
   * Le joueur est entré dans une autre entreprise (`acceptInvite`) : la vue prend l'état de cette entreprise, sans
   * comparer les versions (deux entreprises n'ont pas la même suite de versions). Rend faux si le joueur n'est pas là.
   */
  switchCompany(xuid: Xuid, update: ViewUpdate): boolean;
}

interface Entry {
  /** La réponse de `join` d'où cette vue est partie : une nouvelle réponse de `join` repart de zéro. */
  readonly source: JoinResponse;
  readonly view: CompanyView;
}

export interface ViewStoreDeps {
  readonly players: Pick<PlayerStore, "get">;
  readonly clock: Clock;
}

function makeView(state: CompanyState, balances: Balances, atMs: number): CompanyView {
  return { state, balances, anchor: anchorOf(state, atMs) };
}

export function createViewStore(deps: ViewStoreDeps): ViewStore {
  const { players, clock } = deps;
  const entries = new Map<Xuid, Entry>();

  function entryOf(xuid: Xuid): Entry | undefined {
    const joined = players.get(xuid);
    if (joined === undefined) {
      entries.delete(xuid);
      return undefined;
    }
    const known = entries.get(xuid);
    if (known !== undefined && known.source === joined.join) return known;
    const entry: Entry = {
      source: joined.join,
      view: makeView(joined.join.state, joined.join.balances, clock.now()),
    };
    entries.set(xuid, entry);
    return entry;
  }

  return {
    get: (xuid) => entryOf(xuid)?.view,
    apply(xuid, update) {
      const entry = entryOf(xuid);
      if (entry === undefined) return false;
      // Une réponse sur l'ancienne entreprise, arrivée après un changement d'entreprise, n'est plus celle du joueur.
      if (update.state.companyId !== entry.view.state.companyId) return false;
      if (update.state.version < entry.view.state.version) return false;
      const view = makeView(update.state, update.balances ?? entry.view.balances, clock.now());
      entries.set(xuid, { source: entry.source, view });
      return true;
    },
    switchCompany(xuid, update) {
      const entry = entryOf(xuid);
      if (entry === undefined) return false;
      const view = makeView(update.state, update.balances ?? entry.view.balances, clock.now());
      entries.set(xuid, { source: entry.source, view });
      return true;
    },
  };
}

// ---------------------------------------------------------------- le service répond-il ?

export type LinkStatus = "ok" | "down";

export interface LinkState {
  status(): LinkStatus;
  /** Note le résultat d'un appel au service. */
  note(result: ApiResult<unknown>): void;
}

/**
 * Un appel qui prouve que le service est injoignable ou en panne : réseau coupé, délai dépassé, erreur 5xx.
 * Un 501 (route pas encore écrite), un refus du jeu ou un corps illisible prouvent au contraire qu'il répond.
 * Une erreur locale (statut 0, aucune requête partie) ne dit rien.
 */
function linkVerdict(result: ApiResult<unknown>): LinkStatus | undefined {
  switch (result.kind) {
    case "ok":
    case "refused":
      return "ok";
    case "network":
      return "down";
    case "error":
      if (result.status === 0) return undefined;
      return result.status >= 500 && result.status !== 501 ? "down" : "ok";
  }
}

export function createLinkState(): LinkState {
  let status: LinkStatus = "ok";
  return {
    status: () => status,
    note(result) {
      const verdict = linkVerdict(result);
      if (verdict !== undefined) status = verdict;
    },
  };
}

/**
 * Enveloppe l'API pour que chaque appel, heartbeat compris, nourrisse `LinkState`. L'API rendue a les mêmes
 * fonctions et les mêmes résultats que l'originale.
 */
export function observeApi(api: CoreApi, link: LinkState): CoreApi {
  async function watch<T>(call: Promise<ApiResult<T>>): Promise<ApiResult<T>> {
    const result = await call;
    link.note(result);
    return result;
  }
  return {
    heartbeat: (serverId, onlineXuids) => watch(api.heartbeat(serverId, onlineXuids)),
    join: (xuid, gamertag) => watch(api.join(xuid, gamertag)),
    leave: (xuid) => watch(api.leave(xuid)),
    pollEvents: (after, limit) => watch(api.pollEvents(after, limit)),
    getCompany: (companyId) => watch(api.getCompany(companyId)),
    collect: (companyId, action) => watch(api.collect(companyId, action)),
    buyLevels: (companyId, factoryId, action, amount) => watch(api.buyLevels(companyId, factoryId, action, amount)),
    restoreStep: (companyId, factoryId, action, step) => watch(api.restoreStep(companyId, factoryId, action, step)),
    resolveIncident: (companyId, incidentId, action) => watch(api.resolveIncident(companyId, incidentId, action)),
    getMissions: (xuid) => watch(api.getMissions(xuid)),
    prestigePreview: (companyId) => watch(api.prestigePreview(companyId)),
    prestige: (companyId, action) => watch(api.prestige(companyId, action)),
    getCandidates: (companyId) => watch(api.getCandidates(companyId)),
    hire: (companyId, slot, action, factoryId) => watch(api.hire(companyId, slot, action, factoryId)),
    assignStaff: (companyId, staffId, action, factoryId) => watch(api.assignStaff(companyId, staffId, action, factoryId)),
    fireStaff: (companyId, staffId, action) => watch(api.fireStaff(companyId, staffId, action)),
    invite: (companyId, action, targetXuid) => watch(api.invite(companyId, action, targetXuid)),
    setShares: (companyId, action, shares) => watch(api.setShares(companyId, action, shares)),
    acceptInvite: (companyId, action) => watch(api.acceptInvite(companyId, action)),
    buyZone: (companyId, zoneIndex, action) => watch(api.buyZone(companyId, zoneIndex, action)),
    placeBuilding: (companyId, action, buildingType, x, z, rotation) =>
      watch(api.placeBuilding(companyId, action, buildingType, x, z, rotation)),
    upgradeBuilding: (companyId, buildingId, action) => watch(api.upgradeBuilding(companyId, buildingId, action)),
  };
}
