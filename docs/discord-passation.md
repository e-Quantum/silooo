# Passation du Discord : l'état du chantier

Dernière mise à jour : 5 novembre 2026, 16 h 30, D2 (session Discord).

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
  Aucune fonction du QG n'existe encore : elles viennent case par case, avec `definitions/discord.json` pour leurs
  chiffres et la migration V8 pour leurs tables.
- Numéros : l'ADR **0007** est écrit (`docs/adr/0007-bot-discord-jda.md`, à valider par Kr) ; la migration **V8** reste
  réservée pour la première table du bot (D2 ou D3, rien en D0).

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
- [ ] Les salons et les rôles de la phase `attente` dans le plan, l'accueil guidé (deux questions, rôles de plateforme
  et de mention), le Guide du serveur et ses trois cartes (plaque émaillée et icône, en pixel)
- [ ] Le bonjour d'Oooscar dans `#💬・discussion` (5 à 10 s après l'accueil guidé, selon la plateforme), les arrivées
  groupées (30 s, 10 mentions au plus, puis une ligne), le tuto en message privé avec le bouton « Mon tuto » en
  secours, les rôles de notification, le statut alterné, les trois vocaux d'info renommés deux fois par dix minutes
  au plus
- [ ] Les coulisses : `/spoil`, l'aperçu des réactions, les seuils, la publication par le seul bot, l'attente jusqu'à
  7 h, le registre en base
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
2. **Numéros.** ADR 0007 est écrit ; la migration V8 reste réservée par la session Discord (D2 ou D3).
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
- [ ] Passer les deux serveurs (test, puis Silooo) en serveur Communauté : accueil guidé, forums, annonces
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

## À valider par Kr

- Les noms des salons et des catégories, leurs emoji et leurs sujets, les noms des rôles et leurs couleurs
  (`core/src/main/resources/discord/plan-serveur.json`, D0).
- Les lignes du compte rendu d'Oooscar dans `#📒・journal-du-bot` (`PlanReport.text`, D0) : « Plan du serveur, phase
  attente. », « J'ai créé N rôles, N catégories et N salons. », « Je ne supprime jamais rien. », « Il manque… » à blanc.
- Le bonjour d'Oooscar et le tuto en privé, le format « Gamertag · P3 », les lignes d'Oooscar et les textes des
  rappels : écrits à D2 et D3, listés ici au fur et à mesure.
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
