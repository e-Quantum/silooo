# silooo-core

Le service Kotlin de Silooo : il décide de tout, l'add-on affiche ([ADR 0002](../docs/adr/0002-service-autoritaire.md)).
Ktor (CIO), HikariCP, Flyway, MariaDB. Le contrat avec l'add-on est
[l'OpenAPI](../api/silooo-core.openapi.yaml) ; les tables sont dans
[base-de-donnees.md](../docs/base-de-donnees.md).

## Lancer

Il faut un JDK 17. Gradle vient du wrapper (`./gradlew`), qui le télécharge au premier appel.

```bash
cd core
export SILOOO_DB_URL="jdbc:mariadb://localhost:3306/silooo"
export SILOOO_DB_USER=...  SILOOO_DB_PASSWORD=...  SILOOO_API_KEY=...
./gradlew run --args=migrate   # applique les migrations Flyway et sort
./gradlew run                  # démarre le service sur le port 8080
```

Les valeurs viennent de ta session, jamais d'un fichier du dépôt.

## Tester

```bash
cd core && ./gradlew test --console=plain    # les tests de silooo-core
bash tools/verifier.sh                       # depuis la racine : tout le dépôt, comme la CI
```

- Les tests de logique (API, configuration, `join`, idempotence, contrat) tournent **sans base ni Docker** :
  les faux en mémoire (`PassThroughDatabase`, `InMemorySessionStore`, `InMemoryApiRequestStore`…) remplacent la base, et
  `RecordingConnection` note les écritures JDBC d'une fonction (le SQL exact d'un `UPDATE`) sans la jouer.
- Les tests SQL (`*SqlTest` et `MigrationTest`) lancent un conteneur `mariadb:12.3.3` avec Testcontainers
  (un seul par exécution, vidé avant chaque test : `db/MariaDbSupport`). **Sans Docker ils sont ignorés**
  (« SKIPPED », avec le message « Docker absent »), jamais réussis à vide. Ils tournent dans la CI GitHub : lis le
  journal de l'étape « Vérifier le dépôt » et cherche `SKIPPED` avant de conclure que tout est vert.
- `contract/ContractTest` lit `api/silooo-core.openapi.yaml` (SnakeYAML) et vérifie les 32 opérations. Quand une
  session écrit une route, elle l'ajoute à `WRITTEN` et branche ses faux dans `contractDependencies()`.
- Rapport complet : `core/build/reports/tests/test/index.html`.

## Variables d'environnement

| Variable | Rôle | Obligatoire |
| --- | --- | --- |
| `SILOOO_PORT` | Port d'écoute | non, 8080 par défaut |
| `SILOOO_DB_URL` | URL JDBC de la base `silooo` (`jdbc:mariadb://hôte:3306/silooo`) | oui |
| `SILOOO_DB_USER` | Compte de la base | oui |
| `SILOOO_DB_PASSWORD` | Mot de passe de la base | oui |
| `SILOOO_API_KEY` | La clé attendue dans l'en-tête `X-Silooo-Key` | oui, sauf en mode `migrate` |
| `SILOOO_DEFINITIONS_DIR` | Le dossier `definitions/` (economie.json, usines.json, boutique.json, monde.json, missions.json) | non en développement (le `definitions/` du dépôt, trouvé depuis `core/` ou la racine) ; oui ailleurs ; inutile en mode `migrate` |
| `SILOOO_API_REQUESTS_RETENTION_HOURS` | Durée de garde des réponses d'idempotence (`api_requests`), de 1 à 8760 heures | non, 24 par défaut |
| `SILOOO_DELIVERY_POLL_SECONDS` | Intervalle du relevé des commandes de Gemmes du site (`shop_deliveries`), de 1 à 3600 secondes | non, 30 par défaut |
| `SILOOO_SITE_PORT` | Port du site public, différent de `SILOOO_PORT` | non, 8081 par défaut |
| `SILOOO_SITE_MODE` | Le mode du site : `attente` ou `ouvert` (toute autre valeur arrête le service, code 78) | non, `attente` par défaut |
| `SILOOO_SITE_URL` | L'adresse publique du site, qui commence par `http` | non, `https://silooo.com` par défaut |
| `SILOOO_SITE_URL_DISCORD` | Le lien Discord affiché sur la page d'accueil et d'attente | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_SITE_ADRESSE_JEU` | L'adresse du serveur de jeu affichée sur l'accueil et Rejoindre (sans port) | non, `play.silooo.com` par défaut |
| `SILOOO_SITE_PORT_JEU` | Le port du jeu affiché, entier de 1 à 65535 (le défaut est celui de `BDS_PORT` dans `deploy/compose.yml`, ADR 0004) | non, `19142` par défaut |
| `SILOOO_SITE_URL_BOUTIQUE` | L'adresse où s'achètent les Gemmes, qui commence par `http` | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_SITE_SITES_DE_VOTE` | Les sites de vote, `nom\|url` séparés par des points-virgules (une entrée mal formée arrête le service) | non, aucun site par défaut |

Il manque quelque chose : le service le dit en une fois (toutes les variables absentes) et sort avec
le code 78, sans rien démarrer. Ni le mot de passe ni la clé ne s'écrivent dans les journaux.

Les définitions se chargent au démarrage, avant la base, et le chargement est strict : une clé
manquante, une clé inconnue, un mauvais type ou un fichier absent arrêtent le service avec le code 78
et un message qui nomme le fichier et le chemin JSON. Les tests lisent le `definitions/` du dépôt
(Gradle pose la propriété système `silooo.definitions.dir`), jamais une copie.

Pourquoi 24 heures : l'add-on rejoue une requête après une coupure de quelques secondes ou minutes ; au-delà
d'une journée, un rejeu n'a plus de sens. Une tâche purge les lignes plus vieilles, par lots, une fois par heure.

## Les deux modes

- **Démarrage normal** (aucun argument) : Flyway `validate`, puis le service écoute. Il n'applique
  jamais de migration. Si une migration manque, il échoue net au lieu de tourner sur un schéma
  inconnu.
- **`migrate`** : applique les migrations Flyway et sort. C'est la commande du service
  `silooo-migrate` du compose, avec le compte migrateur. Il n'a pas besoin de la clé.

Pourquoi deux modes : le compte applicatif ne change pas le schéma, la base est partagée avec
SileneMC, et un déploiement doit pouvoir rejouer les migrations seules avant le redémarrage
([deploiement.md](../docs/deploiement.md)). Les migrations sont dans
[`src/main/resources/db/migration/`](src/main/resources/db/migration/V1__init.sql) ; une migration
appliquée ne se modifie jamais.

## L'image Docker

```bash
docker build -f core/Dockerfile -t silooo-core .     # depuis la racine du dépôt : le contexte est la racine
docker run --rm --env-file <fichier d'environnement> silooo-core migrate
docker run --rm --env-file <fichier d'environnement> silooo-core
```

Construction par le wrapper Gradle sur `eclipse-temurin:17-jdk`, exécution sur
`eclipse-temurin:17-jre`, utilisateur non root, `-XX:MaxRAMPercentage=75`. L'image embarque `definitions/`
(les chiffres du jeu) dans `/app/definitions` et pose `SILOOO_DEFINITIONS_DIR` dessus : c'est pourquoi le
contexte est la racine du dépôt. Ce qui entre dans le contexte est dans `Dockerfile.dockerignore`, à côté du
Dockerfile : tout est exclu sauf le code de `core/` (sans les tests) et `definitions/`. La CI la construit à
chaque poussée et, sur `main`, la pousse sur `ghcr.io/e-quantum/silooo-core` (tags : SHA du commit et
`main`). Elle ne contient aucun secret.

## Où est quoi

Un paquet par domaine, sous `src/main/kotlin/com/silooo/core/`.

| Paquet ou fichier | Contenu |
| --- | --- |
| `Main.kt`, `Config.kt` | Les deux modes, la configuration par variables d'environnement |
| `Application.kt` | Le module Ktor et `Dependencies` : la base, les magasins et les interfaces du moteur lui sont passés, aucun singleton |
| `api/` | Le plugin `X-Silooo-Key` (401), les corps d'erreur et de refus, les modèles du contrat, l'idempotence (`Idempotency`, `call.idempotent`), la purge, le filet 501 `PAS_FAIT` |
| `db/` | `Database` (pool et transactions), `Migrations`, le verrou optimiste (`updateCompany`), le journal des monnaies (`creditBalance`), le magasin de `api_requests`, `TransientConflict` |
| `definitions/` | Le chargeur strict de `definitions/` (économie, usines, boutique, monde, missions, personnel) : classes typées et `DefinitionsLoader` |
| `economy/` | Le moteur pur (`EconomyEngine` : prix, production, prestige, hors ligne, découverte des usines ; `Notation` ; `BuyAmount`), ses deux branchements sur `join` (`EngineFigures` implémente `EconomyFigures`, `DefinitionsBootstrap` implémente `CompanyBootstrap`) et les routes `companies/{companyId}` : `CompanyRoutes` (HTTP), `CompanyService` (`getCompany`, `collect`, `buyLevels`, `restoreStep`, `prestigePreview`, `prestige`), `CompanyStore` et `JdbcCompanyStore` (SQL) |
| `server/` | `POST /v1/server/heartbeat` et l'état en mémoire des serveurs Bedrock (`ServerPresence`, la source de présence de `join`) |
| `session/` | `POST /v1/session/join` et `leave` : `SessionService`, `JdbcSessionStore`, `CompanyNaming`, `OnlinePlayers` (qui est connecté ; `server/ServerPresence` l'implémente), et les deux interfaces que le moteur remplit (`EconomyFigures`, `CompanyBootstrap`), dont les implémentations sont dans `economy/` |
| `events/` | `GET /v1/events` (curseur) et `publishEvent` |
| `mission/` | `GET /v1/players/{xuid}/missions` : `MissionRoutes`, `MissionService` (la chaîne de missions, l'avancement, la fin), `MissionStore` et `JdbcMissionStore` (`player_missions`, versement des Lingots, événement) ; `CompanyService.collect` l'appelle |
| `shop/` | `GET /v1/shop/catalog` et `POST /v1/shop/purchases` : `ShopRoutes`, `ShopService` (`catalog`, `buy`), `ShopRules` (grades, abonnements, bonus), `ShopStore` et `JdbcShopStore` (`player_ranks`, `player_subscriptions`, `shop_purchases`, `shop_deliveries`, Gemmes), `ShopFigures` (les bonus de Donar du PDG que le moteur lit) et `DeliveryRelief` (le relevé des livraisons de Gemmes, à intervalle et au `join`) |
| `staff/` | `GET /v1/companies/{companyId}/agency`, `POST .../agency/{slot}/hire`, `PATCH` et `DELETE .../staff/{staffId}` : `StaffRoutes` (HTTP), `StaffService` (`getCandidates`, `hire`, `assignStaff`, `fireStaff`), `AgencyDraw` (la liste du jour), `StaffStore` et `JdbcStaffStore` (`staff`, `agency_candidates`) |
| `src/test/.../simulation/` | Le simulateur du joueur type (code de test, hors du service) : `GameSimulator` rejoue neuf courses avec `EconomyEngine` et `definitions/`, `ThirteenTargetsTest` fait échouer la CI si une des treize cibles du tableur sort de sa fourchette ([economie.md](../docs/economie.md), « Le simulateur ») |

Toute route `/v1/...` pas encore écrite répond 501 avec `{"reason": "PAS_FAIT"}`, et toute route
`/v1` sans clé valable 401 avec `{"reason": "CLE_INVALIDE"}`. Écrire une route la remplace sans rien
retirer ailleurs. Ces codes, avec `REQUETE_INVALIDE` (400) et `ERREUR_INTERNE` (500), sont dans l'OpenAPI
(réponse `Erreur`).

## Écrire une route : les outils

Une route qui modifie quelque chose passe par ces quatre aides (recette
[`silooo-route`](../.claude/skills/silooo-route/SKILL.md)) :

| Aide | Rôle |
| --- | --- |
| `call.idempotent(dependencies, action, "operationId") { connection, actor -> ActionResult }` | Vérifie `requestId` et `actor` (400 sinon), garde la réponse dans `api_requests`, rejoue sans refaire l'action, répond 409 `CONFLIT_REESSAYER` si la même requête est encore en cours. Un refus du jeu (`throw Refusal(...)`) annule l'action mais il est gardé et rejoué. Variante `DELETE` : `requestId` et `actor` lus en paramètres. |
| `updateCompany(connection, companyId, expectedVersion, CompanyColumn.DONAR to x, ...)` | Le verrou optimiste de l'entreprise : un seul `UPDATE ... WHERE version = ?`. Version dépassée : `VersionConflict` (409 `CONFLIT_REESSAYER`). |
| `creditBalance(connection, xuid, Currency.LINGOTS, delta, "motif", refId)` | Le solde du joueur et sa ligne de `currency_ledger` dans la même transaction. Un solde qui passerait sous zéro est refusé avant d'écrire (`InsufficientBalance`, 409) ; le `CHECK` de la base reste le filet. |
| `publishEvent(connection, GameEventKind.GEMMES_RECUES, xuid, payload)` | Écrit l'événement que l'add-on lira par `GET /v1/events`, dans la transaction de ce qui le cause. |

`join`, `leave` et `heartbeat` n'ont pas de `requestId` : ils sont idempotents par nature (un `join` rejoué
retrouve le joueur et rend le même état), et un test le vérifie.

**MariaDB 12 et l'erreur 1020.** Depuis MariaDB 11.6, `innodb_snapshot_isolation` vaut ON : un `UPDATE` ou
un `SELECT ... FOR UPDATE` sur une ligne modifiée par une autre transaction APRÈS notre première lecture
échoue avec l'erreur 1020 (« Record has changed since last read ») au lieu de toucher zéro ligne. Le service la
traite comme un conflit passager : `updateCompany` la rend en `VersionConflict`, `Idempotency` en 409
`CONFLIT_REESSAYER`, `join` recommence (8 essais, avec une courte pause au hasard). Une nouvelle aide qui écrit
après une lecture simple doit faire de même (`isTransientConflict()`, dans `db/TransientConflict.kt`).

## Les chiffres que le moteur remplit

`join` rend des chiffres qu'il ne calcule pas lui-même (revenu par heure, multiplicateur, seuil, prix du prochain
niveau, palier, Donar hors ligne) : ils viennent de l'interface `EconomyFigures`, qu'`economy/EngineFigures`
implémente avec le `EconomyEngine`. Il n'existe pas de version « tout à zéro » : un état sans production n'est pas
un état de jeu. À la création d'une entreprise, `CompanyBootstrap` donne les usines de départ, le plafond hors ligne
de départ et la place de la case d'archipel ; `economy/DefinitionsBootstrap` les tire de `definitions/`
(`prestige.departDeCourse`, `horsLigne.plafondHeures`, `monde.json`). `Dependencies` branche les deux par défaut ;
un test passe les siens (`economyFigures = ...`, `companyBootstrap = ...`), et `EngineFigures` prend une horloge
en paramètre pour qu'un test avance le temps sans attendre.

- **Multiplicateur** : la moyenne des Lingots détenus par les membres (`player_balances` ; un membre sans ligne
  compte pour zéro) et les étoiles de l'entreprise.
- **Revenu** : seules les usines `ACTIVE` produisent. Une usine de la base que `usines.json` ne connaît plus est
  ignorée, avec un avertissement dans le journal, au lieu de faire tomber `join`.
- **Production écoulée** : elle se compte depuis `companies.last_collect_at`, le dernier calcul de production de
  l'entreprise, et non depuis le départ du joueur (`players.last_seen_at`) : deux associés qui se relaient
  se feraient payer la même heure deux fois. Elle se verse selon un `Regime`. `ONLINE` : le revenu plein,
  `EconomyEngine.onlineEarnings`, sans plafond ni efficacité. `OFFLINE` : `offlineEarnings`, heures plafonnées par
  la colonne `companies.offline_cap_hours` (que les améliorations étendent ; la création de l'entreprise la remplit
  avec `horsLigne.plafondHeures`, pour que `definitions/` reste la seule source du chiffre) et efficacité
  `horsLigne.efficacite`. `EconomyFigures.settle` écrit par `updateCompany` (Donar, Donar gagnés, `last_collect_at`)
  dans la transaction de l'appelant : si l'`UPDATE` perd contre un autre associé, l'appelant recommence et ne paie
  pas deux fois. `last_collect_at` repart de maintenant dès que du temps s'est écoulé, même à gain nul, pour qu'un
  `collect` ultérieur ne compte pas ces heures au revenu plein ; rien n'est écrit si l'horloge n'a pas avancé.
- **Qui choisit le régime** : `collect`, `buyLevels`, `restoreStep` et `leave` paient en ligne (leur acteur était
  connecté). `join` paie en ligne si un AUTRE membre de l'entreprise est connecté, hors ligne sinon. La présence est
  `ServerPresence`, que `heartbeat` remplit : un joueur est en ligne s'il figure dans le dernier signal d'un serveur
  reçu il y a moins de 30 secondes (l'add-on bat toutes les 10 secondes). `SessionStore.loadMemberXuids` donne les
  membres. `join` et `leave` recommencent sur un conflit de version (8 essais).
- **Seuil** : la liste de `prestige.seuils`, puis, au-delà de la dernière course, le dernier seuil fois
  `prestige.facteurSeuilSuivant`, plafonné à `Double.MAX_VALUE` pour que l'état reste toujours calculable.
- **Grille des îles** : la case N est dans la colonne `N % casesParRangee` et la rangée `N / casesParRangee` de
  `monde.json`, à `ecartEntreCases` blocs de sa voisine, à partir de `origine`.

`prestige.facteurSeuilSuivant` et les trois valeurs de `monde.json` sont **à valider par Kr**
([economie.md](../docs/economie.md), historique des réglages).

Le nom d'entreprise donné au premier passage est provisoire : « Entreprise de <gamertag> », avec un numéro si le
nom est pris. À valider par Kr (texte vu par les joueurs).

## Les routes d'économie

`GET /v1/companies/{companyId}` (`getCompany`), `POST .../collect`, `POST .../factories/{factoryId}/levels`
(`buyLevels`) et `POST .../factories/{factoryId}/restore` (`restoreStep`) sont écrites, puis le prestige (section suivante). `CompanyState.island` vient de la case d'archipel de l'entreprise (`archipelago_slots`). `CompanyRoutes` valide
l'entrée et répond ; `CompanyService` lit, décide et écrit, sans HTTP et sans aucun chiffre du jeu (prix, production
et plafond viennent de `EconomyEngine` et d'`EconomyFigures`) ; `CompanyStore` porte le SQL qui reste (l'usine,
l'événement, l'existence de l'entreprise). Le Donar de l'entreprise s'écrit par `updateCompany` (verrou optimiste,
409 `CONFLIT_REESSAYER` si un autre associé a écrit), jamais par `creditBalance`, qui est pour les monnaies
personnelles.

- **Une action d'écriture** : l'acteur doit être membre de l'entreprise ; les refus du jeu tombent avant la première
  écriture ; la production en attente est versée d'abord (au revenu plein, régime `ONLINE`), puis `updateCompany`,
  l'usine, l'événement, le tout dans la transaction de `call.idempotent`. L'état rendu est bâti sur ce qui vient
  d'être écrit.
- **Les cas que le contrat ne nomme pas** : l'OpenAPI ne déclare ni 403 ni 404 sur ces quatre routes. Une entreprise
  inconnue, un acteur qui n'en est pas membre, une usine inconnue de `usines.json`, un `amount` ou un `step` hors des
  valeurs donnent 400 `REQUETE_INVALIDE`. Une usine que l'entreprise n'a pas, ou qui ne produit pas, donne
  `USINE_VERROUILLEE` pour `buyLevels`. `leave`, qui verse maintenant la production, peut se heurter à un autre associé : après ses essais il rend
  409 `CONFLIT_REESSAYER`, que l'OpenAPI ne déclare pas pour lui (`KNOWN_409_GAPS` de `ContractTest`).
- **Les événements** : l'OpenAPI n'a pas de type propre à ces actions. Elles publient un `ANNONCE` adressé à l'acteur,
  dont `payload.action` vaut `collect`, `buyLevels`, `restoreStep`, `prestige`, `mission`, ou `hire`, `assignStaff` et `fireStaff` (section suivante) ; ajouter un type
  d'événement commence par l'OpenAPI.
- **Règles à valider par Kr** : les deux régimes de production (revenu plein en ligne, plafond et efficacité hors ligne), le comportement de `max`, la remise en
  état sans coût ([economie.md](../docs/economie.md), sections Hors ligne, Le prix des niveaux, La remise en état).
- **Les tests** : `economy/CompanyRoutesTest` (sans base), `contract/ContractTest` (les routes d'économie, le prestige
  et les missions sont dans `WRITTEN`) et `economy/CompanySqlTest` (MariaDB réelle, ignoré sans Docker : ce qui s'écrit dans `companies`,
  `company_factories` et `game_events`, la transaction unique, et des achats simultanés).

## Le prestige

`GET /v1/companies/{companyId}/prestige/preview` (`prestigePreview`) et `POST .../prestige` (`prestige`) sont dans
`CompanyService`, avec les autres actions. L'aperçu ne change rien. Le prestige est réservé au PDG (403 `PAS_LE_PDG`)
et refusé sous le seuil (409 `SEUIL_NON_ATTEINT`, avec les Donar qui manquent). Seuil, Lingots, rendement décroissant
et départ de course sortent d'`EconomyEngine`, qui les lit dans `economie.json` : aucun chiffre du jeu dans le service.

- **Dans l'ordre** : la production en attente est versée d'abord (en ligne), elle compte pour le seuil et pour les
  Lingots ; puis une seule écriture `updateCompany` pose la course suivante (Donar et Donar gagnés à zéro,
  `course_number` + 1, époque et étoiles tirées du moteur, `course_started_at` et `last_collect_at` à maintenant) ;
  puis les usines repartent, les Lingots se créditent un membre à la fois (`player_balances` et `currency_ledger`,
  motif `prestige`), la ligne de `prestige_history` s'écrit, et chaque membre reçoit un événement. Tout dans la
  transaction de `call.idempotent` : un échec annule tout, un rejeu ne verse rien de plus.
- **Le partage** : `company_members.share_percent`, arrondi vers le bas pour chacun, le reste au PDG
  (`economy/PrestigeShares`).
- **La remise à zéro des usines** : la Carrière (`prestige.departDeCourse`) repart `ACTIVE` à son niveau de départ ;
  les autres usines repassent `EN_RUINE`, niveau 0, étape 0, leurs lignes gardées, et leurs incidents ouverts sont
  clos. La mairie, le silo, le plafond hors ligne et le personnel ne sont pas remis à zéro : ils ne sont pas encore
  modélisés.
- **Les événements** : `ANNONCE` adressé à chaque membre, `payload.action = "prestige"`, avec `companyId`, `course`,
  `era`, `stars`, `lingotsTotal` et la part du membre (`lingots`). `PRESTIGE_ENTREPRISE`, qui existe dans l'OpenAPI,
  n'est pas utilisé : un seul événement adressé à toute l'entreprise ne dit pas la part de chacun.
- **Règles à valider par Kr** : la production en attente compte avant le test du seuil ; le reste du partage va au
  PDG ; les usines autres que la Carrière repartent en ruine ; un événement par membre
  ([economie.md](../docs/economie.md), section Le prestige et les Lingots).
- **Les tests** : `PrestigeSharesTest`, les cas de `CompanyRoutesTest`, et dans `CompanySqlTest` (MariaDB, CI) la
  course écrite en base, l'annulation d'ensemble et des prestiges simultanés qui ne versent qu'une fois.

## Les missions

`GET /v1/players/{xuid}/missions` (`getMissions`) rend la mission en cours et la liste de celles que le joueur a
terminées. Le catalogue est `definitions/missions.json` (schéma `definitions/schemas/missions.schema.json`) : pour
chaque mission, son `id` (la clé de `player_missions`), l'`evenement` qu'elle compte (`collect`), la `cible` et la
`recompense`, qui n'est pas un nombre mais le chemin du nombre dans `economie.json` (la première mission,
`bienvenue-mairie`, pointe `lingotsHorsPrestige.missionBienvenue`). `Definitions.missionRewards` le résout au
chargement, et `tools/verifier_definitions.mjs` refuse un chemin qui ne mène pas à un entier. Les textes des missions
sont ceux de l'add-on : le service ne rend que l'identifiant, l'avancement et les Lingots.

- **Qui compte** : `MissionService.record` est appelé par `CompanyService.collect`, dans la même transaction, quand
  l'encaissement rapporte du Donar (un `collect` à zéro, ou sans rien de dû, ne compte pas). Le service compte
  lui-même : l'add-on ne dit jamais « mission faite ».
- **La chaîne** : la mission en cours est la première du catalogue que le joueur n'a pas terminée, et c'est la seule
  qui avance. Une ligne de `player_missions` d'une mission qui n'est plus au catalogue est ignorée.
- **La fin** : à la cible, la mission se termine dans la même transaction que le `collect` : `completed_at`, Lingots
  versés (`creditBalance`, motif `mission`, `ref_id` = l'identifiant de la mission) et un événement `ANNONCE`
  (`payload.action = "mission"`, avec `missionId` et `rewardLingots`). Il n'y a pas de route pour la réclamer, et une
  mission terminée ne se rejoue jamais : un rejeu du même `requestId` rend la réponse gardée, un nouvel encaissement ne
  verse rien de plus. La ligne est relue sous verrou (`SELECT ... FOR UPDATE`), et le `collect` lui-même est déjà
  protégé par le verrou optimiste de l'entreprise.
- **Les cas que le contrat ne nomme pas** : un XUID mal formé ou d'un joueur que le service ne connaît pas donne 400
  `REQUETE_INVALIDE` (le contrat ne déclare pas de 404).
- **Règles à valider par Kr** : la mission n'avance que si l'encaissement rapporte ; les missions se font dans l'ordre
  du catalogue ; la récompense est versée à la fin, sans geste du joueur ([economie.md](../docs/economie.md), section Les missions).
- **Les tests** : `mission/MissionServiceTest` (sans base), les cas de `CompanyRoutesTest` et de `ContractTest`
  (`getMissions` est dans `WRITTEN`), `DefinitionsLoaderTest` pour le catalogue, et `mission/MissionSqlTest` (MariaDB,
  CI) : la ligne, le solde, le journal et l'événement écrits ensemble, l'annulation d'ensemble, des `collect`
  simultanés qui ne terminent la mission qu'une fois.

## Le personnel

`GET /v1/companies/{companyId}/agency` (`getCandidates`), `POST .../agency/{slot}/hire` (`hire`), `PATCH .../staff/{staffId}`
(`assignStaff`) et `DELETE .../staff/{staffId}?requestId=&actor=` (`fireStaff`). `StaffRoutes` valide l'entrée et répond ;
`StaffService` lit, décide et écrit, sans HTTP et sans aucun chiffre du jeu ; `JdbcStaffStore` porte le SQL de `staff` et de
`agency_candidates`. Aucune migration : les deux tables de V1 suffisent. Tous les chiffres sont dans
`definitions/personnel.json` (schéma `definitions/schemas/personnel.schema.json`, branché sur `tools/verifier_definitions.mjs`,
sur `tools/generer_types.mjs` et sur le chargeur) : la liste du jour, les parts de rareté, le bonus, le salaire et le prix
d'embauche de chaque rareté, les facteurs de rôle, les postes et le plafond hors ligne du chef. Le moteur en tire
`staffBonusPercent`, `staffSalaryPercent`, `crewNetPercent`, `salaryPerHour`, `hireCost`, `staffPosts` et `leaderCapHours`.

- **La liste du jour** : `AgencyDraw` la tire de l'entreprise et du jour UTC seulement (SplitMix64 sur
  `companyId * 0x9E3779B97F4A7C15 + jour`), donc la même toute la journée, à chaque lecture et sur toutes les machines.
  Le rôle de chaque place vient de `agence.rolesParPlace`, la rareté d'un tirage cumulé sur
  `raretes.*.repartitionPourcent` (75, 20 et 5 % au départ), le nom de `agence.noms` (jamais deux fois le même dans une
  liste) et l'apparence de `agence.apparences` pour le rôle. Elle n'est jamais gardée en base. Aucune entrée ne la
  change, donc aucun moyen de payer pour une autre : le contrat n'a ni relance ni tirage payant, et `StaffRoutesTest` le
  vérifie (les opérations d'agence sont `getCandidates` et `hire`, rien d'autre).
- **`agency_candidates`** ne garde que les places embauchées du jour, avec le candidat tel qu'il a été proposé et le
  `hired_staff_id`. Une place embauchée reste dans la liste avec son `staffId` et son usine, sans prix ; renvoyée, sa
  ligne disparaît et le candidat est de nouveau embauchable aujourd'hui, au prix du moment.
- **`hire`** : le prix est `hireCost` = les heures de revenu de la rareté fois le facteur du rôle, fois le revenu net de
  l'entreprise maintenant. La production en attente est versée d'abord (régime `ONLINE`), puis le prix est débité en UNE
  écriture `updateCompany` : si un autre associé a écrit entre-temps, 409 `CONFLIT_REESSAYER`, et rien n'est écrit. Les
  refus sont `DEJA_POSSEDE` (place déjà embauchée), `PAS_ASSEZ_DE_DONAR` (avec la somme qui manque), `USINE_VERROUILLEE`
  (usine qui n'est pas à l'entreprise ou pas `ACTIVE`) et `EMPLACEMENT_OCCUPE` (plus de poste libre pour le rôle dans
  cette usine). L'usine est facultative : un membre sans usine ne rapporte ni ne coûte rien.
- **Les postes** : `postes.salariesBase` salarié par usine, plus un tous les `postes.niveauxParPoste` niveaux, et
  `postes.chefsParUsine` chef. Les postes ne limitent que les embauches et les affectations : une usine qui en perd
  (prestige) garde son équipe.
- **`assignStaff`** change l'usine du membre, sans frais. L'affecter là où il est déjà ne change rien : ni écriture, ni
  événement, la même réponse d'état.
- **`fireStaff`** supprime le membre, sans remboursement. Seul le PDG renvoie : un associé reçoit 403 `PAS_LE_PDG`.
- **Dans l'économie** : un membre dans une usine `ACTIVE` change sa production du net `bonus - salaire`, en pourcent de la
  production de l'usine sans équipe. Les bonus de plusieurs membres s'additionnent. Le salaire est une sortie d'argent
  comptée dans le revenu net (`CompanyState.ratePerHour`, `Factory.ratePerHour`), donc dans les deux régimes : en ligne
  au revenu plein, hors ligne par le plafond et l'efficacité comme le reste. Un chef d'équipe relève de
  `chefEquipe.plafondHorsLigneBonusHeures` le plafond hors ligne de son usine seulement (`offlineEarningsWithLeaders`).
  Sans personnel, tous les chiffres d'avant sont inchangés, bit pour bit.
- **Les cas que le contrat ne nomme pas** : un acteur qui n'est pas membre, une place hors de 1 à 5, une usine inconnue de
  `usines.json`, un membre inconnu ou d'une autre entreprise, un `requestId` mal formé donnent 400 `REQUETE_INVALIDE`
  (l'OpenAPI ne déclare pas de 404). Le 403 `PAS_LE_PDG` de `fireStaff` a été ajouté à l'OpenAPI, ainsi que
  `salaryPercent`, `salaryPerHour` et `hireCost` dans `Staff` ; le rôle `DIRECTEUR` de `Staff.role` n'est pas encore
  modélisé (aucune ligne n'en porte, `find` et `loadStaff` l'écartent).
- **Les événements** : `ANNONCE` adressé à l'acteur, `payload.action` = `hire` (avec `staffId`, `slot`, `role`, `rarity`,
  `cost`, et `factoryId` s'il y en a une), `assignStaff` ou `fireStaff`. Le contrat n'a pas de type propre.
- **Règles tranchées au plus simple, à valider par Kr** : tout membre de l'entreprise embauche et affecte, seul le PDG
  renvoie ; le prix suit le revenu net du moment ; les bonus s'additionnent ; les postes ne limitent que l'entrée ; le
  chef ne relève que le plafond de son usine ; les noms, les apparences et les parts de rareté de `personnel.json` sont des
  valeurs de départ ([economie.md](../docs/economie.md), section Le personnel).
- **Les tests** : `staff/AgencyDrawTest` (le tirage, ses parts de rareté), `economy/EngineStaffTest` et
  `economy/EngineFiguresStaffTest` (les formules et les deux régimes), `staff/StaffRoutesTest` (les quatre routes sans base),
  `contract/ContractTest` (les quatre opérations sont dans `WRITTEN`), `DefinitionsLoaderTest` pour `personnel.json`, et
  `staff/StaffSqlTest` (MariaDB, CI) : les lignes écrites ensemble, l'annulation d'ensemble, des embauches simultanées.

## Ce qui vient après, en phase 1

Chaque route suit la recette [`silooo-route`](../.claude/skills/silooo-route/SKILL.md) : contrat,
test de contrat, puis code.

- Les routes d'économie qui suivent `restoreStep` (usines à découvrir et à racheter,
  associés, ville, marché, boutique) : elles se servent du moteur et de `CompanyService` comme les quatre premières.
- Un coût de remise en état dans `definitions/`, et des types d'événement propres dans l'OpenAPI.
- Le compose et le déploiement arrivent en phase 2 ([passation.md](../docs/passation.md)).

## La commande du jour

`GET /v1/orders/today` (`todayOrder`) rend la commande mondiale du jour ; `POST /v1/orders/today/deliveries`
(`deliverOrder`) livre des unités pour l'entreprise de l'acteur. Le code est dans `orders/` : `OrderPlan` (ce que dit
le jour), `OrderService` (lire, livrer, payer), `OrderStore` et `JdbcOrderStore` (le SQL sur `world_orders` et
`world_order_contributions`, déjà dans V1) et `OrderRoutes`. Aucun chiffre du jeu n'est dans le code :
`economie.json` donne `commandeDuJour.objectifUnites`, `commandeDuJour.minutesDeProductionParUnite` et
`lingotsHorsPrestige.commandeDuJour`, et `usines.json` donne les ressources (`Definitions.resources` : les produits des
usines, une fois chacun, dans l'ordre du fichier).

- **La commande du jour** : le jour est la date UTC de l'horloge du service (`Dependencies.clock`). La ressource
  est la `(jours depuis 1970 mod nombre de ressources)`-ième : la même pour tout le serveur, sans tirage au sort, et
  tout le monde parcourt les ressources dans le même ordre. Tant que personne n'a livré, la commande n'est écrite
  nulle part : `todayOrder` la rend d'après le plan et n'écrit rien. Avec `?xuid=`, `mine` est la part déjà livrée par
  ce joueur ; sans, `mine` vaut 0.
- **Livrer, dans l'ordre** : (1) l'acteur a une entreprise ; (2) la commande du jour est écrite si besoin et
  verrouillée (`SELECT ... FOR UPDATE`) AVANT la lecture de l'entreprise, pour que deux livraisons de la même entreprise
  se suivent et que la seconde lise l'entreprise déjà débitée ; (3) sur une commande terminée, rien n'est pris ni
  écrit et la réponse est un 200 avec la commande finie, sinon la quantité est ramenée au reste à livrer ; (4) le prix est
  `unités x minutesDeProductionParUnite / 60 x revenu par heure de l'entreprise` en Donar (le revenu vient du moteur,
  comme pour `buyLevels`), pris sur la caisse avec la production en attente ; s'il ne la couvre pas, refus
  `PAS_ASSEZ_DE_DONAR` avec `missing` ; (5) l'entreprise est écrite par le verrou optimiste, en une seule écriture avec
  la production versée, puis la part du joueur, la commande et un événement `ANNONCE` (`payload.action =
  "deliverOrder"`, avec `day`, `resourceId` et `quantity`).
- **La fin** : quand la commande atteint l'objectif, chaque joueur qui a livré touche `lingotsHorsPrestige.commandeDuJour`
  Lingots, une fois (`creditBalance`, motif `commande`, `ref_id` = `commande-<jour>`, `rewarded_at` posé sur sa part) et
  reçoit un événement `COMMANDE_TERMINEE` (`day`, `resourceId`, `rewardLingots`). Celui qui livre le dernier lot ne
  prend rien de plus. Tout est dans la transaction de la dernière livraison : si une écriture échoue, la livraison est
  annulée avec la prime.
- **Idempotence et conflits** : `deliverOrder` passe par `call.idempotent` (point d'entrée `deliverOrder`) : un
  `requestId` rejoué rend la réponse gardée, y compris un refus. Une version d'entreprise dépassée donne 409
  `CONFLIT_REESSAYER`. La ligne de la commande est chaude (toutes les livraisons du jour la modifient) : sous
  l'isolation par instantané de MariaDB 12, deux joueurs qui livrent au même instant peuvent voir l'un d'eux recevoir
  `CONFLIT_REESSAYER` (l'erreur 1020 est déjà traduite par `Idempotency`) ; l'add-on réessaie avec le même `requestId`.
- **Les cas que le contrat ne nomme pas** : `quantity` sous 1 ou non entière, acteur sans entreprise et `xuid` mal formé
  de `todayOrder` donnent 400 `REQUETE_INVALIDE`. Une livraison sur une commande déjà terminée n'a pas de code de refus
  au contrat : elle répond 200, `order.completed` vrai, sans rien prendre. Un code de refus propre aurait cassé les deux
  tests de l'add-on qui comparent ses codes à `Refused.reason`, et cette tranche ne touche pas à `addon/` : à reprendre
  avec la session de l'add-on si Kr préfère un refus.
- **Règles à valider par Kr** : le game design dit « une commande par jour que tout le serveur livre ensemble » sans dire
  comment la ressource est choisie, ce que coûte une unité, ni qui est payé ; le service tranche ainsi
  ([economie.md](../docs/economie.md), section Les commandes du jour et le Carnet) : ressource par rang du jour,
  objectif de 100 unités, prix en minutes de revenu de l'entreprise du joueur, plusieurs livraisons par jour permises,
  prime en Lingots hors prestige à chaque participant à la fin (le game design parle de jetons, V1 de Lingots).
- **Les tests** : `orders/OrderRoutesTest` (sans base : prix, rognage, fin, refus, rejeu, conflit, 400),
  `ContractTest` (`todayOrder` et `deliverOrder` sont dans `WRITTEN`), `DefinitionsLoaderTest` pour les clés, et
  `orders/OrderSqlTest` (MariaDB, CI) : tout écrit ensemble, annulé ensemble, des livraisons simultanées qui gardent la
  commande cohérente.

## Le Carnet de trouvailles

`GET /v1/players/{xuid}/findings` (`getFindings`) rend les ressources que le joueur a trouvées, leur nombre possible et la
prime de chacune. Le code est dans `findings/` : `FindingService`, `FindingStore` et `JdbcFindingStore` (le SQL sur
`player_findings`, migration V3), `FindingRoutes`. Le plan de construction cite le Carnet sans le décrire : le service
prend la règle la plus simple.

- **Une trouvaille = une ressource du jeu** (un produit de `usines.json`). Il n'y a pas de route pour en réclamer une :
  `CompanyService.collect` appelle `FindingService.record` dans sa transaction, quand l'encaissement rapporte du Donar.
  Chaque ressource que produit une usine en marche (`ACTIVE`) de l'entreprise entre dans le Carnet de celui qui
  encaisse, si elle n'y est pas. Aucun hasard : la première fois qu'une usine produit, c'est trouvé.
- **Chaque trouvaille paie une fois** `lingotsHorsPrestige.trouvaille` Lingots (`creditBalance`, motif `trouvaille`,
  `ref_id` = la ressource) et publie un événement `ANNONCE` (`payload.action = "trouvaille"`, avec `resourceId` et
  `rewardLingots`). La clé primaire `(xuid, finding_id)` tranche si deux encaissements se croisent : `INSERT IGNORE`
  dit lequel a gagné, et lui seul paie.
- **Le Carnet est au joueur, pas à l'entreprise** : un prestige ne l'efface pas, un associé qui encaisse inscrit dans le
  sien, et ce que le PDG a trouvé n'est pas à lui.
- **Les cas que le contrat ne nomme pas** : un `xuid` mal formé ou d'un joueur que le service ne connaît pas donne 400
  `REQUETE_INVALIDE`, comme `getMissions`. `getFindings` est une opération de plus au contrat (31).
- **Les tests** : `findings/FindingRoutesTest` (sans base : Carnet vide, inscription par `collect`, une seule prime,
  usines en ruine, un Carnet par joueur), `ContractTest` (`getFindings` est dans `WRITTEN`), un cas de
  `CompanyRoutesTest` (le solde du PDG compte les primes du Carnet) et `findings/FindingSqlTest` (MariaDB, CI) : lignes,
  solde, journal et événements écrits et annulés ensemble, la clé primaire.

## Les incidents

`POST /v1/companies/{companyId}/incidents/{incidentId}/resolve` (`resolveIncident`) répare une usine ; le service ouvre
lui-même les incidents. Le code est dans `incident/` : `IncidentDraw` (le tirage), `IncidentService` (ouvrir et réparer),
`IncidentTicker` (le minuteur), `IncidentStore` et `JdbcIncidentStore` (le SQL sur `factory_incidents`, déjà dans V1),
`IncidentRoutes`. Un incident ralentit une usine et ne fait jamais rien perdre : ni Donar, ni niveau, ni personnel.

- **Tous les chiffres sont dans `definitions/incidents.json`** : `frequence` (un créneau de `creneauMinutes`, une chance
  de `chancePourcent` par créneau), `sortes` (`id`, `ralentissement`, `poids`), `recompense.minutesDeRevenu`, et
  `electricite` (voir plus bas). Le service n'a aucun chiffre du jeu ; l'add-on écrit les textes.
- **Le tirage est figé** : `IncidentDraw` ne dépend que de l'entreprise, du jour (UTC) et du rang du créneau dans ce
  jour (`SplitMix64`, comme l'agence d'emploi). Même créneau, même résultat, sur toutes les machines, et rien n'est
  écrit pour s'en souvenir. Aucune relance, aucun tirage payant : un test rejoue la suite.
- **Qui l'ouvre** : un minuteur du service (`incidentTick`, 30 s en production, `null` dans les tests) lit les joueurs
  connectés (`ServerPresence.onlineXuids()`, les signaux de vie frais du serveur Bedrock). Il ne tire que pour les
  entreprises dont un membre est connecté depuis deux passages de suite, pour qu'un joueur qui vient d'arriver ait déjà
  reçu l'argent de son absence (`join` le verse) : **aucun incident pendant une absence**. Une erreur ou un conflit de
  verrou sur une entreprise est noté et ne retarde pas les autres ; le tirage ne change pas dans le créneau, le passage
  suivant le rejoue.
- **Ce que le tirage frappe** : au plus un incident par entreprise et par créneau, au plus un ouvert par usine, sur une
  usine `ACTIVE` de niveau 1 au moins, connue de `usines.json`, qui n'est pas à l'arrêt faute de courant (choisie parmi
  celles-là, par identifiant, d'après le nombre brut du tirage). L'ouverture verse d'abord la production en attente au
  rythme d'avant : le ralentissement ne touche que ce qui vient ensuite. Un événement `INCIDENT` (`payload.action =
  "opened"`, avec `incidentId`, `factoryId`, `kind`, `slowdown`) part vers chaque membre connecté.
- **Dans l'économie** : l'usine produit sa production multipliée par le `ralentissement` de la sorte, **en ligne
  seulement**. Hors ligne, rien n'est ralenti (`EngineFigures`), puisqu'aucun incident ne s'ouvre pendant une absence.
  `Factory.incidentId` et `Factory.incidentKind` disent quoi réparer, `Factory.ratePerHour` est déjà ralenti. Le
  ralentissement porte aussi sur le salaire de l'équipe de l'usine : le net reste la même part de la production. Sans
  incident, tous les chiffres d'avant sont inchangés, bit pour bit.
- **Réparer** : l'add-on lance le mini-jeu et envoie `resolveIncident` quand il est gagné. Le service verse la production
  en attente (au rythme ralenti), puis la récompense (`recompense.minutesDeRevenu` minutes du revenu de l'usine sans
  ralentissement, comptées dans le Donar gagné de la course), ferme l'incident, relance l'usine, le tout en une écriture
  de l'entreprise. Un événement `INCIDENT` (`action = "resolved"`, avec `reward` et `resolvedBy`) part vers les membres
  connectés et l'acteur. N'importe quel membre peut réparer.
- **Les cas que le contrat ne nomme pas** : un incident inconnu de l'entreprise ou d'une autre entreprise, un acteur
  qui n'en est pas membre, un `requestId` mal formé donnent 400 `REQUETE_INVALIDE`. Réparer un incident déjà clos (par un
  associé, par un prestige, ou un double envoi) répond 200 avec l'état d'aujourd'hui, sans récompense ni écriture : le
  contrat n'a pas de code de refus pour cela, et en ajouter un aurait touché `addon/`. À reprendre si Kr préfère un refus
  `INCIDENT_CLOS`. Un prestige clôt les incidents ouverts de l'entreprise (`restartFactories`).
- **Règles tranchées au plus simple, à valider par Kr** : le tirage par créneau de 30 minutes, 20 % de chance, les trois
  sortes (incendie x0,5, panne x0,6, courroie x0,7), la récompense de 10 minutes de revenu, un seul incident par créneau
  ([economie.md](../docs/economie.md), section Les incidents d'usine).
- **Les tests** : `incident/IncidentDrawTest` (la suite figée, les parts), `economy/EngineIncidentTest` et
  `economy/EngineFiguresIncidentTest` (ralentissement en ligne seulement, récompense), `incident/IncidentServiceTest`,
  `incident/IncidentTickerTest` (les deux passages, les absents, les pannes), `incident/IncidentRoutesTest` (la route
  sans base : une écriture, les événements, le rejeu, le conflit), `contract/ContractTest` (`resolveIncident` est dans
  `WRITTEN`) et `incident/IncidentSqlTest` (MariaDB, CI) : lignes, jointure des usines, prestige, minuteur, réparation
  et réparations simultanées.

## L'électricité

À partir de l'époque `electricite.aPartirDeEpoque` de l'entreprise, une usine dont `usines.json` dit
`"electricite": true` s'arrête tant que l'entreprise n'a aucune Centrale (`electricite.usineCentrale`) qui produise, au
niveau 1 au moins. La règle est dans `EconomyEngine.stoppedNoPower` ; `EngineFigures` la branche.

- **À l'arrêt** : l'usine ne produit rien (`Factory.ratePerHour` = 0, `Factory.stoppedNoPower` = true), son équipe ne coûte
  aucun salaire, à tout régime, en ligne et hors ligne. Elle garde son niveau, son état et son personnel : rien n'est perdu,
  et la production reprend dès qu'une Centrale tourne.
- **Dans le tirage** : une usine à l'arrêt n'est jamais frappée par un incident, et réparer une usine arrêtée rapporte 0
  (la récompense suit son revenu).
- **Les cas** : avant l'époque 3, ou avec une Centrale active, personne ne s'arrête ; une usine inconnue de `usines.json`
  ne s'arrête pas (elle ne compte déjà pas). Une Centrale en ruine, en remise en état ou au niveau 0 ne compte pas.
- **Les tests** : `economy/EngineIncidentTest` (qui s'arrête), `economy/EngineFiguresIncidentTest` (revenu nul, salaire
  absent, deux régimes) et `incident/IncidentServiceTest` (le tirage laisse les usines arrêtées tranquilles).

## L'entreprise partagée

Le PDG invite des associés, l'invité accepte, le PDG fixe les parts de chacun. Trois routes : `POST /v1/companies/{companyId}/members`
(`invite`), `POST /v1/companies/{companyId}/members/accept` (`acceptInvite`, ajoutée au contrat : 32 opérations) et
`PUT /v1/companies/{companyId}/members` (`setShares`). Le code est dans `members/` : `MemberService` (les règles),
`MemberRoutes`, `MemberStore` et `JdbcMemberStore` (le SQL sur `company_members` et `company_invites`). Aucune migration : les
deux tables de V1 suffisent. Le prestige et le partage des Lingots (`PrestigeShares`) ne changent pas : ils lisaient déjà
`share_percent`.

- **Les limites** viennent de `monde.json`, bloc `entreprise` : `membresMax` (4, PDG compris) et `invitationHeures` (48). Un
  autre monde les change sans toucher au code. Le service ne fait aucun calcul de solde ni de prix ici.
- **`invite`** : seul le PDG (403 `PAS_LE_PDG` pour un associé). Elle garde une ligne de `company_invites` valable
  `invitationHeures` et publie un événement `INVITATION` pour l'invité (`payload` : `companyId`, `companyName`, `invitedBy`,
  `invitedByName`, `expiresAt`). Réinviter le même joueur renouvelle la ligne. Elle n'écrit pas l'entreprise : ni version de plus,
  ni membre. L'état rendu est celui de l'entreprise, inchangé. Refus 409 : `DEJA_POSSEDE` (déjà membre), `ENTREPRISE_PLEINE`,
  `ENTREPRISE_EN_COURS` (voir plus bas). Un acteur qui n'est pas de l'entreprise, un joueur que le service ne connaît pas ou le PDG
  qui s'invite : 400 `REQUETE_INVALIDE`.
- **`acceptInvite`** : l'acteur est l'invité. Il entre comme `ASSOCIE` avec une part de 0 % : les parts de l'entreprise font
  toujours 100, et le PDG n'en perd aucune sans l'avoir décidé par `setShares`. Les autres invitations du joueur tombent.
  Un événement `ANNONCE` part (`payload.action = "acceptInvite"`, avec `companyId` et `fromCompanyId`). La réponse est
  `CompanyUpdated` pour l'entreprise rejointe. Pas d'invitation, invitation périmée, acteur sans entreprise : 400. Refus 409 :
  `DEJA_POSSEDE` (l'invitation de sa propre entreprise), `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS`.
- **Un joueur n'a qu'une entreprise, et tout joueur en a une depuis son premier `join`.** Le contrat ne dit rien de celle de
  l'invité. Règle choisie, la plus simple et la moins destructrice (**à valider par Kr**) : l'entreprise de l'invité doit être
  « à son départ », c'est-à-dire lui seul comme membre, ni prestige ni course ni époque, ni mairie ni silo améliorés, ni
  personnel ni zone de ville, et exactement les usines de départ de `prestige.departDeCourse` au niveau de départ. Sinon
  `ENTREPRISE_EN_COURS`, à l'invitation comme à l'acceptation. Quand l'invité entre, son entreprise de départ **n'est jamais
  supprimée** : elle reste, sans membre, avec ses usines, son île et sa case de l'archipel. Le contrat n'a aucune route pour la
  quitter, la reprendre ou la libérer ; c'est un manque à traiter avec Kr.
- **La production de l'entreprise rejointe est versée avant l'arrivée**, au régime d'avant : en ligne si un de ses membres est
  connecté (signal de vie), sinon hors ligne (plafond et efficacité), comme `join`. L'invité qui arrive ne compte pas : sinon
  son arrivée transformerait en revenu plein des heures où personne ne jouait. Cette écriture passe la version de l'entreprise,
  qui protège aussi le plafond de membres de deux arrivées simultanées.
- **`setShares`** : seul le PDG (403 `PAS_LE_PDG`). `shares` nomme chaque membre une fois, d'un pourcentage entier de 0 à 100,
  pour 100 en tout, PDG compris (il peut se donner 0). Une liste qui oublie un membre, en nomme un qui n'en est pas, deux fois le
  même, hors de 0 à 100 ou qui ne fait pas 100 : 400, car c'est une requête fausse, pas un refus du jeu. La version de
  l'entreprise monte d'un, et un événement `ANNONCE` part (`payload.action = "setShares"`, `companyId`, et `shares`, la liste
  des `xuid` et `percent`). Un prestige qui avait lu les anciennes parts échoue en `CONFLIT_REESSAYER`.
- **`CompanyState.members`** (ajouté au contrat, facultatif) donne le PDG d'abord, puis les associés dans l'ordre d'arrivée :
  `xuid`, `name` (le gamertag), `role` et `sharePercent`. L'add-on ne le lit pas encore.
- **Idempotence et conflits** : les trois routes passent par `call.idempotent` (points d'entrée `invite`, `acceptInvite`,
  `setShares`) : un `requestId` rejoué rend la réponse gardée. Une version dépassée donne 409 `CONFLIT_REESSAYER`. Tout est écrit
  dans une seule transaction, événement compris : si la publication échoue, l'arrivée et l'invitation sont annulées.
- **Les tests** : `members/MemberServiceTest` (la règle « entreprise déjà lancée » cas par cas, les limites de `definitions/`),
  `members/MemberRoutesTest` (sans base : les trois routes, refus, 400, rejeu, conflit, régime à l'arrivée),
  `ContractTest` (`invite`, `acceptInvite` et `setShares` sont dans `WRITTEN`), `DefinitionsLoaderTest` pour les clés, et
  `members/MemberSqlTest` (MariaDB, CI) : lignes, événements et version écrits et annulés ensemble, le plafond de membres et
  un invité pris par deux entreprises sous concurrence.

## La ville côté service

Trois routes, déjà nommées au contrat (qui reste à 32 opérations), et maintenant écrites : `buyZone`
(`POST /v1/companies/{companyId}/city/zones/{zoneIndex}`), `placeBuilding` (`POST /v1/companies/{companyId}/city/buildings`,
corps : `buildingType`, `x`, `z`, `rotation`) et `upgradeBuilding` (`POST /v1/companies/{companyId}/city/buildings/{buildingId}`).
Le service décide de tout (prix, droits, emplacement, bonus) ; l'add-on affiche `CompanyState.city`. Les règles de jeu et les tableaux
de chiffres sont dans `docs/economie.md`, section « La ville côté service » ; les chiffres eux-mêmes dans `definitions/ville.json`
(et `economie.json`, `ville.zonesPrixLingots`).

- **Le code** est dans `city/` : `CityRules` (les règles pures sur `definitions/` : zone d'une parcelle, prix, prochaine zone, vue
  d'un bâtiment, bonus plafonnés, logés), `CityService` (les trois actions), `CityRoutes` (la forme des requêtes et
  `call.idempotent`), `CityStore` et `JdbcCityStore` (le SQL sur `zones` et `city_buildings`), `CityFigures` (la ville vue par le
  moteur : `housed` du personnel et bonus de production, sans que `economy/` connaisse le SQL). `economy/CityBonusSource` est
  l'interface que le moteur lit ; sans source, rien ne change.
- **L'ordre des vérifications** est le même pour les trois actions : forme de la requête (400 avant tout), appartenance de l'acteur
  à l'entreprise, règles du jeu (409 : `ZONE_VERROUILLEE`, `EMPLACEMENT_OCCUPE`, `DEJA_POSSEDE`), puis les Lingots
  (`PAS_ASSEZ_DE_LINGOTS`, avec la somme qui manque), puis seulement les écritures. Un refus n'écrit rien.
  - `buyZone` : zone entre 1 et 16, `DEJA_POSSEDE` si elle est déjà à l'entreprise, `ZONE_VERROUILLEE` si ce n'est pas la
    suivante, si la mairie est trop basse ou s'il manque des usines actives (la première zone en demande deux). La première zone
    pose aussi la mairie niveau 1 à `mairie.emplacementDepart`.
  - `placeBuilding` : type inconnu, non posable, coin hors grille ou rotation hors 0, 90, 180, 270 : 400. Puis zone non achetée ou
    mairie trop basse : `ZONE_VERROUILLEE` ; parcelle prise : `EMPLACEMENT_OCCUPE` ; autant d'exemplaires que le catalogue
    en permet : `DEJA_POSSEDE`.
  - `upgradeBuilding` : bâtiment inconnu ou d'une autre entreprise : 400 ; dernier niveau : `DEJA_POSSEDE` ; mairie trop basse :
    `ZONE_VERROUILLEE`. La mairie est un bâtiment comme les autres pour cette route ; son niveau passe aussi dans
    `companies.mairie_level`.
- **Une seule écriture de l'entreprise par action** : `updateCompany` une fois (`CompanyWrites.writeProduction`, avec
  `mairieLevel` pour la mairie, qui ajoute `mairie_level = ?` au même `UPDATE`). La production en attente est versée avant de
  débiter, comme partout, et un verrou perdu donne `CONFLIT_REESSAYER`. Les lignes de la ville, le débit, le journal des monnaies
  et l'événement partent dans la même transaction que l'idempotence : si la publication échoue, tout est annulé.
- **Qui paie** : l'acteur, avec son solde personnel (`creditBalance` négatif). Motifs du journal : `zone` (`cid/zone`),
  `batiment` (`cid/buildingId`) et `amelioration` (`cid/buildingId/niveau`). Le PDG et les associés ont les mêmes droits ici.
- **Les bonus** entrent dans le moteur par `economy/EngineFigures` : `Staff.housed`, un salaire réduit pour les logés, et le
  multiplicateur global multiplié par `1 + pourcent / 100` pour `PRODUCTION_DONAR` (`EconomyEngine.withCityBonus`), qui rend
  exactement le calcul d'avant quand le bonus est nul. `LIVRAISON_VITESSE` n'a pas encore de lecteur. `CompanyState.city` et
  `Staff.housed` sont absents des réponses quand le service n'a pas de source de ville.
- **La migration V6** (`V6__ville_parcelle.sql`) ajoute la clé unique `ux_buildings_plot (company_id, x, z)` sur
  `city_buildings` : une parcelle, un bâtiment, même si deux actions arrivent ensemble. Rien d'autre ne change dans le schéma.
- **Les événements** : un `ANNONCE` par action (`payload.action` : `buyZone`, `placeBuilding`, `upgradeBuilding`, avec `companyId`
  et l'acteur), écrit dans la transaction de l'action.
- **Les tests** : `city/CityRulesTest` (zone d'une parcelle, prix, bonus, plafonds, logés), `economy/EngineFiguresCityTest`
  (réponses identiques sans bâtiment à bonus, logement, bonus de production et son plafond),
  `city/CityRoutesTest` (sans base : les trois routes, refus, 400, rejeu, conflit, qui paie), `ContractTest` (les trois
  opérations et le maximum de 16 zones), `DefinitionsLoaderTest` pour `ville.json`, et `city/CitySqlTest` (MariaDB, CI
  seulement, ignoré sans Docker) : lignes, débit, journal et version écrits et annulés ensemble, la clé de V6 sous concurrence,
  `companies.mairie_level`. `InMemoryCityStore` est le faux magasin partagé des tests.
- **Les manques du contrat** (à traiter avec Kr) : pas de code pour « bâtiment pas encore permis par la mairie » (rendu en
  `ZONE_VERROUILLEE`), ni pour « quantité maximale » et « dernier niveau » (rendus en `DEJA_POSSEDE`) ; le maximum de 16 zones est
  écrit en dur dans le chemin de `buyZone` (le test de contrat vérifie qu'il égale le nombre de zones des définitions) ;
  `CompanyState.city` est facultatif ; le catalogue des missions n'a que `collect`, donc aucune mission de ville ; `SiloooJson` est
  tolérant et accepte un entier entre guillemets.

## La boutique côté service

Deux routes, déjà nommées au contrat (qui reste à 32 opérations), et maintenant écrites : `catalog` (`GET /v1/shop/catalog?actor=`) et
`buyItem` (`POST /v1/shop/purchases`, corps : `requestId`, `actor`, `itemId`, et `forXuid` pour un cadeau). S'y ajoute le relevé des
livraisons de Gemmes, qui n'a pas de route : il tourne dans le service. Le service décide de tout (prix, ordre des grades, bonus) ;
les règles de jeu et les tableaux de chiffres sont dans `docs/economie.md`, section « La boutique : Gemmes, grades et Pilier » ; les
chiffres eux-mêmes dans `definitions/boutique.json`, que cette tranche n'a pas touché.

- **Le code** est dans `shop/` : `ShopRules` (les règles pures sur `definitions/` : ordre des grades, catalogue, refus, prolongation
  d'un abonnement, bonus), `ShopService` (`catalog` et `buy`), `ShopRoutes` (la forme des requêtes et `call.idempotent`),
  `ShopStore` et `JdbcShopStore` (le SQL sur `player_ranks`, `player_subscriptions`, `shop_purchases`, `shop_deliveries` et les
  Gemmes), `ShopFigures` (la boutique vue par le moteur : les bonus du PDG), `DeliveryRelief` (le relevé des livraisons, avec
  `launchDeliveryPoll`, la boucle que le module lance). `economy/ShopBonusSource` est l'interface que le moteur lit, comme
  `CityBonusSource` ; `session/PendingDeliveries` est la seule chose que `join` sache du relevé. Sans source, rien ne change.
- **`catalog`** : les six grades dans l'ordre, puis `pilier` et `perso`, avec `owned` et `available` vus de l'`actor`. Un `actor`
  absent, mal formé ou inconnu du service : 400, rien d'écrit.
- **`buyItem`, l'ordre des vérifications** : forme de la requête (400 avant tout : `itemId` vide ou de plus de 48 caractères,
  `forXuid` mal formé), article inconnu ou pas vendu (400), acheteur ou destinataire inconnu (400), puis les Gemmes de l'acheteur sont
  verrouillées et lues, puis les refus du jeu (409 : `DEJA_POSSEDE`, `GRADE_PRECEDENT_REQUIS`, `PAS_ASSEZ_DE_GEMMES` avec la somme qui
  manque), puis seulement les écritures. Un refus n'écrit rien. La réponse est le solde de l'acheteur (`Balances`).
- **Une seule transaction, celle de l'idempotence** : l'achat dans `shop_purchases` (clé unique sur le `requestId`), le débit des
  Gemmes (`creditBalance` négatif, motif `boutique`, référence : l'article), puis le grade (`player_ranks`, source `achat` ou
  `cadeau`) ou l'abonnement prolongé (`player_subscriptions`, `months_total` plus un) avec les Gemmes qu'il rend au destinataire
  (motif `pilier`). Si une écriture échoue, tout est annulé. Aucun événement n'est écrit pour un achat.
- **Le relevé des livraisons** (`DeliveryRelief`) verse les lignes de `shop_deliveries` sans `delivered_at` dont le joueur est connu,
  par XUID puis par pseudo, à intervalle (`Dependencies.deliveries` et `deliveryPoll`, câblés par `Main`) et au `join`
  (`SessionService.join` l'appelle juste après l'inscription du joueur, dans la même transaction). Une livraison se verse en une
  transaction : la ligne marquée (`UPDATE ... WHERE delivered_at IS NULL`, qui doit toucher une ligne), les Gemmes (motif
  `livraison`, référence : la commande), l'événement `GEMMES_RECUES` (`orderId`, `gemmes`, `gemmesTotal`). Un passage vide au plus
  `DeliveryRelief.BATCH` (100) lignes, chacune dans sa transaction : une qui échoue est notée et retentée au passage suivant. Les
  tests passent `deliveries = null` : ni relevé ni tâche de fond.
- **Les bonus** entrent dans le moteur par `economy/EngineFigures` : le multiplicateur global, après celui de la ville, est multiplié
  par `1 + grade / 100` (le plus haut grade du PDG) puis `1 + Pilier / 100` (tant qu'il court) : `EconomyEngine.withShopBonuses`, qui
  rend exactement le calcul d'avant quand les deux sont nuls. C'est le PDG de l'entreprise qui compte (`JdbcShopStore.ceoHoldings`).
  Un achat ne règle pas la production en attente : voir `docs/economie.md`.
- **Aucune migration** : V1 a déjà `shop_deliveries`, `shop_purchases`, `player_ranks` et `player_subscriptions`.
- **Les tests** : `shop/ShopRulesTest` (ordre, catalogue, refus, prolongation, bonus), `shop/ShopRoutesTest` (sans base : les deux
  routes, refus, 400, rejeu, cadeau), `shop/DeliveryReliefTest` (à qui revient une livraison, ce qui reste en attente, versement
  unique, rattrapage au `join`, tâche de fond du module), `economy/EngineFiguresShopTest` (réponses identiques sans grade ni Pilier,
  PDG, bonus non cumulés, bonus versés), `ConfigDeliveryPollTest`, `ContractTest` (les deux opérations, toujours 32), et
  `shop/ShopSqlTest` (MariaDB, CI seulement, ignoré sans Docker) : lignes, journal et solde écrits et annulés ensemble, une
  livraison versée une fois même relevée deux fois ou en même temps, le rattrapage au `join`, deux achats simultanés du même grade.
  `shop/InMemoryShopStore` est le faux magasin partagé des tests.
- **Les manques du contrat** (à traiter avec Kr) : pas de code de refus pour « le Perso demande le grade Ultime » (rendu en
  `GRADE_PRECEDENT_REQUIS`) ; pas de 404 ni de 403 pour un article, un acheteur ou un destinataire inconnu (rendus en 400
  `REQUETE_INVALIDE`) ; aucun événement d'achat n'existe, donc l'add-on ne peut apprendre un grade ou un Pilier acheté ailleurs qu'en
  relisant le catalogue ou en rejoignant ; `Balances` est la seule réponse de `buyItem`, sans le grade ni la fin du Pilier. Le
  contrat n'a pas encore de section d'écarts à part : ces points sont notés ici, comme pour la ville.

## Le site

Le site public `silooo.com` est un module de silooo-core (ADR 0006), pas un service à part. Le paquet est
`com.silooo.core.site`, les contenus et les fichiers sont dans `src/main/resources/site/`. Il tourne dans la même JVM que
l'API, sur son propre port (`SILOOO_SITE_PORT`, 8081 par défaut) : `Main.kt` démarre deux serveurs Ktor, celui de l'API
(`siloooModule`, `/v1`, la clé du serveur) et celui du site (`siteModule`, des pages HTML et des fichiers, sans clé ni JSON). `/v1`
n'existe pas sur le port du site, et le Caddy de SileneMC ne joint que celui-là. Le `Dockerfile` déclare `EXPOSE 8081` ; le compose
ne le publie jamais.

- **Deux modes**, choisis par `SILOOO_SITE_MODE`. En `attente` (le défaut), `/` est la page d'attente et `/mentions/` les mentions.
  En `ouvert`, `/` est l'accueil et les pages de S2 répondent (voir plus bas). Toute autre adresse répond 404, avec une page Silooo. Le mode
  `ouvert` n'est écrit dans aucun fichier du dépôt : seul Kr le pose, dans l'environnement du service.
- **Les contenus** sont des fichiers Markdown de `site/contenu/`, avec un en-tête entre deux lignes `---` : `titre`, `description`,
  `url` (elle finit par `/`) et `modes` (`[attente, ouvert]`). `SiteContent` les lit et les rend en HTML (commonmark-java, HTML brut
  escamoté) une seule fois, au démarrage : un en-tête incomplet arrête le démarrage et nomme le fichier. Un contenu neuf demande
  son nom dans `SiteContent.BUNDLED` et une fonction de rendu dans `site/pages/` (choisie dans `SiteRoutes.kt`).
- **Les pages** sont écrites en DSL HTML (`kotlinx.html`) : `pages/Gabarit.kt` pose la page entière (en-tête, `main`, pied avec la mention
  de Mojang et le lien Mentions), `Attente.kt`, `Accueil.kt`, `Mentions.kt` et `Erreur.kt` posent le `h1` et le contenu. Aucun script.
- **Les pages publiques du mode `ouvert`** (S2) : `/` (accueil), `/rejoindre/`, `/ce-qui-se-vend/`, `/boutique/`, `/vote/`,
  `/mises-a-jour/`, toutes en 404 en `attente`. Mécanique : un contenu Markdown de `site/contenu/` porte le texte écrit à la main ;
  un paragraphe seul `{{nom}}` y marque l'endroit d'un bloc que la page Kotlin de `site/pages/` rend depuis les définitions
  (`Dependencies.definitions.shop`) ou la configuration (`SiteSettings`). **Jamais un chiffre tapé** : prix, bonus, jours,
  Gemmes viennent du JSON, l'adresse et le port de la configuration, et un test le vérifie. Les entrées de mises à jour sont
  `site/contenu/mises-a-jour/AAAA-MM-JJ-<sujet>.md` (`titre`, `description`, `date`), listées dans `UpdateEntries.BUNDLED`.
- **Le wiki** (S3) : `site/contenu/wiki/<sujet>.md` (en-tête des contenus plus `ordre`, entier unique), listés dans
  `WikiContent.BUNDLED` (un test les compare au dossier), rendus par `pages/Wiki.kt` ; les tableaux sont des blocs `{{nom}}`
  de `pages/WikiBlocs.kt`, tirés de `Dependencies.definitions`. Ajouter une page : créer le `.md` (url `/wiki/<sujet>/`, `modes:
  [ouvert]`, un `ordre` libre), l'ajouter à `BUNDLED` et, si elle porte un bloc, à `wikiBlocks`. Le test des chiffres refuse dans
  ces Markdown un nombre suivi de : Donar, Lingots, Gemmes, %, heures, h, €, joueurs, associés (les dates et « époque 1 » passent).
- **Les fichiers** de `site/public/` sont servis sous `/public/` avec un cache d'un jour ; les pages ne se gardent pas. Le logo est une
  copie de `design/logo/` (un test la compare à la source octet pour octet) : on recopie la source, on ne retouche pas la copie.
- **Relire en local**, sur son poste : `SILOOO_SITE_MODE=ouvert ./gradlew run` (avec les variables de la base et de la clé, voir
  « Lancer »), puis `http://localhost:8081/`. Sans la variable, le site est en `attente`.
- **Les tests** (sans base) : `site/SiteModuleTest` (les deux modes, `lang`, un seul `h1`, `title`, `description`, `alt`, les
  en-têtes, le 404, `/v1` absent), `site/SiteContentTest`, `site/SiteLogoTest`, et `ConfigTest` pour les variables.
- **Les textes** vus par les visiteurs passent `tools/anti_ia.py` (contenus Markdown en entier, littéraux du paquet `site`), et chaque
  texte neuf reste à valider par Kr.
- **Le style** (Silooo Atelier pour le web, détail dans `docs/site.md`) : `public/site.css` est l'unique feuille liée, elle importe
  `atelier/tokens.css` puis `atelier.css` (composants `sa-*` et mise en page). Les jetons, les textures (`atelier/textures/`), les
  icônes, Oooscar (`atelier/oooscar/`) et les logos (`logo/`) sont des copies de `design/` ; `SiteAtelierTest` les compare à la
  source octet pour octet et refuse un `url(...)` sans fichier, un arrondi, un dégradé, un flou ou une largeur fixe au-dessus de
  360 px. Les polices (`polices/`, `woff2` latin tirés de `@fontsource/*`, licence OFL) sont servies par le site.
- **Ajouter un composant** : le dessiner d'abord dans `design/` (texture dans `design/textures/`, règle dans `design/composants/`),
  recopier la texture dans `public/atelier/textures/`, écrire la règle dans `atelier.css` avec le même nom `sa-*` et la même bordure
  de `border-image` que `bundle.css`, puis ajouter la texture à la liste du test de copie si besoin.
- **Voir les pages dans un navigateur** sans base : `SILOOO_SITE_RENDER_DIR=/tmp/site ./gradlew test --tests '*SiteRenderToDisk'`
  écrit les pages rendues et `public/` dans ce dossier ; on le sert avec n'importe quel serveur de fichiers (ce test est ignoré
  quand la variable n'est pas posée).
