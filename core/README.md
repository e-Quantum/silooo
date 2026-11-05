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
  les faux en mémoire (`PassThroughDatabase`, `InMemorySessionStore`, `InMemoryApiRequestStore`…) remplacent la base.
- Les tests SQL (`*SqlTest` et `MigrationTest`) lancent un conteneur `mariadb:12.3.3` avec Testcontainers
  (un seul par exécution, vidé avant chaque test : `db/MariaDbSupport`). **Sans Docker ils sont ignorés**
  (« SKIPPED », avec le message « Docker absent »), jamais réussis à vide. Ils tournent dans la CI GitHub : lis le
  journal de l'étape « Vérifier le dépôt » et cherche `SKIPPED` avant de conclure que tout est vert.
- `contract/ContractTest` lit `api/silooo-core.openapi.yaml` (SnakeYAML) et vérifie les 29 opérations. Quand une
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
| `SILOOO_API_REQUESTS_RETENTION_HOURS` | Durée de garde des réponses d'idempotence (`api_requests`), de 1 à 8760 heures | non, 24 par défaut |

Il manque quelque chose : le service le dit en une fois (toutes les variables absentes) et sort avec
le code 78, sans rien démarrer. Ni le mot de passe ni la clé ne s'écrivent dans les journaux.

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
docker build -t silooo-core core     # le contexte est le dossier core/
docker run --rm --env-file <fichier d'environnement> silooo-core migrate
docker run --rm --env-file <fichier d'environnement> silooo-core
```

Construction par le wrapper Gradle sur `eclipse-temurin:17-jdk`, exécution sur
`eclipse-temurin:17-jre`, utilisateur non root, `-XX:MaxRAMPercentage=75`. La CI la construit à
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
| `server/` | `POST /v1/server/heartbeat` et l'état en mémoire des serveurs Bedrock |
| `session/` | `POST /v1/session/join` et `leave` : `SessionService`, `JdbcSessionStore`, `CompanyNaming`, et les deux interfaces que le moteur remplit (`EconomyFigures`, `CompanyBootstrap`) |
| `events/` | `GET /v1/events` (curseur) et `publishEvent` |
| `economy/` | À remplir par le moteur d'économie (voir ci-dessous) |

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

`join` rend des chiffres qu'il ne calcule pas (revenu par heure, multiplicateur, seuil, prix du prochain niveau,
palier, Donar hors ligne) : ils viennent de l'interface `EconomyFigures`. Par défaut c'est `NotYetComputed`, qui
rend zéro partout. À la création d'une entreprise, `CompanyBootstrap` donne les usines de départ et où poser la
case d'archipel suivante ; par défaut `EmptyBootstrap` (aucune usine, case N en (N, 0)). Le moteur d'économie
branche les vraies : `Dependencies(economyFigures = ..., companyBootstrap = ...)`.

Le nom d'entreprise donné au premier passage est provisoire : « Entreprise de <gamertag> », avec un numéro si le
nom est pris. À valider par Kr (texte vu par les joueurs).

## Ce qui vient après, en phase 1

Chaque route suit la recette [`silooo-route`](../.claude/skills/silooo-route/SKILL.md) : contrat,
test de contrat, puis code.

- Le moteur d'économie et les tests dorés. Il lit `definitions/` : l'image ne l'a pas aujourd'hui
  (le contexte est `core/`), à régler dans cette session. Il branche `EconomyFigures` et `CompanyBootstrap`.
- Les routes de `getCompany`, `collect`, `buyLevels`, `restoreStep` et les suivantes, qui se servent des quatre aides
  ci-dessus.
- Le compose et le déploiement arrivent en phase 2 ([passation.md](../docs/passation.md)).
