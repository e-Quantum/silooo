# Architecture : qui appelle qui

Silooo ajoute deux conteneurs au VPS de SileneMC, le serveur Bedrock et silooo-core, et réutilise
la MariaDB et le Redis de SileneMC avec une base et des clés à lui. Le serveur Bedrock n'appelle que
silooo-core. silooo-core décide de tout. Le détail des contrats est dans [socle.md](socle.md) ; ce
fichier dit qui parle à qui et résume les dix règles du socle.

## Les conteneurs

```
  Joueurs (console, mobile, PC)
      |  NetherNet / RakNet, port Bedrock de Silooo (ADR 0004)
      v
+-------------------------------+        +----------------------------+
| silooo-bds                    |  HTTP  | silooo-core                |
| BDS version epinglee          | -----> | Kotlin, Ktor               |
| add-on TypeScript (Script API)|        | regles du jeu, calculs     |
| pack de ressources            |        | ecoute sur le reseau Docker|
| @minecraft/server-net         |        | seulement, jamais Internet |
+-------------------------------+        +------+--------------+------+
                                                |              |
                               +----------------+              +-------------+
                               v                                             v
                    +---------------------+                      +----------------------+
                    | MariaDB de SileneMC |                      | Redis de SileneMC    |
                    | base `silooo`       |                      | cles `silooo:`       |
                    +----------^----------+                      +----------------------+
                               |  INSERT sur `shop_deliveries` seulement
                    +----------+----------+
                    | Site SileneMC       |
                    | boutique, PayNow    |
                    +---------------------+

  silooo-migrate (Flyway) tourne une fois avant chaque demarrage de silooo-core.
  Projet Docker Compose `silooo`, dossier /var/www/silooo sur le VPS.
```

| Qui | Appelle | Comment | Pour quoi |
| --- | --- | --- | --- |
| Joueur | silooo-bds | Protocole Bedrock, sur le port de Silooo | Jouer |
| Add-on (dans silooo-bds) | silooo-core | `@minecraft/server-net`, en-tête `X-Silooo-Key`, voir [l'OpenAPI](../api/silooo-core.openapi.yaml) | Envoyer une intention, recevoir le nouvel état |
| Add-on | silooo-core | `GET /v1/events?after=<curseur>`, toutes les quelques secondes | Recevoir ce que le service a à dire (Gemmes reçues, commande terminée, invitation) |
| Add-on | silooo-core | `POST /v1/server/heartbeat` | Dire que le serveur tourne et qui est en ligne |
| silooo-core | MariaDB de SileneMC | Compte applicatif, base `silooo` | Lire et écrire l'état du jeu |
| silooo-migrate | MariaDB de SileneMC | Compte migrateur, Flyway | Appliquer les migrations |
| silooo-core | Redis de SileneMC | Clés préfixées `silooo:` | Cache et compteurs ; usage précis à relever en phase 1 |
| Site de SileneMC | MariaDB de SileneMC | `INSERT` sur `shop_deliveries`, rien d'autre | Déposer une commande de Gemmes payée |
| silooo-core | Sites de vote | Appels sortants | Relever les votes, voir [design/composants/VoteParty.md](../design/composants/VoteParty.md) |
| silooo-core | Discord (plus tard) | Bot | Alertes privées : silo plein, commande qui expire |

Personne n'appelle l'add-on. `server-net` ne fait que des requêtes sortantes, donc le service ne
pousse jamais rien : l'add-on vient chercher les événements. Personne n'expose silooo-core sur
Internet : il ne répond que sur le réseau Docker.

## Les dix règles du socle

Chaque règle est détaillée dans [socle.md](socle.md), section « Règles à garder en codant ».

1. **Le service décide, l'add-on affiche.** L'add-on envoie une intention ; silooo-core vérifie,
   calcule, écrit et renvoie l'état. Aucun solde, prix ni gain calculé dans `addon/`.
2. **Le compteur qui défile.** L'add-on anime le Donar à partir de `ratePerHour` sans rappeler le
   service, et se recale à chaque réponse.
3. **Idempotence.** Chaque action porte un `requestId`. Rejouée, elle renvoie la réponse gardée dans
   `api_requests` sans refaire l'action.
4. **Entreprise partagée.** `companies.version` est un verrou optimiste ; le second associé reçoit
   `CONFLIT_REESSAYER` et l'add-on rejoue avec l'état frais.
5. **Monnaies.** Donar en `DOUBLE` ; Lingots, Gemmes, jetons et clés en `BIGINT`. Chaque mouvement
   personnel s'écrit dans `currency_ledger` dans la même transaction que le solde.
6. **XUID en chaîne dans le JSON.** Un entier 64 bits dépasse la précision des nombres JavaScript.
7. **Gemmes de la boutique.** Le site n'a que `INSERT` sur `shop_deliveries` ; silooo-core crédite
   une seule fois par `order_id` et écrit l'événement `GEMMES_RECUES`.
8. **Événements.** L'add-on interroge `GET /v1/events` pour tous les joueurs à la fois, avec un
   curseur.
9. **Secret.** `X-Silooo-Key` vit dans les secrets de `@minecraft/server-admin` côté BDS et dans
   l'environnement du conteneur côté service. Jamais dans le code, jamais dans le dépôt.
10. **Définitions.** Usines, bâtiments, cosmétiques et missions vivent dans `definitions/` ; la base
    ne garde que leurs identifiants. Changer un prix, c'est changer un fichier JSON, sans migration.

## Les codes du contrat

- Les opérations de l'OpenAPI sont groupées comme le jeu : session, entreprise, usines, agence et
  personnel, prestige, associés, ville, commande mondiale, marché, boutique, pass, événements.
- Un refus renvoie un code stable listé dans la réponse `Refused` de l'OpenAPI (`PAS_ASSEZ_DE_DONAR`,
  `PAS_LE_PDG`, `SEUIL_NON_ATTEINT`). L'add-on choisit l'icône et la bulle d'Oooscar.
- Les événements ont un type listé dans le schéma `GameEvent` (`GEMMES_RECUES`, `INCIDENT`,
  `BOOST_COLLECTIF`). Un nouveau type s'ajoute d'abord à l'OpenAPI.
- Une route change d'abord dans l'OpenAPI, puis dans son test de contrat, puis dans le code.

## Où vit le code

| Dossier | Contenu | État |
| --- | --- | --- |
| `api/` | [silooo-core.openapi.yaml](../api/silooo-core.openapi.yaml), le contrat | présent |
| `core/` | silooo-core : Kotlin, Ktor, Gradle, migrations Flyway | squelette de la phase 1 : clé, heartbeat, Flyway, filet 501 ; voir `core/README.md` |
| `addon/` | Pack de comportement : TypeScript strict, compilé par esbuild en un seul fichier | à créer en phase 2 |
| `resource-pack/` | Textures du design system, JSON UI, police, entité Oooscar | à créer en phase 2 |
| `definitions/` | Les chiffres du jeu et leurs schémas | présent |
| `deploy/` | Compose, image BDS, scripts de déploiement | à créer en phase 2 |
| `tools/` | Vérificateur, anti-IA, simulateur, générateur de types | en cours |

L'arborescence complète est dans [plan-construction.md](plan-construction.md), section
« Arborescence du dépôt ». Les limites de la Script API sont dans [bedrock.md](bedrock.md), les
tables dans [base-de-donnees.md](base-de-donnees.md), le déploiement dans
[deploiement.md](deploiement.md).

## Plus tard

- Au-delà de quelques centaines de joueurs, un seul serveur Bedrock ne suffit plus : plusieurs
  instances derrière un proxy Bedrock. L'architecture doit permettre ce découpage, pas le faire.
- Endstone reste optionnel : seulement pour ce que l'add-on ne sait pas faire. Rien n'en dépend.
