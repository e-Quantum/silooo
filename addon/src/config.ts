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

export interface Config {
  readonly ticksPerSecond: number;
  readonly requestTimeoutSeconds: number;
  readonly readRetries: number;
  readonly startupDelaySeconds: number;
  readonly heartbeatIntervalSeconds: number;
  readonly events: EventsSettings;
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
  /** Préfixe des lignes du journal du serveur. */
  logPrefix: "[silooo]",
};
