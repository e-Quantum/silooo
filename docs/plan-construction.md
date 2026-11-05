# Silooo — Plan de construction

Nov 5, 2026 · @Kr

## Résumé

Silooo se construit dans un seul dépôt, `e-Quantum/silooo`, cloné sur le VPS dans `/var/www/silooo`, à côté de SileneMC et de Huby. Le travail suit 8 phases, de la phase 0 (le dépôt prêt pour les sessions Claude) à la phase 7 (l'ouverture publique). La première étape jouable est la première heure de jeu, de bout en bout, avant tout le reste.

Cinq règles ne bougent pas pendant tout le chantier :

1. **Le service décide, l'add-on affiche.** silooo-core calcule et écrit tout ; le serveur Bedrock n'envoie que des intentions.
2. **SileneMC ne bouge pas.** Silooo a ses propres conteneurs, sa base `silooo`, son port. Rien dans `/var/www/minecraft` n'est modifié par une session Silooo.
3. **Une seule source par donnée.** Les prix et formules vivent dans `definitions/`, le contrat dans l'OpenAPI, le schéma dans les migrations. La doc renvoie vers eux et ne recopie aucun chiffre.
4. **Faisable sur Bedrock, sans tic d'IA.** Tout écran suit le design system Silooo Atelier ; tout texte vu par les joueurs passe le filtre anti-IA et un avant / après validé par Kr.

## Point de départ

Le dépôt est vide (un seul commit, le README « Game Bedrock »), mais les contrats du jeu existent déjà : schéma de base, API, définitions chiffrées, design system et maquettes. Il manque le code, le serveur Bedrock, l'outillage et les règles pour les sessions Claude. État relevé sur le VPS et sur GitHub le 5 novembre 2026, en lecture seule.

| Élément | État | Où |
| --- | --- | --- |
| Dépôt GitHub | Privé, branche `main`, 1 commit (README) | `e-Quantum/silooo` |
| VPS | Ubuntu 26.04.1, 4 vCPU, 7,6 Gio de RAM dont 2,8 Gio libres, disque de 72 Go rempli à 76 % (18 Go libres) | `silenemc.eu` |
| Outils du VPS | Docker (l'utilisateur `ubuntu` passe par `sudo`), Java 25, Node 22.22, Git 2.53 | VPS |
| Services SileneMC réutilisables | MariaDB 12.3.3, Redis 8.8.2, Caddy 2.10, sauvegardes restic | `/var/www/minecraft/deploy`, `/srv/sauvegarde` |
| Règles des sessions Claude | `CLAUDE.md` de 57 lignes, hooks anti-IA et tableau de coordination, 120 ADR | `/var/www/minecraft` |
| Port Bedrock 19132 | Déjà pris par le Geyser de SileneMC (RakNet, ADR 0121) | VPS |
| Socle v0.1 | `V1__init.sql` (32 tables), OpenAPI 3.0.3 (26 chemins, 29 opérations), `usines.json` (15 usines), `economie.json`, `boutique.json` | archive `silooo-socle.zip` de cette conversation |
| Équilibrage | Tableur v2 (13 cibles sur 13 tenues), modèle financier sur 36 mois | fichiers de cette conversation |
| Game design | Document complet | [Game design](https://claude.ai/artifact/Nny766Cb1quaWrsQhRNF4Y) |
| Business plan | Document complet | [Business plan](https://claude.ai/artifact/52gHC5mf2JnEpKgrpeHjHD) |
| Design system | Silooo Atelier : textures 9-slice, icônes, Oooscar, logo, HUD, menus, cinématique, règles anti-slop | [Silooo Atelier](https://claude.ai/artifact/JFW3iDcsEZ1THywpokpKue) |
| Maquettes | 12 planches, de l'identité à la Vote party | [Maquettes](https://claude.ai/artifact/7uBWLYneMcH3uwBMWNwXbz) |

## Architecture et dépôt

Silooo ajoute deux conteneurs au VPS, le serveur Bedrock et silooo-core, et réutilise la MariaDB et le Redis de SileneMC avec une base et des clés à lui. Tout le code tient dans un seul dépôt.

&#91;embedded content: architecture de Silooo · 2 conteneurs neufs, 2 services de SileneMC réutilisés\]

Le serveur Bedrock n'appelle que silooo-core ; le site de SileneMC n'a que le droit d'ajouter des livraisons de Gemmes.

### Arborescence du dépôt

```
silooo/
├── CLAUDE.md              règles des sessions Claude (court, renvoie aux docs)
├── AGENTS.md              une ligne : lis CLAUDE.md (pour Codex et les autres agents)
├── README.md              pour un humain : ce qu'est Silooo, comment le lancer
├── .claude/
│   ├── settings.json      hooks : anti-IA à chaque écriture, rappel des règles au démarrage
│   └── skills/            recettes : changer un prix, ajouter un écran, écrire pour les joueurs
├── docs/                  la doc, en français (détail plus bas)
├── definitions/           usines.json, economie.json, boutique.json + leurs schémas JSON
├── api/                   silooo-core.openapi.yaml, le contrat add-on ↔ service
├── core/                  silooo-core : Kotlin, Ktor, Gradle, migrations Flyway
├── addon/                 pack de comportement : sources TypeScript, build esbuild, manifest
├── resource-pack/         textures du design system, ui/ (JSON UI), police, entité Oooscar
├── world/                 modèles d'îles et structures (.mcstructure)
├── tools/                 vérificateur, anti_ia.py, simulateur d'économie, générateur de types
├── deploy/                compose.yml, image BDS, scripts de déploiement et de sauvegarde
└── .github/workflows/     ci.yml
```

### Choix techniques à épingler en phase 0

- **Serveur Bedrock** : BDS officiel, même version que les clients (26.60 au 5 novembre). Mise à jour à la main, jamais automatique : une version de Bedrock peut casser l'add-on.
- **Add-on** : TypeScript strict, compilé par esbuild en un seul `scripts/main.js`. `@minecraft/server` en version stable ; `@minecraft/server-net` et `server-admin` n'existent que sur BDS ; en 26.60 ils sont en bêta (`1.0.0-beta`) et demandent l'option « Beta APIs » sur le monde (relevé du 5 novembre 2026, `docs/bedrock.md`).
- **silooo-core** : la pile du site SileneMC (Kotlin, Ktor, Gradle en Kotlin DSL), pour réutiliser l'outillage et les habitudes. Flyway pour la base, Testcontainers avec MariaDB 12.3 pour les tests.
- **Pack de ressources** : les textures et la police sortent du design system Silooo Atelier ; les écrans passent par `server_form.json` et `hud_screen.json`.
- **Définitions** : JSON vérifié par schéma. silooo-core les charge au démarrage ; un script en tire les types TypeScript de l'add-on, jamais écrits à la main.
- **Langues** : identifiants du code en anglais, comme l'OpenAPI (`company`, `factory`) ; textes des joueurs et documentation en français. Le glossaire fait le lien.

## Adapter le projet aux LLM

Une session Claude neuve doit savoir en deux minutes quoi lire, quoi ne pas toucher et comment vérifier son travail. Quatre outils s'en chargent : un `CLAUDE.md` court, une carte de lecture dans `docs/`, une commande unique qui vérifie tout, et des hooks qui rattrapent les erreurs les plus fréquentes. Le modèle est celui de SileneMC, qui marche déjà avec plusieurs sessions en parallèle.

### Les fichiers de règles

- **`CLAUDE.md`**, 80 lignes au plus, avec les mêmes sections que celui de SileneMC (avant de travailler, sans accord, ressources partagées, où lire). Une session passe d'un dépôt à l'autre sans changer d'habitudes. Le détail vit dans `docs/`, jamais dans ce fichier.
- **`AGENTS.md`**, une ligne qui renvoie à `CLAUDE.md`, pour Codex et les autres agents (Codex est déjà installé sur le VPS).
- **`.claude/settings.json`** :
  - hook après chaque écriture : `tools/anti_ia.py` relit les textes vus par les joueurs ;
  - hook au démarrage : `tools/etat.sh` affiche la branche, la dernière migration, la RAM et le disque libres ;
  - hook en fin de réponse : rappel de lancer le vérificateur si des fichiers ont changé ;
  - permissions : lecture de `deploy/.env` et des secrets refusée, `git push` et `docker` soumis à accord.
- **`.claude/skills/`**, trois recettes au départ :
  - `silooo-equilibrage` : changer un prix ou une formule (définitions, simulateur, tests dorés, note dans `docs/economie.md`) ;
  - `silooo-ecran` : ajouter un écran Bedrock (formulaire serveur, JSON UI, textes) selon le design system ;
  - `silooo-route` : ajouter une route (OpenAPI d'abord, test de contrat, code, appel côté add-on).

### La carte de lecture

`docs/README.md` dit quoi lire selon la tâche. Chaque fichier traite un seul sujet et renvoie aux sources au lieu de recopier un chiffre.

| Fichier | Ce qu'il contient | À lire avant de… |
| --- | --- | --- |
| `docs/README.md` | La carte, tâche par tâche | toute tâche |
| `docs/passation.md` | L'état du chantier, la suite, le journal des sessions | commencer et finir une session |
| `docs/vision.md` | Le jeu en une page, pour qui, ce qu'on ne fait pas | toucher à une fonction de jeu |
| `docs/glossaire.md` | Donar, Lingots, Gemmes, Silo, PDG, époque, palier… et leur nom dans le code | lire un mot du jeu inconnu |
| `docs/architecture.md` | Qui appelle qui, les 10 règles du socle | toucher à `core/` ou `addon/` |
| `docs/economie.md` | Formules, paliers, notation (k, M, Md, aa), renvoi à `definitions/` | changer un prix ou un gain |
| `docs/base-de-donnees.md` | Tables, monnaies, journal des mouvements, idempotence | écrire une migration |
| `docs/bedrock.md` | Limites de la Script API, server-net, JSON UI, NetherNet, ce qui casse à chaque version | toucher à `addon/` ou `resource-pack/` |
| `docs/design.md` | Renvoi au design system, règles du pack (9-slice, échelle ×3) | dessiner un écran |
| `docs/anti-ia.md` | Tics de texte et d'image, la règle de l'avant / après | écrire un texte vu par les joueurs |
| `docs/deploiement.md` | Worktrees, déploiement, retour arrière, sauvegardes | déployer (jamais sans Kr) |
| `docs/adr/` | Une décision par fichier, jamais réécrite | changer une décision |

### Les conventions qui aident les modèles

- **Les tests servent de spécification.** Tests dorés tirés du tableur d'équilibrage (les 13 cibles), tests de contrat sur l'OpenAPI, tests de migration sur MariaDB 12.3. Une session qui casse l'équilibrage le voit tout de suite.
- **Une seule commande vérifie tout** : `tools/verifier.sh` (format, lint, tests, schémas des définitions, anti-IA, liens de la doc). La CI lance la même.
- **Des fichiers courts et nommés par leur sujet**, 400 lignes au plus, sans magie (pas de réflexion, pas d'injection cachée). Les commentaires disent pourquoi, avec le numéro d'ADR.
- **Des erreurs métier nommées** (`CONFLIT_REESSAYER`, `SOLDE_INSUFFISANT`…), toutes listées dans l'OpenAPI.
- **Une tâche = une branche `claude/<sujet>` dans son worktree**, et trois lignes dans le journal de `docs/passation.md` en fin de session : ce qui a changé, ce qui reste, comment vérifier.

### Brouillon du `CLAUDE.md`

```markdown
# Silooo : règles pour les sessions Claude

Silooo est un jeu Minecraft Bedrock, un tycoon d'usines dans le ciel : un serveur Bedrock avec un add-on
TypeScript, et un service Kotlin, silooo-core, qui décide de tout. Ces règles valent pour toutes les
sessions et tous les agents. Le détail est dans docs/ : commence par docs/README.md.

## Avant de travailler

- Lis docs/README.md (quoi lire pour ta tâche) et docs/passation.md (l'état du chantier).
- Travaille sur une branche claude/<sujet>, dans ton propre worktree (docs/deploiement.md, section 1).
- git fetch origin avant de commencer ; repars de main s'il a avancé.
- Regarde free -m et df -h / avant un build : le VPS partage sa mémoire avec SileneMC.

## Les règles du code

- Le service décide, l'add-on affiche. Aucun solde, prix ni gain calculé dans addon/.
- Un chiffre du jeu vit dans definitions/ et nulle part ailleurs : change le JSON, pas le code.
- Une route change d'abord dans api/silooo-core.openapi.yaml, puis son test de contrat, puis le code.
- Une table change par une nouvelle migration, jamais en modifiant une migration appliquée.
- Les types de l'add-on tirés de definitions/ sont générés (tools/generer-types.sh) : ne les édite pas.
- Avant de rendre la main : tools/verifier.sh doit finir sans erreur.

## Ce qui ne se fait pas sans accord

- Aucun commit, fusion ni push sans l'accord de Kr. git add fichier par fichier, jamais -A.
- Ne lis ni ne modifie deploy/.env ni les secrets du serveur Bedrock, sous-agents compris.
- Rien dans /var/www/minecraft ni /var/www/huby : ce sont d'autres projets.
- Pas de déploiement, de redémarrage du serveur de jeu ni d'action sur un compte externe
  (GitHub, PayNow, OVH, Discord, sites de vote).
- Aucun texte ni visuel nouveau vu par les joueurs sans avant / après validé par Kr, et sans tic
  d'IA (docs/anti-ia.md). Le hook anti_ia.py signale les tics : corrige-les.
- Aucune mécanique de hasard payant, de pari ni de monnaie échangeable entre joueurs.

## Ressources partagées

- Un seul Gradle à la fois sur le VPS, Silooo et SileneMC confondus. Préfère la CI pour construire.
- MariaDB et Redis sont ceux de SileneMC : base silooo et clés silooo: seulement.
- Numéros d'ADR et de migration : prends le suivant et annonce-le dans docs/passation.md.
- Ferme ta session quand le travail est fini.

## Où lire

- docs/README.md : la carte de lecture, tâche par tâche.
- docs/architecture.md et docs/bedrock.md : le système et ses limites.
- docs/economie.md et definitions/ : les chiffres.
- docs/adr/README.md : les décisions, par numéro.
```

## Installation sur le VPS

Silooo s'installe dans `/var/www/silooo`, avec les mêmes droits que SileneMC (groupe `team-dev`), dans son propre projet Docker Compose. Six des sept gestes reviennent à Kr, parce qu'ils demandent `sudo`, un compte externe ou un secret.

1. **Créer le dossier** (Kr, `sudo`) :

   ```
   sudo install -d -o ubuntu -g team-dev -m 2770 /var/www/silooo
   sudo setfacl -d -m g:team-dev:rwx /var/www/silooo
   ```
2. **Relier le VPS au dépôt** (Kr, GitHub) : une clé de déploiement propre à `e-Quantum/silooo`, en écriture, et à rien d'autre. La clé se crée sur le VPS, sa partie publique se colle dans les réglages du dépôt (Deploy keys), puis :

   ```
   ssh-keygen -t ed25519 -f ~/.ssh/silooo_deploy -C "vps silooo"
   git clone git@github-silooo:e-Quantum/silooo.git /var/www/silooo
   ```

   (`github-silooo` = un alias de `~/.ssh/config` qui pointe vers github.com avec cette clé.)
3. **Créer la base et ses droits** (Kr, dans la MariaDB de SileneMC) : une base `silooo`, un utilisateur `silooo` limité à elle, et pour l'utilisateur du site SileneMC le seul droit d'ajouter des livraisons.

   ```sql
   CREATE DATABASE silooo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'silooo'@'%' IDENTIFIED BY '<mot de passe>';
   GRANT ALL PRIVILEGES ON silooo.* TO 'silooo'@'%';
   -- après la première migration :
   GRANT INSERT ON silooo.shop_deliveries TO '<utilisateur du site>'@'%';
   ```
4. **Poser les secrets** (Kr) : `deploy/.env` de Silooo (mot de passe de la base, clé `X-Silooo-Key`) et le fichier de secrets du serveur Bedrock lu par `@minecraft/server-admin`. Aucune session ne les lit.
5. **Ouvrir le port et le DNS** (Kr) : le 19132 est pris par le Geyser de SileneMC. Deux possibilités, à trancher plus bas : un autre port (par exemple 19142, en UDP pour le jeu et en TCP pour la signalisation NetherNet) ou une IP supplémentaire chez OVH pour garder 19132. Le DNS `play.silooo.com` pointe ensuite vers cette IP. silooo-core n'est jamais exposé : il ne répond que sur le réseau Docker.
6. **Écrire les conteneurs** (une session, puis Kr lance) : `deploy/compose.yml` avec `name: silooo` et trois services. Le réseau de SileneMC est rejoint en externe pour atteindre MariaDB et Redis, sans ouvrir leurs ports.
   - `silooo-migrate` : Flyway, tourne une fois avant chaque démarrage ;
   - `silooo-core` : limite de 512 Mo ;
   - `silooo-bds` : le serveur Bedrock, limite de 1,5 Gio, monde dans un volume.
7. **Brancher les sauvegardes** (Kr) : la base `silooo` et le volume du monde rejoignent restic chaque nuit, et une restauration est testée une fois par mois.

**Le point serré est la mémoire.** Il reste 2,8 Gio sur 7,6 ; les deux conteneurs en demandent jusqu'à 2 Gio. D'où trois mesures : construire dans la CI plutôt que sur le VPS, fermer les sessions Claude inactives (25 sessions pesaient 3,2 Go le 22 octobre, d'après le `CLAUDE.md` de SileneMC), et mesurer la vraie consommation en phase 2 avant de décider d'agrandir le VPS. Le disque (18 Go libres) suffit au départ, à condition de nettoyer les images Docker après chaque déploiement.

## Phases de construction

Le chantier compte 8 phases et environ 23 semaines de travail. La phase 3 est le vrai jalon : la première heure du storyboard se joue de bout en bout. Rien ne passe à la phase suivante tant que sa condition n'est pas remplie.

&#91;embedded content: feuille de route · 8 phases, environ 23 semaines de travail\]

Les durées sont des estimations : le jeu sort quand il est prêt, sans date imposée.

### Phase 0 : le dépôt prêt pour les sessions Claude

- [ ] `CLAUDE.md`, `AGENTS.md` et `.claude/settings.json` (hooks, permissions)
- [ ] `docs/` : la carte de lecture, puis les 11 fichiers du tableau, tirés du game design, du design system et du socle
- [ ] Import du socle : `V1__init.sql` dans `core/`, l'OpenAPI dans `api/`, les trois fichiers de `definitions/` avec leurs schémas
- [ ] Migration rejouée sur MariaDB 12.3, comme le demande le README du socle
- [ ] Premiers ADR : dépôt unique, service autoritaire, base partagée, port Bedrock, langues du code
- [ ] `tools/verifier.sh` et `tools/anti_ia.py` (adapté de celui de SileneMC), CI qui lance le vérificateur
- [ ] Les trois recettes de `.claude/skills/`
- [ ] Test : une session Claude neuve, sans contexte, reçoit « ajoute une usine » et trouve seule les bons fichiers

### Ce que livrent les phases suivantes

1. **silooo-core** : Ktor, Flyway, clé `X-Silooo-Key`, `heartbeat`, `session/join` et `leave`, `/v1/events`, idempotence par `requestId`, verrou optimiste, journal des monnaies. Tests de contrat sur les 29 opérations, même celles qui répondent encore « pas fait ».
2. **Bedrock** : image BDS épinglée, add-on TypeScript construit par esbuild, `server-net` autorisé dans les permissions, premier appel à silooo-core. Un joueur sur console rejoint `play.silooo.com` et apparaît sur son île. Mesure de la mémoire réelle.
3. **Première heure** : usines de l'époque 1, achat de niveaux, compteur qui défile, paliers ×2, Silo, HUD en trois étages, menu à tuiles et fiches, cinématique d'arrivée, Oooscar en entité, première mission de la mairie.
4. **Progression** : prestige et Lingots, époques suivantes, ville et zones, incidents à réparer, carte et îles, gains hors ligne, entreprise partagée (PDG et associés), Carnet de trouvailles. Le simulateur en Kotlin rejoue le tableur.
5. **Boutique et vote** : livraisons de Gemmes depuis le site SileneMC, grades et Pilier, sites de vote et Vote party, salon Discord. Un achat réel de test est livré une fois, et une seule.
6. **Bêta fermée** : joueurs invités par Hubyy, réglages d'équilibrage, essai de charge, restauration d'une sauvegarde, tous les textes passés par l'avant / après.
7. **Ouverture** : serveur public, suivi des erreurs, procédure écrite pour chaque nouvelle version de Bedrock.

## Qualité, déploiement et sauvegardes

La CI construit et teste tout sur GitHub, le VPS ne fait que tirer des images déjà vérifiées. Un déploiement se défait en une commande, et chaque nuit une sauvegarde part dans restic.

### Ce que vérifie la CI

| Partie | Vérifications | Bloque la fusion |
| --- | --- | --- |
| `core/` | Compilation, tests unitaires, tests dorés de l'économie, tests de contrat OpenAPI, migrations sur MariaDB 12.3 (Testcontainers) | oui |
| `addon/` | TypeScript strict, lint, tests de la logique pure, paquet `.mcpack` construit | oui |
| `definitions/` | Schémas JSON, types générés à jour, simulateur dans les cibles du tableur | oui |
| `resource-pack/` | JSON UI valide, textures aux tailles du design system | oui |
| Textes et docs | `anti_ia.py` sur les textes des joueurs, liens morts de `docs/` | oui |
| Images | Image de silooo-core poussée sur le registre privé de GitHub, taguée par commit | sur `main` seulement |

La branche `main` est protégée : fusion par demande de fusion, CI verte obligatoire, avance rapide seulement, jamais de force-push. Ce réglage se fait sur GitHub, donc par Kr.

### Déployer et revenir en arrière

1. Kr donne son accord et choisit le tag à déployer.
2. `deploy/scripts/deploy.sh <tag>` sauvegarde la base et le monde, tire les images, lance `silooo-migrate`, puis redémarre silooo-core et le serveur Bedrock.
3. Un test de fumée appelle `heartbeat` et ouvre une session de test ; s'il échoue, le script revient au tag précédent.
4. Une migration ne se défait pas : chaque migration doit rester compatible avec la version précédente du service, pour que le retour arrière reste possible.
5. Le pack de ressources et l'add-on changent avec le serveur Bedrock, jamais seuls : les joueurs téléchargent la nouvelle version à la connexion.

### Sauvegardes et versions de Bedrock

- **Chaque nuit** : dump de la base `silooo` et copie du monde vers restic, à côté des sauvegardes de SileneMC. Une restauration complète est essayée une fois par mois sur une base de test.
- **À chaque version de Bedrock** : le serveur reste sur l'ancienne version tant que l'add-on n'a pas été testé sur la nouvelle. La procédure (BDS de test, scripts à relancer, versions de la Script API à monter) vit dans `docs/bedrock.md`. La sortie de Bedrock 26.60, qui a forcé SileneMC à passer en NetherNet (ADR 0121), montre que ça arrive sans prévenir.

## Décisions, risques et prochaine étape

Six décisions reviennent à Kr avant ou pendant la phase 0 ; aucune ne bloque l'écriture des règles et de la doc.

| Décision | Possibilités | Recommandation | Statut |
| --- | --- | --- | --- |
| Port du serveur Bedrock | Un autre port, comme 19142 (gratuit, les joueurs le saisissent) ; ou une IP en plus chez OVH pour garder 19132 (payant) | 19142 pour la bêta ; l'IP en plus avant l'ouverture si le port gêne | À trancher |
| Accès du VPS à GitHub | Une clé de déploiement limitée au dépôt ; ou le compte de Kr | La clé de déploiement | À trancher |
| Où construire | CI GitHub et registre privé ; ou Gradle sur le VPS | La CI : le VPS manque de mémoire. Le quota de minutes des dépôts privés est à surveiller | À trancher |
| Coordination des sessions | Le tableau `chantier.py` de SileneMC, partagé ; ou un journal dans `docs/passation.md` | Le tableau s'il accepte un second projet, sinon le journal | À trancher |
| Réseau Docker | Rejoindre celui de SileneMC ; ou une MariaDB à part | Le rejoindre, ce qui suit le choix déjà fait d'une base `silooo` dans la MariaDB de SileneMC | À trancher |
| Marché entre joueurs | L'OpenAPI prévoit des annonces entre joueurs ; le design system exclut la monnaie échangeable | Un marché où l'on vend au Silo à prix fixé par le jeu, et retirer les annonces de l'API | À trancher |

### Risques

- **Mémoire du VPS** (2,8 Gio libres) : mesure en phase 2, constructions dans la CI, sessions inactives fermées, agrandissement du VPS décidé sur les chiffres.
- **Changements de Bedrock et de la Script API** : version épinglée, serveur de test, procédure dans `docs/bedrock.md`.
- **Disque à 76 %** : nettoyage des images après chaque déploiement, journaux limités en taille, alerte à 85 %.
- **Une seule personne au développement** : des phases courtes avec une condition de sortie, et des sessions Claude tenues par les règles et les tests plutôt que par la mémoire.
- **Dérive de l'équilibrage** quand une session touche un chiffre : tests dorés bloquants dans la CI.

### Prochaine étape

La phase 0 commence dès que le dossier `/var/www/silooo` et la clé de déploiement existent (gestes 1 et 2). Une session prépare ensuite, sur une branche, les fichiers de règles, la doc et l'import du socle, et Kr relit avant le premier commit.

*Relevés faits en lecture seule le 5 novembre 2026 : sur le VPS (`CLAUDE.md`, `deploy/compose.yml`, ADR 0119 et 0121 de SileneMC, mémoire et disque) et sur le dépôt [e-Quantum/silooo](https://github.com/e-Quantum/silooo).*
