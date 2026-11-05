# Silooo : règles pour les sessions Claude

Silooo est un jeu Minecraft Bedrock, un tycoon d'usines dans le ciel : un serveur Bedrock avec un add-on
TypeScript, et un service Kotlin, silooo-core, qui décide de tout. Ces règles valent pour toutes les
sessions et tous les agents. Le détail est dans docs/ : commence par docs/README.md.

## Avant de travailler

- Lis docs/README.md (quoi lire pour ta tâche) et docs/passation.md (l'état du chantier, la tâche suivante).
- Travaille sur une branche claude/<sujet>, dans ton propre worktree quand plusieurs sessions tournent.
- git fetch origin avant de commencer ; repars de main s'il a avancé.
- Sur le VPS, regarde free -m et df -h / avant tout ce qui est lourd : il partage sa mémoire avec Hubyy.

## Les règles du code

- Le service décide, l'add-on affiche. Aucun solde, prix ni gain calculé dans addon/.
- Un chiffre du jeu vit dans definitions/ et nulle part ailleurs : change le JSON, pas le code.
- Une route change d'abord dans api/silooo-core.openapi.yaml, puis son test de contrat, puis le code.
- Une table change par une nouvelle migration, jamais en modifiant une migration déjà appliquée.
- Les types de l'add-on tirés de definitions/ sont générés : ne les édite pas à la main.
- Avant de rendre la main : tools/verifier.sh doit finir sans erreur (tant qu'il n'existe pas : les
  builds et tests qui existent).
- Le site silooo.com est un module de silooo-core (core/.../site/) : il affiche, il ne décide rien, et
  SILOOO_SITE_MODE reste à attente partout sauf sur le poste de Kr (docs/site-direction.md).

## Mode autonome (accordé par Kr le 5 novembre 2026, révocable en modifiant ce fichier)

- Commit et push : autorisés sur claude/* ; sur main seulement quand tools/verifier.sh est vert.
  Jamais de force-push, jamais de réécriture d'historique.
- Déploiement : autorisé pour le seul projet Docker « silooo » sur le VPS, après une vérification verte.
  Jamais d'arrêt ni de redémarrage d'un autre conteneur (bw-*, huby-*).
- Les décisions ouvertes du plan (docs/plan-construction.md) se tranchent avec sa recommandation, notée
  dans docs/passation.md, section « Décisions prises sans Kr ».
- Tout texte ou visuel nouveau vu par les joueurs reste marqué « à valider par Kr » dans
  docs/passation.md : il peut être commité, jamais présenté comme définitif.

## Ce qui reste interdit, même en mode autonome

- Lire ou modifier un fichier .env, une clé ou un secret, sous-agents compris. Aucun secret dans le dépôt.
- Toucher à /var/www/minecraft ou /var/www/huby : ce sont d'autres projets, et Hubyy doit rester en ligne.
- Agir sur un compte externe (GitHub, PayNow, OVH, Discord, sites de vote) au-delà de pousser ce dépôt.
- Désactiver ou affaiblir un test pour le faire passer.
- Toute mécanique de hasard payant, de pari ou de monnaie échangeable entre joueurs.
- Tout tic d'IA dans les textes des joueurs : mots-réclame, questions de pub, emoji (design/README.md,
  section « Écrire sans slop »).

## Ressources partagées

- Un seul Gradle à la fois sur le VPS. Préfère construire sur le PC, dans le cloud ou dans la CI.
- MariaDB et Redis sont ceux de SileneMC : base silooo et clés silooo: seulement.
- Numéros d'ADR et de migration : prends le suivant et annonce-le dans docs/passation.md.

## Où lire

- docs/README.md : la carte de lecture, tâche par tâche.
- docs/passation.md : l'état, la tâche suivante, le journal.
- docs/plan-construction.md : le plan en 8 phases ; docs/game-design.md : le jeu.
- docs/direction.md : comment la session de direction pilote les autres sessions.
- docs/site-direction.md et docs/site-passation.md : la direction du site silooo.com, son état, ses étapes.
- docs/discord.md et docs/discord-passation.md : le Discord de Silooo (bot Oooscar, serveur, visuels), son état, ses étapes.
- design/README.md : le design system Silooo Atelier.
