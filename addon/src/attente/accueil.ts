// Le popup d'accueil de la salle d'attente, à chaque entrée et à chaque ouverture de la boussole, sans Bedrock. Même
// grammaire que l'écran d'accueil de Hubyy (UIForms.createMotd et sa classe Screen) : un bonjour en blanc avec le pseudo
// en accent ; une ligne grise ; des boutons à deux lignes (titre en gras, indication grise) avec une icône ; des sections
// en capitales d'accent. Un joueur qui revient voit les infos d'abord ; un joueur relié n'est plus relancé.

import type { LobbyBoard, LobbyPlayer } from "../protocol";
import type { ActionFormSpec, FormAnswer, FormElement } from "../ui/formulaires";
import { ACCUEIL_TEXTES } from "./textes";

/** Ce que fait chaque bouton de l'accueil. */
export type ActionAccueil = "pionnier" | "sauter" | "rejoindre";

export interface EtatAccueil {
  readonly pseudo: string;
  /** Première entrée du joueur dans la salle d'attente. */
  readonly nouveau: boolean;
  /** Sur la liste blanche, et le monde de test est donné : le bouton « Rejoindre Silooo » apparaît. */
  readonly testeur: boolean;
  /** Ce que le service dit du joueur ; absent s'il ne répond pas. */
  readonly joueur?: LobbyPlayer;
  /** Le classement et les actus ; absent si le service ne répond pas. */
  readonly tableau?: LobbyBoard;
}

/** Icônes du pack de textures de Minecraft : aucune image à livrer. */
const ICONES: Readonly<Record<ActionAccueil, string>> = {
  pionnier: "textures/items/name_tag",
  sauter: "textures/items/feather",
  rejoindre: "textures/items/ender_pearl",
};

export interface Accueil {
  readonly spec: ActionFormSpec;
  /** L'action de chaque bouton, par rang : le jeu répond avec ce rang. */
  readonly actions: readonly ActionAccueil[];
}

/** Une section : son nom en capitales et gras d'accent, un détail gris à côté, quelques mots dessous. */
function section(nom: string, detail: string, corps: string): FormElement {
  const ligne = `§6§l${nom.toLocaleUpperCase("fr-FR")}§r${detail === "" ? "" : `§7 · ${detail}`}`;
  return { kind: "label", text: corps === "" ? ligne : `${ligne}\n§7${corps}` };
}

function sectionInfos(tableau: LobbyBoard | undefined): FormElement | undefined {
  if (tableau === undefined) return undefined;
  const lignes = tableau.news.map((actu) => actu.title);
  return section(ACCUEIL_TEXTES.infos, "", lignes.length === 0 ? ACCUEIL_TEXTES.aucuneInfo : lignes.join("\n§7"));
}

function sectionClassement(etat: EtatAccueil): FormElement {
  const tableau = etat.tableau;
  if (tableau === undefined) return section(ACCUEIL_TEXTES.classement, ACCUEIL_TEXTES.semaine, ACCUEIL_TEXTES.horsLigne);
  const lignes = tableau.week.slice(0, 3).map((l) => ACCUEIL_TEXTES.ligne(l.rank, l.gamertag, l.score));
  if (lignes.length === 0) lignes.push(ACCUEIL_TEXTES.aucunSaut);
  const moi = tableau.week.find((l) => l.gamertag === etat.pseudo);
  if (moi !== undefined && moi.rank > 3) lignes.push(ACCUEIL_TEXTES.tonRang(moi.rank));
  if (tableau.pioneers > 0) lignes.push(ACCUEIL_TEXTES.pionniers(tableau.pioneers));
  return section(ACCUEIL_TEXTES.classement, ACCUEIL_TEXTES.semaine, lignes.join("\n"));
}

/** L'écran d'accueil pour [etat], et l'action de chacun de ses boutons. */
export function construireAccueil(etat: EtatAccueil): Accueil {
  const relie = etat.joueur?.linked === true;
  const derniere = etat.tableau?.news[0]?.title;
  const infosDabord = !etat.nouveau && derniere !== undefined;
  const bonjour = etat.nouveau ? ACCUEIL_TEXTES.bonjour(etat.pseudo) : ACCUEIL_TEXTES.rebonjour(etat.pseudo);
  const body = `${bonjour}\n${infosDabord ? `§7${derniere}` : ACCUEIL_TEXTES.cestQuoi}`;

  const elements: FormElement[] = [];
  const actions: ActionAccueil[] = [];
  const bouton = (action: ActionAccueil, texte: string) => {
    elements.push({ kind: "button", text: texte, icon: ICONES[action] });
    actions.push(action);
  };
  const infos = sectionInfos(etat.tableau);
  if (infosDabord && infos !== undefined) elements.push(infos, { kind: "divider" });
  bouton("pionnier", relie ? ACCUEIL_TEXTES.dejaPionnier : ACCUEIL_TEXTES.pionnier);
  bouton("sauter", ACCUEIL_TEXTES.sauter);
  if (etat.testeur) bouton("rejoindre", ACCUEIL_TEXTES.rejoindre);
  elements.push({ kind: "divider" });
  if (!infosDabord && infos !== undefined) elements.push(infos);
  elements.push(sectionClassement(etat));

  const buttons = elements.flatMap((e) => (e.kind === "button" ? [e.text] : []));
  return { spec: { title: ACCUEIL_TEXTES.titre, body, buttons, elements }, actions };
}

/** L'action choisie dans la réponse du jeu, ou undefined (fermé, occupé, rang inconnu). */
export function actionChoisie(accueil: Accueil, reponse: FormAnswer): ActionAccueil | undefined {
  return reponse.kind === "selected" ? accueil.actions[reponse.index] : undefined;
}

export interface MontrerDeps {
  readonly show: (spec: ActionFormSpec) => Promise<FormAnswer>;
  /** Attend [secondes] avant un nouvel essai (le jeu répond `busy` quand un autre écran est ouvert). */
  readonly attendre: (secondes: number) => Promise<void>;
  readonly essais: number;
}

/** Montre l'accueil, en réessayant tant que le jeu est occupé, [essais] fois au plus. */
export async function montrerAccueil(accueil: Accueil, deps: MontrerDeps): Promise<ActionAccueil | undefined> {
  for (let essai = 1; essai <= deps.essais; essai += 1) {
    const reponse = await deps.show(accueil.spec);
    if (reponse.kind !== "busy") return actionChoisie(accueil, reponse);
    if (essai < deps.essais) await deps.attendre(1);
  }
  return undefined;
}
