# Les décisions d'architecture (ADR)

Une décision par fichier, jamais réécrite. Quand une décision change, on écrit un nouvel ADR qui
remplace l'ancien, et l'ancien passe au statut « Remplacée par NNNN ». Un commentaire de code qui
applique une décision cite son numéro.

## Index

| Numéro | Titre | Statut |
| --- | --- | --- |
| [0001](0001-depot-unique.md) | Un seul dépôt pour tout Silooo | Acceptée |
| [0002](0002-service-autoritaire.md) | Le service décide, l'add-on affiche | Acceptée |
| [0003](0003-base-partagee.md) | Une base `silooo` dans la MariaDB de SileneMC | Acceptée |
| [0004](0004-port-bedrock.md) | Le port du serveur Bedrock | Proposée, à valider par Kr |
| [0005](0005-langues-du-code.md) | Les langues du code, des textes et de la documentation | Acceptée |
| [0006](0006-site-silooo-com.md) | Le site silooo.com vit dans silooo-core, sur son propre port, en deux modes | Proposée, à valider par Kr |
| [0007](0007-bot-discord-jda.md) | Le bot Discord Oooscar vit dans silooo-core, sur JDA, derrière une interface | Proposée, à valider par Kr |

## La règle d'écriture

- Un fichier par décision, nommé `NNNN-sujet-court.md`, quarante lignes au plus.
- Un ADR ne se modifie pas après son acceptation, sauf pour changer son statut et pointer vers
  l'ADR qui le remplace.
- Les statuts : « Proposée » (en attente de Kr), « Acceptée », « Rejetée », « Remplacée par NNNN ».
- Une décision du plan déjà tranchée s'écrit « Acceptée ». Une décision ouverte du plan s'écrit
  « Proposée, à valider par Kr », avec la recommandation du plan comme décision provisoire ; la
  direction la note dans [passation.md](../passation.md), section « Décisions prises sans Kr ».
- Aucun chiffre du jeu dans un ADR : renvoyer vers `definitions/`.

## La numérotation

Prendre le numéro suivant de l'index et l'annoncer dans [passation.md](../passation.md), pour que
deux sessions ne prennent pas le même. Mettre à jour l'index ci-dessus dans le même commit.

## Le gabarit

```markdown
# NNNN : titre de la décision

Date : jour mois année. Statut : Proposée | Acceptée | Rejetée | Remplacée par NNNN.

## Contexte

Ce qui pousse à décider : la contrainte, les possibilités, ce qui a été relevé et où.

## Décision

Ce qu'on fait, en quelques phrases. Les possibilités écartées, et pourquoi.

## Conséquences

Ce que ça impose au code, à la doc, au déploiement. Ce que ça rend plus simple, ce que ça coûte,
ce qui reste à vérifier.
```
