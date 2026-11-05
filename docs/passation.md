# Passation : l'état du chantier Silooo

Dernière mise à jour : 5 novembre 2026, début de la phase 0 (session de direction).

## État

- Le dépôt contient les contrats (OpenAPI, migration V1, définitions), le plan, le game design et le
  design system. Aucun code encore. Le kit de départ est commité sur `main`.
- Sur le VPS : `/var/www/silooo` existe avec un fichier d'environnement posé par Kr. La base `silooo`
  n'est pas confirmée. Le VPS n'a pas d'accès GitHub.
- Sur le PC de Kr : Node 24, Java 17, uv (Python géré), Docker Desktop installé mais pas lancé. Pas de
  Gradle global (le wrapper suffit).
- Phase en cours : **0**. Condition de sortie : toutes les cases cochées, `tools/verifier.sh` vert sur
  `main`, CI verte.

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

- [ ] Créer la base `silooo` et l'utilisateur `silooo` dans MariaDB (mot de passe dans le fichier d'environnement de `/var/www/silooo`)
- [ ] Choisir le port Bedrock (recommandé : 19142) et l'ouvrir en UDP et TCP
- [ ] DNS `play.silooo.com` vers l'IP du VPS
- [ ] Protéger la branche `main` sur GitHub (CI verte obligatoire, avance rapide, pas de force-push)
- [ ] Plus tard : secrets GitHub pour le déploiement par la CI
- [ ] Relire la section « Décisions prises sans Kr » et dire ce qui ne va pas
- [ ] Dans l'application Claude, ouvrir les deux sessions « Phase 0 : outillage » et « Phase 0 : recettes » restées en attente de « Trust repository » et les fermer (leur travail a été fait par la direction) ; au prochain lancement d'une session PC en worktree, cliquer « Trust repository » une fois

## Décisions prises sans Kr

1. **Outillage sur le PC.** Python n'est pas installé sur le PC de Kr, mais `uv` l'est. `tools/verifier.sh`
   cherche un interpréteur dans l'ordre `python3`, `python`, puis `uv run`. `tools/anti_ia.py` reste en
   Python, comme le plan le prévoit. Les outils qui lisent `definitions/` (vérification des schémas, plus
   tard génération des types de l'add-on) sont en Node, présent sur le PC, sur le VPS et dans la CI.
2. **Migration V1 sur MariaDB 12.3.** Rejouée dans la CI (service Docker `mariadb`), parce que Docker Desktop
   n'est pas lancé sur le PC et que la direction ne démarre pas d'application sur le PC de Kr. Les tests
   Testcontainers de la phase 1 tourneront aussi dans la CI, comme le plan le recommande (« où construire »).
3. **Lancement des sessions.** Les sessions de travail sont lancées depuis l'application Claude (worktrees
   locaux ou cloud), ce qui revient au `claude --cloud` de `docs/direction.md` : même branche `claude/*`,
   même relecture avant fusion. Les sessions cadrées tournent sur Sonnet pour ménager le quota de Kr ; la
   direction garde son modèle.
4. **Ce que les sessions ne touchent pas.** `docs/passation.md`, `docs/README.md` et `CLAUDE.md` restent à la
   direction, pour éviter les conflits de fusion ; chaque session rend compte dans son message final et la
   direction écrit le journal.

## À valider par Kr

(rien encore)

## Journal

- **5 novembre 2026, début de nuit : direction.** Kit de départ commité et poussé sur `main`
  (85 fichiers). Règle d'arrêt de `docs/direction.md` mise à jour. Trois sessions de phase 0 lancées :
  docs et ADR, outillage (schémas, vérificateur, anti-IA, CI, hooks), recettes `.claude/skills/`.
  Reste : fusionner, compléter `docs/README.md`, test de la session neuve. Vérifier : `git log origin/main`.
