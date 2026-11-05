# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, kit de départ.

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions), le plan, le game design et le
  design system. Aucun code encore.
- Sur le VPS : `/var/www/silooo` existe avec un `.env` posé par Kr. La base `silooo` n'est pas confirmée.
  Le VPS n'a pas d'accès GitHub.
- Phase en cours : **0**.

## Phase 0 : le dépôt prêt pour les sessions Claude

- [ ] `docs/README.md` complet : la carte de lecture du plan, tâche par tâche
- [ ] Les fichiers de `docs/` listés dans le plan : vision, glossaire, architecture, économie, base de données, bedrock, design, anti-ia, déploiement
- [ ] Schémas JSON des trois fichiers de `definitions/`, et leur vérification
- [ ] Migration V1 rejouée sur MariaDB 12.3 (Docker sur le PC ou Testcontainers)
- [ ] ADR 0001 à 0005 : dépôt unique, service autoritaire, base partagée, port Bedrock, langues du code
- [ ] `tools/verifier.sh` et `tools/anti_ia.py`
- [ ] `.github/workflows/ci.yml` qui lance `tools/verifier.sh`
- [ ] `.claude/settings.json` : hooks (anti-IA après écriture) une fois `tools/anti_ia.py` en place
- [ ] Les trois recettes de `.claude/skills/` : equilibrage, ecran, route
- [ ] Test : une session neuve reçoit « ajoute une usine » et trouve seule les bons fichiers

## Phase 1 : silooo-core

- [ ] Projet Gradle Kotlin (Ktor), build et tests en local
- [ ] Flyway avec la migration V1, test de migration sur MariaDB 12.3
- [ ] Clé `X-Silooo-Key`, `GET /v1/server/heartbeat`
- [ ] `session/join` et `session/leave`
- [ ] `GET /v1/events` avec curseur
- [ ] Idempotence par `requestId` (table `api_requests`)
- [ ] Verrou optimiste sur `companies.version`
- [ ] Journal des monnaies dans la même transaction que le solde
- [ ] Tests dorés de l'économie tirés de `docs/references/silooo-equilibrage-v2.xlsx`
- [ ] Tests de contrat sur les 29 opérations de l'OpenAPI
- [ ] Image Docker de silooo-core

## À faire par Kr

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans `/var/www/silooo/.env`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir en UDP et TCP
- [ ] DNS `play.silooo.com` vers l'IP du VPS
- [ ] Plus tard : secrets GitHub pour le déploiement par la CI

## Décisions prises sans Kr

(rien encore)

## À valider par Kr

(rien encore)

## Journal

(une entrée par session : date et heure, ce qui a changé, ce qui reste, comment vérifier)
