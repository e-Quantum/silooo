// Les textes de la salle d'attente, et seulement eux. Français, tutoiement, phrases courtes, sans emoji
// (design/README.md, « Écrire sans slop »). TOUT CE FICHIER EST « À VALIDER PAR KR » : la liste est dans
// addon/README.md, section « Textes à valider, salle d'attente ». tools/anti_ia.py lit ces chaînes.

/** L'invitation du Discord de Silooo, publique (la même que sur silooo.com/discord/). */
export const DISCORD_INVITATION = "discord.gg/ySnDWk4Dus";

export const ATTENTE_TEXTES = {
  titre: "Silooo",
  sousTitre: "Le serveur ouvre bientôt",
  bienvenue: (pseudo: string) =>
    `§eBienvenue ${pseudo}.§r Silooo n'est pas encore ouvert. En attendant, saute de bloc en bloc : chaque bloc atteint vaut un point, une chute et tu repars de zéro.`,
  invitation: `Deviens Pionnier : rejoins le Discord (§b${DISCORD_INVITATION}§r), puis ouvre la boussole pour obtenir ton code et relier ton compte. Tu seras prévenu de la bêta.`,
  barre: (score: number, record: number) => `Score ${score}   Record ${record}`,
  chute: (score: number) => `Chute à ${score}`,
  nouveauRecord: (score: number) => `Nouveau record : ${score}`,
  classement: "Meilleurs sauts",
  boussole: "Devenir Pionnier",
  transfert: "Tu es sur la liste des testeurs : direction le monde de test.",
} as const;

export const PIONNIER_TEXTES = {
  titre: "Devenir Pionnier",
  instructions: [
    "1. Rejoins le Discord de Silooo :",
    `§b${DISCORD_INVITATION}§r`,
    "2. Obtiens ton code ici.",
    "3. Sur Discord, tape /lier puis ton code.",
    "Tu reçois le grade Pionnier et tu seras prévenu de l'ouverture de la bêta.",
  ].join("\n"),
  code: (code: string) => `Ton code : §l${code}§r`,
  lier: (code: string) => `Sur Discord : /lier ${code}`,
  expiration: "Il reste valable 10 minutes.",
  obtenir: "Obtenir mon code",
  nouveau: "Nouveau code",
  reessayer: "Réessayer",
  fermer: "Fermer",
  serviceAbsent: "Le code n'a pas pu être créé. Réessaie dans un instant.",
  horsService: "Les codes ne sont pas encore disponibles ici. Rejoins le Discord en attendant.",
} as const;
