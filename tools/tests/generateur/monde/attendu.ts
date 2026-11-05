// Fichier généré par tools/generer_types.mjs à partir de definitions/ : ne pas éditer à la main.
// Pour le refaire : node tools/generer_types.mjs
// Pour vérifier qu'il est à jour : node tools/generer_types.mjs --verifier

// ---- monde.json

/** Identifiants de monde.json, tableau « zones ». */
export type MondeZonesId =
  | "plaine"
  | "foret-sombre";

/** Fichier fictif pour tester le générateur : il couvre chaque forme de schéma qu'il sait convertir. */
export type MondeDefinitions = {
  readonly version: number;
  readonly zones: readonly MondeZone[];
  /** Une table : clé libre, valeur typée. */
  readonly etiquettes: { readonly [cle: string]: string };
  readonly mode: "jour" | "nuit";
  readonly reglage?: number | null;
  readonly valeur?: string | number;
  readonly ouvert?: {
    readonly nom?: string;
    readonly [cle: string]: unknown;
  };
  readonly "niveau-max"?: number;
  readonly "version-schema"?: 1;
  readonly notes?: readonly string[];
  readonly tout?: unknown;
};

/** Identifiant en kebab-case. */
export type MondeIdentifiant = string;

export type MondeZone = {
  readonly id: MondeZonesId;
  readonly prix: number;
  readonly voisines?: readonly MondeIdentifiant[];
  /** Facultatif. */
  readonly bonus?: number;
};
