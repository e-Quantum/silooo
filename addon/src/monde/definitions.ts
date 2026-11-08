// Les chiffres du monde : definitions/iles.json, recopié dans le script par esbuild. Le fichier est vérifié contre
// son schéma par tools/verifier_definitions.mjs (CI), et ses règles de fond par `verifierIles` (test/iles-definitions.test.ts).
// Rien ici ne décide quoi que ce soit : on lit des chiffres et on les rend tels quels.

import brut from "../../../definitions/iles.json";
import brutBoutique from "../../../definitions/boutique.json";
import brutPersonnel from "../../../definitions/personnel.json";
import brutUsines from "../../../definitions/usines.json";
import brutVille from "../../../definitions/ville.json";
import brutVote from "../../../definitions/vote.json";
import type { BoutiqueDefinitions, IlesDefinitions, PersonnelDefinitions, UsinesDefinitions, VilleDefinitions, VoteDefinitions } from "../generated/definitions";

/**
 * Le JSON importé a des chaînes larges (`string`), les types générés ont les unions exactes du schéma : le passage
 * se fait ici, une seule fois, et le test des définitions compare le fichier réel à ce que le schéma promet.
 */
export const ILES: IlesDefinitions = brut as unknown as IlesDefinitions;

/** La ville de l'île principale : definitions/ville.json (zones, catalogue des bâtiments, prix du niveau 1), lu tel quel. */
export const VILLE: VilleDefinitions = brutVille as unknown as VilleDefinitions;

/** La boutique : definitions/boutique.json. Les noms, les catégories et les bonus des articles viennent d'ici ; les prix, du catalogue du service. */
export const BOUTIQUE: BoutiqueDefinitions = brutBoutique as unknown as BoutiqueDefinitions;

/** Les rayons de cosmétiques de la boutique : la clé `categorie` de definitions/boutique.json. */
export type CosmeticCategory = BoutiqueDefinitions["cosmetiques"][number]["categorie"];

/** Le personnel : definitions/personnel.json. Le nom de chaque rareté vient d'ici. */
export const PERSONNEL: PersonnelDefinitions = brutPersonnel as unknown as PersonnelDefinitions;

/** Les usines : definitions/usines.json. Seule la clé `decouverte` sert ici : la carte dit ce qui ouvre une usine cachée. */
export const USINES: UsinesDefinitions = brutUsines as unknown as UsinesDefinitions;

/** Les votes : definitions/vote.json. Le nom de chaque site de vote vient d'ici (le rappel du vote du jour). */
export const VOTE: VoteDefinitions = brutVote as unknown as VoteDefinitions;

/** Les règles de fond que le schéma JSON ne peut pas dire. Rend une liste de problèmes lisibles, vide si tout va bien. */
export function verifierIles(defs: IlesDefinitions, tailleDeLaCase: number): string[] {
  const problemes: string[] = [];
  const { anneau, plateforme, apparition } = defs;

  const ids = defs.usines.map((usine) => usine.id);
  if (new Set(ids).size !== ids.length) problemes.push("une usine apparaît deux fois dans usines");
  if (!ids.some((id) => id === apparition.usine)) problemes.push(`apparition.usine « ${apparition.usine} » n'est pas dans usines`);

  const rayons = plateforme.couches.map((couche) => plateforme.rayon - couche.retrait);
  if (plateforme.couches[0]?.retrait !== 0) problemes.push("la première couche de la plateforme doit avoir un retrait de 0");
  if (plateforme.couches[0]?.materiau !== "herbe") problemes.push("la face du dessus de la plateforme doit être de l'herbe");
  if (rayons.some((rayon) => rayon < 1)) problemes.push("une couche de la plateforme a un rayon inférieur à 1");
  for (let i = 1; i < rayons.length; i += 1) {
    if ((rayons[i] ?? 0) > (rayons[i - 1] ?? 0)) problemes.push(`la couche ${i + 1} est plus large que la couche ${i}`);
  }

  // L'anneau tient dans la case, hors de l'île principale.
  const moitie = tailleDeLaCase / 2;
  if (anneau.demiCote - plateforme.rayon <= anneau.ilePrincipaleDemiCote) {
    problemes.push("les mini-îles touchent l'île principale : demiCote moins le rayon doit dépasser ilePrincipaleDemiCote");
  }
  if (defs.centreDansLaCase.x + anneau.demiCote + plateforme.rayon >= tailleDeLaCase ||
    defs.centreDansLaCase.x - anneau.demiCote - plateforme.rayon < 0 ||
    defs.centreDansLaCase.z + anneau.demiCote + plateforme.rayon >= tailleDeLaCase ||
    defs.centreDansLaCase.z - anneau.demiCote - plateforme.rayon < 0) {
    problemes.push(`l'anneau sort de la case de ${tailleDeLaCase} blocs`);
  }
  if (defs.centreDansLaCase.x !== moitie || defs.centreDansLaCase.z !== moitie) {
    problemes.push("le centre de l'île principale n'est pas au milieu de la case");
  }

  // Deux mini-îles ne se touchent pas, et chaque bâtiment tient sur sa plateforme avec sa porte et son enseigne.
  const centres = defs.usines.map((usine) => {
    const rad = (usine.angle * Math.PI) / 180;
    const dx = Math.cos(rad);
    const dz = Math.sin(rad);
    const k = anneau.demiCote / Math.max(Math.abs(dx), Math.abs(dz));
    return { id: usine.id, x: Math.round(dx * k), z: Math.round(dz * k) };
  });
  for (let i = 0; i < centres.length; i += 1) {
    for (let j = i + 1; j < centres.length; j += 1) {
      const a = centres[i];
      const b = centres[j];
      if (a === undefined || b === undefined) continue;
      if (Math.hypot(a.x - b.x, a.z - b.z) < 2 * plateforme.rayon + 4) {
        problemes.push(`les mini-îles ${a.id} et ${b.id} sont trop proches`);
      }
    }
  }
  for (const usine of defs.usines) {
    if (usine.largeur % 2 === 0 || usine.profondeur % 2 === 0) problemes.push(`${usine.id} : largeur et profondeur doivent être impaires`);
    // Le coin le plus éloigné (un poteau de chantier, à un bloc des murs) est sur la face du dessus, un disque de rayon + demi-bloc.
    const coin = ((usine.largeur - 1) / 2 + 1) ** 2 + ((usine.profondeur - 1) / 2 + 1) ** 2;
    if (coin > plateforme.rayon ** 2 + plateforme.rayon) problemes.push(`${usine.id} : le bâtiment et ses abords débordent de la plateforme`);
  }
  const depart = defs.usines.find((usine) => usine.id === apparition.usine);
  if (depart !== undefined) {
    const demi = Math.max(depart.largeur, depart.profondeur) / 2;
    if (apparition.distance <= demi) problemes.push("le joueur apparaîtrait dans le bâtiment de départ");
    if (apparition.distance > plateforme.rayon) problemes.push("le joueur apparaîtrait hors de la plateforme");
  }

  // La cinématique : quatre plans, vingt secondes (design/composants/Cinematique.md).
  const plans = defs.cinematique.plans;
  if (plans.length !== 4) problemes.push(`la cinématique d'arrivée a ${plans.length} plans, il en faut 4`);
  const total = plans.reduce((somme, plan) => somme + plan.duree, 0);
  if (Math.abs(total - 20) > 1e-9) problemes.push(`la cinématique d'arrivée dure ${total} s, il en faut 20`);

  return problemes;
}
