# La carte de lecture

Ce que tu dois lire selon ta tâche. Chaque fichier traite un seul sujet et renvoie aux sources
(`definitions/`, l'OpenAPI, les migrations) au lieu de recopier un chiffre.

## Tâche par tâche

| Tâche | À lire | Recette |
| --- | --- | --- |
| Toute tâche | `CLAUDE.md`, puis [passation.md](passation.md) | |
| Piloter les autres sessions | [direction.md](direction.md) | |
| Comprendre le jeu | [vision.md](vision.md), [game-design.md](game-design.md), [glossaire.md](glossaire.md) | |
| Lire un mot du jeu inconnu | [glossaire.md](glossaire.md) | |
| Savoir ce qu'on construit et dans quel ordre | [plan-construction.md](plan-construction.md) | |
| Toucher au service (`core/`) | [architecture.md](architecture.md), [socle.md](socle.md), [base-de-donnees.md](base-de-donnees.md), `api/silooo-core.openapi.yaml` | `silooo-route` |
| Ajouter ou changer une route | `api/silooo-core.openapi.yaml` d'abord, puis [architecture.md](architecture.md) | `silooo-route` |
| Écrire une migration | [base-de-donnees.md](base-de-donnees.md), `core/src/main/resources/db/migration/` | |
| Changer un prix, un gain, un seuil | [economie.md](economie.md), `definitions/`, `docs/references/silooo-equilibrage-v2.xlsx` | `silooo-equilibrage` |
| Ajouter ou régler une usine | [game-design.md](game-design.md) « Catalogue des usines », [economie.md](economie.md), `definitions/usines.json` et `definitions/schemas/` | `silooo-equilibrage`, section « Ajouter une usine » |
| Toucher à `addon/` ou `resource-pack/` | [bedrock.md](bedrock.md), [architecture.md](architecture.md), [design.md](design.md) | `silooo-ecran` |
| Dessiner un écran ou écrire pour les joueurs | [design.md](design.md), `design/README.md`, `design/composants/`, [anti-ia.md](anti-ia.md) | `silooo-ecran` |
| Déployer (jamais sans Kr pour la base, le port, le DNS) | [deploiement.md](deploiement.md), [direction.md](direction.md) | |
| Changer une décision | [adr/README.md](adr/README.md), puis un nouvel ADR | |
| Vérifier son travail avant de rendre la main | `tools/verifier.sh` | |

Les recettes sont dans `.claude/skills/<nom>/SKILL.md`.

## Les fichiers de `docs/`

| Fichier | Ce qu'il contient |
| --- | --- |
| [passation.md](passation.md) | L'état du chantier, la phase en cours et ses cases, « À faire par Kr », le journal des sessions |
| [direction.md](direction.md) | Comment la session de direction pilote les autres sessions et quand elle s'arrête |
| [plan-construction.md](plan-construction.md) | Le plan en 8 phases, l'architecture, l'installation sur le VPS, la CI |
| [game-design.md](game-design.md) | Le jeu, en entier : économie, progression, écrans, textes |
| [vision.md](vision.md) | Le jeu en une page, pour qui, ce qu'on ne fait pas |
| [glossaire.md](glossaire.md) | Les mots du jeu et leur nom dans le code |
| [architecture.md](architecture.md) | Qui appelle qui, les 10 règles du socle |
| [socle.md](socle.md) | Le socle v0.1 : ce qui a été vérifié, les choix faits en autonomie |
| [economie.md](economie.md) | Formules, paliers, notation ; l'historique des réglages |
| [base-de-donnees.md](base-de-donnees.md) | Tables, monnaies, journal des mouvements, idempotence, migrations |
| [bedrock.md](bedrock.md) | Limites de la Script API, server-net, JSON UI, NetherNet, versions |
| [design.md](design.md) | Renvoi au design system, règles du pack de ressources |
| [anti-ia.md](anti-ia.md) | Tics de texte et d'image, la règle de l'avant / après |
| [deploiement.md](deploiement.md) | Worktrees, déploiement, retour arrière, sauvegardes |
| [adr/](adr/README.md) | Une décision par fichier, jamais réécrite |
| `references/` | Le tableur d'équilibrage v2 (13 cibles) |

## Le socle, hors de `docs/`

- `definitions/` : les chiffres du jeu (usines, économie, boutique, monde : la grille des îles, iles : la forme des mini-îles et la cinématique, personnel : l'agence, les raretés et les salaires, incidents : les pannes et l'électricité) et leurs schémas dans `definitions/schemas/`.
- `api/silooo-core.openapi.yaml` : le contrat entre l'add-on et silooo-core, 31 opérations.
- `core/src/main/resources/db/migration/` : les migrations Flyway, V1 en tête.
- `design/` : le design system Silooo Atelier (charte, composants, textures, icônes, Oooscar, logo).
- `addon/` : le pack de comportement (TypeScript, `server-net`), ses écrans dans `addon/src/ui/`, ses textes dans
  `addon/src/ui/textes.ts`, son monde dans `addon/src/monde/`, son dossier statique `addon/pack/` ; `addon/README.md`
  liste les textes à valider.
- `resource-pack/` : le pack de ressources (JSON UI du HUD, textures copiées de `design/`, Oooscar côté client) ;
  `tools/verifier_pack.mjs` contrôle les copies.
- `deploy/` : images, `compose.yml`, scripts de déploiement et de sauvegarde ; `deploy/README.md` et
  [deploiement.md](deploiement.md).
- `tools/` : `verifier.sh` et les contrôles qu'il enchaîne (`verifier_definitions.mjs`, `generer_types.mjs`,
  `anti_ia.py`, `verifier_pack.mjs`), leurs tests dans `tools/tests/`.
