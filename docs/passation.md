# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, 5 h 40, fin de la phase 1 et début de la phase 2 (session de direction).

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions et leurs schémas), le plan, le
  game design, le design system, la documentation (`docs/`, ADR 0001 à 0005), l'outillage (`tools/`,
  CI, hooks, trois recettes) et, dans `core/`, le squelette de silooo-core (Gradle, Ktor, Flyway, clé
  `X-Silooo-Key`, heartbeat, filet 501, Dockerfile) avec les données dorées du tableur
  (`core/src/test/resources/golden/`), les routes de session (`join`, `leave`, `GET /v1/events`,
  idempotence, verrou optimiste, journal des monnaies, tests de contrat sur les 29 opérations) et le moteur
  d'économie avec ses tests dorés. Le branchement du moteur sur `join` est en cours (sous-agent).
- `main` est vert : `tools/verifier.sh` passe sur le PC (tests Gradle compris, tests Docker ignorés) et
  la CI passe (jobs `verifier` avec les deux tests Testcontainers, `migration`, `image`).
- Sur le VPS : `/var/www/silooo` existe avec un fichier d'environnement posé par Kr. La base `silooo`
  n'est pas confirmée. Le VPS n'a pas d'accès GitHub.
- Sur le PC de Kr : Node 24, Java 17, uv (Python géré), Docker Desktop installé mais pas lancé. Pas de
  Gradle global (le wrapper suffit ; Gradle 9.8 est en cache).
- Phase en cours : **1**, à une case de la fin (le branchement du moteur). Condition de sortie : toutes les
  cases cochées, `tools/verifier.sh` vert sur `main` (tests Gradle compris), CI verte, image de silooo-core
  publiée depuis `main` (fait : run `image` vert sur `main`). La phase **2** est ouverte en parallèle pour ce
  qui ne dépend pas de la phase 1 : le relevé des faits Bedrock (sous-agent).

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
- [ ] Brancher le moteur sur `join` (`EconomyFigures` : revenu, multiplicateur, seuil, hors ligne ; `CompanyBootstrap` : la Carrière de départ et la grille des cases) : sous-agent en cours, branche `claude/phase1-branchement`
- [ ] Contrat : déclarer le 409 `CONFLIT_REESSAYER` sur les six opérations qui peuvent le rendre (`join`, `collect`, `resolveIncident`, `fireStaff`, `assignStaff`, `deliverOrder`), puis retirer `KNOWN_409_GAPS` du test de contrat
- [x] Image Docker de silooo-core construite par la CI, publiée sur le registre GitHub depuis `main`

## Phase 2 : Bedrock

Condition de sortie (plan) : un joueur sur console rejoint `play.silooo.com` et apparaît sur son île ;
mesure de la mémoire réelle. Les deux demandent la base, le port et le DNS : voir « À faire par Kr ».

- [ ] `docs/bedrock.md` : les faits « à relever » relevés pour la version épinglée (modules et versions, fichier de permissions, téléchargement de BDS, réseau, limites), avec leurs sources : sous-agent en cours, branche `claude/phase2-releve`
- [ ] `deploy/bds/` : image BDS épinglée (version et somme contrôlées), utilisateur non root, monde dans un volume, `config/default/permissions.json` qui autorise `server-net` et `server-admin`, gabarit de `server.properties` (port de l'ADR 0004)
- [ ] `deploy/compose.yml` : projet `silooo`, services `silooo-migrate`, `silooo-core` (512 Mo) et `silooo-bds` (1,5 Gio), réseau de SileneMC rejoint en externe, secrets lus dans le fichier d'environnement non versionné
- [ ] `deploy/scripts/deploy.sh <tag>` : sauvegarde, tirage des images, migration, redémarrage, test de fumée, retour arrière ; `docs/deploiement.md` mis à jour
- [ ] `addon/` : projet TypeScript strict, esbuild vers un seul `scripts/main.js`, manifest aux versions de modules relevées, tests de la logique pure, paquet `.mcpack` construit
- [ ] `addon/` : client `server-net` vers silooo-core (clé par `server-admin`, en-tête `X-Silooo-Key`, délai, codes `Erreur` traités d'un bloc), heartbeat périodique, `join` à l'arrivée, `leave` au départ, interrogation de `GET /v1/events` avec curseur
- [ ] `tools/` : générateur des types TypeScript de l'add-on tirés de `definitions/`, et contrôle « types à jour » dans `tools/verifier.sh`
- [ ] `tools/verifier.sh` et CI : `addon/` vérifié (compilation stricte, tests, paquet) ; image BDS construite par la CI et publiée depuis `main`
- [ ] Déploiement sur le VPS, test depuis une console : un joueur rejoint `play.silooo.com` et apparaît sur son île (**À faire par Kr** : base, port, DNS, puis le test)
- [ ] Mesure de la mémoire réelle des conteneurs sur le VPS (**après le déploiement**, donc après Kr)

## À faire par Kr

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans le fichier d'environnement de `/var/www/silooo`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir en UDP et TCP
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
    le déclarer : `ContractTest` gèle la liste (`KNOWN_409_GAPS`) et la direction corrige le contrat après le
    branchement du moteur (case de la phase 1), pour ne pas croiser les modifications.
19. **Quatrième fichier de définitions, `monde.json`.** L'écart entre les cases de l'archipel, le nombre de
    cases par rangée et l'origine de la grille sont des chiffres du jeu (`docs/game-design.md` : « au moins
    600 × 600 blocs par joueur ») : ils vont dans `definitions/`, pas dans le code. Au-delà des neuf seuils de
    prestige listés, le seuil suivant vaut le précédent × 1000 (prose `etoilesSuivantes`) : clé numérique
    `prestige.facteurSeuilSuivant`. Les deux à valider par Kr (en cours, sous-agent de branchement).
20. **Phase 2 ouverte avant la fin de la phase 1.** Le relevé des faits Bedrock ne dépend pas du
    branchement du moteur : il part en parallèle, dans la limite de trois sessions à la fois.

## À valider par Kr

- Les huit points d'interprétation du tableur et la règle d'arrondi des Lingots
  (`core/src/test/resources/golden/README.md`) : le moteur suit le tableur en attendant.
- Le nom d'entreprise provisoire « Entreprise de <gamertag> » (`core/.../session/CompanyNaming.kt`), premier
  texte vu par les joueurs. Aucun autre texte de joueur n'a été écrit.
- Les valeurs de `definitions/monde.json` (écart entre les cases, cases par rangée, origine) et
  `prestige.facteurSeuilSuivant` dans `definitions/economie.json`, une fois la branche de branchement fusionnée.

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
