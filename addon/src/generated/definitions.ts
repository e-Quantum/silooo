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

// ---- iles.json

/** Identifiants de iles.json, tableau « usines ». */
export type IlesUsinesId =
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

/** Forme de definitions/iles.json : où l'add-on pose les mini-îles des usines autour de l'île principale, et de quoi elles sont faites. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type IlesDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  /** Altitude (y) de la face du dessus des plateformes, en blocs. Les joueurs se tiennent un bloc au-dessus. */
  readonly hauteur: number;
  /** Décalage en blocs du centre de l'île principale par rapport à l'origine de la case (CompanyState.island), qui est le coin de la case au plus petit x et au plus petit z. Une case fait ecartEntreCases de côté (monde.json) : le centre en est le milieu. */
  readonly centreDansLaCase: IlesPoint;
  /** Où se tiennent les mini-îles : sur un carré autour du centre de l'île principale, qui fait 512 × 512 blocs une fois débloquée. */
  readonly anneau: {
    /** Distance en blocs du centre au carré sur lequel se tiennent les centres des mini-îles, mesurée le long de x ou de z : au moins 256 pour rester hors de l'île principale, au plus la moitié de la case moins le rayon d'une plateforme. */
    readonly demiCote: number;
  };
  /** Le sol d'une mini-île : un disque, en couches de plus en plus étroites vers le bas. */
  readonly plateforme: {
    /** Rayon en blocs de la couche du dessus. */
    readonly rayon: number;
    /** De haut en bas. La première couche est la face du dessus, à l'altitude « hauteur ». */
    readonly couches: readonly {
      readonly materiau: "herbe" | "terre" | "pierre";
      /** Blocs de rayon en moins que la couche du dessus. */
      readonly retrait: number;
    }[];
  };
  /** Le point où le joueur apparaît, sur la mini-île de départ. */
  readonly apparition: {
    /** L'usine dont le joueur apparaît sur l'île : sa Carrière. */
    readonly usine: IlesIdentifiant;
    /** Distance en blocs du centre de la mini-île, du côté de l'île principale, hors du bâtiment. */
    readonly distance: number;
  };
  /** Ce qui se voit d'une usine en ruine. */
  readonly ruine: {
    /** Part des blocs de mur qui manquent ou sont remplacés par des gravats. */
    readonly partMursAbimesPourcent: IlesPourcentage;
    /** Part de la face du dessus où l'herbe est arrachée. */
    readonly partSolAbimePourcent: IlesPourcentage;
  };
  /** Les blocs de Minecraft qui jouent la palette de design/decor/ (herbe, terre, pierre, planches) et les détails. */
  readonly materiaux: {
    readonly herbe: IlesBloc;
    readonly terre: IlesBloc;
    readonly pierre: IlesBloc;
    readonly planches: IlesBloc;
    /** Le toit d'une usine active. */
    readonly toit: IlesBloc;
    /** Ce qui reste d'un mur de pierre abîmé. */
    readonly gravats: IlesBloc;
    /** Ce qui reste d'un mur de planches abîmé, et le sol arraché. */
    readonly mousse: IlesBloc;
    /** Les poteaux d'un chantier (usine en remise en état). */
    readonly echafaudage: IlesBloc;
    /** La lanterne d'une usine active. */
    readonly lumiere: IlesBloc;
    readonly vide: IlesBloc;
  };
  /** Un emplacement et un bâtiment par usine du catalogue (usines.json). Le bâtiment dépend de l'identifiant d'usine. */
  readonly usines: readonly {
    readonly id: IlesUsinesId;
    /** Direction du centre de l'île principale vers cette mini-île, en degrés : 0 vers l'est (x croissant), 90 vers le sud (z croissant), donc dans le sens des aiguilles d'une montre vu d'en haut. */
    readonly angle: number;
    readonly murs: "planches" | "pierre";
    /** Taille du bâtiment le long de x, en blocs, impaire pour que la porte tombe au milieu. */
    readonly largeur: number;
    /** Taille du bâtiment le long de z, en blocs, impaire. */
    readonly profondeur: number;
    /** Hauteur des murs en blocs, au moins 3 pour la porte et l'enseigne. */
    readonly hauteur: number;
  }[];
};

/** Identifiant en kebab-case : minuscules sans accent, chiffres, tirets. */
export type IlesIdentifiant = string;

/** Identifiant d'un type de bloc de Minecraft. */
export type IlesBloc = string;

export type IlesPourcentage = number;

export type IlesPoint = {
  readonly x: number;
  readonly z: number;
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
