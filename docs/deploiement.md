# Déploiement : worktrees, VPS, retour arrière, sauvegardes

Ce fichier rassemble ce que [plan-construction.md](plan-construction.md) et
[direction.md](direction.md) disent du travail en parallèle et du VPS. Aucun déploiement ne se
fait sans vérification verte, ni sans la base, le port et le DNS posés par Kr. Les fichiers de la phase 2 sont
écrits et testés sans Docker (`deploy/`), mais rien n'a encore tourné sur le VPS.

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
  fichier d'environnement posé par Kr, à la racine du dossier (là où Compose le lit, voir plus bas) :
  on ne le lit pas, on ne l'affiche pas, on ne l'écrase pas.
- Projet Docker Compose nommé `silooo`, fichier `deploy/compose.yml`, trois services :
  `silooo-migrate` (Flyway, une fois avant chaque démarrage), `silooo-core` et `silooo-bds` (le
  monde dans un volume). Limites de mémoire dans le compose : 256 Mo, 512 Mo et 1,5 Gio, valeurs du
  plan de construction.
- Le réseau Docker de SileneMC est rejoint en externe pour atteindre MariaDB et Redis sans ouvrir
  leurs ports : recommandation du plan, notée dans l'[ADR 0003](adr/0003-base-partagee.md).
  Seuls `silooo-migrate` et `silooo-core` y entrent ; `silooo-bds` ne voit que le réseau `silooo`.
- silooo-core n'est jamais exposé ; seul le port Bedrock de Silooo s'ouvre
  ([ADR 0004](adr/0004-port-bedrock.md)).
- Commandes autorisées : `sudo docker compose -p silooo --project-directory /var/www/silooo -f deploy/compose.yml <commande>`
  sur le compose de Silooo seulement (c'est ce que fait `deploy.sh`), et la lecture (`docker ps`,
  `docker logs` des conteneurs `silooo-*`).

### Les fichiers de `deploy/`

Le détail et la procédure pas à pas sont dans [deploy/README.md](../deploy/README.md).

| Fichier | Rôle |
| --- | --- |
| `deploy/compose.yml` | Le projet `silooo` : les trois services, les réseaux, les volumes du monde et de la config |
| `deploy/exemple.env.txt` | Le modèle du fichier d'environnement (valeurs fausses) ; Kr le copie à la racine du projet |
| `deploy/bds/Dockerfile` | L'image `silooo-bds` : BDS officiel (version et somme épinglées) et l'add-on construit |
| `deploy/bds/bds.sha256` | La somme SHA-256 du zip de BDS, contrôlée à la construction |
| `deploy/bds/start.sh` | L'entrée du conteneur : écrit `server.properties`, les permissions, `variables.json` et `secrets.json`, puis lance BDS |
| `deploy/bds/server.properties.template` | Le gabarit de `server.properties` |
| `deploy/scripts/deploy.sh` | Le déploiement d'un tag, avec test de fumée et retour arrière |
| `deploy/scripts/sauvegarde.sh` | La sauvegarde de la base et du monde, lancée par `deploy.sh` |
| `deploy/scripts/commun.sh` | Les réglages communs des deux scripts, et les interdits écrits en commentaire |

Le dossier de projet de Compose est la racine (`/var/www/silooo`), pas `deploy/` : Compose y lit le fichier
d'environnement, pour les `${...}` du compose et pour `env_file`. Les scripts le passent avec
`--project-directory`. `deploy/.tag-courant` (le tag en place, pour le retour arrière) et `sauvegardes/`
sont ignorés par git.

### Variables

Elles se posent dans le fichier d'environnement de Kr. Les chiffres sont des réglages d'exploitation, pas des
chiffres du jeu : le jeu reste dans `definitions/`. Le modèle est `deploy/exemple.env.txt`.

| Variable | Lue par | Défaut | Origine |
| --- | --- | --- | --- |
| `SILOOO_DB_URL`, `SILOOO_DB_USER`, `SILOOO_DB_PASSWORD` | `silooo-migrate`, `silooo-core` | aucun, obligatoires | [core/README.md](../core/README.md), base partagée ([ADR 0003](adr/0003-base-partagee.md)) |
| `SILOOO_API_KEY` | `silooo-core` ; `silooo-bds` la range dans `secrets.json` | aucun, obligatoire | en-tête `X-Silooo-Key` du contrat |
| `BDS_PORT` | `silooo-bds` | `19142` | port TCP de signalisation NetherNet ([ADR 0004](adr/0004-port-bedrock.md)) |
| `BDS_UDP_PORTS` | `silooo-bds` | `19143-19174` | plage UDP du jeu dans le conteneur (`server-udp-ports`) : 32 ports pour 20 joueurs, à confirmer libre par Kr |
| `BDS_UDP_PORTS_PUBLIES` | `silooo-bds` | `19143-19174` | plage UDP publiée sur le VPS, de la même taille |
| `BDS_PUBLIC_IP` | `silooo-bds` | vide | l'IPv4 publique du VPS, annoncée aux clients ; vide = avertissement au démarrage |
| `BDS_MAX_PLAYERS` | `silooo-bds` | `20` | réglage d'exploitation, à ajuster à la mesure de mémoire de la phase 2 |
| `BDS_LEVEL_NAME` | `silooo-bds` | `silooo` | nom du dossier du monde dans le volume |
| `BDS_ALLOW_LIST` | `silooo-bds` | `true` | liste blanche tant que le serveur n'est pas ouvert (phase 6) |
| `BDS_SERVER_NAME` | `silooo-bds` | `Silooo` | texte vu par les joueurs : à valider par Kr |
| `SILOOO_CORE_URL` | `silooo-bds` | `http://silooo-core:8080/` | adresse de silooo-core sur le réseau `silooo` |
| `SILENEMC_NETWORK` | Compose et les scripts | `silenemc_default` | nom du réseau de SileneMC : une hypothèse, Kr la confirme avec `docker network ls` |
| `SILOOO_TAG` | Compose | aucun | posé par `deploy.sh` à chaque appel, jamais dans le fichier d'environnement |

Pas de guillemets autour des valeurs : Compose les retire, mais `docker run --env-file` (utilisé par
`deploy.sh` et `sauvegarde.sh`) les garde.

### Ports : TCP et UDP

Avec NetherNet, `server-port` n'est plus le port du jeu : c'est un port TCP de signalisation HTTP, et le jeu passe
en UDP, un port par joueur, dans la plage de `server-udp-ports` ([bedrock.md](bedrock.md), fait 5). Le compose
publie donc deux choses : `BDS_PORT` en TCP, et la plage `BDS_UDP_PORTS_PUBLIES:BDS_UDP_PORTS` en UDP. Kr ouvre
les deux dans le pare-feu du VPS. Derrière Docker, BDS doit connaître l'adresse publique du VPS : `BDS_PUBLIC_IP`
est écrite dans `server-udp-ports` sous la forme `IP:public:local`. Chaque port de la plage est publié par
un processus `docker-proxy` : leur coût en mémoire est à mesurer avec le reste.

### Le monde et « Beta APIs »

`@minecraft/server-net` et `@minecraft/server-admin` sont en bêta : le pack ne charge ses scripts que sur un monde
où l'option « Beta APIs » est activée. Un monde créé par BDS lui-même ne l'a pas, et `start.sh` le dit au
démarrage tant que le dossier du monde n'a pas de `level.dat`. La procédure recommandée, à vérifier sur un BDS de
test ([bedrock.md](bedrock.md), fait 2, « à relever ») :

1. Créer le monde dans un client Bedrock, avec l'option « Beta APIs » activée, sous le nom de `BDS_LEVEL_NAME`.
2. Exporter le monde, en extraire le contenu (`level.dat`, `db/`) et l'envoyer sur le VPS.
3. Le copier dans le volume `silooo-bds-worlds`, sur le VPS (le nom du dossier est `BDS_LEVEL_NAME`, ici `silooo`) :

   ```bash
   docker run --rm -u 0 -v silooo-bds-worlds:/data/worlds -v /var/www/silooo/monde-depart:/entree:ro \
     --entrypoint sh ghcr.io/e-quantum/silooo-bds:<tag> \
     -c 'mkdir -p /data/worlds/silooo && cp -a /entree/. /data/worlds/silooo/ && chown -R 10001:10001 /data/worlds'
   ```

Au démarrage, `start.sh` écrit `world_behavior_packs.json` dans ce dossier pour activer le pack, et garde les
autres packs s'il y en a.

## Envoyer les fichiers tant que le VPS n'a pas d'accès GitHub

Le dépôt n'est pas cloné sur le VPS. Les fichiers de déploiement partent du PC :

```bash
rsync -av --exclude .env --exclude .tag-courant deploy/ ubuntu@silenemc.eu:/var/www/silooo/deploy/
```

ou `scp` fichier par fichier. Jamais d'écrasement du fichier d'environnement (il est à la racine de
`/var/www/silooo`, hors de `deploy/`) ni de `deploy/.tag-courant` (le tag en place sur le VPS). La clé de
déploiement limitée au dépôt, recommandée par le plan, revient à Kr ; quand elle existera, le VPS tirera le
dépôt lui-même et cette section changera.

## Déployer par tag

Prérequis, posés par Kr une fois : le fichier d'environnement à la racine de `/var/www/silooo` (modèle :
`deploy/exemple.env.txt`), `docker login ghcr.io` avec une clé de lecture sur les paquets du dépôt, le réseau
de SileneMC, la base `silooo` et son compte, les ports ouverts.

1. Kr donne son accord et choisit le tag. Les images sont construites par la CI sur `main` et
   taguées par commit, sur le registre privé de GitHub.
2. Depuis la machine de Kr, une commande :

   ```bash
   ssh ubuntu@silenemc.eu "cd /var/www/silooo && bash deploy/scripts/deploy.sh <tag>"
   ```

   Si le compte n'est pas dans le groupe `docker`, `sudo` devant `bash`. Sans tag, le script refuse (code 64) et ne
   touche à rien. Il enchaîne : `free -m` et `df -h /` (refus sous 800 Mo
   disponibles), sauvegarde de la base et du monde, tirage des images, `silooo-migrate`, puis démarrage de
   `silooo-core` et `silooo-bds` seuls (`--no-deps`).
3. Test de fumée : `POST /v1/server/heartbeat` sur silooo-core, depuis un conteneur sur le réseau `silooo`,
   avec la clé d'API lue par le conteneur (elle ne passe sur aucune ligne de commande de l'hôte). Il doit
   répondre `204` dans les 60 secondes. Il ne vérifie pas encore une session de joueur (une session de test
   écrirait dans la base) : ce contrôle viendra avec le premier scénario de jeu.
4. Si le test échoue, le script remet le tag précédent (sans rejouer la migration) et sort en erreur : code 1 si
   le retour arrière a réussi, code 2 s'il n'est pas confirmé. Sans tag précédent (premier déploiement), il
   laisse les conteneurs en l'état pour lire leurs journaux et sort avec le code 1.
5. `free -m` après le déploiement. Si la mémoire disponible passe sous 800 Mo, arrêter les
   conteneurs `silooo` et le noter dans [passation.md](passation.md).
6. Nettoyage des images Docker inutiles (anciennes images de `silooo-core` et `silooo-bds`, images sans
   étiquette de Silooo) : le disque est serré. Le script ne touche qu'aux images de Silooo.
7. Le pack de ressources et l'add-on changent avec le serveur Bedrock, jamais seuls : l'add-on est dans l'image
   `silooo-bds`.

Si la base `silooo` n'existe pas encore, on ne déploie pas silooo-core, et on le note dans « À faire par Kr ».

## Revenir en arrière

- Automatique : si le test de fumée échoue, `deploy.sh` remet le tag précédent, lu dans `deploy/.tag-courant` ou
  dans le conteneur `silooo-core` en cours.
- À la main : rejouer `deploy.sh` avec le tag précédent. Les images précédentes restent sur le registre.
- Une migration ne se défait pas : chaque migration reste compatible avec la version précédente du
  service ([base-de-donnees.md](base-de-donnees.md)). Un retour arrière du service ne touche pas la
  base.
- Si le monde est abîmé, restaurer la sauvegarde prise au début du déploiement, serveur arrêté.
- Noter le retour arrière dans le journal de [passation.md](passation.md), avec la cause.

## Sauvegardes

- `deploy/scripts/sauvegarde.sh [dossier]`, lancé par `deploy.sh` avant chaque déploiement et utilisable seul :
  un dump de la base `silooo` (client `mariadb-dump` dans un conteneur jetable, sur le réseau de SileneMC, avec
  les identifiants du fichier d'environnement) et une archive du volume du monde. Par défaut, dans
  `sauvegardes/AAAAMMJJ-HHMMSS/` sous le projet, dossier ignoré par git. Le script refuse un dump qui ne finit pas par
  la ligne « Dump completed ».
- La copie du monde se fait serveur allumé : elle peut attraper un état à cheval sur deux écritures de BDS. Pour une
  restauration sûre du monde, arrêter `silooo-bds` d'abord. Le script ne purge pas les anciennes sauvegardes : la
  purge se fait à la main, le disque est serré.
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
« À faire par Kr ». Pour le déploiement de la phase 2, en plus :

- le fichier d'environnement à la racine de `/var/www/silooo`, rempli d'après `deploy/exemple.env.txt` ;
- la clé de lecture des paquets GitHub, pour `docker login ghcr.io` sur le VPS ;
- le nom du réseau Docker de SileneMC (`docker network ls`), si ce n'est pas `silenemc_default` ;
- l'ouverture du port TCP `BDS_PORT` et de la plage UDP `BDS_UDP_PORTS_PUBLIES` dans le pare-feu du VPS, après
  avoir vérifié que cette plage est libre ;
- l'adresse IPv4 publique du VPS (`BDS_PUBLIC_IP`) et le DNS ;
- le monde de départ créé avec « Beta APIs » (section « Le monde et Beta APIs ») ;
- la validation du nom du serveur (`BDS_SERVER_NAME`, « Silooo » par défaut) et du choix de la liste blanche.
