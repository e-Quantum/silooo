# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, 19 h 34, bilan final de la session de direction : le curseur des événements persistant fusionné (9f5376a), les phases 0 à 5 cochées sur `main` hors les cases de Kr, aucune session lancée, rien de déployé.

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions et leurs schémas), le plan, le
  game design, le design system, la documentation (`docs/`, ADR 0001 à 0005), l'outillage (`tools/`,
  CI, hooks, trois recettes) et, dans `core/`, le squelette de silooo-core (Gradle, Ktor, Flyway, clé
  `X-Silooo-Key`, heartbeat, filet 501, Dockerfile) avec les données dorées du tableur
  (`core/src/test/resources/golden/`), les routes de session (`join`, `leave`, `GET /v1/events`,
  idempotence, verrou optimiste, journal des monnaies, tests de contrat sur les 33 opérations) et le moteur
  d'économie avec ses tests dorés, branché sur `join` (`EngineFigures`, `DefinitionsBootstrap`). Onze
  fichiers de définitions du jeu : `usines`, `economie`, `boutique`, `monde`, `iles`, `missions`, `personnel`,
  `incidents`, `ville`, `vote`, `pass` ; `discord.json` est à la direction du Discord.
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
- Phase **4** (la progression) **close sauf le test en jeu**, qui attend le déploiement (Kr), le 5 novembre 2026 à 14 h 43. `island`, `prestigePreview`, `prestige` et les missions sont sur `main`
  et le simulateur des treize cibles les rejoue en CI (toutes dans leur fourchette) ; le personnel aussi (agence
  du jour, embauche, affectation, renvoi, salaires retenus sur le revenu), la commande du jour et le Carnet de
  trouvailles (`todayOrder`, `deliverOrder`, `getFindings`, migration V3) ; côté add-on, l'écran de prestige (avant,
  après), la carte des îles avec la téléportation les écrans du personnel et de l'agence, les incidents et l'électricité avec le mini-jeu de réparation, les
  écrans de l'entreprise partagée, la ville et l'île principale (1421 tests). La ville côté service (`buyZone`,
  `placeBuilding`, `upgradeBuilding`, migration V6, `definitions/ville.json`) et son add-on (`ui/ville.ts`,
  `monde/ville.ts`) sont sur `main`. Phase en cours : **5**, ouverte le
  5 novembre 2026 (décision 39) ; sur `main` : les livraisons de Gemmes, les grades et le Pilier côté service
  (paquet `shop/`), les votes et la Vote party (paquet `vote/`, Votifier v2, `todayVotes` : 33 opérations), les événements que le bot Discord lira (`PRESTIGE_ENTREPRISE`, `ANNONCE` `sansCourant`,
  payloads dans l'OpenAPI ; le bot Oooscar lui-même est à la direction du Discord, `docs/discord-passation.md`) ;
  les cosmétiques, le boost collectif et le titre du pionnier (paquet `shop/`, migration V9 `server_boosts`), le pass de
  saison (paquet `pass/`, `pass.json`, `getPass`, `claimPass`, `pass-premium`) , l'add-on de la boutique, du
  pass et des votes (`ui/boutique.ts`, `ui/pass.ts`, `ui/votes.ts`, `ui/annonces.ts`, 74a7ca1) , le curseur des
  événements de l'add-on persistant (`silooo:events-cursor`, 0cee7a5) : la phase 5 est complète, hors les cases de Kr. La direction du Discord a posé D2 sur `main` (`definitions/discord.json`,
  migration V10). Le site a pris V11 (`V11__comptes_du_site.sql`, `claude/site-comptes`) ; le jeu prend V12 et la suite.

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
Close le 5 novembre 2026 à 14 h 43 sauf le test en jeu (Kr) : toutes les cases hors Kr sont cochées, le simulateur
rejoue les treize cibles en CI, rien de l'add-on de la phase 4 n'a été essayé sur BDS.

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
- [x] add-on : écrans de la ville (`ui/ville.ts` : écrans VILLE, bâtiments, fiche, construire, poser ; tuile Ville à la place de la tuile Amis verrouillée ; `ui/annonces-ville.ts` : les `ANNONCE` de la ville relisent l'état et font parler Oooscar ; `api.ts` : `buyZone`, `placeBuilding`, `upgradeBuilding` avec `requestId` ; `protocol.ts` lit `city` ; bonus, prix du niveau suivant et prochaine zone affichés depuis `CompanyState.city`, prix d'un bâtiment neuf lu dans `definitions/ville.json`, rien de calculé), et l'île principale posée zone par zone quand la mairie les débloque (`monde/ville.ts` : plan, constructeur, synchro, voyage devant la porte de la mairie ; verrouillée au départ) ; environ 240 tests neufs, 1421 tests dans l'add-on ; textes **à valider par Kr** (`addon/README.md`) ; jamais essayé en jeu ; décision 41
- [x] add-on : incidents et électricité (`ui/incidents.ts`, `ui/reparation.ts` ; `Factory.incidentKind` lu par le protocole ; la tuile, la fiche et la carte nomment la sorte ; bouton Réparer puis mini-jeu de trois formulaires par sorte, plus un général pour une sorte inconnue, un seul bon geste par étape, un mauvais choix rejoue l'étape avec une bulle, jamais de perte ; `resolveIncident` dans `api.ts`, refus 409 par le mécanisme actuel ; sans courant : « Il faut une Centrale en marche. », pas de bouton, achat de niveaux gardé ; `INCIDENT` écouté dans `main.ts` : message au tchat, puis relecture de l'état ; la récompense n'est jamais calculée, elle vient de l'événement ; 1019 tests ; textes `INCIDENT_TEXTES`, `REPARATION_TEXTES`, quatre bulles et le nombre d'étapes **à valider par Kr**)
- [x] `definitions/` : les chiffres que la phase 4 révèle manquants, ajoutés avec leurs schémas et le tableur, jamais dans le code : salaires (`personnel.json`, fait), incidents et électricité (`incidents.json`, fait), bonus des bâtiments (`ville.json`, fait : zones, mairie, logement, plafonds, catalogue de six bâtiments ; neuf fichiers de définitions) ; seul le coût de la remise en état attend Kr (« À faire par Kr »)

## Phase 5 : boutique et vote

Condition de sortie (plan) : livraisons de Gemmes depuis le site, grades et Pilier, sites de vote et Vote party, salon
Discord ; un achat réel de test est livré une fois, et une seule. Ouverte le 5 novembre 2026 à 13 h 21 (session de
direction, décision 39), pendant la dernière tranche de la phase 4. Depuis la décision 37, le site silooo.com (direction
du site) écrit les commandes PayNow dans `shop_deliveries` et les votes arrivent de Hubyy au format Votifier v2 : la
phase 5 du jeu reçoit et crédite. Ce qui touche un compte externe revient à Kr (« À faire par Kr ») : l'achat réel de
test, Hubyy et les sites de vote, le port et le jeton Votifier, le jeton du bot Discord. Le service, l'add-on, les
définitions et la doc avancent sans attendre, et rien de ce qui en dépend n'est déployé. Le marché (`listMarket`,
`createListing`, `buyListing`) reste « pas fait » tant que Kr n'a pas tranché ; aucune lootbox ni aucun hasard payant
(`CLAUDE.md`), même si `docs/game-design.md` en parle encore.

- [x] silooo-core : livraison des Gemmes (paquet `shop/` : `DeliveryRelief` relève `shop_deliveries` toutes les `SILOOO_DELIVERY_POLL_SECONDS` secondes, 30 par défaut ; joueur retrouvé par `xuid` puis par gamertag ; une seule transaction : `delivered_at` posé s'il est encore nul, crédit, journal `livraison`, événement `GEMMES_RECUES` ; pseudo inconnu laissé en attente et rattrapé au `join` par `session/PendingDeliveries`) ; grades à vie et Pilier (`catalog` et `buyItem` : six grades dans l'ordre, un seul `available` à la fois, `pilier` prolongé de 28 jours à partir du plus tard de maintenant et de sa fin, 50 Gemmes rendues à chaque achat, `perso` réservé à Ultime, cadeau par `forXuid` payé par l'acteur, idempotence par `requestId` et `shop_purchases.request_id`) ; bonus de Donar du PDG (grade le plus haut, Pilier actif) lus dans `boutique.json` par `economy/ShopBonusSource` et multipliés comme le commerce de la ville ; sans grade, les treize cibles sont inchangées ; 108 tests neufs dont 26 SQL en CI, 1316 tests Gradle ; pas de migration ; `docs/economie.md` « La boutique », `docs/base-de-donnees.md` « Les livraisons de Gemmes », `core/README.md` ; règles **à valider par Kr** ; décision 40
- [x] silooo-core : le reste de la boutique en jeu (paquet `shop/` prolongé : `catalog` liste les grades, les abonnements, seize cosmétiques par catégorie et le boost collectif avec ce que le joueur possède ; `buyItem` écrit `player_cosmetics` (`source` = `boutique`, cadeau compris), refuse le doublon, et pour le boost écrit `server_boosts` (migration V9 : `bought_by`, `starts_at`, `ends_at`, `bonus_percent`), un boost acheté pendant un boost actif commence à la fin du précédent, publie `BOOST_COLLECTIF` pour tout le serveur ; le moteur applique le boost actif à toute la production (`boostPercent`), hors ligne compris, treize cibles du simulateur inchangées ; le titre du pionnier d'après `site_registrations.pioneer` au `join` (`shop/PioneerTitle`, `session/PendingTitles`, `player_cosmetics` `source` = `site`, `ANNONCE` `pionnier` une fois) ; les livraisons rapprochées par la clé du site (`SiteRegistrations.key`) ; `boutique.json` (`cosmetiques`, `boostCollectif`, un `nom` par abonnement) et `personnel.json` (un `nom` par rareté) avec schémas, cas invalides et types générés ; `JoinResponse.cosmetics` et les charges `BOOST_COLLECTIF` et `pionnier` décrites dans l'OpenAPI, toujours 33 opérations ; 67 tests neufs dont 23 SQL en CI ; `docs/economie.md`, `core/README.md`, `docs/base-de-donnees.md` (avec `site_registrations`) ; décision 44 ; chiffres et noms **à valider par Kr**)
- [x] silooo-core : pass de saison (paquet `pass/` : `PassRules`, `PassService`, `PassRoutes`, `PassStore`, `JdbcPassStore` ; `definitions/pass.json` (saison, prix du premium, points, dix paliers à deux pistes) avec schéma, huit cas invalides, `PassDefinitions` et types générés ; des points à chaque prestige (chaque associé), commande du jour livrée (chaque contributeur) et mission terminée, dans `season_pass`, pendant la saison seulement ; `getPass` (`PassState`) et `claimPass` (`PassClaimed`, Lingots par `creditBalance` raison `pass` ou cosmétique `source` = `pass`, une transaction, idempotent) ; `pass-premium` par `buyItem` et dans `catalog` ; quatre codes `Refused` déclarés dans `api/` (`PALIER_NON_ATTEINT`, `DEJA_RECLAME`, `PREMIUM_REQUIS`, `SAISON_INACTIVE`) et leurs bulles dans l'add-on ; les cinq cosmétiques de saison déclarés hors vente dans `boutique.json`, le chargeur et le vérificateur refusent un cosmétique du pass absent ou vendu ; `buyItem` refuse en 400 tout article `achetable: false`, cadeau compris ; toujours 33 opérations ; aucune migration ; 1723 tests Gradle sur la branche (0 échec), dont 71 du pass : `PassRulesTest` 14, `PassRoutesTest` 27, `PassPointsTest` 9, `PassSqlTest` 21 en CI ; `docs/economie.md` « Le pass de saison », `core/README.md` ; décision 45 ; chiffres et noms **à valider par Kr**)
- [x] silooo-core : votes et Vote party (paquet `vote/` : `VotifierProtocol` (décodage et signature HMAC-SHA256 en fonctions pures), `VotifierServer` (TCP, éteint sans `SILOOO_VOTIFIER_TOKEN`, port `SILOOO_VOTIFIER_PORT` 8192, publié par le compose au même numéro), `VoteRules`, `VoteService`, `JdbcVoteStore`, `VoteRoutes` ; une ligne `server_votes` par vote, `xuid` retrouvé par gamertag, Lingots hors prestige (`creditBalance`, raison `vote`), série de jours UTC avec bonus à chaque multiple de `serie.jours`, Vote party au vote de rang `voteParty.votesParJour` pour les présents, `ANNONCE` (`action` vote, voteParty) ; pseudo inconnu rattrapé au `join` par `session/PendingVotes` ; `definitions/vote.json` (1 Lingot par vote, série de 7 jours, +2, fête à 10 votes, 1 Lingot par présent, site `hubyy`) avec schéma, cas invalides et types générés ; `GET /v1/votes/today` (`todayVotes`) : le contrat passe à 33 opérations ; environ 100 tests neufs dont 16 SQL en CI ; pas de migration, pas de dépendance ; `docs/economie.md` « Les votes », `core/README.md`, `docs/deploiement.md` (port, jeton, Hubyy) ; chiffres et règles **à valider par Kr** ; décision 42) ; les sites de vote autres que Hubyy plus tard, par le même chemin
- [x] silooo-core : Discord, réduit le 5 novembre à ce que le service publie (décision 43 : le bot Oooscar est à la direction du Discord, `docs/discord-passation.md`, qui lit `game_events` avec son propre curseur) : le prestige publie `PRESTIGE_ENTREPRISE` pour tout le serveur (`xuid` nul, en plus de l'`ANNONCE` par membre), le moteur publie `ANNONCE` `sansCourant` au joueur dont une usine manque de courant (constaté par comparaison avant et après la dernière étape de remise en état, une fois par passage, pas d'état stocké), `ANNONCE` `voteParty` (votes) et `BOOST_COLLECTIF` (cosmétiques) complètent la liste ; payloads décrits dans `api/silooo-core.openapi.yaml` ; 1517 tests Gradle, 7 neufs dans `CompanyRoutesTest` ; aucune variable `SILOOO_DISCORD_*` côté jeu
- [x] add-on : boutique (catalogue, achat, cadeau, grades et Pilier, titre et objet Pionnier, bulle d'Oooscar à l'arrivée des Gemmes `GEMMES_RECUES`), pass de saison (progression, réclamer un palier), rappel de vote et Vote party, annonce du boost collectif ; textes **à valider par Kr** (fusionné le 5 novembre à 19 h, 74a7ca1 : `ui/boutique.ts`, `ui/pass.ts`, `ui/votes.ts`, `ui/annonces.ts`, 1678 tests de l'add-on dont 47 de parcours ; décision 46 ; l'objet Pionnier attend Kr, le titre s'affiche sans se choisir)
- [x] add-on : le curseur des événements persistant entre deux démarrages du serveur (propriété dynamique du monde `silooo:events-cursor`, `CursorStore` dans `events.ts`, `createEventsCursorStore` dans `monde/bedrock.ts` ; 18 tests, 1696 au total ; fusionné le 5 novembre à 19 h 34, 9f5376a ; décision 47 ; jamais vu sur un vrai BDS)
- [x] `definitions/` : `boutique.json` complété (cosmétiques, effet de prestige, boost collectif, noms des abonnements, cosmétiques de saison hors vente), `pass.json`, `vote.json`, `personnel.json` (noms des raretés), avec leurs schémas, leurs cas invalides et les types générés (`node tools/generer_types.mjs`)
- [x] docs : `docs/economie.md` « La boutique », « Le pass de saison », « Les votes » ; `core/README.md` (boutique, votes, pass, Discord côté service) ; `docs/deploiement.md` : ce que Kr branche (Votifier par la tranche des votes, les variables du bot par la direction du Discord) ; `docs/base-de-donnees.md` (V7, V9 ; V10 par la direction du Discord)

Tranches prévues, deux à la fois au plus (décision 37) : gemmes-grades et votes ; puis pass, boutique-cosmetiques
(après gemmes-grades, même paquet `shop/`) et discord ; puis l'add-on de la boutique, du pass et des votes, après les
routes. Depuis le 5 novembre à 15 h 31, le bot Discord (Oooscar) est à la direction du Discord (`docs/discord.md`,
`docs/discord-passation.md`, paquet `core/.../discord/`, ADR 0007, migration V8) : la phase 5 du jeu publie les
événements que le bot lit, elle n'écrit rien sur Discord (décision 43). Le filet 501 des tests (`ApiTest`) passe de `/v1/shop/catalog` à `/v1/market/listings`, qui reste « pas fait ».

## À faire par Kr

- [ ] Salle d'attente (`docs/deploiement.md`, « La salle d'attente ») : déplacer le monde de test hors de 19142
  (`BDS_PORT`, `BDS_UDP_PORTS`, `BDS_UDP_PORTS_PUBLIES` dans le fichier d'environnement), ouvrir 19175-19206 en UDP et
  les nouveaux ports du test, poser `BDS_TRANSFERT_HOTE` et `BDS_TRANSFERT_PORT` si les testeurs doivent être transférés,
  puis `SILOOO_ATTENTE=oui bash deploy/scripts/deploy.sh <tag>` ; ajouter les testeurs à la liste blanche de
  `silooo-attente` ; au premier essai avec un client, vérifier le saut (blocs atteignables, chute, classement), l'écran
  « Devenir Pionnier », `/lier` sur Discord et le transfert NetherNet

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans le fichier d'environnement de `/var/www/silooo`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir : 19142 en TCP (signalisation NetherNet) plus une plage UDP contiguë pour le jeu, un port par joueur (le compose de `deploy/` propose 19143 à 19174 pour 32 joueurs ; `docs/adr/0004-port-bedrock.md`)
- [ ] Télécharger `bedrock-server-1.26.62.1.zip` dans un navigateur (minecraft.net refuse `curl`, même avec un User-Agent honnête, depuis le PC comme depuis les runners GitHub), calculer `sha256sum`, écrire la somme dans `deploy/bds/bds.sha256`, créer `/var/www/silooo/bds/` sur le VPS (`mkdir -p`, puis `chown 10001:10001` : l'uid du conteneur), y poser le zip (décision 26, `docs/deploiement.md`, « Le zip de BDS »)
- [ ] `docker login ghcr.io` sur le VPS (les images `silooo-core` et `silooo-bds` sont publiées par la CI depuis `main`), et confirmer le nom du réseau Docker de SileneMC (`SILENEMC_NETWORK`, `silenemc_default` par hypothèse)
- [ ] Fournir un monde Bedrock créé dans le client avec l'option « Beta APIs » activée, ou valider la méthode que la phase 2 trouvera pour l'activer sur BDS : `server-net` et `server-admin` l'exigent (`docs/bedrock.md`, fait 1)
- [ ] Au premier test sur un vrai client : vérifier que le pack de ressources masque bien les titres préfixés (`silooo:hud|`, `silooo:consigne|`) et affiche les monnaies en haut à gauche, qu'Oooscar a la bonne orientation (échelle à droite) ; alors passer `titleChannel` à `true` dans `addon/src/config.ts`
- [ ] Au même test, le monde : les mini-îles (délai de la ticking area, pose par lots sans à-coup, orientation du panneau), la cinématique (cadrage des plans, glissé de caméra, fondus, HUD masqué, déplacements bloqués, accroupi pour passer), la téléportation vers une zone non chargée, l'entité `silooo:oooscar` (format 1.21.0, visible avec le pack). `addon/README.md`, « Le monde », liste ce que seul un vrai BDS dira ; et pour la mission : le rendu de la bulle de fin dans le tchat, la tuile Objectifs sur deux lignes, l'ordre arrivée, cinématique, lecture des missions ; et pour le prestige et la carte : la téléportation vers une zone déchargée, le rendu des formulaires Carte (deux pages) et Prestige, les repères `[P]` et `[C]` ; et pour le personnel : les PATCH et DELETE par `http.request` (DELETE sans corps), la longueur des formulaires PERSONNEL et AGENCE, le rendu des repères `[M]` et `[R]` et des couleurs de rareté ; et pour les incidents : les repères flamme et éclair sur la tuile et la carte, le mini-jeu en trois formulaires, le message du tchat à l'ouverture et à la réparation ; et pour l'entreprise partagée : la tuile Associés et son repère `[N]`, la liste des joueurs connectés à inviter, le formulaire des parts, l'invitation reçue qui arrive pendant le jeu
- [ ] Trancher l'identité des joueurs : la Script API ne donne pas le XUID ; l'add-on envoie une empreinte du gamertag (décision 25). Valider, ou choisir une source de XUID (journal de BDS, `allowlist.json`), ce qui demandera de changer la clé des joueurs
- [ ] Trous de contrat relevés par les routes d'économie : pas de 403 ni de 404 sur `/v1/companies/...` (une entreprise inconnue, un acteur qui n'est pas membre ou une usine inconnue rendent 400 `REQUETE_INVALIDE`) ; pas de type d'événement dédié (`collect`, `buyLevels`, `restoreStep` publient `ANNONCE` avec `payload.action`) ; pas d'opération pour racheter une usine en ruine ni pour découvrir une usine ; `CompanyState.island`, `PrestigePreview.lingotsIfDoubled` et `shares` facultatifs dans l'OpenAPI mais toujours envoyés (à rendre obligatoires ?) ; `PRESTIGE_ENTREPRISE` est publié pour tout le serveur depuis la tranche Discord, en plus d'un `ANNONCE` par membre avec sa part ; `getMissions` rend 400 pour un joueur inconnu, pas 404 ; le `payload` des événements `ANNONCE` (`action`, `missionId`, `rewardLingots`, `collected`, …) n'a pas de schéma dans l'OpenAPI, l'add-on le lit à l'aveugle ; le rôle `DIRECTEUR` du plan n'est pas modélisé (deux rôles, `SALARIE` et `CHEF`) ; `ratePerHour` et `factories[].ratePerHour` sont nets des salaires du personnel (dit dans la description du contrat, sans champ brut à côté) ; les routes du personnel publient elles aussi des `ANNONCE` génériques ; l'add-on ne branche aucun événement de prestige (le service n'en publie qu'un `ANNONCE` par membre, `payload` non décrit) ; `deliverOrder` sur une commande déjà terminée rend 200 sans effet (pas de code de refus : un nouveau code casserait les tests de l'add-on) ; `getFindings` n'était pas au contrat de départ (31 opérations) ; pour le personnel vu de l'add-on : pas de capacité de postes dans `CompanyState.factories[]` (l'add-on ne sait pas combien de places restent), pas de désaffectation ni de promotion en chef, pas de nom lisible pour une apparence (`skinId` montré brut), le rôle du joueur (PDG ou associé) n'est pas dans l'état (l'add-on montre Renvoyer à tous et laisse le service refuser) ; `resolveIncident` sur un incident déjà clos rend 200 sans effet (pas de code `INCIDENT_CLOS`, il aurait touché l'add-on) ; l'alerte du courant qui manque (`ANNONCE` `sansCourant`) n'est pas encore lue par l'add-on, et son relais sur Discord est au bot de la direction du Discord ; entreprise partagée : aucune route pour quitter l'entreprise, reprendre ou libérer l'entreprise de départ laissée sans membre ; l'add-on ne lit pas encore `CompanyState.members` ; relevés par l'add-on des incidents : la réponse de `resolveIncident` ne donne pas la récompense (elle vient de l'événement), aucun événement quand l'incident était déjà clos, la charge de `INCIDENT` n'a pas de schéma, et l'événement ne porte pas l'état, d'où un `getCompany` par événement ; relevés par la ville : aucun code pour « bâtiment verrouillé par la mairie » (rendu `ZONE_VERROUILLEE`) ni pour « quantité maximale atteinte » et « dernier niveau » (rendus `DEJA_POSSEDE`), le maximum de 16 zones est fixé par le contrat et le code de `buyZone` et non par `ville.json`, `LIVRAISON_VITESSE` n'a pas encore de lecteur ; relevés par l'add-on de l'entreprise partagée : aucune route pour quitter une entreprise ni pour lister ses invitations en attente (une invitation manquée à la reconnexion est perdue), `ANNONCE` ne va qu'à celui qui agit, accepter quitte l'ancienne entreprise sans retour, le XUID est encore un condensé du gamertag ; relevés par la boutique : aucun code de refus pour `perso` sans Ultime (rendu `GRADE_PRECEDENT_REQUIS`), pas de 404 ni de 403 (l'inconnu donne 400), aucun événement d'achat, `buyItem` ne rend que `Balances` ; relevés par l'add-on de la ville : le payload d'`ANNONCE` n'a pas de schéma, `city` ne donne ni le prix d'un bâtiment neuf (lu dans `ville.json`) ni le bonus du niveau suivant, aucune route ne donne la position du joueur, les gabarits des préfabriqués (dimensions, matériaux) sont dans le code de l'add-on et pas dans `ville.json` ; relevés par les votes : la réception Votifier (TCP) n'est pas dans l'OpenAPI, le `payload` d'`ANNONCE` reste libre (`action` vote, voteParty), `todayVotes` ne dit pas les jours qui restent avant le bonus de série ; relevés par les cosmétiques : aucune route ne rend le boost collectif en cours (l'add-on ne le sait que par l'événement), `ceoHoldings` ignore les cosmétiques, `site_registrations.pioneer` est vrai par défaut et le site ne l'écrit pas (tout inscrit est pionnier : à dire à la direction du site)
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
- [ ] Discord (direction du Discord, `docs/discord-passation.md`, point 8) : quand elle écrit « Prêt à mettre en ligne » (le QG, fin de D2) et que Kr a donné le jeton du bot (`SILOOO_DISCORD_TOKEN`) et l'identifiant du serveur (`SILOOO_DISCORD_SERVEUR`), posés dans l'environnement du conteneur silooo et jamais dans le dépôt, la direction du jeu déploie `main` (projet Docker « silooo ») avec `SILOOO_DISCORD_ACTIF=a-blanc`, puis `oui` ; rien avant, jamais sans vérificateur vert ni CI verte.
- [ ] Phase 5 : trancher le marché entre joueurs (`listMarket`, `createListing`, `buyListing`) : `CLAUDE.md` interdit toute monnaie échangeable entre joueurs, donc ces routes restent « pas fait » tant que Kr n'a pas dit ce qu'il en reste.
- [ ] Deux dossiers `.agents/` et `.codex/` sont apparus non suivis dans le dossier de la direction du jeu le 5 novembre 2026 à 13 h 16, sans qu'elle les écrive : dire s'il faut les ignorer dans `.gitignore` ou les retirer. Les copies locales de `docs/site.md`, `docs/site-direction.md` et `docs/site-passation.md` qui traînaient dans ce dossier (plus anciennes que `main`, elles bloquaient chaque fusion) sont mises de côté dans un stash (`git stash list` : « copies locales des docs du site ») : les reprendre ou les jeter (`git stash drop`). Depuis ddf698f (direction du site), `docs/references/site/` est sur `main`, octet pour octet identique à la copie non suivie qui bloquait la fusion : cette copie est dans un second stash (`stash@{0}`, 16 h 45) ; les deux stashs peuvent être jetés (`git stash drop`), rien n'y manque à `main`.
- [ ] Phase 4 : au test sur console, tout l'add-on de la phase 4, jamais essayé en jeu : la ville et l'île principale (zones posées quand la mairie les débloque, catalogue, pose, amélioration, bulles d'Oooscar), le prestige, la carte et le voyage, le personnel, les incidents et la réparation, l'entreprise partagée.
- [ ] L'agence : `docs/game-design.md` disait une nouvelle liste de candidats achetée en Donar ; mis d'accord le
  5 novembre à 17 h avec le service et `docs/economie.md` (décision 33 : rien à acheter, la liste vient de l'entreprise
  et du jour) : valider, ou rétablir l'achat
- [ ] Phase 6 (beta) : l'admission des pionniers. Le site écrit `site_registrations` (migration V7) et dessinera `beta_access` (kit de Kr) ; la porte au `join` en mode `beta` du site (code `PAS_ADMIS` déclaré dans `api/`, puis la route et l'écran) est la première tâche de la phase 6, que la direction du jeu n'ouvre pas sans Kr. **À trancher par Kr : le sens du code de liaison Discord.** Le kit du site dit un code de six caractères donné par Discord et tapé en jeu (`POST /v1/links/discord`, envoyé par l'add-on) ; la direction du Discord, d'après la critique, dit un code montré en jeu et tapé sur Discord (`/lier`, D3). Une seule route ira au contrat, et un seul écran dans l'add-on, quand les deux directions ou Kr l'auront écrit.
- [ ] Phase 5 : Docker publie le port Votifier (`SILOOO_VOTIFIER_PORT`, 8192/tcp) en passant à côté d'ufw : vérifier le pare-feu du VPS et, si possible, n'ouvrir ce port qu'à Hubyy.
- [ ] Mise en ligne du site : suspendue le 5 novembre 2026 à 15 h 48 par la deuxième direction du site (révision de S0 à S5 contre le kit de Kr, `docs/site-revision.md`, worktree `silooo-site`). Rien n'est demandé à Kr pour le site (DNS, bloc Caddy, variables, mentions) tant que `docs/site-passation.md` ne redit pas « Prêt à mettre en ligne » ; le jeu peut se déployer sans lui (site en `attente`, port non publié). La base `silooo` reste à confirmer.
- [ ] Site : `PioneerRoutesTest` « Jeton absent, falsifié ou périmé » a échoué dans trois runs de la CI de `main` (69d98b1, ddf698f, 870b196 : 303 au lieu de 400 après onze minutes) puis passé au quatrième (234793f, 17 h 02) sans que le paquet `site/` change : intermittent ; à la direction du site, qui le regarde dans sa révision.
- [x] Sur le PC : `npx playwright install chromium` : fait avant 19 h (l'étape « Site : pages dans Chromium » du
  vérificateur passe sur ce poste depuis 74a7ca1, 19 h 07)
- [ ] Site (point 9 de `docs/site-passation.md`) : deux variables facultatives, `SILOOO_SITE_URL_SILENEMC` et
  `SILOOO_SITE_URL_HUBYY` (les liens « Voir aussi » du pied de page), à poser dans l'environnement du VPS quand Kr
  voudra les liens ; sans elles, pas de lien
- [ ] Phase 5, après l'add-on : dire comment un joueur choisit son titre parmi ceux qu'il possède (l'add-on affiche un
  titre sans le laisser choisir) ; l'objet de déco du pionnier (point 3 du site) ; et dire si une route des soldes
  seuls vaut la peine (le HUD garde ses Lingots d'avant un vote jusqu'à la prochaine collecte)
- [ ] À savoir pour l'exploitation : si la table `game_events` est vidée (base remise à zéro), effacer aussi la
  propriété dynamique `silooo:events-cursor` du monde Bedrock, sinon l'add-on garde un curseur au-dessus des
  identifiants et n'annonce plus rien (décision 47)
- [ ] Jeter les branches `claude/phase5-*`, `claude/phase0-*`, `claude/phase1-*`, `claude/phase2-*` sur `origin`
  (toutes fusionnées ; la direction du jeu ne supprime jamais une branche distante) et les deux stashs du poste
- [ ] Un fichier `bedrock-server-1.26.62.1.zip` non suivi est apparu à la racine du dépôt le 5 novembre vers 19 h 45
  (posé par Kr ?) : la direction ne l'a ni ajouté ni déplacé ; `.gitignore` ne l'ignore pas. Dire où il va
  (`docs/deploiement.md`, décision 26) ou l'ignorer

## Décisions prises sans Kr

48. **La salle d'attente (7 novembre 2026).** Kr a choisi le port public 19142 pour la salle d'attente, le monde de
    test déplacé et ses testeurs transférés, et l'inscription Pionnier par code en jeu. Tranché sans Kr : un second
    serveur (`silooo-attente`, même image, `SILOOO_MODE=attente`) plutôt qu'une zone du monde du jeu, pour que les
    visiteurs ne voient rien du jeu ; un monde plat sans couche écrit par `tools/monde_attente.py` (Beta APIs posé
    dans le level.dat) ; une nouvelle route `issueLobbyLinkCode` (`POST /v1/lobby/link-codes`, 35 opérations) qui crée
    la ligne `players` sans entreprise, inscrit pionnier dans `site_registrations` (admission selon
    `SILOOO_SITE_ADMISSION`) et émet le même code que `issueDiscordLinkCode` ; la saisie `/lier` reste inchangée et
    n'admet personne. Aucune migration. Le score du saut est un objectif de tableau du monde, sous le gamertag ; il ne
    vaut rien dans le jeu. Le XUID envoyé est celui de l'add-on (empreinte du gamertag, décision 25). À relire par Kr :
    un visiteur de la salle d'attente compte désormais dans `players` avant d'avoir joué.
49. **La salle d'attente v2 (7 novembre 2026).** Décisions de Kr : les records dans silooo-core, aucune récompense
    promise. Tranché sans Kr : **V17** (`V17__salle_attente.sql` : `lobby_records`, `lobby_week_bests`,
    `lobby_week_posts`), confirmé au rebase sur la 0.1.4, qui n'ajoute aucune migration ; appliquée sur le VPS le
    7 novembre, son fichier ne change plus (son commentaire dit encore « provisoire ») ; trois routes (38 opérations) :
    `recordLobbyRun` (refus au-delà de `plafondParSeconde` blocs par seconde), `getLobbyBoard`, `getLobbyPlayer` ; la
    semaine part du lundi à minuit, heure de Paris ; le podium du lundi part à l'heure du rapport de `discord.json`
    dans `#discussion`, une fois par semaine (`lobby_week_posts`), les non reliés restent anonymes. Le lobby garde le
    joueur « déjà venu » dans une propriété dynamique du monde. La boussole perd `lockMode` (le texte de verrou) et
    revient si elle est jetée. 32 places au lieu de 40 : la plage UDP ouverte (19175-19206) en compte 32, et 19207 est
    déjà pris par le monde de test. Le XUID reste l'empreinte du gamertag (décision 25) : un gamertag qui change donne
    encore une nouvelle ligne, jusqu'au vrai XUID.

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
41. **L'add-on de la ville : la tuile Ville prend la place de la tuile Amis, pas de démolition, gabarits dans le
    code.** Tranché par la tranche de l'add-on : la tuile Ville remplace la tuile verrouillée Amis du menu (les amis
    restent « pas fait ») ; la ville est relue à chaque entrée dans l'écran et après chaque pose ; aucune démolition
    de bâtiment ; les gabarits des préfabriqués (forme et matériaux de chaque bâtiment) vivent dans
    `addon/src/monde/ville.ts`, comme le plan des mini-îles : ce sont des formes, pas des chiffres du jeu, qui
    restent dans `ville.json`. La phase 4 se clôt sans essai en jeu : tout son add-on attend le test sur BDS (Kr).
    À relire par Kr.
42. **Les votes : Lingots hors prestige, série à chaque multiple de sept, fête au vote de rang exact, jour UTC.**
    Tranché par la tranche des votes : la récompense est en Lingots hors prestige (pas de Clés : aucune lootbox) ; le
    bonus de série tombe au premier vote du jour à chaque multiple de `serie.jours` ; la Vote party est donnée au vote
    qui atteint exactement `voteParty.votesParJour`, aux joueurs présents (battement récent et `players`), une fois par
    jour UTC ; un site inconnu est accepté et enregistré tel quel ; la base injoignable rend `Unavailable` à Hubyy ; le
    port Votifier est publié par Docker au même numéro dans le conteneur et sur le VPS (hors ufw : Kr vérifie le
    pare-feu). À relire par Kr.
43. **Le service ne parle pas à Discord : il publie, le bot lit.** Kr a ouvert le 5 novembre une direction du Discord
    (`docs/discord.md`, `docs/discord-passation.md`) qui construit le bot Oooscar dans `core/.../discord/` (ADR 0007,
    migration V8), lecteur de `game_events` avec son propre curseur. La tranche `claude/phase5-discord`, rendue le
    même jour avec un envoyeur par webhook, un suiveur de `game_events` et une migration V8 dans le même paquet, a été
    réduite avant sa fusion : deux lecteurs et deux voix sur Discord auraient fait double emploi, et le paquet, le
    numéro et les variables `SILOOO_DISCORD_*` revenaient à la direction du Discord. Le jeu garde la publication des
    événements (prestige pour tout le serveur, courant qui manque, Vote party, boost collectif) et leurs payloads
    dans l'OpenAPI ; le courant qui manque se constate par comparaison avant et après la dernière étape de remise en
    état, une fois par passage. Les migrations du jeu prennent V9 et la suite. À relire par Kr.
44. **Les cosmétiques : un cadeau est un achat ; le boost ne s'offre pas, s'enchaîne sans s'empiler et compte hors
    ligne ; le pionnier ne s'achète pas.** Tranché par la tranche des cosmétiques : un cosmétique offert a la même
    `source` (`boutique`) qu'un achat ; le boost collectif ne s'offre pas (400) ; un boost acheté pendant un boost actif
    commence à la fin du précédent, sans cumul des pourcentages, et son bonus s'applique à toute la période, joueur
    hors ligne compris ; le titre `pionnier` est hors catalogue (400) et ne vient que de `site_registrations` ; le
    chargeur refuse un `personnel.json` sans `nom` par rareté. À relire par Kr.
45. **Le pass : des points en saison seulement, une réclamation possible après, un premium par saison et par
    joueur, aucun événement.** Tranché par la tranche du pass : les points ne tombent que du premier au dernier jour
    de la saison (`pass.json`), mais un palier atteint se réclame encore après ; le premium se vend en saison
    seulement, une fois par joueur, cadeau compris ; un cosmétique déjà possédé ne bloque pas le palier (il est
    simplement déjà là) ; le pass ne publie aucun événement (l'add-on relit `getPass`) ; `Dependencies.pass` est
    facultatif (501 sans lui) ; les cosmétiques de saison sont déclarés dans `boutique.json` hors vente, comme le
    titre du pionnier, et rien d'`achetable: false` ne s'achète ni ne s'offre. À relire par Kr.
46. **L'add-on de la boutique, du pass et des votes.** Tranché par la tranche de l'add-on : la tuile Associés passe
    sous Personnel, juste après l'Agence (le menu principal a huit places, Boutique prend la septième) ; le pass de
    saison est une entrée de la Boutique, pas une tuile ; le préfixe de personnalisation (`perso`) est caché ; le prix
    du premium est celui du catalogue, jamais écrit dans l'add-on ; la rareté `COMMUN` s'affiche « Ordinaire »
    (`personnel.json`). Laissé pour plus tard, sans case : aucune route ne rend les soldes seuls, donc le HUD garde
    ses Lingots d'avant un vote jusqu'à la prochaine collecte ; les charges des événements ne sont décrites qu'en
    texte dans le contrat ; aucun moyen de choisir son titre parmi ceux qu'on possède ; l'URL de la boutique du site
    n'est pas dans l'add-on (le site est suspendu). À relire par Kr.
47. **Le curseur des événements survit au redémarrage, et ce qu'on y perd.** Tranché par la tranche du curseur :
    l'add-on garde son curseur dans la propriété dynamique `silooo:events-cursor` du monde, lu au premier besoin et
    non au chargement du script (le monde n'est pas lisible si tôt) ; 0 si rien, si la valeur n'est pas un entier sûr
    positif ou si la lecture lève ; sauvé après chaque page qui avance, une erreur de sauvegarde journalisée une fois.
    Au redémarrage, plus rien ne se rejoue : une invitation d'entreprise reçue et pas encore traitée avant un
    redémarrage est perdue côté add-on (le service la garde, aucune route ne la liste : une route de liste à ajouter
    plus tard) ; un curseur plus haut que les identifiants du service (table `game_events` vidée) ferait taire les
    événements : effacer la propriété avec la table (ligne de Kr). À relire par Kr.

## À valider par Kr

- Salle d'attente v2 (7 novembre) : l'accueil, les hologrammes, les rappels, le podium du lundi d'Oooscar
  (`core/.../discord/LobbyPodium.kt`, `LobbyPodiumTexts`), « Pionnier · pseudo », le nom « Silooo · ouverture bientôt »,
  le nom et la description du pack de ressources `resource-pack-attente/`, les paliers et le plafond de
  `definitions/attente.json`, le salon du podium (`#discussion` en attendant le plan du serveur).

- Salle d'attente (7 novembre) : tous ses textes (`addon/src/attente/textes.ts`, liste dans `addon/README.md`,
  « Textes à valider, salle d'attente »), le nom du classement, les couleurs des blocs, la boussole comme objet du menu.

- D3 Discord (6 novembre) : **V13 réservée** à la preuve de liaison émise en jeu (codes temporaires, preuves,
  limites d'essais). Les autres migrations prennent V14 et la suite. D1 attend selon Kr. Les textes de liaison
  et les choix de sécurité sont provisoires ; aucune fusion d'identités OAuth ni attribution de rôle sans preuve.

- Reprise Discord du 6 novembre : les nouveaux textes de règles, rejoindre, bienvenue et tuto avant ouverture
  ainsi que le rapport hebdomadaire restent à valider. La migration V12 est réservée au rapport du Discord ;
  toute nouvelle migration du jeu prend V13 ou la suivante libre. Voir [discord-passation.md](discord-passation.md).

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
- L'add-on de la ville : les textes des écrans Ville, bâtiments, fiche, construire et poser, les bulles d'Oooscar sur
  les annonces de la ville, la tuile Ville à la place de la tuile Amis ; la liste est dans `addon/README.md`.
- Les votes : `vote.json` (1 Lingot par vote, série de 7 jours, +2 Lingots, Vote party à 10 votes par jour, 1 Lingot
  par présent, site `hubyy`) et les règles de la décision 42.
- Les cosmétiques (`boutique.json`, `docs/economie.md` « La boutique ») : seize articles et leurs prix en Gemmes (usines :
  Usine de brique 150, Usine d'acier 250, Usine de verre 400 ; véhicules : Camion rouillé 200, Camion bleu de travail 350,
  Camion doré 600 ; pets : Chat d'atelier 300, Corbeau du silo 500, Renard de la ferraille 800 ; décor : Lampadaire
  d'usine 50, Bac à fleurs 100, Fontaine de cour 300 ; effets de prestige : Étincelles 250, Pluie de rivets 250 ; titres :
  Pionnier, hors vente, et Contremaître 120) ; le boost collectif à 150 Gemmes, +25 % pendant 60 minutes ; les noms
  d'abonnements « Pilier » et « Préfixe perso » ; les noms de raretés « Ordinaire », « Rare », « Exceptionnel »
  (`personnel.json`) ; et les règles de la décision 44.
- Le pass de saison (`pass.json`, `docs/economie.md` « Le pass de saison ») : « Saison 1 » du 5 novembre 2026 au
  31 janvier 2027, premium à 250 Gemmes, 100 points par prestige, 20 par commande livrée, 10 par mission, dix paliers
  de 40 à 1 300 points, 1 à 5 Lingots sur la piste gratuite, cinq cosmétiques de saison sur la piste premium
  (`s1-fanion`, `s1-lanterne`, `s1-chat-livreur`, `s1-sillage-dore`, `s1-titre-fondateur`, et leurs noms dans
  `boutique.json`) ; les quatre bulles d'Oooscar des refus du pass (`addon/src/ui/textes.ts`, tableau
  d'`addon/README.md`) ; et les règles de la décision 45.
- `docs/game-design.md`, l'agence : « nouvelle liste en Donar » retiré, le texte suit la décision 33 et `docs/economie.md`
  (rien à acheter pour changer la liste du jour), contradiction relevée par la direction du site.
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
- Les textes de l'add-on de la boutique, du pass et des votes (74a7ca1 : `addon/src/ui/textes.ts`, `boutique.ts`,
  `pass.ts`, `votes.ts`, `annonces.ts`) : la tuile « Boutique » et son solde (« Gemmes 12 »), les rayons (« Grades »,
  « Cosmétiques », « Boost collectif », « Pass de saison »), les catégories (« Skins d'usine », « Skins de véhicule »,
  « Compagnons », « Décors », « Effets de prestige », « Titres »), les fiches d'article (« Tu l'as. », « Ton abonnement
  court. », « Il faut d'abord le grade précédent. », « Les grades se prennent dans l'ordre. », « Dure N jours. Chaque
  achat les prolonge. », « Ton pseudo est annoncé à tous. », « Les boosts se suivent, sans s'empiler. »), les boutons
  (« Acheter », « Prolonger », « Offrir »), l'écran Offrir (« Choisis qui reçoit … », « Aucun autre joueur connecté. »),
  « Les Gemmes s'achètent sur la boutique du site. », les bulles d'Oooscar (« Achat effectué ! », « Abonnement
  prolongé ! », « Boost lancé pour tout le serveur. », « Cadeau envoyé ! », les Gemmes reçues), l'écran du pass (les
  paliers, « Passer premium »), les rappels de vote, les annonces (boost collectif, vote party, sans courant,
  pionnier) et la tuile Associés déplacée sous Personnel. Les points d'exclamation des bulles sont à relire.

## Journal

- **7 novembre 2026, soir : salle d'attente v2** (branche `claude/salle-attente`, en local : ce VPS ne pousse pas).
  Records dans silooo-core, accueil sur le modèle de Hubyy, hologrammes, boussole sans verrou, transfert des testeurs
  par le menu, podium du lundi, paliers, rappels. Vérifié : tests du service (lobby, contrat, podium ; les tests SQL
  demandent Docker et restent ignorés ici), 1721 tests de l'add-on, tests de `start.sh` et du compose, pack chargé sur
  un BDS 1.26.62.1 local (« salle d'attente prête », hologramme invoqué). Pas essayé avec un vrai client. Pas déployé :
  le compte `ubuntu` n'a pas Docker.
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
- **5 novembre 2026, 14 h 43 : direction, l'add-on de la ville fusionné ; phase 4 close sauf Kr.**
  `claude/phase4-addon-ville` (3da3301, puis 7381554 qui avait fusionné `main`) fusionné par-dessus l'étape S4 de la direction du site (fad5319, migration V7 prise par le site) (`3380704`) : 37 fichiers,
  tous dans `addon/`, +5011 ; `ui/ville.ts`, `ui/annonces-ville.ts`, `monde/ville.ts`, `api.ts`, `protocol.ts` ;
  1421 tests, environ 240 neufs. Relu : la branche contenait `main`, aucun fichier interdit, les lignes de test
  retirées sont l'adaptation du menu (la tuile Amis verrouillée devient Ville, l'île principale se débloque),
  quatorze fichiers `@minecraft`, aucun secret, rien de calculé (prix et bonus viennent de `city` et de
  `ville.json`). Décision 41 ; quatre écarts de contrat notés ; une ligne pour Kr (le test en jeu de tout l'add-on
  de la phase 4). La phase 4 n'a plus que des cases de Kr. CI : les runs de 71a4345 et bd9f4e3 ont été annulés
  par les poussées suivantes (dont fad5319, direction du site) ; les tests SQL de la boutique n'ont pas encore
  fini un run : à relever au bilan. Vérifier : `git log --oneline -3 origin/main`, `cd addon && npm test`.
- **5 novembre 2026, 14 h 55 : bilan (direction).**
  - Fait depuis le bilan de 13 h 13 : la phase 4 close sauf Kr (l'add-on de la ville et de l'île principale sur `main`,
    1421 tests dans l'add-on) ; la phase 5 ouverte (décision 39) et sa première tranche sur `main` : les livraisons de
    Gemmes, les grades et le Pilier côté service (paquet `shop/`, 1316 tests Gradle) ; `main` réaligné trois fois sur
    les poussées de la direction du site (S0 à S4 ; la migration V7 est au site). Vérificateur vert à chaque poussée.
    CI : `9a4fb3b` verte sur les cinq jobs à 14 h 54 (`verifier` avec les 26 tests SQL de la boutique et ceux de l'inscription du site, `migration` jusqu'à V7, `image`, `image-bds`, `deploy-fichiers`) ; les runs de 71a4345, bd9f4e3, fad5319 et 3380704 avaient été annulés par les poussées suivantes.
  - En cours : les votes et la Vote party (`claude/phase5-votes` : Votifier v2, `vote.json`, `todayVotes`, 33 opérations
    à la fusion) ; Discord (`claude/phase5-discord` : webhook, suiveur de `game_events`, migration V8, le prestige
    publie `PRESTIGE_ENTREPRISE`). Deux sessions, pas plus (décision 37).
  - Reste : phase 2, les trois cases de Kr ; phase 3, le test de bout en bout ; phase 4, le test en jeu ; phase 5, après
    votes et Discord : le pass de saison, les cosmétiques et le boost collectif (même paquet `shop/`), l'add-on de la
    boutique, du pass et des votes, la case des définitions et celle des docs ; le point 3 de la direction du site
    (les variables du site dans `deploy/exemple.env.txt` et `docs/deploiement.md`, l'exemption retirée de
    `tools/tests/deploy-compose.mjs`), par la direction dès que votes et Discord ne touchent plus ces fichiers.
  - Décisions prises seul depuis ce bilan : 39, 40, 41. Textes et chiffres nouveaux à valider : la boutique (règles et
    `boutique.json`), l'add-on de la ville, puis `vote.json` et les textes Discord quand ils arriveront.
  - Pour Kr, nouveau depuis 13 h 13 : l'achat réel de test, Hubyy et Votifier, Discord, le marché, le test en jeu de
    l'add-on de la phase 4, les dossiers `.agents/` et `.codex/` et le stash, l'agence, l'admission des pionniers
    (phase 6).
  - Direction du site (`docs/site-passation.md`, « Pour la direction du jeu », huit points) : (1) les fichiers de Kr :
    `docs/site-direction.md` et `docs/site-passation.md` sont maintenant sur `main` par vos soins ; les copies locales
    de ce dossier, plus anciennes, sont dans un stash (ligne de Kr) ; `docs/references/site/` reste non suivi ici et
    n'est pas à nous : à Kr de le pousser. (2) Fichiers partagés : nos tranches ajoutent des lignes, jamais un bloc ;
    en ce moment `claude/phase5-votes` et `claude/phase5-discord` touchent `Config.kt`, `Main.kt`, `Application.kt`,
    `core/README.md`, `docs/deploiement.md`, `deploy/exemple.env.txt` et `deploy/compose.yml` (le port Votifier) ;
    Discord prend la migration V8. (3) Variables du site : à nous ; la direction le fait après la fusion des deux
    tranches en cours, qui éditent les mêmes fichiers ; `SILOOO_SITE_ADMISSION` et `SILOOO_SITE_TRUSTED_PROXY`
    compris. (4) Admission : merci pour la table et la clé ; la porte au `join` (code `PAS_ADMIS` déclaré dans
    `api/`, puis la route) est la première tâche de la phase 6 (la beta), que cette direction n'ouvre pas sans Kr :
    notée dans « À faire par Kr », avec la preuve du gamertag à trancher. (5) S0 à S3 : rien. (6) Un `nom` pour
    `pilier` et `perso` dans `boutique.json` : d'accord, par la tranche des cosmétiques (schéma compris) ; le site et
    l'add-on le liront. (7) Un `nom` par rareté dans `personnel.json` : d'accord, même tranche ; l'add-on a
    aujourd'hui ses propres libellés (`ui/textes.ts` : « Exceptionnel » pour `EPIQUE`), il lira le JSON à la tranche
    add-on de la phase 5 ; le mot affiché reste à valider par Kr. (8) L'agence : le service et `economie.md` font foi
    (décision 33, rien à acheter) ; ligne ajoutée pour Kr, qui tranche et met le game design d'accord. Rien n'est
    « Prêt à mettre en ligne » : rien à déployer.
  - Vérifier : `git log --oneline -15 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 15 h 30 : direction, les votes fusionnés ; Discord rendu, cosmétiques lancés.** `claude/phase5-votes`
  en avance rapide (`fd9478b`, la branche avait fusionné `main` jusqu'au bilan de 15 h) : paquet `vote/`,
  `session/PendingVotes`, `vote.json`, `todayVotes`, 53 fichiers, +3800, environ 100 tests neufs dont 16 SQL en CI.
  Relu : la branche contenait `main`, le contrat à 33 opérations (`ContractTest`), le filet 501 toujours sur le marché,
  les lignes de test retirées sont des adaptations (compte du contrat, constructeur de `Definitions`, test du compose
  qui exige maintenant un seul port publié), le jeton des tests est un faux évident, aucun secret, quatorze fichiers
  `@minecraft`, `deploy/exemple.env.txt` gagne les deux variables en commentaire. `docs/README.md` et
  `docs/game-design.md` passent à 33 opérations ; dix fichiers de définitions. Décision 42 ; trois écarts de contrat
  notés (Votifier hors OpenAPI, `payload` d'`ANNONCE` libre, pas de « jours avant bonus ») ; une ligne pour Kr (le
  pare-feu). Discord (`claude/phase5-discord`, 7c424ea, V8) est rendu mais ne contient pas les votes : huit fichiers
  communs, la session refusionne `main` avant la relecture. Lancée : les cosmétiques, l'effet de prestige, le boost
  collectif et le titre du pionnier (`claude/phase5-cosmetiques`, points 6, 7 et 9 du site). Réponses au point 9 de
  la direction du site : (a) la porte au `join` est la phase 6, voir le bilan de 15 h ; (b) le titre et l'objet du
  pionnier d'après `site_registrations.pioneer`, par la tranche des cosmétiques ; (c) le rapprochement des pseudos
  des livraisons est insensible à la casse par la collation, un test SQL le fixe dans la même tranche, pas de préfixe
  Floodgate sur un BDS natif ; (d) `site_registrations` ajoutée à `docs/base-de-donnees.md` par la même tranche. Le
  site est « Prêt à mettre en ligne » (`6fc7887`) : rien n'est déployé tant que la part de Kr n'est pas faite (ligne
  ajoutée). Vérifier : `git log --oneline -3 origin/main`, `cd core && ./gradlew test --tests '*Vote*'`.
- **5 novembre 2026, 15 h 43 : direction, Discord réduit et fusionné ; réponses à la direction du Discord.** Kr a
  ouvert à 15 h 31 (`003e52f`) une direction du Discord (`docs/discord.md`, `docs/discord-passation.md`) qui construit
  le bot Oooscar dans `core/.../discord/` et réserve l'ADR 0007 et la migration V8. La tranche `claude/phase5-discord`
  (webhook, suiveur, migration V8, même paquet) a donc été réduite avant sa fusion à ce que le service publie
  (décision 43), puis fusionnée en avance rapide (`75cc91e`) : le prestige publie `PRESTIGE_ENTREPRISE` pour tout le serveur,
  l'alerte `sansCourant`, les payloads dans l'OpenAPI, 1517 tests Gradle, 7 neufs dans `CompanyRoutesTest`. Relu : plus de paquet `discord/`, plus de
  migration, plus de variable `SILOOO_DISCORD_*`, aucun fichier interdit, aucun test affaibli. Les deux notes périmées
  du contrat (`PRESTIGE_ENTREPRISE` inutilisé, pas d'alerte du courant) corrigées ; la ligne de Kr sur le webhook
  remplacée par le déploiement du bot. `design/README.md` dit l'exception des noms de salons (point 7). En cours :
  les cosmétiques (`claude/phase5-cosmetiques`, migration V9). Réponses à la direction du Discord (« Pour la
  direction du jeu ») : (1) fichiers partagés : plus aucune tranche du jeu ne crée `discord/` ni ne touche
  `libs.versions.toml` ou `core/build.gradle.kts` ; `claude/phase5-cosmetiques` ajoute des lignes dans
  `Application.kt`, `Main.kt` (le `join`), `core/README.md`, `docs/base-de-donnees.md` et le paquet `shop/` ; le pass
  (`claude/phase5-pass`, après) touchera `shop/ShopService`, `Application.kt` et `core/README.md` ; la direction
  elle-même va éditer `deploy/exemple.env.txt`, `docs/deploiement.md` et `tools/tests/deploy-compose.mjs` pour les
  variables du site. Attention : ce test exige que toute variable lue par `Config.kt` ait sa ligne, même en
  commentaire, dans `deploy/exemple.env.txt` et son entrée dans `docs/deploiement.md` ; ajoutez-y vos trois variables
  dans votre tranche, en lignes, jamais en bloc. (2) ADR 0007 et V8 : notés, à vous ; le jeu prend V9 (cosmétiques)
  et la suite, et son prochain ADR serait le 0008. (3) D3 : d'accord, la route d'abord par `silooo-route` (le contrat
  compte 33 opérations dans `ContractTest`, à mettre à jour avec la route) ; l'écran Menu, Mon compte, « Relier
  Discord » est au jeu, à la tranche add-on qui suit la fusion de la route sur `main`. (4) D4 : contrat d'abord ;
  préférez un `kind` déclaré dans l'énumération de `GameEvent` (par exemple `TCHAT_DISCORD`) à un préfixe dans le
  texte, l'add-on affichera `[Discord]` lui-même ; le réglage « Voir le tchat Discord » se range dans le service (un
  champ du joueur au `join`, ou une route de réglage que vous proposez), l'écran et le signalement sont au jeu. (5) D5 :
  titres et décor passent par `player_cosmetics` avec `source` = `discord` (VARCHAR(16)) et des `id` déclarés dans
  `definitions/boutique.json`, liste `cosmetiques` (non achetables), que la tranche des cosmétiques met en place ;
  les durées du parrainage et les Soutiens dans un `definitions/discord.json` à vous, avec schéma et types générés
  (`node tools/generer_types.mjs`), chiffres à valider par Kr ; rien d'annoncé avant l'affichage en jeu, d'accord :
  l'add-on montre ce que `join` rend (champ des cosmétiques possédés, même tranche). (6) Le modèle 3D d'Oooscar :
  noté ; le pack de ressources naît avec l'add-on, pas avant la beta ; le modèle y entrera à ce moment. (7) Fait :
  `design/README.md` dit l'exception, limitée aux noms de salons et de catégories. Vérifier : `git log --oneline -3
  origin/main`, `cd core && ./gradlew test --tests '*Prestige*'`, `git grep -c SILOOO_DISCORD core/src/main`.
- **5 novembre 2026, 15 h 48 : direction, les variables du site (point 3 de la direction du site) ; pass lancé.** Les
  treize variables du module `site/` sont dans `deploy/exemple.env.txt`, en commentaire, avec leur défaut sûr
  (`attente`, 8081), et dans le tableau de `docs/deploiement.md` ; `SILOOO_SITE_MODE` y porte l'avertissement : seul
  Kr passe à `ouvert`, sur le VPS, jamais une session ni un commit. L'exemption de `tools/tests/deploy-compose.mjs`
  est retirée : le test exige maintenant chaque variable lue par `Config.kt` dans l'exemple. Lancé : le pass de
  saison (`claude/phase5-pass` : `pass.json`, `getPass`, `claimPass`, `pass-premium` par `buyItem`, aucune
  migration). Deux sessions en cours : cosmétiques et pass. Vérifier : `node tools/tests/deploy-compose.mjs`,
  `git log --oneline -3 origin/main`.
- **5 novembre 2026, 16 h 45 : direction, la CI de `main` est rouge ; deux tests SQL corrigés, un signalé au site.** Le
  premier run complet depuis 9a4fb3b (69d98b1, puis ddf698f et 870b196) échoue sur trois tests Gradle que le PC ne
  joue pas sans base : `CompanySqlTest` « la course suivante est écrite en base » et « des prestiges simultanés »
  comptaient les événements sans le `PRESTIGE_ENTREPRISE` que la tranche Discord publie pour tout le serveur (2 et 1
  attendus, 3 et 2 trouvés) : corrigés ici, en vérifiant aussi le total du nouvel événement et son `xuid` nul ;
  `PioneerRoutesTest` « Jeton absent, falsifié ou périmé » (paquet `site/`) rend 303 au lieu de 400 après onze
  minutes : à la direction du site, ligne ajoutée pour Kr. La fusion de `origin/main` butait sur la copie non suivie
  de `docs/references/site/` (identique à ddf698f) : mise dans un stash, ligne de Kr complétée. Lu : la direction du
  site (deuxième passage, en local) a suspendu la mise en ligne à 15 h 48 (révision de S0 à S5 contre le kit de Kr,
  `docs/site-revision.md`) : ligne de Kr réécrite, rien ne lui est demandé pour le site ; son point 1 est réglé par
  ddf698f. La direction du Discord a poussé D0 et D2 sur `main` (b94a408, 870b196, 8919727 : paquet `discord/`, ADR
  0007, JDA, quatre variables du bot dans `deploy/exemple.env.txt` avec `SILOOO_DISCORD_ACTIF=non`) ; réponses : (1)
  aucune tranche du jeu ne touche `libs.versions.toml`, `core/build.gradle.kts`, `tools/anti_ia.py` ni `discord/` ;
  cosmétiques ajoute des lignes dans `Main.kt` (le `join`) et le pass dans `Application.kt` (`Dependencies.pass`) :
  gardez les deux côtés ; (2) noté ; (8) noté, ligne de Kr réécrite avec `a-blanc` puis `oui`. Vérifier :
  `gh run list --branch main --limit 3`, `git stash list`.
- **5 novembre 2026, 16 h 51 : direction, les cosmétiques fusionnés ; le pass refusionne.** `claude/phase5-cosmetiques`
  par un commit de fusion (`1eae5a0`, la branche contenait `main` jusqu'à 8919727) : 68 fichiers, +4669, migration V9
  `server_boosts`, 67 tests neufs dont 23 SQL en CI. Relu : aucun fichier interdit, les lignes de test retirées sont
  des adaptations (le catalogue compte les cosmétiques, le rapprochement des pseudos passe par la clé du site, les
  articles inconnus gardent leur test), quatorze fichiers `@minecraft`, aucun secret. Décision 44 ; les noms et les
  prix recopiés dans « À valider par Kr » ; quatre écarts notés (pas de route du boost en cours, `ceoHoldings`,
  `pioneer` vrai par défaut et jamais écrit par le site, verrou du boost vérifiable en CI seulement). Flyway : V8
  (direction du Discord) doit exister avant le premier déploiement qui applique V9, sinon Flyway la refuse hors
  d'ordre ; noté dans la réponse à la direction du Discord. Le pass (`claude/phase5-pass`, 6d8c3ca, vérificateur vert,
  quatre codes de refus ajoutés à l'add-on) refusionne `main` et déclare ses cinq cosmétiques de saison dans
  `boutique.json` (hors vente) avant sa relecture. Vérifier : `git log --oneline -3 origin/main`,
  `cd core && ./gradlew test --tests '*Shop*'`.
- **5 novembre 2026, 17 h 17 : direction, le pass fusionné, le D2 du Discord fusionné ; add-on lancé.**
  `claude/phase5-pass` (99804a1, la branche contenait `main`) en avance rapide : paquet `pass/`, `pass.json`,
  `getPass`, `claimPass`, `pass-premium`, quatre codes de refus (déclarés dans `api/`, bulles dans l'add-on), les
  cinq cosmétiques de saison déclarés hors vente dans `boutique.json` et refusés à l'achat comme tout
  `achetable: false` ; 1723 tests Gradle sur la branche (0 échec), dont 71 du pass : `PassRulesTest` 14, `PassRoutesTest` 27, `PassPointsTest` 9, `PassSqlTest` 21 en CI. Relu : aucun fichier interdit (dans `addon/src`, les
  deux fichiers autorisés), aucun test affaibli (les lignes retirées sont le constructeur `Definitions` et les
  dépendances du contrat élargis au pass), 33 opérations, aucune migration, quatorze fichiers `@minecraft`, aucun
  secret. La poussée a buté sur `origin/main`, avancé par la direction du Discord (2323dd9, 82689a6 : D2,
  `definitions/discord.json`, migration V10 ; V8 reste un trou) : fusionné, un conflit d'une ligne dans
  `core/README.md` (les deux lignes gardées), vérificateur vert. Décision 45 ; `pass.json` et les bulles recopiés
  dans « À valider par Kr » ; onze fichiers de définitions du jeu (82844d0). Réparé : la phrase des fichiers de
  définitions de l'état portait un `\1` depuis 990dcb5 (un script de 15 h 35), et disait 32 opérations. Les cases
  « definitions » et « docs » de la phase 5 cochées. Lancée : l'add-on de la boutique, du pass et des votes
  (`claude/phase5-addon`, recette `silooo-ecran`, partie de 99804a1). À la poussée, `origin/main` avait encore avancé,
  par la direction du site cette fois (6b891e3, 06de293, fdbec93 : la révision contre le kit, son socle, Playwright) :
  fusionné sans conflit, `npm ci` à la racine, vérificateur vert avec l'étape Chromium ignorée sur ce poste.
  Vérifier : `git log --oneline -4 origin/main`,
  `cd core && ./gradlew test --tests '*Pass*'`.
- **5 novembre 2026, 17 h 17 : bilan (direction).**
  - Fait depuis le bilan de 15 h : les votes (33 opérations, décision 42) ; Discord réduit à ce que le service publie
    et fusionné (décision 43 : le bot Oooscar est à la direction du Discord) ; les treize variables du site dans
    `deploy/exemple.env.txt` et `docs/deploiement.md` (point 3 du site) ; les cosmétiques, le boost collectif et le
    titre du pionnier (V9, décision 44) ; le pass de saison (décision 45) ; la CI de `main`, rouge depuis 69d98b1
    (deux tests SQL du prestige, corrigés ; un test du site, intermittent), verte à 234793f (17 h 02, cinq jobs).
    Le service de la phase 5 est complet : ses cinq cases, les définitions et les docs sont cochées.
  - En cours : l'add-on de la boutique, du pass et des votes (`claude/phase5-addon`), seule session lancée.
  - Reste : phase 2, les trois cases de Kr ; phase 3, le test de bout en bout ; phase 4, le test en jeu ; phase 5,
    l'add-on, puis la condition de sortie qui demande Kr (achat réel de test, Hubyy et le port Votifier, le jeton du
    bot Discord). Les phases 6 et 7 ne s'ouvrent pas.
  - Décisions prises seul depuis 15 h : 42, 43, 44, 45. À valider : `vote.json`, les cosmétiques et leurs prix, les
    noms d'abonnements et de raretés, `pass.json`, les bulles des refus du pass, l'agence du game design ; puis les
    textes de l'add-on.
  - Pour Kr, nouveau depuis 15 h : le pare-feu du port Votifier ; la mise en ligne du site suspendue par sa deuxième
    direction (rien à lui demander pour le site) ; le déploiement du bot Discord (`a-blanc` puis `oui`) quand le QG
    sera prêt et le jeton posé ; `PioneerRoutesTest` intermittent ; les deux stashs à jeter ; le sens du code de
    liaison Discord à trancher (phase 6) ; Chromium de Playwright sur le PC.
  - Direction du site (« Pour la direction du jeu », relu sur `claude/site-socle`, 7e6cb6e, pas encore sur `main`) :
    la suspension du 15 h 48 est lue, rien ne sera demandé à Kr pour le site, le jeu se déploiera avec le site en
    `attente` ; la copie non suivie de `docs/references/site/` est dans un stash, identique à `main`, les fusions
    passent. (1) noté : chaque variable qui change passe par `deploy/exemple.env.txt` et `docs/deploiement.md`, le
    test `deploy-compose.mjs` l'exige. (2) `POST /v1/links/discord` et le refus `PAS_ADMIS` au `join` en mode `beta`
    sont la porte de la phase 6, que cette direction n'ouvre pas : notés dans « À faire par Kr » ; le sens du code se
    contredit entre votre kit (donné par Discord, tapé en jeu) et la direction du Discord (montré en jeu, tapé sur
    Discord, D3) : une seule route au contrat, après l'accord écrit des deux directions ou le choix de Kr ; attendu
    aussi : le dessin de `beta_access`. (3) le titre du pionnier est crédité au premier `join` d'un inscrit
    (`PioneerTitle`, `player_cosmetics` `source` = `site`, une fois) ; le drapeau V7 est lu mais vrai par défaut,
    donc tout inscrit l'a, comme le kit le veut ; le mot `evenement` et l'abandon du drapeau viendront avec la
    migration que vous dessinez (une ligne dans `PioneerTitle`) ; l'objet de déco attend Kr. (4) d'accord, à vous,
    sur `claude/site-guide` depuis `main` après 99804a1 (`boutique.json` et `pass.json` y ont changé) :
    `definitions/` et ses schémas seulement, champs facultatifs tant que tout n'est pas écrit, types régénérés par
    `node tools/generer_types.mjs`, textes à valider par Kr ; aucune tranche du jeu n'y travaille (l'add-on en
    cours ne touche pas `definitions/`). (5) (a) fait ; (b) à vous (`shop_deliveries.xuid`), le rapprochement par
    la clé du site reste ; (c) fait (`personnel.json`, `boutique.json`) ; (d) fait (`docs/base-de-donnees.md`).
    (6) nos tranches ajoutent des lignes, jamais un bloc (le pass : `Application.kt`, `Main.kt`, `core/README.md`) ;
    l'add-on en cours ne touche pas `core/`. (7) `docs/game-design.md` mis d'accord avec la décision 33 (rien à
    acheter pour changer la liste), à valider par Kr. (8) lu : après votre fusion, `npm ci` à la racine et l'étape
    Chromium ignorée sur ce poste tant que Kr n'a pas installé Chromium (ligne de Kr) ; la CI la joue.
  - Direction du Discord (« Pour la direction du jeu », relu sur `claude/discord-d2`) : D0 et D2 sont lus et D2 est
    fusionné ici. (1) le pass a ajouté des lignes dans `Application.kt`, `Main.kt` et `core/README.md` (une ligne en
    conflit avec D2, les deux gardées) ; l'add-on en cours ne touche pas `core/`. (2) V10 lue : V8 reste un trou,
    rien à faire pour Flyway, le jeu prend V11 et la suite ; la note V8 du journal de 16 h 5x est caduque. (3) à (7) :
    réponses de 15 h 46, inchangées ; (7) est fait dans `design/README.md`. (8) noté, ligne de Kr réécrite.
  - Vérifier : `git log --oneline -12 origin/main`, `gh run list --branch main --limit 3`.
- **5 novembre 2026, 17 h 52 : direction, la CI de `main` rouge à 86b9c8c (trois tests SQL du pass), corrigée.** Le
  vérificateur de la CI de 86b9c8c (la fusion du socle du site) tombait sur trois tests de `PassSqlTest` : deux
  réclamations du même palier et une réclamation pendant des points, jouées en même temps, se suivaient par un
  conflit à rejouer (`ER_CHECKREAD`, « Record has changed since last read in table 'player_balances' ») au lieu de
  se suivre par le verrou des soldes. Cause : MariaDB ouvre la vue cohérente de la transaction à sa première lecture
  simple ; `isKnownPlayer` (`SELECT 1 FROM players`) était cette lecture, et le `FOR UPDATE` des soldes pris juste
  après sur une ligne changée entre-temps échouait (`innodb_snapshot_isolation`, voir `db/TransientConflict.kt`).
  Le vérificateur du PC ne le voyait pas : les tests SQL ne tournent qu'en CI. Correction, sur
  `claude/phase5-pass-verrous` puis `main` (e3c88c7) : la lecture du joueur est verrouillée en partage
  (`LOCK IN SHARE MODE`) dans `JdbcPassStore` et `JdbcShopStore`, comme `JdbcSessionStore` commence par un
  `FOR UPDATE` ; aucun test touché, le pass et la boutique verts en local, la CI de la branche verte sur les tests
  SQL. Reste vrai : un cadeau et un achat du destinataire au même instant peuvent encore rendre
  `CONFLIT_REESSAYER`, que l'add-on rejoue. À la poussée, `origin/main` avait avancé par la direction du Discord
  (5742efb : l'accueil guidé posé par le bot, le plan de l'attente, le Guide, `docs/discord-guide.md`) : fusionné
  sans conflit (e738f9a), vérificateur rejoué avant la poussée. Vérifier : `gh run list --branch main --limit 2`,
  `git show e3c88c7 --stat`.
- **5 novembre 2026, 19 h 10 : direction, l'add-on de la boutique, du pass et des votes fusionné (74a7ca1).** La tranche a
  rendu `claude/phase5-addon` (deux commits sur 856b57c, 34 fichiers, tous dans `addon/`) : le client (`catalog`,
  `buyItem` avec `forXuid`, `getPass`, `claimPass`, `todayVotes`), la tuile Boutique (`ui/boutique.ts` : grades,
  cosmétiques par catégorie, Pilier, boost collectif, pass avec ses paliers et « Passer premium », cadeau), le pass
  (`ui/pass.ts`), les rappels de vote (`ui/votes.ts`), les annonces (`ui/annonces.ts` : Gemmes reçues, boost collectif,
  vote party, sans courant, pionnier), les noms de rareté lus dans `personnel.json`, `addon/README.md` ; 1678 tests de
  l'add-on (1421 avant), dont 47 de parcours. Revue : `main` contenue, les quatorze fichiers `@minecraft/` inchangés,
  onze lignes de test retirées (deux imports inutiles et l'ancien emplacement de la tuile Associés, remplacé par un test
  de son nouvel emplacement), aucun secret. Avance rapide sur `main`, vérificateur vert à 19 h 07, l'étape Chromium
  comprise. Décision 46 ; textes recopiés dans « À valider par Kr ». La tranche signale ce qu'elle n'a pas pu faire :
  pas de route des soldes seuls (le HUD garde ses Lingots d'avant un vote jusqu'à la prochaine collecte), les charges
  des événements décrites en texte seulement dans le contrat, aucun choix de titre, l'objet Pionnier absent, pas d'URL
  de la boutique du site, et le curseur des événements qui repart de 0 au redémarrage du serveur : le service rend
  alors toute la table par pages de 100 et les annonces se rejouent (connu depuis la phase 3 pour les missions et les
  invitations, qui s'en accommodent ; pas les annonces de la phase 5). Lancée : une tranche courte qui rend ce curseur
  persistant (propriété dynamique du monde, `claude/phase5-curseur`). Vérifier : `git show 74a7ca1 --stat | tail -3`,
  `cd addon && npm test`.
- **5 novembre 2026, 19 h 10 : bilan (direction).**
  - Fait depuis le bilan de 17 h 17 : la CI de `main`, rouge à 86b9c8c (trois tests SQL du pass, isolation par
    instantané de MariaDB), corrigée à e3c88c7 (lecture du joueur verrouillée en partage) ; verte à 856b57c sur ses cinq
    jobs (18 h 41), après trois runs annulés par les poussées des deux autres directions (daaab0f, e02fe8a, c544c36 : le
    vérificateur y était vert chaque fois, seule l'image a été annulée). L'add-on de la phase 5 fusionné (74a7ca1) :
    les cases de la phase 5 sont toutes cochées, sauf la nouvelle, le curseur.
  - En cours : le curseur des événements persistant (`claude/phase5-curseur`), seule session lancée.
  - Reste : la case du curseur ; phase 2, les trois cases de Kr ; phase 3, le test de bout en bout ; phase 4, le test en
    jeu ; phase 5, la condition de sortie qui demande Kr (achat réel de test, Hubyy et le port Votifier, le jeton du bot
    Discord). Les phases 6 et 7 ne s'ouvrent pas. Rien n'est déployé.
  - Décisions prises seul depuis 17 h 17 : 46. À valider : les textes de l'add-on (boutique, pass, votes, annonces).
  - Pour Kr, nouveau : les deux variables des liens du pied de page du site ; le choix du titre et l'objet du pionnier ;
    la route des soldes seuls, si elle vaut la peine ; la ligne de l'agence réécrite (corrigée, à valider) ; la ligne
    de Chromium cochée.
  - Direction du site (« Pour la direction du jeu », point 9, lu sur `main` à c544c36) : les deux variables sont notées
    pour Kr (facultatives, rien à poser avant qu'il veuille les liens) ; `Config.kt`, `core/build.gradle.kts` et
    `core/Dockerfile.dockerignore` touchés, lu, aucune tranche du jeu n'y travaille (la tranche en cours ne touche que
    `addon/`) ; la version de Bedrock dans le signal de vie et la lecture du dernier prestige pour le bandeau : rien
    d'urgent, notées pour une tranche après la phase 5, pas avant que Kr ait vu l'add-on en jeu.
  - Direction du Discord (« Pour la direction du jeu », relu sur `main` à 856b57c) : inchangée depuis 17 h 17 ; ses deux
    tranches de D2 (accueil guidé, bonjour d'Oooscar, coulisses) sont sur `main`, fusionnées par elle ; `Main.kt` touché
    par `BotFeatures`, lu ; rien à répondre de plus.
  - Vérifier : `gh run list --branch main --limit 3`, `git log --oneline -8 origin/main`.
- **5 novembre 2026, 19 h 34 : direction, le curseur des événements persistant fusionné (9f5376a).** La tranche a rendu
  `claude/phase5-curseur` (fee17e1, fusion d'`origin/main` à 8032a6a, puis 0cee7a5) : `CursorStore` (`load`, `save`)
  facultatif dans `createEventsPoller`, lu au premier besoin ; `createEventsCursorStore` sur la propriété dynamique
  `silooo:events-cursor` (`monde/bedrock.ts`), branché dans `main.ts` ; dix-huit tests (1696 au total), aucun retiré ;
  les commentaires qui disaient « repart de 0 » (`events.ts`, `invitations.ts`, `missions.ts`, `annonces.ts`,
  `votes.ts`) et `addon/README.md` à jour. Revue : le second commit ne touche qu'`addon/`, deux lignes de test retirées
  (un assistant réécrit), quatorze fichiers `@minecraft/`, aucun secret. Fusion sur `main` (9f5376a), vérificateur vert.
  Décision 47 ; ce qu'on y perd (l'invitation en attente au redémarrage, la table vidée) y est écrit, et la ligne de Kr
  pour la table vidée. Vérifier : `git show 0cee7a5 --stat | tail -3`, `cd addon && npm test`.
- **5 novembre 2026, 19 h 34 : bilan final de la session de direction.**
  - Fait depuis le bilan de 19 h : le curseur des événements persistant (9f5376a) ; la dernière case de la phase 5
    cochée. Les phases 0 à 5 sont cochées sur `main`, hors les cases de Kr. Rien n'est déployé.
  - En cours : aucune session lancée ; aucun worktree de la direction du jeu.
  - Reste à Kr : phase 2, trois cases (la base, le port, le monde) ; phase 3, le test de bout en bout ; phase 4, le test
    en jeu ; phase 5, la condition de sortie (achat réel de test, Hubyy et le port Votifier, le jeton du bot Discord) ;
    la liste « À faire par Kr ». Les phases 6 et 7 ne s'ouvrent pas.
  - Reste au jeu, sans case, pour une tranche après que Kr aura vu l'add-on : la route des soldes seuls, une route qui
    liste les invitations en attente (décision 47), les charges des événements typées dans le contrat, la version de
    Bedrock dans le signal de vie et le dernier prestige pour le bandeau du site (point 9 du site).
  - Décisions prises seul depuis 19 h : 47. À valider : rien de nouveau (aucun texte nouveau).
  - Directions du site et du Discord : rien de nouveau dans « Pour la direction du jeu » depuis le bilan de 19 h.
  - État du dépôt : `main` = `origin/main` = 3127ae5 (9f5376a, puis la fusion d'`origin/main` à 07e2eef, direction du site, vérificateur vert à 19 h 40), arbre propre hors `.agents/` et `.codex/` (ligne de Kr) ; les
    branches `claude/phase5-*` restent sur `origin`, fusionnées (ligne de Kr). CI : `gh run list --branch main --limit 3`.
  - Vérifier : `git log --oneline -6 origin/main`, `git status --short`.
- **5 novembre 2026, 19 h 51 : direction, CI de `main` verte à 7ffc571 (cinq jobs, 19 h 50) ; un zip de BDS non suivi.** Le
  bilan final est poussé et vert. Un fichier `bedrock-server-1.26.62.1.zip` non suivi est apparu à la racine du
  dépôt après le bilan : jamais à ajouter au dépôt (ligne de Kr). Fin de la session de direction : aucune session
  lancée, aucun worktree, `main` = `origin/main`. Vérifier : `gh run list --branch main --limit 2`.


### D3 — répartition du 6 novembre 2026

V14 réservée aux profils Discord synchronisés ; V15 aux préférences et envois de rappels ; V16 aux bureaux, sondages et tableaux. Trois agents économiques travaillent dans des worktrees isolés ; compilation et intégration centralisées. D1 reste reporté. Le BDS est déployé/testé en whitelist par Kr, seul le core est déployé par cette session. Les nouveaux textes et cartes D3 restent à valider par Kr.


### Livraison D3 Discord — 6 novembre 2026

Liaison prouvée en jeu, profils/pseudos, cartes privées et installation sur le compte, rappels opt-in, bureaux publics,
sondages à voix unique par XUID et tableaux sont intégrés. V13 à V16 sont réservées à ce lot. Le critère D3 est
recetté avec la vraie MariaDB et un réseau Discord simulé ; la recette client utilise l'add-on du même SHA installé
par Kr. Seul le core est déployé ici, le BDS reste géré par Kr en whitelist. Phase Discord attente et site attente.

Décisions prises sans Kr : paliers de prestige 1/5/10/25/50 et laiton dès 5, purement cosmétiques et configurables.
Tous les textes et cartes D3 restent **à valider par Kr**. D1 attend. Le rappel Silo plein reste indisponible faute
d'état/capacité dans le jeu ; le parrainage reste à D5 et le relais du tchat/signalements à D4. Les actions Discord
et déploiements du seul core restent dans l'autorisation explicite de Kr consignée dans la passation Discord.


### Gestion et modération Discord — 7 novembre 2026

V17 réservée aux dossiers/actions de modération, V18 aux signalements de messages. Trois agents économiques travaillent dans des worktrees isolés ; intégration et compilations centralisées. Cette tranche concerne le staff et le serveur Discord, sans nouveau relais du tchat ni déploiement BDS. Les nouveaux textes restent à valider par Kr.


### Reprise Discord — 8 novembre 2026

Branche `claude/discord-complet-20261108`, fondée sur main `61ce234` et intégrant la branche
`claude/discord-gestion-20261107` (`6ce48e0`). V17 est déjà occupée par la salle d'attente :
les migrations Discord non intégrées à main prennent V18 (modération) et V19 (signalements).
V20 est réservée à la prise en charge des tickets. Aucune migration existante sur main n'est modifiée.
Les nouveaux libellés et messages de tickets restent **à valider par Kr**.

Décisions prises sans Kr : priorité au support privé, puis aux outils de modération, aux journaux et aux
diagnostics ; le détail des dépendances et des exclusions est dans `docs/discord-plan-complet.md`.
La lecture des bots SileneMC dans `/var/www/minecraft/deploy/discord-*` est autorisée par Kr le 8 novembre
pour cette comparaison ; aucune modification des sources ou services SileneMC.

Mise à jour d'intégration : main `bfd9f07` est intégré à cette reprise Discord. L'admission en bêta
reste automatique pour tous (décision 21) ; le Discord n'ajoute pas de validation manuelle.


### Validation de la reprise Discord — 8 novembre 2026

`bash tools/verifier.sh` termine sans erreur sur la version intégrant main `bfd9f07` :
2 439 tests Gradle, sans échec, erreur ni test ignoré (MariaDB 12.3.3 de test comprise) ;
1 742 tests add-on et son build ; 170 contrôles d'outillage ; définitions, textes, plan Discord,
liens et pack de ressources valides. Les tests SQL des tickets et le parcours de gestion passent.
Le contrôle du site dans Chromium est ignoré, le navigateur n'étant pas installé.
La recette humaine Discord et le déploiement restent à faire ; les nouveaux textes restent à valider par Kr.

Publication du 8 novembre : le push HTTPS n'a pas d'identifiant GitHub configuré ; le connecteur GitHub refuse
la création de l'arbre avec HTTP 403 « Resource not accessible by integration ». Aucun push ni déploiement
n'a abouti. Les commits validés restent sur `claude/discord-complet-20261108` ; le plan, le patch et un bundle Git
sont exportés dans les livrables du chat. La recette humaine et les validations de textes restent ouvertes.
