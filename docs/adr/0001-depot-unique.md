# 0001 : un seul dépôt pour tout Silooo

Date : 5 novembre 2026. Statut : Acceptée.

## Contexte

Silooo a plusieurs parties qui changent ensemble : le service silooo-core, l'add-on Bedrock, le
pack de ressources, les définitions chiffrées, le contrat OpenAPI, les migrations, les outils, le
déploiement et la documentation. SileneMC vit dans son propre monorepo sur le même VPS. Deux
possibilités : un dépôt par partie, ou un seul dépôt `e-Quantum/silooo`. Les sessions Claude
travaillent en parallèle et doivent trouver seules les bons fichiers.

## Décision

Un seul dépôt, `e-Quantum/silooo`, séparé du monorepo de SileneMC, avec l'arborescence décrite dans
[plan-construction.md](../plan-construction.md), section « Arborescence du dépôt ». Ce qui sert de
SileneMC (modèle des grades et des packs, migrations Flyway, conventions, hook anti-IA) se reprend
par copie, jamais par dépendance.

Écartés : un dépôt par partie, qui casserait la règle « une route change d'abord dans l'OpenAPI,
puis son test, puis le code » en trois commits dans trois dépôts ; et le monorepo de SileneMC,
qui mêlerait deux projets et deux cycles de déploiement.

## Conséquences

- Un commit peut changer l'OpenAPI, le test de contrat, le code Kotlin et l'appel côté add-on.
- Une seule commande vérifie tout, `tools/verifier.sh`, et une seule CI.
- Les définitions sont lues par le service et traduites en types pour l'add-on dans le même dépôt.
- La documentation renvoie vers les sources par liens relatifs, vérifiés par la CI.
- Le dépôt est privé ; le VPS n'y a pas encore accès, voir [deploiement.md](../deploiement.md).
- Si Silooo devient un jour un mode de SileneMC, le code d'économie en Kotlin se recopie ; le dépôt
  reste séparé tant que ce jour n'est pas venu.
