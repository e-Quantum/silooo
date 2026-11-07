// Les textes de la salle d'attente, et seulement eux. Français, tutoiement, phrases courtes, sans emoji
// (design/README.md, « Écrire sans slop »). TOUT CE FICHIER EST « À VALIDER PAR KR » : la liste est dans
// addon/README.md, section « Textes à valider, salle d'attente ». tools/anti_ia.py lit ces chaînes.
// Couleurs, sur le modèle de l'accueil de Hubyy : §6 l'accent, §7 le gris, §f le blanc, §h la crème du Pionnier.

/** L'invitation du Discord de Silooo, publique (la même que sur silooo.com/discord/). */
export const DISCORD_INVITATION = "discord.gg/ySnDWk4Dus";
export const SITE = "silooo.com";

/** Un nombre de blocs : « 1 bloc », « 12 blocs ». */
function blocs(n: number): string {
  return `${n} ${n > 1 ? "blocs" : "bloc"}`;
}

export const ATTENTE_TEXTES = {
  titre: "Silooo",
  sousTitre: "Ouverture bientôt",
  bienvenue: (pseudo: string) =>
    `§fBienvenue §6${pseudo}§f. Silooo ouvre bientôt. Saute de bloc en bloc : chaque bloc atteint vaut un point, une chute et tu repars de zéro. Le tchat suit le règlement du Discord.`,
  invitation: `§7Relie ton compte Discord (§b${DISCORD_INVITATION}§7) pour devenir Pionnier et être prévenu de la bêta. La boussole ouvre le menu.`,
  barre: (score: number, record: number) => `Score ${score} · Record ${record}`,
  chute: (score: number) => `Chute à ${score}`,
  nouveauRecord: (score: number) => `§6Nouveau record : ${blocs(score)}.`,
  recordDiscord: "§7Ton record compte sur Discord : relie ton compte pour voir ton pseudo au podium du lundi.",
  rappel: (score: number) => `${blocs(score)}. Relie ton compte avec /lier sur Discord.`,
  palier: "Les sauts s'allongent",
  classement: "Meilleurs sauts",
  boussole: "Menu",
  bienvenuePionnier: "Bienvenue, Pionnier",
  compteRelie: "Ton compte Discord est relié",
  nomPionnier: (pseudo: string) => `§hPionnier · ${pseudo}`,
  transfertImpossible: "§7Le monde de test ne répond pas. Réessaie dans un moment.",
  nomServeur: "Silooo · ouverture bientôt",
} as const;

export const ACCUEIL_TEXTES = {
  titre: "Silooo",
  bonjour: (pseudo: string) => `§fBonjour §6${pseudo}§f.`,
  rebonjour: (pseudo: string) => `§fContent de te revoir, §6${pseudo}§f.`,
  cestQuoi: "§7Silooo est un jeu d'usines dans le ciel, encore en chantier.\n§7En attendant l'ouverture, bats ton record de saut.",
  pionnier: "§lDevenir Pionnier§r\n§7Ton code pour Discord",
  dejaPionnier: "§lTu es Pionnier§r\n§7Tu seras prévenu de la bêta",
  sauter: "§lSauter§r\n§7Reprendre le parcours",
  rejoindre: "§lRejoindre Silooo§r\n§7Le monde de test",
  infos: "Dernières infos",
  aucuneInfo: "Rien de neuf pour l'instant.",
  classement: "Le classement",
  semaine: "cette semaine",
  ligne: (rang: number, pseudo: string, score: number) => `§6${rang}. §f${pseudo} §7${score}`,
  tonRang: (rang: number) => `§7Ton rang : §f${rang}`,
  aucunSaut: "Personne n'a encore sauté cette semaine.",
  pionniers: (n: number) => `§7Déjà §f${n}§7 ${n > 1 ? "pionniers" : "pionnier"}.`,
  horsLigne: "§7Le classement revient dans un instant.",
} as const;

export const HOLOGRAMME_TEXTES = {
  silooo: "§l§6SILOOO§r\n§fOuverture bientôt",
  liens: `§f${SITE}\n§b${DISCORD_INVITATION}`,
  infos: "§l§6Dernières infos§r",
  semaine: "§l§6La semaine§r",
  ligne: (rang: number, pseudo: string, score: number) => `§6${rang}. §f${pseudo} §7${score}`,
  aucunSaut: "§7Personne encore",
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
