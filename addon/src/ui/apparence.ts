// L'habillage provisoire des écrans : les couleurs de design/ rendues par les codes de couleur de Bedrock,
// et des repères en texte à la place des icônes. Aucune phrase ici : les mots sont dans textes.ts.
//
// Provisoire : tant que le pack de ressources n'a pas de glyphes pour les icônes de design/icones/ ni d'image
// pour Oooscar, un repère entre crochets tient la place de l'icône. Le jour où le pack les apporte (glyphes dans
// la plage privée d'Unicode), c'est ce fichier, et lui seul, qui change. À valider par Kr.

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
 * Un repère par icône de design/icones/ utilisée. Objectifs, Amis et Silo n'ont pas d'icône dans design/ : leur
 * repère est un manque du design system, à dessiner (voir addon/README.md).
 */
export const ICON = {
  donar: "[D]",
  lingots: "[L]",
  gemmes: "[G]",
  usine: "[U]",
  engrenage: "[T]",
  flamme: "[!]",
  eclair: "[E]",
  cadenas: "[X]",
  silo: "[S]",
  carte: "[C]",
  objectifs: "[O]",
  amis: "[A]",
} as const;

/** Les cinq expressions d'Oooscar, une par situation (design/README.md, Oooscar et le logo). On n'en invente pas d'autre. */
export const EXPRESSIONS = ["joie", "fier", "oups", "panique", "dodo"] as const;

export type Expression = (typeof EXPRESSIONS)[number];

/** Le repère d'une expression dans une bulle, en attendant l'image d'Oooscar du pack. */
export const EXPRESSION_MARK: Record<Expression, string> = {
  joie: "[joie]",
  fier: "[fier]",
  oups: "[oups]",
  panique: "[panique]",
  dodo: "[dodo]",
};
