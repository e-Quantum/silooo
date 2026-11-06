// Textes provisoires à valider par Kr. Le service émet le code ; le client ne prouve aucune liaison.
import type { CoreApi } from "../api";
import type { DiscordLinkCode, Xuid } from "../protocol";
import type { ActionFormSpec, FormAnswer } from "./formulaires";
import { DISCORD_TEXTES } from "./textes";

export interface DiscordLinkScreenDeps {
  readonly actor: Xuid;
  readonly api: Pick<CoreApi, "issueDiscordLinkCode">;
  readonly newRequestId: () => string;
  readonly show: (spec: ActionFormSpec) => Promise<FormAnswer>;
}

export async function playDiscordLink(deps: DiscordLinkScreenDeps): Promise<void> {
  let code: DiscordLinkCode | undefined;
  let pendingId: string | undefined;
  let problem: string | undefined;
  for (;;) {
    const answer = await deps.show({
      title: DISCORD_TEXTES.titre,
      body: [DISCORD_TEXTES.instructions, ...(code === undefined ? [] : [DISCORD_TEXTES.code(code.code), DISCORD_TEXTES.expiration(code.expiresAt)]), ...(problem === undefined ? [] : [problem])].join("\n"),
      buttons: [problem !== undefined ? DISCORD_TEXTES.reessayer : code === undefined ? DISCORD_TEXTES.emettre : DISCORD_TEXTES.rafraichir, DISCORD_TEXTES.retour],
    });
    if (answer.kind !== "selected" || answer.index !== 0) return;
    pendingId ??= deps.newRequestId();
    let result;
    try { result = await deps.api.issueDiscordLinkCode({ requestId: pendingId, actor: deps.actor }); }
    catch { problem = DISCORD_TEXTES.serviceAbsent; continue; }
    if (result.kind === "ok") {
      code = result.body;
      pendingId = undefined;
      problem = undefined;
    } else {
      problem = result.kind === "refused" && result.reason === "JOUEUR_INCONNU"
        ? DISCORD_TEXTES.joueurAbsent
        : DISCORD_TEXTES.serviceAbsent;
      // Une panne ou un conflit rejoue l'intention ; un refus définitif exige une nouvelle intention.
      if (result.kind === "refused" && result.reason !== "CONFLIT_REESSAYER") pendingId = undefined;
    }
  }
}
