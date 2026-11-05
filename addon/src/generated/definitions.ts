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
    /** Au-delà de la dernière course listée dans seuils, chaque seuil vaut le précédent multiplié par ce facteur (la lecture chiffrée de etoilesSuivantes). */
    readonly facteurSeuilSuivant: number;
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

// ---- missions.json

/** Identifiants de missions.json, tableau « missions ». */
export type MissionId =
  | "bienvenue-mairie";

/** Forme de definitions/missions.json : le catalogue des missions d'un joueur. Les chiffres vivent dans le fichier de données et dans economie.json, pas ici : le schéma ne fixe que les types et les bornes. */
export type MissionsDefinitions = {
  readonly version: number;
  /** Les missions dans l'ordre où un joueur les fait : la mission en cours est la première qu'il n'a pas terminée. */
  readonly missions: readonly MissionsMission[];
};

export type MissionsMission = {
  /** Identifiant en kebab-case, 48 caractères au plus (colonne player_missions.mission_id). Il ne change plus une fois la mission jouée : c'est la clé du suivi des joueurs. */
  readonly id: MissionId;
  /** L'action du joueur que le service compte. collect : un encaissement qui rapporte du Donar. */
  readonly evenement: "collect";
  /** Nombre d'actions à faire pour terminer la mission. */
  readonly cible: number;
  /** Chemin, dans economie.json, du nombre de Lingots versés à la fin de la mission : une référence, jamais une copie du chiffre. */
  readonly recompense: string;
};

// ---- monde.json

/** Forme de definitions/monde.json. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type MondeDefinitions = {
  readonly version: number;
  /** La grille des archipels dans le monde unique : une case par entreprise, rangées de gauche à droite puis de rangée en rangée. */
  readonly archipel: {
    /** Distance en blocs entre les origines de deux cases voisines, sur x comme sur z : la place réservée à chaque archipel. */
    readonly ecartEntreCases: number;
    /** Nombre de cases d'une rangée (le long de x) avant de passer à la rangée suivante (le long de z). */
    readonly casesParRangee: number;
    /** Coordonnées en blocs de la case d'indice 0. */
    readonly origine: {
      readonly x: number;
      readonly z: number;
    };
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
