# Base de données : tables, monnaies, journal, idempotence, migrations

La base `silooo` vit dans la MariaDB de SileneMC ([ADR 0003](adr/0003-base-partagee.md)). Son schéma
n'évolue que par migrations Flyway, dans
[core/src/main/resources/db/migration/](../core/src/main/resources/db/migration/). La première,
[V1__init.sql](../core/src/main/resources/db/migration/V1__init.sql), crée toutes les tables du
socle. Ce fichier les regroupe par domaine et rappelle les règles ; les colonnes sont dans le SQL.

## Les conventions

- MariaDB, InnoDB, `utf8mb4` avec la collation `unicode_ci`. Dates en UTC, type `DATETIME(3)`.
- Deux comptes : un migrateur, seul à modifier le schéma, et un compte applicatif pour silooo-core,
  en lecture et écriture des données. Le site de SileneMC a un troisième droit, limité à `INSERT`
  sur `shop_deliveries`.
- La base ne stocke que des identifiants pour tout ce qui vient de `definitions/` : `factory_id`,
  `building_type`, `cosmetic_id`, `item_id`, `mission_id`. Changer un prix ne touche pas la base.
- Les commentaires SQL disent pourquoi, en français. Les noms de tables et de colonnes sont en
  anglais, sauf les noms propres du jeu ([ADR 0005](adr/0005-langues-du-code.md)).

## Les tables de V1, par domaine

| Domaine | Tables | Ce qu'elles gardent |
| --- | --- | --- |
| Joueurs et sessions | `players`, `player_ranks`, `player_subscriptions` | XUID, UUID Floodgate, gamertag, étape du tutoriel, dernière connexion ; grades à vie ; Pilier et préfixe perso |
| Monnaies et journal | `player_balances`, `currency_ledger` | Soldes personnels (Lingots, Gemmes, jetons, clés) et chaque mouvement |
| Boutique | `shop_deliveries`, `shop_purchases`, `player_cosmetics` | Commandes de Gemmes du site, achats en jeu, cosmétiques possédés |
| Entreprises et membres | `archipelago_slots`, `companies`, `company_members`, `company_invites` | Case de l'archipel, Donar de la course, époque, étoiles, niveaux de mairie et de Silo, plafond hors ligne, identité ; PDG et associés avec leurs parts ; invitations |
| Usines et personnel | `company_factories`, `factory_incidents`, `staff`, `agency_candidates` | État et niveau de chaque usine, incidents ouverts, personnel nommé, candidats du jour |
| Prestige | `prestige_history` | Chaque prestige, son seuil, ses Donar gagnés, ses Lingots et sa durée |
| Ville | `city_zones`, `city_buildings`, `company_vehicles`, `city_votes` | Zones achetées et par qui, bâtiments posés, véhicules, votes des plus belles villes |
| Commandes | `player_missions`, `world_orders`, `world_order_contributions` | Missions, commande mondiale du jour et part de chacun |
| Carnet | `player_findings` | Les ressources que chaque joueur a trouvées, une ligne chacune (migration [V3__carnet.sql](../core/src/main/resources/db/migration/V3__carnet.sql)) |
| Marché | `market_listings`, `market_trades` | Annonces et ventes, avec la taxe détruite |
| Saison et social | `season_pass`, `season_claims`, `server_votes`, `friends` | Pass et paliers réclamés, votes pour le serveur, amis |
| Événements | `game_events` | Ce que le service a à dire au jeu, avec un identifiant croissant |
| Idempotence | `api_requests` | La réponse gardée de chaque appel qui modifie quelque chose |

Les salariés ordinaires n'ont pas de ligne : ils se déduisent du niveau de l'usine. Seul le
personnel nommé (chefs, directeurs, recrues de l'agence) est dans `staff`. La décoration libre de la
ville vit dans le monde, pas en base.

## Les monnaies et leurs types

| Monnaie | Où | Type | Pourquoi |
| --- | --- | --- | --- |
| Donar | `companies.donar`, `companies.donar_earned` | `DOUBLE` | Il explose au fil des courses ; un `BIGINT` déborderait. Un double va jusqu'à la limite notée dans `economie.json`, clé `notation.limiteDouble`. |
| Lingots, Gemmes, jetons, clés | `player_balances` | `BIGINT` | Ils ne doivent jamais être arrondis. |
| Seuils et Donar historiques | `prestige_history`, `world_orders` | `DOUBLE` | Mêmes raisons que le Donar. |

Le Donar appartient à l'entreprise ; les autres monnaies sont personnelles. Une contrainte `CHECK`
refuse un solde personnel négatif. Dans le JSON de l'API, le XUID voyage en chaîne : un entier 64
bits dépasse la précision des nombres JavaScript.

## Le journal des mouvements

Chaque variation d'un solde de `player_balances` s'écrit dans `currency_ledger`, dans la même
transaction : la monnaie, le delta, le solde après, une raison courte (`prestige`, `mission`,
`zone`, `marche`, `boutique`, `livraison`) et l'identifiant de ce qui l'a causée. Ce journal sert à
l'audit, au support et aux classements hebdomadaires, qui comptent les Lingots gagnés dans la
semaine. Le Donar n'a pas de journal ligne à ligne : il change à chaque encaissement ; sa trace est
`prestige_history`, course par course.

## L'idempotence par `api_requests`

Chaque appel qui modifie quelque chose porte un `requestId` (UUID), dans le corps `ActionBase` ou
en paramètre pour le `DELETE`. silooo-core garde la réponse dans `api_requests`, clé `request_id`.
Un appel rejoué après une coupure ou un nouvel essai de l'add-on retrouve la réponse gardée et ne
refait pas l'action. `shop_purchases.request_id` est unique pour la même raison. Les lignes
anciennes de `api_requests` se purgent par date : la durée de rétention est à fixer en phase 1.

## Le verrou optimiste

`companies.version` et `player_balances.version` changent à chaque écriture. Une écriture qui porte
une version dépassée est refusée avec le code `CONFLIT_REESSAYER` ; l'add-on relit l'état et rejoue.
C'est ce qui permet à plusieurs associés de jouer en même temps sans verrou long en base. Le champ
`CompanyState.version` de l'API transporte cette valeur.

## Les livraisons de Gemmes

Le site de SileneMC écrit une ligne dans `shop_deliveries` par commande payée (`order_id`, le pseudo saisi sur le site, les Gemmes
achetées). Le site n'a que le droit d'insérer. silooo-core fait le reste, dans `shop/DeliveryRelief`, de deux façons :

- **à intervalle régulier** : un passage toutes les 30 secondes par défaut (variable `SILOOO_DELIVERY_POLL_SECONDS`), qui relève les
  lignes sans `delivered_at`, de la plus ancienne à la plus récente, par lots de 100 ;
- **au `join`** : les lignes d'un joueur qui arrive sont versées dans la transaction du `join`, avant la réponse.

Le joueur d'une ligne est trouvé par `xuid` si la ligne en porte un que `players` connaît, sinon par `gamertag` (casse ignorée,
celui dont `last_seen_at` est le plus récent s'ils sont plusieurs). Personne ne correspond : la ligne reste en attente, intacte
(`delivered_at` toujours nul, `xuid` tel que le site l'a écrit), et le passage suivant, ou le `join` du joueur, la reprend.

Une ligne trouvée se verse en **une seule transaction** :

1. `UPDATE shop_deliveries SET delivered_at = ?, xuid = ? WHERE order_id = ? AND delivered_at IS NULL` doit toucher une ligne ; sinon
   un autre passage l'a versée, et rien n'est écrit ;
2. `player_balances.gemmes` et une ligne de `currency_ledger` (motif `livraison`, `ref_id` : l'`order_id`) ;
3. l'événement `GEMMES_RECUES` dans `game_events` (`orderId`, `gemmes`, `gemmesTotal`).

Si une des trois écritures échoue, aucune ne reste et la ligne attend le passage suivant. La commande est donc versée une seule fois,
même si deux passages, ou un passage et un `join`, tournent en même temps : le second ne touche aucune ligne. Aucune migration n'a été
nécessaire, V1 suffit.

## Le curseur du suiveur Discord (V8)

`discord_cursor` garde, pour les messages Discord du serveur, jusqu'où `game_events` a été lu : une seule ligne (`id` = 1, imposé par
un `CHECK`), `last_event_id` (le dernier `game_events.id` lu) et `updated_at`. La ligne n'existe pas avant le premier passage du
suiveur (`discord/DiscordFollower`) ; ce premier passage la pose sur le dernier événement existant et n'envoie rien, pour qu'un
service qui démarre ne rejoue jamais l'ancien. Ensuite le suiveur l'avance après chaque message envoyé et à la fin de chaque lecture,
par `INSERT … ON DUPLICATE KEY UPDATE` : un redémarrage de `silooo-core` reprend où le suivi s'était arrêté. Sans adresse de webhook
(`SILOOO_DISCORD_WEBHOOK`), le suiveur ne tourne pas et ne touche pas à la table. Elle ne contient ni adresse ni texte de message.

## La règle des migrations

- Une migration appliquée ne se modifie jamais. Une table change par une nouvelle migration.
- Le numéro suivant se prend dans l'ordre et s'annonce dans [passation.md](passation.md). Nom de
  fichier : `V<numéro>__<sujet-court>.sql`.
- Chaque migration reste compatible avec la version précédente du service, pour que le retour
  arrière du déploiement reste possible ([deploiement.md](deploiement.md)). Pas de suppression de
  colonne dans la même version que celle qui cesse de l'utiliser.
- V1 a été vérifiée sur MariaDB 10.11 ; la production tourne en MariaDB 12.3. La CI rejoue les
  migrations sur 12.3 (service Docker `mariadb:12.3.3`, puis Testcontainers en phase 1). V1 passe
  sans erreur sur 12.3.3 : 32 tables créées, relevé dans la CI le 5 novembre 2026.
- Les tests de migration font partie de `tools/verifier.sh` et bloquent la fusion.

## Ce qui n'est pas en base

- Les chiffres du jeu : `definitions/`.
- Le monde Bedrock et la décoration libre : le volume du conteneur `silooo-bds`, sauvegardé à part.
- Les secrets : l'environnement des conteneurs et les secrets de `@minecraft/server-admin`.
