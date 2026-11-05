// La seule enveloppe autour de @minecraft/server-ui. Tout le reste des écrans parle à l'interface `FormPresenter`
// et ne connaît ni ActionFormData ni FormCancelationReason : les tests y mettent un faux présentateur, et ce
// fichier n'est jamais chargé par eux (menu.ts n'en importe que des types, et main.ts seul l'importe pour de bon).
//
// Il ne décide de rien : il montre une fenêtre à boutons et dit ce que le joueur en a fait.

import type { Player } from "@minecraft/server";
import { ActionFormData, FormCancelationReason } from "@minecraft/server-ui";

/** Un écran à boutons : un titre, un corps de texte, des boutons dans l'ordre où on les montre. */
export interface ActionFormSpec {
  readonly title: string;
  readonly body: string;
  readonly buttons: readonly string[];
}

/**
 * Ce que le joueur a fait de la fenêtre :
 * - `selected` : il a appuyé sur le bouton d'indice `index` ;
 * - `closed` : il l'a fermée ;
 * - `busy` : Bedrock ne l'a pas montrée, le joueur avait déjà un écran ouvert (le tchat, un autre menu).
 */
export type FormAnswer =
  | { readonly kind: "selected"; readonly index: number }
  | { readonly kind: "closed" }
  | { readonly kind: "busy" };

/** Montre une fenêtre à un joueur et rend sa réponse. Ne lève pas pour un joueur parti : le menu garde cet appel. */
export interface FormPresenter<P> {
  show(player: P, spec: ActionFormSpec): Promise<FormAnswer>;
}

/** Le présentateur de BDS : un ActionFormData par écran. */
export function createFormPresenter(): FormPresenter<Player> {
  return {
    async show(player, spec) {
      const form = new ActionFormData().title(spec.title).body(spec.body);
      for (const button of spec.buttons) form.button(button);
      const response = await form.show(player);
      if (response.canceled) {
        return response.cancelationReason === FormCancelationReason.UserBusy ? { kind: "busy" } : { kind: "closed" };
      }
      return typeof response.selection === "number" ? { kind: "selected", index: response.selection } : { kind: "closed" };
    },
  };
}
