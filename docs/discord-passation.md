# Passation du Discord : l'état du chantier

Dernière mise à jour : 6 novembre 2026, reprise des correctifs D2 sur le VPS.

### Reprise du 5 novembre 2026 sur le VPS

Kr a demandé de reprendre le bot, de corriger les erreurs et les permissions du serveur Silooo, puis de poursuivre le
plan en autonomie avec plusieurs agents. Son accord couvre les opérations sur ce serveur Discord avec le bot et la
livraison du code Silooo ; les autres projets du VPS restent hors périmètre. La priorité est l'accueil de nouveaux
membres. Cette reprise part de `main` à `f061665`, avec des worktrees séparés par lot.

- La livraison examinée sur le VPS est `5dcc5ae`. Les sources de la session précédente ont été récupérées sans les
  réécrire. Le défaut de l'ordre des options de `/giveaway` est toujours présent dans cette base.
- L'audit API a confirmé que l'accueil guidé est désactivé, qu'aucune des quatre commandes n'est installée pour cette
  application sur le serveur, et que les quatre règles AutoMod sont actives.
- Les trois salons internes avaient leur propre overwrite d'écriture, sans refus de lecture à `@everyone` : la
  catégorie ne suffisait pas à les protéger. Une correction ciblée de la visibilité a été appliquée et relue par
  l'API : journal et admissions pour Staff et Modération, signalements pour Modération. La correction durable du
  réconciliateur est intégrée, avec des tests de dérive et de second passage. Les refus de confidentialité
  empêchent le démarrage des fonctions qui écrivent des données internes.
- **V12 est réservée au rapport hebdomadaire du Discord** : historique minimal des faits comptés et reprise sans
  doublon après redémarrage. Elle est écrite, en vérification avant livraison. Les autres tranches prennent V13 et la suite.
- Lots en cours : permissions et accueil guidé ; commandes et contrôles du staff ; rapport du lundi et parcours
  d'accueil. La vérification Gradle et l'intégration sont centralisées, un seul Gradle à la fois sur le VPS.
- Les nouveaux textes d'accueil et du rapport restent **à valider par Kr**. Les cases de recette réelle et la mise
  en ligne ne sont pas cochées sur la seule présence du code.

### Reprise du 6 novembre : intégration

- `/giveaway` place désormais `duree` avant les options facultatives. `CommandSpec` refuse tout ordre invalide ;
  le catalogue réel des quatre commandes est converti par JDA dans les tests.
- Les actions staff refusent une configuration sans rôle Staff et la publication d'une annonce revérifie le rôle.
- Les permissions de lecture, écriture et connexion sont combinées dans un seul overwrite lors de la création.
  Le plan réconcilie les droits existants et vérifie les droits effectifs des salons de l'accueil guidé ; les vocaux
  en sont exclus. Le salon médias existant est repris, sans suppression de salons.
- `QgInfoFeature` remplit le salon officiel des règles et `rejoindre`, avec des messages à clé modifiés au redémarrage.
  Le texte d'attente annonce le jeu fermé ; bienvenue ne prétend plus qu'on joue depuis Discord.
- Le rapport du lundi mesure les événements reçus seulement. Liaison rapide, provenance et signalements restent
  explicitement indisponibles. Une réservation SQL évite les doublons ; un envoi refusé est marqué `failed`,
  journalisé et demande une reprise manuelle. Un arrêt après réservation peut laisser `attempting`.
- Le rôle Entrepreneur est donné aux arrivants humains. Le tuto ATTENTE explique le chantier et l'inscription
  pionnier ; les instructions Minecraft sont conservées pour la bêta et l'ouverture.
- Setup Claude fourni par Kr : règles de consommation appliquées (lectures ciblées, sorties limitées, un agent
  pour le rapport). Les scripts Windows, hooks et connexions privées ne sont pas installés sur le VPS.
- **À valider par Kr** : règles du QG, rejoindre, bienvenue, tuto et formulations d'indisponibilité du rôle Staff,
  ainsi que le rapport. Aucun visuel D1 nouveau ; aucune case D3 à D6 déclarée terminée.
- Vérification et déploiement : voir le journal de livraison ajouté après les contrôles réels.

Ce fichier est tenu par la session Discord. Il ne remplace pas [passation.md](passation.md), qui reste à la direction
du jeu, ni [site-passation.md](site-passation.md), qui reste à la direction du site : ici, seulement le Discord de
Silooo, décrit dans [discord.md](discord.md). Les corrections de la critique du plan priment sur les étapes ; chacune
est recopiée sous l'étape qu'elle touche.

## État

- La session Discord a ouvert le 5 novembre 2026 à 15 h, dans le worktree `silooo-discord` (branche
  `claude/discord-docs`, partie de `main` à `b983993`). Elle a lu `CLAUDE.md`, `docs/README.md`, `docs/passation.md`,
  `docs/site-passation.md`, le cadrage, le plan avec sa critique, et les cinq maquettes de la page « Discord Silooo ».
- Le socle du bot est dans `core/` (branche `claude/discord-d0`) : le paquet `discord/` (ADR 0007, JDA 6.7.0 derrière
  l'interface `DiscordServer`, un faux pour les tests), le plan `core/src/main/resources/discord/plan-serveur.json` avec
  son schéma et son vérificateur, les quatre variables `SILOOO_DISCORD_ACTIF`, `SILOOO_DISCORD_TOKEN`,
  `SILOOO_DISCORD_SERVEUR`, `SILOOO_DISCORD_PHASE` (`core/README.md`, « Le Discord »). Le site a déjà
  `SILOOO_DISCORD_CLIENT_ID`, `SILOOO_DISCORD_CLIENT_SECRET`, `SILOOO_SITE_URL_DISCORD`, le paquet `registration/`
  (`DiscordIdentity`) et la table `site_registrations` (V7).
- Le bot n'a encore jamais parlé à Discord : le plan s'applique au faux Discord dans les tests. Depuis la décision de Kr
  du 5 novembre à 19 h 57 (« Décisions de Kr »), il n'y a qu'un serveur, Silooo, fermé au public jusqu'à l'ouverture du
  QG ; le bot y travaille directement, depuis le PC de Kr, à blanc d'abord. Ce qu'il manque pour le lancer est dans
  « Lancer le bot depuis le PC ».
- `CLAUDE.md` ne contient pas l'exception Discord du plan : la session n'agit sur Discord que par le bot, sur le serveur
  Silooo, et tout réglage que l'API ne permet pas va dans « À faire par Kr » avec sa marche à suivre.
- D0 est sur `main` ; il en reste le plan appliqué au serveur Silooo (après Kr) et la mesure mémoire connectée.
- Étape en cours : **D2**. Son socle est écrit (branche `claude/discord-d2`) : les événements Discord sans JDA
  (`DiscordEvent`, `Reply`), les messages du bot (`BotMessage` : texte, image, boutons, menu, carte à barre ciel,
  formulaires, commandes), la boucle à un fil (`BotRuntime` : file bornée, minuteries, fonctions `Feature` isolées
  les unes des autres), `DiscordServer` élargi (écrire, modifier, message privé, rôles, renommer, statut, commandes),
  `jda/JdaMessages` et `jda/JdaEvents` (intentions membres, messages, contenu, réactions, vocaux ; cache des 500
  derniers membres et des membres en vocal). Le bot ne boucle que si des fonctions lui sont données et hors `a-blanc`.
  Les fonctions du QG viennent case par case, avec `definitions/discord.json` pour leurs chiffres et la migration V10
  pour leurs tables.
- L'accueil guidé est écrit : la clé `accueil` du plan (deux questions, neuf réponses, chacune vers un rôle), posée en
  REST par `OnboardingApplier` et `discord/rest/DiscordRest` une fois les salons créés, avec pour salons par défaut les
  salons publics de la phase. Le Guide du serveur n'a pas d'API : `docs/discord-guide.md` dit ce que Kr pose (mode
  Communauté, mot d'accueil, tâches, trois rubriques) et le brief des trois plaques pour D1.
- Le bonjour d'Oooscar, le tuto en message privé, les arrivées groupées, le statut et le vocal d'info « N en ligne » sont
  écrits (`Welcome.kt`, `Status.kt`, `InfoVoice.kt`, `MemberStore.kt`) et branchés en production par `BotFeatures` dans
  `Main.kt`, avec les joueurs en ligne de `ServerPresence`. Ils n'ont jamais été vus tourner sur un vrai serveur : le bot
  n'a été lancé sur aucun serveur dans cette session, seuls les faux Discord des tests les ont joués.
- Les coulisses sont écrites et branchées (`SpoilStore`, `JdbcSpoilStore`, `SpoilFeature`, `/spoil`, `Feature.commands` pour
  poser les commandes) : jamais vues sur un vrai serveur. Le code JDA des pièces jointes (`JdaEvents`) et des réactions
  (`JdaServer.reactors`) compile mais ne se teste que sur le serveur Silooo.
- Les admissions du staff sont écrites et branchées (`AdmissionStore`, `JdbcAdmissionStore` sur `discord_admissions`,
  `AdmissionFeature`, `SiteRegistrationStore.admit`) : toutes les 60 secondes, une carte par inscription de la page
  d'attente dans `#✅・admissions`, avec Admettre, Refuser et Plus tard ; Admettre pose `admitted_at`, puis donne le rôle
  Bêta et écrit l'invitation en privé si le Discord est relié. Jamais vues sur un vrai serveur. Sans liaison, le rôle et
  l'invitation attendent `/lier` (D3).
- Le journal du bot (`BotJournal`) est la façon commune d'écrire une ligne dans `#📒・journal-du-bot` : les admissions
  et `/annonce` y notent chaque geste du staff, et un refus de Discord n'y casse pas le geste.
- Le garde anti-IA existe en Kotlin (`wording/AntiIa.kt`, `AntiIa.check` et `AntiIa.summary`, mêmes règles et mêmes noms
  que `tools/anti_ia.py`, `AntiIaTest` : 59 cas dont ceux de `tools/tests/anti-ia/`). Il relit les annonces avant
  l'aperçu.
- `/annonce` est écrite et branchée (`AnnounceFeature`) : titre, texte, image, mention ; le garde anti-IA, l'aperçu pour
  le staff seul, Publier et Annuler. Elle ne publie pas encore dans les Actus du site (décision 31). Jamais vue sur un
  vrai serveur.
- Les tickets et « Crée ton vocal » sont écrits et branchés (`BotMessageStore` et `JdbcBotMessageStore` sur
  `discord_bot_messages`, `TicketStore` et `JdbcTicketStore` sur `discord_tickets`, `TicketFeature`, `VoiceRoomFeature`) :
  le menu de `#🎫・tickets` retrouvé ou posté au démarrage, un salon privé par ticket, archivé dans le journal puis supprimé
  à la fermeture ; un vocal « Vocal de Nom » créé quand un membre entre dans « Crée ton vocal », supprimé quand il se vide.
  Le bot a besoin des droits Gérer les salons, Déplacer les membres et Lire l'historique des messages. Jamais vus sur un
  vrai serveur.
- L'XP de tchat est écrite et branchée (`XpStore` et `JdbcXpStore` sur `discord_chat_xp` et `discord_xp_messages`,
  `XpFeature`, `/niveau`) : 15 à 25 points par message et par minute, les rôles aux niveaux 5, 10, 20 et 30, le
  classement de la semaine publié le lundi à l'heure du rapport. Jamais vue sur un vrai serveur.
- Les giveaways sont écrits et branchés (`GiveawayStore` et `JdbcGiveawayStore` sur `discord_giveaways`,
  `discord_giveaway_entries` et `discord_prizes`, `GiveawayFeature`, `/giveaway`) : un par mois, Participer, le tirage à
  la date dite, le gagnant et le lot en base avant tout message. Le lot n'est pas crédité en jeu (`delivered_at` nul) : il
  attend une route du contrat (« Pour la direction du jeu », 9). Règlement en brouillon dans
  `docs/discord-reglement-giveaways.md`. Jamais vus sur un vrai serveur.
- Le socle du bot s'est élargi pour ces fonctions : `ServerIds.categories` et `category(planId)` (remplis par
  `PlanApplier`, `PlanReport.categoryIds`), `ChannelSpec.memberViewers`, `ChannelMessage`, `DiscordServer.deleteChannel`,
  `history`, `moveToVoice`, `voiceMembers` et `memberName`, `MessagePosted.parentChannelId`. Le code JDA correspondant
  compile mais ne se teste que sur le serveur Silooo. Jamais vu sur un vrai serveur.
- L'AutoMod, `#bienvenue`, les quatre tableaux et les deux petits jeux sont écrits et branchés dans `BotFeatures` :
  `Automod.kt` (quatre règles posées par l'API à chaque démarrage, listes dans `core/src/main/resources/discord/automod.json`),
  `Bienvenue.kt` (la carte de `#bienvenue` et l'icône du serveur), `Reactions.kt`, `Highlights.kt` et `Help.kt` (top
  réactions, meilleurs moments, Aide du mois, avec `ReactionStore`, `HighlightStore` et `HelpStore`), `Chantier.kt` (le
  chantier en direct), `Counting.kt`, `Elevator.kt` et `Records.kt` (comptage, monte-charge, records au Panthéon, avec
  `GameStore`), `Boards.kt` (`LiveMessage`, le message à clé que le bot modifie en place). Les chiffres sont dans le bloc
  `bienvenue` et les blocs existants de `definitions/discord.json` ; les tables sont celles de V10. Le socle grandit de
  neuf méthodes (décision 47). La suite `com.silooo.core.discord.*` est verte (40 classes de tests). Les images de D1
  (`bienvenue-jour.png`, `bienvenue-nuit.png`, `icone-serveur.png`, les étages du monte-charge) et les emojis `:donar:` et
  `:prestige:` manquent encore : le bot s'en passe et le dit au journal. Jamais vus sur un vrai serveur.
- Le serveur fermé est tenu par le code (décision 59) : `ClosedServer` et la variable `SILOOO_DISCORD_PUBLIC` retiennent
  les messages privés aux membres hors staff et réduisent les mentions de rôle au staff et à la modération tant que le QG
  n'est pas ouvert. Codés en même temps que ce texte par un agent Sonnet (`core/`) ; jamais joués sur un vrai serveur.
- Tâche suivante : le premier passage à blanc sur le serveur Silooo, dès que les variables du compte sont visibles et
  qu'une base est là (« Lancer le bot depuis le PC ») ; puis le rapport du lundi dans `#journal-du-bot`, la passation et
  la fusion sur main ; ensuite D3.
- Numéros : l'ADR **0007** est écrit (`docs/adr/0007-bot-discord-jda.md`, à valider par Kr) ; la migration **V10**
  est prise (`V10__discord.sql`, toutes les tables du QG ; V8, réservée au départ, reste un trou : V9 est partie sur `main` avant). Le jeu prend V11 et la suite ; une table du Discord qui change
  prendra le prochain numéro libre, annoncé ici.

## Lancer le bot depuis le PC

Décidé par Kr le 5 novembre 2026 (« Décisions de Kr », point 2) : le bot se lance depuis le terminal de Kr, sur le serveur
Silooo, avec la base dans Docker Desktop. La session a écrit [`tools/bot-pc.ps1`](../tools/bot-pc.ps1) pour que tout tienne
dans une commande.

**Ce que fait Kr.**

1. Démarrer Docker Desktop et attendre que son icône soit verte.
2. Ouvrir un nouveau terminal PowerShell, après la pose des variables du compte (`SILOOO_DISCORD_TOKEN` et
   `SILOOO_DISCORD_SERVEUR`), se placer à la racine du dépôt et lancer `.\tools\bot-pc.ps1`. C'est le passage à blanc : le
   bot lit le serveur et écrit l'écart avec le plan, sans rien changer. Ctrl+C l'arrête.
3. Lire le compte rendu dans `#📒・journal-du-bot` s'il existe, sinon dans la sortie du service, puis seulement lancer
   `.\tools\bot-pc.ps1 -Mode oui`, qui applique le plan.

**Ce que fait le script.** Il vérifie que Docker Desktop répond et que les deux variables du compte sont posées dans le
terminal, sans jamais les afficher. Il crée ou redémarre le conteneur `silooo-mariadb` (image `mariadb:11`, base et
utilisateur `silooo`, port 3306 sur 127.0.0.1 seulement), attend que la base réponde, applique les migrations
(`gradlew run --args=migrate`), puis lance `silooo-core` avec le bot (`gradlew run`) en posant `SILOOO_DISCORD_ACTIF`
(`-Mode a-blanc` par défaut, ou `oui`), `SILOOO_DISCORD_PHASE` (`-Phase`, `attente` par défaut) et `SILOOO_DISCORD_PUBLIC`
(`-Public`, `non` par défaut). Le mot de passe de la base locale est tiré au premier lancement et gardé hors du dépôt, dans
`%LOCALAPPDATA%\Silooo\mariadb-pc.txt` ; la clé d'API (`SILOOO_API_KEY`) est tirée à chaque lancement. `-Base` s'arrête
après les migrations, pour les essais de la session. Cette base locale ne sert qu'aux essais : à la passation au VPS, c'est
la base `silooo` de SileneMC qui compte, et ce que le bot a écrit sur Discord pendant les essais reste (d'où « Essais à
nettoyer avant l'ouverture »).

**Ce qui n'est pas vérifié.** Le script n'a tourné que sans Docker, où son premier garde-fou l'arrête : le conteneur, les
migrations et le lancement n'ont jamais été joués par lui, et le bot n'a jamais parlé au vrai Discord. La session n'a pas pu
démarrer Docker Desktop elle-même (refus du classificateur de permissions) : le premier lancement complet est à faire par
Kr.

**Les variables du compte.** La session ne peut pas les lire (une variable du compte Windows n'est lue qu'au démarrage d'un
processus) et ne les affiche jamais ; seul le script, dans le terminal de Kr, vérifie qu'elles sont posées.

**Côté Discord**, les conditions (intentions privilégiées, droits du bot, mode Communauté) sont celles de « À faire par
Kr » : elles se règlent avant le premier lancement.

## Les étapes

La session enchaîne D0, D2, puis D3 à D6 ; D1 avance en parallèle, pendant les rendus. Une étape qui attend Kr ou la
direction du jeu n'arrête pas les autres : sa case va dans « À faire par Kr » ou « Pour la direction du jeu ». Au début
de chaque étape, la session relit ses cases et ajoute celles qui manquent.

### D0 : le socle

Critère de fin : le plan s'applique au faux Discord, puis au serveur Silooo (à blanc, puis pour de vrai), et
`tools/verifier.sh` est vert.

- [x] Premier tour, branche `claude/discord-docs` : `docs/discord.md`, ce fichier, une ligne dans `docs/README.md` et
  dans « Où lire » de `CLAUDE.md` ; vérificateur vert, rebase, fusion en avance rapide, poussée
- [x] ADR 0007 : JDA 6.7.0 (Components V2 depuis la 6.0, une version par mois, prête pour le changement des salons
  d'interaction du 16 novembre 2026), sans le module audio ; Kord 0.18.1 écarté (sans version depuis mars, Ktor 3.4.0) ;
  l'accueil guidé s'écrira en un appel REST avec le client Ktor du service ; la règle « au-delà de 96 Mo, le bot devient
  un processus à part » (`docs/adr/0007-bot-discord-jda.md`, à valider par Kr)
- [x] Paquet `discord/` derrière l'interface `DiscordServer` (`discord/jda/` seul à importer JDA), `FakeDiscordServer`
  pour les tests ; le bot ne démarre que si `SILOOO_DISCORD_ACTIF` vaut `oui` ou `a-blanc` (`non` par défaut) ;
  `SILOOO_DISCORD_TOKEN` et `SILOOO_DISCORD_SERVEUR` lus dans l'environnement, le jeton jamais affiché et masqué dans
  les journaux (`BotToken`) ; le bot tourne dans son fil, toute panne s'écrit dans les journaux sans arrêter le jeu ni
  le site (`DiscordBotTest`) ; `BoundedQueue`, la file bornée des images (critique, point 4)
- [x] `core/src/main/resources/discord/plan-serveur.json` et `plan-serveur.schema.json` : 23 rôles, 9 catégories,
  32 salons, chacun avec sa phase ; `PlanApplier` crée ce qui manque, remet un sujet ou une couleur qui a dérivé, ne
  supprime jamais rien, écrit son compte rendu dans `#📒・journal-du-bot` ; à blanc (`a-blanc`), il écrit l'écart sans
  rien changer, sauf le salon du journal s'il manque
- [x] `tools/verifier.sh` : étape « Discord : plan du serveur » (`tools/verifier_plan_discord.mjs` : schéma, unicité,
  rôles cités, un seul emoji par salon et jamais dans le nom, couleurs de `design/tokens.json`, journal) ; `anti_ia.py`
  lit le plan et les littéraux du paquet `discord/`, la clé `emoji` exceptée ; dix cas refusés dans `tools/tests/run.sh`
- [x] La mémoire au chargement : JDA chargé sans connexion coûte 51 Ko de tas et 318 Ko hors tas (classes), JVM 17.0.12
  (`DiscordMemoryTest`, chiffre écrit dans `core/build/mesure-memoire-discord.txt`), loin du budget de 96 Mo
- [ ] La mémoire connectée : la JVM mesurée avec le bot sur le serveur Silooo, à blanc, chiffre ici (**après Kr**)
- [x] Le plan appliqué au faux Discord (`PlanApplierTest` : attente, bêta après attente, à blanc, refus isolé, dérive)
- [x] Le plan appliqué au serveur Silooo, à blanc puis pour de vrai (**après Kr** : les intentions privilégiées et les
  droits du bot sur le serveur Silooo, le mode Communauté ; puis le compte rendu à blanc lu dans `#📒・journal-du-bot`)
  - À blanc : fait le 5 novembre 2026 vers 21 h 30, compte rendu lu par Kr (19 rôles, 8 catégories et 21 salons
    manquants, rien de changé sauf le salon du journal). Pour de vrai : fait vers 21 h 55, en `-Mode oui` depuis le PC
    de Kr ; la base locale montre les sept cartes posées (voir « Essais à nettoyer avant l'ouverture »). Le compte rendu
    de ce lancement (refus de Discord, mode Communauté) reste à lire par Kr dans `#📒・journal-du-bot`.

### D1 : les visuels

Critère de fin : Kr a validé l'avatar, l'icône, les stickers, les emojis et les deux bannières, et les exports sont dans
`design/discord/`. Règle de la critique (point 5) : deux familles d'images, les illustrations en 3D sous Blender à la
manière de Silooo Atelier, les images en pixel 2D tirées de `design/` sans lissage. La version sans boost d'abord
(point 10). Kr est prévenu avant chaque prise de son PC pour un rendu, avec la durée.

- [x] La marque `silooo` dans les outils de rendu (`marque.md`, `brand_overlay`), sans toucher aux deux autres marques
  (sous-agent du 5 novembre, vérifié par la session : fiche Silooo dans `references/marque.md`, entrée `silooo` dans
  `MARQUES`, logo et polices lus dans le dépôt silooo, rien copié, scripts compilés ; voir « À valider par Kr »)
- [ ] Un rendu de contrôle SileneMC refait après, signalé à Kr (le PC de Kr : prévenu d'abord, voir « À faire par Kr »)
- [ ] Oooscar en 3D dans Blockbench (`.bbmodel`, cinq visages, trois animations : se balancer, cligner, toit qui
  glisse), proposé à la direction du jeu pour le pack de ressources (la session ne l'y met pas)
- [ ] L'avatar d'Oooscar (512 × 512 PNG, et le GIF où il se balance) : brief, contrôle, critique, relecture à froid,
  envoi à Kr
- [ ] L'icône du serveur (512 × 512 PNG, et le GIF où le toit glisse et revient en 3 s) : même boucle
- [ ] Les stickers (cinq humeurs, 320 × 320, 512 Ko au plus, fond transparent)
- [ ] Les treize emojis du serveur (128 × 128, 256 Ko au plus) : les icônes de `design/` et les cinq têtes d'Oooscar
- [ ] Les trois plaques du Guide du serveur (960 × 320, plaque émaillée de `design/composants/Plaque.md` avec l'icône
  `carte`, `cadenas` ou `engrenage`) et leurs trois icônes (128 × 128, fond transparent), brief dans
  `docs/discord-guide.md`, exports dans `design/discord/guide/`
- [ ] Les bannières de jour et de nuit (960 × 540 ; 1920 × 1080 pour l'arrière-plan d'invitation) : relecture à froid,
  envoi à Kr ; la version animée plus tard
- [ ] Les exports validés dans `design/discord/`, chacun marqué « à valider par Kr » tant qu'il ne l'est pas

### D2 : le QG

Critère de fin : sur le serveur Silooo, une arrivée se joue de bout en bout (accueil, bonjour, tuto, rôle, spoil
débloqué, ticket, admission), captures à l'appui, et « Prêt à mettre en ligne : le QG » est écrit ici. Corrections de la
critique : le QG ouvre avec une douzaine de salons, les autres viennent avec la bêta ; seuils des spoils vers 40 % des
membres ; un concours par mois avant la bêta et 200 membres (point 3) ; aucune annonce ne réclame de boost
(point 10) ; le rapport du lundi (point 13).

- [x] Le socle des fonctions : les événements, les messages, la boucle, les minuteries, la traduction JDA, les faux
  pour les tests (`FakeConnection`, `FakeReply`, `FakeTimer`), 11 tests de plus, vérificateur vert
- [x] Les chiffres du Discord dans `definitions/discord.json` et son schéma (accueil, statut, vocaux d'info, coulisses,
  XP, tableaux, monte-charge, rapport du lundi, rappels, giveaways, concours, Aides ; à valider par Kr), lus par
  `DiscordDefinitions` à part du jeu et vérifiés contre le plan ; la migration **V10** (`V10__discord.sql`, V8 au départ) : membres,
  messages vivants du bot, XP, spoils, réactions, meilleurs moments, aide du mois, petits jeux et records, giveaways
  et lots, tickets, admissions, compteurs du rapport. SQL non joué sur le PC (Docker éteint) : la CI le joue
- [x] Les salons et les rôles de la phase `attente` dans le plan (`#💡・suggestions` dès l'attente, les trois vocaux
  d'info en écriture bot seul), l'accueil guidé (la clé `accueil` : « Tu joues sur quoi ? », une réponse obligatoire
  vers Console, Téléphone ou PC ; « Tu veux être prévenu de quoi ? », six réponses vers les rôles de notification ;
  `OnboardingPlanner`, `OnboardingSync`, `OnboardingApplier`, `discord/rest/DiscordRest` en `GET` et `PUT
  /guilds/{id}/onboarding`, dix tests, `ktor-client-mock` en test), le Guide du serveur écrit pour Kr dans
  `docs/discord-guide.md` ; ses trois cartes (plaque émaillée et icône, en pixel) passent à D1
- [x] Le bonjour d'Oooscar dans `#💬・discussion` (5 à 10 s après l'accueil guidé, selon la plateforme), les arrivées
  groupées (30 s, 10 mentions au plus, puis une ligne), le tuto en message privé avec le bouton « Mon tuto » en
  secours, les rôles de notification (donnés par l'accueil guidé lui-même, déjà en place), le statut alterné, le vocal
  d'info « N en ligne » renommé deux fois par dix minutes au plus (les deux autres vocaux d'info gardent le nom du
  plan) ; écrit avec ses tests sur le faux Discord, pas encore vu sur un vrai serveur
- [x] Les coulisses : `/spoil`, l'aperçu des réactions, les seuils, la publication par le seul bot, l'attente jusqu'à
  7 h, le registre en base ; écrit avec ses tests sur le faux Discord, pas encore vu sur un vrai serveur
- [x] Les admissions : les cartes lues dans `site_registrations` (S4 du site), Admettre, Refuser, Plus tard ; `/annonce`
  avec le contrôle anti-IA avant l'aperçu ; écrit avec ses tests sur le faux Discord et sur MariaDB (CI), pas encore vu
  sur un vrai serveur ; l'option « Publier aussi dans les Actus du site » attend les Actus (décision 31), le rôle et
  l'invitation d'un inscrit non relié attendent `/lier` (D3, décision 29)
- [x] Les tickets par menu (remis à neuf après chaque choix, un par membre, archivés au journal puis supprimés), « Crée ton
  vocal », l'XP de tchat (un message par minute, rôles aux niveaux 5, 10, 20, 30, classement de la semaine), les
  giveaways (lot gardé sur l'identifiant Discord, crédité à la liaison prouvée) et leur règlement en brouillon
  (`docs/discord-reglement-giveaways.md`) ; écrit avec ses tests sur le faux Discord et sur MariaDB (CI), pas encore vu sur
  un vrai serveur ; le crédit en jeu des lots attend une route du contrat (décision 44, D5)
- [x] L'AutoMod posé par l'API (la liste du filtre, le spam de mentions, les liens), l'icône et l'image d'accueil de
  `#👋・bienvenue` posées par le bot ; écrit avec ses tests sur le faux Discord, pas encore vu sur un vrai serveur, les images
  attendent D1
- [x] Les tableaux du QG : top réactions de la semaine, meilleurs moments, Aide du mois, le chantier en direct, avec
  `#🏛️・panthéon` et `#⭐・meilleurs-moments` ; écrit avec ses tests sur le faux Discord et sur MariaDB (CI), pas encore vu sur
  un vrai serveur
- [x] Les petits jeux de la catégorie ANIMATION : `#🔢・comptage` et `#🛗・monte-charge`, records au Panthéon ; écrit avec ses
  tests sur le faux Discord et sur MariaDB (CI), pas encore vu sur un vrai serveur
- [ ] Le rapport du lundi dans `#📒・journal-du-bot` : arrivées, accueil terminé, comptes reliés sous 7 jours, membres
  revenus après 7 jours, messages par jour, d'où viennent les arrivants, messages privés échoués, signalements
- [ ] Une arrivée rejouée de bout en bout sur le serveur Silooo, captures dans `docs/references/discord/captures/`
  (envoyées par Kr tant que l'exception Discord n'est pas dans `CLAUDE.md`), et « Prêt à mettre en ligne : le QG »

### D3 : le jeu sur Discord

Critère de fin : un joueur simulé relie son compte, prend son pseudo et ses rôles, et voit sa carte `/entreprise` ; les
rappels respectent 22 h à 7 h. Corrections de la critique : la liaison par le code montré en jeu, et seulement elle,
pour les pseudos, les rôles du jeu, les lots et les votes (point 2) ; les bureaux visibles, jamais verrouillés, le staff
y entre toujours (point 1).

- [ ] La liaison : `/lier` et le bouton de `#👋・bienvenue`, le code de 10 minutes montré en jeu et saisi sur Discord,
  la même liaison que le site (`site_registrations`), `/delier` ; l'écran en jeu est demandé à la direction du jeu
- [ ] Les pseudos « Gamertag · P3 », les rôles PDG, Associé, Prestige N (paliers à choisir par Kr) et Pionnier, le
  laiton à partir du prestige 5 ; mis à jour à chaque événement
- [ ] `#🏭・le-silo` (un message modifié toutes les 30 s, image dessinée par silooo-core, texte dessous, trois boutons,
  l'incident en direct) et `#📈・records` (prestiges groupés, Vote party, records, commande livrée, époque)
- [ ] Les commandes et leurs cartes : `/entreprise`, `/usine`, `/classement`, `/vote`, `/rappels`, `/niveau`,
  `/parrainage`, `/bureau`, le bouton « Montrer » ; l'installation sur le compte du joueur
- [ ] Les rappels privés, désactivés au départ, trois par jour au plus, aucun de 22 h à 7 h sauf les Gemmes reçues,
  « Arrêter ce rappel » sur chacun ; migration des préférences
- [ ] Les bureaux d'entreprise et les sondages du bot (une voix par joueur relié) ; l'apparition des salons de la
  phase `beta`
- [ ] Les tableaux reliés au jeu : le Panthéon en direct, `#🆕・patch-notes`, le suivi des suggestions, les résultats de
  sondage en carte

### D4 : le tchat relié

Critère de fin : un message passe dans les deux sens sur le serveur Silooo, et un message interdit ne passe dans
aucun. Correction de la critique (point 1) : le relais refuse les demandes de contact ; en jeu, « Voir le tchat
Discord » et le signalement sont demandés à la direction du jeu.

- [ ] `POST /v1/chat` d'abord au contrat (`api/silooo-core.openapi.yaml`, recette `silooo-route`), son test de contrat,
  puis le code ; le type d'événement que l'add-on affiche ; l'add-on reste à la direction du jeu
- [ ] Le filtre commun (mots refusés et demandes de contact), 200 caractères, sans lien ni mention, un message toutes
  les 3 secondes, le webhook sous le gamertag
- [ ] Le journal de 30 jours (migration, purge), le signalement par clic droit vers `#🚩・signalements`
- [ ] Les gestes du staff : masquer, couper le relais (1 h, 24 h, jusqu'à nouvel ordre), tout suspendre ; des deux côtés

### D5 : la croissance

Critère de fin : chaque récompense arrive une seule fois en jeu, dans un test de bout en bout. Correction de la critique
(point 11) : rien ne s'annonce sur Discord avant que la direction du jeu ait livré la récompense en jeu.

- [ ] Le parrainage (côté jeu, annoncé à la direction du jeu), `/parrainage`
- [ ] Le concours de la semaine et ses défis mesurés depuis silooo-core ; le concours des créateurs
- [ ] Les Soutiens : rôle pendant le boost, titre et avantages en jeu par le contrat, `#💎・soutiens`
- [ ] Les lots des giveaways et des concours crédités une seule fois en jeu

### D6 : la mise en service

Critère de fin : D0 à D6 cochées sur `main` sauf les cases de Kr, `main` vert, dernier bilan poussé, `git status`
propre.

- [ ] Le serveur Silooo rejoué en entier, captures dans `docs/references/discord/`, la liste de Kr à jour
- [ ] « Prêt pour la bêta » écrit ici, avec ce que Kr doit avoir fait avant ; la direction du jeu déploie

## Prêt à mettre en ligne

Pas encore. Deux mises en ligne sont prévues, chacune écrite ici avec ce que Kr doit avoir fait avant :

1. **Le QG**, avec la page d'attente du site (fin de D2) : le bot est déjà sur le serveur Silooo, fermé au public
   (« Décisions de Kr »). Il passe du PC au VPS : Kr pose le jeton et l'identifiant du serveur dans le fichier
   d'environnement du VPS, le bot du PC s'arrête (un seul bot à la fois), et la direction du jeu déploie avec
   `SILOOO_DISCORD_ACTIF=oui` et `SILOOO_DISCORD_PUBLIC=non`. Le premier passage sur le VPS se fait à blanc : Kr lit
   l'écart dans `#📒・journal-du-bot`. Quand Kr ouvre le QG, `SILOOO_DISCORD_PUBLIC` passe à `oui`, après le nettoyage
   de « Essais à nettoyer avant l'ouverture ».
2. **La bêta** (fin de D6) : les salons de la phase `beta` apparaissent, la liaison et les rôles du jeu s'allument.

## Pour la direction du jeu

Lu à chaque bilan de la direction du jeu. Réponses attendues dans son journal.

1. **Fichiers partagés de `core/`.** D0 a touché `Config.kt` (le champ `bot`, les variables `SILOOO_DISCORD_ACTIF`,
   `SILOOO_DISCORD_TOKEN`, `SILOOO_DISCORD_SERVEUR`, `SILOOO_DISCORD_PHASE`), `Main.kt` (le bot démarre après le site,
   se ferme avant lui), `libs.versions.toml` et `core/build.gradle.kts` (JDA), `core/README.md` (quatre lignes de
   variables, une ligne « Où est quoi », une section « Le Discord »), `tools/anti_ia.py`, `tools/verifier.sh`,
   `tools/tests/run.sh`, et le paquet `discord/` est à la session. `Application.kt` n'est pas touché. Petits commits,
   rebase juste avant la fusion. Dites ici si une tranche y travaille.
2. **Numéros.** ADR 0007 est écrit ; la migration du Discord est V10 (V8, réservée au départ, est restée un trou : V9 est partie avant ; Flyway sans `outOfOrder` aurait refusé V8 après V9).
8. **Le déploiement.** Les quatre variables du bot sont dans `deploy/exemple.env.txt` avec `SILOOO_DISCORD_ACTIF=non`
   (le test `deploy-compose.mjs` l'exige) et dans le tableau de `docs/deploiement.md` ; rien dans `compose.yml`. Au QG (fin de D2), la direction du jeu pose
   `SILOOO_DISCORD_ACTIF=a-blanc` puis `oui` dans l'environnement du VPS, avec le jeton et l'identifiant donnés par Kr.
   Une cinquième variable, `SILOOO_DISCORD_PUBLIC` (décision 59), vaut `non` par défaut et `oui` quand Kr ouvre le QG au
   public ; elle est à ajouter à `deploy/exemple.env.txt` et au tableau de `docs/deploiement.md` avec les quatre autres.
   Sur le VPS, `SILOOO_DISCORD_ACTIF=non` reste jusqu'à la passation du bot du PC au VPS.
3. **La preuve de liaison en jeu** (D3) : un écran Menu, Mon compte, « Relier Discord » qui montre un code de
   6 caractères valable 10 minutes, lu dans silooo-core. La route sera proposée au contrat par la session Discord, par
   la recette `silooo-route` ; l'écran reste à vous.
4. **Le tchat relié** (D4) : `POST /v1/chat` et un type d'événement à afficher en jeu, préfixé `[Discord]` ; un réglage
   « Voir le tchat Discord » et un signalement en jeu (critique, point 1). Contrat d'abord ; l'add-on reste à vous.
5. **Les récompenses** (D5) : titres, objets de déco, Soutiens, parrainage (Menu, Mon compte, Parrainage, durée dans
   `definitions/`), lots des concours et des giveaways. Rien ne s'annonce sur Discord avant leur affichage en jeu.
6. **Le modèle 3D d'Oooscar** (D1) : proposé pour le pack de ressources quand il existera ; la session ne touche pas
   `resource-pack/`.
7. **`design/README.md`** : la règle « Pas d'emoji, jamais » doit dire l'exception des noms de salons Discord, décidée
   par Kr le 5 novembre ; la session Discord n'écrit pas dans `design/README.md`.
9. **Le crédit en jeu des lots des giveaways.** Le tirage écrit le lot dans `discord_prizes` (`delivered_at` nul tant que
   rien n'est crédité), gardé sur l'identifiant Discord du gagnant. Le crédit en jeu (Gemmes, Lingots, cosmétique, une seule
   fois, au compte relié par la liaison prouvée) demande une route du contrat ; la session Discord la proposera par la
   recette `silooo-route` quand la direction du jeu dit où elle la veut. Rien ne s'annonce comme crédité avant.
10. **Le titre « Aide du mois » en jeu.** Il revient au premier de la ligne « Aide du mois passé » du journal du bot, le
   premier de chaque mois. Le poser en jeu demande une route du contrat, comme le crédit des lots (point 9).

## Pour la direction du site

1. La liaison Discord des pionniers (S4) ne prouve pas la possession du gamertag ; le bot ne donne pseudo, rôles, lots
   et votes qu'après le code montré en jeu (D3). Le site peut afficher « relié » ; « prouvé » viendra du jeu.
2. La case « Rejoindre aussi le Discord » à l'inscription demande la portée `guilds.join` en plus d'`identify` ; à
   prévoir quand Kr aura posé l'application.
3. Le règlement des giveaways et la page Confidentialité (journal du tchat relié gardé 30 jours) : textes à publier sur
   le site, fournis en brouillon par la session Discord ; celui des giveaways est dans
   `docs/discord-reglement-giveaways.md`, à valider par Kr avant publication.
4. Une page « Mises à jour » du site devient une carte dans `#🆕・patch-notes` ; un champ de la page dira si elle passe
   aussi dans `#📢・annonces`.
5. `/annonce` ne peut pas publier dans les Actus du site : ce sont des fichiers Markdown du classpath, pas un registre où
   écrire (décision 31 maintenue). Le chantier en direct de `#coulisses` lit ces mêmes fichiers (`Actus.chantier`).

## À faire par Kr

- [ ] Sur le serveur Silooo : activer les intentions privilégiées « membres du serveur » et « contenu des messages » de
  l'application du bot ; l'inviter avec les droits Gérer les rôles, Gérer les salons, Gérer les messages, Gérer le
  serveur, Déplacer les membres, Lire l'historique des messages, Ajouter des réactions ; passer le serveur en mode
  Communauté
- [ ] Relancer l'application Claude après la pose des variables du compte (ou lancer le bot depuis un terminal à toi), et
  démarrer Docker Desktop (ou installer MariaDB) pour la base locale des essais (« Lancer le bot depuis le PC »)
- [ ] Mode Communauté (accueil guidé, forums, annonces) : Discord demande un salon des règles (`#👋・bienvenue`) et un salon
  des mises à jour (`#📒・journal-du-bot`), que le bot a créés au premier passage (`docs/discord-guide.md`, 1)
- [ ] Après le premier passage pour de vrai : te donner à la main le rôle Staff que le bot a créé. Tant que le QG est
  fermé, `ClosedServer` (décision 59) ne laisse les messages privés et les mentions de rôle qu'au staff et à la
  modération
- [ ] Poser le Guide du serveur à la main (l'API ne le permet pas) : le mot d'accueil, les trois tâches des nouveaux,
  les trois rubriques avec leurs textes, puis leurs images quand D1 les aura rendues (`docs/discord-guide.md`, 3) ; lire
  dans `#📒・journal-du-bot` que l'accueil guidé est posé, et dire si Discord a refusé
- [ ] À la mise en ligne du QG (le bot passe du PC au VPS) : le jeton et l'identifiant du serveur dans le fichier
  d'environnement du VPS, jamais dans le dépôt ; les rôles Staff et Modération donnés à la main aux autres membres du
  staff ; `SILOOO_DISCORD_PUBLIC=oui` à l'ouverture au public, une fois « Essais à nettoyer avant l'ouverture » traité
- [ ] S'il veut que la session règle le serveur par le contrôle à distance (badge, captures, ce que l'API ne permet
  pas) : coller l'exception Discord du plan dans la section du mode autonome de `CLAUDE.md`. Sans elle, ces réglages
  restent ici avec leur marche à suivre
- [ ] Connecter le dossier `Documents\minecraft` à la session pour les rendus (D1) ; prévenir si le PC n'est pas
  disponible
- [ ] D1 : dire quand la session peut lancer le rendu de contrôle SileneMC (la marque `silooo` est dans
  `Documents\minecraft\contenu-reseaux\_outils`), et si Pillow lit les polices woff2 du dépôt ; sinon, des TTF dans
  `_outils\assets\fonts\`
- [ ] Les coulisses : l'emoji de serveur `:silo:` doit exister (D1) ; tant qu'il manque, le compte des réactions reste à
  zéro et le spoil suivant ne part pas. Puis rejouer `/spoil` sur le serveur Silooo avec une image : le premier spoil
  part tout de suite avec le rôle Coulisses, le second attend, les réactions `:silo:` font monter la ligne d'état jusqu'au
  seuil ; dire si Discord a refusé la pièce jointe, la réponse différée ou la modification du message
- [ ] Rejouer les admissions et `/annonce` sur le serveur Silooo (une inscription sur la page d'attente, puis Admettre ;
  puis `/annonce` avec et sans image, avec un texte à tics pour voir le refus).
- [ ] Dire à la session du site que `/annonce` écrira dans les Actus quand leur registre sera sur `main`.
- [ ] Une fois le bot invité avec les droits Gérer les salons, Déplacer les membres et Lire l'historique des messages
  (case d'invitation ci-dessus), rejouer sur le serveur Silooo un ticket (ouvrir, écrire, fermer), « Crée ton vocal »,
  quelques messages d'XP et `/niveau`, un `/giveaway` d'une journée.
- [ ] Relire `docs/discord-reglement-giveaways.md` et le donner à la session du site pour publication.
- [ ] Choisir les paliers de prestige qui donnent un rôle, et trancher les rôles liés de Discord (option, ou rien)
- [ ] Retirer les récompenses par invitation de SileneMC (`#🎁・inviter`), interdites par Discord (critique, point 14)
- [ ] Les droits « Gérer le serveur » (l'AutoMod et l'icône), « Gérer les messages » (supprimer dans `#comptage`, épingler
  dans `#coulisses`) et « Ajouter des réactions » (le `:donar:` du comptage) sont dans la case d'invitation ci-dessus ;
  sans « Gérer le serveur », le bot le dit au journal et continue
- [ ] Compléter la liste des mots refusés dans `core/src/main/resources/discord/automod.json` (46 mots de départ) ; le
  filtre du tchat relié de D4 lira le même fichier
- [ ] Poser les emojis de serveur `:donar:` et `:prestige:` (D1) : sans `:donar:`, le comptage ne réagit pas ; sans
  `:prestige:`, aucun meilleur moment ne part
- [ ] Après le premier démarrage, relire les quatre règles « Oooscar » dans les réglages du serveur (Discord limite à six les
  règles à mots-clés) ; puis dire si Discord a refusé l'icône, l'épinglage dans `#coulisses` ou l'ajout des réactions
- [ ] Plus tard, aux boosts : le badge SILO à 3 boosts, la bannière en arrière-plan d'invitation à 2, en haut des
  salons à 7, animée à 14

## Essais à nettoyer avant l'ouverture

Chaque lancement du bot sur le serveur Silooo ajoute ici ce qu'il a créé ou écrit (rôles, salons, messages, règles
AutoMod), à nettoyer ou à garder avant l'ouverture du QG.

- 5 novembre 2026, vers 21 h 30, à blanc depuis le PC de Kr (`tools/bot-pc.ps1`) : le bot a créé le seul salon
  `#📒・journal-du-bot` (sans catégorie Staff, elle n'existait pas) et y a écrit son compte rendu. À garder. Un premier
  lancement en mode `oui`, un peu plus tôt, s'était arrêté avant de rien créer (intention `GUILD_EXPRESSIONS` manquante,
  corrigée en 306f292).
- 5 novembre 2026, vers 21 h 55, en mode `oui` depuis le PC de Kr : le plan de la phase `attente` appliqué (rôles,
  catégories, salons, règles AutoMod), et sept cartes posées par le bot (`bienvenue`, `chantier`, `monte-charge`,
  `pantheon-aide`, `pantheon-reactions`, `pantheon-records`, `tickets-menu`), dont les identifiants vivent dans la base
  locale du PC (`discord_bot_messages`) : à la passation au VPS, soit cette base est recopiée, soit le bot repose les
  cartes et les anciennes sont à supprimer à la main. Le détail des refus de Discord est dans `#📒・journal-du-bot`.

## Décisions de Kr

1. **Un seul serveur Discord, pas de serveur de test** (5 novembre 2026, 19 h 57). Le bot travaille directement sur le
   serveur Silooo, qui reste fermé au public jusqu'à l'ouverture du QG. Le serveur « Silooo Test » et l'application
   « Oooscar Test » ne sont plus attendus.
   - Le jeton et l'identifiant du serveur sont des variables d'environnement du compte Windows de Kr,
     `SILOOO_DISCORD_TOKEN` et `SILOOO_DISCORD_SERVEUR` : ils ne s'écrivent dans aucun fichier du dépôt ni dans aucun
     message, et la session ne les affiche jamais. Elle pose elle-même `SILOOO_DISCORD_ACTIF` et
     `SILOOO_DISCORD_PHASE=attente` à chaque lancement.
   - Les garde-fous : à blanc d'abord, et le compte rendu lu dans `#📒・journal-du-bot` avant tout passage à `oui` ; tant que
     le QG n'est pas ouvert, messages privés et mentions de rôle vers le staff seulement (décision 59) ; un seul bot à la
     fois, celui du PC pendant le chantier et celui du VPS après ; la liste des essais à nettoyer avant l'ouverture est
     tenue ici (« Essais à nettoyer avant l'ouverture »).
2. **Le bot se lance depuis le terminal de Kr, la base dans Docker Desktop** (5 novembre 2026, vers 20 h 40). La session
   prépare le script (`tools/bot-pc.ps1`) et la base, Kr lance ; la base locale ne sert qu'aux essais.

## Décisions prises sans Kr

1. **La liaison dans le sens de la critique.** Le cadrage fait donner le code par le bot et taper en jeu ; la critique
   demande le code montré en jeu. Les deux prouvent le gamertag ; la session suit la critique (le code vient de
   silooo-core, s'affiche en jeu, se saisit sur Discord ou sur le site), parce que l'écran en jeu n'a alors qu'à
   afficher. À valider par Kr avec le dessin de D3.
2. **Les salons du QG.** La douzaine de la critique, plus les trois salons du staff (`#📒・journal-du-bot`,
   `#✅・admissions`, `#🚩・signalements`), invisibles des membres, et `#❓・aide` en forum. Les noms et les emoji sont
   provisoires, à valider par Kr.
3. **Les numéros.** ADR 0007 et migration V8, annoncés ici plutôt que dans `docs/passation.md`, que la session ne
   modifie pas.
4. **JDA plutôt que Kord** (ADR 0007), sur la cadence des versions et le changement de Discord du 16 novembre 2026 ;
   l'accueil guidé, que JDA ne couvre pas, se fera en REST avec le client Ktor du service.
5. **Quatre variables, pas trois.** `SILOOO_DISCORD_SERVEUR` plutôt que `GUILD_ID` (les variables du dépôt sont en
   français), et `SILOOO_DISCORD_PHASE` (`attente`, `beta`, `ouvert`) pour dire au bot quelles entrées du plan existent :
   la bêta s'allume par une variable, sans changer le plan ni le code. `SILOOO_DISCORD_ACTIF` accepte `a-blanc` en plus
   de `oui` et `non` : c'est le mode à blanc du cadrage.
6. **Le mode à blanc crée quand même le journal** (et sa catégorie Staff) s'il manque : sans lui, Kr ne lirait pas
   l'écart. Tout le reste reste intact.
7. **Les noms Discord** : `emoji・nom` pour un salon écrit (point médian japonais, comme sur les serveurs modèles),
   `emoji nom` pour un vocal ; les catégories en casse normale, Discord les affiche en capitales. Un salon se reconnaît à
   ce nom : le renommer dans le plan en crée un nouveau, l'ancien reste jusqu'à ce que Kr l'enlève.
8. **Les couleurs des rôles** prises dans `design/tokens.json` : Staff et Modération en `email-rouge-clair`, Aide en
   `email-vert-clair`, Créateur en `email-bleu-clair`, Pionnier en `email-creme`, Entrepreneur en `ciel` ; les grades, les
   plateformes et les rôles de notification sans couleur. Le schéma n'accepte que ces six couleurs et `laiton`.
9. **Les salons par défaut de l'accueil guidé** sont tous les salons publics de la phase, sans clé dans le plan : moins
   à tenir à jour, et un nouveau membre voit le QG entier. Discord exige 7 salons par défaut dont 5 où tout le monde
   écrit : pour y arriver dès l'attente, `#💡・suggestions` quitte la phase `beta`, et les trois vocaux d'info passent
   en écriture bot seul (`ecriture: []`), puisque personne n'y écrit. Le compte se fait avant l'envoi
   (`OnboardingPlanner`) et s'écrit dans le journal. Le cinquième salon ouvert est le vocal « Crée ton vocal » : si
   Discord ne compte pas les vocaux, il faudra ouvrir l'écriture d'un salon de plus, à trancher par Kr.
10. **Le Guide du serveur à la main de Kr.** Discord n'a pas d'API pour les rubriques, les tâches et le mot d'accueil ;
    le bot pourrait activer le mode Communauté lui-même, mais ce réglage engage les conditions de Discord : il reste à
    Kr, avec le guide (`docs/discord-guide.md`).
11. **Le tuto part au moment du bonjour.** Le tuto en message privé est envoyé quand le bonjour est prêt (5 à 10 s
    après l'accueil guidé), pas à la fin de l'accueil guidé : un membre reparti pendant l'attente ne reçoit rien, car
    son départ annule le bonjour en attente.
12. **Le rang « tu es le N-ième entrepreneur ».** C'est le nombre de membres du serveur au moment du bonjour
    (`DiscordServer.memberCount`), pas un compteur en base. Il ne se dit que dans un bonjour seul : le bonjour groupé
    ne donne aucun rang.
13. **Le vocal « N en ligne » est relu à intervalle fixe.** Toutes les `vocauxInfo.fenetreMinutes /
    vocauxInfo.renommagesMax` minutes (cinq minutes avec les chiffres actuels), sans nouvelle clé dans
    `definitions/` ; il n'est renommé que si le nombre a changé et que le quota de Discord (deux renommages par dix
    minutes) le permet.
14. **« Mes rappels » avant D3.** Le bouton du tuto répond, au membre seul, « Les rappels arrivent avec la bêta. Tu les
    régleras ici, avec la commande rappels. » jusqu'à ce que les rappels privés existent (D3).
15. **Le SQL de `JdbcMemberStore` en minuscules.** Le garde anti-IA (`tools/anti_ia.py`) lit les chaînes du paquet
    `discord/` : le SQL y est écrit en minuscules.
16. **Les commandes posées par `BotRuntime`.** Chaque fonction déclare les siennes (`Feature.commands`) ; `BotRuntime` pose
    l'union de toutes, une fois, avant le démarrage des fonctions. Rien n'est posé si aucune fonction n'en déclare, pour
    ne pas effacer des commandes déjà là.
17. **Le rôle Coulisses est mentionné dans l'en-tête du message** (`<@&rôle> Spoil n°3`) : Discord n'envoie la notification
    qu'à une mention écrite dans le message.
18. **Le premier spoil part tout de suite, nuit ou pas.** Kr le dépose quand il veut qu'il parte ; l'attente de 7 h ne
    vaut que pour les suivants, quand le seuil est atteint entre `nuitDebutHeure` et `nuitFinHeure`.
19. **Le spoil précédent perd sa ligne d'état** quand le suivant est publié : seule la ligne du spoil courant vit.
20. **Une publication refusée se retente.** Si Discord refuse la publication, le dépôt reste en base et le tic suivant la
    retente ; un verrou interdit de publier deux fois le même spoil quand le tic et un dépôt se croisent.
21. **Un spoil tient sur une ligne.** Une option de commande tient sur une ligne : un spoil n'a pas de retour à la ligne
    pour l'instant. Un formulaire viendra si Kr en veut.
22. **Les fichiers d'un spoil.** Un png ou un gif, 16 Mo au plus (la taille de la colonne `image`), reçus hors du fil de
    JDA avec une réponse différée, pour que le téléchargement ne bloque rien.
23. **Le compte des réactions relit Discord à chaque tic** (`reactors`), au lieu de compter les événements : les bots sont
    exclus, chaque membre compte une fois, et le compte survit à un redémarrage.
24. **Le garde anti-IA en Kotlin vit dans `wording/`, pas dans `discord/`.** `AntiIa.check` doit relire les annonces du
    staff avant l'aperçu, avec les mêmes règles et les mêmes noms que `tools/anti_ia.py`. Le garde du dépôt relit les
    chaînes du paquet `discord/` et y refuserait les mots bannis que la liste contient : le garde Kotlin est donc dans
    `core/src/main/kotlin/com/silooo/core/wording/`, que `anti_ia.py` ne lit pas.
25. **Les majuscules d'insistance se jugent ligne par ligne, sans l'exception de la boutique.** Un titre « MAINTENANCE »
    seul sur sa ligne passe, comme dans un `.txt` ou un `.md` ; la même capitale au milieu d'une ligne qui a des
    minuscules est signalée. Le Kotlin n'a pas l'exception de `boutique.json` : un « Ultime » seul sur sa ligne est
    signalé, il ne passe qu'après « grade ».
26. **Admettre pose `admitted_at` par une méthode nouvelle, `SiteRegistrationStore.admit`.** Le registre du site avait la
    lecture et savait poser `admitted_at` à l'inscription ou à la liaison (`link`), pas à la demande du staff. La méthode
    le pose à maintenant (UTC) si l'inscription ne l'a pas encore, et ne le retire jamais. La carte est modifiée avant le
    rôle et le message privé : Discord n'attend la réponse d'un bouton que trois secondes, et ces deux appels viennent
    en plus.
27. **`AdmissionStore.registration` relit `site_registrations` depuis le paquet `discord/`**, en lecture seule, au lieu
    d'élargir `SiteRegistrationStore` pour le bot : le registre du site garde sa surface, et la carte n'a besoin que du
    gamertag, de la date et du compte Discord.
28. **Une carte admise ou refusée ne se rejoue pas.** Un second clic répond « Cette inscription est déjà traitée. » et ne
    change rien. Plus tard n'est pas une décision finale : la carte garde Admettre et Refuser, et Refuser et Plus tard ne
    touchent pas à l'inscription du site.
29. **Sans liaison Discord, l'admission est posée mais le rôle et l'invitation attendent.** Un inscrit qui n'a pas relié
    son Discord est admis (`admitted_at`, le jeu le laisse entrer) ; le journal note « Sans liaison Discord : le rôle et
    l'invitation attendent la liaison. » À D3, `/lier` devra lire `admitted_at` et donner alors le rôle Bêta et l'invitation.
30. **Le journal du bot avale les refus de Discord.** `BotJournal.line` n'écrit qu'une ligne de contrôle pour le staff : si
    Discord la refuse, le geste qu'elle notait, admission ou annonce, a déjà eu lieu et ne doit pas échouer pour autant ;
    le refus s'écrit dans les journaux du service. Sans salon du journal, la ligne est simplement perdue.
31. **`/annonce` sans l'option « Publier aussi dans les Actus du site ».** Le cadrage la prévoit, mais le site n'a pas
    encore d'Actus sur `main` (la session du site y travaille sur `claude/site-attente`) : il n'y a pas de registre où
    écrire. Elle viendra quand les Actus seront là, en écrivant dans leur registre (« À faire par Kr »).
32. **Les brouillons d'`/annonce`.** Un brouillon par membre du staff, en mémoire (un redémarrage le perd, le staff
    relance la commande), le nouveau remplace l'ancien, quinze minutes de vie (un délai d'interface, pas un chiffre du jeu,
    donc pas dans `definitions/`). L'aperçu n'est vu que du staff et ne prévient personne, même avec la mention. Sans
    rôle Actus sur le serveur, l'annonce part sans mention. Si Discord refuse la publication, le brouillon est remis pour
    que le staff puisse réessayer.
33. **`BotMessageStore`, la façon commune de retrouver un message à clé du bot.** Une clé (`tickets-menu`) et le message
    que le bot a posé sont gardés dans `discord_bot_messages` (`JdbcBotMessageStore`) : après un redémarrage, le bot retrouve
    son message, le remet à neuf ou le reposte s'il a disparu, au lieu d'en poser un second. Les prochains messages à clé du
    bot passeront par lui.
34. **Le menu des tickets est remis à neuf après chaque choix.** Discord garde la sélection affichée dans le menu : sans
    remise à neuf, le même choix ne se déclenche plus au clic suivant.
35. **Un seul ticket ouvert par membre.** Le salon n'est visible que du membre, du staff et de la modération
    (`ChannelSpec.memberViewers`). À la fermeture, le salon est copié dans `#📒・journal-du-bot` puis supprimé ; si la copie
    ne se lit pas, le salon reste.
36. **`deleteChannel` est réservé aux salons créés par le bot** (un salon de texte ou un vocal) : un salon posé à la main
    ne peut pas être effacé par un bug d'une fonction.
37. **Les vocaux de membre sont supprimés quand ils se vident.** Au démarrage, le nettoyage ne touche que les vocaux dont le
    nom commence par « Vocal », hors plan et vides ; les occupés sont repris. Un vocal dont le déplacement du membre a été
    refusé et qui reste vide est supprimé 30 secondes plus tard.
38. **L'XP ne dit « passe au niveau » qu'aux paliers à rôle** (5, 10, 20 et 30) : une phrase à chaque niveau serait du bruit
    dans `#💬・discussion`. Un membre ne porte qu'un rôle de niveau : celui du palier atteint, le précédent est retiré.
39. **La semaine d'XP change à l'heure du rapport du lundi, pas à minuit.** Le rapport part à 9 h : les messages du lundi
    matin comptent encore pour la semaine qui finit. Limite connue : la purge coupe à minuit UTC du lundi, donc un message du
    lundi avant 9 h supprimé après le rapport retire ses points à la nouvelle semaine (négligeable).
40. **Les sujets de forum comptent pour leur forum.** Un message dans un sujet de forum est compté si le forum figure dans
    `xp.salons` (`MessagePosted.parentChannelId`).
41. **`/niveau` sans option membre.** Le socle n'a pas de type d'option « membre » : la commande ne montre que son auteur,
    en réponse à lui seul. Voir le niveau d'un autre viendra avec ce type d'option, si Kr le veut.
42. **Les giveaways en message classique, pas en conteneur récent.** Le conteneur de Discord (composants V2) ne se mêle pas à
    une mention de rôle : la carte est un message classique, et le rôle Giveaways n'est prévenu qu'au premier envoi, pas à
    chaque modification du nombre de participants.
43. **Le gagnant et le lot sont en base avant tout message.** Le tirage écrit le gagnant et le lot (`discord_prizes`) avant
    de toucher à Discord ; un refus de Discord fait reprendre les étapes restantes une minute plus tard, 30 fois au plus,
    sans second tirage. Après un redémarrage entre le tirage et les messages, le gagnant et le lot restent en base mais les
    messages ne sont pas refaits.
44. **Le lot reste sur l'identifiant Discord jusqu'au crédit en jeu.** `discord_prizes` garde le lot sans `delivered_at`
    tant que rien n'est crédité ; le crédit viendra par une route du contrat, une fois, au compte relié par la liaison
    prouvée (« Pour la direction du jeu », 9).
45. **Un giveaway `etape` compte dans le mois.** Aucune colonne de `discord_giveaways` ne distingue une grande étape : une
    migration le ferait, plus tard, si Kr le veut. En attendant, `etape` lève seulement la limite d'un par mois au lancement.
46. **Pas d'événement du calendrier Discord pour l'instant.** Le giveaway n'est que sa carte dans `#🎁・giveaways` ; l'événement
    planifié viendra si Kr le veut.
47. **Le socle grandit du minimum.** `DiscordServer` reçoit neuf méthodes, chacune au service d'une fonction de cette
    étape : `pin`, `react` (faux si le serveur n'a pas l'emoji), `deleteMessage` (un message déjà parti ne gêne pas),
    `message` (null s'il n'existe plus), `messageLink`, `hasIcon`, `setIcon`, `automodRules` et `setAutomodRule`.
    `ChannelMessage` gagne `bot`, `ReactionAdded` gagne `isBot`.
48. **L'AutoMod : quatre règles du bot, remplacées à chaque démarrage.** Elles se retrouvent par leur nom (« Oooscar : mots
    refusés », « Oooscar : demandes de contact », « Oooscar : spam de mentions », « Oooscar : liens »), sont posées ou
    remplacées à chaque démarrage, alertent dans `#signalements` et exemptent le staff et la modération, les créateurs aussi
    pour les liens. La liste vit dans `core/src/main/resources/discord/automod.json` : 46 mots de départ à compléter par Kr,
    les motifs des demandes de contact, une limite de 5 mentions, les liens autorisés (silooo.com, discord.com/channels,
    discord.gg). Le même fichier servira au filtre du tchat relié de D4 (`AutomodRules.load()`). Un refus de Discord (le droit
    « Gérer le serveur » absent) fait une ligne au journal, le bot continue.
49. **`#bienvenue` : une carte vivante, avec son image.** Une carte du bot, retrouvée par la clé `bienvenue`. L'image de jour
    sert entre `bienvenue.jourHeure` (7) et `bienvenue.nuitHeure` (22) de `definitions/discord.json` (nouveau bloc, schéma et
    types de l'add-on régénérés), l'image de nuit sinon, dès que D1 fournit `discord/bienvenue-jour.png` et
    `discord/bienvenue-nuit.png` sur le classpath ; sans elles, la carte part sans image et une ligne du journal le dit. Deux
    boutons : « Relier mon compte » répond une attente jusqu'à D3, « Mes rappels » est déjà géré par l'accueil guidé, sans
    double réponse.
50. **L'icône du serveur ne passe jamais devant celle de Kr.** `discord/icone-serveur.png` (D1) n'est posée au démarrage que si
    le serveur n'a pas d'icône : une icône posée à la main n'est jamais écrasée.
51. **Le top réactions compte une réaction par message et par membre.** La marque est gardée dans `discord_reaction_marks`,
    les bots sont exclus, seuls comptent les salons de `reactions.salons`, la semaine court du lundi minuit (Paris). Le
    tableau `pantheon-reactions` est rafraîchi toutes les 60 secondes quand il change ; au lundi, le classement de la semaine
    passée va au journal et les marques sont purgées. Une réaction dans un sujet de forum ne compte que si le bot a vu passer
    le sujet depuis son démarrage : l'événement ne porte pas le forum parent.
52. **Les meilleurs moments sont recopiés une fois, sans ping.** Au seuil de `meilleursMoments.seuil` réactions `:prestige:`,
    celles de l'auteur exclues, dans les seuls salons que le plan montre à tous (jamais un ticket ni un salon du staff) ; les
    messages du bot sont ignorés. Le message est recopié une seule fois (`discord_highlights`), sans mention, avec le lien du
    message.
53. **L'Aide du mois : un bouton « Résolu par » dans chaque sujet neuf.** Le bot le pose dans chaque nouveau sujet de `#aide` ;
    l'auteur du sujet ou le staff choisit dans un menu des participants (hors bots, hors auteur). Un seul choix par sujet
    (`discord_help_marks`). Le tableau `pantheon-aide` montre dix lignes ; au premier du mois, le classement du mois passé va
    au journal.
54. **Le chantier en direct lit les Actus du site.** Les étapes viennent des Actus du classpath (`Actus.chantier`), les mêmes
    que la page d'attente du site. La carte `chantier` vit dans `#coulisses` et est épinglée à la première publication ; ses
    liens sont préfixés par l'adresse du site (`config.siteUrl`, passée à `BotFeatures.build`).
55. **Le comptage garde l'état et les records en base.** Des nombres seuls (un autre message est supprimé), `:donar:` sur le
    bon nombre, un record battu écrit en base ; une chaîne cassée est dite sans ping, avec le record ; à 0, un mauvais nombre ne
    casse rien. L'état (`discord_games`) et les records (`discord_records`) survivent au redémarrage.
56. **Le monte-charge attend en mémoire.** Une carte `monte-charge` avec le bouton « +1 étage » ; l'attente de
    `monteCharge.attenteMinutes` par membre est tenue en mémoire (perdue au redémarrage, sans gravité) ; le staff fait
    redescendre le monte-charge. Les images `discord/monte-charge/<étage>.png` viennent dès que D1 les fournit.
57. **Les records au Panthéon sont lissés.** `pantheon-records` n'est modifié qu'une fois par 60 secondes au plus, les
    modifications suivantes sont différées : Discord limite les modifications par salon.
58. **Les messages vivants sont des cartes.** `#bienvenue`, le monte-charge et le Panthéon sont posés en carte à barre ciel
    (`card = true`), retrouvés par leur clé dans `discord_bot_messages` et modifiés en place, repostés seulement s'ils ont
    disparu (`LiveMessage`, `Boards.kt`) ; les tableaux de la semaine et du mois et le chantier suivent la même forme. Les
    giveaways restent en message classique (décision 42).
59. **Le serveur fermé est tenu par `ClosedServer`, tant que `SILOOO_DISCORD_PUBLIC` vaut `non`.** Pour appliquer la décision
    de Kr du 5 novembre (« Décisions de Kr ») sans second serveur, une variable `SILOOO_DISCORD_PUBLIC` (`non` par défaut,
    `oui` quand le QG est ouvert). Tant qu'elle vaut `non`, `ClosedServer` enveloppe le `DiscordServer` du bot : un message
    privé à un membre sans rôle Staff ni Modération est retenu (compté, une ligne dans les journaux du service, et la
    fonction appelante croit que le membre refuse les messages privés : le bonjour pose alors le bouton « Mon tuto »), et
    les mentions de rôle d'un message sont réduites aux rôles Staff et Modération. Les mentions de membres ne changent pas.
    Sur le VPS, `SILOOO_DISCORD_ACTIF=non` reste jusqu'à la passation du bot du PC au VPS ; à l'ouverture du QG,
    `SILOOO_DISCORD_PUBLIC` passe à `oui`.
60. **Le lancement depuis le PC passe par `tools/bot-pc.ps1`.** Pour appliquer la décision de Kr du 5 novembre à 20 h 40
    (« Décisions de Kr », point 2), un seul script PowerShell, [tools/bot-pc.ps1](../tools/bot-pc.ps1), fait la base, les
    migrations et le lancement. Le conteneur `silooo-mariadb` (image `mariadb:11`) n'écoute que sur 127.0.0.1. Le mot de
    passe de la base locale (gardé dans `%LOCALAPPDATA%\Silooo\mariadb-pc.txt`) et la clé d'API (tirée à chaque lancement)
    sont tirés au hasard et restent hors du dépôt : aucun secret dans le dépôt (`CLAUDE.md`). Le script refuse de partir
    sans Docker Desktop et, hors `-Base`, sans les variables du compte (`SILOOO_DISCORD_TOKEN`, `SILOOO_DISCORD_SERVEUR`),
    qu'il teste sans les afficher. Il ne connaît pas le VPS : « un seul bot à la fois » reste tenu par
    `SILOOO_DISCORD_ACTIF=non` dans le fichier d'environnement du VPS que lit [deploy/compose.yml](../deploy/compose.yml)
    (modèle : [deploy/exemple.env.txt](../deploy/exemple.env.txt)), `non` étant aussi le défaut du code.

## À valider par Kr

- Les noms des salons et des catégories, leurs emoji et leurs sujets, les noms des rôles et leurs couleurs
  (`core/src/main/resources/discord/plan-serveur.json`, D0).
- Les chiffres de `definitions/discord.json` (D2) : le bonjour à 10, 7 et 5 s selon console, téléphone et PC, les
  arrivées groupées (30 s, 10 mentions), le statut qui alterne chaque minute, les spoils (40 % des membres proposés,
  compte chaque minute, nuit de 22 h à 7 h), l'XP (15 à 25 points, une fois par minute, niveau n à 100 × n + 50 × n ×
  (n - 1) / 2 points, rôles aux niveaux 5, 10, 20, 30, dix au classement), le top réactions à dix, les meilleurs
  moments à 5 `:prestige:`, le monte-charge toutes les 5 minutes, le rapport du lundi à 9 h, trois rappels par jour, les
  inscriptions relues chaque minute pour les admissions (`admissions.relectureSecondes`, 60), l'image de `#bienvenue` de jour de 7 h à
  22 h (`bienvenue`), le monte-charge et la limite de cinq mentions de l'AutoMod.
- Les lignes du compte rendu d'Oooscar dans `#📒・journal-du-bot` (`PlanReport.text`, D0) : « Plan du serveur, phase
  attente. », « J'ai créé N rôles, N catégories et N salons. », « Je ne supprime jamais rien. », « Il manque… » à blanc ;
  et pour l'accueil guidé (D2) : « J'ai posé l'accueil guidé : 2 question(s), 9 réponse(s), N salon(s) par défaut dont
  N où tout le monde écrit. », « L'accueil guidé est en place… », « À blanc, je poserais… », « L'accueil guidé attend… ».
- L'accueil guidé (`plan-serveur.json`, clé `accueil`, D2) : « Tu joues sur quoi ? » avec Console (Xbox, PlayStation ou
  Switch), Téléphone (Android, iPhone ou tablette), PC (Windows 10 ou 11) ; « Tu veux être prévenu de quoi ? » avec
  Actus, Coulisses, Concours, Giveaways, Vote party, Concours de villes et leurs descriptions ; les emoji des réponses.
- Le Guide du serveur (`docs/discord-guide.md`, D2) : le mot d'accueil, les trois tâches des nouveaux, les titres et
  les textes des trois rubriques, les icônes choisies (`carte`, `cadenas`, `engrenage`).
- Le bonjour d'Oooscar (`Welcome.kt`, D2). Dans les textes, `@membre` est la mention du membre, `#rejoindre` et
  `#discussion` sont les mentions des salons, et le rang s'écrit « 1er », « 2e », « 1 204e ».
  - Bonjour d'un membre seul : « Bienvenue @membre, tu es le 12e entrepreneur. » suivi, selon la plateforme, de
    « Sur console, tout est dans #rejoindre. », « Sur téléphone, tout est dans #rejoindre. », « Sur PC, tout est dans
    #rejoindre. » ou, sans plateforme, « Tout est dans #rejoindre. »
  - Bonjour groupé (2 à 10 nouveaux, tous mentionnés) : « Bienvenue @a, @b et @c. Tout est dans #rejoindre. »
  - Au-delà de 10 : « 12 nouveaux sont arrivés. Bienvenue à tous : tout est dans #rejoindre. »
  - Le tuto en message privé : « Ton tuto Silooo, en trois étapes. », puis l'étape 1 selon la plateforme, puis
    « 2. Inscris-toi sur silooo.com et relie ton Discord : à la bêta, ton pseudo et tes rôles suivront. » et
    « 3. Dis bonjour dans #discussion. Les coulisses, les sondages et les giveaways sont déjà ouverts ; le jeu arrive
    avec la bêta. » L'étape 1 :
    - console : « Sur ta console, ajoute HubyOnMC en ami, puis rejoins sa partie depuis ta liste d'amis. »
    - téléphone : « Dans Minecraft, onglet Serveurs, ajoute un serveur : adresse play.silooo.com, port 19142. »
    - PC : « Dans Minecraft (Windows 10 ou 11), onglet Serveurs, ajoute un serveur : adresse play.silooo.com, port
      19142. »
    - sans plateforme : « Sur console, ajoute HubyOnMC en ami et rejoins sa partie. Sur téléphone et PC, l'adresse est
      play.silooo.com, port 19142. »
  - La réponse du bouton « Mes rappels » : « Les rappels arrivent avec la bêta. Tu les régleras ici, avec la commande
    rappels. »
  - Les libellés des deux boutons : « Mon tuto » (sous le bonjour, messages privés fermés) et « Mes rappels » (sous le
    tuto).
- Le statut d'Oooscar (`Status.kt`, D2), en alternance : « Joue à play.silooo.com » et « Regarde N entrepreneurs »
  (« Regarde 1 entrepreneur » au singulier).
- Les coulisses (`Spoils.kt`, `SpoilTexts`, D2). Dans les textes, N est un nombre et `#coulisses` la mention du salon ;
  « réaction » prend le singulier pour 1.
  - La commande `/spoil` : « Dépose un spoil des coulisses ». Ses trois options : `texte`, « Le texte du spoil » ;
    `image`, « Une image png ou gif, 16 Mo au plus » ; `seuil`, « Les réactions qui publient le suivant (un entier
    positif) ».
  - Les refus, vus du staff seul : « Cette commande est pour le staff. », « Le salon des coulisses n'existe pas sur le
    serveur : rien n'est déposé. », « Le texte du spoil est vide : rien n'est déposé. », « Le texte fait 1 900
    caractères : 1 800 au plus. Rien n'est déposé. », « Le fichier « nom » est refusé : png ou gif, 16 Mo au plus. Rien
    n'est déposé. », « Le seuil doit être un entier positif : le nombre de réactions qui publie le suivant. Rien n'est
    déposé. », et, si Discord refuse la publication, « Le spoil est déposé, mais Discord a refusé la publication. Elle
    sera retentée dans la minute. »
  - Les réponses au dépôt, vues du staff seul : pour le premier spoil, « Spoil n°1 publié dans #coulisses. Seuil du n°2 :
    40 réactions. » ; pour le suivant d'un spoil publié, « Spoil n°2 déposé : il partira quand le n°1 aura 40 réactions
    (12 pour l'instant). » ; pour un spoil plus loin dans la file, « Spoil n°4 déposé : il partira après le n°3. » Ces
    deux dernières finissent par « Les derniers : n°3 87 réactions, n°2 95, n°1 120. » : les trois derniers spoils
    publiés, du plus récent au plus ancien.
  - Le message du spoil, lu des membres : l'en-tête « <@&rôle> Spoil n°3 » (la mention du rôle Coulisses), le texte, puis
    la ligne d'état. Les trois lignes d'état : « Spoil n°4 à 40 réactions : 12. » ; la même suivie de « Il part à
    7 h. » quand le seuil est atteint la nuit ; « Spoil n°4 : pas encore déposé. » quand rien n'attend.
- Les admissions (`Admissions.kt`, `AdmissionTexts`, D2). Dans les textes, `@staff` est la mention du membre du staff et la
  date s'écrit « le 5 novembre 2026 à 18 h 10 » (« le 1er » le premier du mois). Tout est vu du staff seul, sauf
  l'invitation.
  - La carte, dans `#✅・admissions` : « Inscription de Gamertag, le 5 novembre 2026 à 18 h 10. » puis « Discord :
    relié. » ou « Discord : pas encore relié. » Les trois boutons : « Admettre », « Refuser », « Plus tard ».
  - La carte après le geste, une ligne de plus : « Admis par @staff le 5 novembre 2026 à 18 h 12. », « Refusé par @staff
    le 5 novembre 2026 à 18 h 12. » ou « Remis à plus tard par @staff le 5 novembre 2026 à 18 h 12. »
  - Les réponses aux boutons, vues du clic seul : « Ces boutons sont pour le staff. », « Cette inscription n'est plus
    là. », « Cette inscription est déjà traitée. »
  - L'invitation, le seul texte que l'inscrit lit, en message privé : « Tu es admis à la bêta de Silooo. Elle n'a pas
    encore ouvert : tu seras prévenu ici le jour venu, avec l'adresse et le port. »
  - Les lignes du journal : « Admission : Gamertag admis par @staff. », « Admission : Gamertag refusé par @staff. »,
    « Admission : Gamertag remis à plus tard par @staff. » La ligne d'une admission peut finir par une note : « Sans
    liaison Discord : le rôle et l'invitation attendent la liaison. », « Le rôle Bêta manque sur le serveur :
    l'invitation n'est pas partie. », « Message privé fermé : l'invitation n'est pas partie. » ou « Discord a refusé le
    rôle ou le message privé : l'invitation n'est pas partie. »
- `/annonce` (`Announce.kt`, `AnnounceTexts`, D2). Tout est vu du staff seul, sauf l'annonce publiée.
  - La commande : « Publie une annonce dans le salon des annonces ». Ses options : `titre`, « Le titre de l'annonce, 100
    caractères au plus » ; `texte`, « Le texte de l'annonce, 1 800 caractères au plus (collé, il garde ses lignes) » ;
    `image`, « Une image png ou gif, 16 Mo au plus » ; `mention`, « Mentionner le rôle Actus (non par défaut) », avec les
    choix « oui » et « non ».
  - Les refus : « Cette commande est pour le staff. », « Le titre est vide : rien n'est gardé. », « Le texte est vide :
    rien n'est gardé. », « Le titre fait N caractères : 100 au plus. Rien n'est gardé. », « Le texte fait N caractères :
    1 800 au plus. Rien n'est gardé. », « Le fichier « nom » est refusé : png ou gif, 16 Mo au plus. Rien n'est gardé. »
  - Le refus du garde anti-IA : « L'annonce n'est pas gardée. », puis le résumé du garde (il nomme les règles touchées),
    puis « Corrige et relance /annonce. »
  - L'aperçu : l'annonce telle qu'elle partira (le titre en gras, le texte, l'image, la mention du rôle Actus si elle est
    demandée), avec les boutons « Publier » et « Annuler », sans prévenir personne.
  - Après les boutons : « Publié dans #annonces. », « Annulé. », « Aucune annonce en attente : relance /annonce. », « Le
    salon des annonces n'existe pas sur le serveur. »
  - La ligne du journal : « Annonce publiée par @staff : Titre. »
- Les tickets (`Tickets.kt`, `TicketTexts`, D2). Dans les textes, `@membre` est la mention du membre, `@staff` celle de qui
  ferme, `#aide` et `#salon` les mentions des salons ; la date s'écrit « 5 novembre 2026 à 18 h 10 ».
  - Le menu, dans `#🎫・tickets` : « Pour ce qui ne se dit pas en public. Choisis, et un salon privé s'ouvre entre toi et le
    staff. Une question de jeu se pose dans #aide. », l'invite « Choisis ta demande », et les choix « Une question de jeu »
    (« Elle va dans le salon d'aide »), « Un achat ou des Gemmes », « Mon compte ou ma liaison », « Contester une sanction »,
    « Créateurs ».
  - Les réponses au membre seul : « Une question de jeu se pose dans #aide : la réponse sert à tous. Si c'est privé,
    choisis un autre sujet. » (aucun ticket), « Ton ticket est ouvert : #salon. », « Tu as déjà un ticket ouvert : #salon. »,
    « Les tickets ne sont pas prêts sur ce serveur. »
  - Le salon : le nom `ticket-achat-12`, le sujet « Ticket n°12 de Nom : achat », le premier message « @membre Ton ticket
    est ouvert. Dis-nous tout ici : le staff lit. » et le bouton « Fermer le ticket ».
  - Les refus du bouton : « Ce bouton est pour le staff et l'auteur du ticket. », « Ce ticket est déjà fermé. »
  - Le journal : « Ticket n°12 (achat) de @membre, fermé par @staff. » puis les lignes « 5 novembre 2026 à 18 h 10, Nom :
    texte », par messages de 2 000 caractères ; « Ticket n°N : l'archive n'a pas pu être lue, le salon reste. » ; « Ticket
    n°12 : salon supprimé à la main, fermé. »
- Les vocaux (`VoiceRooms.kt`, D2) : le nom du vocal créé, « Vocal de Nom », et « Vocal N » quand le membre n'a pas de nom.
- L'XP de tchat (`Xp.kt`, `XpTexts`, D2). Dans les textes, `@id` est la mention du membre.
  - La commande `/niveau` : « Montre ton niveau d'XP de tchat ». Ses réponses, au joueur seul : « Niveau 7, 1 800 points, 180
    cette semaine. Prochain niveau à 2 200 points. » ou « Pas encore de points : écris dans #discussion. »
  - Le passage de niveau, dans `#discussion`, sans mention (aux niveaux 5, 10, 20 et 30 seulement) : « @id passe au niveau
    5. »
  - Le classement du lundi, dans `#discussion` : « Les dix de la semaine : » (« Le classement de la semaine : » si la taille
    n'est pas dix), puis « 1. @id, 540 points », ou « Personne n'a écrit cette semaine. »
- Les giveaways (`Giveaways.kt`, `GiveawayTexts`, D2). Dans les textes, `@id` est la mention du gagnant ; la date s'écrit « le
  12 novembre 2026 à 18 h 00 ».
  - La commande `/giveaway` : « Lance un giveaway dans le salon des giveaways ». Ses options : `titre` (80 caractères au
    plus), `lot` (cosmetique, gemmes ou lingots), `quantite`, `cosmetique`, `duree` (1, 3 ou 7 jours) et `etape` (oui).
  - La carte, dans `#🎁・giveaways`, avec le rôle Giveaways prévenu au premier envoi seulement : « Giveaway : Titre », « Lot :
    500 Gemmes », « Lot : 2 000 Lingots » ou « Lot : le cosmétique nom », « Fin le 12 novembre 2026 à 18 h 00. », « Gratuit,
    sans achat, sans invitation ni boost. Un compte relié reçoit le lot en jeu ; sinon il attend la liaison. », « 0
    participant » puis « 37 participants », et le bouton « Participer ».
  - Les réponses au staff : « Giveaway n°3 lancé. » ; les refus : « Cette commande est pour le staff. », « Le titre est vide :
    rien n'est lancé. », « Le titre fait N caractères : 80 au plus. Rien n'est lancé. », « Le lot doit être cosmetique,
    gemmes ou lingots. Rien n'est lancé. », « La quantité doit être un entier positif : le nombre de Gemmes ou de Lingots.
    Rien n'est lancé. », « Un cosmétique demande son identifiant dans l'option cosmetique. Rien n'est lancé. »,
    « L'identifiant du cosmétique fait N caractères : 80 au plus. Rien n'est lancé. », « La durée doit être de 1, 3 ou 7
    jours. Rien n'est lancé. », « Un giveaway par mois : le prochain à partir du 1er décembre. Pour une grande étape, ajoute
    l'option etape. », « Le salon des giveaways n'existe pas sur le serveur. », « Discord a refusé la carte : le giveaway
    n'est pas lancé. Relance la commande. »
  - Les réponses du bouton, au membre seul : « Tu participes. Tirage le 12 novembre 2026 à 18 h 00. », « Tu participes
    déjà. », « Ce giveaway est fini. », « Les bots ne participent pas. »
  - Le tirage : la carte devient « Gagné par @id. » (le gagnant prévenu) ou « Personne n'a participé : pas de tirage. », sans
    bouton ; au gagnant, en privé : « Tu as gagné le giveaway « Titre » : 500 Gemmes. Le lot arrive en jeu sur ton compte
    relié ; sinon, relie ton compte avec /lier et il suivra. » ; au journal : « Giveaway n°3 « Titre » : 37 participants,
    gagné par @id. » (ou « 0 participant, pas de tirage. »), puis les participants en mentions sans prévenir, par messages
    de 2 000 caractères.
- Le règlement des giveaways, en brouillon : `docs/discord-reglement-giveaways.md`, à publier par la session du site une
  fois validé.
- L'AutoMod (`Automod.kt`, `AutomodTexts`, `automod.json`, D2) : la liste de départ des mots refusés, les motifs des demandes
  de contact, la limite de cinq mentions et les liens autorisés. Discord montre ses propres mots aux membres quand une règle
  bloque un message ; seul le journal lit ceux du bot : « AutoMod : 4 règles posées. », « AutoMod : 3 sur 4 règles posées. »
  puis « - <raison> » (voir « Le journal du bot, pour ces fonctions »).
- `#bienvenue` (`Bienvenue.kt`, `BienvenueTexts`, D2). `<#discussion>` est la mention du salon.
  - La carte : « Bienvenue au QG de Silooo. Le jeu se construit sous tes yeux : les coulisses, les sondages et les giveaways
    sont ouverts, le reste arrive avec la bêta. Relie ton compte pour jouer depuis Discord, règle tes rappels, et dis bonjour
    dans <#discussion>. »
  - Les boutons : « Relier mon compte », « Mes rappels ».
  - La réponse du premier : « La liaison arrive avec la bêta : tu relieras ton compte ici, ou avec la commande lier. »
- Le top réactions (`Reactions.kt`, `ReactionTexts`, D2). `<@id>` est la mention du membre.
  - Le tableau : « Top réactions de la semaine : » puis « 1. <@id>, 42 réactions » (« 1. <@id>, 1 réaction » au singulier).
  - Vide : « Personne n'a encore réagi cette semaine. »
- Les meilleurs moments (`Highlights.kt`, `HighlightTexts`, D2) : « <@auteur> dans <#salon> : », puis le texte cité par « > »,
  puis le lien du message ; un texte coupé finit par « (suite) ».
- L'Aide du mois (`Help.kt`, `HelpTexts`, D2). `<@id>` est la mention du membre.
  - Sous chaque sujet neuf de `#aide` : « Quand ta question est réglée, appuie sur le bouton pour dire qui t'a aidé. » et le
    bouton « Résolu par ».
  - Le menu : « Choisis le membre qui a résolu ce sujet. », avec le sélecteur « Choisis un membre ».
  - Les refus, au clic seul : « Seul l'auteur du sujet ou le staff peut dire qui l'a résolu. », « Ce sujet est déjà résolu. »,
    « Personne d'autre n'a encore écrit dans ce sujet. », « Ce sujet ne peut pas être relu pour l'instant. »
  - Les réponses : « C'est noté. » au clic, et dans le sujet « Résolu par <@id>. »
  - Le tableau : « Aide du mois : » puis « 1. <@id>, 3 réponses retenues » (« 1 réponse retenue » au singulier) ; vide :
    « Personne n'a encore résolu de sujet ce mois-ci. »
- Le chantier en direct (`Chantier.kt`, `ChantierTexts`, D2) : le titre « Le chantier en direct », les trois rubriques « Faites »,
  « En cours » et « À venir », et des lignes « titre » ou « titre : https://silooo.com/actus/x/ » (le lien quand l'étape en a un).
- Le comptage (`Counting.kt`, `CountingTexts`, D2). `<@id>` est la mention du membre, `<@x>` celle du détenteur du record.
  - La chaîne cassée : « <@id> a cassé la chaîne à 57 : il fallait 58. Le record est 120, par <@x>. On repart de 1. »
  - Deux fois de suite : « <@id> a joué deux fois de suite : la chaîne s'arrête à 57. Le record est 120, par <@x>. On repart de 1. »
  - Sans record, la phrase du record devient : « Pas encore de record. »
  - La chaîne pas encore commencée : « <@id> a écrit 5 : la chaîne commence à 1. »
- Le monte-charge (`Elevator.kt`, `ElevatorTexts`, D2). `<@id>` est la mention du membre, `<@x>` celle du détenteur du record.
  - La carte au rez-de-chaussée : « Le monte-charge est au rez-de-chaussée. Appuie pour monter d'un étage. »
  - La carte en cours : « Le monte-charge est à l'étage 37. Dernier à appuyer : <@id>. Record : étage 120, par <@x>. »
  - Le bouton : « +1 étage ».
  - Le staff qui fait redescendre : la carte devient « Le staff fait redescendre le monte-charge. Record : étage 120, par <@x>. »
    et le staff seul lit « Le monte-charge est redescendu. »
  - L'attente, au membre seul : « Tu as appuyé il y a 2 minutes. Le monte-charge accepte un étage toutes les 5 minutes par
    membre. » ; aussi « moins d'une minute » pour le délai écoulé et « chaque minute » pour l'attente d'une minute.
- Le Panthéon (`Records.kt`, `RecordTexts`, D2) : le titre « Les records des petits jeux », puis « Comptage : 120, par <@id>, le
  5 novembre 2026. » et « Monte-charge : étage 37, par <@id>, le 5 novembre 2026. » ; sans record : « Aucun record pour
  l'instant. »
- Le journal du bot, pour ces fonctions (D2) : « AutoMod : 4 règles posées. » ou « AutoMod : 3 sur 4 règles posées. » puis
  « - <raison> » ; « Image d'accueil absente : elle arrive avec D1. » ; « Icône du serveur posée. » ; « L'icône du serveur
  n'a pas pu être posée : <raison>. » (« Discord a refusé » quand il n'en dit pas) ; « Top réactions de la semaine passée : »
  puis le classement, ou « Personne n'a réagi. » ; « Meilleur moment : le message de <@a> dans <#c> est recopié. <lien> » ;
  « Aide du mois passé : » puis le classement, ou « Personne n'a résolu de sujet. » ; « Comptage : la chaîne s'arrête à 57,
  cassée par <@id>. » ; « <@id> a fait redescendre le monte-charge de l'étage 37, le 5 novembre 2026 à 18 h 10. » ; « Le
  chantier en direct n'a pas pu être épinglé dans les coulisses. »
- Le format « Gamertag · P3 », les lignes d'Oooscar et les textes des rappels : écrits à D3, listés ici au fur et à
  mesure.
- Le nom du bot, Oooscar, et son avatar ; la bannière, l'icône, les couleurs des rôles et les emojis du serveur (D1).
- La fiche Silooo des outils de rendu (`_outils\references\marque.md`, D1) : le « ooo » en rouge clair `#e0614f` sur
  fond sombre, le gris de texte pris sur `email-creme`, le détail, l'IP et le lien Discord affichés, les polices
  bitmap lissées à toute taille alors que la règle dit « sans lissage ».
- Les lots des concours, les paliers du parrainage (D5).
- Le plan de promotion par Hubyy, à travailler avec Kr.

## Journal

- 5 novembre 2026, 15 h 00 à 15 h 31 (session Discord, PC, worktree `silooo-discord`). Lecture de `CLAUDE.md`,
  `docs/README.md`, `docs/passation.md` (phase 5 en cours ; le Discord par webhook y reste une case non cochée, que le
  bot remplacera), `docs/site-passation.md` (S0 à S5 sur `main`, S4 : pionniers, « Relier Discord », admission), le
  cadrage, le plan et sa critique, les cinq maquettes Discord. Écrit : `docs/discord.md`, ce fichier, une ligne dans
  `docs/README.md` et dans `CLAUDE.md`. Suite : vérificateur, fusion, puis D0.
- 5 novembre 2026, 15 h 31 à 16 h 05 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d0`).
  D0 : ADR 0007 (JDA 6.7.0), le paquet `discord/` (`BotConfig`, `ServerPlan`, `DiscordServer`, `PlanApplier`,
  `DiscordBot`, `BoundedQueue`, `jda/JdaServer`), le plan du serveur et son schéma, `Config.kt` et `Main.kt`, les tests
  sur le faux Discord, `tools/verifier_plan_discord.mjs` et son étape, `anti_ia.py`, `core/README.md`, les quatre
  variables dans `deploy/exemple.env.txt`. Gradle vert (JDA chargé : 51 Ko de tas, 318 Ko hors tas), outillage vert.
  Reste de D0 : le serveur de test et la mesure connectée, après Kr. Rebase sur `origin/main` (les votes y sont
  arrivés : conflits dans `Config.kt` et `core/README.md`, les deux côtés gardés, les votes d'abord), `tools/verifier.sh`
  vert, les quatre variables ajoutées au tableau de `docs/deploiement.md` comme la direction du jeu le demande
  (`docs/passation.md`, 15 h 43), second rebase sur les commits du site, fusion en avance rapide sur `main`. Suite : D2.
- 5 novembre 2026, 16 h 05 à 16 h 30 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  D2, le socle : `DiscordEvent` et `Reply`, `BotMessage` et ses composants, `BotRuntime` (file bornée,
  `CoroutineTimer`, fonctions isolées), `DiscordServer` élargi, `PlanReport.ids`, `jda/JdaMessages`, `jda/JdaEvents`,
  `JdaConnection` avec les intentions et le cache des membres, `FakeConnection`, `FakeReply`, `FakeTimer`,
  `BotRuntimeTest`, `DiscordBotTest` refait. JDA 6 range `Modal` dans `net.dv8tion.jda.api.modals`. Gradle vert
  (30 tests Discord), anti-IA vert, `tools/verifier.sh` vert. En parallèle, un sous-agent a ajouté la marque `silooo`
  aux outils de rendu (`Documents\minecraft\contenu-reseaux\_outils`) : vérifié, rien rendu, le rendu de contrôle
  attend Kr. Suite : fusion sur `main`, puis `definitions/discord.json`, la migration V8 et l'accueil.
- 5 novembre 2026, 16 h 30 à 16 h 50 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Le socle fusionné en avance rapide sur `main` (8919727). Puis `definitions/discord.json` et
  `definitions/schemas/discord.schema.json` (les chiffres du QG, listés dans « À valider par Kr »), `DiscordDefinitions`
  (chargé à part du jeu, vérifié contre le plan : rôles, salons, niveaux qui montent, fuseau), cinq tests, les types de
  l'add-on régénérés (`tools/generer_types.mjs` prend tout `definitions/`), la migration `V8__discord.sql` avec les
  seize tables du QG (pas jouée sur le PC : Docker éteint, la CI la joue), `docs/README.md` et `core/README.md`.
  `tools/verifier.sh` vert. Suite : l'accueil guidé, les salons et les rôles de la phase `attente`.
- 5 novembre 2026, 16 h 50 à 17 h 33 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Les chiffres et la migration fusionnés sur `main` après une fusion d'`origin/main` (les cosmétiques et V9 y étaient
  arrivés : la migration du Discord renumérotée V10, Flyway sans `outOfOrder` aurait refusé V8 après V9). Puis
  l'accueil guidé : la clé `accueil` du plan et son schéma, le vérificateur et quatre cas refusés de plus,
  `PlannedOnboarding` dans `ServerPlan`, `Onboarding.kt` (`OnboardingState`, `OnboardingPlanner`, `OnboardingSync`,
  `OnboardingApplier`), `discord/rest/DiscordRest` (Ktor, `MockEngine` en test), `DiscordServer.onboarding` et
  `setOnboarding` (JDA, faux), `PlanReport.onboarding` et sa ligne dans le journal, `DiscordRefusal` remonté dans
  `discord/`, `#💡・suggestions` dès l'attente, les vocaux d'info en écriture bot seul (décision 9). Le Guide du serveur,
  sans API, écrit pour Kr dans `docs/discord-guide.md` avec le brief des trois plaques (décision 10, cases D1 et Kr).
  Gradle vert (40 tests Discord, dont 10 nouveaux), outillage vert (165). Suite : `tools/verifier.sh`, fusion sur
  `main`, puis le bonjour d'Oooscar, les arrivées groupées, le tuto en privé, les rôles de notification, le statut, les
  vocaux d'info.
- 5 novembre 2026, 17 h 41 à 18 h 02 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Le bonjour d'Oooscar, les arrivées groupées, le tuto en message privé, le statut et le vocal d'info « N en ligne » :
  `MemberStore` (`MemberRecord`, `JdbcMemberStore` sur `discord_members`, V10), `Welcome.kt` (`WelcomeTexts`,
  `WelcomeFeature`), `Status.kt` (`StatusFeature`), `InfoVoice.kt` (`InfoVoiceFeature`, `RenameBudget`), `BotFeatures`
  branché dans `Main.kt` avec les joueurs en ligne de `ServerPresence`, `InMemoryMemberStore`, `WelcomeTest`,
  `StatusAndInfoVoiceTest`, `MemberSqlTest` (MariaDB, ignoré sans Docker), `core/README.md`, et ici cinq décisions (11 à
  15) et les textes à valider. À la demande de Kr (la session cadre et délègue, un modèle par tâche), deux agents
  Sonnet ont fait d'un côté la compilation et les tests, de l'autre le README et cette passation ; un agent Haiku
  relève l'API pour les coulisses. Tests Discord verts (`WelcomeTest` 8, `StatusAndInfoVoiceTest` 3, `MemberSqlTest`
  ignoré sans Docker) après une attente de test corrigée (le second bonjour respecte la fenêtre de 30 s) et un quota
  resserré (`RenameBudget` compte encore le renommage vieux d'une fenêtre pile) ; `tools/verifier.sh` vert à 18 h 02.
  Rien n'a tourné sur un vrai serveur. Suite : fusion sur `main`, puis les coulisses et `/spoil`.
- 5 novembre 2026, 18 h 05 à 18 h 27 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Les coulisses : `SpoilStore` (`Spoil`, `JdbcSpoilStore` sur `discord_spoils`, V10), `Spoils.kt` (`SpoilTexts`,
  `SpoilFeature`), `Feature.commands` dans `BotRuntime` (l'union des commandes posée une fois avant le démarrage),
  `OptionKind` sur `CommandOption`, `CommandUsed.files` et la clé `fichier-refuse`, `DiscordServer.reactors`, le
  téléchargement des pièces jointes et la réponse différée dans `jda/JdaEvents`, le cache des emojis dans `jda/JdaServer`,
  `BotFeatures`, `InMemorySpoilStore`, `SpoilTest`, `SpoilSqlTest` (MariaDB, ignoré sans Docker), deux cas de plus dans
  `BotRuntimeTest`. Écrit par un agent Sonnet sur un brief de la session (Kr veut une session qui cadre et délègue),
  relu par la session ; les écarts sont consignés dans les décisions 16 à 23. Tests Discord verts (`SpoilTest` 16,
  `BotRuntimeTest` 6) ; vérificateur en cours à l'heure de l'écriture. Rien n'a tourné sur un vrai serveur : le code JDA
  des pièces jointes et des réactions compile mais ne se teste que sur le serveur de test. Suite : vérificateur, fusion
  sur `main`, puis les admissions et `/annonce`.
- 5 novembre 2026, 18 h 32 à 18 h 59 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Les admissions et `/annonce` : `AdmissionStore` (`Registration`, `Admission`, `JdbcAdmissionStore` sur
  `discord_admissions`, V10), `Admissions.kt` (`AdmissionTexts`, `AdmissionFeature`), `SiteRegistrationStore.admit`
  (`SiteRegistrations.kt`, `JdbcSiteRegistrationStore.kt`), le bloc `admissions.relectureSecondes` de
  `definitions/discord.json` et de son schéma, `BotJournal`, `Announce.kt` (`AnnounceTexts`, `AnnounceFeature`),
  `wording/AntiIa.kt` (le garde anti-IA en Kotlin, décisions 24 et 25), `BotFeatures`, `InMemoryAdmissionStore`. Tests
  écrits : `AdmissionTest` 17, `BotJournalTest` 3, `AnnounceTest` 16, `DiscordDefinitionsTest` 5, `AntiIaTest` 59,
  `AdmissionSqlTest` et le cas `admit` de `SiteRegistrationSqlTest` (MariaDB, ignorés sans Docker). Les décisions 24 à 32
  et les textes à valider sont ci-dessus ; `core/README.md` à jour. Rien n'a tourné sur un vrai serveur ; `/annonce`
  n'écrit pas encore dans les Actus du site (décision 31) et le rôle et l'invitation d'un inscrit non relié attendent
  `/lier` (décision 29). Suite : vérificateur, fusion sur `main`, puis les tickets, « Crée ton vocal », l'XP et les
  giveaways.
- 5 novembre 2026, 19 h 03 à 19 h 34 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Les tickets, « Crée ton vocal », l'XP de tchat et les giveaways : `BotMessageStore` (`JdbcBotMessageStore` sur
  `discord_bot_messages`), `TicketStore` (`JdbcTicketStore`), `Tickets.kt` (`TicketTexts`, `TicketFeature`), `VoiceRooms.kt`
  (`VoiceRoomFeature`), `XpStore` (`JdbcXpStore`), `Xp.kt` (`XpTexts`, `XpFeature`), `GiveawayStore` (`JdbcGiveawayStore`,
  `GiveawayPrize`), `Giveaways.kt` (`GiveawayTexts`, `GiveawayFeature`), le socle élargi (`ServerIds.categories`,
  `ChannelSpec.memberViewers`, `ChannelMessage`, `deleteChannel`, `history`, `moveToVoice`, `voiceMembers`, `memberName`,
  `MessagePosted.parentChannelId`), `BotFeatures` et le règlement en brouillon `docs/discord-reglement-giveaways.md`.
  Écrit par trois agents en parallèle sur des fichiers disjoints (les tickets et les vocaux, l'XP, les giveaways), relu par
  la session. Tests verts sur les fausses briques : `TicketTest` 19, `VoiceRoomTest` 12, `PlanCategoryIdsTest` 2, `XpTest` 20,
  `GiveawayTest` 18 ; `TicketSqlTest`, `BotMessageSqlTest`, `XpSqlTest` et `GiveawaySqlTest` (MariaDB, ignorés sans Docker :
  la CI les joue). Les décisions 33 à 46 et les textes à valider sont ci-dessus ; `core/README.md` à jour. Rien n'a tourné
  contre un vrai Discord, rien n'est encore commité et `tools/verifier.sh` n'a pas encore tourné. Restent : le crédit en
  jeu des lots (route du contrat, décision 44), le marquage de `etape` en base (45) et le calendrier Discord (46). Suite :
  vérificateur, fusion sur `main`, puis l'AutoMod, les tableaux et les petits jeux.
- 5 novembre 2026, 19 h 47 à 20 h 22 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  L'AutoMod, `#bienvenue`, les quatre tableaux, le comptage et le monte-charge. Le socle élargi par la session (décision 47),
  puis trois agents Sonnet en parallèle sur des fichiers disjoints (l'AutoMod et `#bienvenue` ; les quatre tableaux ; le comptage,
  le monte-charge et les records), relus par la session. Deux retouches de revue : l'icône du serveur n'écrase jamais celle de Kr
  (décision 50), les records au Panthéon sont lissés à une modification par 60 secondes (décision 57). Écrit : `Automod.kt`
  (`AutomodRules`, `AutomodFeature`, `automod.json`), `Bienvenue.kt`, `Boards.kt` (`LiveMessage`), `Reactions.kt` (`ReactionStore`),
  `Highlights.kt` (`HighlightStore`), `Help.kt` (`HelpStore`), `Chantier.kt`, `Counting.kt`, `Elevator.kt`, `Records.kt`,
  `GameStore.kt`, le bloc `bienvenue` de `definitions/discord.json` avec son schéma et les types de l'add-on régénérés, tout
  branché dans `BotFeatures` (le paramètre `siteUrl`). Suite Discord verte : `AutomodTest` 14, `BienvenueTest` 21, `ReactionTest`
  13, `HighlightTest` 12, `HelpTest` 19, `ChantierTest` 9, `CountingTest` 20, `ElevatorTest` 23, `RecordsTest` 12 ;
  `ReactionSqlTest`, `HighlightSqlTest`, `HelpSqlTest` et `GameSqlTest` (MariaDB) sautés sans Docker, la CI les joue. Fusion de
  `origin/main` (970305d), vérificateur lancé. Les décisions 47 à 58 et les textes à valider sont ci-dessus ; `core/README.md` à
  jour. Rien n'a tourné contre un vrai Discord. Suite : le rapport du lundi dans `#journal-du-bot`, puis la passation et la fusion
  sur main.
- 5 novembre 2026, 20 h 26 à 20 h 33 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  La décision de Kr reçue à 19 h 57 : un seul serveur Discord, Silooo, pas de serveur de test ; le bot y travaille
  directement, depuis le PC, et le serveur reste fermé au public jusqu'à l'ouverture du QG. Docs mises à jour : ce fichier
  (« Décisions de Kr », décision 59, « Lancer le bot depuis le PC », « Essais à nettoyer avant l'ouverture », les étapes
  D0, D2, D4 et D6, « À faire par Kr », « Prêt à mettre en ligne »), `docs/discord.md` (le serveur Silooo à la place du
  serveur de test, les secrets) et `core/README.md` (par un agent Sonnet). Le garde-fou `ClosedServer` et la variable
  `SILOOO_DISCORD_PUBLIC` sont codés en parallèle par un agent Sonnet (décision 59). Ce qu'il manque pour lancer depuis le
  PC : les variables du compte pas encore visibles du terminal de la session (relancer l'application Claude), une base
  MariaDB (aucune installée, Docker Desktop éteint) et une `SILOOO_API_KEY` locale. Rien n'a encore tourné contre un vrai
  Discord. Suite : le premier passage à blanc dès que les variables du compte sont visibles et qu'une base est là ; puis
  le rapport du lundi.
- 5 novembre 2026, 20 h 40 à 20 h 58 (session Discord, PC, worktree `silooo-discord`, branche `claude/discord-d2`).
  Kr décide vers 20 h 40 de lancer le bot depuis son propre terminal, avec la base dans Docker Desktop (« Décisions de Kr »,
  point 2). La session écrit `tools/bot-pc.ps1` : il vérifie Docker Desktop et les variables du compte, crée ou redémarre le
  conteneur `silooo-mariadb`, applique les migrations, puis lance le bot en `-Mode a-blanc` (le défaut) ou `oui`, avec un mot
  de passe de base local gardé hors du dépôt et une clé d'API tirée à chaque lancement (décision 60). Le classificateur de
  permissions a refusé que la session démarre Docker Desktop elle-même : c'est Kr qui le démarre. Le script n'a tourné que
  sans Docker (son premier garde-fou). Docs mises à jour par un agent Sonnet : ce fichier (« Lancer le bot depuis le PC »,
  « Décisions de Kr » point 2, décision 60) et `core/README.md`. Rien n'a encore tourné contre un vrai Discord. Suite : Kr
  démarre Docker Desktop et lance `.\tools\bot-pc.ps1` à blanc ; la session lit le compte rendu, note les essais dans
  « Essais à nettoyer avant l'ouverture », puis le rapport du lundi.
