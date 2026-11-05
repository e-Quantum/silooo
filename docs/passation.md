# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, 4 h 50, phase 1 en cours (session de direction).

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions et leurs schémas), le plan, le
  game design, le design system, la documentation (`docs/`, ADR 0001 à 0005), l'outillage (`tools/`,
  CI, hooks, trois recettes) et, dans `core/`, le squelette de silooo-core (Gradle, Ktor, Flyway, clé
  `X-Silooo-Key`, heartbeat, filet 501, Dockerfile) avec les données dorées du tableur
  (`core/src/test/resources/golden/`). Les routes de session et le moteur d'économie sont en cours.
- `main` est vert : `tools/verifier.sh` passe sur le PC (tests Gradle compris, tests Docker ignorés) et
  la CI passe (jobs `verifier` avec les deux tests Testcontainers, `migration`, `image`).
- Sur le VPS : `/var/www/silooo` existe avec un fichier d'environnement posé par Kr. La base `silooo`
  n'est pas confirmée. Le VPS n'a pas d'accès GitHub.
- Sur le PC de Kr : Node 24, Java 17, uv (Python géré), Docker Desktop installé mais pas lancé. Pas de
  Gradle global (le wrapper suffit ; Gradle 9.8 est en cache).
- Phase en cours : **1**. Condition de sortie : toutes les cases cochées, `tools/verifier.sh` vert sur
  `main` (tests Gradle compris), CI verte, image de silooo-core publiée depuis `main`.

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
- [ ] `session/join` et `session/leave`
- [ ] `GET /v1/events` avec curseur
- [ ] Idempotence par `requestId` (table `api_requests`)
- [ ] Verrou optimiste sur `companies.version`
- [ ] Journal des monnaies dans la même transaction que le solde
- [x] Données dorées extraites du tableur (`core/src/test/resources/golden/`) et script d'extraction rejouable
- [ ] Moteur d'économie (prix des niveaux, production, paliers, prestige, notation) et tests dorés
- [ ] Tests de contrat sur les 29 opérations de l'OpenAPI (les routes pas faites répondent 501)
- [ ] Brancher le moteur sur `join` (`EconomyFigures` : revenu, multiplicateur, seuil, hors ligne ; `CompanyBootstrap` : la Carrière de départ) une fois routes et moteur fusionnés
- [ ] Image Docker de silooo-core construite par la CI, publiée sur le registre GitHub depuis `main`

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

## À valider par Kr

- Les huit points d'interprétation du tableur et la règle d'arrondi des Lingots
  (`core/src/test/resources/golden/README.md`) : le moteur suit le tableur en attendant.
- Aucun texte vu par les joueurs n'a encore été écrit.

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
