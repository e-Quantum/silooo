// Les actions que les écrans peuvent demander au service : encaisser, monter une usine, avancer sa remise en état,
// réparer un incident, faire le prestige, embaucher, affecter et renvoyer. Chacune envoie un `requestId` neuf (UUID) et l'identifiant du
// joueur (`actor`), range la réponse dans le ViewStore, et rend un résultat que le menu transforme en bulle d'Oooscar.
//
// Sur `CONFLIT_REESSAYER` (le verrou optimiste du service a bougé), on recharge l'état avec getCompany puis on
// rejoue l'action UNE fois, avec le même `requestId` : le service ne garde pas ce refus, et le même identifiant
// empêche un double paiement si la première tentative avait finalement abouti. Un second conflit est rendu tel quel.
//
// Un seul appel à la fois par joueur : un doigt qui appuie deux fois ne paie pas deux fois.
//
// L'aperçu du prestige et les candidats du jour ne sont pas des actions : des lectures, sans identifiant de requête
// ni verrou, qui ne changent rien chez le service ni dans la vue du joueur.
//
// Après un encaissement réussi, `afterCollect` est appelé : c'est le moment où le service a pu faire avancer la
// mission en cours (ui/missions.ts relit alors les missions). Ce qu'il rend ne change rien au résultat de l'action.
//
// L'entreprise partagée : inviter et fixer les parts passent par l'entreprise du joueur, comme les autres actions.
// Accepter une invitation s'adresse à l'entreprise qui invite, pas à celle du joueur : la réponse est l'état de la
// nouvelle entreprise, et la vue du joueur la prend sans comparer les versions (ViewStore.switchCompany). Sur un
// conflit, il n'y a pas d'état à recharger (celui de l'entreprise du joueur n'y est pour rien) : l'action est
// rejouée une fois, avec le même `requestId`.

import type { CoreApi } from "../api";
import { describeFailure, type ApiResult } from "../http";
import type { ActionBase, BuyAmount, Candidate, PrestigePreview, ShareInput, Xuid } from "../protocol";
import { describeError, type Logger } from "../runtime";
import type { ViewStore, ViewUpdate } from "./etat";

export const CONFLICT_REASON = "CONFLIT_REESSAYER";

/** Ce qui s'est passé, dans les mots du jeu. Le menu en tire la bulle. */
export type ActionOutcome =
  | { readonly kind: "collected"; readonly collected: number; readonly offlineHours: number }
  | { readonly kind: "levelsBought" }
  | { readonly kind: "stepDone" }
  /** Le service a clos l'incident (ou l'avait déjà clos) : la vue du joueur a l'usine à son rythme d'aujourd'hui. La récompense vient de l'événement `INCIDENT`, pas d'ici. */
  | { readonly kind: "incidentResolved" }
  /** Le prestige est fait : la vue du joueur est celle de la course suivante. */
  | { readonly kind: "prestiged" }
  /** Un candidat est embauché : la vue du joueur a son équipe à jour et son Donar débité. */
  | { readonly kind: "hired" }
  | { readonly kind: "assigned" }
  | { readonly kind: "fired" }
  /** L'invitation est partie : la liste des associés ne change qu'à l'acceptation. */
  | { readonly kind: "invited" }
  /** Les parts sont fixées : la vue du joueur a la liste des associés du service. */
  | { readonly kind: "sharesSet" }
  /** L'invitation est acceptée : la vue du joueur est celle de la nouvelle entreprise. */
  | { readonly kind: "joined" }
  | { readonly kind: "refused"; readonly reason: string; readonly missing?: number }
  | { readonly kind: "failed"; readonly failure: "network" | "error" | "unavailable"; readonly reason: string }
  /** Une action du même joueur est encore en cours : celle-ci est ignorée. */
  | { readonly kind: "busy" };

type FailedOutcome = Extract<ActionOutcome, { readonly kind: "failed" }>;

/** Ce que rend la lecture de l'aperçu du prestige : l'aperçu du service, ou l'échec technique. */
export type PreviewOutcome = { readonly kind: "preview"; readonly preview: PrestigePreview } | FailedOutcome;

/** Ce que rend la lecture des candidats du jour : la liste du service, ou l'échec technique. */
export type CandidatesOutcome = { readonly kind: "candidates"; readonly candidates: readonly Candidate[] } | FailedOutcome;

/** Ce que rend la relecture de l'entreprise : la vue du joueur est à jour, ou l'échec technique. */
export type RefreshOutcome = { readonly kind: "refreshed" } | FailedOutcome;

export interface Actions {
  collect(xuid: Xuid): Promise<ActionOutcome>;
  buyLevels(xuid: Xuid, factoryId: string, amount: BuyAmount): Promise<ActionOutcome>;
  restoreStep(xuid: Xuid, factoryId: string, step: number): Promise<ActionOutcome>;
  /**
   * Le mini-jeu de réparation est réussi : demande au service de clore l'incident `incidentId`. Le service ne juge pas le
   * mini-jeu et verse la récompense lui-même ; ici, rien n'est calculé.
   */
  resolveIncident(xuid: Xuid, incidentId: number): Promise<ActionOutcome>;
  /** Le prestige de l'entreprise du joueur. Le service refuse si le joueur n'est pas le PDG ou si le seuil n'est pas atteint. */
  prestige(xuid: Xuid): Promise<ActionOutcome>;
  /** Lit ce que rapporterait un prestige maintenant (GET prestigePreview). */
  preview(xuid: Xuid): Promise<PreviewOutcome>;
  /** Embauche le candidat de la place `slot`, dans une usine ou, sans `factoryId`, sans usine. Le service fixe le prix et refuse si le Donar manque. */
  hire(xuid: Xuid, slot: number, factoryId?: string): Promise<ActionOutcome>;
  /** Change l'usine d'un membre du personnel. Le service refuse si l'usine n'a plus de poste libre. */
  assignStaff(xuid: Xuid, staffId: number, factoryId: string): Promise<ActionOutcome>;
  /** Renvoie un membre du personnel. Le service refuse si le joueur n'est pas le PDG. */
  fireStaff(xuid: Xuid, staffId: number): Promise<ActionOutcome>;
  /** Lit les candidats du jour (GET getCandidates), à chaque ouverture de l'agence. */
  candidates(xuid: Xuid): Promise<CandidatesOutcome>;
  /** Invite un joueur dans l'entreprise du joueur. Le service refuse si le joueur n'est pas le PDG, si l'entreprise est pleine, etc. */
  invite(xuid: Xuid, targetXuid: Xuid): Promise<ActionOutcome>;
  /** Fixe les parts de chaque associé. Le service vérifie le total et refuse (400) s'il n'est pas de 100. */
  setShares(xuid: Xuid, shares: readonly ShareInput[]): Promise<ActionOutcome>;
  /** Accepte l'invitation de l'entreprise `companyId`. La vue du joueur devient celle de cette entreprise. */
  acceptInvite(xuid: Xuid, companyId: number): Promise<ActionOutcome>;
  /** Relit l'entreprise du joueur (GET getCompany) : la liste des associés change sans que le joueur n'agisse. */
  refresh(xuid: Xuid): Promise<RefreshOutcome>;
}

export interface ActionsDeps {
  readonly api: Pick<
    CoreApi,
    | "getCompany"
    | "collect"
    | "buyLevels"
    | "restoreStep"
    | "resolveIncident"
    | "prestige"
    | "prestigePreview"
    | "getCandidates"
    | "hire"
    | "assignStaff"
    | "fireStaff"
    | "invite"
    | "setShares"
    | "acceptInvite"
  >;
  readonly views: ViewStore;
  /** Un UUID neuf à chaque appel (runtime.ts, createRequestIdGenerator). */
  readonly newRequestId: () => string;
  readonly log: Logger;
  /** Appelé, et attendu, après chaque encaissement réussi. Une exception est journalisée, jamais relancée. */
  readonly afterCollect?: (xuid: Xuid) => Promise<void>;
  /** Appelé, et attendu, après chaque entrée dans une entreprise (le HUD se redessine). Une exception est journalisée, jamais relancée. */
  readonly afterJoin?: (xuid: Xuid) => void | Promise<void>;
}

/** Une action prête à partir : tout ce qu'il faut pour l'envoyer, et pour lire ce qu'elle rapporte. */
interface Plan<T> {
  readonly label: string;
  /** L'entreprise visée, quand ce n'est pas celle du joueur (accepter une invitation). Le joueur y entre : la vue la remplace. */
  readonly joins?: number;
  readonly send: (companyId: number, action: ActionBase) => Promise<ApiResult<T>>;
  /** Range le corps de la réponse dans le ViewStore et dit ce qui s'est passé. */
  readonly accept: (body: T) => { readonly update: ViewUpdate; readonly outcome: ActionOutcome };
}

export function createActions(deps: ActionsDeps): Actions {
  const { api, views, newRequestId, log } = deps;
  const running = new Set<Xuid>();

  async function afterCollect(xuid: Xuid): Promise<void> {
    if (deps.afterCollect === undefined) return;
    try {
      await deps.afterCollect(xuid);
    } catch (error) {
      log.warn(`après collect : ${describeError(error)}`);
    }
  }

  async function afterJoin(xuid: Xuid): Promise<void> {
    if (deps.afterJoin === undefined) return;
    try {
      await deps.afterJoin(xuid);
    } catch (error) {
      log.warn(`après acceptInvite : ${describeError(error)}`);
    }
  }

  async function run<T>(xuid: Xuid, plan: Plan<T>): Promise<ActionOutcome> {
    if (running.has(xuid)) return { kind: "busy" };
    const view = views.get(xuid);
    if (view === undefined) return { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" };
    running.add(xuid);
    try {
      const companyId = plan.joins ?? view.state.companyId;
      const action: ActionBase = { requestId: newRequestId(), actor: xuid };
      let result = await plan.send(companyId, action);

      if (result.kind === "refused" && result.reason === CONFLICT_REASON) {
        if (plan.joins === undefined) {
          log.info(`${plan.label} : conflit, état rechargé puis action rejouée une fois`);
          const fresh = await api.getCompany(companyId);
          if (fresh.kind !== "ok") return failure(plan.label, fresh);
          views.apply(xuid, { state: fresh.body });
        } else {
          log.info(`${plan.label} : conflit, action rejouée une fois`);
        }
        result = await plan.send(companyId, action);
      }

      if (result.kind === "ok") {
        const { update, outcome } = plan.accept(result.body);
        if (plan.joins === undefined) views.apply(xuid, update);
        else views.switchCompany(xuid, update);
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
   * refus de getCompany, de prestigePreview ou de getCandidates (le contrat n'en prévoit pas) y arrive, et il est
   * traité comme une erreur du service.
   */
  function failure(label: string, result: Exclude<ApiResult<unknown>, { kind: "ok" }>): FailedOutcome {
    log.warn(`${label} : ${describeFailure(result)}`);
    return result.kind === "network"
      ? { kind: "failed", failure: "network", reason: "RESEAU" }
      : { kind: "failed", failure: "error", reason: result.reason };
  }

  return {
    collect: async (xuid) => {
      const outcome = await run(xuid, {
        label: "collect",
        send: (companyId, action) => api.collect(companyId, action),
        accept: (body) => ({
          update: { state: body.state },
          outcome: { kind: "collected", collected: body.collected, offlineHours: body.offlineHours },
        }),
      });
      if (outcome.kind === "collected") await afterCollect(xuid);
      return outcome;
    },
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
    resolveIncident: (xuid, incidentId) =>
      run(xuid, {
        label: "resolveIncident",
        send: (companyId, action) => api.resolveIncident(companyId, incidentId, action),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "incidentResolved" } }),
      }),
    prestige: (xuid) =>
      run(xuid, {
        label: "prestige",
        send: (companyId, action) => api.prestige(companyId, action),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "prestiged" } }),
      }),
    preview: async (xuid) => {
      const view = views.get(xuid);
      if (view === undefined) return { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" };
      const result = await api.prestigePreview(view.state.companyId);
      if (result.kind === "ok") return { kind: "preview", preview: result.body };
      return failure("prestigePreview", result);
    },
    hire: (xuid, slot, factoryId) =>
      run(xuid, {
        label: "hire",
        send: (companyId, action) => api.hire(companyId, slot, action, factoryId),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "hired" } }),
      }),
    assignStaff: (xuid, staffId, factoryId) =>
      run(xuid, {
        label: "assignStaff",
        send: (companyId, action) => api.assignStaff(companyId, staffId, action, factoryId),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "assigned" } }),
      }),
    fireStaff: (xuid, staffId) =>
      run(xuid, {
        label: "fireStaff",
        send: (companyId, action) => api.fireStaff(companyId, staffId, action),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "fired" } }),
      }),
    candidates: async (xuid) => {
      const view = views.get(xuid);
      if (view === undefined) return { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" };
      const result = await api.getCandidates(view.state.companyId);
      if (result.kind === "ok") return { kind: "candidates", candidates: result.body };
      return failure("getCandidates", result);
    },
    invite: (xuid, targetXuid) =>
      run(xuid, {
        label: "invite",
        send: (companyId, action) => api.invite(companyId, action, targetXuid),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "invited" } }),
      }),
    setShares: (xuid, shares) =>
      run(xuid, {
        label: "setShares",
        send: (companyId, action) => api.setShares(companyId, action, shares),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "sharesSet" } }),
      }),
    acceptInvite: async (xuid, companyId) => {
      const outcome = await run(xuid, {
        label: "acceptInvite",
        joins: companyId,
        send: (target, action) => api.acceptInvite(target, action),
        accept: (body) => ({ update: { state: body.state, balances: body.balances }, outcome: { kind: "joined" } }),
      });
      if (outcome.kind === "joined") await afterJoin(xuid);
      return outcome;
    },
    refresh: async (xuid) => {
      const view = views.get(xuid);
      if (view === undefined) return { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" };
      const result = await api.getCompany(view.state.companyId);
      if (result.kind !== "ok") return failure("getCompany", result);
      views.apply(xuid, { state: result.body });
      return { kind: "refreshed" };
    },
  };
}
