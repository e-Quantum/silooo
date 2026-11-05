# Bedrock : ce que la Script API permet, et ce qui casse à chaque version

Silooo tourne sur le serveur Bedrock officiel (BDS), avec un add-on TypeScript et un pack de
ressources. Ce fichier dit ce que la plate-forme permet, ce qu'elle interdit, et comment on suit
ses versions. Version épinglée : Bedrock 26.60, relevée le 5 novembre 2026. Tout ce qui est marqué
« à relever » se vérifie sur le BDS de cette version avant d'être écrit dans le code.

## Les pièces

| Pièce | Dans le dépôt | Rôle |
| --- | --- | --- |
| BDS | image du conteneur `silooo-bds`, dossier `deploy/` | Le serveur de jeu, même version que les clients |
| Pack de comportement | `addon/` | Le code TypeScript (Script API), compilé par esbuild en un seul fichier de scripts, plus les entités et structures |
| Pack de ressources | `resource-pack/` | Textures, JSON UI, police bitmap, modèle et animations d'Oooscar, sons |
| silooo-core | `core/` | Toutes les règles ; l'add-on l'appelle en HTTP |

Les deux packs sont livrés avec le serveur et téléchargés par le joueur à la connexion. Ils changent
avec le serveur, jamais seuls.

## Ce que la Script API permet

- Réagir aux événements du monde : connexion, déconnexion, interaction avec un bloc ou une entité,
  bloc cassé, objet jeté dans un conteneur.
- Faire tourner du code à chaque tick ou à intervalle : c'est ainsi que l'add-on fait défiler le
  compteur de Donar et interroge `GET /v1/events`.
- Ouvrir des formulaires serveur (module `@minecraft/server-ui`) : liste de boutons, formulaire
  modal, message à deux choix. Le pack de ressources les redessine, voir plus bas.
- Afficher du texte par les canaux de titre, sous-titre et barre d'action : c'est par eux que le HUD
  et les sous-titres de cinématique reçoivent leurs données.
- Lancer des commandes : caméra pour les cinématiques, masquage du HUD, téléportation, pose de
  structures préfabriquées, particules et sons.
- Faire apparaître des entités définies par l'add-on : Oooscar, les salariés visibles, les
  véhicules chevauchables, les camions.
- Garder des propriétés dynamiques sur le monde ou les joueurs. Silooo ne s'en sert pas pour l'état
  du jeu : tout l'état vit dans silooo-core. Elles peuvent servir au confort local (dernier menu
  ouvert), à relever.

## Ce que la Script API ne permet pas

- Aucune requête entrante : le serveur ne peut pas être appelé par silooo-core. D'où l'interrogation
  régulière de `GET /v1/events` ([architecture.md](architecture.md), règle 8).
- Aucun accès au système de fichiers, à une base de données, à des sockets bruts ni à des threads.
- Aucun calcul long : un chien de garde interrompt un script qui bloque le tick. Les calculs
  d'économie restent dans silooo-core, l'add-on ne fait qu'additionner pour l'affichage.
- Aucune interface dessinée par le script : l'apparence des formulaires et du HUD vient du pack de
  ressources, pas du code.
- Aucun survol sur mobile et console : pas d'info-bulle. Une tuile s'ouvre en fiche.
- Pas de dépendance npm à l'exécution : le script compilé doit être autonome. esbuild regroupe tout.

## `@minecraft/server-net` et `@minecraft/server-admin`

- Les deux modules n'existent que sur BDS, jamais sur un client ni dans un monde local.
- `server-net` fait des requêtes HTTP sortantes : c'est le seul lien entre l'add-on et silooo-core.
  Il doit être autorisé dans la configuration des permissions de BDS (fichier de permissions du
  dossier `config/`), sinon le module ne se charge pas. Chemin et format exacts : à relever pour la
  version épinglée.
- `server-admin` lit les variables et les secrets posés par Kr dans le dossier `config/` de BDS. La
  clé `X-Silooo-Key` vient de là, comme une valeur secrète qu'on met dans un en-tête sans jamais
  l'afficher. Aucune session ne lit ce fichier.
- Statut des deux modules (bêta ou stable) et numéros de version à mettre dans le manifeste de
  l'add-on : à relever pour la version épinglée. Un module bêta demande en général d'activer une
  option expérimentale sur le monde, ce qui pèse sur chaque mise à jour : à relever aussi.
- Le temps de réponse de l'appel add-on vers silooo-core doit rester imperceptible : à mesurer en
  phase 2 sur le VPS.

## JSON UI : `server_form.json` et `hud_screen.json`

- Le pack de ressources contient un dossier `ui/` dont les fichiers redéfinissent les écrans du jeu.
  Silooo n'en touche que deux : `server_form.json` (l'apparence des formulaires serveur) et
  `hud_screen.json` (les éléments ajoutés autour de la barre d'inventaire).
- Les formulaires serveur deviennent les menus à tuiles et les fiches du design system. Un bouton de
  formulaire porte un texte et une icône ; le pack le dessine comme une tuile.
- Le HUD reçoit ses données par le canal de titre, avec un préfixe que le pack reconnaît et masque.
  Les règles sont dans [design/composants/Hud.md](../design/composants/Hud.md).
- Un fichier JSON UI casse souvent d'une version à l'autre, parce qu'il surcharge des fichiers du
  jeu qui changent. C'est le premier point à retester à chaque version.
- La police bitmap et les textures 9-slice suivent [design.md](design.md).

## Réseau : NetherNet, RakNet, le port

- Bedrock a deux transports : RakNet, le transport historique en UDP, et NetherNet, plus récent,
  qui a besoin d'une signalisation en plus. La version 26.60 a forcé SileneMC à passer en NetherNet
  (ADR 0121 de SileneMC, hors dépôt). Ce que BDS 26.60 exige exactement pour accepter les deux :
  à relever pour la version épinglée.
- Le port Bedrock par défaut, 19132, est pris sur le VPS par le Geyser de SileneMC. Silooo ne peut
  pas l'utiliser sur la même IP. Le choix du port est l'[ADR 0004](adr/0004-port-bedrock.md) :
  recommandation 19142 pour la bêta, en UDP pour le jeu et en TCP pour la signalisation, et une IP
  en plus avant l'ouverture si le port gêne. Ouverture du port et DNS : par Kr.
- silooo-core n'est jamais exposé : il n'écoute que sur le réseau Docker.
- Au-delà d'une instance, un proxy Bedrock répartit les joueurs ; sa compatibilité avec BDS est à
  valider avant d'en avoir besoin.

## Ce qui casse à chaque version

- Les numéros de version des modules dans le manifeste de l'add-on : un module absent ou trop vieux
  empêche le chargement des scripts.
- Les API bêta : renommées, déplacées ou supprimées sans préavis. Tout appel bêta de l'add-on est
  listé dans un seul fichier, pour être retesté d'un coup.
- Les fichiers JSON UI surchargés par le pack, quand le jeu change les siens.
- Le format des packs (`format_version` des entités, modèles et animations).
- Le protocole réseau, donc le Geyser de SileneMC et un éventuel proxy.
- Le comportement des commandes caméra et HUD, utilisées par les cinématiques.
- Les consoles et les mobiles se mettent à jour tout seuls, souvent le jour même : le serveur doit
  suivre en quelques heures, sinon les joueurs ne peuvent plus se connecter.

## La procédure de mise à jour

Jamais automatique. Le serveur reste sur l'ancienne version tant que l'add-on n'a pas été testé.

1. Lire les notes de version de Bedrock et de la Script API ; noter les modules qui changent.
2. Monter un BDS de test à la nouvelle version, sur le PC ou dans la CI, avec le monde de test.
3. Monter les versions de modules du manifeste, recompiler l'add-on, charger les deux packs.
4. Retester dans l'ordre : chargement des scripts, premier appel à silooo-core, formulaires et
   HUD redessinés, cinématique d'arrivée, entité Oooscar, véhicules, pose de structures.
5. Mettre à jour l'image BDS dans `deploy/`, épingler la version, noter la date dans ce fichier.
6. Sauvegarde du monde et de la base, puis déploiement par tag ([deploiement.md](deploiement.md)).
7. Test de fumée depuis un vrai client, console de préférence.

Une mise à jour qui casse l'add-on sans solution rapide se traite ainsi : le serveur reste sur
l'ancienne version, on l'annonce aux joueurs, et le correctif passe en priorité.

## À relever pour la version épinglée

- Statut et numéros de version des modules `server`, `server-ui`, `server-net`, `server-admin`.
- Chemin et format du fichier de permissions qui autorise `server-net`.
- Ce que BDS demande pour NetherNet et RakNet côté ports et signalisation.
- Limites utiles : taille maximale d'un pack, nombre d'entités avant ralentissement sur Switch et
  vieux mobiles, budget de tick du chien de garde.
