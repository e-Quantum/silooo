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
- `contract/ContractTest` lit `api/silooo-core.openapi.yaml` (SnakeYAML) et vérifie les 34 opérations. Quand une
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
| `SILOOO_SITE_MODE` | Le mode du site : `attente`, `beta` (tout sauf la vitrine) ou `ouvert` (toute autre valeur arrête le service, code 78) | non, `attente` par défaut |
| `SILOOO_SITE_URL` | L'adresse publique du site, qui commence par `http` | non, `https://silooo.com` par défaut |
| `SILOOO_SITE_URL_DISCORD` | Le lien Discord affiché sur la page d'accueil et d'attente | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_SITE_ADRESSE_JEU` | L'adresse du serveur de jeu affichée sur l'accueil et Rejoindre (sans port) | non, `play.silooo.com` par défaut |
| `SILOOO_SITE_PORT_JEU` | Le port du jeu affiché, entier de 1 à 65535 (le défaut est celui de `BDS_PORT` dans `deploy/compose.yml`, ADR 0004) | non, `19142` par défaut |
| `SILOOO_SITE_URL_BOUTIQUE` | L'adresse où s'achètent les Gemmes, qui commence par `http` | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_SITE_SITES_DE_VOTE` | Les sites de vote, `nom\|url` séparés par des points-virgules (une entrée mal formée arrête le service) | non, aucun site par défaut |
| `SILOOO_SITE_URL_SILENEMC` | Le site de SileneMC, dans « Voir aussi » au pied de chaque page, qui commence par `http` | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_SITE_URL_HUBYY` | Le site de Hubyy, à côté, qui commence par `http` | non, pas de lien tant qu'elle n'est pas posée |
| `SILOOO_DISCORD_CLIENT_ID` | L'identifiant de l'application Discord de « Relier Discord » ; va avec le secret (une seule des deux arrête le service) | non, pas de bouton « Relier Discord » sans elle |
| `SILOOO_DISCORD_CLIENT_SECRET` | Le secret de cette application ; ne s'affiche jamais | non, voir ci-dessus |
| `SILOOO_XBOX_CLIENT_ID` | L'identifiant de l'application Azure de la connexion Xbox du site (comptes Microsoft personnels) ; va avec le secret (une seule des deux arrête le service) | non, pas de porte Xbox sans elle |
| `SILOOO_XBOX_CLIENT_SECRET` | Le secret de cette application ; ne s'affiche jamais | non, voir ci-dessus |
| `SILOOO_SITE_TRUSTED_PROXY` | `true` : l'adresse du visiteur se lit dans `X-Forwarded-For` (le Caddy de SileneMC la pose) ; `false` ou absente : l'adresse vue par silooo-core | non, `false` par défaut |
| `SILOOO_PAYNOW_WEBHOOK_SECRET` | La clé de la signature des webhooks de PayNow (`POST /paynow/webhook`) ; ne s'affiche jamais | non, sans elle le webhook répond 503 |
| `SILOOO_VOTIFIER_TOKEN` | Le jeton de Votifier 2, avec lequel Hubyy signe ses votes ; ne s'affiche jamais | non, sans lui rien n'écoute et les votes de Hubyy ne sont pas reçus |
| `SILOOO_VOTIFIER_PORT` | Le port TCP de la réception des votes, de 1 à 65535, différent de `SILOOO_PORT` et de `SILOOO_SITE_PORT` | non, 8192 par défaut ; lu seulement quand le jeton est posé |
| `SILOOO_DISCORD_ACTIF` | Le bot Discord Oooscar (ADR 0007) : `non`, `a-blanc` (lit l'écart avec le plan sans rien changer) ou `oui` (applique le plan) ; toute autre valeur refuse le démarrage | non, `non` par défaut |
| `SILOOO_DISCORD_TOKEN` | Le jeton du bot ; ne s'affiche jamais | oui dès que `SILOOO_DISCORD_ACTIF` n'est pas `non` |
| `SILOOO_DISCORD_SERVEUR` | L'identifiant numérique du serveur Discord que le bot règle | oui dès que `SILOOO_DISCORD_ACTIF` n'est pas `non` |
| `SILOOO_DISCORD_PHASE` | La phase du serveur : `attente`, `beta` ou `ouvert` ; chaque entrée du plan porte la phase où elle apparaît | non, `attente` par défaut |
| `SILOOO_DISCORD_PUBLIC` | Le QG est ouvert au public : `non` (les messages privés ne partent qu'au staff, les mentions de rôle ne visent que ses rôles) ou `oui` ; toute autre valeur refuse le démarrage | non, `non` par défaut |

Il manque quelque chose : le service le dit en une fois (toutes les variables absentes) et sort avec
le code 78, sans rien démarrer. Ni le mot de passe, ni la clé, ni le jeton Votifier ne s'écrivent dans les journaux.

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
| `definitions/` | Le chargeur strict de `definitions/` (économie, usines, boutique, monde, missions, personnel, votes, pass) : classes typées et `DefinitionsLoader` |
| `economy/` | Le moteur pur (`EconomyEngine` : prix, production, prestige, hors ligne, découverte des usines ; `Notation` ; `BuyAmount`), ses deux branchements sur `join` (`EngineFigures` implémente `EconomyFigures`, `DefinitionsBootstrap` implémente `CompanyBootstrap`) et les routes `companies/{companyId}` : `CompanyRoutes` (HTTP), `CompanyService` (`getCompany`, `collect`, `buyLevels`, `restoreStep`, `prestigePreview`, `prestige`), `CompanyStore` et `JdbcCompanyStore` (SQL) |
| `server/` | `POST /v1/server/heartbeat` et l'état en mémoire des serveurs Bedrock (`ServerPresence`, la source de présence de `join`) |
| `session/` | `POST /v1/session/join` et `leave` : `SessionService`, `JdbcSessionStore`, `CompanyNaming`, `OnlinePlayers` (qui est connecté ; `server/ServerPresence` l'implémente), et les deux interfaces que le moteur remplit (`EconomyFigures`, `CompanyBootstrap`), dont les implémentations sont dans `economy/` |
| `events/` | `GET /v1/events` (curseur) et `publishEvent` |
| `mission/` | `GET /v1/players/{xuid}/missions` : `MissionRoutes`, `MissionService` (la chaîne de missions, l'avancement, la fin), `MissionStore` et `JdbcMissionStore` (`player_missions`, versement des Lingots, événement) ; `CompanyService.collect` l'appelle |
| `shop/` | `GET /v1/shop/catalog` et `POST /v1/shop/purchases` : `ShopRoutes`, `ShopService` (`catalog`, `buy`), `ShopRules` (grades, abonnements, cosmétiques, boost, bonus), `ShopStore` et `JdbcShopStore` (`player_ranks`, `player_subscriptions`, `player_cosmetics`, `server_boosts`, `shop_purchases`, `shop_deliveries`, `site_registrations` en lecture, Gemmes), `ShopFigures` (les bonus de Donar du PDG et le boost collectif que le moteur lit), `DeliveryRelief` (le relevé des livraisons de Gemmes, à intervalle et au `join`) et `PioneerTitle` (le titre Pionnier, donné au `join`) |
| `staff/` | `GET /v1/companies/{companyId}/agency`, `POST .../agency/{slot}/hire`, `PATCH` et `DELETE .../staff/{staffId}` : `StaffRoutes` (HTTP), `StaffService` (`getCandidates`, `hire`, `assignStaff`, `fireStaff`), `AgencyDraw` (la liste du jour), `StaffStore` et `JdbcStaffStore` (`staff`, `agency_candidates`) |
| `vote/` | `GET /v1/votes/today` et la réception des votes de Hubyy : `VoteRoutes` (HTTP), `VotifierProtocol` et `VotifierServer` (Votifier 2, en TCP), `VoteService` (`today`, `receive`, `relieve`), `VoteRules` (la série et le rang du jour, purs), `VoteStore` et `JdbcVoteStore` (`server_votes`, le versement en Lingots, l'événement) ; `session/PendingVotes` est ce que `join` en sait |
| `pass/` | `GET /v1/pass` et `POST /v1/pass/claims` : `PassRoutes` (HTTP), `PassService` (`state`, `claim`, les points du prestige, de la commande et des missions, la piste premium de la boutique), `PassRules` (saison, refus, `PassState`, purs), `PassStore` et `JdbcPassStore` (`season_pass`, `season_claims`, Lingots, `player_cosmetics`) ; `CompanyService`, `OrderService`, `MissionService` et `ShopService` l'appellent |
| `discord/` | Le bot Discord Oooscar (ADR 0007, docs/discord.md) : `BotConfig` (variables, jeton masqué), `ServerPlan` (le plan `resources/discord/plan-serveur.json`), `DiscordServer` (l'interface, la seule chose que le reste connaît), `PlanApplier` (crée ce qui manque, ne supprime jamais), `DiscordBot` (le fil du bot), `BoundedQueue`, `DiscordEvent` et `Reply` (les événements sans JDA), `BotMessage` (cartes, boutons, menus, formulaires, commandes), `BotRuntime` (la boucle à un fil, les minuteries, les fonctions `Feature`), `DiscordDefinitions` (`definitions/discord.json`), `OnboardingState`, `OnboardingPlanner` et `OnboardingApplier` (l'accueil guidé tiré du plan, posé après les salons), `MemberStore` et `JdbcMemberStore` (`discord_members`), `SpoilStore` et `JdbcSpoilStore` (`discord_spoils`), `AdmissionStore` et `JdbcAdmissionStore` (`discord_admissions`), `BotMessageStore` et `JdbcBotMessageStore` (`discord_bot_messages`, la façon commune de retrouver un message à clé du bot après un redémarrage), `TicketStore` et `JdbcTicketStore` (`discord_tickets`), `XpStore` et `JdbcXpStore` (`discord_chat_xp`, `discord_xp_messages`), `GiveawayStore` et `JdbcGiveawayStore` (`discord_giveaways`, `discord_giveaway_entries`, `discord_prizes`), `BotJournal` (la ligne dans `#journal-du-bot`), `WelcomeFeature`, `StatusFeature`, `InfoVoiceFeature`, `SpoilFeature`, `AdmissionFeature`, `AnnounceFeature`, `TicketFeature`, `VoiceRoomFeature`, `XpFeature` et `GiveawayFeature` (les fonctions du QG), `BotFeatures` (leur assemblage en production) ; `discord/rest/DiscordRest` parle à Discord en REST pour ce que JDA ne couvre pas (l'accueil guidé) ; `discord/jda/` est le seul paquet qui importe JDA |
| `wording/` | Le garde anti-IA des textes : `AntiIa.check(text)` rend les trouvailles, `AntiIa.summary` les met en une phrase ; mêmes règles et mêmes noms que `tools/anti_ia.py`. `/annonce` s'en sert. Il est hors de `discord/` parce que le garde du dépôt relit les chaînes de ce paquet et refuserait les mots bannis que sa liste contient |
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
  `era`, `stars`, `lingotsTotal` et la part du membre (`lingots`). Un `PRESTIGE_ENTREPRISE` s'y ajoute pour tout le serveur
  (`xuid` nul) : `companyId`, `companyName`, `lingots` (le total avant partage) et `prestigeCount` (la course qui vient de
  finir). Il ne dit pas la part de chacun ; c'est le bot Discord qui le lit ([Discord côté service](#discord-côté-service)).
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
- **L'alerte** : la dernière étape de `restoreStep` publie un `ANNONCE` `sansCourant` quand elle fait passer l'entreprise à
  l'état sans courant ([Discord côté service](#discord-côté-service)).

## Discord côté service

Le bot Discord est à la direction du Discord ([docs/discord.md](../docs/discord.md)) : il lit `game_events` avec son propre curseur.
Le service publie pour lui `PRESTIGE_ENTREPRISE` (tout le serveur), `BOOST_COLLECTIF`, l'`ANNONCE` `voteParty` et l'`ANNONCE` `sansCourant`,
dont les champs sont décrits dans l'OpenAPI (schéma `GameEvent`, opérations `prestige` et `restoreStep`).

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

Deux routes, déjà nommées au contrat (qui reste à 34 opérations), et maintenant écrites : `catalog` (`GET /v1/shop/catalog?actor=`) et
`buyItem` (`POST /v1/shop/purchases`, corps : `requestId`, `actor`, `itemId`, et `forXuid` pour un cadeau). S'y ajoute le relevé des
livraisons de Gemmes, qui n'a pas de route : il tourne dans le service. Le service décide de tout (prix, ordre des grades, bonus) ;
les règles de jeu et les tableaux de chiffres sont dans `docs/economie.md`, section « La boutique : Gemmes, grades, Pilier, cosmétiques
et boost » ; les chiffres eux-mêmes dans `definitions/boutique.json` (les cosmétiques, le boost collectif et les noms sont **à valider
par Kr**). La boutique vend les grades, les deux abonnements, les cosmétiques et le boost collectif ; le titre Pionnier se donne au `join`.

- **Le code** est dans `shop/` : `ShopRules` (les règles pures sur `definitions/` : ordre des grades, catalogue, refus, prolongation
  d'un abonnement, bonus), `ShopService` (`catalog` et `buy`), `ShopRoutes` (la forme des requêtes et `call.idempotent`),
  `ShopStore` et `JdbcShopStore` (le SQL sur `player_ranks`, `player_subscriptions`, `shop_purchases`, `shop_deliveries` et les
  Gemmes), `ShopFigures` (la boutique vue par le moteur : les bonus du PDG), `DeliveryRelief` (le relevé des livraisons, avec
  `launchDeliveryPoll`, la boucle que le module lance), `PioneerTitle` (le titre Pionnier). `economy/ShopBonusSource` est l'interface que
  le moteur lit, comme `CityBonusSource` ; `session/PendingDeliveries` est la seule chose que `join` sache du relevé, et
  `session/PendingTitles` la seule qu'il sache des titres. Sans source, rien ne change.
- **`catalog`** : les six grades dans l'ordre, `pilier` et `perso`, les quinze cosmétiques en vente (dans l'ordre de `boutique.json`),
  puis `boost-collectif`, avec `owned` et `available` vus de l'`actor` (le boost est toujours `available`, jamais `owned`). Le titre
  Pionnier n'y est pas. Un `actor` absent, mal formé ou inconnu du service : 400, rien d'écrit.
- **`buyItem`, l'ordre des vérifications** : forme de la requête (400 avant tout : `itemId` vide ou de plus de 48 caractères,
  `forXuid` mal formé), article inconnu ou pas vendu (400), acheteur ou destinataire inconnu (400), puis les Gemmes de l'acheteur sont
  verrouillées et lues, puis les refus du jeu (409 : `DEJA_POSSEDE`, `GRADE_PRECEDENT_REQUIS`, `PAS_ASSEZ_DE_GEMMES` avec la somme qui
  manque), puis seulement les écritures. Un refus n'écrit rien. La réponse est le solde de l'acheteur (`Balances`). Deux cas de plus
  sont des 400 : `pionnier` (hors vente, comme un article inconnu) et `boost-collectif` avec `forXuid` (le boost est pour tout le serveur).
- **Une seule transaction, celle de l'idempotence** : l'achat dans `shop_purchases` (clé unique sur le `requestId`), le débit des
  Gemmes (`creditBalance` négatif, motif `boutique`, référence : l'article), puis le grade (`player_ranks`, source `achat` ou
  `cadeau`), le cosmétique (`player_cosmetics`, source `boutique`, cadeau compris), l'abonnement prolongé (`player_subscriptions`,
  `months_total` plus un) avec les Gemmes qu'il rend au destinataire (motif `pilier`), ou le boost (une ligne de `server_boosts`, V9, et
  l'événement `BOOST_COLLECTIF`). Si une écriture échoue, tout est annulé. Seul le boost écrit un événement.
- **Le boost collectif** : sa fenêtre commence à l'achat, ou à la fin du dernier boost réservé (`ShopRules.scheduleBoost`) ; la lecture de
  cette fin est un `SELECT ... FOR UPDATE` (`JdbcShopStore.lastBoostEnd`), donc deux achats en même temps se suivent, ou l'un reçoit un
  conflit transitoire que `Idempotency` rend en `CONFLIT_REESSAYER`. Le moteur lit le boost qui court dans `ShopBonuses.boostPercent`
  (rendu par `ShopFigures`, via `ShopBonusSource.of`), et `EconomyEngine.withShopBonuses(multiplicateur, grade, Pilier, boost)` le multiplie, pour toutes les entreprises.
  Le boost qui court au règlement vaut pour toute la période réglée, hors ligne comprise (à valider par Kr).
- **Le titre Pionnier** : `PioneerTitle` (branché sur `Dependencies.pendingTitles` par `Main`) lit `site_registrations` par la clé
  `SiteRegistrations.key` du gamertag ; `session/SessionService.join` l'appelle après le relevé des livraisons et des votes, dans la
  même transaction. Il écrit `player_cosmetics` (source `site`) et une `ANNONCE` (`payload.action` = `pionnier`), une seule fois. La
  réponse de `join` rend `cosmetics`, la liste de ce que le joueur possède.
- **Le relevé des livraisons** (`DeliveryRelief`) verse les lignes de `shop_deliveries` sans `delivered_at` dont le joueur est connu,
  par XUID puis par pseudo, à intervalle (`Dependencies.deliveries` et `deliveryPoll`, câblés par `Main`) et au `join`
  (`SessionService.join` l'appelle juste après l'inscription du joueur, dans la même transaction). Une livraison se verse en une
  transaction : la ligne marquée (`UPDATE ... WHERE delivered_at IS NULL`, qui doit toucher une ligne), les Gemmes (motif
  `livraison`, référence : la commande), l'événement `GEMMES_RECUES` (`orderId`, `gemmes`, `gemmesTotal`). Un passage vide au plus
  `DeliveryRelief.BATCH` (100) lignes, chacune dans sa transaction : une qui échoue est notée et retentée au passage suivant. Les
  tests passent `deliveries = null` : ni relevé ni tâche de fond.
- **Les bonus** entrent dans le moteur par `economy/EngineFigures` : le multiplicateur global, après celui de la ville, est multiplié
  par `1 + grade / 100` (le plus haut grade du PDG), `1 + Pilier / 100` (tant qu'il court) puis `1 + boost / 100` (le boost collectif qui
  court) : `EconomyEngine.withShopBonuses`, qui rend exactement le calcul d'avant quand les trois sont nuls. C'est le PDG de l'entreprise qui compte (`JdbcShopStore.ceoHoldings`).
  Un achat ne règle pas la production en attente : voir `docs/economie.md`.
- **Une migration, V9** (`V9__boosts_collectifs.sql`) : la table `server_boosts`. V1 a déjà `shop_deliveries`, `shop_purchases`,
  `player_ranks`, `player_subscriptions` et `player_cosmetics` ; V7 a `site_registrations`. **Le bot Discord a pris V10** (`V10__discord.sql`, ses tables `discord_*`) : V8, réservée au départ, reste un trou, parce que V9 est
  partie sur `main` avant elle et que Flyway, sans `outOfOrder`, aurait refusé V8 après V9.
- **Les tests** : `shop/ShopRulesTest` (26 : ordre, catalogue, refus, prolongation, bonus, enchaînement des boosts), `shop/ShopRoutesTest`
  (46, sans base : les deux routes, cosmétiques, boost, refus, 400, rejeu, cadeau), `shop/PioneerTitleTest` (9 : à qui, une fois, ce que
  `join` montre), `shop/DeliveryReliefTest` (18 : à qui revient une livraison, la clé du pseudo, ce qui reste en attente, versement
  unique, rattrapage au `join`, tâche de fond du module), `economy/EngineFiguresShopTest` (17 : réponses identiques sans grade, Pilier
  ni boost, PDG, bonus non cumulés, boost de tout le serveur, bonus versés), `definitions/DefinitionsLoaderTest` (les nouvelles clés et
  leurs refus), `ConfigDeliveryPollTest`, `ContractTest` (les opérations, toujours 33, avec les deux achats et le titre validés contre les
  schémas), et `shop/ShopSqlTest` (49, MariaDB, CI seulement, ignoré sans Docker) : lignes, journal et solde écrits et annulés ensemble,
  une livraison versée une fois même relevée deux fois ou en même temps, le pseudo comparé par la clé du site, le rattrapage au `join`,
  deux achats simultanés du même grade ou du même cosmétique, des boosts achetés en même temps qui se suivent sans se chevaucher, le
  titre Pionnier donné une fois. `shop/InMemoryShopStore` est le faux magasin partagé des tests. Les treize cibles du simulateur ne
  bougent pas.
- **Les manques du contrat** (à traiter avec Kr) : pas de code de refus pour « le Perso demande le grade Ultime » (rendu en
  `GRADE_PRECEDENT_REQUIS`) ; pas de 404 ni de 403 pour un article, un acheteur ou un destinataire inconnu (rendus en 400
  `REQUETE_INVALIDE`) ; aucun événement d'achat n'existe, donc l'add-on ne peut apprendre un grade ou un Pilier acheté ailleurs qu'en
  relisant le catalogue ou en rejoignant ; `Balances` est la seule réponse de `buyItem`, sans le grade ni la fin du Pilier. Le
  contrat n'a pas encore de section d'écarts à part : ces points sont notés ici, comme pour la ville.
- **Les manques de cette tranche** : aucune route ne rend le boost qui court (le jeu l'apprend par l'événement `BOOST_COLLECTIF`, qui
  part à l'achat, même pour un boost en attente) ; `site_registrations.pioneer` vaut vrai pour toute inscription, avant comme après
  l'ouverture, puisque le site n'écrit pas la colonne ; `ShopStore.ceoHoldings` ne lit pas les cosmétiques (aucun n'a de bonus) ;
  la comparaison des pseudos par la clé du site relit `players` à chaque livraison (un index fonctionnel suffira si la table grossit).

## Les votes côté service

Une route, `todayVotes` (`GET /v1/votes/today?xuid=`), ajoutée au contrat (qui passe à 34 opérations), et une entrée qui n'est pas HTTP :
la réception des votes de Hubyy par Votifier 2, en TCP. Le service décide de tout (ce qui est versé, la série, la Vote party) ; l'add-on
n'a qu'à afficher ce que `todayVotes` rend. Les règles et le tableau des chiffres sont dans `docs/economie.md`, section « Les votes » ; les
chiffres eux-mêmes dans `definitions/vote.json`, **tous à valider par Kr**. Le déploiement (port, jeton, déclaration sur Hubyy) est dans
`docs/deploiement.md`.

- **Le code** est dans `vote/` : `VotifierProtocol` (le protocole, en fonctions pures : le défi, le décodage d'un message, la signature
  HMAC-SHA256 comparée à temps constant avant que le `payload` soit lu, les refus nommés), `VotifierServer` (le serveur TCP et
  `launchVotifier`, que `Main` lance seulement si le jeton est posé), `VoteRules` (la série et le rang du jour, purs), `VoteService`
  (`todayVotes`, `receive`, `relieve`), `VoteStore` et `JdbcVoteStore` (le SQL sur `server_votes`, le crédit en Lingots, l'événement),
  `VoteRoutes` (la forme de la requête). `session/PendingVotes` est la seule chose que `join` sache des votes. Sans cette source, rien ne
  change.
- **Votifier** : sans `SILOOO_VOTIFIER_TOKEN`, aucun port ne s'ouvre et le démarrage écrit une ligne d'information. Avec le jeton, le
  service écoute sur `SILOOO_VOTIFIER_PORT` (8192 par défaut). Il refuse un message trop long (4096 octets), sans la magie `0x733A`, mal
  signé, avec un autre défi que celui de la connexion ou tronqué, avec une cause nommée dans la réponse `error`. Il tient au plus 16
  connexions et coupe une lecture qui dépasse 5 secondes. Un échec de la base répond `Unavailable` : le site peut renvoyer le vote. Le
  jeton ne s'écrit nulle part : `VotifierSettings` le masque, et aucun message d'erreur de configuration ne le cite.
- **Sans migration** : `server_votes` existe depuis `V1__init.sql` et suffit (une ligne par vote, `rewarded_at` pour la marque de
  versement). Aucune dépendance de plus : `javax.crypto` et la bibliothèque standard font la signature.
- **Un vote** : la ligne, le crédit de Lingots (`creditBalance`, motif `vote`, référence : l'id de la ligne), la marque `rewarded_at` et
  l'événement `ANNONCE` tiennent en une seule transaction. La Vote party (motif `vote-party`, référence : le jour) est écrite dans la même
  transaction que le vote de rang exact. Les réceptions passent une par une (`Mutex`) et, sur un conflit transitoire de MariaDB, la
  transaction est rejouée trois fois au plus.
- **`join`** verse les votes en attente du pseudo, du plus ancien au plus récent, cent au plus, après les livraisons de Gemmes et dans la
  même transaction : le solde de la réponse les montre. Un vote rattrapé compte dans la série de son propre jour et ne lance jamais la
  Vote party.
- **`todayVotes`** : lecture seule, rien d'écrit. Sans `xuid`, la part du joueur est à zéro ; un `xuid` mal formé donne 400.

Les tests (aucun ne demande de base, sauf `VoteSqlTest`, ignoré sans Docker comme les autres `*SqlTest`) : `VotifierProtocolTest` (le
protocole et son refus de tout ce qui est mal formé), `VotifierServerTest` (le serveur sur un vrai port local, avec un faux
gestionnaire), `VoteRulesTest`, `VoteServiceTest` (la série, la Vote party, le rattrapage, l'échec d'une écriture), `VoteRoutesTest`,
`VoteJoinTest` (le rattrapage par HTTP), `VoteSqlTest` (le SQL réel : un seul versement même avec deux `join` en même temps, l'annulation après une panne, le rang du jour et la fête une seule fois),
et `ConfigTest` pour les deux variables. Un faux dépôt (`InMemoryVoteStore`) remplace la base ailleurs. Les chiffres attendus viennent de
`definitions/vote.json`, jamais d'un littéral.

Ce que le contrat et les docs ne disent pas encore :

- `docs/README.md` et `docs/game-design.md` comptent encore 32 opérations ; la direction les met à jour à la fusion.
- Votifier est du TCP : il n'a pas sa place dans `silooo-core.openapi.yaml`. Il est décrit ici et dans `docs/economie.md`.
- Le contrat laisse `payload` libre dans `ANNONCE` : l'add-on reconnaît un vote à `payload.action` = `vote` et la fête à `voteParty`.
- `todayVotes` rend `streakGoalDays` et `mine.streak` mais pas le nombre de jours avant le bonus : l'add-on le calcule (objectif moins
  série, modulo l'objectif).

## Le pass côté service

Deux routes que le contrat déclarait déjà sans les décrire, `getPass` (`GET /v1/pass?actor=`) et `claimPass` (`POST /v1/pass/claims`) : le
contrat reste à 34 opérations, avec les schémas `PassState` et `PassClaimed` et quatre codes de refus de plus. Un article de plus dans la
boutique, `pass-premium`. Le service décide de tout (les points, ce qui est
versé, le prix) ; l'add-on n'a qu'à afficher `PassState`. Les règles et le tableau des chiffres sont dans `docs/economie.md`, section
« Le pass de saison » ; les chiffres eux-mêmes dans `definitions/pass.json`, **tous à valider par Kr**.

- **Le code** est dans `pass/` : `PassRoutes` (la forme de la requête), `PassService` (`state`, `claim`, les trois points d'entrée des
  points, les deux gestes que demande la boutique), `PassRules` (les règles pures : saison, refus, forme de `PassState`), `PassStore` et
  `JdbcPassStore` (le SQL sur `season_pass` et `season_claims`, le crédit en Lingots, `player_cosmetics`). `Dependencies.pass` peut être
  nul : sans lui, les deux routes ne sont pas posées (le filet 501 répond) et la boutique ne vend pas `pass-premium`, ce qui laisse les
  tests qui n'en ont pas besoin tels quels.
- **Sans migration** : `season_pass` et `season_claims` existent depuis `V1__init.sql` et suffisent. Aucune dépendance de plus.
- **Les points** ne passent par aucune route. `CompanyService.prestige` appelle `onPrestige` pour chaque associé, `OrderService` appelle
  `onOrderCompleted` pour chaque contributeur quand la commande se termine, `MissionService` appelle `onMissionCompleted`. Chaque appel est
  fait dans la transaction de l'action, après le crédit de Lingots de la même action, et n'écrit rien hors saison.
- **`claimPass`** : les cas de 400 (palier ou piste sans récompense, acteur inconnu) tombent avant toute écriture, puis les verrous
  (la ligne de soldes du joueur, puis celle du pass), puis les refus du jeu (`DEJA_RECLAME`, `PREMIUM_REQUIS`, `PALIER_NON_ATTEINT` avec
  `missing`), puis le palier est noté (clé primaire de `season_claims`) et la récompense donnée : des Lingots (`creditBalance`, motif
  `pass`, référence `saison:palier:piste`) ou un cosmétique (`player_cosmetics`, source `pass`, un cosmétique déjà possédé n'empêche rien).
  Un `requestId` rejoué rend la même réponse, par `call.idempotent`.
- **L'ordre des verrous** est toujours : la ligne de soldes du joueur, puis la ligne de son pass. C'est celui des actions qui versent des
  Lingots avant de compter des points. Un achat offert (`forXuid`) prend les verrous du destinataire après ceux de l'acheteur : une
  impasse très rare avec un prestige du destinataire reste possible, MariaDB la détecte, une des deux transactions est annulée et le
  client reçoit `CONFLIT_REESSAYER`.
- **`buyItem` `pass-premium`** : le prix vient de `pass.json`, jamais du code de la boutique ; refusé hors saison (`SAISON_INACTIVE`) et
  si le destinataire l'a déjà (`DEJA_POSSEDE`) ; l'achat et l'ouverture de la piste tiennent dans la même transaction. `catalog` le
  montre en dernier, avec `owned` et `available`.
- **Les quatre codes de refus** que le pass ajoute à `Refused.reason` : `PALIER_NON_ATTEINT`, `DEJA_RECLAME`, `PREMIUM_REQUIS`,
  `SAISON_INACTIVE` (déclarés dans `api/silooo-core.openapi.yaml`, repris par `RefusalCodes.ALL`, que `ContractTest` compare à l'enum).

Les tests : `PassRulesTest` (les règles, sans base), `PassRoutesTest` (HTTP avec un faux dépôt : état, réclamation, rejeu, refus, 400,
achat, cadeau, catalogue), `PassPointsTest` (les trois sources de points, hors saison, rejeu), `PassSqlTest` (le SQL réel, la
concurrence, le parcours HTTP ; ignoré sans Docker comme les autres `*SqlTest`), `DefinitionsLoaderTest` (le fichier et ses refus) et
`ContractTest` (conformité des deux routes et des refus au contrat). Un faux dépôt (`InMemoryPassStore`) remplace la base ailleurs. Les
chiffres attendus viennent de `definitions/pass.json`, jamais d'un littéral.

Ce que le contrat et les docs ne disent pas encore :

- Le contrat dit `tier` entier, mais comme partout dans le service, un nombre écrit entre guillemets (`"1"`) est lu comme un nombre : la
  négociation de contenu est celle de toutes les routes.
- Les cinq cosmétiques de `pass.json` (`s1-fanion`, `s1-lanterne`, `s1-chat-livreur`, `s1-sillage-dore`, `s1-titre-fondateur`) sont
  déclarés dans `boutique.json` (`cosmetiques`, `achetable` à faux, prix 0), avec des noms **à valider par Kr**. Le chargeur
  (`DefinitionsChecks`) et `tools/verifier_definitions.mjs` refusent un cosmétique du pass que `boutique.json` ne déclare pas ou
  qu'elle vend (deux cas dans `tools/tests/definitions-invalides/pass-cosmetique-*`). La règle « pas vendu » n'est pas propre au titre
  Pionnier : `ShopRules.cosmetics` ne garde que les cosmétiques `achetable`, donc `buyItem` refuse (400) tout autre, pour soi et en
  cadeau, et le catalogue ne le liste jamais, possédé ou non ; `claimPass` écrit `player_cosmetics` (source `pass`) sans passer par
  cette règle, et `join` les montre dans `cosmetics`.
- `PassState` ne dit pas combien de paliers manquent ni les points du palier suivant : l'add-on les lit dans `tiers`.

## Le site

Le site public `silooo.com` est un module de silooo-core (ADR 0006), pas un service à part. Le paquet est
`com.silooo.core.site`, les contenus et les fichiers sont dans `src/main/resources/site/`. Il tourne dans la même JVM que
l'API, sur son propre port (`SILOOO_SITE_PORT`, 8081 par défaut) : `Main.kt` démarre deux serveurs Ktor, celui de l'API
(`siloooModule`, `/v1`, la clé du serveur) et celui du site (`siteModule`, des pages HTML et des fichiers, sans clé ni JSON). `/v1`
n'existe pas sur le port du site, et le Caddy de SileneMC ne joint que celui-là. Le `Dockerfile` déclare `EXPOSE 8081` ; le compose
ne le publie jamais.

- **Trois modes**, choisis par `SILOOO_SITE_MODE`. En `attente` (le défaut), `/` est la page d'attente de la maquette, `/actus/` les
  actus, `/discord/` la page du Discord (le bouton vers `SILOOO_SITE_URL_DISCORD` quand elle est posée), et les quatre pages légales répondent (`/mentions-legales/`, `/cgv/`, `/confidentialite/`, `/parents/`, contenus
  `site/contenu/<nom>.md` rendus par `pages/Legal.kt`, brouillons à relire par Kr). En `beta`, tout répond sauf la vitrine : `/` reste la page d'attente. En `ouvert`, `/` est l'accueil et les pages
  de S2 répondent (voir plus bas). Toute autre adresse répond 404, avec une page Silooo. Les modes `beta` et `ouvert` ne sont écrits dans aucun
  fichier du dépôt : seul Kr les pose, dans l'environnement du service.
- **Les actus** (`Actus.kt`, `pages/Actus.kt`) : un fichier par actu dans `site/contenu/actus/<slug>.md`, listé dans
  `NewsArticles.BUNDLED` (un test le compare au dossier). En-tête `titre`, `description`, `date` (obligatoire dès que
  `publie` n'est pas `non`), `etape` (`oui` : l'actu compte dans le panneau « Le chantier » de la page d'attente), `ordre`
  (le rang d'une étape à venir). Le corps suit le plan fixe du kit : une introduction, puis `## Nouveau`, `## Changements`,
  `## Corrections`, chacune une liste de points ; un autre intertitre arrête le démarrage. La liste est à `/actus/`, un
  article publié à `/actus/<slug>/`, dans les deux modes. Le chantier : les étapes publiées (franchies, dans l'ordre des
  dates), puis les étapes à venir dans l'ordre de `ordre`, la première « en cours ». Demain, la même forme viendra de la
  table `news_articles` écrite dans l'admin.
- **Les contenus** sont des fichiers Markdown de `site/contenu/`, avec un en-tête entre deux lignes `---` : `titre`, `description`,
  `url` (elle finit par `/`) et `modes` (`[attente, ouvert]`). `SiteContent` les lit et les rend en HTML (commonmark-java, HTML brut
  escamoté) une seule fois, au démarrage : un en-tête incomplet arrête le démarrage et nomme le fichier. Un contenu neuf demande
  son nom dans `SiteContent.BUNDLED` et une fonction de rendu dans `site/pages/` (choisie dans `SiteRoutes.kt`).
- **Les pages** sont écrites en DSL HTML (`kotlinx.html`) : `pages/Gabarit.kt` pose la page entière (en-tête, `main`, pied avec la mention
  de Mojang et les liens légaux), `Attente.kt`, `Accueil.kt`, `Legal.kt`, `Discord.kt` et `Erreur.kt` posent le `h1` et le contenu. Une page
  dont `PageFrame.panneau` est `false` (l'attente, les actus) pose son contenu sur le ciel, comme les maquettes, au lieu du
  panneau de fonte. Les seuls scripts sont ceux du gabarit (`menu.js`, `oooscar.js`, `direct.js`).
- **Les pages publiques du mode `ouvert`** (S2) : `/` (accueil), `/rejoindre/`, `/ce-qui-se-vend/`, `/boutique/`, `/vote/`,
  toutes en 404 en `attente`. Mécanique : un contenu Markdown de `site/contenu/` porte le texte écrit à la main ;
  un paragraphe seul `{{nom}}` y marque l'endroit d'un bloc que la page Kotlin de `site/pages/` rend depuis les définitions
  (`Dependencies.definitions.shop`) ou la configuration (`SiteSettings`). **Jamais un chiffre tapé** : prix, bonus, jours,
  Gemmes viennent du JSON, l'adresse et le port de la configuration, et un test le vérifie.
- **Le wiki** (S3) : `site/contenu/wiki/<sujet>.md` (en-tête des contenus plus `ordre`, entier unique), listés dans
  `WikiContent.BUNDLED` (un test les compare au dossier), rendus par `pages/Wiki.kt` ; les tableaux sont des blocs `{{nom}}`
  de `pages/WikiBlocs.kt`, tirés de `Dependencies.definitions`. Ajouter une page : créer le `.md` (url `/wiki/<sujet>/`, `modes:
  [ouvert]`, un `ordre` libre), l'ajouter à `BUNDLED` et, si elle porte un bloc, à `wikiBlocks`. Le test des chiffres refuse dans
  ces Markdown un nombre suivi de : Donar, Lingots, Gemmes, %, heures, h, €, joueurs, associés (les dates et « époque 1 » passent).
- **Les pionniers, Relier Discord, l'admission** (S4, **dessin et textes à valider par Kr**) : `/pionniers/`, `/pionniers/etat/`,
  `/pionniers/discord/` et `/pionniers/discord/retour/`, rendus en `attente` comme en `ouvert` (`site/PioneerRoutes.kt`,
  `site/pages/Pionniers.kt`, texte dans `contenu/pionniers.md`). Le site n'écrit que `site_registrations` (V7). Le code de l'inscription
  est dans `com.silooo.core.registration` (hors du paquet `site`, dont les littéraux passent le filtre de texte) : `SiteRegistrationStore`
  et `JdbcSiteRegistrationStore`, `SiteAdmission`, `DiscordIdentity` (`HttpDiscordIdentity` sur le client Ktor CIO ; un faux dans les tests).
  - **La table** `site_registrations` : `gamertag_key` (clé primaire), `gamertag`, `discord_user_id` (unique, nul tant que rien n'est
    relié, jamais affiché), `pioneer`, `created_at`, `linked_at`, `admitted_at`.
  - **La clé** : `SiteRegistrations.key(gamertag)` (sans espace aux bords, espaces réduits à un seul, minuscules, `Locale.ROOT`). La route
    de `join` appelle cette fonction, jamais une copie.
  - **La règle d'admission** : `SiteAdmission.admitsAt(moment)`, une seule fonction. `tous` pose `admitted_at` à l'inscription, `discord`
    au moment où le compte est relié (`UPDATE ... COALESCE(admitted_at, ...)` : une admission n'est jamais retirée ni refaite). Un
    identifiant Discord ne se relie qu'à un gamertag (clé unique) ; le deuxième essai rend la page « déjà relié » sans rien écrire.
  - **Admettre à la main** (Kr, sur la base `silooo`, jamais exécuté par le code) :
    `UPDATE site_registrations SET admitted_at = UTC_TIMESTAMP(3) WHERE gamertag_key = ?` avec la clé en minuscules (`kr test` pour « Kr Test »).
  - **Le cookie** `silooo_pionnier` (seul cookie du site, après une inscription) : `HttpOnly`, `SameSite=Lax`, `Secure` si `SILOOO_SITE_URL`
    est en https, une heure, chemin `/pionniers/`. Il porte la clé du gamertag et, pendant « Relier Discord », l'`state` attendu ; signé en
    HMAC-SHA256 avec `Dependencies.siteSessionSecret`, tiré au démarrage (`SecureRandom`, 32 octets) : il change à chaque redémarrage, une
    inscription en cours recommence. Le jeton caché du formulaire est signé de la même façon, valable dix minutes.
  - **La limite de débit** : cinq `POST /pionniers/` par minute et par adresse (mémoire, 10 000 adresses au plus), 429 et `Retry-After`
    au-delà. Avec `SILOOO_SITE_TRUSTED_PROXY=true`, l'adresse est le dernier maillon de `X-Forwarded-For`.
  - **Relier Discord** : activé si les deux variables Discord sont posées. L'URL de retour est `SILOOO_SITE_URL` + `/pionniers/discord/retour/` :
    c'est celle que Kr déclare dans l'application Discord. Portée `identify` seule. L'identifiant Discord ne sort jamais dans une page, un
    journal ou un message d'erreur.
  - **Pour la direction du jeu** : `join` refuse quand `site_registrations.admitted_at` est nul pour la clé du gamertag
    (`SELECT 1 FROM site_registrations WHERE gamertag_key = ? AND admitted_at IS NOT NULL`, la clé venant de `SiteRegistrations.key`). La
    colonne `pioneer` suffit au jeu pour créditer le titre et l'objet du pionnier ; le site ne touche ni `players` ni `player_cosmetics`.
  - **Les tests** : `site/PioneerRoutesTest` (parcours complet avec faux dépôt et faux fournisseur), `site/SiteAdmissionConfigTest`,
    `registration/SiteRegistrationSqlTest` (MariaDB, ignoré sans Docker).
- **Les données vivantes, le webhook PayNow** (S5, **textes à valider par Kr**). Les accès à la base sont dans `com.silooo.core.sitedata`
  (hors du paquet `site`, comme `registration`) : `SiteStatsStore` / `JdbcSiteStatsStore` (que des `SELECT`) et `ShopDeliveryStore` /
  `JdbcShopDeliveryStore` (un `INSERT`). `Dependencies` les porte avec leurs défauts (`siteStatsStore`, `shopDeliveryStore`, `siteCaches`,
  `payNowWebhookSecret`).
  - **Joueurs en ligne** (accueil, `ouvert`) : `site/OnlinePlayers.kt` lit `ServerPresence.all()` : les joueurs des serveurs qui ont battu il y a
    moins de `SITE_MAX_BEAT_AGE` (deux minutes, plus large que les 30 secondes de `join`), sans doublon. Aucun battement récent :
    « Serveur fermé pour l'instant. » ; zéro joueur : « Personne en ligne pour l'instant. ». La base n'est pas lue.
  - **Classements** (`/classements/`, `site/StatsRoutes.kt`, `pages/Classements.kt`, texte dans `contenu/classements.md`) : la semaine ISO en
    cours en UTC (`SiteWeek`, lue sur `Dependencies.clock`, du lundi 00:00 inclus au lundi suivant 00:00 exclu). La requête en mots : pour chaque
    joueur, la somme des `delta` positifs de `currency_ledger` en `LINGOTS` dont `created_at` tombe dans la semaine ; jointure `players` (gamertag),
    puis `company_members` et `companies` (entreprise, absente pour un joueur sans entreprise) ; tri par somme décroissante, puis gamertag. Les
    dépenses, les autres monnaies et les soldes ne comptent pas. Page de 25 (`RANKING_PAGE_SIZE`, `?page=N`, 40 pages au plus, 404 au-delà du
    dernier joueur). Cache d'une minute (`SiteCache`, valeur, date, horloge du service, partagé entre les requêtes), par page et par semaine.
  - **La fiche** (`/entreprise/{id}/`, `pages/Entreprise.kt`) : nom, époque, étoiles, prestiges (`COUNT` dans `prestige_history`), date de
    création, membres (PDG d'abord, puis par date d'arrivée). Jamais de Donar, de Lingots ni de XUID : `CompanyCard` ne les porte pas.
    Identifiant invalide ou inconnu : 404 en page Silooo. Cache d'une minute par identifiant, 300 entrées au plus (la plus ancienne sort).
  - **Le webhook** (`POST /paynow/webhook`, `site/WebhookRoutes.kt`, rendu dans les deux modes) : **le format exact des webhooks de PayNow est
    à confirmer par Kr**. Tout ce qui en dépend est dans `site/PayNowWebhook.kt` : l'en-tête `PayNow-Signature` (supposé) porte l'HMAC-SHA256 du
    corps brut en hexadécimal minuscule, avec `SILOOO_PAYNOW_WEBHOOK_SECRET` ; le corps JSON porte `order_id`, `gamertag`, `gemmes` (les champs
    en plus sont ignorés). Pour changer l'en-tête, le calcul ou les noms des champs : cette classe, ses constantes et `PayNowWebhookTest`.
    Réponses : 503 sans secret (journal « webhook PayNow non configuré », une fois par démarrage), 413 au-delà de 4 Ko, 401 sans signature ou
    avec une mauvaise (comparaison en temps constant), 400 qui nomme le champ manquant ou les Gemmes non strictement positives (sans écho du
    corps), 200 `{"received": true}` sinon. La ligne `shop_deliveries` n'a que `order_id`, `gamertag`, `gemmes` ; `xuid` et `delivered_at` restent
    nuls, le relevé du jeu (`shop/DeliveryRelief`) les remplit et crédite. Un `order_id` déjà présent rend 200 sans seconde ligne : l'`INSERT`
    attrape l'erreur de doublon (code 1062), il ne lit pas avant ; la contrainte `gemmes > 0` remonte, elle.
  - **Les tests** : `site/SiteStatsTest` (accueil, semaine, classements, fiches, caches, aucun XUID), `site/PayNowWebhookTest`,
    `ConfigPayNowTest`, et sur MariaDB (ignorés sans Docker) `sitedata/SiteStatsSqlTest` et `sitedata/ShopDeliverySqlTest`.
  - **Mettre en ligne** : la marche à suivre est dans `docs/site.md`, section « Mettre en ligne ».
- **Les fichiers** de `site/public/` sont servis sous `/public/` avec un cache d'un jour ; les pages ne se gardent pas. Les logos, les
  jetons, les textures, les icônes et Oooscar ne sont pas dans `core/` : Gradle les copie de `design/` dans les ressources à la
  construction (`processResources` de `build.gradle.kts` ; l'image Docker reçoit `design/` à côté de `core/`), et `SiteAtelierTest`
  compare chaque copie à sa source octet pour octet. `robots.txt` et `sitemap.xml` (les pages du mode, une fois chacune) ont une
  heure de cache.
- **Le gabarit** (`pages/Gabarit.kt`, d'après les maquettes de Kr) : l'en-tête (la marque, un bouton Menu qui replie la navigation
  sous 700 px, la navigation du mode listée dans `pages/Navigation.kt`, une entrée seulement si le mode rend la page), le bandeau
  direct en mode ouvert, le panneau de fonte du contenu, la scène d'Oooscar sur le sol (`pages/Oooscar.kt` : les cinq visages, la
  bulle d'arrivée de chaque adresse, les textes des réactions posés sur `data-textes`), le pied sombre. Trois scripts, tous des
  fichiers de `public/` (la politique de sécurité interdit un script écrit dans la page) : `menu.js` (synchrone, pose `html.js` et
  fait agir le bouton), `oooscar.js` (charge `public/oooscar.svg`, remplace le PNG par le SVG articulé et joue les réactions ; sans
  script ou sans le SVG, le PNG et la bulle restent) et `direct.js` (en mode ouvert, relit `GET /site/direct.json` toutes les
  trente secondes, cache le bandeau si le service ne répond pas, et lance l'événement `silooo:voteparty` quand la Vote party
  passe atteinte). `/site/direct.json` (`SiteDirect.kt`) est une route du site, hors du contrat OpenAPI : la présence et les votes
  du jour, `version` et `dernierPrestige` à `null` tant que le jeu ne les donne pas, trente secondes de cache. Aucun texte lu par
  un visiteur n'est dans un script : tout vient du Kotlin, que `tools/anti_ia.py` relit.
- **Relire en local**, sur son poste : `SILOOO_SITE_MODE=ouvert ./gradlew run` (avec les variables de la base et de la clé, voir
  « Lancer »), puis `http://localhost:8081/`. Sans la variable, le site est en `attente`.
- **Les tests** (sans base) : `site/SiteModuleTest` (les deux modes, `lang`, un seul `h1`, `title`, `description`, `alt`, les
  en-têtes, le 404, `robots.txt` et `sitemap.xml`, `/v1` absent), `site/SiteGabaritTest` (la scène, le menu, les scripts, le pied,
  le bandeau en mode ouvert seulement), `site/SiteDirectTest` (`/site/direct.json` fermé et ouvert), `site/SiteContentTest`,
  `site/SiteLogoTest`, `site/SiteAtelierTest`, `site/EmpreinteTest`, et `ConfigTest` pour les variables.
- **Les textes** vus par les visiteurs passent `tools/anti_ia.py` (contenus Markdown en entier, littéraux du paquet `site`), et chaque
  texte neuf reste à valider par Kr.
- **Le style** (Silooo Atelier pour le web, détail dans `docs/site.md`, « Style ») : `public/site.css` est l'unique feuille liée, elle
  importe `atelier/tokens.css` (copie de `design/tokens.css`), `atelier/composants.css` (`design/composants/bundle.css` où Gradle
  remplace chaque adresse `/_blob/<id>` par la texture de `atelier/textures/`, table `atelierBlobs` de `build.gradle.kts`) puis
  `atelier.css` (polices, base, les réglages que le web demande aux composants, la mise en page). `SiteAtelierTest` vérifie que
  `composants.css` et `bundle.css` ne diffèrent que par ces adresses, et refuse un `url(...)` sans fichier, un arrondi, un dégradé,
  un flou, une largeur fixe au-dessus de 360 px, et un `border-image` dans `atelier.css` (un composant se dessine dans `design/`).
  Les polices (`polices/`, `woff2` latin tirés de `@fontsource/*`, licence OFL) sont servies par le site.
- **Ajouter un composant** : le dessiner dans `design/` (texture dans `design/textures/`, règle dans `design/composants/bundle.css`
  avec une adresse `/_blob/<id>`), ajouter l'adresse à `atelierBlobs` dans `build.gradle.kts` et à la table de `docs/site.md`. Rien
  à recopier. Un réglage propre au web (marge, soulignement, interligne) va dans `atelier.css`, jamais un `border-image`.
- **Voir les pages dans un navigateur** sans base : `./gradlew test` écrit les pages rendues et `public/` dans
  `core/build/site-rendu/` (`SiteRenderToDisk`, Gradle pose `SILOOO_SITE_RENDER_DIR`) ; `node tools/verifier_site.mjs` les ouvre
  dans Chromium à 360 et 1 280 px (débordement, erreurs de console, requêtes en échec, images sans `alt`) et `--captures <dossier>`
  en écrit les captures. `tools/verifier.sh` enchaîne les deux.
- **L'empreinte d'un gamertag** (`site/Empreinte.kt`) : le `provisionalXuid` de l'add-on (`addon/src/identity.ts`), FNV-1a 64 bits
  sur les unités UTF-16 du gamertag en minuscules et sans espaces autour, en décimal ; `EmpreinteTest` tient les vecteurs calculés
  avec l'add-on. Elle retrouve un joueur de `players` depuis la boutique, la liaison Discord ou la connexion Xbox (décision 25).
- **L'image de partage** (`public/partage.png`, 1200 sur 630 : le logo, le nom sur une plaque, Oooscar et sa bulle) est l'`og:image`
  de chaque page. Elle se refait avec `node tools/partage/capturer.mjs` (Chromium rend `tools/partage/partage.html` avec le
  `public/` de `core/build/site-rendu/`).

## Le Discord

Le bot Oooscar vit dans le paquet `discord/` (ADR 0007, cadrage dans `docs/discord.md`, état dans `docs/discord-passation.md`). Il
démarre dans son propre fil après le site, seulement si `SILOOO_DISCORD_ACTIF` le demande, et tout ce qui lui arrive de mal (jeton
refusé, serveur inconnu, Discord injoignable, plan illisible) s'écrit dans les journaux sans arrêter le jeu ni le site.

- **Trois modes.** `non` (le défaut) : rien ne se charge. `a-blanc` : le bot se connecte, lit le serveur et écrit dans le journal
  l'écart avec le plan, sans rien changer, sauf le salon du journal lui-même s'il manque. `oui` : il crée ce qui manque et remet
  le sujet d'un salon ou la couleur d'un rôle qui ont dérivé. Dans tous les cas, il ne supprime jamais rien : une entrée retirée du
  plan reste sur Discord jusqu'à ce que Kr l'enlève à la main.
- **Le plan** est `src/main/resources/discord/plan-serveur.json`, contrôlé par `plan-serveur.schema.json` et
  `tools/verifier_plan_discord.mjs` (unicité, rôles cités, un seul emoji par salon et jamais dans le nom, couleurs de
  `design/tokens.json`, salon du journal). Les clés sont en français ; chaque rôle, catégorie et salon porte la phase où il
  apparaît (`attente`, `beta`, `ouvert`), et `SILOOO_DISCORD_PHASE` dit où en est le serveur. Un salon se reconnaît à son nom
  Discord (`emoji・nom`) : le renommer dans le plan en crée un nouveau.
- **Le serveur Silooo, depuis le PC.** Il n'y a qu'un serveur Discord, celui de Silooo, et pas de serveur de test (décision de
  Kr du 5 novembre 2026) : le bot y travaille directement, fermé au public jusqu'à l'ouverture du QG. Il tourne sur le PC de Kr
  pendant le chantier et sur le VPS après, jamais les deux à la fois. On commence par `SILOOO_DISCORD_ACTIF=a-blanc`, on lit le
  compte rendu dans `#journal-du-bot`, puis on passe à `oui`. `SILOOO_DISCORD_TOKEN` et `SILOOO_DISCORD_SERVEUR` (l'identifiant
  numérique du serveur) sont des variables du compte Windows de Kr, jamais affichées. Tant que le QG n'est pas ouvert,
  `SILOOO_DISCORD_PHASE=attente` et `SILOOO_DISCORD_PUBLIC=non` : les messages privés ne partent qu'au staff et les mentions de
  rôle ne visent que ses rôles. Sur le PC, [`tools/bot-pc.ps1`](../tools/bot-pc.ps1) (PowerShell, depuis la racine du dépôt)
  fait tout : la base MariaDB dans Docker Desktop (conteneur `silooo-mariadb`, port 3306 sur 127.0.0.1 seulement, mot de
  passe local gardé hors du dépôt dans `%LOCALAPPDATA%\Silooo`), les migrations, une clé d'API locale, puis `gradlew run`
  avec `-Mode a-blanc` (le défaut) ou `-Mode oui`. La marche à suivre pour Kr est dans
  [discord-passation.md](../docs/discord-passation.md), « Lancer le bot depuis le PC ». Le serveur doit être en mode
  Communauté pour les forums et le salon d'annonces ; sinon le bot le dit dans son compte rendu et crée le reste. Le bot a
  besoin des droits « Gérer les rôles » et « Gérer les salons », et de deux intentions privilégiées, « membres du serveur » et
  « contenu des messages », cochées dans le portail des développeurs.
- **Le journal** : le salon `journal-du-bot` de la catégorie Staff reçoit un compte rendu à chaque démarrage (ce qui manquait, ce qui
  a été créé ou modifié, les refus de Discord), sans aucune mention ; le résumé va aussi dans les journaux du service.
- **La boucle.** Hors `a-blanc`, le bot sert ce qui arrive de Discord (arrivées, accueil terminé, départs, messages, réactions,
  vocaux, commandes, boutons, menus, formulaires : `DiscordEvent`) sur un seul fil, `BotRuntime` : une file bornée (les plus vieux
  tombent, comptés), des minuteries (`Timer`) sur ce même fil, et des fonctions (`Feature`) isolées les unes des autres : une qui
  lève n'arrête ni la boucle ni les autres. Chaque fonction déclare ses commandes (`Feature.commands`) : `BotRuntime` pose l'union de
  toutes, une seule fois, avant de démarrer les fonctions, et ne pose rien si aucune n'en déclare. Un message du bot (`BotMessage`) est une carte à barre ciel (composants récents de
  Discord) ou du texte, avec boutons, menu, image ; les formulaires et les commandes ont leurs modèles. `discord/jda/JdaMessages`
  et `JdaEvents` traduisent dans les deux sens. Les chiffres du Discord sont dans `definitions/discord.json`
  (`DiscordDefinitions`, chargé à part du jeu, vérifié contre le plan : rôles et salons cités, niveaux qui montent) ; ses tables
  sont celles de la migration V10, toutes préfixées `discord_`. `ServerIds` garde aussi les catégories (`categories`, `category(planId)`,
  remplis par `PlanApplier`) ; `ChannelSpec.memberViewers` ouvre un salon à des membres précis ; `DiscordServer.deleteChannel` ne supprime
  qu'un salon de texte ou un vocal créé par le bot ; `history` lit les messages d'un salon, `moveToVoice` déplace un membre vers un
  vocal, `voiceMembers` dit qui s'y trouve et `memberName` rend le nom d'un membre ; `MessagePosted.parentChannelId` donne le forum d'un sujet.
- **L'accueil guidé.** La clé `accueil` du plan : des questions dont chaque réponse donne un rôle du plan (la plateforme, une
  seule réponse, obligatoire ; les notifications, plusieurs). `OnboardingPlanner` en fait l'état que Discord attend, avec pour
  salons par défaut tous les salons publics de la phase, et compte avant d'envoyer la règle de Discord (7 salons par défaut, dont
  5 où tout le monde écrit) ; `OnboardingSync` compare à ce que le serveur a, identifiants mis à part, et garde ceux que Discord
  connaît ; `OnboardingApplier` ne renvoie rien si tout est en place, dit ce qu'il poserait à blanc, et met un refus dans les
  échecs du compte rendu. JDA ne couvre pas l'accueil guidé : `discord/rest/DiscordRest` l'envoie en REST (`GET` et `PUT
  /guilds/{id}/onboarding`) avec le client Ktor du service, le jeton dans le seul en-tête `Authorization`, un refus rendu avec son
  code HTTP et le message de Discord. Le Guide du serveur, lui, n'a pas d'API : `docs/discord-guide.md` dit ce que Kr pose.
- **L'arrivée.** `WelcomeFeature` attend `accueil.bonjourSecondes` secondes (selon la plateforme choisie à l'accueil guidé) après la fin
  de l'accueil guidé, envoie le tuto en trois étapes en message privé, avec le bouton « Mes rappels », puis dit bonjour dans
  `#discussion` ; messages privés fermés, le bouton « Mon tuto » sous le bonjour l'affiche. Les arrivées qui se bousculent partent en un
  message toutes les `accueil.groupeSecondes` secondes, avec jusqu'à `accueil.groupeMentionsMax` mentions, puis en une seule ligne ; un
  membre parti avant son bonjour ne reçoit rien. `MemberStore` (`JdbcMemberStore` sur `discord_members`, V10, SQL en minuscules : le
  garde anti-IA lit les chaînes du paquet) retient l'arrivée, la plateforme, le bonjour, le tuto (envoyé ou refusé) et le départ.
- **Le statut et les vocaux d'info.** `StatusFeature` alterne toutes les `statut.alternanceSecondes` secondes « Joue à play.silooo.com » et
  « Regarde N entrepreneurs » (les membres du serveur). `InfoVoiceFeature` remplace le nombre du vocal « N en ligne » par les joueurs en
  jeu (`ServerPresence`), le relit toutes les `fenetreMinutes / renommagesMax` minutes et ne le renomme que si le nombre a changé et que
  `RenameBudget` le permet (deux renommages par dix minutes, la limite de Discord) ; les deux autres vocaux d'info gardent le nom du
  plan. `BotFeatures` assemble les fonctions sur le fil du bot une fois le plan appliqué : un `discord.json` abîmé arrête le bot,
  pas le jeu.
- **Les coulisses.** Le staff dépose les spoils à l'avance avec `/spoil` (le texte, une image png ou gif de 16 Mo au plus, le seuil en
  option). `SpoilStore` (`JdbcSpoilStore` sur `discord_spoils`, V10, SQL en minuscules) les garde dans l'ordre, image comprise. Le
  premier part tout de suite dans `#coulisses`, publié par le bot avec le rôle Coulisses mentionné dans l'en-tête ; le suivant part
  quand le spoil courant atteint son seuil, par défaut `spoils.partDesMembres` des membres du serveur (un au moins). Toutes les
  `spoils.miseAJourSecondes` secondes, `SpoilFeature` relit Discord (`DiscordServer.reactors` : les membres qui ont réagi avec l'emoji
  du serveur `spoils.emoji`, chacun une fois, les bots exclus), garde le compte en base et ne modifie la ligne d'état sous le spoil
  que si elle change. Un seuil atteint entre `spoils.nuitDebutHeure` h et `spoils.nuitFinHeure` h, dans le fuseau de `discord.json`,
  attend le matin : une seule publication est programmée. Le registre est en base et Discord est relu à chaque tic, donc un
  redémarrage ne perd ni les spoils ni le compte. Une option de commande est du texte ou un fichier (`OptionKind`) : `JdaEvents`
  reçoit la pièce jointe hors du fil de JDA et répond en différé ; un fichier trop gros ou ni png ni gif est refusé sans rien
  déposer. Si Discord refuse la publication, le spoil reste déposé et le tic suivant la retente.
- **Les admissions.** Toutes les `admissions.relectureSecondes` secondes (60), `AdmissionFeature` lit les inscriptions de
  `site_registrations` qui n'ont pas de ligne dans `discord_admissions` et poste une carte par inscription dans `#admissions` (vingt au
  plus par passage, les plus anciennes d'abord), avec Admettre, Refuser et Plus tard ; les boutons vérifient le rôle Staff. Admettre pose `admitted_at` par `SiteRegistrationStore.admit`,
  modifie la carte d'abord (Discord attend la réponse trois secondes), puis, quand le Discord est relié, donne le rôle Bêta et écrit
  l'invitation en privé ; sans liaison, le journal le note et le rôle et l'invitation attendent `/lier` (D3 lira `admitted_at`).
  Refuser et Plus tard ne touchent pas à l'inscription, Plus tard garde les deux autres boutons, une carte admise ou refusée ne se
  rejoue pas. `AdmissionStore` (`JdbcAdmissionStore` sur `discord_admissions`, V10, SQL en minuscules) relit `site_registrations` en
  lecture seule (`registration`) et sait quelle carte est postée : un redémarrage ne double rien. Chaque geste va dans
  `#journal-du-bot` par `BotJournal`, qui avale un refus de Discord pour qu'une ligne perdue ne casse pas le geste.
- **Les annonces.** `/annonce` est réservée au staff : un titre (100 caractères au plus), un texte (1 800 au plus), une image png ou gif
  de 16 Mo au plus, et `mention` (non par défaut : mentionner le rôle Actus). `AnnounceFeature` fait relire le titre et le texte par
  `AntiIa` (paquet `wording/`) : à la moindre trouvaille rien n'est gardé et la réponse donne le résumé. Sinon le staff seul voit
  l'aperçu, sans prévenir personne, avec Publier et Annuler ; Publier poste dans `#annonces` (sans mention si le rôle Actus manque)
  et écrit au journal. Un brouillon par membre du staff, en mémoire (un redémarrage le perd), remplacé par le suivant, expiré après
  quinze minutes ; si Discord refuse la publication, le brouillon est remis. L'option « Publier aussi dans les Actus du site » n'est
  pas faite : le site n'a pas encore d'Actus sur `main`.
- **Les tickets et les vocaux.** Au démarrage, `TicketFeature` retrouve le menu de `#tickets` (clé `tickets-menu`, par `BotMessageStore`
  sur `discord_bot_messages`, la façon commune de retrouver un message à clé du bot) et le remet à neuf, ou le poste ; il est remis à
  neuf après chaque choix, parce que Discord garde la sélection affichée. Une question de jeu renvoie vers `#aide` sans ticket ; un autre
  choix ouvre dans la catégorie Aide un salon `ticket-<sujet>-<n>`, visible du membre, du staff et de la modération, avec le bouton
  « Fermer le ticket ». Un seul ticket ouvert par membre. Fermer (l'auteur, le staff ou la modération) copie le salon dans
  `#journal-du-bot`, ligne par ligne et par messages de 2 000 caractères, puis le supprime ; si l'archive ne se lit pas, le salon reste.
  Un salon supprimé à la main est fermé au démarrage. `TicketStore` (`JdbcTicketStore` sur `discord_tickets`, V10) numérote et garde l'état.
  `VoiceRoomFeature` fait de « Crée ton vocal » une porte : le membre qui y entre reçoit « Vocal de Nom » dans la catégorie Vocaux et y est
  déplacé ; il le retrouve s'il l'a déjà ; un vocal créé est supprimé quand il se vide (30 secondes après un déplacement refusé).
  Au démarrage, les vocaux dont le nom commence par « Vocal », vides et hors plan, sont supprimés ; les occupés sont repris. Le bot a besoin des droits
  « Gérer les salons », « Déplacer les membres » et « Lire l'historique des messages ».
- **L'XP de tchat.** `XpFeature` donne 15 à 25 points par message, une fois par minute et par membre, dans les salons de
  `xp.salons` (`definitions/discord.json`) ; un sujet de forum compte pour son forum (`MessagePosted.parentChannelId`), les bots sont
  exclus, et un message supprimé (`MessageRemoved`) perd ses points. Le niveau n demande `niveauBase × n + niveauCroissance × n × (n - 1) / 2`
  points. Aux niveaux 5, 10, 20 et 30, le rôle du plan est donné et celui du palier d'avant retiré, et « passe au niveau N » part dans le
  salon sans mention ; aux autres niveaux, rien. `/niveau` répond au joueur seul. Le lundi à l'heure du rapport (`rapport`, pas minuit :
  les messages du lundi matin restent à la semaine), le classement de la semaine est publié dans `#discussion`, puis les points de la
  semaine sont remis à zéro et purgés ; après un lundi manqué, la publication part au démarrage. `XpStore` (`JdbcXpStore` sur
  `discord_chat_xp` et `discord_xp_messages`, V10) garde le total, la semaine et les messages comptés. Limite connue : la purge coupe à
  minuit UTC du lundi, donc un message du lundi avant 9 h supprimé après le rapport retire ses points à la nouvelle semaine.
- **Les giveaways.** `/giveaway` est réservée au staff : un titre (80 caractères au plus), un lot (cosmétique, Gemmes ou Lingots), sa
  quantité ou son cosmétique, une durée de 1, 3 ou 7 jours, et `etape`. Un seul par mois (`giveaways.parMois`), sauf `etape` pour une
  grande étape. `GiveawayFeature` poste la carte dans `#giveaways`, avec le rôle Giveaways mentionné au premier envoi seulement ; c'est
  un message classique, le conteneur récent de Discord ne se mêlant pas à la mention de rôle. Participer ajoute le membre une fois, les
  bots sont refusés, la carte suit le nombre de participants. À la date dite (réarmée au démarrage, tout de suite si elle est échue),
  le tirage écrit le gagnant et le lot en base (`discord_prizes`, `delivered_at` nul) avant tout message ; puis la carte annonce le
  gagnant, qui est prévenu en privé, et le journal garde les participants. Un refus de Discord fait reprendre les étapes restantes une
  minute plus tard, 30 fois au plus, sans second tirage. `GiveawayStore` (`JdbcGiveawayStore` sur `discord_giveaways`,
  `discord_giveaway_entries` et `discord_prizes`, V10) garde les trois. Le lot reste sur l'identifiant Discord : le crédit en jeu viendra
  par une route du contrat. Limites connues : un giveaway `etape` compte dans le mois (aucune colonne ne le distingue) ; après un
  redémarrage entre le tirage et les messages, le gagnant et le lot restent en base mais les messages ne sont pas refaits ; pas
  d'événement du calendrier Discord. Le règlement est en brouillon dans `docs/discord-reglement-giveaways.md`.
- **Le socle de ces fonctions.** `DiscordServer` gagne `pin`, `react` (faux si le serveur n'a pas l'emoji), `deleteMessage` (un
  message déjà parti ne gêne pas), `message` (`null` s'il n'existe plus), `messageLink`, `hasIcon`, `setIcon`, `automodRules` et
  `setAutomodRule` ; `ChannelMessage.bot` et `ReactionAdded.isBot` disent qui est un bot. `LiveMessage` (`Boards.kt`) retrouve par
  sa clé un message vivant du bot (`discord_bot_messages`), le modifie sur place et ne le reposte que s'il a disparu.
  `BotFeatures.build(definitionsDir, database, siteUrl, onlineCount)` reçoit l'adresse du site (`siteUrl`, `config.siteUrl` dans
  `Main.kt`) pour les liens que le bot écrit.
- **L'AutoMod.** `AutomodFeature` pose à chaque démarrage quatre règles de Discord, retrouvées par leur nom (« Oooscar : mots
  refusés », « Oooscar : demandes de contact », « Oooscar : spam de mentions », « Oooscar : liens ») : elles bloquent le message
  avant qu'il paraisse, alertent dans `#signalements` et exemptent le staff et la modération, les créateurs aussi pour les liens.
  Les listes sont la ressource `discord/automod.json` (`AutomodRules.load()` ; le filtre du tchat relié de D4 la relira). Sans le
  droit « Gérer le serveur », Discord refuse : une ligne au journal du bot, et le bot continue.
- **`#bienvenue` et l'icône.** `BienvenueFeature` retrouve la carte du bot (clé `bienvenue`) et la modifie sur place : l'image de
  jour entre `bienvenue.jourHeure` et `bienvenue.nuitHeure` (bloc `bienvenue` de `definitions/discord.json`), celle de nuit sinon,
  lues dans `discord/bienvenue-jour.png` et `discord/bienvenue-nuit.png` (fournies par D1 ; sans elles, la carte part sans image et
  le journal le dit). Deux boutons : « Relier mon compte » (une attente jusqu'à D3) et « Mes rappels » (que `WelcomeFeature`
  traite déjà). L'icône du serveur (`discord/icone-serveur.png`, D1) n'est posée que si le serveur n'en a pas.
- **Le top réactions.** `ReactionFeature` compte une réaction par message et par membre, bots exclus, dans les salons de
  `reactions.salons` (`ReactionStore`, `JdbcReactionStore` sur `discord_reaction_marks`, V10). Le tableau `pantheon-reactions`
  de `#panthéon` est modifié toutes les 60 secondes au plus, seulement s'il change. Le lundi à minuit (Paris), le classement de la
  semaine passée va au journal et les marques sont purgées. Un sujet de forum ne compte que si le bot l'a vu passer depuis son
  démarrage (l'événement ne porte pas le forum parent).
- **Les meilleurs moments.** `HighlightFeature` recopie un message dans `#meilleurs-moments`, une seule fois (`HighlightStore`,
  `JdbcHighlightStore` sur `discord_highlights`), quand il reçoit `meilleursMoments.seuil` réactions `:prestige:` hors celles de
  l'auteur, dans les seuls salons que le plan montre à tous (jamais un ticket ni un salon du staff). Les messages du bot sont
  ignorés ; la copie ne mentionne personne et porte le lien du message.
- **L'Aide du mois.** `HelpFeature` pose dans chaque sujet neuf de `#aide` un message avec le bouton « Résolu par » : l'auteur du
  sujet ou le staff choisit dans un menu des participants (bots et auteur exclus), un seul choix par sujet, et le membre nommé est
  mentionné dans le sujet. `HelpStore` (`JdbcHelpStore` sur `discord_help_marks`) compte les réponses retenues du mois ; le tableau
  `pantheon-aide` montre dix lignes et, au premier du mois, le classement du mois passé va au journal. Le titre en jeu est l'affaire
  de la direction du jeu (`docs/discord-passation.md`, « Pour la direction du jeu », 10).
- **Le chantier en direct.** `ChantierFeature` poste dans `#coulisses` la carte `chantier` (faites, en cours, à venir), tirée des
  Actus du classpath (`Actus.chantier`, les mêmes que la page d'attente du site), l'épingle à sa première publication et la modifie
  sur place ; ses liens sont préfixés par `siteUrl`.
- **Le comptage.** `CountingFeature` : `#comptage` n'accepte que des nombres (un autre message est supprimé), chacun écrit le
  suivant et jamais deux fois de suite. Le bon nombre reçoit `:donar:` et un record battu est écrit ; une chaîne cassée est dite
  sans mention, avec le record, et on repart de 1. L'état (`discord_games`) et les records (`discord_records`) sont en base
  (`GameStore`, `JdbcGameStore`) : ils survivent au redémarrage.
- **Le monte-charge.** `ElevatorFeature` tient la carte `monte-charge` de `#monte-charge` et son bouton « +1 étage » : un étage par
  membre toutes les `monteCharge.attenteMinutes` minutes (l'attente est en mémoire, un redémarrage la vide), et le staff ou la
  modération qui appuie fait redescendre. L'étage vit dans `discord_games` ; l'image de l'étage est `discord/monte-charge/<étage>.png`
  quand D1 la fournit.
- **Le Panthéon des records.** `RecordsBoard` tient la carte `pantheon-records` de `#panthéon` : le record du comptage et celui du
  monte-charge. Discord limitant les modifications par salon, elle n'est modifiée qu'une fois par 60 secondes ; les suivantes sont
  différées.
- **Les tests** (sans Discord) : `discord/ServerPlanTest` (le plan du dépôt et ses incohérences), `discord/PlanApplierTest` (le plan
  sur `FakeDiscordServer`, à blanc puis pour de vrai, bêta après attente, refus isolé, dérive corrigée), `discord/OnboardingTest`
  (l'accueil guidé posé une fois, repris avec ses identifiants, à blanc, refusé, trop peu de salons ouverts, comparaison),
  `discord/rest/DiscordRestTest` (la requête telle que Discord l'attend sur un `MockEngine`, les refus sans le jeton), `discord/DiscordBotTest`
  (rien ne lève, le jeton ne s'affiche pas, les événements servis jusqu'à `close`), `discord/BotRuntimeTest` (l'ordre, l'isolement,
  la file pleine, les minuteries, l'union des commandes posée une fois avant le démarrage des fonctions, rien posé sans commande),
  `discord/DiscordDefinitionsTest` (le fichier du dépôt colle au plan, la formule des niveaux),
  `discord/DiscordMemoryTest` (JDA chargé sans connexion, chiffre écrit dans `build/mesure-memoire-discord.txt`),
  `discord/WelcomeTest` (le bonjour après son délai selon la plateforme, le bouton « Mon tuto », les arrivées groupées puis résumées, le
  membre parti, les bots écartés, le salon absent), `discord/StatusAndInfoVoiceTest` (le statut qui alterne, le vocal renommé dans son
  quota), `discord/MemberSqlTest` (MariaDB, CI, ignoré sans Docker : l'arrivée, puis le membre créé à l'accueil quand son arrivée a été
  manquée ; les deux autres tests passent par le faux `InMemoryMemberStore`), `discord/SpoilTest` (seize cas sur le faux Discord et le
  faux `InMemorySpoilStore` : le premier dépôt publié tout de suite, de nuit aussi, les suivants en attente, les trois derniers dans la
  réponse, la ligne d'état modifiée seulement quand le compte change, le seuil de jour puis de nuit, l'attente du matin, le seuil par
  défaut, les refus, le dernier compte gardé quand Discord refuse, le redémarrage, le salon absent, la commande déclarée),
  `discord/SpoilSqlTest` (MariaDB, CI, ignoré sans Docker : les numéros, l'ordre de publication, le compte, l'image),
  `discord/AdmissionTest` (dix-sept cas sur le faux Discord et le faux `InMemoryAdmissionStore` : une carte par inscription, la carte
  d'un Discord relié, vingt cartes au plus par passage, l'inscription arrivée après le démarrage, Admettre avec et sans liaison, le message privé fermé, le rôle absent
  ou refusé, Refuser, Plus tard, un bouton hors staff, la clé inconnue, la carte déjà traitée, les autres boutons ignorés, le salon
  ou le journal absent, les textes en français), `discord/AdmissionSqlTest` (MariaDB, CI, ignoré sans Docker : les inscriptions sans
  carte, la relecture d'une inscription, la carte puis la décision) avec le cas `admit` de `registration/SiteRegistrationSqlTest`,
  `discord/BotJournalTest` (la ligne postée, le salon absent, le refus de Discord avalé), `discord/AnnounceTest` (seize cas : l'aperçu,
  les tics refusés, Publier et le journal, la mention absente par défaut ou sans rôle, Annuler, Publier sans brouillon, un brouillon
  par auteur, l'expiration à quinze minutes, le brouillon remplacé, un membre hors staff, les refus, le texte le plus long, le salon
  absent, la publication refusée qui garde le brouillon), `discord/TicketTest` (dix-neuf cas sur le faux Discord et le faux
  `InMemoryTicketStore` : le menu posté puis retrouvé et remis à neuf, la question de jeu sans ticket, l'ouverture, un seul ticket par
  membre, le serveur pas prêt, la fermeture par l'auteur, le staff ou la modération, un tiers refusé, l'archive au journal, l'archive
  illisible qui garde le salon, le salon supprimé à la main), `discord/VoiceRoomTest` (douze cas : le vocal créé et le membre déplacé, le
  retour dans le sien, la suppression à la sortie, le déplacement refusé, le nettoyage au démarrage), `discord/PlanCategoryIdsTest` (deux
  cas : les catégories du plan dans `PlanReport.categoryIds`), `discord/XpTest` (vingt cas : les points par message, une fois par minute,
  les salons et les forums, les bots, le message supprimé, les niveaux et les rôles de palier, `/niveau`, le rapport du lundi, le lundi
  manqué), `discord/GiveawayTest` (dix-huit cas : le lancement et ses refus, un par mois, `etape`, Participer, le tirage, le gagnant et le
  lot écrits avant les messages, la reprise sans second tirage, personne n'a participé), avec `discord/TicketSqlTest`,
  `discord/BotMessageSqlTest`, `discord/XpSqlTest` et `discord/GiveawaySqlTest` (MariaDB, CI, ignorés sans Docker : les lignes de
  chaque magasin), `discord/AutomodTest` (14 cas), `discord/BienvenueTest` (21), `discord/ReactionTest` (13),
  `discord/HighlightTest` (12), `discord/HelpTest` (19), `discord/ChantierTest` (9), `discord/CountingTest` (20),
  `discord/ElevatorTest` (23) et `discord/RecordsTest` (12) sur le faux Discord, avec `discord/ReactionSqlTest`,
  `discord/HighlightSqlTest`, `discord/HelpSqlTest` et `discord/GameSqlTest` (MariaDB, CI, ignorés sans Docker),
  `wording/AntiIaTest` (59 cas, dont ceux de `tools/tests/anti-ia/`) et
  `ConfigDiscordTest` pour les variables. Les textes du bot et du plan passent `tools/anti_ia.py`
  (la clé `emoji` exceptée).
