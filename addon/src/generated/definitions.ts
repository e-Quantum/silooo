// Fichier généré par tools/generer_types.mjs à partir de definitions/ : ne pas éditer à la main.
// Pour le refaire : node tools/generer_types.mjs
// Pour vérifier qu'il est à jour : node tools/generer_types.mjs --verifier

// ---- attente.json

/** Forme de definitions/attente.json : le parcours du saut infini, son classement, ses rappels et ses rythmes. Les chiffres vivent dans le fichier de données : le schéma ne fixe que les types et les bornes. */
export type AttenteDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  readonly parcours: {
    /** Hauteur la plus basse d'un bloc. */
    readonly yMin: number;
    /** Hauteur la plus haute d'un bloc. */
    readonly yMax: number;
    /** Le joueur est tombé quand ses pieds passent ce nombre de blocs sous le dernier bloc atteint. */
    readonly chute: number;
    /** Le plus grand trou selon la marche. Au-delà de 4 à plat, un saut n'est plus franchissable à pied. */
    readonly trouMax: {
      readonly monte: number;
      readonly plat: number;
      readonly descend: number;
    };
    /** Les paliers de difficulté, du premier (des 0) au dernier, dans l'ordre croissant de leur score. */
    readonly paliers: readonly {
      readonly des: number;
      readonly chanceVirage: number;
      readonly chanceGrandTrou: number;
    }[];
    /** Blocs par seconde de course au-delà desquels le service refuse la course. */
    readonly plafondParSeconde: number;
  };
  readonly classement: {
    /** Lignes des classements rendus par le service et de la barre latérale (15 au plus, la limite de Bedrock). */
    readonly taille: number;
    /** Le fuseau de la semaine du classement. */
    readonly fuseau: string;
    /** Les dernières actus du site montrées dans le lobby. */
    readonly actus: number;
    /** Lignes du classement de la semaine sur l'hologramme. */
    readonly hologramme: number;
    /** Places du classement de la semaine postées par Oooscar le lundi. */
    readonly podiumDiscord: number;
  };
  readonly rappels: {
    /** Un rappel Discord à chaque multiple de ce score. */
    readonly tousLesPoints: number;
    /** Au plus un rappel par joueur dans cet intervalle. */
    readonly intervalleSecondes: number;
  };
  readonly rythmes: {
    /** Délai du popup d'accueil après l'arrivée. */
    readonly accueilSecondes: number;
    /** Essais du popup quand le jeu répond qu'un écran est déjà ouvert. */
    readonly accueilEssais: number;
    /** Intervalle du contrôle de la liaison Discord des joueurs présents. */
    readonly lienSecondes: number;
    /** Intervalle de relecture du classement et des actus. */
    readonly tableauSecondes: number;
    /** Intervalle du contrôle de la boussole. */
    readonly boussoleSecondes: number;
  };
};

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

/** Identifiants de boutique.json, tableau « cosmetiques ». */
export type ShopCosmeticId =
  | "usine-brique"
  | "usine-acier"
  | "usine-verre"
  | "camion-rouille"
  | "camion-bleu"
  | "camion-dore"
  | "chat-atelier"
  | "corbeau-silo"
  | "renard-ferraille"
  | "lampadaire"
  | "bac-fleurs"
  | "fontaine-cour"
  | "etincelles"
  | "pluie-rivets"
  | "pionnier"
  | "contremaitre"
  | "s1-fanion"
  | "s1-lanterne"
  | "s1-chat-livreur"
  | "s1-sillage-dore"
  | "s1-titre-fondateur";

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
    readonly nom: BoutiqueNomAffiche;
    readonly gemmes: number;
    readonly jours: number;
    readonly bonusDonarPourcent?: BoutiquePourcentage;
    readonly gemmesRendues?: number;
    readonly reserveA?: BoutiqueIdentifiantGrade;
  }[];
  /** Les cosmétiques de la boutique et le titre du pionnier. Un cosmétique non achetable (achetable à faux) n'est pas vendu : le jeu ou le site le donne, et son prix est 0. Les identifiants sont ceux de la colonne player_cosmetics.cosmetic_id et ne recoupent ni un grade, ni un abonnement, ni le boost collectif. */
  readonly cosmetiques: readonly {
    readonly id: ShopCosmeticId;
    readonly nom: BoutiqueNomAffiche;
    /** Le rayon : skin d'usine, skin de véhicule, pet, décor, effet de prestige ou titre. */
    readonly categorie: "skinUsine" | "skinVehicule" | "pet" | "decor" | "effetPrestige" | "titre";
    /** Prix en Gemmes. 0 pour un cosmétique non achetable. */
    readonly prixGemmes: number;
    /** Vrai si la boutique le vend ; faux pour un cosmétique que le jeu ou le site donne (le titre Pionnier). */
    readonly achetable: boolean;
  }[];
  /** Le boost collectif : un joueur l'achète en Gemmes, la production de tout le serveur monte de bonusPourcent pendant dureeMinutes, et son pseudo est annoncé. Un boost acheté pendant un boost actif commence à la fin du précédent : ils ne s'empilent pas. */
  readonly boostCollectif: {
    readonly prixGemmes: number;
    /** Hausse de la production de tout le serveur, en pourcent. Au moins 1. */
    readonly bonusPourcent: number;
    readonly dureeMinutes: number;
  };
};

/** Identifiant en kebab-case : minuscules sans accent, chiffres, tirets. */
export type BoutiqueIdentifiant = string;

/** Identifiant de cosmétique : kebab-case, 48 caractères au plus (colonne player_cosmetics.cosmetic_id). */
export type BoutiqueIdentifiantCosmetique = string;

/** Nom vu par les joueurs : à valider par Kr. */
export type BoutiqueNomAffiche = string;

/** Identifiant de grade : kebab-case, avec un « + » final permis pour les grades calqués sur SileneMC (vip+). */
export type BoutiqueIdentifiantGrade = string;

/** Prix en euros, virgule décimale et deux décimales : « 0,99 ». */
export type BoutiqueEuros = string;

export type BoutiquePourcentage = number;

// ---- discord.json

/** Forme de definitions/discord.json : les délais de l'accueil, les vocaux d'info, les coulisses, l'XP de tchat, les tableaux, les petits jeux, le rapport du lundi, les rappels, les giveaways, les concours, les admissions et l'image d'accueil. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type DiscordDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  /** Le fuseau horaire des heures du fichier et du rapport du lundi, au format de la base IANA. */
  readonly fuseau: string;
  /** Le bonjour d'Oooscar après l'accueil guidé, et les arrivées groupées. */
  readonly accueil: {
    /** Les secondes entre la fin de l'accueil guidé et le bonjour, selon le rôle de plateforme choisi. */
    readonly bonjourSecondes: {
      readonly console: number;
      readonly telephone: number;
      readonly pc: number;
    };
    /** Quand les arrivées se bousculent, un seul bonjour toutes ces secondes. */
    readonly groupeSecondes: number;
    /** Combien de nouveaux au plus un bonjour groupé mentionne ; au-delà, une ligne avec leur nombre. */
    readonly groupeMentionsMax: number;
  };
  /** Le statut d'Oooscar, qui alterne deux lignes. */
  readonly statut: {
    /** Combien de secondes chaque ligne du statut reste affichée. */
    readonly alternanceSecondes: number;
  };
  /** Les vocaux verrouillés en tête du serveur, renommés par le bot ; Discord limite ces renommages. */
  readonly vocauxInfo: {
    /** Combien de renommages au plus par vocal dans la fenêtre. */
    readonly renommagesMax: number;
    /** La fenêtre des renommages, en minutes. Discord en accorde deux par dix minutes. */
    readonly fenetreMinutes: number;
  };
  /** Les coulisses : des spoils déposés à l'avance, publiés quand assez de membres ont réagi au précédent. */
  readonly spoils: {
    /** Le seuil proposé par défaut au dépôt d'un spoil, en part des membres du serveur (0,4 = 40 %). */
    readonly partDesMembres: number;
    /** Toutes ces secondes, le bot met à jour le compte des réactions sous le spoil en cours. */
    readonly miseAJourSecondes: number;
    /** L'emoji du serveur qui compte comme réaction au spoil. */
    readonly emoji: DiscordNomEmoji;
    /** À partir de cette heure, un spoil au seuil attend le matin. */
    readonly nuitDebutHeure: DiscordHeure;
    /** L'heure du matin où les spoils en attente partent. */
    readonly nuitFinHeure: DiscordHeure;
  };
  /** L'XP de tchat : des points par message, des rôles aux niveaux, le classement de la semaine. */
  readonly xp: {
    /** Le moins de points qu'un message compté rapporte. */
    readonly pointsMin: number;
    /** Le plus de points qu'un message compté rapporte ; au moins pointsMin. */
    readonly pointsMax: number;
    /** Un seul message compté par membre dans cet intervalle. */
    readonly intervalleSecondes: number;
    /** Les points de base d'un niveau : le niveau n demande niveauBase × n + niveauCroissance × n × (n - 1) / 2 points. */
    readonly niveauBase: number;
    /** Ce que chaque niveau demande de plus que le précédent. */
    readonly niveauCroissance: number;
    /** Les salons du plan où les messages comptent ; les autres (salons du bot, tchat du jeu) ne donnent rien. */
    readonly salons: readonly DiscordIdentifiantDuPlan[];
    /** Les rôles du plan donnés aux niveaux, du plus bas au plus haut. */
    readonly roles: readonly {
      readonly niveau: number;
      readonly role: DiscordIdentifiantDuPlan;
    }[];
    /** Combien de membres le classement de la semaine cite, le lundi. */
    readonly classementTaille: number;
  };
  /** Le top réactions de la semaine au Panthéon : qui a donné le plus de réactions, une par message. */
  readonly reactions: {
    /** Combien de membres le tableau cite. */
    readonly topTaille: number;
    /** Les salons du plan où les réactions comptent. */
    readonly salons: readonly DiscordIdentifiantDuPlan[];
  };
  /** Un message assez réagi est recopié dans les meilleurs moments. */
  readonly meilleursMoments: {
    /** L'emoji du serveur qui compte. */
    readonly emoji: DiscordNomEmoji;
    /** Le nombre de membres différents, l'auteur et les bots exclus, qui fait recopier le message. */
    readonly seuil: number;
  };
  /** Le petit jeu du monte-charge : un bouton par membre, de temps en temps. */
  readonly monteCharge: {
    /** Les minutes qu'un membre attend entre deux appuis sur « +1 étage ». */
    readonly attenteMinutes: number;
  };
  /** Le rapport du lundi dans le journal du bot. */
  readonly rapport: {
    /** L'heure du lundi où le rapport part. */
    readonly heure: DiscordHeure;
    /** Le rapport compte les arrivants qui ont relié leur compte dans ce délai, en jours. */
    readonly reliesSousJours: number;
    /** Le rapport compte les membres revenus écrire après ce délai, en jours. */
    readonly revenusApresJours: number;
  };
  /** Les rappels privés, tous désactivés au départ. */
  readonly rappels: {
    /** Combien de rappels au plus par membre et par jour. */
    readonly parJour: number;
    /** À partir de cette heure, à l'heure du joueur, aucun rappel sauf les Gemmes reçues. */
    readonly nuitDebutHeure: DiscordHeure;
    /** L'heure où les rappels reprennent. */
    readonly nuitFinHeure: DiscordHeure;
  };
  /** Les giveaways : gratuits, un bouton pour participer. */
  readonly giveaways: {
    /** Combien de giveaways par mois, les grandes étapes en plus. */
    readonly parMois: number;
  };
  /** Les concours d'îles et de villes. */
  readonly concours: {
    /** Combien de concours par mois avant la bêta et le seuil de membres. */
    readonly parMoisAvantBeta: number;
    /** À partir de ce nombre de membres, et une fois la bêta ouverte, un concours par semaine. */
    readonly membresPourHebdo: number;
  };
  /** Les Aides sur candidature. */
  readonly aides: {
    /** À partir de ce nombre de membres, la commande de candidature s'ouvre. */
    readonly candidaturesDesMembres: number;
  };
  /** Les cartes d'admission du salon du staff : les inscriptions de la page d'attente, relues dans la base. */
  readonly admissions: {
    /** Toutes ces secondes, le bot cherche les inscriptions sans carte et en poste une par inscription. */
    readonly relectureSecondes: number;
  };
  /** L'image du message de #bienvenue : celle du jour entre jourHeure et nuitHeure, celle de la nuit le reste du temps, aux heures du fuseau du fichier. */
  readonly bienvenue: {
    /** L'heure où l'image d'accueil passe au jour. */
    readonly jourHeure: DiscordHeure;
    /** L'heure où l'image d'accueil passe à la nuit. */
    readonly nuitHeure: DiscordHeure;
  };
  /** Synchronisation des profils et rôles cosmétiques de prestige. */
  readonly profils: {
    readonly syncSecondes: number;
    readonly laitonDesPrestige: number;
    readonly couleurPrestige: string;
    readonly paliersPrestige: readonly {
      readonly minimum: number;
      readonly role: DiscordIdentifiantDuPlan;
    }[];
  };
};

/** Un identifiant du plan du serveur : minuscules, chiffres et tirets. */
export type DiscordIdentifiantDuPlan = string;

/** Une heure du jour, de 0 à 23, dans le fuseau du fichier. */
export type DiscordHeure = number;

/** Le nom d'un emoji du serveur, sans les deux-points. */
export type DiscordNomEmoji = string;

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
    readonly commandeDuJour: number;
    readonly trouvaille: number;
  };
  readonly commandeDuJour: {
    readonly objectifUnites: number;
    readonly minutesDeProductionParUnite: number;
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
    /** Demi-côté en blocs de l'île principale une fois débloquée (512 × 512, donc 256), autour du même centre : les mini-îles doivent rester en dehors. */
    readonly ilePrincipaleDemiCote: number;
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
    /** Où se tient Oooscar à l'arrivée, par rapport au centre de la mini-île de départ. */
    readonly oooscar: IlesDecalagePlat;
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
    /** L'enseigne murale qui porte le nom de l'usine, au-dessus de sa porte. */
    readonly enseigne: IlesBloc;
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
  /** La cinématique d'arrivée (design/composants/Cinematique.md : 20 secondes, 4 plans), filmée autour de la mini-île de départ. Chaque plan part d'un point, glisse vers un autre et regarde un point fixe ; tous les points sont donnés par rapport au centre de cette mini-île. */
  readonly cinematique: {
    readonly plans: readonly {
      /** Durée du plan en secondes. */
      readonly duree: number;
      /** Où la caméra commence. */
      readonly depart: IlesVue;
      /** Où la caméra finit. */
      readonly arrivee: IlesVue;
      /** Le point que la caméra regarde pendant tout le plan. */
      readonly regard: IlesVue;
    }[];
  };
};

/** Un décalage sur le sol de la mini-île, dans son repère : « avant » va vers le centre de l'île principale, « droite » est à droite quand on regarde vers l'avant. En blocs. */
export type IlesDecalagePlat = {
  readonly avant: number;
  readonly droite: number;
};

/** Un point de la mise en scène, dans le repère de la mini-île : avant, droite (voir decalagePlat) et haut, au-dessus de la face du dessus de la plateforme. En blocs. */
export type IlesVue = {
  readonly avant: number;
  readonly droite: number;
  readonly haut: number;
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

// ---- incidents.json

/** Identifiants de incidents.json, tableau « sortes ». */
export type IncidentKindId =
  | "incendie"
  | "panne"
  | "courroie";

/** Forme de definitions/incidents.json : à quelle cadence un incident survient pendant qu'un joueur est en ligne, les sortes d'incident et leur ralentissement, la récompense du mini-jeu de réparation, et ce que l'électricité demande. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type IncidentsDefinitions = {
  readonly version: number;
  /** La cadence des incidents. Le temps se découpe en créneaux ; à chaque créneau, une entreprise qui a un joueur en ligne tire au sort, une seule fois, si un incident survient. Jamais pendant une absence. */
  readonly frequence: {
    /** Durée d'un créneau, en minutes. Elle doit diviser une journée (1440 minutes) : les créneaux d'un jour UTC se suivent sans reste, et le numéro du créneau fait partie du tirage. */
    readonly creneauMinutes: number;
    /** La chance qu'un incident survienne à un créneau, en pourcent. C'est un tirage gratuit, figé par l'entreprise, le jour et le numéro du créneau : personne ne peut le relancer ni le payer. */
    readonly chancePourcent: number;
  };
  /** Les sortes d'incident (début d'incendie, machine en panne, courroie cassée). Dans l'ordre : le tirage s'y réfère par la position, donc changer l'ordre change les tirages. */
  readonly sortes: readonly IncidentsSorte[];
  /** Ce que donne le mini-jeu de réparation réussi. */
  readonly recompense: {
    /** La récompense, en Donar, vaut ce nombre de minutes du revenu de l'usine réparée, calculé comme si elle n'avait jamais été ralentie. */
    readonly minutesDeRevenu: number;
  };
  /** Ce que l'électricité demande aux usines modernes. */
  readonly electricite: {
    /** À partir de cette époque de l'entreprise, une usine dont l'entrée « electricite » de usines.json est vraie s'arrête quand l'entreprise n'a pas de Centrale active. */
    readonly aPartirDeEpoque: number;
    /** L'identifiant (usines.json) de l'usine qui produit le courant : la Centrale. Elle compte quand elle est active, au niveau 1 au moins. */
    readonly usineCentrale: string;
  };
};

export type IncidentsSorte = {
  /** Identifiant en kebab-case, 16 caractères au plus (colonne factory_incidents.kind). Il ne change plus : c'est la clé que la base et l'add-on gardent. */
  readonly id: IncidentKindId;
  /** Le facteur sur la production de l'usine tant que l'incident dure : 0,5 veut dire moitié moins vite. Strictement entre 0 et 1 : un incident ralentit, il n'arrête ni ne fait rien perdre. Deux décimales au plus (colonne factory_incidents.slowdown). */
  readonly ralentissement: number;
  /** Le poids de cette sorte dans le tirage : une sorte de poids 2 survient deux fois plus souvent qu'une de poids 1. */
  readonly poids: number;
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
  /** L'entreprise partagée : combien de joueurs elle réunit au plus, et combien de temps une invitation reste valable. */
  readonly entreprise: {
    /** Nombre de membres au plus d'une entreprise, PDG compris (au-delà : ENTREPRISE_PLEINE). */
    readonly membresMax: number;
    /** Durée de validité d'une invitation, en heures, depuis le moment où le PDG l'envoie. */
    readonly invitationHeures: number;
  };
};

// ---- pass.json

/** Forme de definitions/pass.json : la saison, le prix de la piste premium, les points que rapportent les actions et les paliers. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. La piste premium ne peut donner qu'un cosmétique : un palier premium avec des Lingots ou des Gemmes est refusé par la forme même du fichier. */
export type PassDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  /** La saison en cours. Une seule à la fois ; en ouvrir une autre se fait en remplaçant ce fichier. */
  readonly saison: {
    /** L'identifiant de la saison, en minuscules et tirets (16 caractères au plus : season_pass.season_id). Il ne change jamais pour une saison commencée. */
    readonly id: string;
    /** Le nom de la saison tel que le joueur le lit. */
    readonly nom: string;
    /** Le premier jour de la saison (jour UTC, AAAA-MM-JJ). */
    readonly debut: string;
    /** Le dernier jour de la saison (jour UTC, AAAA-MM-JJ), au plus tôt le jour du début. */
    readonly fin: string;
  };
  /** La piste premium, qui s'achète en Gemmes dans la boutique en jeu. */
  readonly premium: {
    /** Le prix de la piste premium pour la saison, en Gemmes. Au moins une : un pass premium gratuit n'a pas de sens. */
    readonly gemmes: number;
  };
  /** Les points que rapporte chaque action du joueur pendant la saison. */
  readonly points: {
    /** Les points de chaque associé de l'entreprise quand elle fait un prestige. */
    readonly parPrestige: number;
    /** Les points de chaque joueur qui a livré à la commande du jour quand elle se termine. */
    readonly parCommande: number;
    /** Les points d'une mission terminée. */
    readonly parMission: number;
  };
  /** Les paliers de la saison, du premier au dernier. Le seuil est le total de points du joueur, il croît d'un palier au suivant. */
  readonly paliers: readonly {
    /** Le numéro du palier : 1 pour le premier, puis un de plus à chaque palier. */
    readonly palier: number;
    /** Le total de points du joueur à atteindre pour réclamer ce palier. */
    readonly points: number;
    /** La récompense de la piste gratuite : des Lingots hors prestige. */
    readonly gratuit?: {
      readonly lingots: number;
    };
    /** La récompense de la piste premium : un cosmétique, et rien d'autre. */
    readonly premium?: {
      /** L'identifiant d'un cosmétique de boutique.json (48 caractères au plus : player_cosmetics.cosmetic_id). */
      readonly cosmetique: string;
    };
  }[];
};

// ---- personnel.json

/** Forme de definitions/personnel.json : l'agence d'emploi, les raretés, les rôles, les postes des usines et le chef d'équipe. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type PersonnelDefinitions = {
  readonly version: number;
  /** L'agence d'emploi : les candidats du jour. */
  readonly agence: {
    /** Nombre de candidats proposés chaque jour, 5 au plus : le contrat (hire, chemin « slot ») n'en connaît pas plus. */
    readonly candidatsParJour: number;
    /** Le rôle proposé à chaque place de la liste du jour, dans l'ordre des places : autant d'entrées que de candidats par jour. */
    readonly rolesParPlace: readonly PersonnelRoleAgence[];
    /** Les prénoms que l'agence tire. Textes vus par les joueurs : à valider par Kr. */
    readonly noms: readonly string[];
    /** Les apparences (skinId) que l'agence tire, par rôle. L'add-on les associe à un modèle ; à valider par Kr. */
    readonly apparences: {
      readonly SALARIE: PersonnelApparences;
      readonly CHEF: PersonnelApparences;
    };
  };
  /** Les trois raretés, par code : la répartition des candidats, le bonus, le salaire et le prix. EPIQUE est la rareté que le game design appelle « légendaire ». Le code est une clé et non une valeur : la charte des textes bannit le mot. */
  readonly raretes: {
    readonly COMMUN: PersonnelRarete;
    readonly RARE: PersonnelRarete;
    readonly EPIQUE: PersonnelRarete;
  };
  /** Ce qui change d'un rôle à l'autre : un facteur sur le prix d'embauche et un facteur sur le salaire. */
  readonly roles: {
    readonly SALARIE: PersonnelRole;
    readonly CHEF: PersonnelRole;
  };
  /** Combien de personnel nommé une usine accueille. */
  readonly postes: {
    /** Postes de salarié d'une usine active, quel que soit son niveau. */
    readonly salariesBase: number;
    /** Un poste de salarié de plus tous les N niveaux de l'usine. */
    readonly niveauxParPoste: number;
    /** Postes de chef d'équipe d'une usine active. */
    readonly chefsParUsine: number;
  };
  readonly chefEquipe: {
    /** Heures ajoutées au plafond hors ligne d'une usine qui a un chef d'équipe. */
    readonly plafondHorsLigneBonusHeures: number;
  };
};

/** Un rôle que l'agence propose. */
export type PersonnelRoleAgence = "SALARIE" | "CHEF";

export type PersonnelApparences = readonly string[];

export type PersonnelRarete = {
  /** Le nom que les joueurs voient pour cette rareté (la clé reste COMMUN, RARE, EPIQUE). Texte vu par les joueurs : à valider par Kr. */
  readonly nom: string;
  /** Part des candidats de cette rareté, en pourcent. Les trois parts font 100. */
  readonly repartitionPourcent: number;
  /** Gain de production de l'usine où le membre travaille, en pourcent de la production de l'usine sans lui. */
  readonly bonusProductionPourcent: number;
  /** Salaire par heure, en pourcent de la production de l'usine sans lui (avant le facteur du rôle). */
  readonly salairePourcent: number;
  /** Prix d'embauche, en heures du revenu de l'entreprise (avant le facteur du rôle). */
  readonly coutHeuresDeRevenu: number;
};

export type PersonnelRole = {
  /** Facteur sur le prix d'embauche de la rareté. */
  readonly coutFacteur: number;
  /** Facteur sur le salaire de la rareté. */
  readonly salaireFacteur: number;
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

// ---- ville.json

/** Identifiants de ville.json, tableau « batiments ». */
export type CityBuildingId =
  | "mairie"
  | "route"
  | "maison"
  | "immeuble"
  | "commerce"
  | "garage";

/** Forme de definitions/ville.json : les zones de l'île principale, la mairie, les bâtiments et leurs bonus. Les prix des zones restent dans economie.json (ville.zonesPrixLingots). Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type VilleDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  /** Le quadrillage de l'île principale : un chunk fait blocsParChunk blocs de côté, une parcelle (la place d'un bâtiment) parcelleChunks chunks. */
  readonly ile: {
    /** Blocs sur le côté d'un chunk (16 dans Minecraft). */
    readonly blocsParChunk: number;
    /** Chunks sur le côté d'une parcelle : un bâtiment en occupe une. */
    readonly parcelleChunks: number;
  };
  /** Les zones de la ville : un quadrillage de colonnes × colonnes zones carrées, achetées dans l'ordre de la liste. */
  readonly zones: {
    /** Zones sur un côté de l'île (4 : seize zones). */
    readonly colonnes: number;
    /** Chunks sur le côté d'une zone (8). */
    readonly tailleChunks: number;
    /** Ce qu'il faut pour acheter la première zone, en plus de son prix. */
    readonly premiere: {
      /** Usines actives qu'il faut avoir (la deuxième usine ouvre la ville). */
      readonly usinesActivesMin: number;
    };
    /** Les zones dans l'ordre où on les achète : la première entrée est la zone 1, la deuxième la zone 2. Autant d'entrées que de prix dans economie.json (ville.zonesPrixLingots). */
    readonly ordre: readonly VilleZone[];
  };
  /** La mairie est posée toute seule à l'achat de la première zone. */
  readonly mairie: {
    readonly emplacementDepart: VilleEmplacement;
  };
  /** Ce que rapporte un salarié logé. */
  readonly logement: {
    /** Réduction du salaire d'un membre logé, en pourcent de son salaire. */
    readonly reductionSalairePourcent: number;
  };
  /** Le plus que les bâtiments donnent ensemble pour chaque type de bonus : les bonus restent petits. LOGEMENTS en places, les autres en pourcent. */
  readonly plafonds: {
    readonly LOGEMENTS: number;
    readonly PRODUCTION_DONAR: number;
    readonly LIVRAISON_VITESSE: number;
  };
  /** Le catalogue des bâtiments. Le niveau 1 est celui qu'on pose, les suivants s'achètent un par un. */
  readonly batiments: readonly VilleBatiment[];
};

/** Ce qu'un bâtiment donne. LOGEMENTS : des places pour loger des salariés (leur salaire baisse). PRODUCTION_DONAR : un pourcentage de production de Donar en plus, pour toute l'entreprise. LIVRAISON_VITESSE : un pourcentage de vitesse en plus pour les camions de livraison. Jamais de Lingots. */
export type VilleTypeBonus = "LOGEMENTS" | "PRODUCTION_DONAR" | "LIVRAISON_VITESSE";

export type VilleZone = {
  /** Colonne de la zone, depuis 0 au plus petit x. */
  readonly colonne: number;
  /** Rangée de la zone, depuis 0 au plus petit z. */
  readonly rangee: number;
  /** Niveau de mairie qu'il faut avoir pour acheter la zone. */
  readonly niveauMairieRequis: number;
};

export type VilleEmplacement = {
  /** Blocs depuis le coin de l'île, sur une parcelle entière. */
  readonly x: number;
  /** Blocs depuis le coin de l'île, sur une parcelle entière. */
  readonly z: number;
  readonly rotation: 0 | 90 | 180 | 270;
};

export type VilleBatiment = {
  /** Identifiant en kebab-case, 32 caractères au plus (colonne city_buildings.building_type). Il ne change plus une fois le bâtiment posé chez un joueur. */
  readonly id: CityBuildingId;
  /** Vrai si le joueur le pose avec l'outil de construction ; faux pour la mairie, posée toute seule. */
  readonly posable: boolean;
  /** Combien d'exemplaires une entreprise peut en avoir. */
  readonly quantiteMax: number;
  readonly typeBonus?: VilleTypeBonus;
  /** Un niveau par entrée : la première est le niveau 1. Sans typeBonus, aucun niveau n'a de bonus ; avec lui, tous en ont un. */
  readonly niveaux: readonly VilleNiveau[];
};

export type VilleNiveau = {
  /** Prix en Lingots pour poser le bâtiment (niveau 1) ou pour passer à ce niveau. */
  readonly prixLingots: number;
  /** Niveau de mairie qu'il faut avoir pour poser le bâtiment ou passer à ce niveau. */
  readonly niveauMairieRequis: number;
  /** Le bonus total que donne le bâtiment à ce niveau, dans l'unité de son typeBonus. */
  readonly bonus?: number;
};

// ---- vote.json

/** Identifiants de vote.json, tableau « sites ». */
export type VoteSiteId =
  | "hubyy";

/** Forme de definitions/vote.json : la récompense d'un vote, le bonus de série, la Vote party et les sites de vote connus. Les chiffres vivent dans le fichier de données, pas ici : le schéma ne fixe que les types et les bornes. */
export type VoteDefinitions = {
  readonly version: number;
  readonly commentaire: string;
  /** Ce que donne un vote reçu, en Lingots hors prestige. */
  readonly recompense: {
    /** Les Lingots versés au joueur pour chaque vote reçu. Au moins un : un vote sans récompense n'a pas de sens. */
    readonly lingotsParVote: number;
  };
  /** Le bonus de série : des jours de vote de suite. */
  readonly serie: {
    /** Le nombre de jours UTC de suite où le joueur a voté pour que le vote de ce jour-là donne le bonus. Au moins deux : un seul jour serait chaque vote. */
    readonly jours: number;
    /** Les Lingots de plus pour le vote qui boucle la série, en plus de recompense.lingotsParVote. */
    readonly lingotsEnPlus: number;
  };
  /** La Vote party : un objectif de votes pour tout le serveur, dans un jour UTC. */
  readonly voteParty: {
    /** Le nombre de votes que le serveur doit recevoir dans un jour pour que la Vote party ait lieu. Elle n'a lieu qu'une fois par jour. */
    readonly votesParJour: number;
    /** Les Lingots que reçoit chaque joueur présent au moment où la Vote party a lieu. */
    readonly lingotsParJoueur: number;
  };
  /** Les sites de vote connus. L'identifiant est ce que server_votes.site garde pour un vote venu de ce site ; un service inconnu est accepté tel qu'il s'annonce, car le jeton authentifie déjà l'émetteur. */
  readonly sites: readonly {
    /** L'identifiant du site, en minuscules et tirets (32 caractères au plus). */
    readonly id: VoteSiteId;
    /** Le nom du site tel que le service de vote l'annonce (serviceName). */
    readonly nom: string;
  }[];
};
