// La seule enveloppe autour de @minecraft/server-ui. Tout le reste des écrans parle à l'interface `FormPresenter`
// et ne connaît ni ActionFormData ni FormCancelationReason : les tests y mettent un faux présentateur, et ce
// fichier n'est jamais chargé par eux (menu.ts n'en importe que des types, et main.ts seul l'importe pour de bon).
//
// Il ne décide de rien : il montre une fenêtre à boutons (ou, pour les parts, un formulaire de champs de texte) et dit
// ce que le joueur en a fait.

import type { Player } from "@minecraft/server";
import { ActionFormData, FormCancelationReason, ModalFormData } from "@minecraft/server-ui";

/**
 * Un élément d'un écran à sections (server-ui 2.3.0 : header, label, divider, button avec icône), sur le modèle de
 * l'écran d'accueil de Hubyy : une section (son nom en capitales et quelques mots dessous), une note, un séparateur,
 * un bouton (son texte, une icône facultative du pack de textures).
 */
export type FormElement =
  | { readonly kind: "header"; readonly text: string }
  | { readonly kind: "label"; readonly text: string }
  | { readonly kind: "divider" }
  | { readonly kind: "button"; readonly text: string; readonly icon?: string };

/**
 * Un écran à boutons : un titre, un corps de texte, des boutons dans l'ordre où on les montre. Avec [elements], l'écran
 * suit ces éléments à la place de [buttons] (qui reste la liste des textes de ses boutons, dans le même ordre) : la
 * réponse donne toujours le rang du bouton, sans compter les autres éléments.
 */
export interface ActionFormSpec {
  readonly title: string;
  readonly body: string;
  readonly buttons: readonly string[];
  readonly elements?: readonly FormElement[];
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

/** Un champ de texte d'un formulaire : son étiquette, le texte qui l'indique quand il est vide, et sa valeur de départ. */
export interface TextFieldSpec {
  readonly label: string;
  readonly placeholder: string;
  readonly initial: string;
}

/** Un formulaire de champs de texte, avec le texte de son bouton d'envoi. */
export interface ModalFormSpec {
  readonly title: string;
  readonly submit: string;
  readonly fields: readonly TextFieldSpec[];
}

/**
 * Ce que le joueur a fait du formulaire :
 * - `submitted` : il l'a envoyé ; `values` a une chaîne par champ, dans l'ordre (vide si le champ l'est) ;
 * - `closed` : il l'a fermé ;
 * - `busy` : Bedrock ne l'a pas montré, le joueur avait déjà un écran ouvert.
 */
export type ModalAnswer =
  | { readonly kind: "submitted"; readonly values: readonly string[] }
  | { readonly kind: "closed" }
  | { readonly kind: "busy" };

/** Montre une fenêtre à un joueur et rend sa réponse. Ne lève pas pour un joueur parti : le menu garde cet appel. */
export interface FormPresenter<P> {
  show(player: P, spec: ActionFormSpec): Promise<FormAnswer>;
  /** Montre un formulaire de champs de texte. Mêmes règles que `show`. */
  ask(player: P, spec: ModalFormSpec): Promise<ModalAnswer>;
}

/** Le présentateur de BDS : un ActionFormData par écran. */
export function createFormPresenter(): FormPresenter<Player> {
  return {
    async show(player, spec) {
      const form = new ActionFormData().title(spec.title).body(spec.body);
      if (spec.elements === undefined) {
        for (const button of spec.buttons) form.button(button);
      } else {
        for (const element of spec.elements) {
          if (element.kind === "header") form.header(element.text);
          else if (element.kind === "label") form.label(element.text);
          else if (element.kind === "divider") form.divider();
          else if (element.icon === undefined) form.button(element.text);
          else form.button(element.text, element.icon);
        }
      }
      const response = await form.show(player);
      if (response.canceled) {
        return response.cancelationReason === FormCancelationReason.UserBusy ? { kind: "busy" } : { kind: "closed" };
      }
      return typeof response.selection === "number" ? { kind: "selected", index: response.selection } : { kind: "closed" };
    },
    async ask(player, spec) {
      const form = new ModalFormData().title(spec.title).submitButton(spec.submit);
      for (const field of spec.fields) form.textField(field.label, field.placeholder, { defaultValue: field.initial });
      const response = await form.show(player);
      if (response.canceled) {
        return response.cancelationReason === FormCancelationReason.UserBusy ? { kind: "busy" } : { kind: "closed" };
      }
      // Rien que des champs de texte : l'indice d'une valeur est celui de son champ.
      const values = spec.fields.map((_, index) => {
        const value = response.formValues?.[index];
        return typeof value === "string" ? value : "";
      });
      return { kind: "submitted", values };
    },
  };
}
