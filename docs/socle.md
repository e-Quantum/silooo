# Silooo : socle technique (v0.1, 4 nov. 2026)

Point de départ du dépôt Silooo, tiré du game design (doc « Silooo — Game design ») et du tableur
`UrbaFactory-equilibrage-v2.xlsx`. Rien n'est encore codé : ce sont les contrats sur lesquels
l'add-on et le service vont s'appuyer.

```
db/migration/V1__init.sql        schéma de la base « silooo » (Flyway, MariaDB)
api/silooo-core.openapi.yaml     contrat entre l'add-on (server-net) et silooo-core
definitions/usines.json          les 15 usines : époque, produits, entrées, prix, production
definitions/economie.json        croissance des prix, paliers ×2, hors ligne, seuils, Lingots, zones
definitions/boutique.json        packs de Gemmes, grades et abonnements (calqués sur SileneMC)
```

## Ce qui a été vérifié

- `V1__init.sql` passe sur MariaDB 10.11 (32 tables), avec un essai : un joueur, une entreprise,
  une usine, un Donar à 9,9 × 10^300, une livraison de Gemmes, un événement JSON. Les contraintes refusent
  un solde négatif et une zone 17. La production tourne en MariaDB 12.3 : à rejouer dessus avant de fusionner.
- `silooo-core.openapi.yaml` est valide OpenAPI 3.0.3 (26 chemins, 29 opérations).

## Règles à garder en codant

1. **Le service décide, l'add-on affiche.** L'add-on envoie une intention ; silooo-core vérifie, calcule,
   écrit, et renvoie le nouvel état. Jamais de solde calculé côté add-on.
2. **Le compteur qui défile** : l'add-on anime le Donar à partir de `ratePerHour`, sans rappeler le
   service ; il se recale à chaque réponse.
3. **Idempotence** : chaque action porte un `requestId` (UUID). Rejouée, elle renvoie la réponse gardée
   dans `api_requests` sans refaire l'action.
4. **Entreprise partagée** : `companies.version` sert de verrou optimiste. Deux associés qui achètent en même
   temps : le second reçoit `CONFLIT_REESSAYER` et l'add-on rejoue l'appel avec l'état frais.
5. **Monnaies** : Donar en `DOUBLE`. Lingots, Gemmes, jetons et clés en `BIGINT`. Chaque mouvement
   de monnaie personnelle s'écrit dans `currency_ledger` dans la même transaction que le solde.
6. **XUID en chaîne dans le JSON** : un entier 64 bits dépasse la précision des nombres JavaScript.
7. **Gemmes de la boutique** : le site de SileneMC n'a que `INSERT` sur `shop_deliveries`. silooo-core
   relève les lignes non livrées, crédite le solde une seule fois (clé `order_id`), écrit l'événement
   `GEMMES_RECUES`.
8. **Événements** : `server-net` ne fait que des requêtes sortantes. Le serveur Bedrock interroge
   `GET /v1/events?after=<curseur>` toutes les quelques secondes, pour tous les joueurs à la fois.
9. **Secret** : `X-Silooo-Key` vit dans les secrets de `@minecraft/server-admin` côté BDS, et dans
   l'environnement du conteneur côté service ; jamais dans le code.
10. **Définitions** : usines, bâtiments, cosmétiques et missions vivent dans `definitions/` ; la base ne
    garde que leurs identifiants. Changer un prix = changer un fichier, sans migration.

## Choix faits en autonomie (à confirmer)

- Les Lingots sont **personnels** (portefeuille de chaque joueur). Un associé paie une zone de ville avec les
  siens ; `city_zones.bought_by` garde qui a payé.
- Le bonus de +1 % par Lingot détenu se calcule, pour une entreprise, sur la **moyenne** des Lingots de
  ses membres : une équipe de quatre ne produit pas quatre fois plus vite qu'un joueur seul.
- Les salariés « ordinaires » ne sont pas des lignes en base : ils se déduisent du niveau de l'usine
  (modèle du tableur). Seul le personnel nommé (chefs, directeurs, recrues de l'agence) a sa ligne.
