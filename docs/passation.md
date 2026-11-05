# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, 14 h 28, les Gemmes et les grades fusionnés, tranche des votes lancée (session de direction).

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions et leurs schémas), le plan, le
  game design, le design system, la documentation (`docs/`, ADR 0001 à 0005), l'outillage (`tools/`,
  CI, hooks, trois recettes) et, dans `core/`, le squelette de silooo-core (Gradle, Ktor, Flyway, clé
  `X-Silooo-Key`, heartbeat, filet 501, Dockerfile) avec les données dorées du tableur
  (`core/src/test/resources/golden/`), les routes de session (`join`, `leave`, `GET /v1/events`,
  idempotence, verrou optimiste, journal des monnaies, tests de contrat sur les 32 opérations) et le moteur
  d'économie avec ses tests dorés, branché sur `join` (`EngineFigures`, `DefinitionsBootstrap`). Huit
  fichiers de définitions : `usines`, `economie`, `boutique`, `monde`, `iles`, `missions`, `personnel`, `incidents`.
- `main` est vert : `tools/verifier.sh` passe sur le PC (tests Gradle compris, tests Docker ignorés) et
  la CI passe (jobs `verifier` avec les deux tests Testcontainers, `migration`, `image`).
- Sur le VPS : `/var/www/silooo` existe avec un fichier d'environnement posé par Kr. La base `silooo`
  n'est pas confirmée. Le VPS n'a pas d'accès GitHub.
- Sur le PC de Kr : Node 24, Java 17, uv (Python géré), Docker Desktop installé mais pas lancé. Pas de
  Gradle global (le wrapper suffit ; Gradle 9.8 est en cache).
- Phase **1 close** le 5 novembre 2026 à 6 h 40 : toutes les cases cochées, `tools/verifier.sh` vert sur `main`
  (tests Gradle compris), CI verte, image de silooo-core publiée depuis `main`. Ce qui n'est pas de la phase 1
  et reste pour la phase 3 : les routes d'économie (`getCompany`, `collect`, `buyLevels`, …) et la mécanique
  d'énergie (`stoppedNoPower` toujours faux).
- Phase **2** (Bedrock) : tout ce qui ne dépend pas de Kr est sur `main` (`addon/`, `deploy/`, images `silooo-core`
  et `silooo-bds` publiées par la CI). Restent le déploiement, le test console et la mesure de la mémoire, qui
  attendent la base, le port, le zip de BDS et le DNS (« À faire par Kr »).
- Phase **3** (la première heure) **close sauf le test de bout en bout**, qui attend le déploiement (Kr). Tout le
  reste est sur `main` : les quatre routes d'économie et la mission de bienvenue côté service ; côté add-on, le HUD,
  le menu, la fiche d'usine, le Silo, les mini-îles des usines, l'arrivée, la cinématique, Oooscar et la mission de
  la mairie (tuile Objectifs, 631 tests) ; le pack de ressources.
- Phase en cours : **4** (la progression). `island`, `prestigePreview`, `prestige` et les missions sont sur `main`
  et le simulateur des treize cibles les rejoue en CI (toutes dans leur fourchette) ; le personnel aussi (agence
  du jour, embauche, affectation, renvoi, salaires retenus sur le revenu), la commande du jour et le Carnet de
  trouvailles (`todayOrder`, `deliverOrder`, `getFindings`, migration V3) ; côté add-on, l'écran de prestige (avant,
  après), la carte des îles avec la téléportation les écrans du personnel et de l'agence, les incidents et l'électricité avec le mini-jeu de réparation, les
  écrans de l'entreprise partagée (1177 tests). La ville côté service (`buyZone`, `placeBuilding`,
  `upgradeBuilding`, migration V6, `definitions/ville.json`) est sur `main` ; l'add-on ne la montre pas encore.
  En cours : l'add-on de la ville et de l'île principale (`claude/phase4-addon-ville`). Phase 5 ouverte le
  5 novembre 2026 (décision 39) ; sur `main` : les livraisons de Gemmes, les grades et le Pilier côté service
  (paquet `shop/`) ; en cours : les votes et la Vote party (`claude/phase5-votes`).

## Phase 0 : le dépôt prêt pour les sessions Claude

- [x] `docs/README.md` complet : la carte de lecture du plan, tâche par tâche
- [x] Les fichiers de `docs/` listés dans le plan : vision, glossaire, architecture, économie, base de données, bedrock, design, anti-ia, déploiement
- [x] Schémas JSON des trois fichiers de `definitions/`, et leur vérification
- [x] Migration V1 rejouée sur MariaDB 12.3 (dans la CI, service `mariadb:12.3.3`)
- [x] ADR 0001 à 0005 : dépôt unique, service autoritaire, base partagée, port Bedrock, langues du code
- [x] `tools/verifier.sh` et `tools/anti_ia.py`
- [x] `.github/workflows/ci.yml` qui lance `tools/verifier.sh`
- [x] `.claude/settings.json` : hooks (anti-IA après écriture) une fois `tools/anti_ia.py` en place
- [x] Les trois recettes de `.claude/skills/` : equilibrage, ecran, route
- [x] Test : une session neuve reçoit « ajoute une usine » et trouve seule les bons fichiers

## Phase 1 : silooo-core

- [x] Projet Gradle Kotlin (Ktor), build et tests en local et dans la CI (`actions/setup-java` 17, cache Gradle)
- [x] Flyway avec la migration V1, test de migration Testcontainers sur MariaDB 12.3 (ignoré proprement sans Docker, jamais faux-vert ; passé dans la CI)
- [x] Clé `X-Silooo-Key`, `POST /v1/server/heartbeat` (toute route `/v1` pas encore écrite répond 501)
- [x] `session/join` et `session/leave` (`SessionService`, `JdbcSessionStore`, tests SQL sur MariaDB 12.3.3 dans la CI)
- [x] `GET /v1/events` avec curseur, et `publishEvent` pour les routes qui ont quelque chose à dire
- [x] Idempotence par `requestId` (table `api_requests`, aide `call.idempotent`, purge réglable)
- [x] Verrou optimiste sur `companies.version` (`updateCompany`, 409 `CONFLIT_REESSAYER`)
- [x] Journal des monnaies dans la même transaction que le solde (`creditBalance`)
- [x] Données dorées extraites du tableur (`core/src/test/resources/golden/`) et script d'extraction rejouable
- [x] Moteur d'économie (prix des niveaux, production, paliers, prestige, notation) et tests dorés
- [x] Tests de contrat sur les 29 opérations de l'OpenAPI (les routes pas faites répondent 501)
- [x] Brancher le moteur sur `join` (`EngineFigures` : revenu, multiplicateur, seuil, hors ligne compté depuis `last_collect_at` ; `DefinitionsBootstrap` : la Carrière de départ, le plafond hors ligne et la grille des cases de `monde.json`) ; tests SQL `EngineFiguresSqlTest` passés dans la CI
- [x] Contrat : le 409 `CONFLIT_REESSAYER` est déclaré sur les six opérations qui peuvent le rendre (`join`, `collect`, `resolveIncident`, `fireStaff`, `assignStaff`, `deliverOrder`) ; `KNOWN_409_GAPS` est vide dans le test de contrat
- [x] Image Docker de silooo-core construite par la CI, publiée sur le registre GitHub depuis `main`

## Phase 2 : Bedrock

Condition de sortie (plan) : un joueur sur console rejoint `play.silooo.com` et apparaît sur son île ;
mesure de la mémoire réelle. Les deux demandent la base, le port et le DNS : voir « À faire par Kr ».

- [x] `docs/bedrock.md` : les faits « à relever » relevés pour la version épinglée (modules et versions, fichier de permissions, téléchargement de BDS, réseau, limites), avec leurs sources et leur fiabilité ; reste une courte liste « Reste à relever » (fichiers livrés dans le zip, activation de « Beta APIs », erreurs réseau), à vider sur le BDS de test
- [x] `deploy/bds/` : image `silooo-bds` sans BDS (décision 26 : `start.sh` prend le zip posé dans `/data/bds`, contrôle la SHA-256 de `bds.sha256` ou le MD5 de `bds.md5`, décompresse une fois dans `/data/bds/<version>/`), utilisateur `silooo` uid 10001, monde et `config/` dans des volumes nommés, `permissions.json` rendu depuis le manifest de l'add-on, `server.properties.template` (port 19142 TCP, plage UDP)
- [x] `deploy/compose.yml` : projet `silooo`, services `silooo-migrate` (256 Mo), `silooo-core` (512 Mo, aucun port) et `silooo-bds` (1,5 Gio, `restart: on-failure:5`, bind mount `SILOOO_BDS_DIR`), réseau de SileneMC rejoint en externe (`SILENEMC_NETWORK`), secrets par `env_file` ; modèle `deploy/exemple.env.txt`
- [x] `deploy/scripts/deploy.sh <tag>` : mémoire et disque, sauvegarde (`sauvegarde.sh`), tirage des images, migration, démarrage, test de fumée (heartbeat 204 en 60 s), retour arrière vers le tag précédent, nettoyage des seules images silooo ; refuse sans le dossier du zip ; `docs/deploiement.md` et `deploy/README.md` à jour ; tests dans `tools/tests/deploy-*`
- [x] `addon/` : projet TypeScript strict, esbuild vers un seul `scripts/main.js`, manifest aux versions de modules relevées, tests de la logique pure (`node --test`, 111 tests), paquet `.mcpack` construit
- [x] `addon/` : client `server-net` vers silooo-core (clé par `server-admin`, en-tête `X-Silooo-Key`, délai, codes `Erreur` traités d'un bloc), heartbeat périodique, `join` à l'arrivée, `leave` au départ, interrogation de `GET /v1/events` avec curseur ; tous les appels bêta dans `addon/src/beta.ts`
- [x] `tools/` : générateur des types TypeScript de l'add-on tirés de `definitions/` (`tools/generer_types.mjs`, parcourt le dossier), et contrôle « Types de l'add-on à jour » dans `tools/verifier.sh`
- [x] `tools/verifier.sh` et CI : `addon/` vérifié (compilation stricte, tests, paquet ; la CI lance le vérificateur)
- [x] CI : jobs `deploy-fichiers` (ShellCheck, `compose config` avec un fichier d'environnement factice) et `image-bds` (image construite, contrôlée : uid 10001, pack présent, aucun `bedrock_server`, `start.sh` sort en 66 sans zip ; publiée sur `ghcr.io/e-quantum/silooo-bds` depuis `main`). La sonde de téléchargement et l'essai de démarrage de 45 s n'ont pas de zip sur un runner : ils informent sans bloquer
- [ ] Déploiement sur le VPS, test depuis une console : un joueur rejoint `play.silooo.com` et apparaît sur son île (**À faire par Kr** : base, port, DNS, puis le test)
- [ ] Mesure de la mémoire réelle des conteneurs sur le VPS (**après le déploiement**, donc après Kr)

## Phase 3 : la première heure

Condition de sortie (plan) : la première heure du storyboard se joue de bout en bout. Le test de bout en bout
demande un BDS déployé (phase 2, « À faire par Kr ») ; tout le reste avance sans Kr. Ouverte le 5 novembre 2026
pour le côté service, pendant que la phase 2 écrit `deploy/` et `addon/` (décision 24).

- [x] silooo-core : `getCompany` (l'état `CompanyState` d'après le moteur : revenu, multiplicateur, seuil, usines avec prix du niveau suivant et prochain palier)
- [x] silooo-core : `collect` (production depuis `last_collect_at` en deux régimes, décision 27 ; Donar crédité à l'entreprise, événement publié)
- [x] silooo-core : `buyLevels` (`1`, `10`, `palier`, `max` par `BuyAmount`, prix du moteur, refus `PAS_ASSEZ_DE_DONAR` avec la somme manquante, paliers ×2, événement publié)
- [x] silooo-core : `restoreStep` (les quatre étapes de remise en état d'une usine `EN_RUINE` puis `REMISE_EN_ETAT` jusqu'à `ACTIVE`, gratuites tant que `definitions/` ne les chiffre pas, événement publié)
- [x] silooo-core : tests SQL sur MariaDB pour ces quatre routes (`CompanySqlTest`, 13 tests passés dans la CI), `WRITTEN` du test de contrat étendu aux huit opérations écrites, idempotence et verrou optimiste sur chacune
- [x] add-on : compteur de Donar qui défile entre deux réponses du service (`ui/compteur.ts` : revenu × temps écoulé, jamais sous le solde du service ; affichage seul)
- [x] add-on : les usines de l'époque 1 posées autour de l'île principale, une mini-île par usine (`definitions/iles.json` : plate-forme, bâtiment, panneau ; en ruine ou entière selon l'état du service), posées par lots sous ticking area, une fois par état (`addon/src/monde/`) ; `island` vient du service, l'add-on ne calcule rien (décision 30)
- [x] add-on : HUD en trois étages (`ui/hud.ts` ; le canal de titre est coupé par `config.ui.hud.titleChannel` tant que le pack de ressources ne masque pas les préfixes, tout passe par la barre d'action), menu à tuiles (boussole « Menu »), liste des usines paginée, fiche d'usine avec Monter de 1, de 10, au palier, au max (`buyLevels`), palier ×2 et coût affichés
- [x] add-on : le Silo (Encaisser → `collect`, heures hors ligne affichées) et la remise en état en quatre étapes (Parler, Réparer, Livrer, Recruter → `restoreStep`) ; rejeu une fois après `CONFLIT_REESSAYER` ; une bulle d'Oooscar par code `reason`
- [x] add-on : téléportation à l'île de la Carrière, cinématique d'arrivée (quatre plans de `iles.json`, sous-titres d'Oooscar, saut en restant accroupi une seconde), Oooscar en entité `silooo:oooscar` (`addon/pack/entities/`, une par joueur) ; textes **à valider par Kr**
- [x] silooo-core : la mission de bienvenue de la mairie (`GET /v1/players/{xuid}/missions`, `definitions/missions.json` dont la récompense est un chemin dans `economie.json` ; `collect` l'avance et la termine dans la même transaction, Lingots versés une fois, événement `ANNONCE` d'action `mission` ; décisions 29 et 31)
- [x] add-on : la première mission de la mairie affichée (`ui/missions.ts` : `getMissions` lu à l'arrivée, après chaque `collect` et à l'événement `ANNONCE` d'action `mission` ; consigne et avancement du service dans l'étage consigne du HUD à la place du seuil de prestige ; tuile Objectifs et écran OBJECTIFS ; bulle d'Oooscar à la fin par le tchat ; textes par identifiant de mission, comparés à `definitions/missions.json` par un test ; textes **à valider par Kr**)
- [x] `resource-pack/` : manifest, `pack_icon`, textures copiées octet pour octet de `design/` (contrôle `tools/verifier_pack.mjs`), JSON UI (`ui/hud_screen.json`, `ui/silooo/hud.json`) qui masque les préfixes `silooo:hud|` et `silooo:consigne|` et place monnaies et consigne en haut à gauche, Oooscar côté client (géométrie, texture générée par `tools/generer_oooscar.mjs`, animations) ; `start.sh` l'installe dans `resource_packs/silooo` et `world_resource_packs.json`, `texturepack-required=true`. Reste `config.ui.hud.titleChannel: true`, à passer après le test sur un vrai client (**Kr**)
- [ ] Test de bout en bout de la première heure sur le BDS de test (**après le déploiement**, donc après Kr)

## Phase 4 : la progression

Condition de sortie (plan) : prestige et Lingots, époques suivantes, ville et zones, incidents à réparer, carte et
îles, gains hors ligne, entreprise partagée (PDG et associés), Carnet de trouvailles ; le simulateur en Kotlin
rejoue le tableur. Ouverte le 5 novembre 2026 pour le côté service, pendant que la phase 3 finit son add-on et son
pack de ressources (décision 28). Le marché (`listMarket`, `createListing`, `buyListing`) reste « pas fait » tant
que Kr n'a pas tranché (`docs/economie.md`, « Le marché » : aucune monnaie ne circule entre joueurs).

- [x] silooo-core : `prestigePreview` et `prestige` (PDG seul, 403 `PAS_LE_PDG` ; 409 `SEUIL_NON_ATTEINT` avec les Donar qui manquent ; seuil, Lingots, rendement décroissant et départ de course lus par `EconomyEngine` dans `economie.json` ; partage selon `share_percent`, reste au PDG ; `prestige_history` ; course suivante : époque, étoiles ; Carrière au niveau de départ, autres usines en ruine ; un événement par membre ; règles **à valider par Kr**, `core/README.md` « Le prestige », décision 31)
- [x] silooo-core : simulateur Kotlin (`core/src/test/.../simulation/`, `GameSimulator`, barrière `ThirteenTargetsTest`) qui rejoue les treize cibles du tableur avec la stratégie du tableur (l'achat qui minimise prix / revenu + prix / gain, attente du prix exact, prestige au seuil) ; cibles et fourchettes dans `golden/simulation.json` ; treize temps dans leur fourchette, égaux au tableur ; `docs/economie.md` « Le simulateur » ; décision 32
- [x] silooo-core : personnel (`getCandidates`, `hire`, `assignStaff`, `fireStaff` ; `staff/`, `AgencyDraw`, `StaffService`, `StaffRoutes`, `JdbcStaffStore`, sans migration) : cinq candidats du jour tirés d'une suite figée par l'entreprise et la date (pas de tirage payant), raretés `COMMUN`, `RARE`, `EPIQUE` lues dans `definitions/personnel.json` ; prix d'embauche en heures de revenu net du moment ; salaire en pourcent du revenu de l'usine, retenu dans `ratePerHour` (net des salaires, bonus compris) ; postes par niveau d'usine, un chef par usine ; le chef relève le plafond hors ligne de son usine ; seul le PDG renvoie (403 `PAS_LE_PDG`) ; 19 tests SQL ; `core/README.md` et `docs/economie.md` « Le personnel » ; règles, noms et apparences des candidats **à valider par Kr** ; décision 33
- [x] silooo-core : incidents (`resolveIncident` ; paquet `incident/` : `IncidentDraw`, `IncidentTicker`, `IncidentService`, `IncidentRoutes`, `JdbcIncidentStore`, sans migration) et énergie (`stoppedNoPower` calculé dans `EngineFigures`) : un minuteur de trente secondes tire, pour les joueurs présents à deux passages de suite, au plus un incident par entreprise et par créneau de `frequence.creneauMinutes`, d'une suite figée par l'entreprise, le jour et le créneau (pas de hasard payant), sorte pondérée de `sortes[]`, ralentissement au régime en ligne seulement ; `resolveIncident` clôt, relance et verse `recompense.minutesDeRevenu` ; événement `INCIDENT` avec sa charge au contrat, `Factory.incidentKind` ; dès `electricite.aPartirDeEpoque`, une usine qui demande du courant s'arrête sans Centrale active, à tous les régimes, sans salaire ; `definitions/incidents.json` et son schéma ; 13 tests SQL ; `core/README.md` et `docs/economie.md` « Les incidents », « L'électricité » ; chiffres et règles **à valider par Kr** ; décision 35
- [x] silooo-core : ville (`buyZone`, `placeBuilding`, `upgradeBuilding` ; paquet `city/` ; migration V6 `ux_buildings_plot`, une parcelle un bâtiment ; `CompanyState.city` : `mairieLevel`, `nextZone`, `buildings`, `bonuses`) : zones achetées dans l'ordre, 1 à 16, au prix fixe de `ville.zonesPrixLingots`, la zone N demande une mairie de niveau N, la première demande `zones.premiere.usinesActivesMin` usines actives et pose la mairie gratuite ; bâtiments du catalogue `ville.json` posés sur une parcelle d'un chunk puis améliorés niveau par niveau, mairie non posable ; chacun paie avec ses propres Lingots ; bonus `LOGEMENTS` (salarié logé : salaire −20 %, `Staff.housed`), `PRODUCTION_DONAR` (multiplie le multiplicateur global), `LIVRAISON_VITESSE` (affiché, pas encore lu), additionnés puis plafonnés, jamais de Lingots ; la ville survit au prestige ; refus `ZONE_VERROUILLEE`, `EMPLACEMENT_OCCUPE`, `DEJA_POSSEDE`, `PAS_ASSEZ_DE_LINGOTS` ; 17 tests SQL en CI, treize cibles inchangées ; `docs/economie.md` et `core/README.md` « La ville côté service » ; règles et chiffres **à valider par Kr** ; décision 38
- [x] silooo-core : entreprise partagée (`invite`, `setShares`, et `acceptInvite` ajoutée au contrat : 32 opérations ; tables de V1, sans migration) : le PDG invite un joueur (invitation valable `entreprise.invitationHeures`, événement `INVITATION` à l'invité), l'invité accepte si son entreprise est encore à son départ (sinon 409 `ENTREPRISE_EN_COURS`), au plus `entreprise.membresMax` membres (`definitions/monde.json`, bloc `entreprise`, schéma et cas invalide) ; il entre avec une part nulle, le PDG fixe les parts (entiers qui font 100, 400 sinon) ; `CompanyState.members` (PDG d'abord, puis l'ordre d'arrivée) ; l'entreprise de départ de l'invité reste sans membre, usines et île comprises ; `core/README.md` et `docs/economie.md` « L'entreprise partagée » ; règles **à valider par Kr** ; décision 36
- [x] silooo-core : commandes du jour (`todayOrder`, `deliverOrder` ; `orders/`, `OrderPlan`, `OrderService`, `OrderRoutes`, `JdbcOrderStore`) et Carnet de trouvailles (`getFindings`, ajoutée au contrat : 31 opérations ; `findings/`, migration `V3__carnet.sql`, table `player_findings`) : ressource du jour par rang du jour UTC, objectif `commandeDuJour.objectifUnites`, prix en minutes de revenu (`minutesDeProductionParUnite`), prime en Lingots par participant à la fin et par trouvaille au premier `collect` qui la sort (`lingotsHorsPrestige.commandeDuJour`, `trouvaille`) ; `core/README.md` « La commande du jour », « Le Carnet de trouvailles », `docs/economie.md` « Les commandes du jour et le Carnet » ; règles **à valider par Kr** ; décision 34
- [x] add-on : carte et îles (`ui/menu.ts` écran CARTE, formulaire natif sur deux pages, une icône par île, « ????? » pour les usines à découvrir, île principale verrouillée ; `monde/voyage.ts` : téléportation sur l'île choisie, attente de sa pose 10 s au plus) et écran de prestige avant / après (`prestigePreview` relu à chaque ouverture, bouton seulement si le service l'autorise, parts dès deux membres « Ta part » / « Un associé », bulle « Prestige accompli, patron ! » ; textes `PRESTIGE_TEXTES`, `CARTE_TEXTES` et noms d'époque **à valider par Kr**)
- [x] add-on : écrans du personnel (`ui/personnel.ts`, `ui/ecran.ts` ; tuile Personnel, septième du menu avant Amis ; écrans PERSONNEL (équipe usine par usine, membres sans usine), MEMBRE (affecter, renvoyer en deux appuis), AGENCE et CANDIDAT (cinq candidats du jour relus à l'ouverture, prix et salaire tels que le service les donne, marque « Déjà embauché ») ; `getCandidates`, `hire`, `assignStaff`, `fireStaff` dans `api.ts`, PATCH et DELETE dans `http.ts` sans rejeu ; ligne d'équipe dans la fiche d'usine ; pas de bouton de nouvelle liste contre du Donar ; textes `PERSONNEL_TEXTES`, `AGENCE_TEXTES` et cinq bulles **à valider par Kr**)
- [x] add-on : écrans de l'entreprise partagée (`ui/associes.ts`, `ui/invitations.ts` ; tuile Associés avant Amis, repère `[N]` faute d'icône ; liste des membres avec rôle et part, Inviter parmi les joueurs connectés, formulaire des parts pour le PDG, invitation reçue acceptée ou remise à plus tard ; `invite`, `acceptInvite`, `setShares` dans `api.ts`, PUT dans `http.ts` ; `CompanyState.members` et `INVITATION` lus par le protocole ; aucun calcul ; 1177 tests ; textes **à valider par Kr**)
- [ ] add-on : écrans de la ville (zones, mairie, catalogue et pose des bâtiments, amélioration, bonus affichés depuis `CompanyState.city` ; textes **à valider par Kr**), et l'île principale posée zone par zone quand la mairie les débloque (verrouillée au départ, `docs/game-design.md` : la phase 3 ne la construit pas) : `claude/phase4-addon-ville` en cours
- [x] add-on : incidents et électricité (`ui/incidents.ts`, `ui/reparation.ts` ; `Factory.incidentKind` lu par le protocole ; la tuile, la fiche et la carte nomment la sorte ; bouton Réparer puis mini-jeu de trois formulaires par sorte, plus un général pour une sorte inconnue, un seul bon geste par étape, un mauvais choix rejoue l'étape avec une bulle, jamais de perte ; `resolveIncident` dans `api.ts`, refus 409 par le mécanisme actuel ; sans courant : « Il faut une Centrale en marche. », pas de bouton, achat de niveaux gardé ; `INCIDENT` écouté dans `main.ts` : message au tchat, puis relecture de l'état ; la récompense n'est jamais calculée, elle vient de l'événement ; 1019 tests ; textes `INCIDENT_TEXTES`, `REPARATION_TEXTES`, quatre bulles et le nombre d'étapes **à valider par Kr**)
- [x] `definitions/` : les chiffres que la phase 4 révèle manquants, ajoutés avec leurs schémas et le tableur, jamais dans le code : salaires (`personnel.json`, fait), incidents et électricité (`incidents.json`, fait), bonus des bâtiments (`ville.json`, fait : zones, mairie, logement, plafonds, catalogue de six bâtiments ; neuf fichiers de définitions) ; seul le coût de la remise en état attend Kr (« À faire par Kr »)

## Phase 5 : boutique et vote

Condition de sortie (plan) : livraisons de Gemmes depuis le site, grades et Pilier, sites de vote et Vote party, salon
Discord ; un achat réel de test est livré une fois, et une seule. Ouverte le 5 novembre 2026 à 13 h 21 (session de
direction, décision 39), pendant la dernière tranche de la phase 4. Depuis la décision 37, le site silooo.com (direction
du site) écrit les commandes PayNow dans `shop_deliveries` et les votes arrivent de Hubyy au format Votifier v2 : la
phase 5 du jeu reçoit et crédite. Ce qui touche un compte externe revient à Kr (« À faire par Kr ») : l'achat réel de
test, Hubyy et les sites de vote, le port et le jeton Votifier, le salon et le webhook Discord. Le service, l'add-on, les
définitions et la doc avancent sans attendre, et rien de ce qui en dépend n'est déployé. Le marché (`listMarket`,
`createListing`, `buyListing`) reste « pas fait » tant que Kr n'a pas tranché ; aucune lootbox ni aucun hasard payant
(`CLAUDE.md`), même si `docs/game-design.md` en parle encore.

- [x] silooo-core : livraison des Gemmes (paquet `shop/` : `DeliveryRelief` relève `shop_deliveries` toutes les `SILOOO_DELIVERY_POLL_SECONDS` secondes, 30 par défaut ; joueur retrouvé par `xuid` puis par gamertag ; une seule transaction : `delivered_at` posé s'il est encore nul, crédit, journal `livraison`, événement `GEMMES_RECUES` ; pseudo inconnu laissé en attente et rattrapé au `join` par `session/PendingDeliveries`) ; grades à vie et Pilier (`catalog` et `buyItem` : six grades dans l'ordre, un seul `available` à la fois, `pilier` prolongé de 28 jours à partir du plus tard de maintenant et de sa fin, 50 Gemmes rendues à chaque achat, `perso` réservé à Ultime, cadeau par `forXuid` payé par l'acteur, idempotence par `requestId` et `shop_purchases.request_id`) ; bonus de Donar du PDG (grade le plus haut, Pilier actif) lus dans `boutique.json` par `economy/ShopBonusSource` et multipliés comme le commerce de la ville ; sans grade, les treize cibles sont inchangées ; 108 tests neufs dont 26 SQL en CI, 1316 tests Gradle ; pas de migration ; `docs/economie.md` « La boutique », `docs/base-de-donnees.md` « Les livraisons de Gemmes », `core/README.md` ; règles **à valider par Kr** ; décision 40
- [ ] silooo-core : le reste de la boutique en jeu (`catalog`, `buyItem` : cosmétiques dans `player_cosmetics`, dont le titre Pionnier écrit par le site, effet de prestige, boost collectif +25 % pour tout le serveur pendant une heure avec le pseudo de l'acheteur, événement `BOOST_COLLECTIF`, cadeau à un autre joueur) ; idempotence par `shop_purchases.request_id` ; prix dans `definitions/boutique.json` (articles ajoutés avec le schéma) ; aucune lootbox, aucun hasard payant
- [ ] silooo-core : pass de saison (`getPass`, `claimPass`) : paliers gratuits et premium, points et saison dans `definitions/pass.json` (nouveau, avec son schéma et ses cas invalides), `season_pass`, `season_claims`, le premium acheté en Gemmes par `buyItem`
- [ ] silooo-core : votes et Vote party : réception Votifier v2 (jeton et port lus dans l'environnement, jamais dans le dépôt ; éteinte sans jeton ; tests sans réseau sur le décodage et la signature), une ligne `server_votes` par vote, récompense par vote en Lingots hors prestige lue dans `definitions/vote.json`, séries de jours, Vote party quand le serveur atteint l'objectif collectif du jour (récompense à tous les joueurs présents), événement `ANNONCE` ; les sites de vote autres que Hubyy plus tard, par le même chemin ; une opération de lecture `GET /v1/votes/today` (`todayVotes`) ajoutée au contrat pour l'add-on : `claude/phase5-votes` en cours
- [ ] silooo-core : Discord (un envoyeur de messages par webhook dont l'adresse vient de l'environnement du conteneur, jamais du dépôt ; messages du prestige, de la Vote party, du boost collectif et du courant qui manque ; éteint sans adresse ; tests sans réseau)
- [ ] add-on : boutique (catalogue, achat, cadeau, grades et Pilier, titre et objet Pionnier, bulle d'Oooscar à l'arrivée des Gemmes `GEMMES_RECUES`), pass de saison (progression, réclamer un palier), rappel de vote et Vote party, annonce du boost collectif ; textes **à valider par Kr**
- [ ] `definitions/` : `boutique.json` complété (cosmétiques, effet de prestige, boost collectif), `pass.json`, `vote.json`, avec leurs schémas, leurs cas invalides et les types générés
- [ ] docs : `docs/economie.md` « La boutique », « Le pass de saison », « Les votes » ; `core/README.md` ; `docs/deploiement.md` : ce que Kr branche (Votifier, Discord)

Tranches prévues, deux à la fois au plus (décision 37) : gemmes-grades et votes ; puis pass, boutique-cosmetiques
(après gemmes-grades, même paquet `shop/`) et discord ; puis l'add-on de la boutique, du pass et des votes, après les
routes. Le filet 501 des tests (`ApiTest`) passe de `/v1/shop/catalog` à `/v1/market/listings`, qui reste « pas fait ».

## À faire par Kr

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans le fichier d'environnement de `/var/www/silooo`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir : 19142 en TCP (signalisation NetherNet) plus une plage UDP contiguë pour le jeu, un port par joueur (le compose de `deploy/` propose 19143 à 19174 pour 32 joueurs ; `docs/adr/0004-port-bedrock.md`)
- [ ] Télécharger `bedrock-server-1.26.62.1.zip` dans un navigateur (minecraft.net refuse `curl`, même avec un User-Agent honnête, depuis le PC comme depuis les runners GitHub), calculer `sha256sum`, écrire la somme dans `deploy/bds/bds.sha256`, créer `/var/www/silooo/bds/` sur le VPS (`mkdir -p`, puis `chown 10001:10001` : l'uid du conteneur), y poser le zip (décision 26, `docs/deploiement.md`, « Le zip de BDS »)
- [ ] `docker login ghcr.io` sur le VPS (les images `silooo-core` et `silooo-bds` sont publiées par la CI depuis `main`), et confirmer le nom du réseau Docker de SileneMC (`SILENEMC_NETWORK`, `silenemc_default` par hypothèse)
- [ ] Fournir un monde Bedrock créé dans le client avec l'option « Beta APIs » activée, ou valider la méthode que la phase 2 trouvera pour l'activer sur BDS : `server-net` et `server-admin` l'exigent (`docs/bedrock.md`, fait 1)
- [ ] Au premier test sur un vrai client : vérifier que le pack de ressources masque bien les titres préfixés (`silooo:hud|`, `silooo:consigne|`) et affiche les monnaies en haut à gauche, qu'Oooscar a la bonne orientation (échelle à droite) ; alors passer `titleChannel` à `true` dans `addon/src/config.ts`
- [ ] Au même test, le monde : les mini-îles (délai de la ticking area, pose par lots sans à-coup, orientation du panneau), la cinématique (cadrage des plans, glissé de caméra, fondus, HUD masqué, déplacements bloqués, accroupi pour passer), la téléportation vers une zone non chargée, l'entité `silooo:oooscar` (format 1.21.0, visible avec le pack). `addon/README.md`, « Le monde », liste ce que seul un vrai BDS dira ; et pour la mission : le rendu de la bulle de fin dans le tchat, la tuile Objectifs sur deux lignes, l'ordre arrivée, cinématique, lecture des missions ; et pour le prestige et la carte : la téléportation vers une zone déchargée, le rendu des formulaires Carte (deux pages) et Prestige, les repères `[P]` et `[C]` ; et pour le personnel : les PATCH et DELETE par `http.request` (DELETE sans corps), la longueur des formulaires PERSONNEL et AGENCE, le rendu des repères `[M]` et `[R]` et des couleurs de rareté ; et pour les incidents : les repères flamme et éclair sur la tuile et la carte, le mini-jeu en trois formulaires, le message du tchat à l'ouverture et à la réparation ; et pour l'entreprise partagée : la tuile Associés et son repère `[N]`, la liste des joueurs connectés à inviter, le formulaire des parts, l'invitation reçue qui arrive pendant le jeu
- [ ] Trancher l'identité des joueurs : la Script API ne donne pas le XUID ; l'add-on envoie une empreinte du gamertag (décision 25). Valider, ou choisir une source de XUID (journal de BDS, `allowlist.json`), ce qui demandera de changer la clé des joueurs
- [ ] Trous de contrat relevés par les routes d'économie : pas de 403 ni de 404 sur `/v1/companies/...` (une entreprise inconnue, un acteur qui n'est pas membre ou une usine inconnue rendent 400 `REQUETE_INVALIDE`) ; pas de type d'événement dédié (`collect`, `buyLevels`, `restoreStep` publient `ANNONCE` avec `payload.action`) ; pas d'opération pour racheter une usine en ruine ni pour découvrir une usine ; `CompanyState.island`, `PrestigePreview.lingotsIfDoubled` et `shares` facultatifs dans l'OpenAPI mais toujours envoyés (à rendre obligatoires ?) ; le type d'événement `PRESTIGE_ENTREPRISE` n'est pas utilisé (un `ANNONCE` par membre, avec sa part) ; `getMissions` rend 400 pour un joueur inconnu, pas 404 ; le `payload` des événements `ANNONCE` (`action`, `missionId`, `rewardLingots`, `collected`, …) n'a pas de schéma dans l'OpenAPI, l'add-on le lit à l'aveugle ; le rôle `DIRECTEUR` du plan n'est pas modélisé (deux rôles, `SALARIE` et `CHEF`) ; `ratePerHour` et `factories[].ratePerHour` sont nets des salaires du personnel (dit dans la description du contrat, sans champ brut à côté) ; les routes du personnel publient elles aussi des `ANNONCE` génériques ; l'add-on ne branche aucun événement de prestige (le service n'en publie qu'un `ANNONCE` par membre, `payload` non décrit) ; `deliverOrder` sur une commande déjà terminée rend 200 sans effet (pas de code de refus : un nouveau code casserait les tests de l'add-on) ; `getFindings` n'était pas au contrat de départ (31 opérations) ; pour le personnel vu de l'add-on : pas de capacité de postes dans `CompanyState.factories[]` (l'add-on ne sait pas combien de places restent), pas de désaffectation ni de promotion en chef, pas de nom lisible pour une apparence (`skinId` montré brut), le rôle du joueur (PDG ou associé) n'est pas dans l'état (l'add-on montre Renvoyer à tous et laisse le service refuser) ; `resolveIncident` sur un incident déjà clos rend 200 sans effet (pas de code `INCIDENT_CLOS`, il aurait touché l'add-on) ; pas d'alerte Discord quand le courant manque (`docs/game-design.md`, ligne 204) ; entreprise partagée : aucune route pour quitter l'entreprise, reprendre ou libérer l'entreprise de départ laissée sans membre ; l'add-on ne lit pas encore `CompanyState.members` ; relevés par l'add-on des incidents : la réponse de `resolveIncident` ne donne pas la récompense (elle vient de l'événement), aucun événement quand l'incident était déjà clos, la charge de `INCIDENT` n'a pas de schéma, et l'événement ne porte pas l'état, d'où un `getCompany` par événement ; relevés par la ville : aucun code pour « bâtiment verrouillé par la mairie » (rendu `ZONE_VERROUILLEE`) ni pour « quantité maximale atteinte » et « dernier niveau » (rendus `DEJA_POSSEDE`), le maximum de 16 zones est fixé par le contrat et le code de `buyZone` et non par `ville.json`, `LIVRAISON_VITESSE` n'a pas encore de lecteur ; relevés par l'add-on de l'entreprise partagée : aucune route pour quitter une entreprise ni pour lister ses invitations en attente (une invitation manquée à la reconnexion est perdue), `ANNONCE` ne va qu'à celui qui agit, accepter quitte l'ancienne entreprise sans retour, le XUID est encore un condensé du gamertag ; relevés par la boutique : aucun code de refus pour `perso` sans Ultime (rendu `GRADE_PRECEDENT_REQUIS`), pas de 404 ni de 403 (l'inconnu donne 400), aucun événement d'achat, `buyItem` ne rend que `Balances`
- [ ] Trous de `definitions/` : ni coût ni niveau de départ pour la remise en état d'une usine (le service prend les étapes gratuites et le niveau `prestige.departDeCourse.niveau`, `docs/economie.md`). **Le simulateur montre que ce trou compte** : avec une remise en état gratuite, la Scierie et la Mine profonde tombent à 0,08 h, le prestige 2 à 20,9 h, le prestige 3 à 48,5 h, la première étoile à 17,5 h, tous hors cible ; le tableur suppose un rachat payant. Deux cibles ont peu de marge (première amélioration : 13 s ; Mine profonde : 1 min)
- [ ] La clé `decouverte` de `usines.json` (`depart`, `niveau-entreprise-N`, `epoque-N`, `apres-<usine>`) n'est pas lue par le service : rien ne révèle une usine, rien ne la rachète (pas de route). `EconomyEngine.isDiscovered` la lit maintenant (`apres-<usine>` = usine remise en état, à valider) ; la route de découverte et de rachat reste à écrire une fois le coût fixé
- [ ] DNS `play.silooo.com` vers l'IP du VPS
- [ ] Protéger la branche `main` sur GitHub (CI verte obligatoire, avance rapide, pas de force-push)
- [ ] Plus tard : secrets GitHub pour le déploiement par la CI, et une clé de lecture du registre `ghcr.io` pour le VPS
- [ ] Relire la section « Décisions prises sans Kr » et dire ce qui ne va pas
- [ ] Relire l'ADR 0004 (port 19142, statut « Proposée ») et l'ADR 0003 (réseau Docker de SileneMC rejoint en externe)
- [ ] Trancher les deux points ouverts de `docs/vision.md` : les lootbox à clés (le game design les cite, la règle « aucun hasard payant » les interdit) et le marché d'annonces entre joueurs (`createListing`, `buyListing`), que le plan recommande de remplacer par une vente au Silo à prix fixé. Rien ne s'écrit sur ces deux sujets avant ta réponse.
- [ ] Trancher les huit points d'interprétation du tableur et la règle d'arrondi des Lingots : `core/src/test/resources/golden/README.md`, section « Points d'interprétation ». En attendant, le moteur suit le tableur.
- [ ] Dans l'application Claude, ouvrir les deux sessions « Phase 0 : outillage » et « Phase 0 : recettes » restées en attente de « Trust repository » et les fermer (leur travail a été fait par la direction) ; au prochain lancement d'une session PC en worktree, cliquer « Trust repository » une fois
- [ ] Trancher trois trous du contrat relevés par les tests : aucune route pour nommer l'entreprise (le game design le demande à l'étape 1 de l'onboarding ; en attendant, nom provisoire « Entreprise de <gamertag> ») ; aucune route ne fait avancer `tutorial_step` (à prévoir en phase 3) ; `PAS_ASSEZ_DE_JETONS` et `PAS_ASSEZ_DE_CLES` manquent dans `Refused` (les clés dépendent de ta décision sur les lootbox)
- [ ] Les fichiers de la direction du site (`docs/site.md`, `docs/site-direction.md`, `docs/site-passation.md`, `docs/references/site/`) apparaissent **non suivis dans le dossier de la direction du jeu** (`C:\Users\largu\Documents\silooo`), alors qu'elle devait travailler dans le worktree `silooo-site` : vérifier où elle écrit. La direction du jeu ne les touche ni ne les ajoute jamais ; tant qu'ils ne sont pas commités, `git status` n'est pas propre de son fait.
- [ ] Phase 5 : un achat réel de test sur la boutique PayNow, livré une fois et une seule, puis la vérification en jeu (le site écrit `shop_deliveries`, le jeu crédite et montre `GEMMES_RECUES`).
- [ ] Phase 5 : Hubyy : déclarer Silooo avec l'adresse, le port Votifier et le jeton ; ouvrir ce port sur le VPS ; le jeton dans l'environnement du conteneur silooo, jamais dans le dépôt. Les autres sites de vote plus tard, par le même chemin.
- [ ] Phase 5 : le salon Discord et son webhook ; l'adresse dans l'environnement du conteneur silooo, jamais dans le dépôt.
- [ ] Phase 5 : trancher le marché entre joueurs (`listMarket`, `createListing`, `buyListing`) : `CLAUDE.md` interdit toute monnaie échangeable entre joueurs, donc ces routes restent « pas fait » tant que Kr n'a pas dit ce qu'il en reste.
- [ ] Deux dossiers `.agents/` et `.codex/` sont apparus non suivis dans le dossier de la direction du jeu le 5 novembre 2026 à 13 h 16, sans qu'elle les écrive : dire s'il faut les ignorer dans `.gitignore` ou les retirer. Les copies locales de `docs/site.md`, `docs/site-direction.md` et `docs/site-passation.md` qui traînaient dans ce dossier (plus anciennes que `main`, elles bloquaient chaque fusion) sont mises de côté dans un stash (`git stash list` : « copies locales des docs du site ») : les reprendre ou les jeter (`git stash drop`).

## Décisions prises sans Kr

1. **Outillage sur le PC.** Python n'est pas installé sur le PC de Kr, mais `uv` l'est. `tools/verifier.sh`
   cherche un interpréteur dans l'ordre `python3`, `python`, puis `uv run`. `tools/anti_ia.py` reste en
   Python, comme le plan le prévoit. Les outils qui lisent `definitions/` (vérification des schémas, plus
   tard génération des types de l'add-on) sont en Node, présent sur le PC, sur le VPS et dans la CI.
2. **Migration V1 sur MariaDB 12.3.** Rejouée dans la CI (service Docker `mariadb`), parce que Docker Desktop
   n'est pas lancé sur le PC et que la direction ne démarre pas d'application sur le PC de Kr. Les tests
   Testcontainers de la phase 1 tournent aussi dans la CI, comme le plan le recommande (« où construire »).
3. **Lancement des sessions.** Les sessions de travail sont lancées depuis l'application Claude (worktrees
   locaux ou cloud), ce qui revient au `claude --cloud` de `docs/direction.md` : même branche `claude/*`,
   même relecture avant fusion. Les sessions cadrées tournent sur Sonnet pour ménager le quota de Kr ; la
   direction garde son modèle. Les sessions PC en worktree demandent un clic « Trust repository » de Kr,
   et une session cloud ne peut être lancée que sur un tour ouvert par un message de Kr : en son absence,
   le travail passe par des sous-agents (Sonnet), chacun dans son propre worktree git, branche `claude/*`,
   relus et fusionnés par la direction comme n'importe quelle session.
4. **Ce que les sessions ne touchent pas.** `docs/passation.md`, `docs/README.md` et `CLAUDE.md` restent à la
   direction, pour éviter les conflits de fusion ; chaque session rend compte dans son message final et la
   direction écrit le journal.
5. **ADR 0003 acceptée, ADR 0004 proposée.** Le réseau Docker de SileneMC est rejoint en externe pour
   atteindre MariaDB et Redis (recommandation du plan). Le port 19142 reste « Proposée » : c'est Kr qui
   l'ouvre.
6. **Fins de ligne.** `.gitattributes` force LF sur tout le dépôt : les scripts tournent dans la CI et sur
   le VPS, le dépôt est édité sur Windows. Seul `core/gradlew.bat` garde CRLF.
7. **Lootbox et marché.** Tant que Kr n'a pas tranché (voir « À faire par Kr »), aucune route ni écran de
   lootbox ni d'annonces du marché ne s'écrit. En phase 1, ces opérations de l'OpenAPI répondent 501 comme
   les autres routes pas encore faites.
8. **Phase 1, tests avec Docker.** Testcontainers tourne dans la CI. Sur un poste sans Docker, les tests
   qui en dépendent sont ignorés avec un message clair, jamais réussis à vide.
9. **Phase 1, découpage.** Un squelette d'abord, en parallèle l'extraction des données dorées du tableur ;
   puis les routes de session et le moteur d'économie en parallèle sur le squelette ; enfin la direction
   branche le moteur sur `join` (voir la case de la phase 1).
10. **Contrat : réponses techniques.** Un composant `Erreur` (400 `REQUETE_INVALIDE`, 401 `CLE_INVALIDE`,
    500 `ERREUR_INTERNE`, 501 `PAS_FAIT`) est déclaré sur les 29 opérations, à part des refus du jeu
    (`Refused`). L'add-on les traite d'un bloc.
11. **Deux modes pour silooo-core.** `migrate` applique les migrations Flyway et sort (service
    `silooo-migrate`, compte migrateur) ; le démarrage normal fait Flyway `validate` seulement et refuse
    de démarrer si une migration manque. Jamais de `clean` : la base est partagée.
12. **Versions épinglées** dans `core/gradle/libs.versions.toml`, relevées sur Maven Central le 5 novembre
    2026 : Gradle 9.8.2, Kotlin 2.3.21, Ktor 3.6.1, Flyway 13.12.0, HikariCP 7.0.2, MariaDB Connector 3.5.11,
    JUnit 5.14.4, Testcontainers 2.0.5.
13. **Image Docker avec les définitions.** Le contexte de construction devient la racine du dépôt pour
    embarquer `definitions/` ; `SILOOO_DEFINITIONS_DIR` dit au service où les lire (en cours, session
    moteur).
14. **Routes et moteur écrits en parallèle.** `join` rend un `CompanyState` dont les champs calculés passent
    par une interface `EconomyFigures` et l'usine de départ par `CompanyBootstrap`, avec des implémentations
    par défaut vides ; la direction branche le moteur dessus après la fusion des deux branches.
15. **Rétention des réponses d'idempotence.** `api_requests` garde une réponse 24 heures, réglable par
    `SILOOO_API_REQUESTS_RETENTION_HOURS` (1 à 8760), purge par lots une fois par heure. Réglage technique,
    pas un chiffre du jeu : un rejeu arrive dans les secondes qui suivent une coupure.
16. **MariaDB 12 et `innodb_snapshot_isolation`.** L'erreur 1020 (ligne changée depuis la lecture) est
    traitée comme un conflit de version : 409 `CONFLIT_REESSAYER`, et `join` rejoue jusqu'à huit fois. Le pool
    reste en REPEATABLE READ ; passer en READ COMMITTED reste une option si les conflits se multiplient.
17. **Nom d'entreprise provisoire.** Tant que le contrat n'a pas de route de nommage : « Entreprise de
    <gamertag> », suffixe « 2 », « 3 » si le nom est pris, coupé à 32 caractères. Texte vu par les joueurs :
    à valider par Kr. Le départ du joueur se fige dans `players.last_seen_at`, sans migration V2.
18. **Écarts de contrat gelés dans les tests.** Six opérations peuvent répondre 409 `CONFLIT_REESSAYER` sans
    le déclarer : `ContractTest` gelait la liste (`KNOWN_409_GAPS`) ; la direction a déclaré le 409 sur les six dans
    l'OpenAPI le 5 novembre 2026 et la liste est vide : toute opération nouvelle avec `requestId` doit déclarer le sien.
19. **Quatrième fichier de définitions, `monde.json`.** L'écart entre les cases de l'archipel, le nombre de
    cases par rangée et l'origine de la grille sont des chiffres du jeu (`docs/game-design.md` : « au moins
    600 × 600 blocs par joueur ») : ils vont dans `definitions/`, pas dans le code. Au-delà des neuf seuils de
    prestige listés, le seuil suivant vaut le précédent × 1000 (prose `etoilesSuivantes`) : clé numérique
    `prestige.facteurSeuilSuivant` = 1000. Les deux sont sur `main`, à valider par Kr.
20. **Phase 2 ouverte avant la fin de la phase 1.** Le relevé des faits Bedrock ne dépend pas du
    branchement du moteur : il part en parallèle, dans la limite de trois sessions à la fois.
21. **Build BDS épinglé : 1.26.62.1.** La version 26.60 a reçu deux correctifs (26.61, 26.62) sans changement
    de protocole ni de modules stables ; les consoles sont déjà à jour. On installe le dernier correctif de la
    ligne 26.60, et `docs/bedrock.md` garde « 26.60 » comme version de la ligne. La somme SHA-256 du zip se
    calcule une fois et s'épingle dans `deploy/` (Mojang ne publie qu'un en-tête `Content-MD5`).
22. **Option « Beta APIs » acceptée.** `server-net` et `server-admin` sont en bêta en 26.60 et l'exigent sur le
    monde ; sans `server-net`, l'add-on ne parle pas à silooo-core. Le coût est connu : à chaque version de
    Bedrock, les deux modules peuvent changer de numéro ; `docs/bedrock.md` garde la procédure. La méthode pour
    activer l'option sur un monde de BDS reste à trouver (question à Kr, voir « À faire par Kr »).
23. **NetherNet seul.** BDS 26.60 refuse RakNet ; le compose publie le port TCP de signalisation et une plage
    UDP, dimensionnée sur `max-players`, sous la forme « IP publique:port ». ADR 0004 mis à jour (toujours
    « Proposée »).
24. **Phase 3 ouverte côté service pendant la phase 2.** Les routes d'économie de silooo-core (`getCompany`,
    `collect`, `buyLevels`, `restoreStep`) ne dépendent ni de `deploy/` ni de `addon/`, et la phase 2 ne peut
    pas se fermer sans Kr (base, port, DNS). Même règle que la décision 20 : trois sessions au plus, chacune
    dans son dossier (`core/` pour celle-ci). Le côté add-on de la phase 3 attend la fusion de `addon/`.
25. **Identité provisoire des joueurs : une empreinte du gamertag.** La Script API 2.11.0 ne donne pas le XUID ;
    `Player.id` est opaque et propre au monde chargé (un monde recréé pendant l'alpha changerait tous les joueurs
    d'identité), et `players.xuid` est un `BIGINT UNSIGNED`. L'add-on envoie donc, dans le champ `xuid`, une
    empreinte FNV-1a 64 bits du gamertag en minuscules (`addon/src/identity.ts`, seul fichier qui le décide) :
    elle passe le schéma `Xuid`, tient dans la colonne, survit au monde, et le site SileneMC connaît les joueurs
    par leur gamertag. Coût : un gamertag changé est un joueur neuf tant qu'une vraie source de XUID n'existe pas.
    Ni le contrat, ni la base, ni le service ne changent. À valider par Kr.
26. **BDS téléchargé au premier démarrage, pas dans l'image.** minecraft.net ne répond pas à `curl`, même avec un
    User-Agent honnête (0 octet puis délai dépassé, depuis le PC et depuis les runners GitHub) ; on ne déguise pas le
    client en navigateur, et les conditions de BDS interdisent d'héberger le zip nous-mêmes. L'image `silooo-bds`
    contient Ubuntu, l'add-on et `start.sh` ; au démarrage, `start.sh` prend le zip posé par Kr dans le volume
    `/data/bds` (ou tente le téléchargement), contrôle la somme SHA-256 épinglée dans `deploy/bds/bds.sha256` (ou,
    tant qu'elle n'est pas épinglée, le MD5 publié par Mojang, et journalise la somme à épingler), puis décompresse
    une fois dans le volume. La version reste épinglée et contrôlée, comme le plan le veut ; seul le lieu du
    téléchargement change. À relire par Kr.
27. **La production se verse en deux régimes.** Tant qu'un membre de l'entreprise est connecté, la production depuis
    `last_collect_at` se paie au revenu plein, sans plafond ni efficacité : c'est ce que le compteur du HUD montre
    (`docs/socle.md`, règle 2) et ce que « première vente en 2 minutes » demande. Entre le départ du dernier membre
    et l'arrivée du suivant, c'est la formule hors ligne (plafond de l'entreprise, `horsLigne.efficacite`). `leave`
    verse et remet `last_collect_at` ; `join` verse (régime en ligne si un autre membre est connecté d'après le
    dernier `heartbeat`, hors ligne sinon) et remet la date même à gain nul. Le sous-agent avait d'abord payé le
    joueur connecté à l'efficacité hors ligne : corrigé avant fusion. `leave` déclare désormais le 409 dans le
    contrat. Règles détaillées dans `docs/economie.md`, à valider par Kr.
28. **La phase 4 s'ouvre côté service pendant que la phase 3 finit son add-on.** Même raison que la décision 24 :
    `core/` est libre, les routes de progression ne touchent ni `addon/` ni `resource-pack/`, et trois sessions
    tiennent en parallèle. La phase 3 ne se ferme qu'avec ses cases d'add-on et de pack, puis le test de bout en
    bout après Kr ; la phase 4 ne se ferme jamais avant elle. Le marché reste « pas fait » (question ouverte à Kr).
29. **Deux ajouts au contrat pour la première heure.** `CompanyState.island` (`{ x, z }`, facultatif) : l'origine de
    l'archipel de l'entreprise, prise dans `archipelago_slots` par `companies.slot_index`, pour que l'add-on pose
    l'île et les usines sans rien calculer. Et une route de missions, `GET /v1/players/{xuid}/missions`
    (`getMissions`), sans route de réclamation : le service avance et termine lui-même la mission de bienvenue de
    la mairie dans `collect`, crédite `lingotsHorsPrestige.missionBienvenue` au joueur et publie un événement. Le
    catalogue des missions va dans `definitions/missions.json`. La migration V1 avait déjà `player_missions` et
    `archipelago_slots` ; le contrat ne les servait pas. À relire par Kr.
30. **`island` est le coin de la case de l'archipel.** Le service range dans `archipelago_slots` l'origine de la
    case (`colonne × ecartEntreCases`, `rangée × ecartEntreCases`, depuis `origine` de `monde.json`) ; l'add-on
    place le centre de l'île principale à `centreDansLaCase` de `definitions/iles.json` (300, 300 : la moitié de la
    case, qu'un test des définitions compare à `monde.json`) et les mini-îles en carré autour. Les chiffres des îles
    vivent dans `iles.json`, à part de `monde.json` (la grille) ; l'entité `silooo:oooscar` est en `format_version`
    1.21.0, sans comportement, sans dégâts, jamais persistée. L'île principale n'est pas posée en phase 3 : elle
    est verrouillée au départ et se débloque zone par zone avec la ville (phase 4). À relire par Kr.
31. **Ce que le prestige remet à zéro, et comment une mission avance.** Tranché par la tranche de service avec la
    recommandation du plan, détaillé dans `core/README.md` (« Le prestige », « Les missions ») et `docs/economie.md` :
    la production en attente est versée avant le test du seuil et compte pour les Lingots ; le partage suit
    `share_percent`, arrondi vers le bas, le reste au PDG ; la Carrière repart au niveau de départ, les autres usines
    en ruine (niveau 0, lignes gardées, incidents clos) ; mairie, silo, plafond hors ligne et personnel ne bougent
    pas (pas encore modélisés) ; un événement `ANNONCE` par membre avec sa part. Les missions se font dans l'ordre
    du catalogue ; la mission en cours avance quand un `collect` rapporte du Donar, se termine et verse ses Lingots
    dans la même transaction, sans geste du joueur ; un joueur inconnu donne 400. Les textes des missions sont à
    l'add-on. À relire par Kr.
32. **Le simulateur vit dans les tests du service.** `core/src/test/kotlin/com/silooo/core/simulation/`, pas un
    outil à part : il n'a besoin que d'`EconomyEngine` et des définitions, et la CI le lance avec les autres tests.
    Il lit `apres-<usine>` comme « usine remise en état » et suppose la remise en état instantanée, comme le
    tableur. `EconomyEngine` gagne `companyLevel` et `isDiscovered` (purs, testés), sans changer un calcul existant.
    À relire par Kr.
33. **Le personnel sans tirage payant ni migration.** Tranché par la tranche de service avec la recommandation du
    plan, détaillé dans `core/README.md` et `docs/economie.md` (« Le personnel ») : l'agence propose cinq candidats
    par jour et par entreprise, tirés d'une suite figée par l'entreprise et la date (rejouable, rien à acheter) ;
    tout membre embauche et affecte, seul le PDG renvoie, sans remboursement ; le prix d'embauche se lit sur le
    revenu net du moment ; les bonus de production s'additionnent et les salaires se retiennent sur le revenu de
    l'usine, donc `ratePerHour` est net ; les postes limitent l'entrée dans l'usine, pas la production ; le chef
    d'équipe relève le plafond hors ligne de sa seule usine. Les tables de V1 suffisent : pas de migration.
    À relire par Kr.
34. **La commande du jour et le Carnet suivent la recommandation du plan.** Tranché par la tranche de service,
    détaillé dans `core/README.md` (« La commande du jour », « Le Carnet de trouvailles ») et `docs/economie.md` : la
    ressource du jour se tire du rang du jour UTC dans la liste des produits, la même pour tout le serveur ; l'objectif
    et le prix d'une unité (en minutes de production) sont dans `economie.json` ; chaque participant touche une prime
    en Lingots hors prestige quand la commande se termine ; le Carnet est au joueur, une ligne par ressource vue
    sortir d'une de ses usines, inscrite au `collect` qui la rapporte, une prime en Lingots une seule fois (la clé
    primaire de `player_findings` est le garde-fou) ; livrer une commande terminée ne fait rien et rend 200. Les
    commandes lisent l'horloge de `Dependencies.clock`. V3 pour le Carnet, V2 inutilisée, prochaine migration V4.
    À relire par Kr.
35. **Les incidents se tirent sans hasard payant, par créneau, en ligne seulement ; l'électricité agit partout.**
    Tranché par la tranche de service, détaillé dans `core/README.md` et `docs/economie.md` (« Les incidents »,
    « L'électricité ») : un minuteur de trente secondes du service regarde les joueurs présents à deux passages de
    suite ; pour chaque entreprise et chaque créneau de `frequence.creneauMinutes`, un tirage figé par l'entreprise, le
    jour et le créneau (SplitMix64, rien à acheter) décide d'au plus un incident, d'une sorte pondérée (`sortes[]`), qui
    ralentit l'usine tant qu'il est ouvert et seulement au régime en ligne ; `resolveIncident` (le mini-jeu réussi) le
    clôt et verse `recompense.minutesDeRevenu` de revenu de l'usine ; un incident déjà clos rend 200 sans effet. Dès
    l'époque `electricite.aPartirDeEpoque`, une usine qui demande du courant s'arrête (`stoppedNoPower`) sans Centrale
    active, à tous les régimes, et ne coûte alors pas de salaire. Pas de migration : V4 reste un trou, V5 et V6 sont
    réservées à l'entreprise partagée et à la ville. À relire par Kr.
36. **L'invité entre à part nulle, et son entreprise de départ reste telle quelle.** Tranché par la tranche de
    service (`core/README.md`, `docs/economie.md` « L'entreprise partagée ») : le PDG invite par XUID, l'invitation
    vaut `entreprise.invitationHeures` (48) puis se périme ; `acceptInvite` demande que l'entreprise de l'invité soit
    encore à son départ (lui seul, rien amélioré), sinon 409 `ENTREPRISE_EN_COURS`, pour qu'aucun Donar ni Lingot ne
    change d'entreprise ; au plus `entreprise.membresMax` (4) membres ; l'entreprise laissée n'est jamais supprimée,
    elle reste sans membre avec ses usines et son île ; le nouvel associé a 0 % tant que le PDG n'a pas appelé
    `setShares` (parts entières qui font 100, 400 sinon). Les tables de V1 suffisent : V5 reste un trou, comme V2
    et V4 ; la prochaine migration est V6. À relire par Kr.
37. **Deux directions, décidé par Kr le 5 novembre 2026 (ce n'est pas une décision prise seule, elle est notée
    ici pour qu'on la retrouve).** Une deuxième direction construit le site silooo.com dans le worktree
    `silooo-site`, en même temps que la direction du jeu. (1) La direction du jeu passe à 2 sessions de travail
    au plus ; la direction du site en lance une. (2) Elle écrit `docs/site.md`, `docs/site-direction.md`,
    `docs/site-passation.md` et le code du site (`core/.../site/`, `core/src/main/resources/site/`) ; elle ajoute
    une fois des lignes à `docs/README.md` et `CLAUDE.md` ; elle n'écrit jamais `docs/passation.md` ni
    `docs/direction.md`. (3) À chaque bilan, la direction du jeu lit « Pour la direction du jeu » dans
    `docs/site-passation.md` et répond dans son journal. (4) Phase 5 : les votes arrivent de Hubyy au format
    Votifier v2 (jeton) et silooo-core les reçoit ; les Gemmes arrivent par le webhook PayNow que le site écrit
    dans `shop_deliveries`, et par le site SileneMC ; la phase 5 crédite ces lignes. (5) Numéros de migration et
    d'ADR : pris au moment de la fusion ; si l'autre direction a pris le numéro, le suivant. (6) La direction du
    jeu reste seule à déployer silooo-core : quand `docs/site-passation.md` dit « Prêt à mettre en ligne » et que
    Kr a fait sa part, elle déploie `main`. Reporté dans `docs/direction.md` (section « La direction du site »).
38. **La ville : chacun paie avec ses Lingots, les zones dans l'ordre, la mairie suit les zones.** Tranché par la
    tranche de service (`docs/economie.md` « La ville côté service ») : une zone, un bâtiment ou un niveau se paie
    avec les Lingots de celui qui agit, PDG ou associé ; les zones s'achètent dans l'ordre de `zones.ordre`, la
    zone N demande une mairie de niveau N ; la première zone demande deux usines actives et pose la mairie au
    niveau 1, gratuite, à `mairie.emplacementDepart` ; une parcelle fait un chunk et n'a qu'un bâtiment (clé
    unique de la migration V6) ; les premiers embauchés sont logés d'abord, un logé coûte 20 % de salaire en
    moins ; le commerce multiplie le multiplicateur global ; la ville survit au prestige ; aucun bonus ne donne
    de Lingots ni ne touche aux seuils. Les chiffres (`ville.json`, `economie.json` `ville.zonesPrixLingots`)
    sont des valeurs de départ. À relire par Kr.
39. **La phase 5 s'ouvre pendant la dernière tranche de la phase 4.** La phase 4 n'a plus qu'une case hors Kr,
    l'add-on de la ville, déjà en cours dans une session ; la deuxième session accordée par Kr resterait vide une à
    deux heures. La phase 5 (boutique et vote) s'ouvre donc dès maintenant pour son côté service, qui ne touche
    aucun fichier de l'add-on ; la phase 4 se clôt dès que l'add-on de la ville est fusionné, et rien de la phase 5
    n'est déployé (la condition de sortie demande Kr : achat réel, port Votifier, Discord). Les lootbox du game
    design ne sont pas construites : `CLAUDE.md` interdit le hasard payant.
40. **La boutique : le bonus est celui du PDG, seul le grade le plus haut compte, le Pilier se prolonge.** Tranché
    par la tranche de service (`docs/economie.md` « La boutique ») : le multiplicateur global de l'entreprise prend
    le bonus du PDG, pas celui de l'associé qui joue ; seul le grade le plus haut possédé compte, sans cumul ; grade,
    Pilier et commerce de la ville se multiplient entre eux ; un achat en Gemmes ne verse pas la production en
    attente (les Gemmes ne touchent pas au Donar) ; le Pilier se rachète et ses 28 jours s'ajoutent à sa fin, avec
    50 Gemmes rendues à chaque achat ; `perso` sans Ultime est refusé en `GRADE_PRECEDENT_REQUIS` faute de code
    dédié ; le relevé des livraisons tourne toutes les 30 s. À relire par Kr.

## À valider par Kr

- Les huit points d'interprétation du tableur et la règle d'arrondi des Lingots
  (`core/src/test/resources/golden/README.md`) : le moteur suit le tableur en attendant.
- Le nom d'entreprise provisoire « Entreprise de <gamertag> » (`core/.../session/CompanyNaming.kt`), premier
  texte vu par les joueurs.
- Le nom du pack « Silooo » et sa description « Pack de comportement du jeu Silooo. » (`addon/manifest.json`),
  visibles dans la liste des packs du monde.
- Le nom du serveur « Silooo » dans la liste des amis (`BDS_SERVER_NAME`, `deploy/exemple.env.txt`).
- Le pack de ressources : nom « Silooo », description « Interface, HUD et Oooscar, la mascotte du jeu Silooo. »
  (`resource-pack/texts/fr_FR.lang`), et `texturepack-required=true` dans `server.properties` (le HUD en dépend).
- La cinématique d'arrivée : ses cinq phrases (`CINEMATIQUE_TEXTES` dans `addon/src/ui/textes.ts`, tableau dans
  `addon/README.md`), le découpage en quatre plans et leur cadrage (`definitions/iles.json`, `cinematique.plans`).
- La forme des mini-îles (`definitions/iles.json`) : rayon 7, un bâtiment par usine avec son angle autour de l'île
  principale, 35 % de murs et 30 % de sol abîmés pour une usine en ruine, les matériaux.
- Les règles du prestige et des missions de la décision 31 (`core/README.md`, « Le prestige » et « Les missions » ;
  `docs/economie.md`) : elles fixent ce que le joueur touche au prestige et quand la mairie le récompense.
- Les textes de la mission de bienvenue (`MISSION_TEXTES`, `OBJECTIFS_TEXTES` dans `addon/src/ui/textes.ts`,
  tableau dans `addon/README.md`) : titre « Bienvenue à la mairie », consigne « Encaisse au Silo » (le HUD montre
  « Encaisse au Silo : 0/1 » à la place du seuil de prestige tant qu'une mission est en cours), bulle de fin
  « Premier encaissement, bien joué ! », repli « Bien joué, patron ! », écran OBJECTIFS (EN COURS, Avancement,
  Récompense N Lingots, TERMINÉES, « Plus de mission pour l'instant. », « Aucune mission terminée. »), tuile
  « Mission 0/1 » ou « Plus de mission ». Deux choix d'écran aussi : la bulle de fin passe par le tchat (la barre
  d'action est réécrite chaque seconde par le HUD) ; tant que `titleChannel` est à `false`, la consigne du HUD ne
  s'affiche pas et la mission se lit à Objectifs.
- Les règles du personnel de la décision 33 (`core/README.md` et `docs/economie.md`, « Le personnel ») et les noms
  et apparences des candidats de `definitions/personnel.json` (vingt-quatre prénoms, six apparences de salarié,
  quatre de chef) : provisoires, l'add-on les affichera tels quels.
- Les textes de l'écran de prestige et de la carte (`PRESTIGE_TEXTES`, `CARTE_TEXTES` dans `addon/src/ui/textes.ts`,
  tableau dans `addon/README.md`) : « Tu revends tes usines et tu reçois des Lingots. », « REPART À ZÉRO », « RESTE »,
  « Prestige ! » (prescrit par `design/README.md`), « Prestige accompli, patron ! », « Choisis une île pour t'y
  rendre. », « ????? » pour une usine à découvrir, « Entière · niv. N », « En ruine », « En travaux n/t », les noms
  d'époque Artisanale, Vapeur, Moderne, Futuriste (ceux de `docs/game-design.md`), la tuile Carte « n îles » et la
  tuile Prestige « Course n ». Choix d'écran : Prestige est la sixième tuile du menu ; Oooscar ne suit pas le
  joueur d'une île à l'autre.
- Les règles de la commande du jour et du Carnet de la décision 34 (`core/README.md`, `docs/economie.md`) et les
  quatre chiffres ajoutés à `economie.json` : objectif de 100 unités, une minute de production par unité, un
  Lingot par participant à la fin, un Lingot par trouvaille.
- Les textes des écrans du personnel et de l'agence (`PERSONNEL_TEXTES`, `AGENCE_TEXTES`, bulles dans
  `addon/src/ui/textes.ts`, tableau dans `addon/README.md`) : « Ton équipe, usine par usine. », « Personne dans ton
  équipe pour l'instant. », « Choisis qui rejoint ton équipe. », « Choisis son usine. », « Cette personne quitte ton
  entreprise. », « Embauche N Donar », « Salaire N % du revenu de l'usine », « Salaire N Donar/h », « Bonus +N % de
  production », les bulles « Nouvelle recrue embauchée ! », « Affectation faite. », « Membre renvoyé. », « Plus de
  poste libre ici. », « Déjà embauché. » ; les raretés Commun, Rare et **Exceptionnel** (« épique » est un mot banni
  par `design/README.md`) ; les couleurs de rareté (§7, §9, §e) et les repères `[M]`, `[R]`. Choix d'écran : Personnel
  est la septième tuile, avant Amis ; l'Agence s'ouvre depuis PERSONNEL ; seules les équipes habitées sont listées ;
  le renvoi demande deux appuis ; l'apparence s'affiche par son identifiant brut.
- Les chiffres de `definitions/incidents.json` : un créneau de 30 min avec 20 % de chance d'incident par entreprise,
  trois sortes (incendie, ralentissement 0,5, poids 1 ; panne, 0,6, poids 2 ; courroie, 0,7, poids 2), récompense de
  10 minutes de revenu de l'usine, électricité obligatoire dès l'époque 3 avec la Centrale (`usineCentrale`) ; et les
  règles de la décision 35.
- L'entreprise partagée : 4 membres au plus, invitation valable 48 h, l'invité doit encore être à son départ, son
  entreprise reste en l'état, part nulle jusqu'à `setShares` (décision 36) ; la bulle « Tu as déjà lancé ton
  entreprise. » (refus `ENTREPRISE_EN_COURS`).
- L'add-on des incidents : les noms des trois sortes et leurs phrases, les quatre bulles (« Vite, une usine a un
  souci. », « L'usine repart ! », « Bien joué, l'usine repart ! », « Un associé a tout réparé. »), les messages du
  tchat, les consignes, boutons et indices des quatre mini-jeux (trois sortes et un général), le nombre d'étapes
  (trois) et la place des bonnes réponses, la phrase de fiche « Un incident ralentit sa production. » ; la liste
  est dans `addon/README.md`.
- La ville côté service : toutes les clés de `definitions/ville.json` (les 16 zones et leur ordre, deux usines
  actives pour la première, l'emplacement de la mairie, les 20 % du logement, les plafonds 60 places, 10 %,
  40 %, les prix et bonus par niveau des six bâtiments) et les prix des zones dans `economie.json` ; les règles
  de la décision 38.
- L'add-on de l'entreprise partagée : la tuile « Associés », les rôles « PDG » et « Associé », les lignes
  « <rôle> · <nom> · <part> % » et « (toi) », les écrans Inviter et Parts, l'invitation « <nom> t'invite dans
  <entreprise> » avec « Accepter » et « Plus tard », les bulles (« Invitation envoyée. », « Parts
  enregistrées. », « Bienvenue dans l'entreprise. », « Tu as une invitation. », « Les parts ont changé. »,
  « Les parts doivent faire 100. », « Cette invitation a expiré. », « Cette entreprise est pleine. ») ; la
  phrase « Tu es seul dans l'entreprise. Invite des joueurs, jusqu'à ce que le service refuse. » parle du
  service au joueur, à reformuler ; la liste est dans `addon/README.md`.
- La boutique côté service : les règles de la décision 40 (bonus du PDG, grade le plus haut seul, Pilier prolongé,
  50 Gemmes rendues à chaque achat, `perso` réservé à Ultime), et les chiffres de `boutique.json` déjà là (grades,
  Pilier, `perso`), maintenant lus par le moteur.
- Tous les textes des écrans de l'add-on (`addon/src/ui/textes.ts`) : HUD, boussole « Menu », menu, liste des
  usines, fiche, Silo, et les bulles d'Oooscar (une par code `reason`). La liste avant / après est dans
  `addon/README.md`, section « Textes à valider » ; un test la compare au code. Deux choix d'écran à valider
  aussi : monter une usine demande trois touches depuis le menu (Usines, la tuile, le bouton), pas deux ; les
  icônes et Oooscar sont tenus par des repères provisoires (`[joie]`, …) tant que le pack de ressources n'existe pas.
- L'identité provisoire des joueurs par empreinte du gamertag (décision 25).
- Les règles des routes d'économie (`docs/economie.md`) : production en deux régimes (décision 27) ; `max` achète
  tout ce que le solde paie et ne refuse que si pas même un niveau ; remise en état gratuite en quatre étapes, niveau
  final `max(niveau, departDeCourse.niveau)` ; `heartbeat` de moins de 30 s = joueur connecté.
- Les valeurs de `definitions/monde.json` (`ecartEntreCases` 600, `casesParRangee` 64, `origine` (0, 0)) et
  `prestige.facteurSeuilSuivant` = 1000 dans `definitions/economie.json` (`docs/economie.md`, historique du 5 nov.).
- Trois règles du hors ligne prises par le branchement (`docs/economie.md`) : l'absence se compte depuis
  `last_collect_at` de l'entreprise et non depuis le départ du joueur ; la colonne `offline_cap_hours` fait foi
  (recopiée de `horsLigne.plafondHeures` à la création) ; le surplus au-delà du plafond est perdu.

## Journal

- **5 novembre 2026, début de nuit : direction.** Kit de départ commité et poussé sur `main`
  (85 fichiers). Règle d'arrêt de `docs/direction.md` mise à jour. Trois sessions de phase 0 lancées :
  docs et ADR, outillage (schémas, vérificateur, anti-IA, CI, hooks), recettes `.claude/skills/`.
  Reste : fusionner, compléter `docs/README.md`, test de la session neuve. Vérifier : `git log origin/main`.
- **5 novembre 2026, 3 h 45 : direction, phase 0 terminée.** Les trois chantiers sont sur `main` en avance
  rapide : docs et ADR (session cloud), outillage et recettes (sous-agents sur le PC, les deux sessions PC
  en worktree étant restées sur « Trust repository »). `tools/verifier.sh` vert sur `main` : schémas,
  anti-IA, 148 liens, 60 tests d'outillage. CI verte sur `main` (jobs `verifier` et `migration` : V1
  rejouée sur MariaDB 12.3.3, 32 tables). Test de la session neuve réussi : un agent sans contexte reçoit
  « ajoute une usine », lit `CLAUDE.md`, `docs/README.md`, la recette `silooo-equilibrage` et
  `docs/economie.md`, puis désigne `definitions/usines.json` et l'historique des réglages, sans toucher
  aux schémas ni à `boutique.json`. Il manquait à la recette une section « Ajouter une usine » : ajoutée,
  avec une ligne dans `docs/README.md`. Vérifier : `gh run list --branch main`,
  `git log --oneline -6 origin/main`.
- **5 novembre 2026, 4 h 50 : direction, bilan de la première moitié de la phase 1.** Deux sous-agents en
  worktrees, fusionnés sur `main` après rebase : le squelette de silooo-core (Gradle, Kotlin, Ktor, Flyway,
  clé `X-Silooo-Key`, heartbeat `POST` 204, filet 501, Dockerfile, CI avec Java 17 et job `image`) et les
  données dorées (six JSON extraits du tableur par `tools/tableur/extraire.py`, README de 350 lignes :
  84 valeurs en accord avec `definitions/`, aucun écart de valeur, six réglages du tableur sans clé, huit
  points d'interprétation). Contrat : composant `Erreur` déclaré sur les 29 opérations. CI de `main` :
  `verifier` (dont les deux tests Testcontainers, passés) et `migration` verts. Deux sous-agents lancés :
  routes de session (join, leave, events, idempotence, verrou, journal, tests de contrat) et moteur
  d'économie avec tests dorés. Vérifier : `gh run list --branch main`, `cd core && ./gradlew test`.
- **5 novembre 2026, 5 h 40 : direction, routes et moteur sur `main`.** Le moteur d'économie (fusion en
  avance rapide ; 285 lignes, deux constantes hors définitions : 24 heures par jour et le bruit flottant ;
  `definitions/economie.json` gagne `prestige.departDeCourse` ; l'image Docker embarque `definitions/`) puis
  les routes de session (neuf commits rebasés sur `main` : idempotence, verrou optimiste, journal des monnaies,
  `join`, `leave`, `GET /v1/events`, 54 tests SQL Testcontainers, tests de contrat sur les 29 opérations lus
  dans l'OpenAPI par SnakeYAML). Conflits résolus par la direction dans `Config.kt`, `Application.kt`,
  `Main.kt` et `core/README.md` (les deux jeux de champs de `Dependencies` gardés) ; un commit de plus pour
  passer les définitions aux `Dependencies` des tests de routes. CI de `main` verte avant la fusion des
  routes (`verifier`, `migration`, `image` publiée) ; celle d'après en cours. Deux sous-agents lancés : le
  branchement du moteur sur `join` (`claude/phase1-branchement`) et le relevé Bedrock de la phase 2
  (`claude/phase2-releve`). Vérifier : `gh run list --branch main`, `git log --oneline -12 origin/main`.
- **5 novembre 2026, 6 h 05 : direction, contrat complété et relevé Bedrock fusionné.** Le 409
  `CONFLIT_REESSAYER` est déclaré sur les six opérations qui peuvent le rendre et le test de contrat n'a plus
  de trou connu. Le relevé Bedrock est sur `main` (`docs/bedrock.md`, 252 lignes : modules `server` 2.11.0 et
  `server-ui` 2.3.0 stables, `server-net` et `server-admin` 1.0.0-beta avec « Beta APIs », permissions de BDS,
  `secrets.json` lu par `server-admin`, API `server-net`, URL de téléchargement testée pour 1.26.60.6, 1.26.61.1
  et 1.26.62.1, NetherNet obligatoire avec signalisation TCP et plage UDP, chien de garde, manifeste). ADR 0004
  et plan mis à jour en conséquence. Décisions 21 à 23. Deux sous-agents de phase 2 lancés : `deploy/`
  (image BDS, compose, `deploy.sh`, `claude/phase2-deploy`) et `addon/` (projet TypeScript, client
  `server-net`, heartbeat, `join`, `leave`, événements, types générés, `claude/phase2-addon`) ; le branchement
  du moteur (phase 1) est toujours en cours. Vérifier : `git log --oneline -6 origin/main`.
- **5 novembre 2026, 6 h 45 : direction, phase 1 close.** Le branchement du moteur sur `join` est fusionné en
  avance rapide (`4a9428a`, deux commits, 42 fichiers : `EngineFigures`, `DefinitionsBootstrap`,
  `WorldDefinitions`, `definitions/monde.json` et son schéma, six fichiers de tests dont `EngineFiguresSqlTest`
  sur MariaDB). `tools/verifier.sh` vert sur le PC, CI verte sur la branche avant fusion. Dernière case de la
  phase 1 cochée ; `docs/README.md` cite `monde.json`. Vérifier : `git log --oneline -3 origin/main`,
  `gh run list --branch main --limit 2`.
- **5 novembre 2026, 7 h 00 : direction, phase 3 ouverte côté service.** Liste de la phase 3 écrite d'après le
  plan ; troisième sous-agent lancé sur les routes d'économie (`claude/phase3-economie-routes`, `core/` seul),
  à côté des deux de la phase 2. Décision 24.
- **5 novembre 2026, 7 h 25 : direction, add-on de la phase 2 fusionné.** `claude/phase2-addon` en avance rapide
  (`f4535dd` : `addon/` complet, `tools/generer_types.mjs`, étape « Types de l'add-on à jour », 106 tests Node) ;
  `tools/verifier.sh` vert avec `npm ci`, `npm test`, `npm run build` et le `.mcpack`. Le sous-agent a relevé que
  l'API de script ne donne pas le XUID : décision 25 (empreinte du gamertag dans `addon/src/identity.ts`, 5 tests
  de plus), question posée à Kr, piste notée dans `docs/bedrock.md`. Quatre cases de la phase 2 cochées ; restent
  `deploy/` (sous-agent en cours), l'image BDS dans la CI, et les deux cases qui attendent Kr. Vérifier :
  `git log --oneline -4 origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 7 h 50 : bilan de la nuit (direction).**
  - Fait depuis le bilan de 5 h 40 : contrat complété (409 déclaré partout), relevé Bedrock fusionné et reporté dans
    l'ADR 0004 et le plan, moteur branché sur `join` (phase 1 close), `addon/` et le générateur de types fusionnés,
    identité provisoire des joueurs (décision 25), liste de la phase 3 écrite. `main` : vérificateur et CI verts.
  - En cours : `deploy/` (reprise après la décision 26, `claude/phase2-deploy`), routes d'économie
    (`claude/phase3-economie-routes`), écrans de l'add-on (`claude/phase3-addon-ecrans`).
  - Reste pour la phase 2 : fusionner `deploy/`, l'image BDS verte dans la CI ; puis Kr (base, port, zip de BDS,
    DNS, monde « Beta APIs », test console, mesure de la mémoire). Phase 3 : les routes, les écrans, puis la
    cinématique, Oooscar, la mission de la mairie et le JSON UI du pack de ressources.
  - Décisions prises seul depuis 5 h 40 : 21 à 26. Textes nouveaux à valider : nom du pack, description du pack,
    nom du serveur « Silooo » (`BDS_SERVER_NAME`), et ceux que la tranche d'écrans apportera.
  - Vérifier : `git log --oneline -8 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 8 h 30 : direction, routes d'économie fusionnées.** `claude/phase3-economie-routes` en avance
  rapide (`bbf0c88`, trois commits dont une fusion de `main` : `CompanyRoutes`, `CompanyService`, `JdbcCompanyStore`,
  `CompanyStates`, deux régimes de production, 58 tests dont 14 SQL passés dans la CI). Contrat : `leave` déclare le
  409, `offlineHours` décrit les deux régimes, `KNOWN_409_GAPS` vide. Cinq cases de la phase 3 cochées côté service.
  Décision 27, trous de contrat et de définitions notés pour Kr. Vérifier : `git log --oneline -5 origin/main`.
- **5 novembre 2026, 8 h 45 : direction, `deploy/` et les écrans fusionnés.** `claude/phase2-deploy` (`0803726`,
  décision 26 appliquée : image sans BDS, zip de Kr dans `/data/bds`, `bds.md5`, bind mount, `deploy.sh` qui refuse
  sans le dossier, jobs `deploy-fichiers` et `image-bds` verts) et `claude/phase3-addon-ecrans` (`d8408d9`, HUD,
  menu, fiche, Silo, boussole, 339 tests) fusionnés dans `main` par deux commits de fusion (`e2dac4c`, `b2319a2`),
  aucun fichier en commun. Vérificateur vert. Phase 2 : tout est coché sauf les trois cases qui attendent Kr. Phase 3 :
  huit cases sur onze cochées. `docs/bedrock.md` corrigé sur le User-Agent. Les deux branches restent sur le dépôt
  (pas de suppression de branche distante). Vérifier : `git log --oneline -6 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 9 h 00 : direction, trois tranches lancées.** Liste de la phase 4 écrite, décisions 28 et 29.
  `CompanyState.island` ajouté au contrat (`0ea5414`). Sous-agents : `claude/phase3-addon-monde` (usines posées sur
  l'île, téléportation, cinématique d'arrivée, entité `silooo:oooscar` côté comportement, dossier statique
  `addon/pack/`), `claude/phase3-resource-pack` (manifest, textures copiées de `design/`, JSON UI du HUD qui masque
  les préfixes, Oooscar côté client, installation par `start.sh` et le Dockerfile), `claude/phase4-prestige-mission`
  (`island` renseigné, `prestigePreview`, `prestige`, `getMissions`, `definitions/missions.json`). Fichiers disjoints,
  sauf `addon/src/generated/definitions.ts` que deux tranches regénèrent : la direction le regénère à la fusion.
- **5 novembre 2026, 8 h 20 : direction, pack de ressources fusionné.** `claude/phase3-resource-pack` en avance
  rapide (`571b6b5` : `resource-pack/` avec 37 textures copiées, JSON UI du HUD, Oooscar côté client, installation
  par `start.sh`, `texturepack-required=true`, contrôle du pack dans le vérificateur, CI `image-bds` qui vérifie la
  copie et la liste du monde). Case du pack cochée, moins `titleChannel` qui attend le test client. Vérifier :
  `git log --oneline -3 origin/main`, `node tools/verifier_pack.mjs`.
- **5 novembre 2026, 8 h 35 : direction, le monde de l'add-on fusionné.** Les heures des entrées de ce jour qui précèdent étaient en avance d'environ une heure et quart sur l'horloge du PC ; à partir d'ici, elles la suivent. `claude/phase3-addon-monde` en avance
  rapide (`b9d2b24` : `addon/src/monde/`, `definitions/iles.json` et son schéma, entité `silooo:oooscar` dans
  `addon/pack/`, build qui copie `pack/`, 522 tests). Deux cases de la phase 3 cochées, la mission de la mairie côté
  add-on devient une case à part. Décision 30. Reste en vol : `claude/phase4-prestige-mission`. Vérifier :
  `git log --oneline -3 origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 9 h 00 : direction, prestige et missions fusionnés.** `claude/phase4-prestige-mission`
  (`342770b`) fusionnée dans `main` par un commit de fusion (`c598f09`) : `island` renseigné, `prestigePreview`,
  `prestige`, `getMissions`, `definitions/missions.json`, 30 opérations au contrat, 560 tests Gradle (90 SQL en CI).
  Décision 31. Vérifier : `git log --oneline -3 origin/main`, `cd core && ./gradlew test`.
- **5 novembre 2026, 9 h 05 : bilan de la matinée (direction).**
  - Fait depuis le bilan noté 7 h 50 (6 h 35 à l'horloge) : routes d'économie, `deploy/`, écrans de l'add-on, pack
    de ressources, monde de l'add-on (îles, arrivée, cinématique, Oooscar), prestige et missions côté service, tous
    sur `main` ; liste de la phase 4 écrite ; contrat à 30 opérations. `main` : vérificateur et CI verts à chaque
    poussée.
  - En cours : l'affichage de la mission de la mairie dans l'add-on (`claude/phase3-addon-mission`), le simulateur
    des treize cibles (`claude/phase4-simulateur`), le personnel côté service (`claude/phase4-personnel`).
  - Reste : phase 2, les trois cases de Kr ; phase 3, la mission dans l'add-on puis le test de bout en bout après
    Kr ; phase 4, sept cases ; phase 5, pas ouverte.
  - Décisions prises seul depuis ce bilan : 27 à 31. Textes nouveaux à valider : écrans de l'add-on, pack de
    ressources, cinématique ; règles d'économie des décisions 27 et 31.
  - Vérifier : `git log --oneline -12 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 9 h 30 : direction, la mission de la mairie dans l'add-on fusionnée, phase 3 close sauf Kr.**
  `claude/phase3-addon-mission` en avance rapide (`7df014b` : `ui/missions.ts`, `getMissions` dans `api.ts`, HUD,
  tuile Objectifs, bulle de fin, 631 tests). La phase 3 n'a plus que le test de bout en bout, qui attend le
  déploiement : la direction passe à la phase 4. Une annonce déjà dite, ou d'une mission terminée avant l'arrivée,
  n'est pas redite (le curseur d'événements repart de zéro au redémarrage). Vérifier : `git log --oneline -3
  origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 9 h 40 : direction, le simulateur fusionné.** `claude/phase4-simulateur` (`84bd04e`) fusionnée
  dans `main` par un commit de fusion (`d1c53ab`). Treize temps mesurés dans leur fourchette, égaux au tableur à
  1e-14. Deux écarts remontés à Kr : la remise en état gratuite met cinq cibles hors fourchette (le tableur
  suppose un rachat payant), et `decouverte` n'est pas lue par le service. Décision 32. Lancées : l'écran de prestige
  et la carte des îles (`claude/phase4-addon-prestige-carte`), les commandes du jour et le Carnet
  (`claude/phase4-commandes`). Vérifier : `git log --oneline -3 origin/main`, `cd core && ./gradlew test --tests '*ThirteenTargets*'`.
- **5 novembre 2026, 10 h 23 : direction, le personnel fusionné.** `claude/phase4-personnel` en avance rapide
  (`5efe78f` : `staff/`, `definitions/personnel.json` et son schéma, 19 tests SQL en CI, `ratePerHour` net des
  salaires). Relu : tirage figé par l'entreprise et la date (pas de hasard payant), filet 501 du test de contrat
  déplacé vers `resolveIncident`, tests du simulateur adaptés sans perte. Décision 33. Vérifier :
  `git log --oneline -3 origin/main`, `cd core && ./gradlew test --tests '*Staff*'`.
- **5 novembre 2026, 10 h 37 : direction, l'écran de prestige et la carte des îles fusionnés ; incidents lancés.**
  `claude/phase4-addon-prestige-carte` en avance rapide (`4606e79` : `prestigePreview` et `prestige` dans `api.ts`,
  écrans PRESTIGE et CARTE de `ui/menu.ts`, `monde/voyage.ts`, 757 tests). Relu : aucun calcul dans l'add-on,
  aucune ligne de test retirée, `voyage.ts` seul nouveau fichier qui touche Bedrock. Lancée dans la foulée du
  personnel : les incidents et l'électricité côté service (`claude/phase4-incidents`, paquet `incident/`,
  `definitions/incidents.json`, pas de migration attendue ; V2 reste libre, le personnel n'en a pas eu besoin, V3
  réservée aux commandes). Vérifier : `git log --oneline -3 origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 11 h 03 : direction, la commande du jour et le Carnet fusionnés ; écrans du personnel lancés.**
  `claude/phase4-commandes` en avance rapide (`03dbfe8` : `orders/`, `findings/`, `V3__carnet.sql`, `getFindings`
  ajoutée au contrat, 31 opérations, quatre clés dans `economie.json`). Relu : les tests SQL des missions et de
  l'entreprise comptent maintenant les événements et primes du Carnet (adaptés, rien de retiré), filet 501 déplacé.
  Décision 34. Lancée vers 10 h 40 : les écrans du personnel dans l'add-on (`claude/phase4-addon-personnel` : tuile
  Personnel, écrans PERSONNEL et AGENCE, sans bouton de nouvelle liste en Donar). Vérifier : `git log --oneline -3
  origin/main`, `cd core && ./gradlew test --tests '*Order*' --tests '*Finding*'`.
- **5 novembre 2026, 11 h 03 : bilan (direction).**
  - Fait depuis le bilan de 9 h 05 : la mission de la mairie dans l'add-on (phase 3 close sauf le test de bout en
    bout), le simulateur des treize cibles, le personnel, l'écran de prestige et la carte des îles, la commande du
    jour et le Carnet, tous sur `main` ; contrat à 31 opérations ; sept fichiers de définitions. `main` : vérificateur
    et CI verts à chaque poussée.
  - En cours : les incidents et l'électricité côté service (`claude/phase4-incidents`), les écrans du personnel dans
    l'add-on (`claude/phase4-addon-personnel`).
  - Reste : phase 2, les trois cases de Kr ; phase 3, le test de bout en bout après Kr ; phase 4, cinq cases
    (incidents et électricité, ville, entreprise partagée, écrans de l'add-on, chiffres manquants) ; phase 5, pas
    ouverte.
  - Décisions prises seul depuis ce bilan : 32 à 34. Textes nouveaux à valider : mission, prestige et carte ; règles
    du personnel, de la commande du jour et du Carnet ; noms et apparences des candidats.
  - Pour Kr, nouveau depuis 9 h 05 : le coût de la remise en état (le simulateur montre cinq cibles hors fourchette
    sans lui), `decouverte` non lue par le service, le `DIRECTEUR`, `ratePerHour` net des salaires, `deliverOrder`
    200 sans effet, `getFindings` ajoutée au contrat.
  - Vérifier : `git log --oneline -12 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 11 h 28 : direction, les écrans du personnel fusionnés ; entreprise partagée lancée.**
  `claude/phase4-addon-personnel` en avance rapide (`0aa3e53` : `ui/personnel.ts`, `ui/ecran.ts`, quatre routes du
  personnel dans `api.ts`, PATCH et DELETE dans `http.ts`, 932 tests). Relu : aucun calcul dans l'add-on, aucun
  nouveau fichier qui touche Bedrock, tests du menu adaptés à la septième tuile. Lancée vers 11 h 10 : l'entreprise
  partagée côté service (`claude/phase4-partagee`, paquet `members/`, `invite` et `setShares`). Numéros de migration :
  V4 réservée aux incidents, V5 à l'entreprise partagée, V2 trou volontaire. Vérifier : `git log --oneline -3
  origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 11 h 47 : direction, les incidents et l'électricité fusionnés ; ville lancée.**
  `claude/phase4-incidents` en avance rapide (`b3253db` : paquet `incident/` avec `IncidentDraw`, `IncidentTicker`,
  `IncidentService`, `IncidentRoutes`, `definitions/incidents.json` et son schéma, `Factory.incidentKind` et la charge
  de l'événement `INCIDENT` au contrat, 13 tests SQL en CI, pas de migration). Relu : tirage figé par l'entreprise, le
  jour et le créneau (pas de hasard payant), ralentissement ajouté au moteur sans changer un calcul sans incident
  (treize cibles vertes), tests de contrat réécrits avec les incidents sans rien retirer, le lecteur de l'add-on
  ignore les champs inconnus. Décision 35. Lancée vers 11 h 35 : la ville côté service (`claude/phase4-ville`,
  paquet `city/`, `definitions/ville.json`, V6 si besoin). Vérifier : `git log --oneline -3 origin/main`,
  `cd core && ./gradlew test --tests '*Incident*'`.
- **5 novembre 2026, 12 h 10 : direction, l'entreprise partagée fusionnée ; add-on partagée lancée.**
  `claude/phase4-partagee` en avance rapide (`3d6145d` : `invite`, `acceptInvite`, `setShares`, `CompanyState.members`,
  code `ENTREPRISE_EN_COURS`, bloc `entreprise` de `monde.json`, tests SQL en CI, pas de migration). Relu : la
  branche contient `main`, les lignes de test retirées sont le filet 501 déplacé vers `/v1/shop/catalog` et le
  compte 31 → 32, aucun import `@minecraft` ni calcul dans les miroirs de l'add-on, une bulle à valider.
  Décision 36 ; `docs/README.md` et `docs/game-design.md` passent à 32 opérations. Lancée dans la foulée :
  l'add-on de l'entreprise partagée (`claude/phase4-addon-partagee` : écran Associés, invitation reçue, parts).
  Vérifier : `git log --oneline -3 origin/main`, `cd core && ./gradlew test --tests '*Member*'`.
- **5 novembre 2026, 12 h 31 : direction, l'add-on des incidents fusionné.** `claude/phase4-addon-incidents` en
  avance rapide (`a4d1b35` : `ui/incidents.ts`, `ui/reparation.ts`, `incidentKind` au protocole, `resolveIncident`
  dans `api.ts`, écoute de `INCIDENT`, 1019 tests). Relu : la branche contient `main`, rien hors de `addon/`, les
  lignes de test retirées sont des titres qui comptent les routes, aucun fichier de plus ne parle à `@minecraft`
  (quatorze avant, quatorze après), aucun calcul, textes sans slop. Quatre écarts de contrat notés pour Kr.
  Vérifier : `git log --oneline -3 origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 12 h 45 : direction, nouvelle organisation avec la direction du site (décision 37).**
  Kr ajoute une direction du site ; la direction du jeu passe à deux sessions (deux tournent : la ville,
  l'add-on de l'entreprise partagée ; rien de plus ne sera lancé avant qu'une finisse). `docs/direction.md` mis
  à jour. Réponses à « Pour la direction du jeu » de `docs/site-passation.md` : (1) votes : d'accord, la phase 5
  fera recevoir Votifier v2 par silooo-core (jeton et port dans l'environnement, à ouvrir par Kr), écrire
  `server_votes`, tenir la Vote party et les séries ; (2) Gemmes : la phase 5 relève les lignes de
  `shop_deliveries` sans `delivered_at`, quelle que soit leur origine (webhook PayNow du site, site SileneMC),
  crédite une fois par `order_id` et publie `GEMMES_RECUES` ; la ligne du plan est reformulée dans la liste de
  la phase 5 ; (3) Pionnier : l'add-on montrera le titre et l'objet quand la tranche add-on de la boutique lira
  les cosmétiques possédés ; le contrat n'a pas encore de champ pour un titre de joueur, il sera ajouté par
  `api/` d'abord ; (4) sessions : noté, deux ; (5) S3 : l'écran « Relier Discord » et le refus des non-admis
  dans `join` passent par le contrat (`api/`, un code `Refused` nommé) avant tout code : dites-nous la table ou le
  champ qui dit « admis » et la direction du jeu fera la route et l'écran. Observé : les fichiers du site sont
  non suivis dans le dossier de la direction du jeu, noté dans « À faire par Kr ».
- **5 novembre 2026, 13 h 02 : direction, l'add-on de l'entreprise partagée fusionné.** `claude/phase4-addon-partagee`
  en avance rapide (`9f68633` : `ui/associes.ts`, `ui/invitations.ts`, tuile Associés, `invite`, `acceptInvite`,
  `setShares` dans `api.ts`, PUT dans `http.ts`, 1177 tests). Relu : la branche contient `main` (elle y avait
  fusionné `origin/main`), rien hors de `addon/` et `tools/tests/`, aucun fichier de plus ne parle à `@minecraft`
  (quatorze), aucun calcul, textes sans slop, une phrase à reformuler. Vérifier : `git log --oneline -3
  origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 13 h 06 : direction, la ville fusionnée.** `claude/phase4-ville` par un commit de fusion
  (`2da55d0` : `main` avait avancé de l'add-on partagée, aucun fichier commun) : paquet `city/`, migration V6,
  `definitions/ville.json` et son schéma, 96 lignes de types générés, 84 fichiers, 17 tests SQL en CI. Relu : les
  lignes de test retirées sont des adaptations (semence de `ContractTest` avec `members`, constructeur de
  `Definitions`, titre « neuf fichiers »), le contrat reste à 32 opérations, aucun calcul ni import `@minecraft`
  dans les miroirs de l'add-on, toutes les clés de `ville.json` à valider. Décision 38. Vérifier :
  `git log --oneline -3 origin/main`, `cd core && ./gradlew test --tests '*City*'`.
- **5 novembre 2026, 13 h 13 : bilan (direction).**
  - Fait depuis le bilan de 11 h 03 : les incidents et l'électricité (service puis add-on, mini-jeu de réparation),
    les écrans du personnel, l'entreprise partagée (service puis add-on), la ville côté service, tous sur `main` ;
    contrat à 32 opérations ; neuf fichiers de définitions ; 1177 tests dans l'add-on. `main` : vérificateur vert à
    chaque poussée (la CI de `9f68633` et de la ville reste à relever au prochain bilan).
  - En cours : l'add-on de la ville et de l'île principale (`claude/phase4-addon-ville`), lancé à ce bilan.
  - Reste : phase 2, les trois cases de Kr ; phase 3, le test de bout en bout après Kr ; phase 4, une case (l'add-on
    de la ville et l'île principale) puis le test sur un vrai BDS ; phase 5, pas ouverte (sa liste est prête, elle
    s'ouvre dès que la phase 4 n'a plus que les cases de Kr).
  - Décisions prises seul depuis ce bilan : 35, 36, 38 ; la 37 est celle de Kr (deux directions). Textes nouveaux à
    valider : incidents (service et add-on), entreprise partagée (règles et écrans), ville (toutes les clés).
  - Pour Kr, nouveau depuis 11 h 03 : les écarts de contrat des incidents, de l'entreprise partagée et de la ville
    (ligne « Trancher trois trous du contrat »), les fichiers du site non suivis dans ce dossier, le coût de la
    remise en état toujours attendu.
  - Direction du site (`docs/site-passation.md`, « Pour la direction du jeu ») : rien de nouveau depuis les
    réponses de 12 h 45 ; le site est à l'étape S0, rien n'est « Prêt à mettre en ligne », donc rien à déployer.
  - Vérifier : `git log --oneline -12 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 13 h 21 : direction, phase 5 ouverte ; tranche des Gemmes et des grades lancée.** Liste de la
  phase 5 écrite d'après le plan et la décision 37 (Gemmes par `shop_deliveries` que le webhook PayNow du site écrit,
  votes en Votifier v2). Décision 39. Lancée : `claude/phase5-gemmes-grades` (paquet `shop/` : relevé des livraisons,
  `catalog` et `buyItem` pour les grades et le Pilier, bonus de Donar lus par le moteur ; le filet 501 des tests passe
  à `listMarket`). Toujours en cours : `claude/phase4-addon-ville`. Tranches suivantes, deux à la fois : votes, puis
  pass, cosmétiques, Discord, puis l'add-on. Quatre lignes ajoutées pour Kr (achat de test, Votifier, Discord, marché).
- **5 novembre 2026, 13 h 30 : direction, `main` réaligné sur les poussées de la direction du site.** La direction du
  site a poussé sur `main` ses étapes S0 à S2 (le site dans silooo-core : `core/.../site/`,
  `core/src/main/resources/site/`, ADR 0006, `docs/site*.md`, des lignes dans `CLAUDE.md`, `docs/README.md` et
  `core/README.md`, des retouches à `Config.kt`, `Application.kt`, `Main.kt`, `tools/anti_ia.py` et
  `tools/tests/run.sh`) ; elle vise V7 pour sa migration. Ma poussée de 13 h 24 a été refusée ; `main` local réaligné
  sans toucher aux copies locales de ses documents (plus anciennes que ce qu'elle a commité, jamais ajoutées), passation
  repoussée (`41c16c2`). Vérifier : `git log --oneline -20 origin/main`.
- **5 novembre 2026, 14 h 28 : direction, les Gemmes et les grades fusionnés ; votes lancés.** `claude/phase5-gemmes-grades`
  en avance rapide (`b3a2c93`), puis `origin/main` (site S3) fusionné par-dessus (`71a4345`) : paquet `shop/`,
  `economy/ShopBonusSource`, `session/PendingDeliveries`, 108 tests neufs, 1316 tests Gradle, pas de migration ; la
  branche avait fusionné `origin/main` avec le site S2, conflits résolus dans `README`, `Application`, `Config`,
  `Main`. Relu : la branche contient `main`, le contrat garde 32 opérations
  (descriptions de `catalog` et `buyItem` précisées, `itemId` borné), les lignes de test retirées sont le filet 501
  déplacé vers `/v1/market/listings` et des adaptations du câblage, `deploy/exemple.env.txt` gagne
  `SILOOO_DELIVERY_POLL_SECONDS` en commentaire, aucun secret, quatorze fichiers `@minecraft`. Les 26 tests SQL de
  la boutique n'ont tourné qu'en CI : à relever au prochain bilan. Décision 40. Lancée dans la foulée : les votes et
  la Vote party (`claude/phase5-votes` : Votifier v2, `vote.json`, `todayVotes`). Vérifier : `git log --oneline -3
  origin/main`, `cd core && ./gradlew test --tests '*Shop*'`.
