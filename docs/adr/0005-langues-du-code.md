# 0005 : les langues du code, des textes et de la documentation

Date : 5 novembre 2026. Statut : Acceptée.

## Contexte

Le jeu est en français, pour un public français, et Kr relit tout. Le code est écrit par des
sessions Claude et s'appuie sur des bibliothèques en anglais. L'OpenAPI et la migration V1 existent
déjà : identifiants en anglais (`company`, `factory`, `currency_ledger`), noms propres du jeu en
français (`donar`, `lingots`, `mairie_level`), codes métier en capitales françaises
(`PAS_ASSEZ_DE_DONAR`, `PDG`, `EN_RUINE`), commentaires en français. Les définitions ont des clés en
français (`croissancePrix`, `paliersX2`) parce que Kr les édite.

## Décision

- Identifiants du code en anglais : tables, colonnes, propriétés JSON, `operationId`, classes,
  fonctions, variables, fichiers de code.
- Les noms propres du jeu restent en français partout, comme des noms de marque : Donar, Lingots,
  Gemmes, Silo, PDG, mairie, Pilier.
- Les codes métier vus par l'add-on (codes de refus, états, rôles, types d'événements) sont en
  français, en capitales sans accent : ce sont des mots du jeu que l'add-on traduit en icône et en
  bulle.
- Les clés de `definitions/` sont en français : ce sont les chiffres du jeu, lus et modifiés par Kr.
- Textes des joueurs, documentation, commentaires, messages de commit et ADR en français.
- Le [glossaire](../glossaire.md) fait le lien entre chaque mot du jeu et son identifiant.

Écarté : tout en français dans le code (frottement avec les bibliothèques, accents dans les
identifiants) et tout en anglais (textes et chiffres relus par Kr).

## Conséquences

- Un identifiant nouveau se cherche d'abord dans le glossaire ; un mot nouveau s'y ajoute.
- Les énumérations SQL et OpenAPI restent telles quelles : les changer serait une migration et une
  rupture de contrat.
- Les textes des joueurs passent le filtre anti-IA ([anti-ia.md](../anti-ia.md)) ; les identifiants
  n'y passent pas.
- Les types TypeScript générés depuis `definitions/` gardent les clés françaises des fichiers.
