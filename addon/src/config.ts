// Réglages techniques de l'add-on : noms lus dans la configuration de BDS, cadences et délais.
// Aucun chiffre du jeu ici. Les chiffres du jeu vivent dans definitions/, les calculs dans silooo-core.
// Les durées sont en secondes ; runtime.ts les convertit en ticks.

/** Noms du secret et des variables posés par le déploiement dans secrets.json et variables.json de BDS. */
export const SETTING_NAMES = {
  secret: "silooo_key",
  coreUrl: "core_url",
  serverId: "server_id",
} as const;

/** Identifiant du serveur quand la variable server_id manque. */
export const DEFAULT_SERVER_ID = "bds-1";

export interface EventsSettings {
  readonly pollIntervalSeconds: number;
  readonly drainDelaySeconds: number;
  readonly pageSize: number;
  readonly maxBackoffSeconds: number;
}

export interface HudSettings {
  /** Secondes entre deux rafraîchissements du HUD. */
  readonly refreshSeconds: number;
  /** Faux : tout le HUD passe par la barre d'action, sans le canal de titre (utile tant que le pack ne le masque pas). */
  readonly titleChannel: boolean;
  /** Durée d'affichage du titre entre deux envois : plus longue que l'intervalle, pour qu'il ne s'éteigne jamais. */
  readonly titleStaySeconds: number;
}

export interface UiSettings {
  /** Identifiant de l'objet qui ouvre le menu, et que le joueur reçoit à son premier join. */
  readonly menuItem: string;
  /** Attente avant de donner l'objet : l'inventaire n'est pas toujours prêt au tout premier tick. */
  readonly menuItemDelaySeconds: number;
  /** Tant que le joueur arrive (île posée, cinématique en cours), la boussole attend : on revérifie à ce rythme... */
  readonly menuItemWaitStepSeconds: number;
  /** ...pendant cette durée au plus, puis on la donne quand même : une cinématique ne doit jamais priver le joueur de son menu. */
  readonly menuItemWaitMaxSeconds: number;
  /** Nouveaux essais d'un formulaire que Bedrock refuse parce que le joueur a déjà un écran ouvert (le tchat, un autre menu). */
  readonly formBusyRetries: number;
  /** Attente entre deux de ces essais. */
  readonly formBusyRetrySeconds: number;
  /** Tuiles au plus par écran de menu (design/composants/Menu.md : grille de 4 sur 2). */
  readonly tilesPerScreen: number;
  readonly hud: HudSettings;
}

/** La pose des îles et la place d'Oooscar. Des réglages techniques : les chiffres de l'archipel sont dans definitions/iles.json. */
export interface WorldSettings {
  /** Secondes entre deux relevés des îles des joueurs présents (usine changée par une action, île donnée après le join). */
  readonly syncSeconds: number;
  readonly productionTickSeconds: number;
  /** Tous les combien on regarde si un joueur est tombé sous les îles (monde/secours.ts). */
  readonly rescueSeconds: number;
  /** Opérations de bloc exécutées d'un coup avant de rendre la main au serveur : une île ne fige pas le jeu. */
  readonly opsPerBatch: number;
  readonly batchPauseSeconds: number;
  /** Première attente après un échec de pose (zone impossible à charger), doublée à chaque échec jusqu'au plafond. */
  readonly buildRetrySeconds: number;
  readonly buildRetryMaxSeconds: number;
  /** Combien de temps l'arrivée attend que la mini-île de départ soit posée, avant de laisser le joueur où il est. */
  readonly arrivalWaitSeconds: number;
  /** Au plus ce temps d'attente avant de téléporter un joueur qui choisit une île sur la carte : le temps qu'elle soit posée. */
  readonly travelWaitSeconds: number;
  /** L'entité d'Oooscar : l'identifiant de pack/entities/oooscar.json. */
  readonly oooscarType: string;
  /** Oooscar apparaît après cette attente, et on réessaie ce nombre de fois : le chunk d'arrivée n'est pas chargé à la seconde où le joueur y arrive. */
  readonly oooscarSpawnRetrySeconds: number;
  readonly oooscarSpawnRetries: number;
  /** Rayon autour de l'arrivée où l'on retire les Oooscar oubliés avant d'en poser un. */
  readonly oooscarSweepRadius: number;
  /** Nom du dossier de ticking area : tous les identifiants de zone commencent ainsi, pour les retrouver. */
  readonly areaPrefix: string;
}

/** La cinématique d'arrivée (design/composants/Cinematique.md). Les plans et leurs durées sont dans definitions/iles.json. */
export interface CinematicSettings {
  /** Intervalle du pas de la cinématique : lecture de l'accroupissement, étapes dues. */
  readonly tickSeconds: number;
  /** Durée où il faut rester accroupi pour passer. */
  readonly skipHoldSeconds: number;
  /** Un sous-titre reste à l'écran au moins ce temps. */
  readonly subtitleMinSeconds: number;
  /** Attente entre le début d'un plan et l'apparition de son sous-titre. */
  readonly subtitleLeadSeconds: number;
  /** Le fondu au noir qui cache une coupe : vers le noir, tenu, puis retour. */
  readonly fadeInSeconds: number;
  readonly fadeHoldSeconds: number;
  readonly fadeOutSeconds: number;
  /** Délai entre la coupe et le début du glissé de la caméra : le temps que la coupe soit prise en compte. */
  readonly moveLeadSeconds: number;
  /** Rythme du rappel « reste accroupi pour passer ». */
  readonly hintRepeatSeconds: number;
}

export interface Config {
  readonly ticksPerSecond: number;
  readonly requestTimeoutSeconds: number;
  readonly readRetries: number;
  readonly startupDelaySeconds: number;
  readonly heartbeatIntervalSeconds: number;
  readonly events: EventsSettings;
  readonly ui: UiSettings;
  readonly world: WorldSettings;
  readonly cinematic: CinematicSettings;
  readonly logPrefix: string;
}

export const config: Config = {
  /** Cadence du tick de Bedrock. */
  ticksPerSecond: 20,
  /** Délai d'une requête vers silooo-core avant abandon. */
  requestTimeoutSeconds: 5,
  /** Nouveaux essais d'une lecture (GET) qui échoue. Une écriture n'est jamais rejouée toute seule. */
  readRetries: 1,
  /** Attente avant le premier heartbeat et la première lecture d'événements, le temps que le monde charge. */
  startupDelaySeconds: 1,
  /** Intervalle entre deux heartbeats. */
  heartbeatIntervalSeconds: 10,
  events: {
    /** Intervalle entre deux lectures de GET /v1/events quand il n'y a plus rien à lire. */
    pollIntervalSeconds: 3,
    /** Attente avant de relire quand la page était pleine : il reste sans doute des événements. */
    drainDelaySeconds: 0.25,
    /** Événements demandés par lecture (le contrat accepte de 1 à 500, 100 par défaut). */
    pageSize: 100,
    /** Plafond de l'attente après des échecs de suite : elle double à chaque échec jusque-là. */
    maxBackoffSeconds: 60,
  },
  ui: {
    menuItem: "minecraft:compass",
    menuItemDelaySeconds: 2,
    menuItemWaitStepSeconds: 0.5,
    menuItemWaitMaxSeconds: 60,
    formBusyRetries: 10,
    formBusyRetrySeconds: 0.5,
    tilesPerScreen: 8,
    hud: {
      /** Une fois par seconde au plus : design/composants/Hud.md. */
      refreshSeconds: 1,
      /** Faux tant que resource-pack/ ne masque pas le préfixe du titre (addon/README.md, section des écrans) : sans lui, le préfixe s'afficherait au milieu de l'écran. */
      titleChannel: false,
      titleStaySeconds: 4,
    },
  },
  world: {
    syncSeconds: 5,
    productionTickSeconds: 0.1,
    rescueSeconds: 0.5,
    opsPerBatch: 8,
    batchPauseSeconds: 0.05,
    buildRetrySeconds: 2,
    buildRetryMaxSeconds: 30,
    arrivalWaitSeconds: 30,
    travelWaitSeconds: 10,
    oooscarType: "silooo:oooscar",
    oooscarSpawnRetrySeconds: 1,
    oooscarSpawnRetries: 5,
    oooscarSweepRadius: 16,
    areaPrefix: "silooo_ile_",
  },
  cinematic: {
    tickSeconds: 0.05,
    skipHoldSeconds: 1,
    subtitleMinSeconds: 3,
    subtitleLeadSeconds: 0.6,
    fadeInSeconds: 0.25,
    fadeHoldSeconds: 0.15,
    fadeOutSeconds: 0.35,
    moveLeadSeconds: 0.1,
    hintRepeatSeconds: 1,
  },
  /** Préfixe des lignes du journal du serveur. */
  logPrefix: "[silooo]",
};
