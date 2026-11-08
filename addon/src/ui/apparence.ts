// L'habillage des écrans : les couleurs de design/ rendues par les codes de couleur de Bedrock, et les icônes. Aucune
// phrase ici : les mots sont dans textes.ts.
//
// Les monnaies et Oooscar sont des glyphes de la planche du pack (plage privée d'Unicode, voir GLYPH). Les autres
// icônes gardent pour l'instant un repère entre crochets ; c'est ce fichier, et lui seul, qui change. À valider par Kr.

import type { StaffRarity } from "../protocol";

/** Le signe qui ouvre un code de couleur dans un texte Bedrock. */
const SECTION = "§";

/**
 * Les couleurs de design/tokens.json, ramenées au code de couleur de Bedrock le plus proche. Une monnaie garde
 * sa couleur et rien d'autre ne la prend (design/README.md, Couleurs).
 */
export const COLOR = {
  reset: `${SECTION}r`,
  bold: `${SECTION}l`,
  /** `papier` : le texte courant, et la valeur du Donar et des Gemmes. */
  paper: `${SECTION}f`,
  /** `laiton` : le Donar. */
  donar: `${SECTION}6`,
  /** `or-lingot` : les Lingots. */
  lingots: `${SECTION}p`,
  /** `amethyste` : l'icône des Gemmes. */
  gemmes: `${SECTION}u`,
  /** `email-vert-clair` : une réussite, une usine qui tourne. */
  success: `${SECTION}a`,
  /** `email-rouge-clair` : un blocage, un refus. */
  blocked: `${SECTION}c`,
  /** `flamme` : un incident, un arrêt faute de courant. */
  incident: `${SECTION}n`,
  /** `fonte-300` : une légende, une ligne secondaire. */
  muted: `${SECTION}7`,
  /** `fonte` assombrie : une tuile verrouillée. */
  locked: `${SECTION}8`,
} as const;

/** Entoure un texte d'une couleur, puis revient à la couleur courante. */
export function colored(color: string, text: string): string {
  return `${color}${text}${COLOR.reset}`;
}

/**
 * Les glyphes de la planche du pack (resource-pack/font/glyph_E2.png, écrite par tools/generer_glyphes.mjs) : un
 * caractère de la plage privée d'Unicode par image de design/. Le caractère est U+E2<case>, la case vient de
 * design/glyphes.json ; un test vérifie que les deux correspondent. À valider par Kr.
 */
export const GLYPH = {
  donar: "",
  lingot: "",
  gemme: "",
  usine: "",
  carte: "",
  ville: "",
  marche: "",
  boutique: "",
  cadenas: "",
  engrenage: "",
  eclair: "",
  flamme: "",
  etoile: "",
  fermer: "",
  "oooscar-joie": "",
  "oooscar-fier": "",
  "oooscar-oups": "",
  "oooscar-panique": "",
  "oooscar-dodo": "",
} as const;

/**
 * Un repère par icône utilisée. Les icônes dessinées dans design/ ont leur glyphe de la planche ; le prestige prend
 * l'étoile. Objectifs, Amis, Silo, Personnel, Agence, Associés, Pass, Palier, Possédé et Cadeau n'ont pas d'icône dans
 * design/ : ils gardent un repère entre crochets, manque du design system à dessiner (voir addon/README.md).
 */
export const ICON = {
  donar: GLYPH.donar,
  lingots: GLYPH.lingot,
  gemmes: GLYPH.gemme,
  usine: GLYPH.usine,
  engrenage: GLYPH.engrenage,
  flamme: GLYPH.flamme,
  eclair: GLYPH.eclair,
  cadenas: GLYPH.cadenas,
  silo: "[S]",
  carte: GLYPH.carte,
  prestige: GLYPH.etoile,
  objectifs: "[O]",
  amis: "[A]",
  personnel: "[M]",
  agence: "[R]",
  associes: "[N]",
  ville: GLYPH.ville,
  boutique: GLYPH.boutique,
  pass: "[Y]",
  /** Un palier du pass dont tout ce qui peut l'être est réclamé. */
  palierFait: "[F]",
  /** Un palier du pass qui attend d'être réclamé. */
  palierAReclamer: "[K]",
  /** Un article que le joueur possède déjà. */
  possede: "[H]",
  cadeau: "[Q]",
} as const;

/**
 * La couleur du nom d'un membre du personnel selon sa rareté : gris, bleu, jaune (docs/game-design.md : gris, bleu,
 * or). Aucune monnaie ne prend ces couleurs, et le nom de la rareté est toujours écrit à côté : la couleur ne porte
 * jamais l'information seule. À valider par Kr.
 */
export const RARITY_COLOR: Readonly<Record<StaffRarity, string>> = {
  COMMUN: `${SECTION}7`,
  RARE: `${SECTION}9`,
  EPIQUE: `${SECTION}e`,
};

/** Les cinq expressions d'Oooscar, une par situation (design/README.md, Oooscar et le logo). On n'en invente pas d'autre. */
export const EXPRESSIONS = ["joie", "fier", "oups", "panique", "dodo"] as const;

export type Expression = (typeof EXPRESSIONS)[number];

/** Le visage d'Oooscar dans une bulle : son glyphe, une expression par situation. */
export const EXPRESSION_MARK: Record<Expression, string> = {
  joie: GLYPH["oooscar-joie"],
  fier: GLYPH["oooscar-fier"],
  oups: GLYPH["oooscar-oups"],
  panique: GLYPH["oooscar-panique"],
  dodo: GLYPH["oooscar-dodo"],
};
