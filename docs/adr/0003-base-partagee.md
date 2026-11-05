# 0003 : une base `silooo` dans la MariaDB de SileneMC

Date : 5 novembre 2026. Statut : Acceptée. Le point « réseau Docker de SileneMC rejoint » est la
recommandation du plan, à valider par Kr.

## Contexte

Le VPS héberge déjà la MariaDB et le Redis de SileneMC, avec ses sauvegardes restic. Il est serré en
mémoire. Silooo a besoin d'une base relationnelle et d'un cache. Le site de SileneMC doit pouvoir
déposer les commandes de Gemmes payées. Deux possibilités : une MariaDB à part dans les conteneurs
de Silooo, ou une base dédiée dans la MariaDB existante.

## Décision

Une base `silooo` dans la MariaDB de SileneMC, avec ses propres comptes : un migrateur pour Flyway
et un compte applicatif pour silooo-core. Le compte du site de SileneMC reçoit le seul droit
d'insérer dans `shop_deliveries`. Dans le Redis de SileneMC, Silooo n'utilise que des clés
préfixées `silooo:`. Le schéma est [V1__init.sql](../../core/src/main/resources/db/migration/V1__init.sql).

Pour atteindre ces deux services sans ouvrir leurs ports, le plan recommande que le projet Compose
`silooo` rejoigne en externe le réseau Docker de SileneMC. C'est la décision provisoire, à valider
par Kr ; l'autre possibilité est une MariaDB à part, qui coûterait de la mémoire.

Écartée : la MariaDB à part, pour la mémoire et parce que le site de SileneMC devrait alors joindre
un second serveur de base.

## Conséquences

- La création de la base et des comptes revient à Kr (mot de passe root). Sans elle, silooo-core ne
  se déploie pas.
- Les sauvegardes de la base rejoignent restic avec celles de SileneMC.
- Aucune session ne touche à une autre base ni à une autre clé Redis que celles de Silooo.
- La version de MariaDB est celle de SileneMC : les migrations se testent sur la même, voir
  [base-de-donnees.md](../base-de-donnees.md).
- Si Silooo déménage un jour sur sa propre machine, la base part avec lui par dump et restauration.
