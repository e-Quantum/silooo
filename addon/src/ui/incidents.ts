// Les incidents d'usine vus par le joueur : le message du tchat quand un incident s'ouvre ou se répare, et la relecture
// de l'état de l'entreprise pour que la fiche d'usine montre (ou ne montre plus) le bouton « Réparer ».
//
// Le service décide tout (core/README.md, « Les incidents ») : quand un incident s'ouvre, de quelle sorte, de combien il
// ralentit, ce que la réparation rapporte. Il publie l'événement `INCIDENT` pour chaque membre connecté de l'entreprise
// (api/silooo-core.openapi.yaml, GameEvent). Ce fichier lit l'événement et l'écrit en phrases ; il ne calcule rien :
// pas de ralentissement appliqué ici, pas de gain estimé, la récompense montrée est `payload.reward` mise en forme.

import type { CoreApi } from "../api";
import { describeFailure } from "../http";
import type { GameEvent, Xuid } from "../protocol";
import { describeError, type Logger } from "../runtime";
import { COLOR, colored } from "./apparence";
import type { ViewStore } from "./etat";
import { formatAmount } from "./nombres";
import { BULLES, INCIDENT_TEXTES, factoryName, incidentTexts, renderBubble } from "./textes";

/** Un incident qui s'ouvre, ou qui est réparé, tel que l'événement du service le dit. */
export type IncidentEvent =
  | {
      readonly action: "opened";
      readonly incidentId: number;
      readonly factoryId: string;
      readonly kind: string;
    }
  | {
      readonly action: "resolved";
      readonly incidentId: number;
      readonly factoryId: string;
      readonly kind: string;
      /** Le Donar versé, tel que le service le dit : 0 quand rien n'a été versé. Absent si le payload ne le dit pas. */
      readonly reward: number | undefined;
      /** Le XUID de celui qui a réparé, ou undefined si le payload ne le dit pas. */
      readonly resolvedBy: Xuid | undefined;
    };

/** Lit un événement `INCIDENT`. Rend undefined pour un autre type d'événement ou un payload qu'on ne peut pas lire. */
export function readIncidentEvent(event: GameEvent): IncidentEvent | undefined {
  if (event.kind !== "INCIDENT") return undefined;
  const { action, incidentId, factoryId, kind, reward, resolvedBy } = event.payload;
  if (typeof incidentId !== "number" || !Number.isSafeInteger(incidentId)) return undefined;
  if (typeof factoryId !== "string" || factoryId === "") return undefined;
  // La sorte est toujours là d'après le contrat ; si elle manque, le mot général « Incident » suffit.
  const sorte = typeof kind === "string" ? kind : "";
  if (action === "opened") return { action, incidentId, factoryId, kind: sorte };
  if (action === "resolved") {
    return {
      action,
      incidentId,
      factoryId,
      kind: sorte,
      reward: typeof reward === "number" && Number.isFinite(reward) && reward >= 0 ? reward : undefined,
      resolvedBy: typeof resolvedBy === "string" && resolvedBy !== "" ? resolvedBy : undefined,
    };
  }
  return undefined;
}

/**
 * Le texte envoyé au joueur `xuid` : la bulle d'Oooscar, l'usine, puis, pour une réparation, la récompense du service.
 * Une récompense de 0 le dit : un associé avait déjà réparé (le service ne verse rien deux fois).
 */
export function incidentText(incident: IncidentEvent, xuid: Xuid): string {
  const usine = factoryName(incident.factoryId);
  if (incident.action === "opened") {
    return [
      renderBubble(BULLES.incident),
      colored(COLOR.incident, INCIDENT_TEXTES.ouverture.usine(usine, incidentTexts(incident.kind).nom)),
      INCIDENT_TEXTES.ouverture.conseil,
    ].join("\n");
  }
  const parToi = incident.resolvedBy === xuid;
  const lines = [
    renderBubble(parToi ? BULLES.repareeParToi : BULLES.repareeParUnAssocie),
    INCIDENT_TEXTES.reparation.faite(usine),
  ];
  if (incident.reward !== undefined) {
    if (incident.reward > 0) {
      lines.push(colored(COLOR.donar, INCIDENT_TEXTES.reparation.recompense(formatAmount(incident.reward))));
    } else {
      lines.push(parToi ? INCIDENT_TEXTES.reparation.aucune : INCIDENT_TEXTES.reparation.dejaFaite);
    }
  }
  return lines.join("\n");
}

export interface Incidents {
  /**
   * Un événement `INCIDENT` : si le joueur est présent, le message du tchat, puis la relecture de l'état de son entreprise
   * (la fiche d'usine montre alors le bouton « Réparer », ou ne le montre plus). Ne lève pas.
   */
  onIncident(event: GameEvent): Promise<void>;
}

export interface IncidentsDeps {
  readonly api: Pick<CoreApi, "getCompany">;
  readonly views: ViewStore;
  /** Envoie un texte au joueur (le tchat). Une exception est journalisée, jamais relancée. */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly log: Logger;
}

export function createIncidents(deps: IncidentsDeps): Incidents {
  const { api, views, say, log } = deps;

  async function refresh(xuid: Xuid, companyId: number): Promise<void> {
    const result = await api.getCompany(companyId);
    if (result.kind !== "ok") {
      log.warn(`incident, état de ${xuid} : ${describeFailure(result)}`);
      return;
    }
    views.apply(xuid, { state: result.body });
  }

  return {
    async onIncident(event) {
      const incident = readIncidentEvent(event);
      const xuid = event.xuid;
      if (incident === undefined || xuid === null) return;
      // Un joueur absent n'a personne à qui parler : à son retour, `join` rend l'état du service.
      const view = views.get(xuid);
      if (view === undefined) return;
      try {
        say(xuid, incidentText(incident, xuid));
      } catch (error) {
        log.warn(`message d'incident à ${xuid} : ${describeError(error)}`);
      }
      try {
        await refresh(xuid, view.state.companyId);
      } catch (error) {
        log.warn(`incident, état de ${xuid} : ${describeError(error)}`);
      }
    },
  };
}
