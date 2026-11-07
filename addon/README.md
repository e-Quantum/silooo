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
| `src/http.ts` | Client de silooo-core : résultats typés ok, refusé, erreur, réseau ; un seul nouvel essai, pour les GET (POST, PUT, PATCH et DELETE sont des écritures, jamais rejouées). |
| `src/protocol.ts`, `src/api.ts` | Chemins, corps et lecture des réponses, conformes à `api/silooo-core.openapi.yaml`. |
| `src/session.ts`, `src/state.ts` | `join` à la première arrivée, `leave` au départ, état gardé par joueur. |
| `src/heartbeat.ts`, `src/events.ts` | Heartbeat régulier ; lecture des événements, avec un curseur gardé entre deux démarrages. |
| `src/config.ts` | Cadences et délais techniques. Aucun chiffre du jeu. |
| `src/ui/` | Les écrans : HUD, menu, carte, prestige, objectifs, personnel, agence, réparation, associés, ville, boutique, pass, votes, boussole. Le détail est dans « Les écrans » plus bas. Seul `ui/textes.ts` contient des phrases. |
| `src/ui/formulaires.ts` | La seule enveloppe de `@minecraft/server-ui`. |
| `src/monde/` | Le monde : îles des usines, île principale (la ville), arrivée, voyage depuis la carte, cinématique, Oooscar. Le détail est dans « Le monde » plus bas. |
| `src/monde/bedrock.ts` | L'enveloppe de `@minecraft/server` pour le monde (blocs, caméra, entité, téléportation, curseur des événements). Seul `main.ts` l'importe. |
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
  `HttpRequestMethod` (`Get`, `Post`, `Put`, `Patch`, `Delete`), `HttpResponse` (status, body) ;
- `@minecraft/server-admin` : `secrets.get`, `variables.get`, et le type `SecretString`.

## Les écrans

Première tranche de la phase 3 : le HUD, le menu et la fiche d'usine, puis les objectifs (la première mission, celle de
la mairie). L'add-on n'y calcule rien. Chaque valeur affichée est un champ d'une réponse du service ; seul le compteur
de Donar avance entre deux réponses (revenu par heure × temps écoulé, jamais sous le solde du service). La phase 4
y ajoute le prestige et la carte des îles (voir « La carte et le prestige » plus bas), puis le personnel et l'agence (voir « Le personnel »), puis les incidents d'usine et leur réparation (voir « Les incidents »), puis les associés (voir « Les associés »), puis la ville et l'île principale (voir « La ville »). La phase 5 y ajoute la boutique en Gemmes, le pass de saison, les votes et les annonces qui vont avec (voir « La boutique, le pass et les votes »).

```
boussole ── MENU ─┬─ Silo ───── Encaisser ............ collect
                  ├─ Usines ─── tuile ─── fiche ─┬─ Monter de 1, de 10, au palier, au max ... buyLevels
                  │                              ├─ Parler, Réparer, Livrer, Recruter ....... restoreStep
                  │                              └─ Réparer (usine en incident) ─ mini-jeu ... resolveIncident
                  ├─ Objectifs ─ la mission en cours, les missions terminées ................ getMissions
                  ├─ Carte ──── l'île principale, une ligne par usine ─── île choisie ... téléportation (monde/voyage.ts)
                  ├─ Prestige ─ l'aperçu du service ─── Prestige ! ... prestige ── écran d'après
                  │                                     (lecture de l'aperçu .......... prestigePreview)
                  ├─ Personnel ─ une équipe par usine ─ un membre ─┬─ Affecter à une usine ... assignStaff
                  │         │                                      └─ Renvoyer, confirmé ..... fireStaff
                  │         ├─ Agence ─ un candidat ─ Embaucher ─ son usine, ou aucune ... hire
                  │         │          (lecture des candidats du jour ........ getCandidates)
                  │         └─ Associés ─ la liste ─┬─ Inviter ─ un joueur connecté ....... invite
                  │             (relue à chaque     ├─ Parts (PDG) ─ un champ par membre . setShares
                  │              ouverture ........ ├─ Invitation reçue ─ Accepter ... acceptInvite, puis le HUD
                  │              getCompany)        └─ Retour
                  ├─ Boutique ─ les Gemmes (catalogue, pass et votes relus à l'ouverture .. catalog, getPass, todayVotes)
                  │         ├─ Grades, Cosmétiques par catégorie ─ un article ─┬─ Acheter, Prolonger ... buyItem
                  │         │                                                  └─ Offrir ─ un joueur connecté ... buyItem + forXuid
                  │         ├─ Pilier, Boost collectif ─ un article (le boost ne s'offre pas)
                  │         └─ Pass de saison ─┬─ Passer premium .................... buyItem (pass-premium)
                  │              (relu ....... └─ Paliers ─ un palier ─ Réclamer le gratuit, le premium .... claimPass
                  │               getPass)
                  └─ Ville ──── la ville (relue à chaque ouverture ... getCompany) ─┬─ Acheter la zone ........ buyZone
                                                                                     ├─ Bâtiments ─ un bâtiment ─ Améliorer ... upgradeBuilding
                                                                                     └─ Construire ─ un type ─ Poser ici ...... placeBuilding
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
| `etat.ts` | La dernière vue de chaque joueur (une réponse plus ancienne, ou d'une autre entreprise, est ignorée ; `switchCompany` change d'entreprise) et l'état de la liaison avec le service. |
| `actions.ts` | Les seize actions (encaisser, acheter des niveaux, remettre en état, réparer un incident, prestige, embaucher, affecter, renvoyer, inviter, fixer les parts, accepter une invitation, acheter une zone, poser un bâtiment, améliorer un bâtiment, acheter un article de la boutique, réclamer un palier du pass) : un identifiant par action, un seul nouvel essai après `CONFLIT_REESSAYER`, une action à la fois par joueur. Après un encaissement réussi, elle appelle `afterCollect` (la relecture des missions). L'aperçu du prestige, les candidats du jour, le catalogue, le pass et les votes du jour sont des lectures : ni verrou ni identifiant. |
| `missions.ts` | Les missions de chaque joueur d'après la dernière lecture de `getMissions`, et l'annonce d'une mission terminée. Les trois moments de lecture : l'arrivée, après chaque encaissement, une annonce. |
| `usines.ts` | La situation d'une usine d'après les champs du contrat, et la prochaine étape de remise en état. |
| `hud.ts` | Le HUD : rendu pur, puis envoi aux joueurs une fois par seconde. |
| `ecran.ts` | Le vocabulaire commun des écrans : routes, cibles des boutons, `Screen`, bulle et pages. Aucun écran, aucun import de Bedrock. |
| `menu.ts` | Le menu, la liste des usines, la fiche, le Silo, les objectifs, la carte, le prestige (avant et après), et le parcours entre tous les écrans, personnel, associés, ville et boutique compris. |
| `reparation.ts` | Le mini-jeu de réparation : la logique pure (`repairMove`, qui juge un choix et rend l'étape suivante, la même étape après une erreur, ou la fin), l'écran d'une étape, et `playRepair`, qui enchaîne les formulaires et rend `done` ou `back`. Il n'appelle pas le service : c'est `menu.ts` qui appelle `resolveIncident` après la dernière étape réussie. |
| `incidents.ts` | L'événement `INCIDENT` en phrases pour le tchat (un incident s'ouvre, un incident est réparé, avec la récompense du service), puis la relecture de l'état de l'entreprise pour que la fiche d'usine suive. |
| `personnel.ts` | Les écrans du personnel et de l'agence, la porte des associés, et la ligne d'équipe de la fiche d'usine : des fonctions pures, comme celles de `menu.ts`. |
| `associes.ts` | Les écrans des associés (la liste, le choix du joueur à inviter, l'invitation reçue), le formulaire des parts et la lecture de ses champs : des fonctions pures. |
| `ville.ts` | Les écrans de la ville : la tuile du menu et celle de la carte, la ville (mairie, bonus, prochaine zone), la liste des bâtiments, la fiche d'un bâtiment, le choix d'un bâtiment à poser, la pose, et la bulle d'Oooscar de chaque refus : des fonctions pures. |
| `boutique.ts` | Les écrans de la boutique : la tuile du menu, la boutique, un rayon, la fiche d'un article, le choix du joueur à qui l'offrir, la ligne du titre Pionnier, et la bulle d'Oooscar de chaque achat ou refus : des fonctions pures. |
| `pass.ts` | Les écrans du pass de saison : la porte dans la boutique, le pass, la liste des paliers, la fiche d'un palier avec ses deux pistes, et la bulle d'un palier réclamé : des fonctions pures. |
| `votes.ts` | Le rappel du vote du jour (le nom des sites de `definitions/vote.json`, la ligne de la boutique, la lecture à l'arrivée du joueur) et les `ANNONCE` `vote` et `voteParty`. |
| `annonces.ts` | Les événements `GEMMES_RECUES` et `BOOST_COLLECTIF`, les `ANNONCE` `pionnier` et `sansCourant` : une bulle ou une ligne de tchat chacun, et le titre de pionnier retenu pour l'en-tête du menu. |
| `annonces-ville.ts` | L'événement `ANNONCE` d'un achat de zone, d'une pose ou d'une amélioration : relecture de l'état de l'entreprise, puis une bulle courte dans le tchat. |
| `invitations.ts` | Les invitations reçues de chaque joueur d'après l'événement `INVITATION` (une par entreprise, jusqu'à `expiresAt`), et l'annonce d'un changement de parts. |
| `formulaires.ts` | La seule enveloppe de `@minecraft/server-ui` : des écrans à boutons (`show`) et des formulaires à champs de texte (`ask`). Les tests passent un faux présentateur. |
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
la bulle de fin de la mission puis les Lingots que le payload donne, et les missions sont relues. Si le curseur
des événements est perdu, les événements sont relus depuis le début : l'add-on retient les missions déjà terminées à
l'arrivée du joueur et ne redit pas une annonce déjà dite pendant sa visite.

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
fermée tant que l'entreprise n'a pas sa première zone et sa mairie (« Achète la première zone. »), puis ouverte : elle mène à la
mairie (voir « La ville »). Ensuite vient une ligne par usine dans l'ordre de `definitions/usines.json`,
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

### Le personnel

Quatrième tranche des écrans (phase 4). Toujours aucun calcul : le bonus, le salaire par heure, le prix d'embauche, la
rareté et le nom d'un membre ou d'un candidat sont des champs du service, mis en forme par `ui/nombres.ts`. Les écrans
sont dans `ui/personnel.ts` ; les phrases dans `PERSONNEL_TEXTES` et `AGENCE_TEXTES` (`ui/textes.ts`), tous à valider par
Kr. Le personnel de l'entreprise est `CompanyState.staff` (le contrat ne le marque pas obligatoire : absent, il est vide).

**Personnel.** La tuile du menu, après Prestige (ligne d'info « Aucun membre », « 1 membre », « `<n>` membres »), ouvre
l'écran PERSONNEL. En tête, la porte de l'agence (sur chaque page), puis une tuile par équipe : une par usine qui a du
monde, dans l'ordre des usines du service, puis « Sans usine » pour les membres qui n'en ont pas. Chaque tuile dit combien
de membres y travaillent. Une tuile ouvre l'équipe : un membre par tuile, son nom de la couleur de sa rareté, puis la
ligne « `<rareté>` · `<rôle>` · +`<bonus>` % ». La couleur ne porte jamais seule : la rareté est écrite à côté.

**Un membre.** La fiche donne la rareté et le rôle, le nom, le bonus de production, le salaire par heure
(`salaryPerHour`, en Donar), l'usine, et l'apparence telle que le service la donne (`skinId`, brut, faute de nom
d'apparence). Trois boutons : « Affecter à une usine », « Renvoyer », « Retour ».

**Affecter.** Le choix liste toutes les usines de l'entreprise sauf celle du membre, avec le nombre de membres qui y
travaillent. L'add-on ne connaît pas les postes d'une usine : il demande, et si le service refuse (`EMPLACEMENT_OCCUPE`),
Oooscar dit « Plus de poste libre ici. » et l'écran reste. Réussi, on revient à la fiche du membre.

**Renvoyer.** Le bouton « Renvoyer » n'existe que sur l'écran de confirmation, avec « Garder » pour renoncer. L'add-on ne
lit pas le rôle du joueur : il envoie, et le refus `PAS_LE_PDG` (403) devient la bulle « Seul le PDG peut le faire. ».
Renvoyé, on revient à l'écran PERSONNEL.

**Agence.** À chaque ouverture, l'écran relit les candidats du jour (`getCandidates`, une lecture : ni verrou ni
identifiant) et les montre tels que le service les donne, un par tuile : nom, rareté, rôle, bonus, ou la marque
« Déjà embauché » quand le service a rendu un `staffId`. Sans lecture (échec), Oooscar le dit et il ne reste que « Retour ».
**Il n'y a aucun bouton de nouvelle liste contre du Donar** : `docs/game-design.md` en parle, mais un tirage payant est du
hasard payant, interdit (`CLAUDE.md`) ; le contrat n'a d'ailleurs pas la route.

**Un candidat.** La fiche donne la rareté et le rôle, le nom, le bonus, le salaire en pourcent du revenu de l'usine, le
prix d'embauche (`hireCost`, tel que le service le donne) et l'apparence. « Embaucher » mène au choix de l'usine, où
« Sans usine » vient d'abord (sur chaque page), puis les usines de l'entreprise. `hire` part avec l'usine choisie, ou sans.
Embauché, on revient à l'agence, relue : le candidat porte la marque « Déjà embauché ». Refusé (`PAS_ASSEZ_DE_DONAR` avec la
somme qui manque, `USINE_VERROUILLEE`, `EMPLACEMENT_OCCUPE`, `DEJA_POSSEDE`), l'écran reste et Oooscar le dit.

**La fiche d'usine.** Quand au moins un membre y travaille, elle ajoute une ligne « Équipe : `<n>` membres, avec chef » (ou
« sans chef »), lue dans la liste du personnel : « chef » veut dire un membre qui n'est pas un simple salarié.

### Les incidents

Le service décide des incidents (quand, de quelle sorte, de combien la production ralentit, ce que la réparation
rapporte) ; l'add-on les montre et propose le mini-jeu. Il ne calcule rien : ni ralentissement, ni gain.

**Ce que la fiche d'usine dit.** `Factory.incidentKind` donne la sorte (`incendie`, `panne`, `courroie`, ou `null`). La
tuile, la ligne de la carte et la fiche la nomment (« Début d'incendie »), avec une phrase par sorte. Une sorte que
l'add-on ne connaît pas, ou absente, garde le mot « Incident ». Une usine en incident a un bouton « Réparer », en premier ;
elle garde ses boutons de montée de niveau (`canBuyLevels` est lu comme pour toute usine). Une usine arrêtée faute de courant
(`SANS_COURANT`) n'a aucun bouton de réparation : la dernière ligne de la fiche dit qu'il faut une Centrale en marche.

**Le mini-jeu** (`ui/reparation.ts`). « Réparer » ouvre trois écrans à boutons, dans l'ordre : le geste de sécurité, l'outil
ou la pièce, le geste final. Un seul bouton est juste. Un mauvais choix rejoue l'étape avec un indice d'Oooscar, sans rien
perdre ; « Retour » quitte le jeu sans rien demander. Au dernier bon choix, `resolveIncident` (POST
`/v1/companies/{companyId}/incidents/{incidentId}/resolve`, même corps que `restoreStep`) clôt l'incident :
`200` montre « L'usine repart ! » avec l'état que le service rend ; un refus `409` passe par le mécanisme de refus des
autres actions. Le mini-jeu n'est pas jugé par le service : il ne vérifie que la réparation demandée.

**L'événement `INCIDENT`** (`ui/incidents.ts`). Quand un incident s'ouvre, le joueur en ligne lit dans le tchat quelle usine et
quelle sorte ; quand il est réparé, il lit qui l'a réparé et la récompense, telle que le service la donne
(`payload.reward`, mise en forme comme les autres nombres). Une récompense de 0 le dit, et dit si un associé avait déjà
réparé. L'add-on relit ensuite l'état de l'entreprise, parce que l'événement ne le rend pas : sans cela la fiche ne
montrerait le bouton « Réparer » qu'au prochain relevé.

### Les associés

Cinquième tranche des écrans (phase 4). Le service décide de tout : la liste des membres, leur rôle, leur part, le nombre
maximal d'associés, qui peut inviter. L'add-on n'additionne aucune part, ne compte aucun associé pour en déduire une
limite, et ne connaît pas de maximum. Les écrans sont dans `ui/associes.ts` (fonctions pures) et les invitations reçues
dans `ui/invitations.ts` ; les phrases dans `ASSOCIES_TEXTES`, toutes à valider par Kr. La liste des membres est
`CompanyState.members` (le contrat ne la marque pas obligatoire : absente, elle est vide).

**Associés.** La tuile, sous Personnel (le menu principal n'a que huit places : la Boutique a pris la sienne), juste après l'Agence, ouvre l'écran ASSOCIÉS ; son « Retour » revient à PERSONNEL. À chaque ouverture, il relit l'entreprise
(`getCompany`) : la liste est celle du service, le PDG en premier, avec le rôle, le nom et la part en pourcent. La ligne du
joueur porte « (toi) ». Un joueur seul lit qu'il peut inviter « jusqu'à ce que le service refuse » : l'écran ne donne aucun
chiffre. Le PDG a les boutons « Inviter » et « Parts » (« Parts » seulement quand l'entreprise a au moins deux membres) ;
un associé n'a que la liste. Les invitations reçues, s'il y en a, sont les premières entrées de la liste.

**Inviter.** Le choix liste les joueurs connectés que le service connaît (ceux dont le `join` a répondu), sauf le joueur
lui-même, par leur nom. L'identifiant (XUID) part avec la requête `invite`. Réussi, Oooscar dit « Invitation envoyée. »
et on revient à l'écran ASSOCIÉS. Un refus passe par les bulles de refus : `PAS_LE_PDG`, `DEJA_POSSEDE` (« Il est déjà
associé. »), `ENTREPRISE_PLEINE`, `ENTREPRISE_EN_COURS` (« Il a déjà lancé son entreprise. »). Un 400 veut dire que le
service ne connaît pas ce joueur.

**Parts.** Le formulaire a un champ de texte par membre, rempli avec la part que le service a donnée. L'add-on vérifie
seulement que chaque champ est un entier de 0 à 100 ; il n'additionne rien. C'est le service qui répond : un 400 (la liste
ne nomme pas exactement les membres, ou le total n'est pas celui qu'il attend) devient la bulle « Les parts doivent faire
100. ». Un 200 remplace la liste par celle de la réponse.

**Invitation reçue.** L'événement `INVITATION` (adressé à l'invité) est gardé en mémoire, une invitation par entreprise qui
invite (une nouvelle remplace l'ancienne), jusqu'à son `expiresAt` ou jusqu'à la réponse du joueur. L'add-on compare
`expiresAt` à l'horloge, rien d'autre. Le joueur en est prévenu dans le tchat (une fois par invitation, et à son arrivée
s'il était hors ligne). L'entrée « `<nom>` t'invite dans `<entreprise>` » de l'écran ASSOCIÉS ouvre une fenêtre avec
« Accepter » et « Plus tard ». Accepter appelle `acceptInvite` sur l'entreprise de l'invitation. Réussi, l'add-on prend
l'identifiant et l'état de la réponse (il change d'entreprise, sans comparer les versions : ce sont deux entreprises), puis
redessine le HUD. Le joueur quitte son entreprise actuelle ; le texte de la fenêtre le dit. Un 400 veut dire que
l'invitation a expiré : elle disparaît de la liste.

**Les annonces.** L'`ANNONCE` du service après un `setShares` (elle n'est envoyée qu'à celui qui a agi) fait dire à Oooscar,
dans le tchat, « Les parts ont changé. ». La liste n'est relue qu'à la prochaine ouverture de l'écran.

**Verrous.** `invite`, `setShares` et `acceptInvite` passent par `Actions` comme les autres : un identifiant par action,
une action à la fois par joueur, un seul nouvel essai après `CONFLIT_REESSAYER`. `setShares` change la version de
l'entreprise, pas `invite`. Pour `acceptInvite`, le nouvel essai ne relit pas l'entreprise (celle qu'on relirait est
l'ancienne).

### La ville

Sixième tranche des écrans (phase 4) : la ville de l'île principale. Le service décide de tout : les zones que l'entreprise
possède, le prix et les conditions de la suivante, chaque bâtiment avec son niveau, sa parcelle, sa rotation, son bonus et le
prix du niveau suivant, et les bonus d'ensemble (plafonds compris). L'add-on ne calcule ni prix, ni bonus, ni gain. Il lit
`CompanyState.city` (facultatif au contrat : un état sans `city` est valide, les champs inconnus sont ignorés) et affiche. Les
phrases sont dans `VILLE_TEXTES`, `BATIMENT_NOMS` et la tuile `MENU_TEXTES.ville`, toutes à valider par Kr. Les écrans sont
dans `ui/ville.ts` (fonctions pures), le plan de blocs dans `monde/ville.ts`.

**Le menu.** La tuile « Ville » est la huitième et dernière : elle prend la place de la tuile verrouillée « Amis ». Sans `city`
dans l'état, elle est fermée et dit les usines actives qu'il faut pour la première zone (le chiffre vient de
`definitions/ville.json`, `zones.premiere.usinesActivesMin`). Dès que le service donne une ville, elle s'ouvre : avant la
première zone, sa ligne d'info donne les usines actives que le service demande ; ensuite, le niveau de la mairie.

**La ville.** L'écran VILLE relit l'entreprise à chaque ouverture (`getCompany`), parce qu'un associé a pu acheter une zone ou
poser un bâtiment. Avant la première zone : « Tu n'as pas encore de zone. » et la prochaine zone. Ensuite : le niveau de la
mairie, les bonus d'ensemble tels que le service les donne (une ligne par bonus qui n'est pas nul), la prochaine zone
(numéro, prix en Lingots, mairie requise, usines actives requises), puis les boutons « Acheter la zone `<n>` », « Bâtiments »
et « Construire ». Le bouton d'achat est toujours montré tant que `nextZone` n'est pas nul : le service juge, et la bulle
d'Oooscar dit le refus (`ZONE_VERROUILLEE` : il manque des usines actives pour la première zone, la mairie doit monter
pour les autres). Quand toutes les zones sont à l'entreprise (`nextZone` nul), l'écran le dit.

**Les bâtiments.** La liste (la mairie en tête, une page à la fois comme celle des usines) montre pour chacun son nom, son
niveau et son bonus, ou sa parcelle quand il n'en a pas. La fiche donne le niveau sur le maximum, le bonus, la parcelle et le
prix du niveau suivant avec la mairie qu'il demande ; « Améliorer » appelle `upgradeBuilding`. Au dernier niveau, la fiche
dit « Niveau maximum. ». L'add-on n'affiche pas le bonus du niveau suivant : le contrat ne le donne pas.

**Construire et poser.** « Construire » liste les types que le catalogue de `definitions/ville.json` marque posables, avec le
prix du niveau 1 et son bonus (la mairie n'est pas posable). Choisir un type ouvre POSER : l'écran lit la position du joueur
(`ArrivalWorld.locate`), l'arrondit vers le bas à la grille d'une parcelle (16 blocs) depuis le coin de l'île principale, et
montre la parcelle. « Tourner » fait un quart de tour et dit où est la porte (ou le sens de la route). « Poser ici » relit la
position à cet instant, puis appelle `placeBuilding` avec le type, la parcelle et la rotation ; l'add-on ne vérifie rien d'autre
(zone possédée, emplacement libre, quota : c'est le service). Hors de l'île principale, ou sans position lisible, aucune
requête ne part et Oooscar le dit. Réussi, on revient à l'écran VILLE, qui montre l'état rendu.

**L'île principale.** Le relevé du monde (`monde/synchro.ts`) montre la ville au constructeur (`syncCity`) à chaque tour, après
les mini-îles. Le constructeur pose, un chantier à la fois, la plateforme de chaque zone possédée (le même sol que les
mini-îles, carré et plein sur 128 blocs, par bandes de deux chunks), puis chaque bâtiment : un préfabriqué simple par type
(`GABARITS` dans `monde/ville.ts` : quatre murs, une porte de deux blocs, un toit, une lanterne pour certains, une enseigne
avec le nom, le niveau et le bonus), centré sur sa parcelle de 16 blocs. La route est une bande de gravats, plus large d'un
bloc de chaque côté par niveau. Un niveau de plus rehausse le bâtiment d'un étage (tous les deux niveaux pour la mairie). Une
marque par zone et par bâtiment (type, niveau, parcelle, rotation, origine d'archipel) évite toute repose, et un bâtiment
attend la plateforme de sa zone. Rien n'est démoli : un bâtiment qui disparaît de la liste du service reste dans le monde. Un
type inconnu de l'add-on devient une boîte de planches qui porte son nom, avec un seul avertissement au journal.

**Le voyage.** La ligne « Île principale » de la carte s'ouvre quand l'entreprise a sa première zone et sa mairie, et mène à
la mairie (`createCityTravel`) : le joueur arrive à deux blocs devant la porte, le regard sur elle, une fois la plateforme et la
mairie posées (au plus `config.world.travelWaitSeconds`). Sinon elle reste fermée et dit « Achète la première zone. ».

**Les annonces.** L'`ANNONCE` d'un achat de zone, d'une pose ou d'une amélioration (`payload.action` vaut `buyZone`,
`placeBuilding` ou `upgradeBuilding`, adressée à celui qui a agi) relit l'état de l'entreprise puis fait dire à Oooscar une
bulle courte dans le tchat (`ui/annonces-ville.ts`). C'est un doublon voulu de la bulle de l'écran : l'événement prévient aussi
un joueur qui a agi depuis une autre session.

**Verrous.** `buyZone`, `placeBuilding` et `upgradeBuilding` passent par `Actions` comme les autres : un identifiant par
action, une action à la fois par joueur, un seul nouvel essai après `CONFLIT_REESSAYER` (avec `getCompany` entre les deux).

### La boutique, le pass et les votes

Première tranche de la phase 5 côté add-on. Le service décide de tout : les prix en Gemmes, ce que le joueur possède, ce qu'il
peut acheter, les paliers du pass et leur état, les votes du jour. L'add-on n'ajoute ni ne retire une Gemme : chaque solde
affiché est celui de la dernière réponse du service. Les écrans sont dans `ui/boutique.ts`, `ui/pass.ts` et `ui/votes.ts`
(fonctions pures), les annonces dans `ui/annonces.ts` ; les phrases dans `BOUTIQUE_TEXTES`, `PASS_TEXTES`, `VOTE_TEXTES` et
`ANNONCES_TEXTES`, toutes à valider par Kr. Le client (`catalog`, `buyItem`, `getPass`, `claimPass`, `todayVotes`) est dans
`api.ts`, ses chemins et la lecture des réponses dans `protocol.ts`, et un test les confronte à `api/silooo-core.openapi.yaml`.

**Où est la tuile.** Le menu principal n'a que huit places : la tuile Boutique prend celle d'Associés, qui passe sous
Personnel (l'écran PERSONNEL a deux portes, l'Agence et les Associés, sur chaque page ; « Retour » des associés revient à
PERSONNEL). Ville reste la huitième. Le pass de saison est une entrée de la boutique, pas une tuile du menu. La ligne d'info
de la tuile est le solde de Gemmes du service.

**La boutique.** BOUTIQUE relit à chaque ouverture, ensemble, `catalog`, `getPass` et `todayVotes` (des lectures : ni verrou,
ni identifiant). Elle montre les Gemmes, le rappel du vote du jour (une ligne, seulement quand le service dit zéro vote
aujourd'hui), puis les portes : Grades (avec le prochain grade à prendre), Pilier, Cosmétiques, Boost collectif, Pass de
saison (avec le nombre de récompenses à réclamer). Seul l'échec du catalogue est dit par Oooscar ; une relecture qui échoue
garde la dernière lecture réussie ; sans lecture réussie, il reste les portes des rayons et celle du pass, sans prix. Sans
Gemme, l'écran dit où en acheter : « Les Gemmes s'achètent sur la boutique du site. » L'add-on n'écrit aucune adresse, et ni le
contrat ni `definitions/` n'en donnent.

**Les rayons et les articles.** L'ordre est celui du catalogue du service. Les noms, les catégories et les bonus viennent de
`definitions/boutique.json` (grades, abonnements, cosmétiques, boost collectif) ; le prix, « possédé » et « disponible » du
catalogue. Une tuile d'article dit son prix, « Possédé » (`[H]`), « En cours » pour un abonnement, ou porte un cadenas et la
raison (« Grade précédent requis », « Pas en vente »). Les cosmétiques sont rangés par catégorie (la clé `categorie` du JSON,
six rayons). Le préfixe perso (l'abonnement `perso`) n'est pas montré : il demande de choisir un texte, et l'add-on n'a pas de
mécanisme de préfixe de tchat. Un article du catalogue que le JSON ne connaît pas n'est pas montré non plus. Les cosmétiques
qui ne se vendent pas (`pionnier`, ceux du pass) ne sont pas dans le catalogue du service.

**Acheter, prolonger.** La fiche d'un article donne son prix, ce qu'il apporte, les Gemmes du joueur, et « Acheter »
(« Prolonger » pour un abonnement en cours). L'achat est `buyItem` : un identifiant par action, une action à la fois par
joueur, un seul nouvel essai après `CONFLIT_REESSAYER` (avec `getCompany` entre les deux). Réussi, les Gemmes affichées sont
celles que le service rend ; le catalogue est relu (ce que le joueur possède, le prochain grade) ; l'entreprise est relue
quand l'article change le revenu (un grade, le Pilier, le boost). Oooscar dit « Achat effectué ! », « Abonnement prolongé ! »
ou « Boost lancé pour tout le serveur. ». Faute de Gemmes (`PAS_ASSEZ_DE_GEMMES`), la bulle dit la somme qui manque et une
ligne dit où en acheter. Un 400 veut dire que le service ne vend plus l'article.

**Offrir.** La fiche propose aussi « Offrir », sauf pour le boost collectif : le choix liste les joueurs connectés que le
service connaît, sauf le joueur (comme pour inviter), et l'identifiant part dans `forXuid`. C'est le joueur qui paie. Réussi,
il revient à la fiche et Oooscar dit « Cadeau envoyé ! ». Deux refus changent de mots : `DEJA_POSSEDE` (« Il l'a déjà. ») et
`GRADE_PRECEDENT_REQUIS` (« Il lui faut le grade précédent. »). Un 400 veut dire que le service ne connaît pas le
destinataire (« Je ne connais pas ce joueur. »).

**Le pass.** L'écran PASS, relu à chaque ouverture, donne la saison, ses dates, les points du joueur, l'état de la piste
premium, « Paliers » et, quand la piste premium est en vente et pas possédée, « Passer premium » avec le prix du catalogue :
c'est un `buyItem` de `pass-premium`, après quoi le catalogue et le pass sont relus. PALIERS liste les paliers du service
avec leur état (à réclamer, réclamé, pas atteint, demande le premium). La fiche d'un palier montre les deux pistes, gratuite
et premium, la récompense de chacune (des Lingots, ou le nom d'un cosmétique) et un bouton par récompense à réclamer.
« Réclamer » appelle `claimPass` ; la réponse donne les Lingots ou le cosmétique versés (« Reçu : … »), le solde de Lingots
est celui du service, et le pass est relu pour que la fiche montre l'état du service. Un refus (`PALIER_NON_ATTEINT`,
`DEJA_RECLAME`, `PREMIUM_REQUIS`, `SAISON_INACTIVE`) relit aussi le pass : la lecture était peut-être périmée.

**Les votes.** À l'arrivée du joueur, après la cinématique, comme les missions et les invitations, l'add-on lit `todayVotes` ;
si le joueur n'a pas voté aujourd'hui, Oooscar le lui rappelle dans le tchat avec le nom des sites de vote. Ces noms sont les
`nom` de `definitions/vote.json`, séparés par des virgules : jamais un lien. La même ligne est sur l'écran de la boutique. Un
`ANNONCE` `vote` (adressé au joueur qui a voté) fait dire à Oooscar « Merci pour ton vote ! » avec les Lingots versés, la
série et le bonus de série, tels que l'événement les donne ; un `ANNONCE` `voteParty` est dit à tous les joueurs présents,
une fois, quand son `day` est le jour (UTC) d'aujourd'hui.

**Les annonces.**

- `GEMMES_RECUES` (adressé au joueur qui reçoit les Gemmes) : une bulle d'Oooscar par livraison, avec les Gemmes arrivées et le
  solde. Le solde de Gemmes affiché ensuite est le `gemmesTotal` de l'événement ; l'état et l'ancrage de la vue ne changent pas.
- `BOOST_COLLECTIF` : une seule ligne de tchat pour tous les joueurs présents, avec le pseudo de l'acheteur s'il est donné. La
  durée dite est ce qui reste à courir, lu dans `endsAt` ; un boost fini ne se redit pas.
- `ANNONCE` `pionnier` : une bulle de bienvenue, une fois par joueur et par chargement du script. Le titre est retenu pour
  l'en-tête du menu, qui montre « Titre : Pionnier » sous le nom de l'entreprise quand `join` (champ `cosmetics`) le donne,
  ou quand l'annonce l'a appris. L'objet « Pionnier » n'existe pas côté service : l'add-on ne le donne pas.
- `ANNONCE` `sansCourant` : une bulle d'Oooscar, puis le nom de l'usine arrêtée faute de courant, au joueur que l'événement vise.

Le service garde les événements, et le curseur de l'add-on est gardé dans la propriété dynamique `silooo:events-cursor` du
monde : un redémarrage de BDS ne rejoue rien. Si ce curseur est perdu (monde neuf, propriété illisible), les livraisons de
Gemmes, les votes et le titre de pionnier, qui n'ont pas de date, se redisent. Le boost fini et la Vote party d'un autre jour
sont reconnus par leurs dates et se taisent. Le solde de Gemmes d'une livraison rejouée cède la place au suivant, qui arrive
dans la même relecture.

**Les raretés du personnel.** `ui/textes.ts` lit le nom de chaque rareté dans `definitions/personnel.json` (clé « nom »), au
lieu de le recopier ; un test le compare au fichier. Le nom de la plus commune y est « Ordinaire », où le code disait
« Commun » : le fichier fait foi.

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
| `constructeur.ts` | Pose les plans par lots entre deux pauses, un chantier à la fois, la zone chargée par une ticking area. Un échec se rejoue après 2 s, puis le délai double jusqu'à 30 s. Une marque par usine et par état, rangée dans une propriété dynamique du monde : une île n'est posée qu'une fois par état. `syncCity` fait de même pour la ville : une marque par zone (« ville:zone:`<n>` ») et par bâtiment (« ville:b:`<id>` »), qui change avec le niveau, la parcelle, la rotation ou l'origine. Un bâtiment attend la plateforme de sa zone. |
| `arrivee.ts` | La suite de l'arrivée, et le moment où le joueur est « occupé » : HUD silencieux, boussole en attente, menu fermé. |
| `cinematique.ts` | Le lecteur de cinématique : plans, caméra libre et glissé, fondus pour cacher les coupes, HUD masqué, sous-titres par le canal de titre, saut en restant accroupi une seconde, remise en état à la fin. |
| `oooscar.ts` | Une Oooscar par joueur, jamais deux, retirée au départ, balayée au démarrage et à chaque relevé. |
| `synchro.ts` | Le relevé périodique : l'état des usines de chaque joueur est montré au constructeur, qui ne refait que ce qui a changé. Une usine remise en état, ou retombée en ruine par un prestige, change de visage au relevé suivant. Il retient aussi l'origine d'archipel de chaque entreprise (`originOf`), pour le voyage. Quand l'état porte une ville (`city`), il la montre aussi au constructeur (`syncCity`), après les mini-îles. |
| `voyage.ts` | Le voyage depuis la carte : origine de l'archipel, point d'arrivée de l'île choisie (`arrivalAt`), îles montrées au constructeur, attente de l'île voulue, téléportation. Il n'importe pas `@minecraft/*` : le monde est un `ArrivalWorld`, comme pour l'arrivée. `createCityTravel` fait le même voyage vers la porte de la mairie. |
| `ville.ts` | Le plan de l'île principale, en fonctions pures : la plateforme de chaque zone possédée (par bandes de deux chunks), les bâtiments en préfabriqués simples (murs, porte, toit, enseigne), la route en bande de gravats, la parcelle sous les pieds d'un joueur (`parcelAt`), les marques et la place devant la porte de la mairie (`doorArrival`). |
| `bedrock.ts` | L'enveloppe de `@minecraft/server` : blocs, panneaux, zones chargées, caméra, entité, téléportation, position d'un joueur (`locate`). |

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

Liaison D3, à valider par Kr : « Mon compte », « MON COMPTE », « DISCORD », « Relier Discord »,
« Dans Discord, saisis ton code avec /lier ou le bouton Bienvenue. », « Émettre un code »,
« Rafraîchir le code », « Réessayer », « Expire le », « Code : », « Reconnecte-toi au jeu. »,
« Le service ne répond pas. Réessaie. », « Ton compte n'est pas encore enregistré dans le jeu. Reconnecte-toi. ».
Le code et l'expiration affichés sont ceux du service ; aucun code n'est généré ou écrit dans les journaux du client.
Le chemin est Menu > Mon compte > Relier Discord ; Prestige reste accessible depuis Mon compte.

Tous les textes de joueurs de cette tranche sont à valider par Kr. Ils sont commités, jamais présentés comme
définitifs. Ils vivent dans `src/ui/textes.ts`, et un test vérifie que chacun figure dans ce tableau. L'avant est
« rien » : ces écrans n'existaient pas, sauf là où la colonne dit autre chose.

Les noms des usines ne sont pas dans ce tableau : ils viennent de `definitions/usines.json` (clé « nom »), recopiés
dans `FACTORY_NAMES` et comparés au fichier par un test. Les repères provisoires (`[D] [L] [G] [U] [T] [!] [E] [X]
[S] [C] [O] [A] [P] [M] [R] [N] [V] [B] [Y] [F] [K] [H] [Q]` pour les icônes, `[joie]`, `[fier]`, `[oups]`, `[panique]`, `[dodo]` pour Oooscar) sont dans
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
| Menu, tuile verrouillée Amis, remplacée par la tuile Ville | Amis | Ville |
| Menu, tuile | rien | Personnel |
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
| Fiche, phrase d'un incident de sorte inconnue (le service ne dit plus qu'il bloque : il ralentit) | Un incident bloque sa production. | Un incident ralentit sa production. |
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
| Refus `ENTREPRISE_EN_COURS` (à valider par Kr) | rien | Tu as déjà lancé ton entreprise. |
| Refus `DEJA_POSSEDE`, bulle fière | rien | Tu l'as déjà. |
| Refus `GRADE_PRECEDENT_REQUIS` | rien | Il faut d'abord le grade précédent. |
| Refus `CONFLIT_REESSAYER`, si le second essai échoue aussi | rien | Ça a bougé. Réessaie. |
| Refus `PALIER_NON_ATTEINT` du pass (à valider par Kr ; avec la somme, puis sans) | rien | Il te manque `<somme>` points. Pour un seul point : Il te manque 1 point. Sans somme : Il te manque des points. |
| Refus `DEJA_RECLAME` du pass (à valider par Kr) | rien | Tu as déjà réclamé ce palier. |
| Refus `PREMIUM_REQUIS` du pass (à valider par Kr) | rien | Cette piste demande le pass premium. |
| Refus `SAISON_INACTIVE` du pass (à valider par Kr) | rien | La saison n'est pas en cours. |
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
| Carte, île principale, tuile verrouillée tant que l'entreprise n'a pas de zone | Île principale, avec le mot Bientôt | Île principale, avec Achète la première zone. |
| Carte, usine que l'entreprise n'a pas | rien | ????? |
| Carte, usine entière | rien | Entière · niv. `<niveau>` |
| Carte, usine en ruine | rien | En ruine |
| Carte, usine en travaux | rien | En travaux `<étape>`/`<total>` |
| Carte, boutons | rien | Précédent, Suite, Retour, et la ligne Page `<n>` sur `<total>` des usines |
| Bulle oups, usine que l'entreprise n'a pas | rien | Tu n'as pas encore cette usine. |
| Bulle oups, île introuvable (pas d'origine d'archipel, ou île absente de `iles.json`) | rien | Je ne trouve pas cette île. |
| Bulle oups, île pas prête dans le délai | rien | L'île n'est pas encore prête. |

Le personnel et l'agence (`PERSONNEL_TEXTES`, `AGENCE_TEXTES` et les bulles qui s'y ajoutent). Les nombres, les noms et les
apparences viennent du service. Le nom de la troisième rareté est à confirmer : le mot du game design est banni par la
charte (`design/README.md`), « Exceptionnel » tient la place. La couleur du nom suit la rareté (gris, bleu, jaune,
`ui/apparence.ts`), à valider aussi.

| Où | Avant | Après |
|---|---|---|
| Menu, tuile Personnel, ligne d'info (aussi sur les tuiles d'usine des écrans d'affectation et d'embauche) | rien | Aucun membre, 1 membre, puis `<n>` membres |
| Fiche d'usine, ligne d'équipe (quand au moins un membre y travaille) | rien | Équipe : `<n>` membres, avec chef (ou sans chef) |
| Personnel, titre | rien | PERSONNEL |
| Personnel, phrase | rien | Ton équipe, usine par usine. |
| Personnel, quand personne n'est embauché | rien | Personne dans ton équipe pour l'instant. |
| Personnel, tuile de l'agence | rien | Agence, avec la ligne Les candidats du jour |
| Personnel, équipe des membres sans usine | rien | Sans usine |
| Personnel, rôles du contrat | rien | Salarié, Chef, Directeur |
| Personnel, raretés du contrat (les noms viennent de `definitions/personnel.json` ; celui de la troisième est à confirmer) | rien | Ordinaire, Rare, Exceptionnel |
| Personnel, ligne d'un membre ou d'un candidat dans une liste | rien | `<rareté>` · `<rôle>` · +`<bonus>` % |
| Personnel, en tête d'une fiche | rien | `<rareté>` · `<rôle>` |
| Équipe, titre | rien | ÉQUIPE |
| Membre, titre | rien | MEMBRE |
| Membre et candidat, bonus | rien | Bonus +`<bonus>` % de production |
| Membre, salaire par heure | rien | Salaire `<montant>` Donar/h |
| Membre et candidat, apparence | rien | Apparence `<apparence du service>` |
| Membre, usine | rien | Usine `<nom>` |
| Membre, sans usine | rien | Pas d'usine pour l'instant |
| Membre, boutons | rien | Affecter à une usine, Renvoyer, Retour |
| Affectation, titre | rien | AFFECTATION |
| Affectation, phrase | rien | Choisis son usine. |
| Affectation, quand il n'y a pas d'autre usine | rien | Aucune autre usine. |
| Affectation, bouton | rien | Retour |
| Renvoi, titre | rien | RENVOI |
| Renvoi, phrase | rien | Cette personne quitte ton entreprise. |
| Renvoi, boutons | rien | Renvoyer, Garder |
| Agence, titre | rien | AGENCE |
| Agence, phrase | rien | Choisis qui rejoint ton équipe. |
| Agence, sans candidat | rien | Aucun candidat aujourd'hui. |
| Agence, candidat déjà embauché | rien | Déjà embauché |
| Agence, bouton | rien | Retour |
| Candidat, titre | rien | CANDIDAT |
| Candidat, prix d'embauche | rien | Embauche `<montant>` Donar |
| Candidat, salaire | rien | Salaire `<pourcent>` % du revenu de l'usine |
| Candidat, boutons | rien | Embaucher, Retour |
| Embauche, titre | rien | EMBAUCHE |
| Embauche, phrase | rien | Choisis son usine, ou aucune. |
| Embauche, tuile sans usine | rien | Sans usine, avec la ligne Rejoint l'équipe sans poste |
| Embauche, bouton | rien | Retour |
| Bulle joie, après une embauche | rien | Nouvelle recrue embauchée ! |
| Bulle joie, après une affectation | rien | Affectation faite. |
| Bulle oups, après un renvoi | rien | Membre renvoyé. |
| Refus `EMPLACEMENT_OCCUPE` d'une embauche ou d'une affectation (remplace Cet emplacement est occupé.) | Cet emplacement est occupé. | Plus de poste libre ici. |
| Refus `DEJA_POSSEDE` d'une embauche, bulle fière (remplace Tu l'as déjà.) | Tu l'as déjà. | Déjà embauché. |

Les incidents et leur réparation (`INCIDENT_TEXTES`, `REPARATION_TEXTES` et quatre bulles). Le service ne rend que
l'identifiant de la sorte d'incident ; les noms et les phrases sont dans l'add-on, un jeu par sorte de
`definitions/incidents.json`. Le mini-jeu a trois étapes par sorte, avec trois boutons chacune dont un seul est juste.
Le nombre d'étapes, leur ordre (le geste de sécurité, l'outil ou la pièce, le geste final) et la place de la bonne
réponse sont des choix de l'add-on, pas des chiffres du jeu : à valider par Kr, comme les phrases. Une mauvaise réponse
rejoue l'étape avec un indice d'Oooscar et ne fait rien perdre. La récompense n'est jamais calculée dans l'add-on : elle
vient de l'événement `INCIDENT` du service.

| Où | Avant | Après |
|---|---|---|
| Usines et carte, nom d'une sorte d'incident (une sorte inconnue garde Incident) | rien | Début d'incendie, Machine en panne, Courroie cassée |
| Fiche, sorte d'un incident connu (remplace INCIDENT) | INCIDENT | le nom de la sorte en capitales : DÉBUT D'INCENDIE, MACHINE EN PANNE, COURROIE CASSÉE |
| Fiche, phrase d'un incendie | rien | Le feu a pris, la production ralentit. |
| Fiche, phrase d'une panne | rien | Une machine est en panne, la production ralentit. |
| Fiche, phrase d'une courroie cassée | rien | Une courroie a cassé, la production ralentit. |
| Fiche, bouton d'un incident (premier bouton) | rien | Réparer |
| Fiche, dernière ligne d'une usine sans courant (elle n'a aucun bouton) | rien | Il faut une Centrale en marche. |
| Carte, usine en incident | rien | `<sorte>` · niv. `<niveau>` |
| Carte, usine sans courant | rien | Sans courant · niv. `<niveau>` |
| Tchat, un incident s'ouvre | rien | `<usine>` : `<sorte>`, puis Répare-la depuis le menu, dans Usines. |
| Tchat, un incident est réparé | rien | `<usine>` : réparation faite |
| Tchat, la récompense dite par le service | rien | Récompense `<montant>` Donar |
| Tchat, récompense nulle, c'est le joueur qui a réparé | rien | Pas de récompense cette fois. |
| Tchat, récompense nulle, un associé avait déjà réparé | rien | Pas de récompense, un associé avait déjà réparé. |
| Bulle panique, un incident s'ouvre | rien | Vite, une usine a un souci. |
| Bulle joie, après la réparation, écran du joueur | rien | L'usine repart ! |
| Bulle joie, tchat, réparé par le joueur | rien | Bien joué, l'usine repart ! |
| Bulle joie, tchat, réparé par un associé | rien | Un associé a tout réparé. |
| Réparation, titre | rien | RÉPARATION |
| Réparation, ligne d'étape | rien | Étape `<n>` sur `<total>` |
| Réparation, bouton | rien | Retour |
| Réparation (incendie), étape 1, consigne | rien | Le feu a pris. Choisis le premier geste. |
| Réparation (incendie), étape 1, boutons (le bon est « Couper l'arrivée ») | rien | Jeter un seau d'eau, Couper l'arrivée, Ouvrir les fenêtres |
| Bulle oups, mauvais choix (incendie, étape 1) | rien | Coupe d'abord ce qui l'alimente. |
| Réparation (incendie), étape 2, consigne | rien | Le feu s'étend. Prends le bon outil. |
| Réparation (incendie), étape 2, boutons (le bon est « L'extincteur ») | rien | Un seau d'eau, Une pelle, L'extincteur |
| Bulle oups, mauvais choix (incendie, étape 2) | rien | Un extincteur éteint plus vite. |
| Réparation (incendie), étape 3, consigne | rien | Vise bien avant d'appuyer. |
| Réparation (incendie), étape 3, boutons (le bon est « La base des flammes ») | rien | La base des flammes, Le haut des flammes, La fumée |
| Bulle oups, mauvais choix (incendie, étape 3) | rien | Vise plus bas, à la base. |
| Réparation (panne), étape 1, consigne | rien | La machine s'est arrêtée. Choisis le premier geste. |
| Réparation (panne), étape 1, boutons (le bon est « Couper le courant ») | rien | La relancer d'un coup, Couper le courant, Taper dessus |
| Bulle oups, mauvais choix (panne, étape 1) | rien | Coupe le courant avant d'y toucher. |
| Réparation (panne), étape 2, consigne | rien | Le capot est ouvert. Repère la cause. |
| Réparation (panne), étape 2, boutons (le bon est « Un engrenage grippé ») | rien | Un engrenage grippé, Une vitre propre, Un câble tout neuf |
| Bulle oups, mauvais choix (panne, étape 2) | rien | Cherche ce qui est bloqué. |
| Réparation (panne), étape 3, consigne | rien | Remets la machine en route. |
| Réparation (panne), étape 3, boutons (le bon est « Graisser, puis la relancer ») | rien | La relancer sans rien faire, La débrancher pour de bon, Graisser, puis la relancer |
| Bulle oups, mauvais choix (panne, étape 3) | rien | Graisse avant de relancer. |
| Réparation (courroie), étape 1, consigne | rien | La courroie a lâché. Choisis le premier geste. |
| Réparation (courroie), étape 1, boutons (le bon est « Arrêter la machine ») | rien | Arrêter la machine, Tirer sur la courroie, Accélérer la machine |
| Bulle oups, mauvais choix (courroie, étape 1) | rien | Arrête d'abord la machine. |
| Réparation (courroie), étape 2, consigne | rien | Prends la bonne pièce. |
| Réparation (courroie), étape 2, boutons (le bon est « Une courroie neuve ») | rien | Une corde à linge, Une ficelle, Une courroie neuve |
| Bulle oups, mauvais choix (courroie, étape 2) | rien | Il faut une courroie neuve. |
| Réparation (courroie), étape 3, consigne | rien | Pose la courroie, puis règle-la. |
| Réparation (courroie), étape 3, boutons (le bon est « Tendue sans forcer ») | rien | Serrée à fond, Tendue sans forcer, Très lâche |
| Bulle oups, mauvais choix (courroie, étape 3) | rien | Ni trop lâche, ni trop serrée. |
| Réparation (générique), étape 1, consigne | rien | Une usine a un souci. Choisis le premier geste. |
| Réparation (générique), étape 1, boutons (le bon est « Couper l'alimentation ») | rien | Courir dans tous les sens, Couper l'alimentation, Attendre que ça passe |
| Bulle oups, mauvais choix (générique, étape 1) | rien | Mets l'usine en sécurité d'abord. |
| Réparation (générique), étape 2, consigne | rien | L'usine ralentit. Cherche la cause. |
| Réparation (générique), étape 2, boutons (le bon est « Inspecter la machine ») | rien | Inspecter la machine, Repeindre le mur, Compter le stock |
| Bulle oups, mauvais choix (générique, étape 2) | rien | Cherche d'abord ce qui cloche. |
| Réparation (générique), étape 3, consigne | rien | Remets l'usine en route. |
| Réparation (générique), étape 3, boutons (le bon est « Réparer, puis relancer ») | rien | Tout débrancher, Relancer sans réparer, Réparer, puis relancer |
| Bulle oups, mauvais choix (générique, étape 3) | rien | Répare avant de relancer. |

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

L'entreprise partagée (phase 4). Les phrases sont dans `ASSOCIES_TEXTES` et dans la tuile `MENU_TEXTES.associes`, toutes
à valider par Kr. L'avant est « rien ».

| Où | Avant | Après |
|---|---|---|
| Menu, tuile | rien | Associés |
| Menu, ligne d'info de la tuile, joueur seul | rien | Invite des joueurs |
| Menu, ligne d'info de la tuile, avec des associés | rien | `<n>` associés |
| Associés, titre | rien | ASSOCIÉS |
| Associés, rôle du PDG | rien | PDG |
| Associés, rôle d'un associé | rien | Associé |
| Associés, ligne d'un membre | rien | `<rôle>` · `<nom>` · `<part>` % |
| Associés, ligne du joueur lui-même | rien | `<ligne>` (toi) |
| Associés, joueur seul (sans maximum : l'add-on n'en connaît pas) | rien | Tu es seul dans l'entreprise. Invite des joueurs, jusqu'à ce que le service refuse. |
| Associés, bouton du PDG | rien | Inviter |
| Associés, bouton du PDG | rien | Parts |
| Associés, bouton | rien | Retour |
| Invitation reçue, entrée de la liste et ligne du tchat | rien | `<nom>` t'invite dans `<entreprise>` |
| Invitation, titre | rien | INVITATION |
| Invitation, phrase | rien | Tu quittes ton entreprise actuelle. Le PDG fixera ta part. |
| Invitation, bouton | rien | Accepter |
| Invitation, bouton | rien | Plus tard |
| Inviter, titre | rien | INVITER |
| Inviter, phrase | rien | Choisis un joueur connecté. |
| Inviter, aucun autre joueur | rien | Aucun autre joueur connecté. |
| Parts, titre | rien | PARTS |
| Parts, un champ par membre | rien | Part de `<nom>`, en % |
| Parts, indice du champ | rien | Un nombre entier |
| Parts, bouton d'envoi | rien | Enregistrer |
| Bulle joie, invitation envoyée | rien | Invitation envoyée. |
| Bulle joie, parts enregistrées | rien | Parts enregistrées. |
| Bulle joie, après avoir accepté | rien | Bienvenue dans l'entreprise. |
| Bulle joie, invitation reçue (tchat) | rien | Tu as une invitation. |
| Bulle joie, annonce du service après un changement de parts (tchat) | rien | Les parts ont changé. |
| Bulle oups, un champ n'est pas un pourcentage entier (rien n'est envoyé) | rien | Écris un pourcentage entier. |
| Bulle oups, le service refuse les parts (400) | rien | Les parts doivent faire 100. |
| Bulle oups, le service ne trouve plus l'invitation (400) | rien | Cette invitation a expiré. |
| Bulle oups, le service ne connaît pas le joueur invité (400) | rien | Je ne connais pas ce joueur. |
| Refus `DEJA_POSSEDE` d'une invitation (remplace Tu l'as déjà.) | Tu l'as déjà. | Il est déjà associé. |
| Refus `DEJA_POSSEDE` en acceptant (remplace Tu l'as déjà.) | Tu l'as déjà. | Tu es déjà associé ici. |
| Refus `ENTREPRISE_EN_COURS` d'une invitation (remplace Tu as déjà lancé ton entreprise.) | Tu as déjà lancé ton entreprise. | Il a déjà lancé son entreprise. |
| Refus `ENTREPRISE_PLEINE` en acceptant (remplace Ton entreprise est pleine.) | Ton entreprise est pleine. | Cette entreprise est pleine. |

La ville et l'île principale (phase 4). Les phrases sont dans `VILLE_TEXTES`, `BATIMENT_NOMS` et `MENU_TEXTES`. Les noms des
bâtiments viennent de `definitions/ville.json` (clé « id » du catalogue), recopiés dans `BATIMENT_NOMS` et comparés au fichier par
un test. Un type que l'add-on ne connaît pas encore prend son identifiant en capitale initiale. Les noms, les niveaux et les
bonus de l'enseigne posée sur chaque bâtiment de l'île principale reprennent ces mêmes phrases. Tout est à valider par Kr.

| Où | Avant | Après |
|---|---|---|
| Noms des bâtiments (menus, fiches, enseignes) | rien | Mairie, Route, Maison, Immeuble, Commerce, Garage |
| Menu, tuile Ville, ligne d'info quand le service ne donne pas de ville | rien | Il faut `<n>` usines actives. |
| Menu, tuile Ville, même ligne au singulier | rien | Il faut 1 usine active. |
| Menu, tuile Ville, ligne d'info avant la première zone | rien | Première zone : `<n>` usines actives |
| Menu, tuile Ville, même ligne au singulier | rien | Première zone : 1 usine active |
| Menu, tuile Ville, ligne d'info quand l'entreprise a la mairie | rien | Mairie niveau `<niveau>` |
| Carte, île principale ouverte, ligne d'info | rien | Mairie niveau `<niveau>` |
| Ville, titre | rien | VILLE |
| Ville, avant la première zone | rien | Tu n'as pas encore de zone. |
| Ville, section de la zone à acheter | rien | Prochaine zone |
| Ville, numéro de la zone | rien | Zone `<n>` |
| Ville, prix de la zone | rien | `<prix>` Lingots |
| Ville, mairie demandée par la zone | rien | Mairie niveau `<niveau>` requise |
| Ville, usines actives demandées par la zone | rien | `<n>` usines actives requises |
| Ville, même ligne au singulier | rien | 1 usine active requise |
| Ville, bouton d'achat | rien | Acheter la zone `<n>` |
| Ville, quand toutes les zones sont à l'entreprise | rien | Toutes les zones sont à toi. |
| Ville, section des bonus d'ensemble | rien | Bonus |
| Ville, aucun bonus | rien | Aucun bonus pour l'instant. |
| Ville, personnel logé | rien | Personnel logé `<logés>` sur `<places>` |
| Bonus d'un bâtiment, logements | rien | Logements `<n>` |
| Bonus d'un bâtiment, production de Donar | rien | Donar +`<n>` % |
| Bonus d'un bâtiment, vitesse de livraison | rien | Livraison +`<n>` % |
| Ville, bouton de la liste | rien | Bâtiments |
| Bâtiments, nombre | rien | `<n>` bâtiments |
| Bâtiments, nombre au singulier | rien | 1 bâtiment |
| Bâtiments, aucun | rien | Aucun bâtiment pour l'instant. |
| Bâtiments, ligne d'un bâtiment | rien | `<nom>` niveau `<niveau>` |
| Fiche d'un bâtiment, niveau | rien | Niveau `<niveau>` sur `<maximum>` |
| Fiche d'un bâtiment, prix du niveau suivant | rien | Niveau suivant : `<prix>` Lingots |
| Fiche d'un bâtiment, dernier niveau | rien | Niveau maximum. |
| Fiche d'un bâtiment, bouton | rien | Améliorer |
| Enseigne posée sur le bâtiment, deuxième ligne | rien | Niveau `<niveau>` |
| Construire, titre | rien | CONSTRUIRE |
| Ville, bouton | rien | Construire |
| Construire, phrase | rien | Choisis un bâtiment à poser. |
| Construire, détail d'un type | rien | `<prix>` Lingots · `<bonus>` |
| Construire, détail d'un type sans bonus | rien | `<prix>` Lingots |
| Poser, titre | rien | POSER |
| Poser, parcelle sous les pieds du joueur | rien | Parcelle `<x>`, `<z>` |
| Poser, prix du bâtiment neuf | rien | Prix `<prix>` Lingots |
| Poser, le joueur n'est pas sur l'île principale | rien | Place-toi sur l'île principale. |
| Poser, la position n'est pas lisible | rien | Je ne sais pas où tu te trouves. |
| Poser, côté de la porte (rotation 0, 90, 180, 270) | rien | Porte au sud, Porte à l'ouest, Porte au nord, Porte à l'est |
| Poser, sens de la route (0 et 180, 90 et 270) | rien | Sens nord-sud, Sens est-ouest |
| Poser, bouton | rien | Poser ici |
| Poser, bouton | rien | Tourner |
| Bulle joie, zone achetée | rien | Nouvelle zone achetée ! |
| Bulle joie, bâtiment posé | rien | Bâtiment posé. |
| Bulle joie, bâtiment amélioré | rien | Bâtiment amélioré ! |
| Bulle joie, annonce du service après un achat, une pose ou une amélioration (tchat) | rien | les trois bulles ci-dessus |
| Bulle oups, « Poser ici » hors de l'île principale (rien n'est envoyé) | rien | Va sur l'île principale. |
| Bulle oups, la position du joueur n'est pas lisible (rien n'est envoyé) | rien | Je ne te trouve pas. |
| Bulle oups, première zone sans assez d'usines actives (`ZONE_VERROUILLEE`, remplace Cette zone est verrouillée.) | Cette zone est verrouillée. | Il te faut plus d'usines actives. |
| Bulle oups, autre zone ou amélioration, mairie trop basse (`ZONE_VERROUILLEE`, remplace Cette zone est verrouillée.) | Cette zone est verrouillée. | Monte d'abord la mairie. |
| Bulle oups, pose dans une zone fermée (`ZONE_VERROUILLEE`, remplace Cette zone est verrouillée.) | Cette zone est verrouillée. | Zone fermée ou mairie trop basse. |
| Bulle oups, parcelle prise (`EMPLACEMENT_OCCUPE`, remplace Cet emplacement est occupé.) | Cet emplacement est occupé. | Un bâtiment est déjà là. |
| Bulle oups, quota du type atteint (`DEJA_POSSEDE` en posant, remplace Tu l'as déjà.) | Tu l'as déjà. | Maximum atteint pour ce bâtiment. |
| Bulle fier, zone déjà possédée (`DEJA_POSSEDE` en achetant, remplace Tu l'as déjà.) | Tu l'as déjà. | Cette zone est déjà à toi. |
| Bulle fier, bâtiment au dernier niveau (`DEJA_POSSEDE` en améliorant, remplace Tu l'as déjà.) | Tu l'as déjà. | Déjà au niveau maximum. |

Les autres refus (`PAS_ASSEZ_DE_LINGOTS`, `PAS_LE_PDG`, `CONFLIT_REESSAYER`, service injoignable) gardent les bulles déjà dans
les tableaux plus haut.

La boutique, le pass, les votes et les annonces (phase 5). Les phrases sont dans `BOUTIQUE_TEXTES`, `PASS_TEXTES`, `VOTE_TEXTES`,
`ANNONCES_TEXTES` et dans la tuile `MENU_TEXTES.boutique`, toutes à valider par Kr. Les noms des grades, du Pilier et des
cosmétiques viennent de `definitions/boutique.json` (clé « nom »), ceux des sites de vote de `definitions/vote.json` : l'add-on
les lit, il ne les recopie pas. « Boost collectif » et « Pass de saison » n'ont pas de nom dans les définitions : ils sont dans
`BOUTIQUE_TEXTES.rayons`. Les chiffres (prix, bonus, durée, points, Lingots, Gemmes) sont ceux du service ou des définitions.
L'avant est « rien », sauf pour le nom de la rareté du personnel et la place de la tuile Associés.

| Où | Avant | Après |
|---|---|---|
| Menu, tuile | rien | Boutique |
| Menu, tuile Boutique, ligne d'info | rien | Gemmes `<gemmes>` |
| Menu, sous le nom de l'entreprise, quand `join` donne le titre Pionnier | rien | Titre : `<nom du titre>` |
| Boutique, titre | rien | BOUTIQUE |
| Boutique, solde (en tête de la boutique et de chaque fiche) | rien | Gemmes : `<gemmes>` |
| Boutique, portes | rien | Grades, Cosmétiques, Boost collectif, Pass de saison |
| Boutique, ligne d'info de la porte Grades | rien | Prochain : `<grade>` |
| Boutique, même ligne quand tous les grades sont pris | rien | Tous possédés |
| Boutique, ligne d'info de la porte Pass de saison | rien | Rien à réclamer, 1 récompense à réclamer, `<n>` récompenses à réclamer |
| Boutique, rappel du vote du jour | rien | Vote du jour : `<sites>` |
| Boutique et fiche, quand il manque des Gemmes | rien | Les Gemmes s'achètent sur la boutique du site. |
| Cosmétiques, rayons (la catégorie de `definitions/boutique.json`) | rien | Skins d'usine, Skins de véhicule, Compagnons, Décors, Effets de prestige, Titres |
| Cosmétiques, ligne d'info d'un rayon | rien | 1 article, puis `<n>` articles |
| Rayon, titre | rien | RAYON |
| Rayon, aucun article | rien | Rien en vente ici. |
| Article, tuile : possédé, abonnement en cours | rien | Possédé, En cours |
| Article, tuile : cadenas et raison | rien | Grade précédent requis, Pas en vente |
| Article, tuile : prix | rien | `<gemmes>` Gemmes |
| Article, titre | rien | ARTICLE |
| Article, prix | rien | Prix : `<gemmes>` Gemmes |
| Article, un grade | rien | Grade : +`<bonus>` % de Donar. |
| Article, un grade, l'ordre | rien | Les grades se prennent dans l'ordre. |
| Article, un abonnement | rien | Dure `<jours>` jours. Chaque achat les prolonge. |
| Article, un abonnement, le bonus | rien | +`<bonus>` % de Donar pendant l'abonnement. |
| Article, un abonnement, les Gemmes rendues | rien | `<gemmes>` Gemmes rendues à chaque achat. |
| Article, un cosmétique | rien | Cosmétique : `<catégorie>`. |
| Article, le boost collectif | rien | +`<pourcent>` % de production pour tout le serveur, pendant `<durée>`. |
| Article, le boost collectif, le pseudo | rien | Ton pseudo est annoncé à tous. |
| Article, le boost collectif, la suite | rien | Les boosts se suivent, sans s'empiler. |
| Durée d'un boost, en mots | rien | une heure, `<n>` heures, 1 minute, `<n>` minutes |
| Article, déjà possédé | rien | Tu l'as. |
| Article, abonnement en cours | rien | Ton abonnement court. |
| Article, grade pas encore disponible | rien | Il faut d'abord le grade précédent. |
| Article, boutons | rien | Acheter, Prolonger, Offrir, Retour |
| Offrir, titre | rien | OFFRIR |
| Offrir, phrase | rien | Choisis qui reçoit `<article>` (`<prix>` Gemmes). |
| Offrir, aucun autre joueur | rien | Aucun autre joueur connecté. |
| Bulle joie, achat réussi | rien | Achat effectué ! |
| Bulle joie, abonnement prolongé | rien | Abonnement prolongé ! |
| Bulle fier, boost collectif lancé | rien | Boost lancé pour tout le serveur. |
| Bulle joie, cadeau envoyé | rien | Cadeau envoyé ! |
| Bulle oups, cadeau d'un article que le destinataire a déjà (`DEJA_POSSEDE` en offrant, remplace Tu l'as déjà.) | Tu l'as déjà. | Il l'a déjà. |
| Bulle oups, cadeau d'un grade qu'il ne peut pas prendre (`GRADE_PRECEDENT_REQUIS` en offrant, remplace Il faut d'abord le grade précédent.) | Il faut d'abord le grade précédent. | Il lui faut le grade précédent. |
| Bulle oups, le service refuse l'article (400) | rien | Cet article n'est plus en vente. |
| Pass, titre | rien | PASS |
| Pass, dates de la saison | rien | Du `<début>` au `<fin>` |
| Pass, points du joueur | rien | Points : `<points>` |
| Pass, piste premium | rien | Piste premium active, Piste premium non activée |
| Pass, bouton | rien | Paliers |
| Pass, bouton, avec son prix | rien | Passer premium |
| Pass, retour | rien | Retour |
| Paliers, titre | rien | PALIERS |
| Paliers, tuile d'un palier | rien | Palier `<n>` |
| Paliers, ligne d'info d'un palier : l'état | rien | À réclamer, Réclamé, Demande le premium |
| Paliers, ligne d'info d'un palier pas atteint | rien | Dès `<points>` points |
| Palier, titre | rien | PALIER |
| Palier, seuil | rien | `<points>` points pour l'atteindre |
| Palier, les deux pistes | rien | Gratuit, Premium |
| Palier, une ligne par piste | rien | `<piste>` : `<récompense>` |
| Palier, récompense en Lingots | rien | 1 Lingot, `<n>` Lingots |
| Palier, état d'une récompense | rien | Réclamé, À réclamer, Pas atteint, Demande le premium, Rien sur cette piste |
| Palier, boutons | rien | Réclamer le gratuit, Réclamer le premium |
| Palier, ce que le service a versé | rien | Reçu : `<récompense>` |
| Bulle joie, palier réclamé | rien | Palier réclamé ! |
| Bulle joie, pass premium acheté | rien | Pass premium activé ! |
| Vote, bulle joie à l'arrivée (tchat), pour qui n'a pas voté | rien | Tu n'as pas encore voté aujourd'hui. |
| Vote, ligne qui suit la bulle (tchat) | rien | Vote sur `<sites>` pour gagner des Lingots. |
| Vote, bulle joie, vote reçu (tchat) | rien | Merci pour ton vote ! |
| Vote, Lingots versés (tchat) | rien | +1 Lingot, +`<n>` Lingots |
| Vote, série (tchat) | rien | Série : 1 jour, Série : `<n>` jours |
| Vote, bonus de série (tchat) | rien | Dont bonus de série : `<n>` Lingots |
| Vote party, bulle fier (tchat, à tous les joueurs présents) | rien | La Vote party a lieu ! |
| Vote party, ligne qui suit la bulle (tchat) | rien | Chaque joueur présent gagne `<n>` Lingots. |
| Gemmes reçues, bulle joie (tchat) | rien | Tes Gemmes sont arrivées ! |
| Gemmes reçues, ligne qui suit la bulle (tchat) | rien | Gemmes : +`<gemmes>`, solde `<solde>` |
| Boost collectif, ligne de tchat pour tout le serveur, avec le pseudo | rien | `<pseudo>` offre un boost de +`<pourcent>` % au serveur pendant `<durée>`. |
| Boost collectif, même ligne sans le pseudo | rien | Un boost de +`<pourcent>` % court sur le serveur pendant `<durée>`. |
| Pionnier, bulle fier de bienvenue (tchat, une fois) | rien | Bienvenue parmi les pionniers. |
| Usine sans courant, bulle oups (tchat) | rien | Il faut une Centrale en marche. |
| Usine sans courant, ligne qui suit la bulle (tchat) | rien | `<usine>` est arrêtée faute de courant. |
| Personnel, tuile Associés (elle était dans le menu principal, qui n'a que huit places) | Associés, dans le menu | Associés, dans l'écran PERSONNEL |
| Personnel, raretés du contrat (le nom vient de `definitions/personnel.json`) | Commun, Rare, Exceptionnel | Ordinaire, Rare, Exceptionnel |

## Textes à valider, salle d'attente

Le pack de la salle d'attente (`src/attente/`, `docs/deploiement.md`, « La salle d'attente »), v2 du 7 novembre. Tout est
nouveau : l'avant est « rien ». Les textes sont dans `src/attente/textes.ts` ; ceux du podium du lundi sont dans
`core/.../discord/LobbyPodium.kt` (`LobbyPodiumTexts`).

| Où | Après |
| --- | --- |
| Titre et sous-titre à l'arrivée | Silooo ; Ouverture bientôt |
| Tchat, à l'arrivée | Bienvenue `<pseudo>`. Silooo ouvre bientôt. Saute de bloc en bloc : chaque bloc atteint vaut un point, une chute et tu repars de zéro. Le tchat suit le règlement du Discord. |
| Tchat, à l'arrivée | Relie ton compte Discord (discord.gg/ySnDWk4Dus) pour devenir Pionnier et être prévenu de la bêta. La boussole ouvre le menu. |
| Accueil, bonjour | Bonjour `<pseudo>`. ; à un joueur qui revient : Content de te revoir, `<pseudo>`. |
| Accueil, présentation | Silooo est un jeu d'usines dans le ciel, encore en chantier. En attendant l'ouverture, bats ton record de saut. |
| Accueil, boutons | Devenir Pionnier / Ton code pour Discord ; Tu es Pionnier / Tu seras prévenu de la bêta ; Sauter / Reprendre le parcours ; Rejoindre Silooo / Le monde de test (testeurs) |
| Accueil, sections | Dernières infos ; Rien de neuf pour l'instant. ; Le classement · cette semaine ; Ton rang : `<n>` ; Personne n'a encore sauté cette semaine. ; Déjà `<n>` pionniers. ; Le classement revient dans un instant. |
| Hologrammes | SILOOO / Ouverture bientôt ; silooo.com / discord.gg/ySnDWk4Dus ; Dernières infos ; La semaine ; Personne encore |
| Barre d'action | Score `<n>` · Record `<n>` |
| Sous-titres | Chute à `<n>` ; Les sauts s'allongent (palier) |
| Tchat, record battu | Nouveau record : `<n>` blocs. ; Ton record compte sur Discord : relie ton compte pour voir ton pseudo au podium du lundi. |
| Tchat, rappel tous les 25 points | `<n>` blocs. Relie ton compte avec /lier sur Discord. |
| Classement (barre latérale) | Meilleurs sauts |
| Nom de la boussole | Menu |
| Liaison vue en jeu | Bienvenue, Pionnier ; Ton compte Discord est relié ; nom au-dessus du joueur : Pionnier · `<pseudo>` |
| Tchat, transfert raté | Le monde de test ne répond pas. Réessaie dans un moment. |
| Nom du serveur (compose) | Silooo · ouverture bientôt |
| Pack de ressources | Silooo, salle d'attente ; Les hologrammes de la salle d'attente de Silooo. |
| Écran Pionnier | inchangé depuis la v1 : 1. Rejoins le Discord de Silooo ; 2. Obtiens ton code ici. ; 3. Sur Discord, tape /lier puis ton code. ; Ton code ; Il reste valable 10 minutes. ; Obtenir mon code, Nouveau code, Réessayer, Fermer |
| Discord, podium du lundi | Le saut infini de la semaine, dans la salle d'attente de Silooo : ; 1. `<pseudo>`, `<n>` blocs ; un joueur non relié ; Pour voir ton pseudo ici, relie ton compte avec /lier. |

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
- Pas de route pour racheter une ruine : une usine en ruine se voit, mais l'écran n'a rien à proposer.

À la tranche du monde :

- Le service doit remplir `island` (l'origine de la case d'archipel de l'entreprise). Sans lui, rien n'est posé et le
  journal le dit une fois : les joueurs restent où Bedrock les met.
- Le coin de la case et le centre de l'île principale à +300 +300 sont une hypothèse de cette tranche.
- L'île principale se pose à la tranche de la ville (plus bas), autour du centre à +300 +300 que les mini-îles entourent.
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
- La ligne « Île principale » de la carte s'ouvre avec la ville (tranche de la ville, plus bas) : elle reste fermée tant que
  l'entreprise n'a ni zone ni mairie.
- Oooscar ne suit pas le joueur sur l'île choisie : il reste là où l'arrivée l'a posé.
- Pas de texte pour les époques au-delà de la quatrième : un numéro inconnu se montre sans nom.

À la tranche du personnel :

- Le contrat ne donne pas le nombre de postes d'une usine ni par rôle : l'écran ne peut pas dire qu'une usine est pleine
  avant d'essayer. Le service refuse avec `EMPLACEMENT_OCCUPE`, l'écran le dit en bulle.
- `assignStaff` exige une usine : un membre déjà affecté ne peut pas redevenir « sans usine ».
- Pas de route pour promouvoir un chef en directeur, ni pour mettre une nouvelle liste de candidats : aucun bouton non
  plus. Le service ne rend pas encore le rôle `DIRECTEUR`, l'add-on le lit quand même.
- Pas de nom d'apparence : l'écran montre l'identifiant du service (`ouvrier-casque`), qui n'est pas un texte écrit.
- Le contrat ne donne pas le rôle du joueur : « Renvoyer » est proposé à tout membre, et `PAS_LE_PDG` revient en bulle.
- Rien ne donne le nom d'un membre qui n'est plus dans la liste (renvoyé par un autre associé) : l'écran qui le
  montrait revient simplement à son parent.

À la tranche des incidents :

- La réponse de `resolveIncident` ne dit pas si l'incident vient d'être clos ni quelle récompense a été versée : seul
  l'événement `INCIDENT` la donne. Le contrat dit que `payload.reward` vaut 0 quand l'incident était déjà clos, mais
  le service ne publie alors aucun événement : l'add-on ne peut pas le dire à ce joueur, il montre « L'usine
  repart ! » avec l'état rendu.
- L'événement `INCIDENT` ne rend pas l'état de l'entreprise : l'add-on relit `getCompany` à chaque événement reçu
  (une lecture de plus par incident et par membre en ligne) pour que le bouton « Réparer » apparaisse à temps.
- Le payload de l'événement `INCIDENT` n'est décrit que par du texte (`GameEvent`), sans schéma : l'add-on lit chaque
  clé avec prudence et ne montre pas de somme illisible.
- Un `incidentId` inconnu, d'une autre entreprise ou d'un acteur hors entreprise revient en `400` : l'add-on le range
  parmi les erreurs du service, sans texte à lui.
- `payload.slowdown` (le facteur du ralentissement) n'est pas montré : la fiche dit « la production ralentit » sans
  chiffre, parce que le décider et le chiffrer revient au service.

À la tranche de l'entreprise partagée :

- Pas de route pour quitter une entreprise, ni pour retirer un associé, ni pour lister les invitations en attente : une
  invitation n'arrive que par l'événement `INVITATION`. Une invitation reçue pendant que le service était arrêté, ou avant
  le dernier redémarrage de l'add-on, n'est vue que si elle est encore dans la mémoire de l'add-on ou si l'événement est relu
  (le curseur survit au redémarrage : seulement s'il est perdu).
- Les `ANNONCE` d'un `setShares` ou d'un `acceptInvite` ne vont qu'à celui qui a agi : les autres membres ne sont pas
  prévenus du changement de parts. Ils voient la liste à jour à la prochaine ouverture de l'écran ASSOCIÉS.
- L'identifiant (XUID) d'un joueur est encore provisoire (`identity.ts`, un hachage du nom) : l'invitation ne vise le bon
  joueur que tant que le service l'accepte comme tel.
- « Parts » n'est pas proposé à un PDG seul : il n'y a rien à répartir. C'est un choix de l'écran, le service accepterait.
- Accepter une invitation laisse l'ancienne entreprise sans membre, sans moyen d'y revenir : le texte de la fenêtre le dit
  (« Tu quittes ton entreprise actuelle. »), mais rien ne le retient.
- La tuile du menu ne montre pas le nombre d'invitations en attente.

À la tranche de la ville :

- Les dimensions et les matériaux des préfabriqués (`GABARITS` dans `monde/ville.ts`) sont écrits dans le code : le fichier
  `definitions/ville.json` ne les donne pas. Ils devraient y vivre, avec le reste des chiffres du jeu. Aucun n'est un prix, un
  bonus ou un gain, mais un changement de taille demande aujourd'hui de toucher au code.
- Pas de démolition, ni de route pour déplacer ou retirer un bâtiment : si le service en retire un de la liste, le monde garde
  le bâtiment posé, et rien ne le dit au joueur.
- `CompanyState.city` ne donne pas le prix d'un bâtiment neuf, ni le bonus du niveau suivant : l'add-on lit le prix du
  niveau 1 dans `definitions/ville.json` et ne montre pas le bonus du niveau suivant. Les noms des bâtiments sont recopiés
  dans `BATIMENT_NOMS` (un test les compare au fichier).
- Le payload de l'événement `ANNONCE` d'un achat de zone, d'une pose ou d'une amélioration n'a pas de schéma : `action` n'est
  décrit que par du texte. L'add-on lit cette seule clé et relit l'état de l'entreprise, sans se fier à d'autres champs.
- Aucune route ne dit où le joueur se tient : l'add-on lit sa position dans le monde (`locate`) et arrondit à la parcelle de
  16 blocs. Une pose se joue donc là où il se tient, pas sur une parcelle choisie sur un plan.
- La liste des bâtiments n'a ni tri ni filtre : elle suit l'ordre du service, la mairie en tête.
- Aucun essai en jeu n'a été possible : tout est prouvé sur de faux mondes (voir la section sur ce qui ne se vérifie que sur un
  vrai BDS).

À la tranche de la boutique, du pass et des votes (phase 5) :

- Aucune route ne rend les soldes (`Balances`) : seuls les corps des réponses d'écriture en portent, et `GEMMES_RECUES` pour les
  Gemmes. Le solde de Lingots du HUD ne suit donc ni un vote, ni la Vote party, ni une mission avant la prochaine réponse qui en
  porte (un encaissement, un achat, un palier réclamé), et un joueur à qui l'on offre le Pilier ne voit pas les Gemmes rendues
  avant la sienne.
- Le payload de `GEMMES_RECUES` (`orderId`, `gemmes`, `gemmesTotal`) n'est décrit que dans `core/README.md`, pas dans l'OpenAPI.
  Celui de `BOOST_COLLECTIF` (`percent`, `minutes`, `startsAt`, `endsAt`, `buyerName`, sans identifiant du joueur) et ceux des
  `ANNONCE` `vote`, `voteParty`, `pionnier` et `sansCourant` n'ont pas de schéma non plus : l'add-on lit chaque clé avec
  prudence et ne dit pas une somme illisible.
- `join` ne rend les cosmétiques du joueur que comme une liste d'identifiants (`cosmetics`), et aucune route ne permet d'équiper
  un titre : le menu montre « Pionnier » quand il est dans la liste, rien d'autre. L'objet « Pionnier » n'existe pas côté
  service ; l'add-on ne le donne pas et n'en invente pas.
- Les cosmétiques qui ne se vendent pas (`pionnier`, ceux du pass) n'ont pas de prix : le catalogue du service ne les rend pas,
  l'add-on les nomme d'après `definitions/boutique.json`.
- Le préfixe perso (l'abonnement `perso`) n'est pas montré : il demande un texte choisi par le joueur, et l'add-on n'a pas de
  mécanisme de préfixe de tchat. Le service le vend, l'écran ne le propose pas.
- Ni le boost collectif ni le pass de saison n'ont de nom dans `definitions/` : « Boost collectif » et « Pass de saison » sont
  écrits dans `BOUTIQUE_TEXTES.rayons`.
- La boutique du site, où l'on achète les Gemmes, n'a pas d'adresse dans le contrat ni dans `definitions/` : l'écran dit « sur la
  boutique du site » sans lien.
- Le curseur des événements est gardé dans le monde ; s'il est perdu (monde neuf, propriété illisible), les livraisons de
  Gemmes, les votes et le titre de pionnier, qui n'ont pas de date, se redisent. Un solde de Gemmes rejoué cède la place au
  suivant, qui arrive dans la même relecture.

Au design system :

- Pas d'icône pour Objectifs, Amis, Silo, Personnel, Agence, Associés, Ville, Boutique (`[B]`), Pass (`[Y]`), palier réclamé (`[F]`) et à réclamer (`[K]`), article possédé (`[H]`) et cadeau (`[Q]`) (Objectifs est actif mais garde son repère `[O]`, Personnel `[M]`, Agence `[R]`, Associés `[N]`, Ville `[V]`), ni de dossier `textures/ui/silooo/icones/` dans le pack de ressources.
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

- Personnel, choix faits sans Kr, à relire : le personnel est la sixième tuile du menu, avant Associés et Ville ; l'agence est une
  tuile de l'écran PERSONNEL, pas du menu ; l'écran PERSONNEL liste les équipes qui ont du monde, pas toutes les usines ;
  la couleur d'un nom suit la rareté (gris, bleu, jaune) ; la troisième rareté s'écrit « Exceptionnel » ; le salaire
  s'écrit par heure comme le service le donne, alors que le revenu du HUD est par seconde (« Donar/h » contre « /s ») ;
  la ligne d'équipe de la fiche d'usine s'ajoute aux trois infos de `design/composants/Fiche.md` (« trois infos au
  plus ») ; l'apparence s'affiche en toutes lettres, brute ; embauché, on revient à l'agence ; renvoyé, à PERSONNEL.
- Pas de nouvelle liste de candidats contre du Donar : le game design la prévoit, mais c'est du hasard payant. Si Kr la
  veut sans tirage payant (une attente, ou un prix fixe sans hasard), le contrat n'a pas encore la route.

- Incidents, choix faits sans Kr, à relire : le mini-jeu a trois étapes par sorte, dans l'ordre sécurité, outil ou
  pièce, geste final, avec trois boutons dont un seul est juste ; la place de la bonne réponse varie d'une étape à
  l'autre ; une mauvaise réponse rejoue l'étape avec un indice d'Oooscar, jamais une perte ; « Retour » est le dernier
  bouton ; le message du tchat remplace une bulle à l'écran (le pack de ressources n'en a pas) ; une sorte inconnue
  prend le mini-jeu générique ; la phrase de la fiche pour une sorte inconnue passe de « bloque » à « ralentit »,
  parce que le service ralentit sans arrêter. Les noms d'usines changent de genre, d'où « `<usine>` : `<sorte>` » sans
  article ni accord.

- Ville, choix faits sans Kr, à relire : la tuile « Ville » remplace la tuile verrouillée « Amis », huitième et dernière du
  menu (la tuile Amis n'avait ni écran ni route) ; elle est ouverte dès que l'état porte une `city` et verrouillée sinon,
  avec les usines actives de la première zone lues dans `definitions/ville.json` ; un `nextZone` absent est lu comme nul
  (toutes les zones achetées), alors qu'une `city` mal formée fait échouer la lecture de l'état entier ; le bouton
  « Acheter la zone » est toujours montré tant qu'il reste une zone, le service jugeant les conditions ; une entreprise sans
  zone ne peut pas se rendre sur l'île principale (la tuile de la carte reste fermée) ; la liste des bâtiments est
  paginée comme celle des usines ; après une pose réussie on revient à l'écran VILLE ; l'écran VILLE relit l'entreprise à
  chaque ouverture ; l'`ANNONCE` de la ville fait dire une bulle dans le tchat en plus de celle de l'écran (un doublon
  possible pour qui vient d'agir) ; les préfabriqués vivent dans le code, sans démolition (rien n'est retiré du monde) ;
  les marques de la ville (zones et bâtiments) sont dans une seconde propriété dynamique du monde, à côté de celle des
  îles, pour que les deux relevés ne s'écrasent pas.

- Boutique, pass et votes, choix faits sans Kr, à relire : la tuile Boutique est la septième du menu et prend la place d'Associés,
  qui passe sous Personnel (le menu n'a que huit places, et Ville reste la huitième) ; le pass est une entrée de la boutique, pas
  une tuile ; le Pilier et le boost collectif sont des portes de la boutique, sans rayon ; les cosmétiques sont rangés en six
  rayons, un par catégorie de `definitions/boutique.json` ; le rappel du vote du jour est une ligne de l'écran de la boutique et
  une bulle dans le tchat à l'arrivée ; offrir passe par les joueurs connectés, comme inviter, et le boost collectif ne s'offre
  pas ; le prix de « Passer premium » est celui du catalogue du service, pas celui de `definitions/pass.json` ; la piste gratuite s'appelle « Gratuit » et l'autre « Premium » ; la rareté la plus commune s'écrit « Ordinaire », le nom
  de `definitions/personnel.json` ; la ligne de tchat du boost collectif est la même pour tous, sans nom d'acheteur quand
  l'événement n'en donne pas ; les repères `[B]`, `[Y]`, `[F]`, `[K]`, `[H]` et `[Q]` sont provisoires.


## Ce qui ne se vérifie que sur un vrai BDS

- Le canal de titre : papillotement entre deux envois, durée d'affichage, effet du masquage par le pack.
- Les espaces insécables : U+00A0 peut s'afficher comme un carré selon la police.
- Les codes de couleur de matériaux `§p`, `§u` et `§n`, propres à Bedrock et récents.
- Le refus `UserBusy` d'un formulaire et les nouveaux essais.
- L'événement `itemUse` sur la boussole, et son `lockMode` (`inventory`, pas de lâcher, pas de déplacement).
- Le don de l'objet au tout premier tick : le délai de `config.ui.menuItemDelaySeconds` est une hypothèse.
- Un formulaire envoyé à un joueur qui vient de partir.
- Les signes `§` dans les boutons des formulaires.
- Le curseur des événements : la propriété dynamique `silooo:events-cursor` du monde se lit à la première lecture (le script se
  charge avant que le monde soit lisible), s'écrit à chaque page qui avance, et un redémarrage de BDS ne rejoue aucune annonce.
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
- Le personnel : `http.request` avec les méthodes PATCH et DELETE (`HttpRequestMethod.Patch`, `HttpRequestMethod.Delete`) :
  le DELETE n'a pas de corps, `requestId` et `actor` sont dans l'URL ; la longueur des formulaires de membre et de
  candidat (sept à neuf lignes, retours à la ligne), les tuiles de membre à deux lignes, les repères `[M]` et `[R]`, les
  codes de couleur `§7`, `§9` et `§e` du nom selon la rareté, et la relecture de l'agence à chaque ouverture.
- Les incidents : l'événement `INCIDENT` reçu pour un joueur qui vient de se connecter (publié à ce moment-là), le
  formulaire du mini-jeu (cinq lignes, retour à la ligne de la consigne), le code de couleur de la
  sorte, et `player.sendMessage` à trois lignes.
- Les missions : `player.sendMessage` avec les codes `§` pour la bulle de fin, la tuile Objectifs à deux lignes, la
  lecture des missions à l'arrivée pendant la cinématique, et une `ANNONCE` reçue pour un joueur qui vient de se
  connecter (publiée à ce moment-là).
- La ville : la position du joueur (`locate`) et sa conversion en parcelle ; les marques de la ville en deux propriétés
  dynamiques du monde (celle des îles et celle de la ville) ; le panneau mural de l'enseigne (`facing_direction`, texte
  à trois lignes, orientation selon la rotation) ; le chargement des bandes d'une plateforme de 128 blocs et la pose à
  plusieurs centaines de blocs du joueur (ticking area, reprises avec attente doublée) ; l'arrivée à deux blocs devant la
  porte de la mairie, le regard sur elle ; le rendu des préfabriqués (toits, étages, lanternes, porte à deux blocs).

- La boutique et le pass : la longueur des formulaires (huit tuiles à deux lignes, la fiche d'un article, la fiche d'un palier
  de sept lignes), les repères `[B]`, `[Y]`, `[F]`, `[K]`, `[H]` et `[Q]`, une bulle de `GEMMES_RECUES` reçue par un joueur qui
  vient de se connecter (publiée à ce moment-là), la ligne de tchat du boost collectif envoyée à tous, et le
  rappel du vote à l'arrivée pendant la cinématique.

## Points ouverts

- L'API de script ne donne pas le XUID d'un joueur. `src/identity.ts` envoie à sa place une empreinte FNV-1a
  64 bits du gamertag (minuscules, sans espaces autour), en décimal : elle passe le schéma `Xuid` du contrat et la
  colonne `BIGINT UNSIGNED` du service, et survit à un monde recréé (décision 25 de `docs/passation.md`, à valider
  par Kr). Quand une source de vrai XUID existera, c'est le seul fichier à changer.
- Ce qui ne se vérifie que sur un vrai BDS : le comportement de `http.request` sur un statut 4xx ou 5xx et sur un
  délai dépassé, l'ordre de démarrage du script, le format de `allowed_uris` dans `permissions.json`.
- Un `join` qui échoue n'est pas rejoué, et un script rechargé ne refait pas le `join` des joueurs déjà en ligne.
