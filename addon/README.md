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
| `src/ui/` | Les écrans : HUD, menu, boussole. Le détail est dans « Les écrans » plus bas. Seul `ui/textes.ts` contient des phrases. |
| `src/ui/formulaires.ts` | La seule enveloppe de `@minecraft/server-ui`. |
| `src/generated/definitions.ts` | Types tirés de `definitions/`. Généré, ne pas éditer. |
| `test/` | Tests de la logique, sans Bedrock. |
| `scripts/` | `construire.mjs` (le build) et `tester.mjs` (les tests). |

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

Première tranche de la phase 3 : le HUD, le menu et la fiche d'usine. L'add-on n'y calcule rien. Chaque valeur
affichée est un champ d'une réponse du service ; seul le compteur de Donar avance entre deux réponses (revenu par
heure × temps écoulé, jamais sous le solde du service).

```
boussole ── MENU ─┬─ Silo ───── Encaisser ............ collect
                  ├─ Usines ─── tuile ─── fiche ─┬─ Monter de 1, de 10, au palier, au max ... buyLevels
                  │                              └─ Parler, Réparer, Livrer, Recruter ....... restoreStep
                  └─ Carte, Objectifs, Amis : tuiles verrouillées, avec le mot « Bientôt »
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
| `actions.ts` | Les trois actions : un identifiant par action, un seul nouvel essai après `CONFLIT_REESSAYER`, une action à la fois par joueur. |
| `usines.ts` | La situation d'une usine d'après les champs du contrat, et la prochaine étape de remise en état. |
| `hud.ts` | Le HUD : rendu pur, puis envoi aux joueurs une fois par seconde. |
| `menu.ts` | Le menu, la liste des usines, la fiche, le Silo, et le parcours entre eux. |
| `formulaires.ts` | La seule enveloppe de `@minecraft/server-ui`. Les tests passent un faux présentateur. |
| `boussole.ts` | L'objet qui ouvre le menu : don à l'arrivée, ouverture à l'usage. |

Seuls `main.ts`, `beta.ts` et `ui/formulaires.ts` importent des modules `@minecraft/*`. Les tests ne les chargent jamais.

### Le HUD

Trois étages (`design/composants/Hud.md`). Les réglages sont dans `config.ui.hud` ; chaque ligne est expliquée
dans `config.ts`.

- En haut, le canal de titre de Bedrock : le titre porte les monnaies et commence par `silooo:hud|`, le sous-titre
  porte la consigne et commence par `silooo:consigne|`.
- Au-dessus de la barre d'inventaire, la barre d'action : le gain par seconde, `+<revenu>/s`.
- En dessous : rien, le contrat n'a ni île, ni membres connectés, ni ping.

`config.ui.hud.titleChannel` est à `false` : tant que `resource-pack/` ne masque pas ces deux préfixes, le canal de
titre afficherait le préfixe au milieu de l'écran. Tout le HUD passe alors par la barre d'action. Quand le service ne
répond pas, le HUD se réduit à « Service hors ligne ».

Ce que `resource-pack/` doit apporter, et qui n'y est pas encore :

1. Dans `hud_screen.json`, masquer le préfixe `silooo:hud|` du titre et placer le reste en haut à gauche.
2. Faire de même pour `silooo:consigne|` du sous-titre, juste sous les monnaies.
3. Les glyphes des icônes de `design/icones/` et les images d'Oooscar, pour remplacer les repères provisoires.

Le jour où ces trois points sont faits : `titleChannel: true`, et `ui/apparence.ts` change.

### Les formulaires

Bedrock refuse d'ouvrir un formulaire quand le joueur a déjà un écran ouvert (le tchat, un autre menu). Le menu
réessaie alors `config.ui.formBusyRetries` fois, toutes les `config.ui.formBusyRetrySeconds` secondes, puis
abandonne sans bruit. Un joueur qui part pendant qu'un formulaire est ouvert ne fait rien tomber : l'erreur est
journalisée.

## Textes à valider

Tous les textes de joueurs de cette tranche sont à valider par Kr. Ils sont commités, jamais présentés comme
définitifs. Ils vivent dans `src/ui/textes.ts`, et un test vérifie que chacun figure dans ce tableau. L'avant est
« rien » : ces écrans n'existaient pas.

Les noms des usines ne sont pas dans ce tableau : ils viennent de `definitions/usines.json` (clé « nom »), recopiés
dans `FACTORY_NAMES` et comparés au fichier par un test. Les repères provisoires (`[D] [L] [G] [U] [T] [!] [E] [X]
[S] [C] [O] [A]` pour les icônes, `[joie]`, `[fier]`, `[oups]`, `[panique]`, `[dodo]` pour Oooscar) sont dans
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
| Menu, tuile | rien | Amis |
| Menu, tuile verrouillée | rien | Bientôt |
| Menu, bouton | rien | Retour |
| Menu, ligne d'une tuile | rien | Revenu `<revenu>` |
| Menu, nombre d'usines | rien | 1 usine, puis `<n>` usines |
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
| Refus `SEUIL_NON_ATTEINT` | rien | Le seuil n'est pas atteint. |
| Refus `USINE_VERROUILLEE` | rien | Cette usine est verrouillée. |
| Refus `ZONE_VERROUILLEE` | rien | Cette zone est verrouillée. |
| Refus `EMPLACEMENT_OCCUPE` | rien | Cet emplacement est occupé. |
| Refus `ENTREPRISE_PLEINE` | rien | Ton entreprise est pleine. |
| Refus `DEJA_POSSEDE`, la seule bulle fière | rien | Tu l'as déjà. |
| Refus `GRADE_PRECEDENT_REQUIS` | rien | Il faut d'abord le grade précédent. |
| Refus `CONFLIT_REESSAYER`, si le second essai échoue aussi | rien | Ça a bougé. Réessaie. |

Les échecs techniques, dans le code `network`, `unavailable` et `error` : `network` et `unavailable` donnent « Le
service ne répond pas. » ; `error` avec la raison `PAS_FAIT` (route pas encore écrite) donne « Pas encore
disponible. » ; tout autre `error` donne « Problème côté service. ».

## Ce qui manque

Au contrat (`api/`, non modifié par cette tranche) :

- Pas de champ pour le contenu du Silo (la production en attente), ni de plafond hors ligne : l'écran du Silo ne
  montre que le Donar, le revenu et le multiplicateur, et le résultat de la dernière collecte.
- Pas d'objectif en cours : le HUD n'a que le seuil de prestige comme consigne.
- Pas d'heure de dernière collecte (`asOf`, `lastCollectAt`), ni d'ère par usine.
- Les noms d'usines ne sont pas dans les types générés, seulement les identifiants.
- Le sens exact de `restoreStep` est supposé : le dernier palier fait, la prochaine étape est la suivante.
- `nextCost` ne donne que le prix de 1 niveau. Les prix de 10 niveaux, du palier et du max ne sont pas affichés ;
  le service refuse ou accepte, et `Refused.missing` dit la somme qui manque.
- Pas de route pour racheter une ruine ni pour résoudre un incident : une usine en incident ou sans courant se
  voit, mais l'écran n'a rien à proposer.

Au design system :

- Pas d'icône pour Objectifs, Amis et Silo, ni de dossier `textures/ui/silooo/icones/` dans le pack de ressources.
- Pas d'image d'Oooscar utilisable en jeu : les bulles portent un repère entre crochets.
- Pas de glyphes dans le pack de ressources pour les icônes qui existent dans `design/icones/`.

## Ce qui reste à décider

- « Une action en deux clics » : le Silo y est (Silo, Encaisser), pas la montée d'une usine (Usines, la tuile, le
  bouton). Un accès direct depuis le menu principal à l'usine la plus rentable serait possible, mais il demande un
  choix de jeu (laquelle ?) qui n'est pas pris ici.

## Ce qui ne se vérifie que sur un vrai BDS

- Le canal de titre : papillotement entre deux envois, durée d'affichage, effet du masquage par le pack.
- Les espaces insécables : U+00A0 peut s'afficher comme un carré selon la police.
- Les codes de couleur de matériaux `§p`, `§u` et `§n`, propres à Bedrock et récents.
- Le refus `UserBusy` d'un formulaire et les nouveaux essais.
- L'événement `itemUse` sur la boussole, et son `lockMode` (`inventory`, pas de lâcher, pas de déplacement).
- Le don de l'objet au tout premier tick : le délai de `config.ui.menuItemDelaySeconds` est une hypothèse.
- Un formulaire envoyé à un joueur qui vient de partir.
- Les signes `§` dans les boutons des formulaires.

## Points ouverts

- L'API de script ne donne pas le XUID d'un joueur. `src/identity.ts` envoie à sa place une empreinte FNV-1a
  64 bits du gamertag (minuscules, sans espaces autour), en décimal : elle passe le schéma `Xuid` du contrat et la
  colonne `BIGINT UNSIGNED` du service, et survit à un monde recréé (décision 25 de `docs/passation.md`, à valider
  par Kr). Quand une source de vrai XUID existera, c'est le seul fichier à changer.
- Ce qui ne se vérifie que sur un vrai BDS : le comportement de `http.request` sur un statut 4xx ou 5xx et sur un
  délai dépassé, l'ordre de démarrage du script, le format de `allowed_uris` dans `permissions.json`.
- Un `join` qui échoue n'est pas rejoué, et un script rechargé ne refait pas le `join` des joueurs déjà en ligne.
