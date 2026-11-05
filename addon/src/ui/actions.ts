// Les trois actions que les écrans peuvent demander au service : encaisser, monter une usine, avancer sa remise
// en état. Chacune envoie un `requestId` neuf (UUID) et l'identifiant du joueur (`actor`), range la réponse dans
// le ViewStore, et rend un résultat que le menu transforme en bulle d'Oooscar.
//
// Sur `CONFLIT_REESSAYER` (le verrou optimiste du service a bougé), on recharge l'état avec getCompany puis on
// rejoue l'action UNE fois, avec le même `requestId` : le service ne garde pas ce refus, et le même identifiant
// empêche un double paiement si la première tentative avait finalement abouti. Un second conflit est rendu tel quel.
//
// Un seul appel à la fois par joueur : un doigt qui appuie deux fois ne paie pas deux fois.

import type { CoreApi } from "../api";
import { describeFailure, type ApiResult } from "../http";
import type { ActionBase, BuyAmount, Xuid } from "../protocol";
import type { Logger } from "../runtime";
import type { ViewStore, ViewUpdate } from "./etat";

export const CONFLICT_REASON = "CONFLIT_REESSAYER";

/** Ce qui s'est passé, dans les mots du jeu. Le menu en tire la bulle. */
export type ActionOutcome =
  | { readonly kind: "collected"; readonly collected: number; readonly offlineHours: number }
  | { readonly kind: "levelsBought" }
  | { readonly kind: "stepDone" }
  | { readonly kind: "refused"; readonly reason: string; readonly missing?: number }
  | { readonly kind: "failed"; readonly failure: "network" | "error" | "unavailable"; readonly reason: string }
  /** Une action du même joueur est encore en cours : celle-ci est ignorée. */
  | { readonly kind: "busy" };

export interface Actions {
  collect(xuid: Xuid): Promise<ActionOutcome>;
  buyLevels(xuid: Xuid, factoryId: string, amount: BuyAmount): Promise<ActionOutcome>;
  restoreStep(xuid: Xuid, factoryId: string, step: number): Promise<ActionOutcome>;
}

export interface ActionsDeps {
  readonly api: Pick<CoreApi, "getCompany" | "collect" | "buyLevels" | "restoreStep">;
  readonly views: ViewStore;
  /** Un UUID neuf à chaque appel (runtime.ts, createRequestIdGenerator). */
  readonly newRequestId: () => string;
  readonly log: Logger;
}

/** Une action prête à partir : tout ce qu'il faut pour l'envoyer, et pour lire ce qu'elle rapporte. */
interface Plan<T> {
  readonly label: string;
  readonly send: (companyId: number, action: ActionBase) => Promise<ApiResult<T>>;
  /** Range le corps de la réponse dans le ViewStore et dit ce qui s'est passé. */
  readonly accept: (body: T) => { readonly update: ViewUpdate; readonly outcome: ActionOutcome };
}

export function createActions(deps: ActionsDeps): Actions {
  const { api, views, newRequestId, log } = deps;
  const running = new Set<Xuid>();

  async function run<T>(xuid: Xuid, plan: Plan<T>): Promise<ActionOutcome> {
    if (running.has(xuid)) return { kind: "busy" };
    const view = views.get(xuid);
    if (view === undefined) return { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" };
    running.add(xuid);
    try {
      const companyId = view.state.companyId;
      const action: ActionBase = { requestId: newRequestId(), actor: xuid };
      let result = await plan.send(companyId, action);

      if (result.kind === "refused" && result.reason === CONFLICT_REASON) {
        log.info(`${plan.label} : conflit, état rechargé puis action rejouée une fois`);
        const fresh = await api.getCompany(companyId);
        if (fresh.kind !== "ok") return failure(plan.label, fresh);
        views.apply(xuid, { state: fresh.body });
        result = await plan.send(companyId, action);
      }

      if (result.kind === "ok") {
        const { update, outcome } = plan.accept(result.body);
        views.apply(xuid, update);
        return outcome;
      }
      if (result.kind === "refused") {
        return result.missing === undefined
          ? { kind: "refused", reason: result.reason }
          : { kind: "refused", reason: result.reason, missing: result.missing };
      }
      return failure(plan.label, result);
    } finally {
      running.delete(xuid);
    }
  }

  /**
   * Un échec technique : journalisé, rendu sans détail. Les refus du jeu des actions ne passent pas ici ; seul un
   * refus de getCompany (le contrat n'en prévoit pas) y arrive, et il est traité comme une erreur du service.
   */
  function failure(label: string, result: Exclude<ApiResult<unknown>, { kind: "ok" }>): ActionOutcome {
    log.warn(`${label} : ${describeFailure(result)}`);
    return result.kind === "network"
      ? { kind: "failed", failure: "network", reason: "RESEAU" }
      : { kind: "failed", failure: "error", reason: result.reason };
  }

  return {
    collect: (xuid) =>
      run(xuid, {
        label: "collect",
        send: (companyId, action) => api.collect(companyId, action),
        accept: (body) => ({
          update: { state: body.state },
          outcome: { kind: "collected", collected: body.collected, offlineHours: body.offlineHours },
        }),
      }),
    buyLevels: (xuid, factoryId, amount) =>
      run(xuid, {
        label: "buyLevels",
        send: (companyId, action) => api.buyLevels(companyId, factoryId, action, amount),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "levelsBought" } }),
      }),
    restoreStep: (xuid, factoryId, step) =>
      run(xuid, {
        label: "restoreStep",
        send: (companyId, action) => api.restoreStep(companyId, factoryId, action, step),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "stepDone" } }),
      }),
  };
}
