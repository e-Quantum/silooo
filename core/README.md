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

- Les tests de l'API (clé, heartbeat, 501) et de la configuration tournent **sans base ni Docker** :
  le module Ktor reçoit un faux `FakeDatabase`.
- Le test de migration (`MigrationTest`) lance un conteneur `mariadb:12.3.3` avec Testcontainers,
  applique V1 et compte les tables. **Sans Docker il est ignoré** (« SKIPPED », avec le message
  « Docker absent »), jamais réussi à vide. Il tourne dans la CI GitHub.
- Rapport complet : `core/build/reports/tests/test/index.html`.

## Variables d'environnement

| Variable | Rôle | Obligatoire |
| --- | --- | --- |
| `SILOOO_PORT` | Port d'écoute | non, 8080 par défaut |
| `SILOOO_DB_URL` | URL JDBC de la base `silooo` (`jdbc:mariadb://hôte:3306/silooo`) | oui |
| `SILOOO_DB_USER` | Compte de la base | oui |
| `SILOOO_DB_PASSWORD` | Mot de passe de la base | oui |
| `SILOOO_API_KEY` | La clé attendue dans l'en-tête `X-Silooo-Key` | oui, sauf en mode `migrate` |

Il manque quelque chose : le service le dit en une fois (toutes les variables absentes) et sort avec
le code 78, sans rien démarrer. Ni le mot de passe ni la clé ne s'écrivent dans les journaux.

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

Un paquet par domaine, sous `src/main/kotlin/com/silooo/core/`. Les paquets `session`, `events` et
`economy` ne contiennent qu'un commentaire qui dit ce que la session suivante y met.

| Paquet ou fichier | Contenu |
| --- | --- |
| `Main.kt`, `Config.kt` | Les deux modes, la configuration par variables d'environnement |
| `Application.kt` | Le module Ktor et `Dependencies` : la base et les services lui sont passés, aucun singleton |
| `api/` | Le plugin `X-Silooo-Key` (401), le corps d'erreur du contrat, le filet 501 `PAS_FAIT` |
| `db/` | `Database` (pool et transactions), `HikariDatabase`, `Migrations` |
| `server/` | `POST /v1/server/heartbeat` et l'état en mémoire des serveurs Bedrock |
| `session/`, `events/`, `economy/` | À remplir (voir ci-dessous) |

Toute route `/v1/...` pas encore écrite répond 501 avec `{"reason": "PAS_FAIT"}`, et toute route
`/v1` sans clé valable 401 avec `{"reason": "CLE_INVALIDE"}`. Écrire une route la remplace sans rien
retirer ailleurs. Ces deux codes ne sont pas encore dans l'OpenAPI.

## Ce qui vient après, en phase 1

Chaque route suit la recette [`silooo-route`](../.claude/skills/silooo-route/SKILL.md) : contrat,
test de contrat, puis code.

- `session/join` et `session/leave`, `GET /v1/events` avec curseur.
- L'idempotence par `requestId` (table `api_requests`), le verrou optimiste sur `companies.version`,
  le journal des monnaies dans la même transaction que le solde.
- Le moteur d'économie et les tests dorés. Il lit `definitions/` : l'image ne l'a pas aujourd'hui
  (le contexte est `core/`), à régler dans cette session.
- Les tests de contrat sur les 29 opérations de l'OpenAPI.
- Le compose et le déploiement arrivent en phase 2 ([passation.md](../docs/passation.md)).
