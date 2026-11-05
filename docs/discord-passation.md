# Passation du Discord : l'état du chantier

Dernière mise à jour : 5 novembre 2026, 18 h 27, D2 (session Discord).

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
- Le bot n'a encore jamais parlé à Discord : le plan s'applique au faux Discord dans les tests ; le serveur de test
  attend l'application et le jeton de Kr (« À faire par Kr »).
- `CLAUDE.md` ne contient pas l'exception Discord du plan : la session n'agit sur Discord que par le bot de test, et
  tout réglage que l'API ne permet pas va dans « À faire par Kr » avec sa marche à suivre.
- D0 est sur `main` ; il en reste le serveur de test (après Kr) et la mesure mémoire connectée.
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
  `Main.kt`, avec les joueurs en ligne de `ServerPresence`. Ils n'ont jamais été vus tourner sur un vrai serveur : aucun
  bot de test n'a été lancé dans cette session, seuls les faux Discord des tests les ont joués.
- Les coulisses sont écrites et branchées (`SpoilStore`, `JdbcSpoilStore`, `SpoilFeature`, `/spoil`, `Feature.commands` pour
  poser les commandes) : jamais vues sur un vrai serveur. Le code JDA des pièces jointes (`JdaEvents`) et des réactions
  (`JdaServer.reactors`) compile mais ne se teste que sur le serveur de test.
- Numéros : l'ADR **0007** est écrit (`docs/adr/0007-bot-discord-jda.md`, à valider par Kr) ; la migration **V10**
  est prise (`V10__discord.sql`, toutes les tables du QG ; V8, réservée au départ, reste un trou : V9 est partie sur `main` avant). Le jeu prend V11 et la suite ; une table du Discord qui change
  prendra le prochain numéro libre, annoncé ici.

## Les étapes

La session enchaîne D0, D2, puis D3 à D6 ; D1 avance en parallèle, pendant les rendus. Une étape qui attend Kr ou la
direction du jeu n'arrête pas les autres : sa case va dans « À faire par Kr » ou « Pour la direction du jeu ». Au début
de chaque étape, la session relit ses cases et ajoute celles qui manquent.

### D0 : le socle

Critère de fin : le plan s'applique au faux Discord, puis au serveur de test (à blanc, puis pour de vrai), et
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
- [ ] La mémoire connectée : la JVM mesurée avec le bot sur le serveur de test, à blanc, chiffre ici (**après Kr**)
- [x] Le plan appliqué au faux Discord (`PlanApplierTest` : attente, bêta après attente, à blanc, refus isolé, dérive)
- [ ] Le plan appliqué au serveur de test « Silooo Test » à blanc, puis pour de vrai (**après Kr** : l'application
  « Oooscar Test », son jeton dans un fichier local, l'invitation sur le serveur de test, le mode Communauté)

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

Critère de fin : sur le serveur de test, une arrivée se joue de bout en bout (accueil, bonjour, tuto, rôle, spoil
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
- [ ] Les admissions : les cartes lues dans `site_registrations` (S4 du site), Admettre, Refuser, Plus tard ; `/annonce`
  avec le contrôle anti-IA avant l'aperçu
- [ ] Les tickets par menu, « Crée ton vocal », l'XP de tchat (un message par minute, rôles aux niveaux 5, 10, 20, 30,
  classement de la semaine), les giveaways (lot gardé sur l'identifiant Discord, crédité à la liaison prouvée) et leur
  règlement en brouillon
- [ ] L'AutoMod posé par l'API (la liste du filtre, le spam de mentions, les liens), l'icône et l'image d'accueil de
  `#👋・bienvenue` posées par le bot
- [ ] Les tableaux du QG : top réactions de la semaine, meilleurs moments, Aide du mois, le chantier en direct, avec
  `#🏛️・panthéon` et `#⭐・meilleurs-moments`
- [ ] Les petits jeux de la catégorie ANIMATION : `#🔢・comptage` et `#🛗・monte-charge`, records au Panthéon
- [ ] Le rapport du lundi dans `#📒・journal-du-bot` : arrivées, accueil terminé, comptes reliés sous 7 jours, membres
  revenus après 7 jours, messages par jour, d'où viennent les arrivants, messages privés échoués, signalements
- [ ] Une arrivée rejouée de bout en bout sur le serveur de test, captures dans `docs/references/discord/captures/`
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

Critère de fin : un message passe dans les deux sens sur le serveur de test, et un message interdit ne passe dans
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

- [ ] Le serveur de test rejoué en entier, captures dans `docs/references/discord/`, la liste de Kr à jour
- [ ] « Prêt pour la bêta » écrit ici, avec ce que Kr doit avoir fait avant ; la direction du jeu déploie

## Prêt à mettre en ligne

Pas encore. Deux mises en ligne sont prévues, chacune écrite ici avec ce que Kr doit avoir fait avant :

1. **Le QG**, avec la page d'attente du site (fin de D2) : Kr pose le jeton du vrai bot dans le fichier d'environnement
   du VPS, l'invite sur le serveur Silooo avec les droits listés, et donne l'identifiant du serveur. La direction du
   jeu déploie avec `SILOOO_DISCORD_ACTIF=oui`. Le premier passage se fait à blanc : Kr lit l'écart dans
   `#📒・journal-du-bot`, puis le bot applique le plan.
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

## Pour la direction du site

1. La liaison Discord des pionniers (S4) ne prouve pas la possession du gamertag ; le bot ne donne pseudo, rôles, lots
   et votes qu'après le code montré en jeu (D3). Le site peut afficher « relié » ; « prouvé » viendra du jeu.
2. La case « Rejoindre aussi le Discord » à l'inscription demande la portée `guilds.join` en plus d'`identify` ; à
   prévoir quand Kr aura posé l'application.
3. Le règlement des giveaways et la page Confidentialité (journal du tchat relié gardé 30 jours) : textes à publier sur
   le site, fournis en brouillon par la session Discord.
4. Une page « Mises à jour » du site devient une carte dans `#🆕・patch-notes` ; un champ de la page dira si elle passe
   aussi dans `#📢・annonces`.

## À faire par Kr

- [ ] Créer le serveur « Silooo Test » et l'application « Oooscar Test » ; activer les intentions « membres du serveur »
  et « contenu des messages » ; l'inviter sur le serveur de test avec les droits de gérer les salons, les rôles, les
  pseudos et les webhooks ; poser son jeton et l'identifiant du serveur dans un fichier local d'environnement que la
  session lancera sans l'ouvrir (dire son chemin). Les variables attendues : `SILOOO_DISCORD_ACTIF=a-blanc`,
  `SILOOO_DISCORD_TOKEN`, `SILOOO_DISCORD_SERVEUR` (l'identifiant numérique), avec celles de la base et de la clé
  (`core/README.md`, « Le Discord »)
- [ ] Passer les deux serveurs (test, puis Silooo) en serveur Communauté : accueil guidé, forums, annonces. Discord
  demande un salon des règles (`#👋・bienvenue`) et un salon des mises à jour (`#📒・journal-du-bot`), que le bot a créés
  au premier passage (`docs/discord-guide.md`, 1)
- [ ] Poser le Guide du serveur à la main (l'API ne le permet pas) : le mot d'accueil, les trois tâches des nouveaux,
  les trois rubriques avec leurs textes, puis leurs images quand D1 les aura rendues (`docs/discord-guide.md`, 3) ; lire
  dans `#📒・journal-du-bot` que l'accueil guidé est posé, et dire si Discord a refusé
- [ ] Pour le vrai serveur, à la mise en ligne du QG : l'application et son jeton dans le fichier d'environnement du
  VPS, l'invitation avec les mêmes droits, l'identifiant du serveur, les rôles Staff et Modération donnés à la main
- [ ] S'il veut que la session règle le serveur par le contrôle à distance (badge, captures, ce que l'API ne permet
  pas) : coller l'exception Discord du plan dans la section du mode autonome de `CLAUDE.md`. Sans elle, ces réglages
  restent ici avec leur marche à suivre
- [ ] Connecter le dossier `Documents\minecraft` à la session pour les rendus (D1) ; prévenir si le PC n'est pas
  disponible
- [ ] D1 : dire quand la session peut lancer le rendu de contrôle SileneMC (la marque `silooo` est dans
  `Documents\minecraft\contenu-reseaux\_outils`), et si Pillow lit les polices woff2 du dépôt ; sinon, des TTF dans
  `_outils\assets\fonts\`
- [ ] Les coulisses : l'emoji de serveur `:silo:` doit exister (D1) ; tant qu'il manque, le compte des réactions reste à
  zéro et le spoil suivant ne part pas. Puis rejouer `/spoil` sur le serveur de test avec une image : le premier spoil
  part tout de suite avec le rôle Coulisses, le second attend, les réactions `:silo:` font monter la ligne d'état jusqu'au
  seuil ; dire si Discord a refusé la pièce jointe, la réponse différée ou la modification du message
- [ ] Choisir les paliers de prestige qui donnent un rôle, et trancher les rôles liés de Discord (option, ou rien)
- [ ] Retirer les récompenses par invitation de SileneMC (`#🎁・inviter`), interdites par Discord (critique, point 14)
- [ ] Plus tard, aux boosts : le badge SILO à 3 boosts, la bannière en arrière-plan d'invitation à 2, en haut des
  salons à 7, animée à 14

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

## À valider par Kr

- Les noms des salons et des catégories, leurs emoji et leurs sujets, les noms des rôles et leurs couleurs
  (`core/src/main/resources/discord/plan-serveur.json`, D0).
- Les chiffres de `definitions/discord.json` (D2) : le bonjour à 10, 7 et 5 s selon console, téléphone et PC, les
  arrivées groupées (30 s, 10 mentions), le statut qui alterne chaque minute, les spoils (40 % des membres proposés,
  compte chaque minute, nuit de 22 h à 7 h), l'XP (15 à 25 points, une fois par minute, niveau n à 100 × n + 50 × n ×
  (n - 1) / 2 points, rôles aux niveaux 5, 10, 20, 30, dix au classement), le top réactions à dix, les meilleurs
  moments à 5 `:prestige:`, le monte-charge toutes les 5 minutes, le rapport du lundi à 9 h, trois rappels par jour.
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
- Le format « Gamertag · P3 », les lignes d'Oooscar et les textes des rappels : écrits à D3, listés ici au fur et à
  mesure.
- Le nom du bot, Oooscar, et son avatar ; la bannière, l'icône, les couleurs des rôles et les emojis du serveur (D1).
- La fiche Silooo des outils de rendu (`_outils\references\marque.md`, D1) : le « ooo » en rouge clair `#e0614f` sur
  fond sombre, le gris de texte pris sur `email-creme`, le détail, l'IP et le lien Discord affichés, les polices
  bitmap lissées à toute taille alors que la règle dit « sans lissage ».
- Les lots des concours, le règlement des giveaways, les paliers du parrainage (D5).
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
