# deploy/ : ce qui part sur le VPS

Les fichiers qui font tourner Silooo sur le VPS : l'image qui lance le serveur Bedrock (sans le contenir, décision 26),
le projet Docker Compose, les scripts de déploiement et de sauvegarde. Le détail (ports, monde, retour arrière, interdits) est dans
[docs/deploiement.md](../docs/deploiement.md).

## Ce qui est où

| Chemin | Rôle |
| --- | --- |
| `compose.yml` | Projet `silooo` : `silooo-migrate`, `silooo-core`, `silooo-bds`, leurs limites de mémoire, réseaux et volumes |
| `exemple.env.txt` | Modèle du fichier d'environnement, avec des valeurs fausses et une ligne d'explication par variable |
| `bds/Dockerfile` | Image `silooo-bds` : l'add-on construit, `start.sh` et ses outils ; sans le serveur Bedrock ; construite par la CI |
| `bds/bds.sha256` | Somme SHA-256 du zip de Bedrock Dedicated Server, à écrire par Kr ; contrôlée au démarrage du conteneur |
| `bds/bds.md5` | Content-MD5 annoncé par Mojang pour ce zip ; contrôlé tant que la somme SHA-256 n'est pas épinglée |
| `bds/start.sh` | Entrée du conteneur : trouve, contrôle et décompresse le zip de BDS, écrit la configuration à partir des variables, puis lance le serveur |
| `bds/server.properties.template` | Gabarit de `server.properties` |
| `scripts/deploy.sh` | Déploie un tag : sauvegarde, tirage, migrations, démarrage, test de fumée, retour arrière |
| `scripts/sauvegarde.sh` | Dump de la base `silooo` et archive du monde |
| `scripts/commun.sh` | Réglages communs aux deux scripts |

Les tests de ces fichiers sont dans `tools/tests/` (`deploy-start.sh`, avec `faire-zip.mjs` qui fabrique un faux zip de
BDS, `deploy-deploy.sh`, `deploy-compose.mjs`) et tournent dans `tools/verifier.sh`, sans Docker. La CI contrôle en plus
les scripts avec ShellCheck, lit le compose avec Docker Compose, construit l'image `silooo-bds` sans toucher au réseau
de Mojang et vérifie que BDS n'y est pas.

## Lancer

Sur le VPS, dans `/var/www/silooo`, une fois les prérequis posés par Kr (plus bas) :

```bash
bash deploy/scripts/deploy.sh <tag>      # le tag est le SHA d'un commit de main, dont la CI a poussé les images
bash deploy/scripts/sauvegarde.sh        # une sauvegarde seule, sans déployer
```

Sans tag, `deploy.sh` refuse. Il ne touche qu'aux conteneurs, images et volumes de Silooo.

Le compose n'est lancé qu'à travers ces scripts. À la main, c'est toujours le même appel (le fichier d'environnement
est lu à la racine du projet, d'où `--project-directory`) :

```bash
SILOOO_TAG=<tag> docker compose -p silooo --project-directory /var/www/silooo -f deploy/compose.yml ps
```

## Ce que Kr doit fournir

- La base `silooo` sur la MariaDB de SileneMC, et son compte (`SILOOO_DB_USER`, `SILOOO_DB_PASSWORD`).
- Le fichier d'environnement, à la racine de `/var/www/silooo`, d'après `exemple.env.txt`. Une session ne le lit ni ne
  le crée. La clé d'API (`SILOOO_API_KEY`) est une valeur aléatoire longue.
- Le nom du réseau Docker de SileneMC (`docker network ls`), si ce n'est pas `silenemc_default` (`SILENEMC_NETWORK`).
- `docker login ghcr.io` sur le VPS, avec une clé qui lit les paquets du dépôt : les images y sont privées.
- Le port TCP `BDS_PORT` (19142 par défaut) et la plage UDP `BDS_UDP_PORTS_PUBLIES` (19143-19174 par défaut)
  ouverts dans le pare-feu, après vérification que cette plage est libre.
- L'adresse IPv4 publique du VPS (`BDS_PUBLIC_IP`) et le DNS.
- Le zip de BDS (décision 26, [docs/deploiement.md](../docs/deploiement.md), « Le zip de BDS »). Le serveur n'est pas dans
  l'image et minecraft.net refuse les téléchargements par script. Kr : (1) télécharger `bedrock-server-1.26.62.1.zip`
  dans un navigateur ; (2) le poser dans `SILOOO_BDS_DIR` (`/var/www/silooo/bds` par défaut), dossier de l'uid 10001
  (`chown 10001:10001`) ; (3) calculer sa somme avec `sha256sum` et l'écrire dans `bds/bds.sha256` à la place des
  zéros, puis committer ; (4) lancer `deploy.sh`. Sans somme épinglée, `start.sh` contrôle le MD5 de `bds/bds.md5` et
  écrit la somme à épingler dans le journal du conteneur.
- Un monde de départ créé avec « Beta APIs » (voir [docs/deploiement.md](../docs/deploiement.md)) : sans lui, les
  scripts de l'add-on ne se chargent pas.
- La validation du nom du serveur (`BDS_SERVER_NAME`, « Silooo » par défaut) : texte vu par les joueurs.

## Ce qui n'est pas encore éprouvé

Rien de ceci n'a tourné sur le VPS. La CI construit l'image, vérifie que `start.sh` s'arrête proprement sans zip, et ne
lance BDS que si une étape d'information a pu télécharger le zip ; le reste (liaison de
l'add-on à silooo-core, ports UDP sous Docker, consommation de mémoire réelle) se mesure au premier déploiement.
