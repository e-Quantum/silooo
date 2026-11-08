// La protection de la Carrière : ce que le joueur peut casser sur son île de départ. Fonctions pures, sans Bedrock.
//
// L'île entière (le préfabriqué posé par le constructeur) est protégée : ni casse ni pose de bloc. Seuls les volumes de
// definitions/iles.json `atelier.libre` se cassent : le front de taille, d'où le tutoriel fait extraire les premières
// pierres. Le mode créatif n'est pas jugé ici : c'est l'appelant qui laisse passer les constructeurs.

import type { IlesDefinitions } from "../generated/definitions";
import { workshopBlock } from "./carriere";
import { frameOfIsland, islandCenter, islandRadius, usineIle, type Pos, type Xz } from "./geometrie";

export type Protection = "hors" | "protege" | "libre";

/** Ce que vaut un bloc au regard des Carrières des archipels connus. */
export function carriereProtection(defs: IlesDefinitions, at: Pos, origins: readonly Xz[]): Protection {
  const usine = usineIle(defs, defs.apparition.usine);
  if (usine === undefined) return "hors";
  const radius = islandRadius(defs, usine);
  const bas = defs.hauteur - defs.atelier.solStructure;
  const haut = defs.hauteur + defs.atelier.hauteurNettoyage;
  for (const origin of origins) {
    const center = islandCenter(origin, usine, defs);
    if (Math.abs(at.x - center.x) > radius || Math.abs(at.z - center.z) > radius || at.y < bas || at.y > haut) continue;
    const frame = frameOfIsland(origin, usine, defs);
    for (const volume of defs.atelier.libre) {
      const a = workshopBlock(frame, defs.hauteur, volume.de), b = workshopBlock(frame, defs.hauteur, volume.a);
      if (at.x >= Math.min(a.x, b.x) && at.x <= Math.max(a.x, b.x) && at.y >= Math.min(a.y, b.y) && at.y <= Math.max(a.y, b.y)
        && at.z >= Math.min(a.z, b.z) && at.z <= Math.max(a.z, b.z)) return "libre";
    }
    return "protege";
  }
  return "hors";
}
