# Déploiement : worktrees, VPS, retour arrière, sauvegardes

Ce fichier rassemble ce que [plan-construction.md](plan-construction.md) et
[direction.md](direction.md) disent du travail en parallèle et du VPS. Aucun déploiement ne se
fait avant la phase 2, ni sans vérification verte, ni sans la base, le port et le DNS posés par Kr.

## Travailler en parallèle : branches et worktrees

- Une tâche, une branche `claude/<sujet>`. Quand plusieurs sessions tournent sur le même PC,
  chacune a son worktree :

  ```bash
  git worktree add ../silooo-wt/<sujet> -b claude/<sujet>
  ```

- Avant de commencer : `git fetch origin`, et repartir de `main` s'il a avancé.
- Avant de rendre la main : `tools/verifier.sh` sans erreur, puis `git push -u origin claude/<sujet>`.
- La direction relit le diff, lance la vérification et fusionne sur `main` en avance rapide. Jamais
  de force-push, jamais de réécriture d'historique, sur aucune branche.
- `docs/passation.md`, `docs/README.md` et `CLAUDE.md` restent à la direction : une session rend
  compte dans son message final.
- Un worktree fini se retire avec `git worktree remove ../silooo-wt/<sujet>`.

## Où construire

| Où | Quoi | Pourquoi |
| --- | --- | --- |
| CI GitHub | Builds, tests, images Docker | Le VPS manque de mémoire ; les images arrivent déjà vérifiées |
| PC de Kr | Ce qui a besoin de Docker, Gradle ou des fichiers du PC | Node, Java et uv y sont ; Docker Desktop doit être lancé |
| VPS | Tirer les images, lancer les conteneurs, tests de fumée | Rien d'autre : un seul Gradle à la fois, Silooo et SileneMC confondus |

Avant tout ce qui est lourd sur le VPS : `free -m` et `df -h /`. Il partage sa mémoire avec Hubyy
et SileneMC.

## Le VPS et le projet Compose `silooo`

- Dossier `/var/www/silooo`, créé par Kr avec les droits du groupe `team-dev`. Il contient un
  fichier d'environnement posé par Kr : on ne le lit pas, on ne l'affiche pas, on ne l'écrase pas.
- Projet Docker Compose nommé `silooo`, fichier `deploy/compose.yml`, trois services :
  `silooo-migrate` (Flyway, une fois avant chaque démarrage), `silooo-core` et `silooo-bds` (le
  monde dans un volume). Limites de mémoire dans le compose.
- Le réseau Docker de SileneMC est rejoint en externe pour atteindre MariaDB et Redis sans ouvrir
  leurs ports : recommandation du plan, notée dans l'[ADR 0003](adr/0003-base-partagee.md).
- silooo-core n'est jamais exposé ; seul le port Bedrock de Silooo s'ouvre
  ([ADR 0004](adr/0004-port-bedrock.md)).
- Commandes autorisées : `sudo docker compose -p silooo <commande>` sur le compose de Silooo seulement, et
  la lecture (`docker ps`, `docker logs` des conteneurs `silooo-*`).

## Envoyer les fichiers tant que le VPS n'a pas d'accès GitHub

Le dépôt n'est pas cloné sur le VPS. Les fichiers de déploiement partent du PC :

```bash
rsync -av --exclude .env deploy/ ubuntu@silenemc.eu:/var/www/silooo/deploy/
```

ou `scp` fichier par fichier. Jamais d'écrasement du fichier d'environnement. La clé de déploiement
limitée au dépôt, recommandée par le plan, revient à Kr ; quand elle existera, le VPS tirera le
dépôt lui-même et cette section changera.

## Déployer par tag

1. Kr donne son accord et choisit le tag. Les images sont construites par la CI sur `main` et
   taguées par commit, sur le registre privé de GitHub.
2. `deploy/scripts/deploy.sh <tag>` : sauvegarde de la base et du monde, tirage des images,
   `silooo-migrate`, puis redémarrage de silooo-core et du serveur Bedrock.
3. Test de fumée : `heartbeat` répond, une session de test s'ouvre et se ferme. S'il échoue, le
   script revient au tag précédent.
4. `free -m` après le déploiement. Si la mémoire disponible passe sous 800 Mo, arrêter les
   conteneurs `silooo` et le noter dans [passation.md](passation.md).
5. Nettoyer les images Docker inutiles après chaque déploiement : le disque est serré.
6. Le pack de ressources et l'add-on changent avec le serveur Bedrock, jamais seuls.

Le script `deploy.sh` s'écrit en phase 2. Si la base `silooo` n'existe pas encore, on ne déploie
pas silooo-core, et on le note dans « À faire par Kr ».

## Revenir en arrière

- Rejouer `deploy.sh` avec le tag précédent. Les images précédentes restent sur le registre.
- Une migration ne se défait pas : chaque migration reste compatible avec la version précédente du
  service ([base-de-donnees.md](base-de-donnees.md)). Un retour arrière du service ne touche pas la
  base.
- Si le monde est abîmé, restaurer la sauvegarde prise au début du déploiement, serveur arrêté.
- Noter le retour arrière dans le journal de [passation.md](passation.md), avec la cause.

## Sauvegardes

- Chaque nuit, restic : dump de la base `silooo` et copie du volume du monde, à côté des
  sauvegardes de SileneMC dans `/srv/sauvegarde`. Branchement par Kr.
- Le game design vise une sauvegarde de la base toutes les heures et une copie hors du serveur : à
  confirmer par Kr.
- Une restauration complète est essayée une fois par mois sur une base de test. La première a lieu
  pendant la bêta fermée.
- Avant chaque déploiement et chaque mise à jour de Bedrock : une sauvegarde de plus.

## Mémoire et disque

- Seuil : 800 Mo de mémoire disponible. En dessous, les conteneurs `silooo` s'arrêtent et la
  direction le note. La vraie consommation se mesure en phase 2 avant de décider d'agrandir le VPS.
- Les sessions Claude inactives se ferment : elles pèsent sur la même mémoire.
- Disque : nettoyage des images après chaque déploiement, journaux limités en taille, alerte à 85 %.

## Les interdits

- Jamais `docker stop`, `docker rm` ni `docker restart` sur un conteneur `bw-*` ou `huby-*`. Hubyy
  doit rester en ligne.
- Jamais lire, afficher ni copier un secret : le fichier d'environnement de `/var/www/silooo`, le
  fichier de secrets de `@minecraft/server-admin`, les clés. Sous-agents compris.
- Rien dans `/var/www/minecraft` ni `/var/www/huby`.
- Jamais de déploiement sans vérification verte, ni d'un tag qui n'est pas sur `main`.
- Jamais de phase 6 (bêta) ni de phase 7 (ouverture) sans Kr.
- Aucune action sur un compte externe (GitHub au-delà de ce dépôt, OVH, PayNow, Discord, sites de
  vote).
- Pas de session Claude sur le VPS : les commandes passent par `ssh ubuntu@silenemc.eu "<commande>"`.

## Ce qui revient à Kr

Base `silooo` et ses comptes, port Bedrock, DNS, clé de déploiement, secrets, protection de la
branche `main`, branchement restic. La liste à jour est dans [passation.md](passation.md), section
« À faire par Kr ».
