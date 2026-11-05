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
- `contract/ContractTest` lit `api/silooo-core.openapi.yaml` (SnakeYAML) et vérifie les 31 opérations. Quand une
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
| `definitions/` | Le chargeur strict de `definitions/` (économie, usines, boutique, monde, missions) : classes typées et `DefinitionsLoader` |
| `economy/` | Le moteur pur (`EconomyEngine` : prix, production, prestige, hors ligne, découverte des usines ; `Notation` ; `BuyAmount`), ses deux branchements sur `join` (`EngineFigures` implémente `EconomyFigures`, `DefinitionsBootstrap` implémente `CompanyBootstrap`) et les routes `companies/{companyId}` : `CompanyRoutes` (HTTP), `CompanyService` (`getCompany`, `collect`, `buyLevels`, `restoreStep`, `prestigePreview`, `prestige`), `CompanyStore` et `JdbcCompanyStore` (SQL) |
| `server/` | `POST /v1/server/heartbeat` et l'état en mémoire des serveurs Bedrock (`ServerPresence`, la source de présence de `join`) |
| `session/` | `POST /v1/session/join` et `leave` : `SessionService`, `JdbcSessionStore`, `CompanyNaming`, `OnlinePlayers` (qui est connecté ; `server/ServerPresence` l'implémente), et les deux interfaces que le moteur remplit (`EconomyFigures`, `CompanyBootstrap`), dont les implémentations sont dans `economy/` |
| `events/` | `GET /v1/events` (curseur) et `publishEvent` |
| `mission/` | `GET /v1/players/{xuid}/missions` : `MissionRoutes`, `MissionService` (la chaîne de missions, l'avancement, la fin), `MissionStore` et `JdbcMissionStore` (`player_missions`, versement des Lingots, événement) ; `CompanyService.collect` l'appelle |
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
  dont `payload.action` vaut `collect`, `buyLevels`, `restoreStep`, `prestige` ou `mission` ; ajouter un type
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

## Ce qui vient après, en phase 1

Chaque route suit la recette [`silooo-route`](../.claude/skills/silooo-route/SKILL.md) : contrat,
test de contrat, puis code.

- Les routes d'économie qui suivent `restoreStep` (usines à découvrir et à racheter, agence et personnel,
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

- **La commande du jour** : le jour est la date UTC de l'horloge du service (`Dependencies.ordersClock`). La ressource
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
