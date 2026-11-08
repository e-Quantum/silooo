// L'affiche Discord de la salle d'attente, sans Bedrock : la fenêtre (bannière, QR code vers silooo.com/discord/, bouton)
// et la ligne « silooo.com/discord » qui scintille dans la barre d'action. La fenêtre vient de la pop-up pub de Hubyy
// (hub/src/main/resources/pub-pack/ui/server_form.json et UIForms.createPubPoster) : le marqueur dans le titre fait
// dessiner l'écran par `resource-pack-attente/ui/server_form.json`, et chaque bouton ne montre que la zone dont son texte
// porte la marque (des codes de couleur, invisibles). Seuls des boutons : une note ou un séparateur prendraient une case.

import type { ActionFormSpec, FormAnswer, FormElement } from "../ui/formulaires";
import type { MontrerDeps } from "./accueil";
import { AFFICHE_TEXTES, LIEN_DISCORD } from "./textes";

/** Le marqueur du titre : le pack remplace alors la fenêtre standard par l'affiche. */
export const MARQUE_AFFICHE = "§s§d§i§s";
const BANNIERE = "§1§1§r";
const QR = "§2§2§r";
const SUITE = "§3§3§r";

/** Les images de resource-pack-attente/textures/ui/silooo_discord/ (le QR est fait par tools/generer_qr_discord.mjs). */
export const IMAGES_AFFICHE = {
  banniere: "textures/ui/silooo_discord/banniere",
  qr: "textures/ui/silooo_discord/qr",
} as const;

/** La fenêtre : trois boutons, dans l'ordre bannière, QR code, suite. N'importe lequel la ferme. */
export function construireAffiche(): ActionFormSpec {
  const elements: FormElement[] = [
    { kind: "button", text: `${BANNIERE}${AFFICHE_TEXTES.badge}`, icon: IMAGES_AFFICHE.banniere },
    { kind: "button", text: `${QR}§l${AFFICHE_TEXTES.legendeQr}`, icon: IMAGES_AFFICHE.qr },
    { kind: "button", text: `${SUITE}${AFFICHE_TEXTES.continuer}  ›` },
  ];
  return {
    title: `${MARQUE_AFFICHE}§r${AFFICHE_TEXTES.titre.toLocaleUpperCase("fr-FR")}`,
    body: `${AFFICHE_TEXTES.corps}\n${AFFICHE_TEXTES.suite}\n${AFFICHE_TEXTES.lien}`,
    buttons: elements.flatMap((e) => (e.kind === "button" ? [e.text] : [])),
    elements,
  };
}

/**
 * Montre l'affiche et rend `true` quand le joueur l'a vue (fermée ou continuée), `false` si le jeu était occupé
 * [essais] fois de suite. [essais] vaut 1 pour une chute : l'affiche ne se met pas en file derrière un autre écran.
 */
export async function montrerAffiche(deps: MontrerDeps): Promise<boolean> {
  const spec = construireAffiche();
  for (let essai = 1; essai <= deps.essais; essai += 1) {
    const reponse: FormAnswer = await deps.show(spec);
    if (reponse.kind !== "busy") return true;
    if (essai < deps.essais) await deps.attendre(1);
  }
  return false;
}

/** Pas de la ligne animée : le reflet traverse les lettres, puis la ligne reste fixe PAUSE pas. Un pas = 2 ticks. */
const PAUSE = 30;
const REFLET = 2;
const CYCLE = LIEN_DISCORD.length + REFLET + PAUSE;

/**
 * « silooo.com/discord » pour le pas [pas] : aqua, avec un reflet blanc de deux lettres qui va de gauche à droite puis
 * une pause d'environ trois secondes. Même longueur et mêmes lettres à chaque pas : le texte ne saute pas, rien n'est gras.
 */
export function lienAnime(pas: number): string {
  const position = ((Math.floor(pas) % CYCLE) + CYCLE) % CYCLE;
  let texte = "§b";
  let reflet = false;
  for (let i = 0; i < LIEN_DISCORD.length; i += 1) {
    const dedans = i >= position - REFLET + 1 && i <= position;
    if (dedans !== reflet) {
      texte += dedans ? "§f" : "§b";
      reflet = dedans;
    }
    texte += LIEN_DISCORD[i];
  }
  return texte;
}
