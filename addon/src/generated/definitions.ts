// Fichier généré par tools/generer_types.mjs à partir de definitions/ : ne pas éditer à la main.
// Pour le refaire : node tools/generer_types.mjs
// Pour vérifier qu'il est à jour : node tools/generer_types.mjs --verifier

// ---- boutique.json

/** Identifiants de boutique.json, tableau « grades ». */
export type ShopGradeId =
  | "mvip"
  | "vip"
  | "vip+"
  | "heros"
  | "legende"
  | "ultime";

/** Identifiants de boutique.json, tableau « abonnements ». */
export type ShopSubscriptionId =
  | "pilier"
  | "perso";

/** Forme de definitions/boutique.json. Les prix vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type BoutiqueDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  readonly packsGemmes: readonly {
    readonly gemmes: number;
    readonly euros: BoutiqueEuros;
    readonly bonusPourcent: BoutiquePourcentage;
  }[];
  readonly grades: readonly {
    readonly id: ShopGradeId;
    readonly nom: string;
    readonly gemmes: number;
    readonly bonusDonarPourcent: BoutiquePourcentage;
  }[];
  readonly regleGrades: string;
  readonly abonnements: readonly {
    readonly id: ShopSubscriptionId;
    readonly gemmes: number;
    readonly jours: number;
    readonly bonusDonarPourcent?: BoutiquePourcentage;
    readonly gemmesRendues?: number;
    readonly reserveA?: BoutiqueIdentifiantGrade;
  }[];
};

/** Identifiant en kebab-case : minuscules sans accent, chiffres, tirets. */
export type BoutiqueIdentifiant = string;

/** Identifiant de grade : kebab-case, avec un « + » final permis pour les grades calqués sur SileneMC (vip+). */
export type BoutiqueIdentifiantGrade = string;

/** Prix en euros, virgule décimale et deux décimales : « 0,99 ». */
export type BoutiqueEuros = string;

export type BoutiquePourcentage = number;

// ---- economie.json

/** Forme de definitions/economie.json. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type EconomieDefinitions = {
  readonly version: number;
  readonly niveaux: {
    readonly croissancePrix: number;
    readonly formulePrix: string;
    readonly paliersX2: {
      readonly fixes: readonly number[];
      readonly ensuiteTous: number;
      readonly commentaire: string;
    };
  };
  readonly horsLigne: {
    readonly plafondHeures: number;
    readonly efficacite: number;
  };
  readonly prestige: {
    readonly seuils: readonly {
      readonly course: number;
      readonly fin: string;
      readonly donar: number;
    }[];
    readonly etoilesSuivantes: string;
    readonly conversionLingots: {
      readonly coefficient: number;
      readonly diviseurReference: number;
      readonly formule: string;
    };
    readonly productionParEtoile: number;
    readonly bonusParLingotDetenu: number;
    /** L'usine qui est déjà là au début de chaque course, et son niveau ; les autres usines repartent à zéro. */
    readonly departDeCourse: {
      readonly usine: string;
      readonly niveau: number;
    };
  };
  readonly lingotsHorsPrestige: {
    readonly missionBienvenue: number;
    readonly moyenneParHeureDeJeu: number;
  };
  readonly ville: {
    readonly zonesPrixLingots: readonly number[];
  };
  readonly notation: {
    readonly apresMd: string;
    readonly limiteDouble: string;
  };
};

// ---- usines.json

/** Identifiants de usines.json, tableau « usines ». */
export type FactoryId =
  | "carriere"
  | "scierie"
  | "ferme"
  | "mine-profonde"
  | "fonderie"
  | "atelier-outils"
  | "briqueterie"
  | "elevage"
  | "centrale"
  | "verrerie"
  | "menuiserie"
  | "conserverie"
  | "usine-textile"
  | "usine-robots"
  | "cristallerie";

/** Forme de definitions/usines.json. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type UsinesDefinitions = {
  readonly version: number;
  readonly source: string;
  readonly unites: {
    readonly prixNiveau1: string;
    readonly productionParNiveau: string;
  };
  readonly usines: readonly UsinesUsine[];
};

/** Identifiant en kebab-case : minuscules sans accent, chiffres, tirets. */
export type UsinesIdentifiant = string;

export type UsinesUsine = {
  readonly id: FactoryId;
  readonly nom: string;
  readonly epoque: number;
  readonly produit: readonly UsinesIdentifiant[];
  readonly entrees: readonly UsinesIdentifiant[];
  /** Comment l'usine se débloque : au départ, à un niveau d'entreprise, à une époque, ou après une autre usine. */
  readonly decouverte: string;
  readonly prixNiveau1: number;
  readonly productionParNiveau: number;
  readonly electricite: boolean;
};
