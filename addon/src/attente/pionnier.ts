// L'écran « Devenir Pionnier » de la salle d'attente : l'invitation Discord, puis le code de liaison émis par silooo-core
// (issueLobbyLinkCode). Le service inscrit le visiteur pionnier et émet le code ; l'écran ne fait que montrer.
// Sans Bedrock : le présentateur d'écrans est passé en paramètre (test/attente.test.ts).

import type { CoreApi } from "../api";
import type { DiscordLinkCode, Xuid } from "../protocol";
import type { ActionFormSpec, FormAnswer } from "../ui/formulaires";
import { PIONNIER_TEXTES } from "./textes";

export interface PionnierDeps {
  readonly actor: Xuid;
  readonly gamertag: string;
  /** Absent quand la salle d'attente n'a pas de réglages pour joindre silooo-core : l'écran ne montre que l'invitation. */
  readonly api: Pick<CoreApi, "issueLobbyLinkCode"> | undefined;
  readonly newRequestId: () => string;
  readonly show: (spec: ActionFormSpec) => Promise<FormAnswer>;
}

export async function ecranPionnier(deps: PionnierDeps): Promise<void> {
  const api = deps.api;
  let code: DiscordLinkCode | undefined;
  let enCours: string | undefined;
  let probleme: string | undefined = api === undefined ? PIONNIER_TEXTES.horsService : undefined;
  for (;;) {
    const corps = [PIONNIER_TEXTES.instructions];
    if (code !== undefined) corps.push("", PIONNIER_TEXTES.code(code.code), PIONNIER_TEXTES.lier(code.code), PIONNIER_TEXTES.expiration);
    if (probleme !== undefined) corps.push("", probleme);
    const boutons = api === undefined
      ? [PIONNIER_TEXTES.fermer]
      : [probleme !== undefined ? PIONNIER_TEXTES.reessayer : code === undefined ? PIONNIER_TEXTES.obtenir : PIONNIER_TEXTES.nouveau, PIONNIER_TEXTES.fermer];
    const reponse = await deps.show({ title: PIONNIER_TEXTES.titre, body: corps.join("\n"), buttons: boutons });
    if (api === undefined || reponse.kind !== "selected" || reponse.index !== 0) return;
    // Une panne rejoue la même intention ; un succès ou un refus définitif en demande une nouvelle.
    enCours ??= deps.newRequestId();
    let resultat;
    try {
      resultat = await api.issueLobbyLinkCode({ requestId: enCours, actor: deps.actor }, deps.gamertag);
    } catch {
      probleme = PIONNIER_TEXTES.serviceAbsent;
      continue;
    }
    if (resultat.kind === "ok") {
      code = resultat.body;
      enCours = undefined;
      probleme = undefined;
    } else {
      probleme = PIONNIER_TEXTES.serviceAbsent;
      if (resultat.kind === "refused" && resultat.reason !== "CONFLIT_REESSAYER") enCours = undefined;
    }
  }
}
