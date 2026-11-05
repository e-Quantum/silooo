# addon : le pack de comportement de Silooo

L'add-on tourne dans le serveur Bedrock (BDS) et parle en HTTP à silooo-core. Il n'a aucune règle de jeu :
aucun solde, prix ni gain ne se calcule ici. Il envoie ce qui se passe (arrivée, départ, heartbeat), lit les
événements que le service publie, et affiche.

## Où est quoi

| Chemin | Rôle |
|---|---|
| `manifest.json` | Pack "Silooo", module script `scripts/main.js`, dépendances. Les deux uuid ne changent jamais. |
| `src/main.ts` | Point d'entrée. Rien ne le fait tomber : toute exception est attrapée et journalisée. |
| `src/beta.ts` | Le seul fichier qui touche aux modules bêta (réseau, secret, variables). |
| `src/http.ts` | Client de silooo-core : résultats typés ok, refusé, erreur, réseau ; un seul nouvel essai, pour les GET. |
| `src/protocol.ts`, `src/api.ts` | Chemins, corps et lecture des réponses, conformes à `api/silooo-core.openapi.yaml`. |
| `src/session.ts`, `src/state.ts` | `join` à la première arrivée, `leave` au départ, état gardé par joueur. |
| `src/heartbeat.ts`, `src/events.ts` | Heartbeat régulier ; lecture des événements avec un curseur qui part de 0. |
| `src/config.ts` | Cadences et délais techniques. Aucun chiffre du jeu. |
| `src/ui/` | Les écrans : HUD, menu, carte, prestige, objectifs, boussole. Le détail est dans « Les écrans » plus bas. Seul `ui/textes.ts` contient des phrases. |
| `src/ui/formulaires.ts` | La seule enveloppe de `@minecraft/server-ui`. |
| `src/monde/` | Le monde : îles des usines, arrivée, voyage depuis la carte, cinématique, Oooscar. Le détail est dans « Le monde » plus bas. |
| `src/monde/bedrock.ts` | L'enveloppe de `@minecraft/server` pour le monde (blocs, caméra, entité, téléportation). Seul `main.ts` l'importe. |
| `pack/entities/oooscar.json` | L'entité `silooo:oooscar`, copiée telle quelle dans `dist/pack/` par le build. Le côté client est dans `resource-pack/`. |
| `src/generated/definitions.ts` | Types tirés de `definitions/`. Généré, ne pas éditer. |
| `test/` | Tests de la logique, sans Bedrock. |
| `scripts/` | `construire.mjs` (le build : esbuild, puis copie de `pack/`) et `tester.mjs` (les tests). |

## Construire et tester

Node 22 ou plus. Depuis ce dossier :

```
npm ci
npm test        # compile les tests avec esbuild, puis node --test
npm run build   # tsc strict, puis dist/
```

Le build produit `dist/pack/` (le dossier à copier dans `development_behavior_packs/` du serveur) et
`dist/silooo-addon.mcpack`. Le script est un seul fichier ESM, `dist/pack/scripts/main.js`, avec les modules
`@minecraft/*` laissés en import. Le dossier `dist/` n'est pas versionné ; son chemin est un contrat avec
`deploy/`.

Les types de `src/generated/` se refont depuis la racine du dépôt : `node tools/generer_types.mjs`.
`tools/verifier.sh` échoue s'ils sont périmés.

## URL de silooo-core et clé

Rien de tout cela n'est dans le pack. Le déploiement pose deux fichiers dans le dossier de configuration de
BDS, `config/<uuid du module script>/` (voir `docs/bedrock.md`, fait 2) :

- `variables.json` : `core_url` (l'URL de base de silooo-core, en `http://` ou `https://`) et, si besoin,
  `server_id` (par défaut `bds-1`) ;
- `secrets.json` : `silooo_key`, la clé d'API.

L'add-on lit la clé par `secrets.get` et la passe telle quelle dans l'en-tête `X-Silooo-Key` : il ne peut ni la
lire ni l'écrire dans un journal. Si la clé ou l'URL manque, il reste inactif et le dit une fois dans le journal.

## Beta APIs

`@minecraft/server-net` et `@minecraft/server-admin` sont des modules bêta. Le manifeste les déclare en
`1.0.0-beta`, et chaque monde qui utilise le pack doit avoir l'option "Beta APIs" activée. Leurs versions de
types sont épinglées dans `package.json` (`1.0.0-beta.1.26.62-stable`), jamais en `latest`. À chaque nouvelle
version de Bedrock, on relit les appels listés ci-dessous.

Appels bêta, tous dans `src/beta.ts` :

- `@minecraft/server-net` : `http.request`, `HttpRequest` (uri, method, timeout, headers, body), `HttpHeader`,
  `HttpRequestMethod`, `HttpResponse` (status, body) ;
- `@minecraft/server-admin` : `secrets.get`, `variables.get`, et le type `SecretString`.

## Les écrans

Première tranche de la phase 3 : le HUD, le menu et la fiche d'usine, puis les objectifs (la première mission, celle de
la mairie). L'add-on n'y calcule rien. Chaque valeur affichée est un champ d'une réponse du service ; seul le compteur
de Donar avance entre deux réponses (revenu par heure × temps écoulé, jamais sous le solde du service). La phase 4
y ajoute le prestige et la carte des îles : voir « La carte et le prestige » plus bas.

```
boussole ── MENU ─┬─ Silo ───── Encaisser ............ collect
                  ├─ Usines ─── tuile ─── fiche ─┬─ Monter de 1, de 10, au palier, au max ... buyLevels
                  │                              └─ Parler, Réparer, Livrer, Recruter ....... restoreStep
                  ├─ Objectifs ─ la mission en cours, les missions terminées ................ getMissions
                  ├─ Carte ──── une ligne par usine ─── île choisie ... téléportation (monde/voyage.ts)
                  ├─ Prestige ─ l'aperçu du service ─── Prestige ! ... prestige ── écran d'après
                  │                                     (lecture de l'aperçu .......... prestigePreview)
                  └─ Amis : tuile verrouillée, avec le mot « Bientôt »
```

La boussole (`minecraft:compass`, réglable dans `config.ui.menuItem`) est donnée à la première arrivée si le joueur
n'en a pas, puis l'utiliser ouvre le menu. Une action tient en deux touches depuis le menu pour le Silo
(Silo, Encaisser) et en trois pour monter une usine (Usines, la tuile, le bouton) : voir « Ce qui reste à décider ».

| Fichier de `src/ui/` | Rôle |
|---|---|
| `textes.ts` | Le seul fichier qui contient des phrases : les écrans, le HUD, les bulles d'Oooscar (un texte par code `reason`). |
| `apparence.ts` | Les couleurs de `design/` en codes Bedrock, et les repères provisoires qui tiennent la place des icônes et d'Oooscar. |
| `nombres.ts` | La notation des grands nombres : k, M, Md, aa…, trois chiffres significatifs, virgule décimale, espace insécable. |
| `compteur.ts` | Le compteur de Donar entre deux réponses du service. |
| `etat.ts` | La dernière vue de chaque joueur (une réponse plus ancienne est ignorée) et l'état de la liaison avec le service. |
| `actions.ts` | Les quatre actions (encaisser, acheter des niveaux, remettre en état, prestige) : un identifiant par action, un seul nouvel essai après `CONFLIT_REESSAYER`, une action à la fois par joueur. Après un encaissement réussi, elle appelle `afterCollect` (la relecture des missions). L'aperçu du prestige est une lecture : ni verrou ni identifiant. |
| `missions.ts` | Les missions de chaque joueur d'après la dernière lecture de `getMissions`, et l'annonce d'une mission terminée. Les trois moments de lecture : l'arrivée, après chaque encaissement, une annonce. |
| `usines.ts` | La situation d'une usine d'après les champs du contrat, et la prochaine étape de remise en état. |
| `hud.ts` | Le HUD : rendu pur, puis envoi aux joueurs une fois par seconde. |
| `menu.ts` | Le menu, la liste des usines, la fiche, le Silo, les objectifs, la carte, le prestige (avant et après), et le parcours entre eux. |
| `formulaires.ts` | La seule enveloppe de `@minecraft/server-ui`. Les tests passent un faux présentateur. |
| `boussole.ts` | L'objet qui ouvre le menu : don à l'arrivée, ouverture à l'usage. |

Seuls `main.ts`, `beta.ts`, `ui/formulaires.ts` et `monde/bedrock.ts` importent des modules `@minecraft/*` (`bedrock.ts` n'est importé que par `main.ts`). Les tests ne les chargent jamais.

### Le HUD

Trois étages (`design/composants/Hud.md`). Les réglages sont dans `config.ui.hud` ; chaque ligne est expliquée
dans `config.ts`.

- En haut, le canal de titre de Bedrock : le titre porte les monnaies et commence par `silooo:hud|`, le sous-titre
  porte la consigne et commence par `silooo:consigne|`. La consigne est celle de la mission en cours, suivie de son
  avancement tel que le service le donne (`progress` sur `target`, jamais recalculés) ; sans mission en cours (toutes
  terminées, pas encore lues, ou inconnue de cet add-on), c'est le seuil de prestige.
- Au-dessus de la barre d'inventaire, la barre d'action : le gain par seconde, `+<revenu>/s`.
- En dessous : rien, le contrat n'a ni île, ni membres connectés, ni ping.

`config.ui.hud.titleChannel` est à `false` : tant que `resource-pack/` ne masque pas ces deux préfixes, le canal de
titre afficherait le préfixe au milieu de l'écran. Tout le HUD passe alors par la barre d'action, qui ne porte pas la
consigne : tant que `titleChannel` est à `false`, la mission se lit dans le menu, à Objectifs. Quand le service ne
répond pas, le HUD se réduit à « Service hors ligne ».

Ce que `resource-pack/` doit apporter, et qui n'y est pas encore :

1. Dans `hud_screen.json`, masquer le préfixe `silooo:hud|` du titre et placer le reste en haut à gauche.
2. Faire de même pour `silooo:consigne|` du sous-titre, juste sous les monnaies.
3. Les glyphes des icônes de `design/icones/` et les images d'Oooscar, pour remplacer les repères provisoires.

Le jour où ces trois points sont faits : `titleChannel: true`, et `ui/apparence.ts` change.

### Les objectifs

La tuile Objectifs du menu ouvre l'écran des objectifs : la mission en cours (titre, consigne, avancement, récompense
en Lingots), puis la liste des missions terminées. La ligne d'info de la tuile donne l'avancement de la mission en
cours. Chaque chiffre (avancement, cible, Lingots) est celui de `getMissions`, mis en forme et jamais recalculé :
l'add-on ne dit jamais « mission faite ». Le service compte l'action (un `collect` qui rapporte du Donar), termine la
mission et verse les Lingots.

L'add-on ne garde que les mots, par identifiant de `definitions/missions.json` (`MISSION_TEXTES` dans
`ui/textes.ts`) : un titre, la consigne du HUD et la bulle d'Oooscar de la fin. Un test vérifie que chaque
identifiant du fichier a ses textes et qu'aucun texte ne vise un identifiant que le fichier ne connaît pas. Une
mission que cet add-on ne connaît pas encore se montre avec un titre tiré de l'identifiant, sans consigne.

`ui/missions.ts` lit les missions à trois moments : à l'arrivée du joueur (le crochet `joined` de la session,
combiné à celui du monde par `combineHooks`), après chaque encaissement réussi (`afterCollect` de `actions.ts`), et sur
un événement `ANNONCE` dont `payload.action` vaut `mission`. L'écran des objectifs relit aussi à chaque ouverture. Les
lectures d'un même joueur se suivent, une réponse qui arrive après le départ du joueur est jetée, et en cas d'échec la
lecture précédente reste.

L'annonce d'une mission terminée (`ANNONCE`, `missionId`, `rewardLingots`) : Oooscar la dit dans le tchat du joueur,
la bulle de fin de la mission puis les Lingots que le payload donne, et les missions sont relues. Le curseur des
événements repart de 0 quand BDS redémarre : l'add-on retient les missions déjà terminées à l'arrivée du joueur et ne
redit pas une annonce déjà dite pendant sa visite.

### La carte et le prestige

Troisième tranche des écrans (phase 4). Toujours aucun calcul : le prestige est la décision du service, et la carte ne
montre que ce que le service dit des usines. Les phrases sont dans `PRESTIGE_TEXTES` et `CARTE_TEXTES`
(`ui/textes.ts`), tous à valider par Kr.

**Avant le prestige.** La tuile Prestige du menu (ligne d'info « Course `<n>` ») ouvre l'écran d'avant. À chaque
ouverture, et après un refus, il relit l'aperçu du service (`prestigePreview`, une lecture : ni verrou, ni
identifiant) et montre ce qu'il dit : la course et l'époque, le seuil quand le prestige n'est pas permis, les
Lingots en tout, la part de chaque membre, ce que donneraient deux fois plus de Donar, la course suivante (époque 2, 3,
4 ou une étoile de plus), puis, en mots, ce qui repart à zéro (le Donar, le niveau des usines, toutes les usines hors
la Carrière) et ce qui reste (les Lingots, les Gemmes, l'entreprise et ses associés). Les parts ne s'affichent qu'à
partir de deux membres, et en deux mots, « Ta part » et « Un associé » : l'aperçu ne donne que des XUID, jamais un nom.
Le bouton « Prestige ! » n'existe que si l'aperçu dit `allowed`. Sans aperçu (lecture ratée), Oooscar le dit et il ne
reste que « Retour ».

**Le prestige.** Le bouton appelle `prestige` : un identifiant d'action et un seul nouvel essai après
`CONFLIT_REESSAYER`, comme les autres actions. Les refus deviennent des bulles d'Oooscar : `PAS_LE_PDG`, et
`SEUIL_NON_ATTEINT` avec la somme de Donar qui manque quand le service la donne. L'add-on ne lit pas le rôle du joueur :
le service refuse, l'écran le dit. Après un refus, l'aperçu est relu.

**Après le prestige.** Le service renvoie l'état de l'entreprise (`CompanyUpdated`) : la vue et le HUD se mettent
à jour comme après toute autre action, et l'écran d'après montre les étoiles et les Lingots tels que le
service les rend, avec une phrase sur la Carrière qui tourne et le bouton « Voir les usines ». Les îles n'ont rien à
brancher de plus : les usines sont revenues en ruine dans l'état du service, et le relevé suivant
(`config.world.syncSeconds`) les redessine.

**La carte.** La tuile Carte (ligne d'info « 1 île », « `<n>` îles ») ouvre une liste : l'île principale en tête,
verrouillée (« Bientôt », elle n'est pas construite), puis une ligne par usine dans l'ordre de `definitions/usines.json`,
qui est celui de `iles.json` (un test le vérifie). Une usine de l'entreprise montre son nom et son état d'après le
service : « Entière · niv. `<niveau>` », « En ruine », « En travaux `<étape>`/`<total>` ». Une usine qu'elle n'a pas
reste cachée (« ????? ») et Oooscar dit qu'elle n'est pas encore à elle. La liste se pagine comme celle des usines
(`config.ui.tilesPerScreen`).

**Le voyage.** Choisir une île de l'entreprise appelle le `travel` que `main.ts` injecte dans le menu
(`monde/voyage.ts`) : le menu n'importe rien de Bedrock. Le voyage retrouve l'origine de l'archipel (celle de l'état du
service, sinon la dernière retenue par le relevé), montre au constructeur les îles de l'état du service (il ne refait que
ce qui a changé), attend l'île voulue au plus `config.world.travelWaitSeconds`, puis téléporte le joueur à son point
d'arrivée, face au bâtiment. Arrivé, le menu se ferme. Si l'on ne sait pas où aller, ou si l'île n'est pas prête dans le
délai, le joueur reste où il est et Oooscar le dit. Sans `travel` (le menu seul, sans monde), la réponse est
« Pas encore disponible. ».

### Les formulaires

Bedrock refuse d'ouvrir un formulaire quand le joueur a déjà un écran ouvert (le tchat, un autre menu). Le menu
réessaie alors `config.ui.formBusyRetries` fois, toutes les `config.ui.formBusyRetrySeconds` secondes, puis
abandonne sans bruit. Un joueur qui part pendant qu'un formulaire est ouvert ne fait rien tomber : l'erreur est
journalisée.

## Le monde

Seconde tranche de la phase 3 : les îles des usines, l'arrivée du joueur, la cinématique et Oooscar. Toujours aucune
règle de jeu. L'add-on pose ce que le service décrit (`CompanyState.island` et la liste des usines), et chaque chiffre
de l'archipel vit dans `definitions/iles.json` (schéma `definitions/schemas/iles.schema.json`, types générés).

```
join ── island présent ? ── non ── une ligne de journal, rien posé, personne déplacé
                         └─ oui ── mini-îles posées (Carrière d'abord) ── île de départ prête ── téléportation
                                   ── Oooscar près du joueur ── cinématique, au premier passage seulement
```

| Fichier de `src/monde/` | Rôle |
|---|---|
| `definitions.ts` | `definitions/iles.json`, typé. |
| `geometrie.ts` | Les cases de l'archipel, le repère local d'une île (l'avant regarde l'île principale), le point d'arrivée de chaque île (`arrivalAt`, et `arrivalOf` pour l'île de départ). |
| `plan.ts` | Le plan d'une mini-île par usine et par état : une liste d'opérations, sans bloc Bedrock. En ruine : abîmée et sans toit, au hasard d'un hachage fixe (même résultat à chaque pose). Active : entière. Plate-forme, bâtiment, panneau au nom de l'usine. |
| `constructeur.ts` | Pose les plans par lots entre deux pauses, un chantier à la fois, la zone chargée par une ticking area. Un échec se rejoue après 2 s, puis le délai double jusqu'à 30 s. Une marque par usine et par état, rangée dans une propriété dynamique du monde : une île n'est posée qu'une fois par état. |
| `arrivee.ts` | La suite de l'arrivée, et le moment où le joueur est « occupé » : HUD silencieux, boussole en attente, menu fermé. |
| `cinematique.ts` | Le lecteur de cinématique : plans, caméra libre et glissé, fondus pour cacher les coupes, HUD masqué, sous-titres par le canal de titre, saut en restant accroupi une seconde, remise en état à la fin. |
| `oooscar.ts` | Une Oooscar par joueur, jamais deux, retirée au départ, balayée au démarrage et à chaque relevé. |
| `synchro.ts` | Le relevé périodique : l'état des usines de chaque joueur est montré au constructeur, qui ne refait que ce qui a changé. Une usine remise en état, ou retombée en ruine par un prestige, change de visage au relevé suivant. Il retient aussi l'origine d'archipel de chaque entreprise (`originOf`), pour le voyage. |
| `voyage.ts` | Le voyage depuis la carte : origine de l'archipel, point d'arrivée de l'île choisie (`arrivalAt`), îles montrées au constructeur, attente de l'île voulue, téléportation. Il n'importe pas `@minecraft/*` : le monde est un `ArrivalWorld`, comme pour l'arrivée. |
| `bedrock.ts` | L'enveloppe de `@minecraft/server` : blocs, panneaux, zones chargées, caméra, entité, téléportation. |

Choix faits ici, à relire :

- Les chiffres de l'archipel sont dans un fichier à part, `definitions/iles.json`, parce que `monde.json` n'était pas à
  modifier dans cette tranche. Il ne répète pas la taille de la case : le test des définitions la lit dans `monde.json`
  et vérifie que l'archipel y tient, centré.
- `island` est lu comme le coin de la case de l'archipel du joueur ; l'île principale en occupe le centre
  (`centreDansLaCase`, +300 +300). Les mini-îles forment un carré autour d'elle, une par usine, à l'angle que
  donne `iles.json`.
- L'entité d'Oooscar est en `format_version` `1.21.0` : un fichier plus récent que le moteur n'est pas chargé, et un
  monstre sans comportement n'a pas besoin des nouveautés. Elle n'a ni comportement, ni dégâts, ni butin, et ne
  persiste jamais.
- `npm run build` copie `pack/` dans `dist/pack/` (`--statique` change le dossier source, `--sortie` le dossier de
  sortie, qui doit être vide ou absent). Une collision avec `manifest.json` ou `scripts/`, ou un JSON invalide, arrête le build.
- Le balayage des Oooscar oubliées après un plantage attend `startupDelaySeconds`, comme le reste du démarrage.
- Tous les textes de la cinématique sont dans `ui/textes.ts`, à valider par Kr comme les autres.

## Textes à valider

Tous les textes de joueurs de cette tranche sont à valider par Kr. Ils sont commités, jamais présentés comme
définitifs. Ils vivent dans `src/ui/textes.ts`, et un test vérifie que chacun figure dans ce tableau. L'avant est
« rien » : ces écrans n'existaient pas, sauf là où la colonne dit autre chose.

Les noms des usines ne sont pas dans ce tableau : ils viennent de `definitions/usines.json` (clé « nom »), recopiés
dans `FACTORY_NAMES` et comparés au fichier par un test. Les repères provisoires (`[D] [L] [G] [U] [T] [!] [E] [X]
[S] [C] [O] [A] [P]` pour les icônes, `[joie]`, `[fier]`, `[oups]`, `[panique]`, `[dodo]` pour Oooscar) sont dans
`ui/apparence.ts` : ce sont des places gardées pour des images, pas des textes.

| Où | Avant | Après |
|---|---|---|
| HUD, ligne hors ligne | rien | Service hors ligne |
| HUD, consigne | rien | Prestige à `<seuil>` Donar |
| Objet de l'inventaire | rien | Menu |
| Menu, titre | rien | MENU |
| Menu, tuile | rien | Silo |
| Menu, tuile | rien | Usines |
| Menu, tuile | rien | Carte |
| Menu, tuile | rien | Objectifs |
| Menu, tuile | rien | Prestige |
| Menu, tuile | rien | Amis |
| Menu, tuile verrouillée | rien | Bientôt |
| Menu, bouton | rien | Retour |
| Menu, ligne d'une tuile | rien | Revenu `<revenu>` |
| Menu, nombre d'usines | rien | 1 usine, puis `<n>` usines |
| Menu, tuile Carte, ligne d'info (la tuile était verrouillée) | Bientôt (tuile verrouillée) | 1 île, puis `<n>` îles |
| Menu, tuile Prestige, ligne d'info | rien | Course `<n>` |
| Usines, titre | rien | USINES |
| Usines, liste vide | rien | Aucune usine pour l'instant. |
| Usines, pagination | rien | Page `<n>` sur `<total>` |
| Usines, bouton | rien | Précédent |
| Usines, bouton | rien | Suite |
| Usines, tuile d'une usine active | rien | Niv. `<niveau>` · `<revenu>` |
| Usines, tuile | rien | En ruine |
| Usines, tuile | rien | Remise en état `<étape>`/`<total>` |
| Usines, tuile | rien | Incident |
| Usines, tuile | rien | Sans courant |
| Fiche, titre | rien | USINE |
| Fiche, sorte | rien | ACTIVE |
| Fiche, sorte | rien | EN RUINE |
| Fiche, sorte | rien | REMISE EN ÉTAT |
| Fiche, sorte | rien | INCIDENT |
| Fiche, sorte | rien | SANS COURANT |
| Fiche, phrase d'une usine active | rien | Elle livre sa production au Silo. |
| Fiche, phrase d'une usine en ruine | rien | Elle est en ruine. Remets-la en état. |
| Fiche, phrase d'une usine en travaux | rien | Les travaux avancent, étape par étape. |
| Fiche, phrase d'un incident | rien | Un incident bloque sa production. |
| Fiche, phrase d'un arrêt faute de courant | rien | Elle est à l'arrêt, sans courant. |
| Fiche, info | rien | Niveau `<niveau>` |
| Fiche, info | rien | Revenu `<revenu>` |
| Fiche, info | rien | Palier ×2 au niveau `<niveau>` |
| Fiche, info | rien | Coût `<montant>` Donar |
| Fiche, info d'une usine en travaux | rien | Étape `<n>` sur `<total>` : `<nom de l'étape>` |
| Fiche, bouton | rien | Monter de 1 |
| Fiche, bouton | rien | Monter de 10 |
| Fiche, bouton | rien | Monter au palier |
| Fiche, bouton | rien | Monter au max |
| Fiche, étape 1 | rien | Dialogue, bouton Parler |
| Fiche, étape 2 | rien | Tâches, bouton Réparer |
| Fiche, étape 3 | rien | Livraison, bouton Livrer |
| Fiche, étape 4 | rien | Recrutement, bouton Recruter |
| Fiche, bouton | rien | Retour |
| Silo, titre | rien | SILO |
| Silo, phrase | rien | Encaisse pour recevoir ta production. |
| Silo, info | rien | Donar `<montant>` |
| Silo, info | rien | Revenu `<revenu>` |
| Silo, info | rien | Multiplicateur `<multiplicateur>` |
| Silo, bouton | rien | Encaisser |
| Silo, bouton | rien | Retour |
| Silo, après la collecte | rien | Encaissé `<montant>` Donar |
| Silo, après la collecte | rien | Hors ligne `<durée>` |
| HUD, consigne d'une mission en cours (remplace le seuil de prestige) | Prestige à `<seuil>` Donar | `<consigne>` : `<fait>`/`<cible>`, par exemple Encaisse au Silo : 0/1 |
| Menu, tuile Objectifs, ligne d'info | Bientôt (tuile verrouillée) | Mission `<fait>`/`<cible>` |
| Menu, tuile Objectifs, ligne d'info quand le service n'a plus de mission | Bientôt (tuile verrouillée) | Plus de mission |
| Objectifs, titre | rien | OBJECTIFS |
| Objectifs, au-dessus de la mission en cours | rien | EN COURS |
| Objectifs, avancement | rien | Avancement `<fait>`/`<cible>` |
| Objectifs, récompense (et ligne de l'annonce de fin) | rien | Récompense `<lingots>` Lingots |
| Objectifs, au-dessus des missions terminées | rien | TERMINÉES |
| Objectifs, quand le service n'a plus de mission en cours | rien | Plus de mission pour l'instant. |
| Objectifs, liste des terminées vide | rien | Aucune mission terminée. |
| Objectifs, bouton | rien | Retour |
| Mission `bienvenue-mairie`, titre | rien | Bienvenue à la mairie |
| Mission `bienvenue-mairie`, consigne (celle du HUD) | rien | Encaisse au Silo |

Le titre d'une mission que `ui/textes.ts` ne connaît pas encore est l'identifiant du service mis en lettres
(`mission-du-futur` donne Mission du futur) : ce n'est pas un texte écrit, mais il s'affiche tant que le texte manque.

Les bulles d'Oooscar. Une bulle tient en six mots au plus, avec une des cinq expressions (joie, fier, oups, panique,
dodo), écrite sur une ligne : « Oooscar », le repère de l'expression, la phrase.

| Où | Avant | Après |
|---|---|---|
| Bulle oups, tuile verrouillée ou route pas encore écrite | rien | Pas encore disponible. |
| Bulle panique, réseau coupé ou délai dépassé | rien | Le service ne répond pas. |
| Bulle panique, erreur technique du service | rien | Problème côté service. |
| Bulle oups, code de refus inconnu | rien | Impossible pour l'instant. |
| Bulle joie, après Encaisser | rien | Production encaissée ! |
| Bulle dodo, après Encaisser avec des heures hors ligne | rien | Gagné pendant ton absence. |
| Bulle joie, après un achat de niveaux | rien | Usine améliorée ! |
| Bulle joie, après une étape de remise en état | rien | Étape terminée ! |
| Refus `PAS_ASSEZ_DE_DONAR` (avec la somme, puis sans) | rien | Il te manque `<somme>` Donar. Sans somme : Il te manque du Donar. |
| Refus `PAS_ASSEZ_DE_LINGOTS` | rien | Il te manque `<somme>` Lingots. Sans somme : Il te manque des Lingots. |
| Refus `PAS_ASSEZ_DE_GEMMES` | rien | Il te manque `<somme>` Gemmes. Sans somme : Il te manque des Gemmes. |
| Refus `PAS_LE_PDG` | rien | Seul le PDG peut le faire. |
| Refus `SEUIL_NON_ATTEINT` (avec la somme, puis sans) | rien | Il te manque `<somme>` Donar. Sans somme : Le seuil n'est pas atteint. |
| Refus `USINE_VERROUILLEE` | rien | Cette usine est verrouillée. |
| Refus `ZONE_VERROUILLEE` | rien | Cette zone est verrouillée. |
| Refus `EMPLACEMENT_OCCUPE` | rien | Cet emplacement est occupé. |
| Refus `ENTREPRISE_PLEINE` | rien | Ton entreprise est pleine. |
| Refus `DEJA_POSSEDE`, bulle fière | rien | Tu l'as déjà. |
| Refus `GRADE_PRECEDENT_REQUIS` | rien | Il faut d'abord le grade précédent. |
| Refus `CONFLIT_REESSAYER`, si le second essai échoue aussi | rien | Ça a bougé. Réessaie. |
| Bulle joie, fin de la mission `bienvenue-mairie` (dite dans le tchat, avant la ligne Récompense) | rien | Premier encaissement, bien joué ! |
| Bulle joie, fin d'une mission dont l'add-on n'a pas les textes | rien | Bien joué, patron ! |

Le prestige et la carte (`PRESTIGE_TEXTES`, `CARTE_TEXTES` et les bulles qui s'y ajoutent). Tous les nombres
viennent du service, mis en forme par `ui/nombres.ts`. Les noms d'époque sont ceux de `docs/game-design.md`.

| Où | Avant | Après |
|---|---|---|
| Prestige, titre | rien | PRESTIGE |
| Prestige, où en est la course | rien | Course `<n>` · Époque `<n>` : `<nom de l'époque>` |
| Prestige, nom de l'époque 1, 2, 3, 4 | rien | Artisanale, Vapeur, Moderne, Futuriste |
| Prestige, course suivante (époque 2, 3 ou 4) | rien | Course suivante : époque `<n>`, `<nom>` |
| Prestige, course suivante (étoile) | rien | Course suivante : une étoile de plus |
| Prestige, principe | rien | Tu revends tes usines et tu reçois des Lingots. |
| Prestige, quand le service l'autorise | rien | Le seuil est atteint. |
| Prestige, quand il ne l'autorise pas (sans bouton de confirmation) | rien | Le seuil n'est pas atteint. |
| Prestige, seuil | rien | Seuil `<seuil>` Donar |
| Prestige, total | rien | Lingots en tout `<lingots>` |
| Prestige, part du joueur (à partir de deux membres) | rien | Ta part `<lingots>` Lingots |
| Prestige, part d'un autre membre | rien | Un associé `<lingots>` Lingots |
| Prestige, si le Donar avait doublé | rien | Avec deux fois plus de Donar : `<lingots>` Lingots |
| Prestige, ce qui repart, titre | rien | REPART À ZÉRO |
| Prestige, ce qui repart | rien | Ton Donar et le niveau de tes usines. |
| Prestige, ce qui repart | rien | La Carrière redémarre, les autres usines retombent en ruine. |
| Prestige, ce qui reste, titre | rien | RESTE |
| Prestige, ce qui reste | rien | Tes Lingots, tes Gemmes, ton entreprise et ses associés. |
| Prestige, bouton de confirmation (le point d'exclamation est celui de `design/README.md`) | rien | Prestige ! |
| Prestige, bouton | rien | Retour |
| Après le prestige, étoiles | rien | Étoiles `<n>` |
| Après le prestige, Lingots | rien | Tu as `<lingots>` Lingots |
| Après le prestige, phrase | rien | La Carrière tourne. Remets les autres usines en état. |
| Après le prestige, bouton | rien | Voir les usines |
| Après le prestige, bouton | rien | Retour |
| Bulle fier, prestige accompli | rien | Prestige accompli, patron ! |
| Carte, titre | rien | CARTE |
| Carte, phrase | rien | Choisis une île pour t'y rendre. |
| Carte, île principale (verrouillée) | rien | Île principale, avec le mot Bientôt |
| Carte, usine que l'entreprise n'a pas | rien | ????? |
| Carte, usine entière | rien | Entière · niv. `<niveau>` |
| Carte, usine en ruine | rien | En ruine |
| Carte, usine en travaux | rien | En travaux `<étape>`/`<total>` |
| Carte, boutons | rien | Précédent, Suite, Retour, et la ligne Page `<n>` sur `<total>` des usines |
| Bulle oups, usine que l'entreprise n'a pas | rien | Tu n'as pas encore cette usine. |
| Bulle oups, île introuvable (pas d'origine d'archipel, ou île absente de `iles.json`) | rien | Je ne trouve pas cette île. |
| Bulle oups, île pas prête dans le délai | rien | L'île n'est pas encore prête. |

La cinématique d'arrivée (`design/composants/Cinematique.md`) : quatre plans, une phrase d'Oooscar par plan, plus la
consigne pour passer. Le nom de qui parle (« Oooscar ») est le titre du sous-titre, la phrase en est le texte. Chaque
phrase tient en huit mots au plus et reste à l'écran trois secondes au moins.

| Où | Avant | Après |
|---|---|---|
| Cinématique, plan 1 | rien | Bienvenue chez toi, patron. |
| Cinématique, plan 2 | rien | Tu hérites d'une vieille carrière abandonnée. |
| Cinématique, plan 3 | rien | Les murs tiennent encore, à peu près. |
| Cinématique, plan 4 | rien | Remettons-la en état, je t'accompagne. |
| Cinématique, consigne pour passer (barre d'action) | rien | Reste accroupi pour passer. |

Les échecs techniques, dans le code `network`, `unavailable` et `error` : `network` et `unavailable` donnent « Le
service ne répond pas. » ; `error` avec la raison `PAS_FAIT` (route pas encore écrite) donne « Pas encore
disponible. » ; tout autre `error` donne « Problème côté service. ».

## Ce qui manque

Au contrat (`api/`, non modifié par cette tranche) :

- Pas de champ pour le contenu du Silo (la production en attente), ni de plafond hors ligne : l'écran du Silo ne
  montre que le Donar, le revenu et le multiplicateur, et le résultat de la dernière collecte.
- Le payload de l'événement `ANNONCE` d'une mission n'a pas de schéma : `action`, `missionId` et `rewardLingots` ne
  sont décrits que dans le texte de `getMissions`. L'add-on lit chaque clé avec prudence et ne montre pas une somme
  illisible.
- `getMissions` ne rend que l'identifiant des missions terminées, sans titre : l'add-on en a les textes pour celles
  de `definitions/missions.json` (une seule pour l'instant), et pour une autre il compose un titre avec l'identifiant.
- Pas d'heure de dernière collecte (`asOf`, `lastCollectAt`), ni d'ère par usine.
- Les noms d'usines ne sont pas dans les types générés, seulement les identifiants.
- Le sens exact de `restoreStep` est supposé : le dernier palier fait, la prochaine étape est la suivante.
- `nextCost` ne donne que le prix de 1 niveau. Les prix de 10 niveaux, du palier et du max ne sont pas affichés ;
  le service refuse ou accepte, et `Refused.missing` dit la somme qui manque.
- Pas de route pour racheter une ruine ni pour résoudre un incident : une usine en incident ou sans courant se
  voit, mais l'écran n'a rien à proposer.

À la tranche du monde :

- Le service doit remplir `island` (l'origine de la case d'archipel de l'entreprise). Sans lui, rien n'est posé et le
  journal le dit une fois : les joueurs restent où Bedrock les met.
- Le coin de la case et le centre de l'île principale à +300 +300 sont une hypothèse de cette tranche.
- L'île principale n'est pas posée : les mini-îles s'ordonnent autour d'un centre où rien n'est encore construit.
- Le côté client d'Oooscar (géométrie, texture, apparence) est au pack de ressources, hors de cette tranche : le
  serveur sait la faire apparaître, pas la montrer.
- Les phrases de la cinématique sont des textes à valider, de même que le découpage et le cadrage des plans.

À la tranche du prestige et de la carte :

- L'événement `PRESTIGE_ENTREPRISE` (un prestige lancé par un autre membre) n'est pas branché : son payload n'est pas
  décrit dans le contrat, et aucun écran ne le montre. Les autres membres voient leurs usines et leurs îles changer
  au relevé suivant, sans qu'Oooscar le leur dise.
- L'aperçu du prestige ne donne que les XUID des membres : l'écran dit « Ta part » et « Un associé », sans nom.
- Le contrat ne donne pas le rôle du joueur : l'écran propose « Prestige ! » à tout membre quand le service
  l'autorise, et `PAS_LE_PDG` revient en bulle pour qui n'est pas le PDG.
- L'île principale n'est pas construite : sa ligne de la carte est verrouillée (« Bientôt »).
- Oooscar ne suit pas le joueur sur l'île choisie : il reste là où l'arrivée l'a posé.
- Pas de texte pour les époques au-delà de la quatrième : un numéro inconnu se montre sans nom.

Au design system :

- Pas d'icône pour Objectifs, Amis et Silo (Objectifs est actif mais garde son repère `[O]`), ni de dossier `textures/ui/silooo/icones/` dans le pack de ressources.
- Pas d'image d'Oooscar utilisable en jeu : les bulles portent un repère entre crochets.
- Pas de glyphes dans le pack de ressources pour les icônes qui existent dans `design/icones/`.

## Ce qui reste à décider

- Où Oooscar dit la fin d'une mission : pour l'instant dans le tchat du joueur (`player.sendMessage`), faute d'un autre
  endroit qui ne soit pas réécrit chaque seconde par le HUD. Une vraie bulle à l'écran dépend du pack de ressources.
- La consigne du HUD n'est visible qu'avec `titleChannel` à `true` (le sous-titre) ; à `false` elle manque, et la
  mission se lit à Objectifs. Le réglage reste à `false` tant que le pack ne masque pas les préfixes.
- « Une action en deux clics » : le Silo y est (Silo, Encaisser), pas la montée d'une usine (Usines, la tuile, le
  bouton). Un accès direct depuis le menu principal à l'usine la plus rentable serait possible, mais il demande un
  choix de jeu (laquelle ?) qui n'est pas pris ici.

- Prestige et carte, choix faits sans Kr, à relire : le prestige est une sixième tuile du menu (« Course `<n>` ») ;
  l'aperçu est relu à chaque ouverture, il n'est pas gardé ; sans autorisation du service il n'y a pas de bouton de
  confirmation, seulement le seuil ; l'attente d'une île avant de téléporter dure `config.world.travelWaitSeconds`
  (10 s) ; le bouton de confirmation s'écrit « Prestige ! », seul point d'exclamation d'un bouton, parce que
  `design/README.md` le prescrit ; « ????? » est le seul point d'interrogation, c'est le nom caché d'une usine.

## Ce qui ne se vérifie que sur un vrai BDS

- Le canal de titre : papillotement entre deux envois, durée d'affichage, effet du masquage par le pack.
- Les espaces insécables : U+00A0 peut s'afficher comme un carré selon la police.
- Les codes de couleur de matériaux `§p`, `§u` et `§n`, propres à Bedrock et récents.
- Le refus `UserBusy` d'un formulaire et les nouveaux essais.
- L'événement `itemUse` sur la boussole, et son `lockMode` (`inventory`, pas de lâcher, pas de déplacement).
- Le don de l'objet au tout premier tick : le délai de `config.ui.menuItemDelaySeconds` est une hypothèse.
- Un formulaire envoyé à un joueur qui vient de partir.
- Les signes `§` dans les boutons des formulaires.
- Les îles : le délai de chargement d'une ticking area, la pose par lots sans à-coup, l'orientation du panneau mural
  d'après `facing_direction`, le sort de l'île quand la zone est déchargée.
- La caméra : le cadrage de chaque plan, le glissé (`easeOptions`), les fondus qui cachent les coupes, le masquage du
  HUD, le sous-titre par le canal de titre sans le préfixe du HUD.
- Le blocage des déplacements pendant la cinématique et la lecture de l'accroupissement (le même geste doit faire passer).
- La téléportation vers une zone encore non chargée, et la remise du joueur à la fin de la cinématique.
- L'entité `silooo:oooscar` : validité du JSON au format `1.21.0`, absence de dégâts et de butin, disparition au départ.
- Le voyage : la téléportation vers une île dont la zone n'est plus chargée (la ticking area est relâchée après la
  pose), l'attente de `config.world.travelWaitSeconds` quand l'île se redessine après un prestige, et le point
  d'arrivée de chacune des quinze îles (hors du bâtiment et sur la plateforme, prouvé par les tests de géométrie,
  mais pas vu).
- Les formulaires de la carte (seize lignes, deux pages) et du prestige (longueur du corps, retours à la ligne), et les
  repères provisoires `[P]`, `[C]` et `[X]`.
- Les missions : `player.sendMessage` avec les codes `§` pour la bulle de fin, la tuile Objectifs à deux lignes, la
  lecture des missions à l'arrivée pendant la cinématique, et une `ANNONCE` reçue pour un joueur qui vient de se
  connecter (le curseur des événements repart de 0 au redémarrage de BDS).

## Points ouverts

- L'API de script ne donne pas le XUID d'un joueur. `src/identity.ts` envoie à sa place une empreinte FNV-1a
  64 bits du gamertag (minuscules, sans espaces autour), en décimal : elle passe le schéma `Xuid` du contrat et la
  colonne `BIGINT UNSIGNED` du service, et survit à un monde recréé (décision 25 de `docs/passation.md`, à valider
  par Kr). Quand une source de vrai XUID existera, c'est le seul fichier à changer.
- Ce qui ne se vérifie que sur un vrai BDS : le comportement de `http.request` sur un statut 4xx ou 5xx et sur un
  délai dépassé, l'ordre de démarrage du script, le format de `allowed_uris` dans `permissions.json`.
- Un `join` qui échoue n'est pas rejoué, et un script rechargé ne refait pas le `join` des joueurs déjà en ligne.
