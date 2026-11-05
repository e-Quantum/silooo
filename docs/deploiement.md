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
| `deploy/bds/Dockerfile` | L'image `silooo-bds` : l'add-on construit, `start.sh` et les outils (`unzip`, `jq`, `curl`). Pas de BDS dedans (décision 26) |
| `deploy/bds/bds.sha256` | La somme SHA-256 du zip de BDS que Kr pose, à écrire par Kr ; `start.sh` la contrôle au démarrage |
| `deploy/bds/bds.md5` | Le Content-MD5 annoncé par Mojang pour ce zip, contrôlé tant que la somme SHA-256 n'est pas épinglée |
| `deploy/bds/start.sh` | L'entrée du conteneur : trouve et contrôle le zip de BDS, le décompresse une fois, écrit `server.properties`, les permissions, `variables.json` et `secrets.json`, puis lance BDS |
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
| `SILOOO_VOTIFIER_TOKEN` | `silooo-core` (par `env_file`) | vide | le jeton de Votifier 2, avec lequel Hubyy signe les votes ; secret, à poser aussi sur Hubyy ; vide : `silooo-core` n'écoute pas (section « Les votes ») |
| `SILOOO_VOTIFIER_PORT` | `silooo-core` (par `env_file`), Compose | `8192` | port TCP de la réception des votes, le même dans le conteneur et sur le VPS ; différent de 8080 et de `SILOOO_SITE_PORT` |
| `SILOOO_SITE_MODE` | `silooo-core` (par `env_file`) | `attente` | le mode du site silooo.com : `attente` (page d'attente et mentions), `beta` (tout sauf la vitrine : `/` reste la page d'attente) ou `ouvert` ; toute autre valeur arrête le service. **Seul Kr passe à `beta` ou à `ouvert`, sur le VPS : jamais une session, jamais par un commit** (`CLAUDE.md`) |
| `SILOOO_SITE_PORT` | `silooo-core` (par `env_file`) | `8081` | port du site dans le conteneur, servi par le Caddy de SileneMC (bloc dans [docs/site-passation.md](site-passation.md)) ; différent de 8080 et de `SILOOO_VOTIFIER_PORT` ; le compose ne le publie pas |
| `SILOOO_SITE_URL` | `silooo-core` (par `env_file`) | `https://silooo.com` | l'adresse publique du site, qui commence par `http` ; sert au cookie `Secure` et au retour de « Relier Discord » |
| `SILOOO_SITE_URL_DISCORD` | `silooo-core` (par `env_file`) | vide | le lien d'invitation Discord de l'accueil et de la page d'attente ; vide : pas de lien |
| `SILOOO_SITE_ADRESSE_JEU`, `SILOOO_SITE_PORT_JEU` | `silooo-core` (par `env_file`) | `play.silooo.com`, `19142` | l'adresse et le port du jeu affichés sur l'accueil et Rejoindre ; le port est celui de `BDS_PORT` |
| `SILOOO_SITE_URL_BOUTIQUE` | `silooo-core` (par `env_file`) | vide | l'adresse de la boutique de Gemmes (PayNow) ; vide : pas de lien |
| `SILOOO_SITE_SITES_DE_VOTE` | `silooo-core` (par `env_file`) | vide | les sites de vote affichés, `nom\|url` séparés par des points-virgules ; une entrée mal formée arrête le service |
| `SILOOO_SITE_URL_SILENEMC` | `silooo-core` (par `env_file`) | vide | le site de SileneMC, dans « Voir aussi » au pied de chaque page ; vide : pas de lien |
| `SILOOO_SITE_URL_HUBYY` | `silooo-core` (par `env_file`) | vide | le site de Hubyy, à côté ; vide : pas de lien |
| `SILOOO_SITE_ADMISSION` | `silooo-core` (par `env_file`) | `discord` | l'admission des pionniers : `discord` (admis quand le compte Discord est relié) ou `tous` (dès l'inscription, pour l'ouverture) |
| `SILOOO_DISCORD_CLIENT_ID`, `SILOOO_DISCORD_CLIENT_SECRET` | `silooo-core` (par `env_file`) | vides | l'application Discord du bouton « Relier Discord » du site (OAuth ; ce n'est pas le bot de `docs/discord.md`) ; les deux ensemble, une seule arrête le service ; absentes : pas de bouton ; le secret ne se commite jamais |
| `SILOOO_XBOX_CLIENT_ID`, `SILOOO_XBOX_CLIENT_SECRET` | `silooo-core` (par `env_file`) | vides | l'application Azure de la connexion Xbox du site (comptes Microsoft personnels, autorité `consumers`, portée `XboxLive.signin`) ; les deux ensemble, une seule arrête le service ; absentes : pas de porte Xbox ; le secret ne se commite jamais |
| `SILOOO_SITE_TRUSTED_PROXY` | `silooo-core` (par `env_file`) | `false` | `true` : l'adresse du visiteur se lit dans `X-Forwarded-For`, posé par Caddy ; à passer à `true` derrière le Caddy de SileneMC |
| `SILOOO_PAYNOW_WEBHOOK_SECRET` | `silooo-core` (par `env_file`) | vide | la clé de signature des webhooks de PayNow (`POST /paynow/webhook`) ; secret, jamais dans le dépôt ; vide : le webhook répond 503 et aucune commande n'est livrée |
| `SILOOO_DISCORD_ACTIF` | `silooo-core` (par `env_file`) | `non` | le bot Discord Oooscar ([docs/discord.md](discord.md)) : `non` (pas de bot), `a-blanc` (le bot se connecte, compare le serveur au plan et n'écrit que son compte rendu dans le journal) ou `oui` ; toute autre valeur arrête le service |
| `SILOOO_DISCORD_TOKEN` | `silooo-core` (par `env_file`) | vide | le jeton du bot Oooscar, obligatoire dès que `SILOOO_DISCORD_ACTIF` n'est pas `non` ; secret, jamais dans le dépôt ni dans les journaux (le service le masque) |
| `SILOOO_DISCORD_SERVEUR` | `silooo-core` (par `env_file`) | vide | l'identifiant numérique du serveur Discord que le bot règle, obligatoire avec le jeton ; Kr y invite le bot avant de démarrer |
| `SILOOO_DISCORD_PHASE` | `silooo-core` (par `env_file`) | `attente` | la phase du serveur Discord (`attente`, `beta` ou `ouvert`) : le bot crée les rôles et salons de cette phase et des précédentes, et n'en supprime jamais |
| `SILOOO_DISCORD_PUBLIC` | `silooo-core` (par `env_file`) | `non` | le QG est ouvert au public : `non` (les messages privés du bot ne partent qu'au staff, ses mentions de rôle ne visent que les rôles Staff et Modération) ou `oui` ; toute autre valeur arrête le service ; reste à `non` jusqu'à l'ouverture du QG |
| `BDS_PORT` | `silooo-bds` | `19142` | port TCP de signalisation NetherNet ([ADR 0004](adr/0004-port-bedrock.md)) |
| `BDS_UDP_PORTS` | `silooo-bds` | `19143-19174` | plage UDP du jeu dans le conteneur (`server-udp-ports`) : 32 ports pour 20 joueurs, à confirmer libre par Kr |
| `BDS_UDP_PORTS_PUBLIES` | `silooo-bds` | `19143-19174` | plage UDP publiée sur le VPS, de la même taille |
| `BDS_PUBLIC_IP` | `silooo-bds` | vide | l'IPv4 publique du VPS, annoncée aux clients ; vide = avertissement au démarrage |
| `BDS_MAX_PLAYERS` | `silooo-bds` | `20` | réglage d'exploitation, à ajuster à la mesure de mémoire de la phase 2 |
| `BDS_LEVEL_NAME` | `silooo-bds` | `silooo` | nom du dossier du monde dans le volume |
| `BDS_ALLOW_LIST` | `silooo-bds` | `true` | liste blanche tant que le serveur n'est pas ouvert (phase 6) |
| `BDS_SERVER_NAME` | `silooo-bds` | `Silooo` | texte vu par les joueurs : à valider par Kr |
| `SILOOO_CORE_URL` | `silooo-bds` | `http://silooo-core:8080/` | adresse de silooo-core sur le réseau `silooo` |
| `SILOOO_BDS_DIR` | Compose et `deploy.sh` | `/var/www/silooo/bds` | dossier de l'hôte monté sur `/data/bds`, où Kr pose le zip de BDS ; propriété de l'uid 10001. Facultatif ; s'il change, l'exporter aussi dans la session avant `deploy.sh` |
| `SILENEMC_NETWORK` | Compose et les scripts | `silenemc_default` | nom du réseau de SileneMC : une hypothèse, Kr la confirme avec `docker network ls` |
| `SILOOO_TAG` | Compose | aucun | posé par `deploy.sh` à chaque appel, jamais dans le fichier d'environnement |

Pas de guillemets autour des valeurs : Compose les retire, mais `docker run --env-file` (utilisé par
`deploy.sh` et `sauvegarde.sh`) les garde.

### Le zip de BDS

Décision 26 ([passation.md](passation.md)) : l'image `silooo-bds` ne contient pas le serveur Bedrock. Depuis un script,
minecraft.net ne répond pas : 0 octet puis un délai dépassé, vu depuis le PC de Kr et depuis la CI GitHub, avec un
User-Agent qui dit ce qu'il est. On ne le déguise pas en navigateur, et on n'héberge pas le zip : les conditions
d'utilisation de BDS interdisent de le redistribuer. Kr télécharge donc le zip dans un navigateur et le pose sur le VPS ;
`start.sh` le contrôle et le décompresse au démarrage du conteneur.

La procédure de Kr, une fois, puis à chaque nouvelle version de BDS :

1. Dans un navigateur, ouvrir <https://www.minecraft.net/en-us/download/server/bedrock> et télécharger la version
   Linux : `bedrock-server-1.26.62.1.zip` (le nom suit `ARG BDS_VERSION` de `deploy/bds/Dockerfile`).
2. Le poser sur le VPS, dans le dossier `SILOOO_BDS_DIR` (`/var/www/silooo/bds` par défaut), propriété de l'uid 10001
   qui est celui du conteneur (sans cela `start.sh` s'arrête avec le code 73) :

   ```bash
   scp bedrock-server-1.26.62.1.zip ubuntu@silenemc.eu:/tmp/
   ssh ubuntu@silenemc.eu "sudo install -d -o 10001 -g 10001 /var/www/silooo/bds && \
     sudo install -o 10001 -g 10001 -m 644 /tmp/bedrock-server-1.26.62.1.zip /var/www/silooo/bds/ && \
     rm /tmp/bedrock-server-1.26.62.1.zip"
   ```

3. Calculer la somme SHA-256 du fichier (`sha256sum bedrock-server-1.26.62.1.zip`, ou sous PowerShell
   `(Get-FileHash -Algorithm SHA256 .\bedrock-server-1.26.62.1.zip).Hash.ToLower()`) et l'écrire à la place des zéros
   dans la dernière ligne de `deploy/bds/bds.sha256`, puis committer : la CI refait l'image et le tag suivant porte la
   somme. Tant que ce n'est pas fait, `start.sh` contrôle le MD5 de `deploy/bds/bds.md5` (celui que Mojang annonce) et
   écrit dans le journal du conteneur la ligne à épingler : `docker logs silooo-silooo-bds-1`.
4. Déployer : `bash deploy/scripts/deploy.sh <tag>`. Le script refuse si le dossier n'existe pas, et avertit s'il ne
   contient pas de zip.

Ce que fait `start.sh`, dans l'ordre : il cherche `/data/bds/bedrock-server-<version>.zip` ; s'il manque, il tente
le téléchargement (2 essais de 60 secondes, avec son User-Agent honnête), puis, en cas d'échec, s'arrête avec le code 66
et ce même mode d'emploi dans la sortie. Il contrôle la somme (65 si elle ne correspond pas : le fichier est refusé, pas
décompressé), décompresse une seule fois dans `/data/bds/<version>/` (la marque `.silooo-extrait` dit que c'est fini),
retire de cette copie `worlds/`, `config/`, `server.properties`, `allowlist.json` et `permissions.json` pour les
remplacer par des liens vers les volumes `/data/worlds` et `/data/config`, copie le pack de comportement de l'image
dans `behavior_packs/silooo` et son pack de ressources dans `resource_packs/silooo` à chaque démarrage, puis lance `./bedrock_server` depuis ce dossier. Une fois la copie
décompressée en place, le zip n'est plus relu : le supprimer libère du disque. Avec `restart: on-failure:5`, un zip
absent ou refusé ne fait pas boucler le conteneur : après cinq essais il reste arrêté, et `docker logs` dit pourquoi.

Pour changer de version de BDS ([bedrock.md](bedrock.md), « La procédure de mise à jour ») : `ARG BDS_VERSION` du
Dockerfile, le nom et les sommes de `bds.sha256` et de `bds.md5`, dans le même commit ; le nouveau zip dans
`SILOOO_BDS_DIR`. Le dossier de l'ancienne version peut se supprimer à la main une fois la nouvelle en service.

Les essais de `start.sh` sans Docker construisent un faux zip (`tools/tests/faire-zip.mjs`) et un faux `curl` :
zip présent avec la bonne somme, mauvaise somme refusée, zip absent et téléchargement impossible. La CI vérifie que
BDS n'est pas dans l'image, que `start.sh` sort en 66 sans zip, et essaie de démarrer BDS si la sonde de téléchargement
a pu récupérer le zip. Sinon cet essai est sauté et se fait au premier déploiement.

### Ports : TCP et UDP

Avec NetherNet, `server-port` n'est plus le port du jeu : c'est un port TCP de signalisation HTTP, et le jeu passe
en UDP, un port par joueur, dans la plage de `server-udp-ports` ([bedrock.md](bedrock.md), fait 5). Le compose
publie donc deux choses : `BDS_PORT` en TCP, et la plage `BDS_UDP_PORTS_PUBLIES:BDS_UDP_PORTS` en UDP. Kr ouvre
les deux dans le pare-feu du VPS. Derrière Docker, BDS doit connaître l'adresse publique du VPS : `BDS_PUBLIC_IP`
est écrite dans `server-udp-ports` sous la forme `IP:public:local`. Chaque port de la plage est publié par
un processus `docker-proxy` : leur coût en mémoire est à mesurer avec le reste.

Un troisième port sort, en TCP : `SILOOO_VOTIFIER_PORT` (8192 par défaut), celui de `silooo-core` pour les votes de Hubyy
(section « Les votes »). Kr l'ouvre aussi dans le pare-feu du VPS.

### Les votes

Hubyy envoie chaque vote pour le serveur à `silooo-core` par Votifier 2, en TCP. C'est le seul port de `silooo-core` que le compose
publie : l'API (8080) et le site (8081) restent sur les réseaux Docker. Ce que fait un vote est dans
[economie.md](economie.md), section « Les votes » ; le code, dans [core/README.md](../core/README.md), « Les votes côté service ».

- Le service n'écoute que si `SILOOO_VOTIFIER_TOKEN` est posé dans le fichier d'environnement. Sans jeton, le port est publié par
  Docker mais rien n'y répond, et le démarrage écrit « Votifier éteint » dans les journaux.
- Le port est `SILOOO_VOTIFIER_PORT` (8192 par défaut), le même numéro dans le conteneur et sur le VPS. Il doit différer de 8080 et de
  `SILOOO_SITE_PORT` ; le service refuse de démarrer sinon. Le défaut du compose et celui de `Config.kt` sont comparés par
  `tools/tests/deploy-compose.mjs`.
- Docker publie ce port avec ses propres règles `iptables`, que le pare-feu de l'hôte (ufw) ne filtre pas toujours : il est joignable dès que le
  conteneur tourne. Kr vérifie l'état du pare-feu après le premier déploiement, comme pour la plage UDP.
- Le jeton est un secret : une valeur aléatoire longue (par exemple la sortie de `openssl rand -hex 32`), posée dans le fichier
  d'environnement et sur Hubyy, nulle part ailleurs. Aucune session ne l'écrit dans le dépôt, dans un journal ni dans un message ; le
  service ne l'affiche jamais. Pour le changer, Kr le remplace aux deux endroits puis relance `silooo-core`.
- Au démarrage avec un jeton, le journal de `silooo-core` dit « Votifier : à l'écoute sur le port N ». Chaque vote reçu y laisse une
  ligne (le site et le pseudo), chaque refus une ligne d'avertissement avec sa cause et l'adresse de l'émetteur.
- Un vote que la base n'a pas pu enregistrer reçoit la cause `Unavailable` : Hubyy peut le renvoyer. Rien n'est versé deux fois.

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

Au démarrage, `start.sh` écrit `world_behavior_packs.json` et `world_resource_packs.json` dans ce dossier pour activer
les deux packs, et garde les autres packs de chaque liste s'il y en a. Le pack de ressources (`resource-pack/`) est dans
l'image, comme l'add-on : `texturepack-required=true` fait que le joueur doit l'accepter pour se connecter, parce que
le HUD en dépend ([design.md](design.md), « Où vont les fichiers dans `resource-pack/` »).

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
de SileneMC, la base `silooo` et son compte, les ports ouverts, le zip de BDS dans `SILOOO_BDS_DIR` (section « Le zip
de BDS »).

1. Kr donne son accord et choisit le tag. Les images sont construites par la CI sur `main` et
   taguées par commit, sur le registre privé de GitHub.
2. Depuis la machine de Kr, une commande :

   ```bash
   ssh ubuntu@silenemc.eu "cd /var/www/silooo && bash deploy/scripts/deploy.sh <tag>"
   ```

   Si le compte n'est pas dans le groupe `docker`, `sudo` devant `bash`. Sans tag, le script refuse (code 64) et ne
   touche à rien. Il enchaîne : `free -m` et `df -h /` (refus sous 800 Mo
   disponibles, ou si le dossier `SILOOO_BDS_DIR` n'existe pas ; avertissement s'il ne contient pas de zip), sauvegarde de la base et du monde, tirage des images, `silooo-migrate`, puis démarrage de
   `silooo-core` et `silooo-bds` seuls (`--no-deps`).
3. Test de fumée : `POST /v1/server/heartbeat` sur silooo-core, depuis un conteneur sur le réseau `silooo`,
   avec la clé d'API lue par le conteneur (elle ne passe sur aucune ligne de commande de l'hôte). Il doit
   répondre `204` dans les 60 secondes. Il ne vérifie pas encore une session de joueur (une session de test
   écrirait dans la base) : ce contrôle viendra avec le premier scénario de jeu. Il ne regarde pas non plus
   `silooo-bds` : au premier déploiement, lire son journal (`docker logs silooo-silooo-bds-1`) pour voir que le zip a été
   accepté et que BDS a démarré.
4. Si le test échoue, le script remet le tag précédent (sans rejouer la migration) et sort en erreur : code 1 si
   le retour arrière a réussi, code 2 s'il n'est pas confirmé. Sans tag précédent (premier déploiement), il
   laisse les conteneurs en l'état pour lire leurs journaux et sort avec le code 1.
5. `free -m` après le déploiement. Si la mémoire disponible passe sous 800 Mo, arrêter les
   conteneurs `silooo` et le noter dans [passation.md](passation.md).
6. Nettoyage des images Docker inutiles (anciennes images de `silooo-core` et `silooo-bds`, images sans
   étiquette de Silooo) : le disque est serré. Le script ne touche qu'aux images de Silooo.
7. Le pack de ressources et l'add-on changent avec le serveur Bedrock, jamais seuls : l'add-on et le pack de
   ressources sont dans l'image `silooo-bds`.

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
- Le dossier du zip de BDS (`SILOOO_BDS_DIR`) n'est pas sauvegardé : il se refait en retéléchargeant le zip. Seuls
  la base et le volume du monde comptent.

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
- le zip de BDS : le télécharger dans un navigateur, le poser dans `SILOOO_BDS_DIR` (propriété de l'uid 10001), écrire
  sa somme SHA-256 dans `deploy/bds/bds.sha256` (section « Le zip de BDS », décision 26) ;
- le monde de départ créé avec « Beta APIs » (section « Le monde et Beta APIs ») ;
- la validation du nom du serveur (`BDS_SERVER_NAME`, « Silooo » par défaut) et du choix de la liste blanche ;
- pour les votes (section « Les votes ») : le jeton `SILOOO_VOTIFIER_TOKEN` dans le fichier d'environnement, l'ouverture du port TCP
  `SILOOO_VOTIFIER_PORT` (8192 par défaut) dans le pare-feu du VPS, et la déclaration de Silooo sur Hubyy avec l'adresse du VPS, ce
  port et ce jeton (Votifier 2). Les champs du formulaire sont ceux de Hubyy : Kr les confirme.
