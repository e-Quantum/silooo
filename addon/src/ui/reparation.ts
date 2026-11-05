// Le mini-jeu de réparation d'une usine en incident : une suite courte d'écrans à boutons, une par étape, d'après la
// sorte d'incident que la fiche du service donne (`Factory.incidentKind`). Chaque étape propose deux ou trois boutons
// dont un seul est le bon ; un mauvais choix rejoue la même étape avec une bulle d'Oooscar qui met sur la voie, et ne
// coûte jamais rien (docs/economie.md, « Les incidents d'usine »). À la dernière étape réussie, le menu demande au
// service de clore l'incident (`resolveIncident`) : le service ne juge pas le mini-jeu, l'add-on l'a joué.
//
// Ce que ce fichier décide, et ce qu'il laisse au service :
// - le nombre d'étapes (trois par sorte, voir REPARATION_TEXTES) et leur ordre (le geste de sécurité, l'outil ou la
//   pièce, le geste final) sont des constantes de l'add-on, écrites avec les phrases dans ui/textes.ts. Ce ne sont pas
//   des chiffres d'économie : changer l'un ou l'autre ne change ni le ralentissement, ni la récompense, ni rien d'autre
//   que la durée du jeu. À valider par Kr, comme tous les textes.
// - la récompense, le ralentissement et le succès de la clôture sont au service. Rien n'est calculé ici.
//
// La logique d'une réponse (`repairMove`) est une fonction pure. La présentation passe par l'écran que le menu sait
// montrer (`present`, donc un FormPresenter) : ce fichier n'importe rien de Bedrock.

import { COLOR, colored } from "./apparence";
import { bodyOf, type Notice } from "./ecran";
import type { ActionFormSpec, FormAnswer } from "./formulaires";
import { REPARATION_TEXTES, factoryName, incidentTexts, repairSteps, type Bubble } from "./textes";

/** Ce que devient le mini-jeu après la réponse du joueur à une étape. */
export type RepairMove =
  /** Mauvais bouton : la même étape revient avec cette bulle d'Oooscar. */
  | { readonly kind: "wrong"; readonly bubble: Bubble }
  /** Bon bouton : l'étape d'après (de 0 à nombre d'étapes - 1). */
  | { readonly kind: "next"; readonly step: number }
  /** Bon bouton à la dernière étape : la réparation est faite. */
  | { readonly kind: "done" };

/** Le nombre d'étapes du mini-jeu d'une sorte d'incident. */
export function repairStepCount(incidentKind: string | null | undefined): number {
  return repairSteps(incidentKind).length;
}

/**
 * Ce que donne le bouton `choice` (son indice) à l'étape `step` (de 0) du mini-jeu de `incidentKind`. Pure : même
 * entrée, même sortie. Une étape hors du jeu est une erreur du programme, pas une réponse du joueur.
 */
export function repairMove(incidentKind: string | null | undefined, step: number, choice: number): RepairMove {
  const steps = repairSteps(incidentKind);
  const current = Number.isInteger(step) ? steps[step] : undefined;
  if (current === undefined) throw new RangeError(`étape hors du mini-jeu : ${step}`);
  if (choice !== current.bonne) return { kind: "wrong", bubble: current.erreur };
  return step + 1 >= steps.length ? { kind: "done" } : { kind: "next", step: step + 1 };
}

/**
 * L'écran d'une étape : la sorte et l'usine, où l'on en est, la consigne, puis les boutons de l'étape et « Retour » en
 * dernier. `notice` est la bulle d'Oooscar d'un mauvais choix. Une étape hors du jeu est une erreur du programme.
 */
export function repairScreen(factoryId: string, incidentKind: string | null | undefined, step: number, notice?: Notice): ActionFormSpec {
  const steps = repairSteps(incidentKind);
  const current = Number.isInteger(step) ? steps[step] : undefined;
  if (current === undefined) throw new RangeError(`étape hors du mini-jeu : ${step}`);
  const lines = [
    colored(COLOR.incident, incidentTexts(incidentKind).nom.toUpperCase()),
    `${COLOR.bold}${factoryName(factoryId)}${COLOR.reset}`,
    colored(COLOR.muted, REPARATION_TEXTES.etape(step + 1, steps.length)),
    "",
    current.consigne,
  ];
  return { title: REPARATION_TEXTES.titre, body: bodyOf(notice, lines), buttons: [...current.choix, REPARATION_TEXTES.retour] };
}

/**
 * Comment le mini-jeu s'est terminé :
 * - `done` : toutes les étapes sont réussies, le menu peut demander la clôture au service ;
 * - `back` : le joueur a pris « Retour », l'incident reste ouvert et rien n'est demandé ;
 * - `closed` : le joueur a fermé la fenêtre (ou Bedrock ne l'a pas montrée) ; le menu se ferme comme pour tout écran.
 */
export type RepairResult = "done" | "back" | "closed";

export interface RepairDeps {
  /** Montre un écran au joueur et rend sa réponse (menu.ts, `present`, qui réessaie quand Bedrock est occupé). */
  readonly show: (spec: ActionFormSpec) => Promise<FormAnswer>;
}

/** Joue le mini-jeu d'une usine, étape par étape, jusqu'à la réussite, « Retour » ou la fermeture. Une exception du présentateur remonte au menu, qui la journalise. */
export async function playRepair(deps: RepairDeps, factoryId: string, incidentKind: string | null | undefined): Promise<RepairResult> {
  let step = 0;
  let notice: Notice | undefined;
  for (;;) {
    const answer = await deps.show(repairScreen(factoryId, incidentKind, step, notice));
    if (answer.kind !== "selected") return "closed";
    // Le dernier bouton est « Retour » : il n'est pas un choix de l'étape.
    if (answer.index === repairSteps(incidentKind)[step]?.choix.length) return "back";
    const move = repairMove(incidentKind, step, answer.index);
    if (move.kind === "done") return "done";
    if (move.kind === "next") {
      step = move.step;
      notice = undefined;
    } else {
      notice = { bubble: move.bubble };
    }
  }
}
