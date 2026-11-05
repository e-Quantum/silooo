# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, 8 h 45, `deploy/` et les écrans de l'add-on fusionnés (session de direction).

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions et leurs schémas), le plan, le
  game design, le design system, la documentation (`docs/`, ADR 0001 à 0005), l'outillage (`tools/`,
  CI, hooks, trois recettes) et, dans `core/`, le squelette de silooo-core (Gradle, Ktor, Flyway, clé
  `X-Silooo-Key`, heartbeat, filet 501, Dockerfile) avec les données dorées du tableur
  (`core/src/test/resources/golden/`), les routes de session (`join`, `leave`, `GET /v1/events`,
  idempotence, verrou optimiste, journal des monnaies, tests de contrat sur les 29 opérations) et le moteur
  d'économie avec ses tests dorés, branché sur `join` (`EngineFigures`, `DefinitionsBootstrap`). Quatre
  fichiers de définitions : `usines`, `economie`, `boutique`, `monde`.
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
- Phase en cours : **3** (la première heure). Côté service, les quatre routes d'économie sont sur `main` ; côté
  add-on, le HUD, le menu, la fiche d'usine et le Silo aussi (339 tests). Restent les usines posées sur l'île, la
  cinématique d'arrivée, Oooscar en entité, la mission de la mairie et le pack de ressources (JSON UI), puis le test
  de bout en bout après Kr.

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
- [ ] add-on : les usines de l'époque 1 posées sur l'île du joueur (structures dans le monde, sur la grille de `definitions/monde.json`)
- [x] add-on : HUD en trois étages (`ui/hud.ts` ; le canal de titre est coupé par `config.ui.hud.titleChannel` tant que le pack de ressources ne masque pas les préfixes, tout passe par la barre d'action), menu à tuiles (boussole « Menu »), liste des usines paginée, fiche d'usine avec Monter de 1, de 10, au palier, au max (`buyLevels`), palier ×2 et coût affichés
- [x] add-on : le Silo (Encaisser → `collect`, heures hors ligne affichées) et la remise en état en quatre étapes (Parler, Réparer, Livrer, Recruter → `restoreStep`) ; rejeu une fois après `CONFLIT_REESSAYER` ; une bulle d'Oooscar par code `reason`
- [ ] add-on : cinématique d'arrivée, Oooscar en entité, première mission de la mairie (textes et visuels **à valider par Kr**)
- [ ] `resource-pack/` : JSON UI qui masque les préfixes `silooo:hud|` et `silooo:consigne|` du canal de titre et place les monnaies et la consigne en haut à gauche, glyphes des icônes de `design/icones/`, images d'Oooscar ; `start.sh` l'installe à côté du pack de comportement ; puis `titleChannel: true`
- [ ] Test de bout en bout de la première heure sur le BDS de test (**après le déploiement**, donc après Kr)

## À faire par Kr

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans le fichier d'environnement de `/var/www/silooo`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir : 19142 en TCP (signalisation NetherNet) plus une plage UDP contiguë pour le jeu, un port par joueur (le compose de `deploy/` propose 19143 à 19174 pour 32 joueurs ; `docs/adr/0004-port-bedrock.md`)
- [ ] Télécharger `bedrock-server-1.26.62.1.zip` dans un navigateur (minecraft.net refuse `curl`, même avec un User-Agent honnête, depuis le PC comme depuis les runners GitHub), calculer `sha256sum`, écrire la somme dans `deploy/bds/bds.sha256`, créer `/var/www/silooo/bds/` sur le VPS (`mkdir -p`, puis `chown 10001:10001` : l'uid du conteneur), y poser le zip (décision 26, `docs/deploiement.md`, « Le zip de BDS »)
- [ ] `docker login ghcr.io` sur le VPS (les images `silooo-core` et `silooo-bds` sont publiées par la CI depuis `main`), et confirmer le nom du réseau Docker de SileneMC (`SILENEMC_NETWORK`, `silenemc_default` par hypothèse)
- [ ] Fournir un monde Bedrock créé dans le client avec l'option « Beta APIs » activée, ou valider la méthode que la phase 2 trouvera pour l'activer sur BDS : `server-net` et `server-admin` l'exigent (`docs/bedrock.md`, fait 1)
- [ ] Trancher l'identité des joueurs : la Script API ne donne pas le XUID ; l'add-on envoie une empreinte du gamertag (décision 25). Valider, ou choisir une source de XUID (journal de BDS, `allowlist.json`), ce qui demandera de changer la clé des joueurs
- [ ] Trous de contrat relevés par les routes d'économie : pas de 403 ni de 404 sur `/v1/companies/...` (une entreprise inconnue, un acteur qui n'est pas membre ou une usine inconnue rendent 400 `REQUETE_INVALIDE`) ; pas de type d'événement dédié (`collect`, `buyLevels`, `restoreStep` publient `ANNONCE` avec `payload.action`) ; pas d'opération pour racheter une usine en ruine
- [ ] Trous de `definitions/` : ni coût ni niveau de départ pour la remise en état d'une usine (le service prend les étapes gratuites et le niveau `prestige.departDeCourse.niveau`, `docs/economie.md`)
- [ ] DNS `play.silooo.com` vers l'IP du VPS
- [ ] Protéger la branche `main` sur GitHub (CI verte obligatoire, avance rapide, pas de force-push)
- [ ] Plus tard : secrets GitHub pour le déploiement par la CI, et une clé de lecture du registre `ghcr.io` pour le VPS
- [ ] Relire la section « Décisions prises sans Kr » et dire ce qui ne va pas
- [ ] Relire l'ADR 0004 (port 19142, statut « Proposée ») et l'ADR 0003 (réseau Docker de SileneMC rejoint en externe)
- [ ] Trancher les deux points ouverts de `docs/vision.md` : les lootbox à clés (le game design les cite, la règle « aucun hasard payant » les interdit) et le marché d'annonces entre joueurs (`createListing`, `buyListing`), que le plan recommande de remplacer par une vente au Silo à prix fixé. Rien ne s'écrit sur ces deux sujets avant ta réponse.
- [ ] Trancher les huit points d'interprétation du tableur et la règle d'arrondi des Lingots : `core/src/test/resources/golden/README.md`, section « Points d'interprétation ». En attendant, le moteur suit le tableur.
- [ ] Dans l'application Claude, ouvrir les deux sessions « Phase 0 : outillage » et « Phase 0 : recettes » restées en attente de « Trust repository » et les fermer (leur travail a été fait par la direction) ; au prochain lancement d'une session PC en worktree, cliquer « Trust repository » une fois
- [ ] Trancher trois trous du contrat relevés par les tests : aucune route pour nommer l'entreprise (le game design le demande à l'étape 1 de l'onboarding ; en attendant, nom provisoire « Entreprise de <gamertag> ») ; aucune route ne fait avancer `tutorial_step` (à prévoir en phase 3) ; `PAS_ASSEZ_DE_JETONS` et `PAS_ASSEZ_DE_CLES` manquent dans `Refused` (les clés dépendent de ta décision sur les lootbox)

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

## À valider par Kr

- Les huit points d'interprétation du tableur et la règle d'arrondi des Lingots
  (`core/src/test/resources/golden/README.md`) : le moteur suit le tableur en attendant.
- Le nom d'entreprise provisoire « Entreprise de <gamertag> » (`core/.../session/CompanyNaming.kt`), premier
  texte vu par les joueurs.
- Le nom du pack « Silooo » et sa description « Pack de comportement du jeu Silooo. » (`addon/manifest.json`),
  visibles dans la liste des packs du monde.
- Le nom du serveur « Silooo » dans la liste des amis (`BDS_SERVER_NAME`, `deploy/exemple.env.txt`).
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
