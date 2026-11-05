# 0002 : le service décide, l'add-on affiche

Date : 5 novembre 2026. Statut : Acceptée.

## Contexte

Un add-on Bedrock tourne sur le serveur, mais la Script API ne donne ni base de données, ni calcul
long, ni requête entrante. Les règles d'économie (grands nombres, prestige, conversion en Lingots)
demandent des tests sérieux et du code réutilisable. SileneMC a déjà sa boutique en Kotlin, testée.
Deux possibilités : tout dans l'add-on avec un stockage local, ou un service séparé qui porte les
règles.

## Décision

Un service Kotlin, silooo-core, porte toutes les règles et toute l'écriture. L'add-on affiche et
envoie des intentions par `@minecraft/server-net`, avec le contrat
[api/silooo-core.openapi.yaml](../../api/silooo-core.openapi.yaml). Aucun solde, prix ni gain n'est
calculé dans `addon/`. Le service ne pousse rien : l'add-on interroge `GET /v1/events`.

Écarté : l'add-on autoritaire, parce que la triche par le client devient possible, que les calculs
bloqueraient le tick, et que les règles ne seraient testables qu'en jeu.

## Conséquences

- Les dix règles du socle, résumées dans [architecture.md](../architecture.md) : idempotence par
  `requestId`, verrou optimiste sur `companies.version`, journal des monnaies, XUID en chaîne,
  événements tirés par l'add-on, secret `X-Silooo-Key` hors du code.
- Le compteur de Donar s'anime localement à partir de `ratePerHour` et se recale à chaque réponse.
- Le temps de réponse add-on vers service doit rester imperceptible : à mesurer en phase 2.
- Les tests dorés de l'économie et les tests de contrat vivent dans `core/` et bloquent la fusion.
- `server-net` doit être autorisé dans la configuration de BDS, voir [bedrock.md](../bedrock.md).
- Endstone reste optionnel ; rien n'en dépend.
