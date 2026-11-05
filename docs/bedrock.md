# Bedrock : ce que la Script API permet, et ce qui casse à chaque version

Silooo tourne sur le serveur Bedrock officiel (BDS), avec un add-on TypeScript et un pack de
ressources. Ce fichier dit ce que la plate-forme permet, ce qu'elle interdit, et comment on suit
ses versions. Version épinglée : Bedrock 26.60, relevée le 5 novembre 2026. Les faits relevés sont
à la fin, section « Relevé pour la version 26.60 » ; ce qui reste marqué « à relever » se vérifie sur le BDS
de cette version avant d'être écrit dans le code.

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
  dossier `config/`), sinon le module ne se charge pas. Chemin et format exacts : fait 2 du
  relevé, en fin de fichier.
- `server-admin` lit les variables et les secrets posés par Kr dans le dossier `config/` de BDS. La
  clé `X-Silooo-Key` vient de là, comme une valeur secrète qu'on met dans un en-tête sans jamais
  l'afficher. Aucune session ne lit ce fichier.
- Les deux modules sont en bêta seulement en 26.60 (`1.0.0-beta` dans le manifeste) et demandent
  d'activer l'option « Beta APIs » sur le monde, ce qui pèse sur chaque mise à jour : fait 1 du relevé.
  La procédure pour activer cette option sur un monde de BDS reste à relever.
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
  (ADR 0121 de SileneMC, hors dépôt). Ce que BDS exige pour l'un et l'autre : fait 5 du
  relevé.
- Le port Bedrock par défaut, 19132, est pris sur le VPS par le Geyser de SileneMC. Silooo ne peut
  pas l'utiliser sur la même IP. Le choix du port est l'[ADR 0004](adr/0004-port-bedrock.md) :
  recommandation 19142 pour la bêta, en UDP pour le jeu et en TCP pour la signalisation, et une IP
  en plus avant l'ouverture si le port gêne. Avec NetherNet, 19142 est le port TCP de signalisation et le
  jeu passe par une plage UDP à fixer (fait 5 du relevé). Ouverture du port et DNS : par Kr.
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

## Relevé pour la version 26.60

Relevé le 5 novembre 2026, sur le web.

La version 26.60 existe : sortie le 27 octobre 2026, protocole 2225, serveur 1.26.60.6. Elle a reçu deux
correctifs. 26.61 (serveur 1.26.61.1, 29 octobre) puis 26.62 (serveur 1.26.62.1, 4 novembre, même
protocole 2225). La dernière stable publiée à ce jour est donc 26.62 ; les modules stables ne changent pas
d'un correctif à l'autre. Le build à installer sur le VPS est 1.26.62.1, sauf décision contraire ; les
trois builds restent téléchargeables (fait 4). Sources : [wiki 26.60](https://minecraft.wiki/w/Bedrock_Edition_26.60),
[wiki 26.62](https://minecraft.wiki/w/Bedrock_Edition_26.62), API de téléchargement (fait 4).

Fiabilité, notée en fin de ligne. **Officiel** : lu sur une page, un service ou un dépôt de Mojang ou de
Microsoft (learn.microsoft.com, registre npm, API de téléchargement, dépôt Mojang/bedrock-samples,
en-têtes HTTP de minecraft.net). **Tiers** : wiki, blog, dépôt d'un tiers ; à confirmer sur le BDS de test.
Les notes de version officielles de 26.60 (feedback.minecraft.net) répondent 403 et minecraft.net ne
répond pas : ce que ce fichier dit de leur contenu vient du wiki qui les cite. Le zip de BDS n'a pas été
téléchargé, donc ni son `server.properties` ni son `permissions.json` n'ont été lus.

### 1. Modules de la Script API et versions

| Module | Statut en 26.60 | `version` dans `dependencies` | Types npm (`package.json`) |
| --- | --- | --- | --- |
| `@minecraft/server` | stable | `2.11.0` | `2.11.0` (tag `latest`, 27 oct. 2026) |
| `@minecraft/server-ui` | stable | `2.3.0` | `2.3.0` (tag `latest`, 27 oct. 2026) |
| `@minecraft/server-net` | bêta seulement | `1.0.0-beta` | `1.0.0-beta.1.26.62-stable` (tag `beta`) ou `1.0.0-beta.1.26.60-stable` |
| `@minecraft/server-admin` | bêta seulement | `1.0.0-beta` | `1.0.0-beta.1.26.62-stable` (tag `beta`) ou `1.0.0-beta.1.26.60-stable` |

- `server` 2.11.0 et `server-ui` 2.3.0 sont les versions stables de 26.60 ; 2.12.0 et 2.4.0 sont en bêta.
  Pages en vue stable : [server](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server/minecraft-server?view=minecraft-bedrock-stable),
  [server-ui](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-ui/minecraft-server-ui?view=minecraft-bedrock-stable).
  Officiel.
- `server-net` et `server-admin` n'ont aucune page en vue stable, seulement en vue expérimentale, avec la
  mention « still in pre-release » : [server-net](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-net/minecraft-server-net?view=minecraft-bedrock-experimental),
  [server-admin](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-admin/minecraft-server-admin?view=minecraft-bedrock-experimental).
  Ces pages affichent `1.0.0-beta.1.26.70-preview.23` (la préversion, pas 26.60). La forme `1.0.0-beta`
  dans le manifeste vient de [wiki.bedrock.dev](https://wiki.bedrock.dev/scripting/script-net) et de
  [jaylydev](https://jaylydev.github.io/scriptapi-docs/features/secrets-and-variables.html). Statut :
  officiel. Forme du numéro : tiers, à confirmer au premier chargement du pack sur le BDS de test.
- Un module `-beta` oblige à activer l'option « Beta APIs » sur chaque monde qui utilise le pack, et son
  numéro peut changer à chaque version majeure sans mise à jour automatique :
  [Script Module Versioning](https://learn.microsoft.com/en-us/minecraft/creator/documents/scripting/versioning?view=minecraft-bedrock-stable).
  Donc oui pour `server-net` et `server-admin`, non pour `server` et `server-ui` seuls. Officiel.
- Registre npm : tags `latest` de `server-net` et `server-admin` sur un vieux numéro (`1.0.0-beta.11940b24`),
  à ne pas utiliser ; épingler le numéro exact. `server-net` demande `@minecraft/server-admin` en pair, et
  `@minecraft/server` `^1.17.0 || ^2.0.0`. Autres pairs : `@minecraft/common` 1.3.0, `@minecraft/vanilla-data`
  1.26.60. Source : `https://registry.npmjs.org/@minecraft%2Fserver` (et `server-ui`, `server-net`,
  `server-admin`, `common`, `vanilla-data`). Officiel.

### 2. Permissions de BDS, `variables.json`, `secrets.json`

- Dossier de configuration : `config/` à côté de `bedrock_server`, créé au premier lancement. Le sous-dossier
  `config/default/` contient `permissions.json` pour tous les packs. Officiel :
  [Scripting Bedrock Dedicated Server](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/scripting?view=minecraft-bedrock-stable)
  (page mise à jour en juillet 2025).
- Variante par pack : `config/<uuid>/permissions.json`, où `<uuid>` est l'`uuid` du module de type `script`
  du manifeste (pas celui de l'en-tête). Même page, officiel. wiki.bedrock.dev écrit `<pack_id>` : tiers,
  à ne pas suivre.
- Contenu par défaut de `config/default/permissions.json` selon cette page : clé `allowed_modules`, liste de
  `@minecraft/server-gametest`, `@minecraft/server`, `@minecraft/server-ui`, `@minecraft/server-admin`,
  `@minecraft/server-editor`. `@minecraft/server-net` n'y figure pas : il faut l'ajouter, de préférence dans le
  fichier du pack (Learn recommande de garder le fichier par défaut minimal). Fichier livré avec 1.26.62.1 :
  à relever (zip non lu).
- Réglages HTTP de `server-net`, dans le même fichier, sous `module_permissions` :
  `{"allowed_modules": ["@minecraft/server-net"], "module_permissions": {"@minecraft/server-net":
  {"allowed_uris": ["https://example.com/"], "force_tls": true, "max_body_bytes": 1048576,
  "max_concurrent_requests": 1, "session_headers": {"session-data": "abc123"}}}}`. Toutes les options sont
  facultatives, une option absente n'est pas appliquée. `force_tls` s'appelait `force_https` en 1.26.10 et a
  été renommé en 1.26.20. Officiel : [notes 1.26.10](https://learn.microsoft.com/en-us/minecraft/creator/documents/update1.26.10?view=minecraft-bedrock-stable),
  [notes 1.26.20](https://learn.microsoft.com/en-us/minecraft/creator/documents/update1.26.20?view=minecraft-bedrock-stable).
  silooo-core parle en HTTP simple sur le réseau Docker : `force_tls` doit rester absent ou à `false`.
  Format de correspondance de `allowed_uris` et accès à un nom d'hôte Docker : à relever (non documentés).
- `variables.json` et `secrets.json` : dans `config/default/` ou dans `config/<uuid>/`. Officiel, même page.
  `variables.json` : objet JSON nom vers valeur, exemple officiel `{"mobSpawnRate": 22}`. `secrets.json` : le
  format n'est pas montré par Microsoft ; un tiers donne un objet nom vers chaîne, `{"secretToken": "<valeur>"}`
  ([jaylydev](https://jaylydev.github.io/scriptapi-docs/features/secrets-and-variables.html)). Tiers.
- Lecture dans le script : `import { variables, secrets } from "@minecraft/server-admin"`.
  `variables.get(name): unknown | undefined`, `secrets.get(name): SecretString | undefined`, et `names:
  string[]` sur les deux. `SecretString` est un marqueur : le script ne peut pas lire la valeur. Un
  `HttpHeader` (ou `HttpRequest.addHeader`) accepte `SecretString | string` comme valeur et la résout à
  l'envoi. `secrets.get` ne s'appelle pas en mode d'exécution restreinte. Officiel :
  [ServerSecrets](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-admin/serversecrets?view=minecraft-bedrock-experimental),
  [ServerVariables](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-admin/servervariables?view=minecraft-bedrock-experimental),
  [SecretString](https://learn.microsoft.com/en-us/minecraft/creator/scriptapi/minecraft/server-admin/secretstring?view=minecraft-bedrock-experimental).
- Procédure pour activer « Beta APIs » sur un monde de BDS : aucune page officielle trouvée. Deux tiers
  disent de créer le monde dans le client avec l'option, puis de le copier sur le serveur
  ([winternode](https://winternode.com/help/games/minecraft-bedrock/setup/enabling-experimental-features)) ;
  une édition de `level.dat` est évoquée, sans seconde source. À relever.

### 3. API de `@minecraft/server-net`

Source de tout ce fait, sauf mention : les pages `httprequest`, `httpclient`, `httpheader`, `httpresponse` et
`httprequestmethod` du dossier `server-net` de la référence Learn, en vue expérimentale (même URL de base que
la page du module, au fait 1). Officiel.

- `import { http, HttpRequest, HttpHeader, HttpRequestMethod } from "@minecraft/server-net"`.
  `http.request(req: HttpRequest): Promise<HttpResponse>`, `http.get(uri: string): Promise<HttpResponse>`,
  `http.cancelAll(reason: string): void`.
- `HttpRequest` : `new HttpRequest(uri)`, propriétés `uri`, `method`, `headers: HttpHeader[]`,
  `body: string | ISerializable`, `timeout` en secondes (valeur par défaut non documentée). Méthodes chaînables
  `addHeader(key, value)`, `setBody`, `setHeaders`, `setMethod`, `setTimeout`.
- `HttpRequestMethod` : `Get`, `Head`, `Post`, `Put`, `Delete`, `Patch`. `HttpHeader` : `new HttpHeader(key:
  string, value: SecretString | string)`.
- `HttpResponse` : lecture seule `status: number`, `body: string`, `headers: HttpHeader[]`, `request`.
- Erreurs (noms depuis 1.26.20) : `InternalHttpRequestError` (`errorCode`, `errorMessage`, erreur de la
  plate-forme), `MalformedUriError`, `TLSOnlyError`, `UriNotAllowedError`, `HttpRequestLimitExceededError`
  (`inFlightRequests`, `maxConcurrentRequests`), `RequestBodyTooLargeError` (`maxBytes`, `providedBytes`).
  Anciens noms : `HttpsOnlyError`, `HttpRequestNotAllowedError`, `HttpRequestBodyTooLargeError`,
  `MalformedHttpRequestError`.
- Ce que les pages ne disent pas : si une réponse 4xx ou 5xx rejette la promesse (elles décrivent `status`
  comme le code de la réponse), ce que produit un délai dépassé, si une erreur réseau rejette la promesse ou
  lève tout de suite. À relever sur le BDS de test, en phase 2.
- Exemple minimal, tiré de la page `HttpRequest` et adapté (nom de secret, de variable et chemin fictifs) :

```ts
import { http, HttpRequest, HttpHeader, HttpRequestMethod } from "@minecraft/server-net";
import { secrets, variables } from "@minecraft/server-admin";

const cle = secrets.get("silooo_key");
if (!cle) throw new Error("secret silooo_key absent");

const req = new HttpRequest(`${variables.get("core_url")}/chemin`);
req.method = HttpRequestMethod.Post;
req.timeout = 5;
req.body = JSON.stringify({ exemple: 1 });
req.headers = [new HttpHeader("Content-Type", "application/json"), new HttpHeader("X-Silooo-Key", cle)];
const reponse = await http.request(req); // reponse.status, reponse.body
```

### 4. Téléchargement de BDS pour Linux

- URL : `https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-<version>.zip`. Testé en HEAD
  le 5 novembre 2026 : `1.26.60.6` (98 094 690 octets, 27 oct.), `1.26.61.1` (98 093 051, 29 oct.) et
  `1.26.62.1` (98 089 493, 4 nov.) répondent 200 ; une version inexistante répond 404. Préversion :
  `bin-linux-preview/bedrock-server-1.26.70.23.zip`. Officiel (réponses de minecraft.net).
- Les liens courants viennent d'un point d'API sans authentification :
  `GET https://net-secondary.web.minecraft-services.net/api/v1.0/download/links`. Réponse JSON
  `{"result":{"links":[{"downloadType":"serverBedrockLinux","downloadUrl":"<url>"}]}}` avec cinq entrées, de
  types `serverBedrockWindows`, `serverBedrockLinux`, `serverBedrockPreviewWindows`, `serverBedrockPreviewLinux`
  et `serverJar`. Il ne donne que la stable et la préversion actuelles. Aucune page officielle trouvée qui le
  documente : lu en direct. Officiel par son origine, non documenté.
- En-têtes : aucun cookie ni jeton, et aucune acceptation de CLUF dans la requête. Le CLUF est celui de
  minecraft.net/terms (l'image itzg demande `EULA=TRUE`) ; la
  [page de téléchargement](https://www.minecraft.net/download/server/bedrock) n'a pas pu être lue (délai
  dépassé). User-Agent : le 5 novembre 2026, ni le User-Agent par défaut de curl ni celui de l'image
  (`silooo-bds/1.0 (+https://github.com/e-Quantum/silooo)`) n'ont reçu de réponse (0 octet, puis délai dépassé),
  depuis le PC comme depuis les runners GitHub ; lors du premier relevé, un User-Agent vide et `Mozilla/5.0`
  avaient reçu 200. Le site ne sert le zip qu'aux navigateurs. On ne déguise pas le client : le zip est posé par
  Kr (décision 26 de `docs/passation.md`, `docs/deploiement.md`, « Le zip de BDS »).
- Intégrité : aucune somme publiée trouvée sur une page. Les réponses portent un en-tête `Content-MD5`, lu mais
  non recalculé sur le fichier : `1.26.62.1` = `4a23794a882b008d3f0a5fde8b338f55`, `1.26.61.1` =
  `72e52f2f9fd1050806959f41e8293905`, `1.26.60.6` = `f0ae82eec98e4191a917349b7af12c6c`. Pas de SHA-256 : la CI
  calcule le sien à la première vérification et l'épingle dans `deploy/`.
- Binaire : `bedrock_server`, lancé par `LD_LIBRARY_PATH=. ./bedrock_server`. Ubuntu est la seule distribution
  Linux officiellement prise en charge. Au premier lancement, BDS crée `worlds/`, `config/` et laisse
  `behavior_packs/`, `resource_packs/` à la racine pour les packs partagés. Officiel :
  [Getting Started](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/getting-started?view=minecraft-bedrock-stable).

### 5. Réseau : ports, RakNet, NetherNet

- Port par défaut : 19132 (`server-port`) ; `server-portv6` : 19133. Officiel :
  [server.properties](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/server-properties?view=minecraft-bedrock-stable)
  (page de juin 2025, sans les clés NetherNet).
- Transport : clé `transport`, valeurs `nethernet` ou `raknet`, défaut `nethernet` depuis BDS 1.26.50 (image
  itzg, wiki). Tiers : [wiki NetherNet](https://minecraft.wiki/w/NetherNet),
  [itzg](https://github.com/itzg/docker-minecraft-bedrock-server).
- RakNet est déprécié en 26.60 ; l'avertissement qui proposait NetherNet est devenu une erreur pour un
  serveur qui ne l'utilise pas (notes 26.60 citées par le [wiki](https://minecraft.wiki/w/Bedrock_Edition_26.60)).
  Les clients 26.60 ne se connectent plus en RakNet : affirmation de Geyser et d'hébergeurs, pas de Mojang.
  Tiers. Conséquence pour Silooo : NetherNet obligatoire, RakNet ne sert à rien.
- Avec NetherNet : `server-port` est un port TCP de signalisation HTTP (`GET /v1/join`,
  `POST /v1/join/{networkId}`) ; `server-portv6` n'a plus d'effet ; le jeu passe en UDP, un port par client.
  Tiers (wiki NetherNet, itzg, [setupmc](https://setupmc.com/guides/nethernet-bedrock-server/)).
- Clés nouvelles : `server-udp-ports` (un port ou une plage, forme `[IP:]public:local`, la plage doit couvrir
  `max-players`) et `server-ip` (vide = toutes les interfaces ; sans effet en RakNet). Sans `server-udp-ports`,
  BDS prend des ports éphémères du système. Derrière Docker, il faut une IP publique et la forme
  `public:local` : BDS ne remplit pas l'IP tout seul. UDP 7551 sert à la découverte sur le réseau local
  (`enable-lan-visibility`, vrai par défaut). Tiers.
- En RakNet (pour mémoire) : UDP sur `server-port` et sur `server-portv6`, sans signalisation. Tiers.

### 6. Limites et clés de `server.properties` utiles

- Taille maximale d'un pack, nombre d'entités avant ralentissement sur Switch et vieux mobiles : aucune valeur
  publiée trouvée (pages Learn, wiki, recherche). À relever par un test.
- Chien de garde (noms et défauts : [page Learn](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/server-properties?view=minecraft-bedrock-stable),
  officiel ; plages : [wiki](https://minecraft.wiki/w/Server.properties), tiers) : `script-watchdog-enable=true`,
  `-enable-exception-handling=true`, `-enable-shutdown=true`, `-hang-exception=true`,
  `-hang-threshold=10000` (ms, plage 3000 à 20000), `-spike-threshold=100` (50 à 500), `-slow-threshold=10`
  (5 à 50), `-memory-warning=100` (Mo, 0 = coupé), `-memory-limit=250` (Mo, 0 = coupé, au-dessus le monde
  est sauvé puis arrêté). Unité de `spike` et `slow` : non précisée.
- Autres clés de script : `allow-outbound-script-debugging` et `allow-inbound-script-debugging` (faux),
  `force-inbound-debug-port` (19144), `script-debugger-auto-attach` (`disabled`), `script-debugger-passcode`,
  `diagnostics-capture-*`. Officiel, même page.
- `allow-list` : la page Learn dit faux par défaut, le wiki et l'image itzg disent vrai dans le fichier de
  1.26.62.1, et une liste vide refuse tout le monde. Écrire la valeur voulue dans `deploy/`, ne pas compter sur
  le défaut. `allow-player-joining` (wiki, commenté dans le fichier) : si faux, les connexions sont refusées
  sauf si un script les accepte. Tiers.
- Journal de contenu : `content-log-file-enabled=false` (officiel) ; `content-log-console-output-enabled=false`
  et `content-log-level=info` (`error`, `warning`, `info`, `verbose`), wiki, tiers.
- Zones chargées (wiki, tiers) : `persistent-ticking-area-max-count=10`, `persistent-ticking-area-max-chunks=100`,
  `transient-ticking-area-max-chunks=300` (accepte `unlimited`).
- `texturepack-required=false` par défaut : si vrai, les clients doivent utiliser les packs du monde.
  Officiel, même page Learn.

### 7. Manifeste du pack de comportement

- `format_version` du manifeste : `2`. `header.min_engine_version` : `[1, 26, 60]` dans le dépôt Mojang
  [bedrock-samples, tag v1.26.60.5](https://github.com/Mojang/bedrock-samples/tree/v1.26.60.5). Officiel.
- Module de script, d'après Learn : `type` `script`, `language` `javascript`, `uuid`, `version`, `entry`
  (par exemple `scripts/main.js`). Dépendances par `module_name` et `version`.
  [Source](https://learn.microsoft.com/en-us/minecraft/creator/documents/bedrockserver/scripting?view=minecraft-bedrock-stable).
  Officiel.

```json
{
  "format_version": 2,
  "header": { "name": "<nom>", "description": "<texte>", "uuid": "<uuid>", "version": [0, 0, 1], "min_engine_version": [1, 26, 60] },
  "modules": [
    { "type": "script", "language": "javascript", "uuid": "<uuid du module>", "version": [0, 0, 1], "entry": "scripts/main.js" }
  ],
  "dependencies": [
    { "module_name": "@minecraft/server", "version": "2.11.0" },
    { "module_name": "@minecraft/server-ui", "version": "2.3.0" },
    { "module_name": "@minecraft/server-admin", "version": "1.0.0-beta" },
    { "module_name": "@minecraft/server-net", "version": "1.0.0-beta" }
  ]
}
```

- `format_version` des fichiers, compté sur tous les fichiers du tag v1.26.60.5 de bedrock-samples (officiel) :
  entités de comportement jusqu'à `"1.26.60"` (11 sur 128, la plupart en 1.26.0 à 1.26.50) ; entités côté
  client `1.10.0` (95), `1.8.0` (78), `1.26.0` (7) ; modèles `models/entity` `1.8.0` (78), `1.12.0` (63),
  `1.21.0` (38), jusqu'à `1.26.10` (4) ; animations `1.8.0` (136) et `1.10.0` (7) ; contrôleurs d'animation
  `1.10.0` (73) ; contrôleurs de rendu `1.8.0` (142), `1.10.0` (28) et `1.10` (3). Le numéro est une chaîne.
  Dès `format_version` 1.26.0, un JSON d'entité invalide n'est plus chargé
  ([notes 1.26.10](https://learn.microsoft.com/en-us/minecraft/creator/documents/update1.26.10?view=minecraft-bedrock-stable)).

### Reste à relever

- Contenu de `server.properties` et de `config/default/permissions.json` livrés avec 1.26.62.1 : lire le zip
  dans la CI ou sur le PC, puis corriger ce fichier.
- Procédure pour activer « Beta APIs » sur un monde de BDS, faute de page officielle.
- Une source de XUID pour l'add-on : la Script API 2.11.0 n'en donne pas (`Player.id` est un identifiant opaque
  propre au monde). En attendant, `addon/src/identity.ts` envoie une empreinte du gamertag (décision 25 de
  `docs/passation.md`). Pistes : le journal de BDS (« Player connected: <nom>, xuid: <xuid> »), `allowlist.json` rempli
  par BDS, une version future de l'API.
- Comportement des erreurs réseau et des délais, format de `allowed_uris`, accès aux noms d'hôte Docker.
- Taille maximale d'un pack et nombre d'entités avant ralentissement sur Switch et vieux mobiles.
- Texte officiel des notes de version 26.60 et page de téléchargement, inaccessibles depuis ici.
