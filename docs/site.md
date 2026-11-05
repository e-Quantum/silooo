# silooo.com : le site

Ce fichier dit ce qu'il faut construire pour le site, dans quel ordre, et à quoi on reconnaît que c'est fini.
Il est tiré du cadrage validé par Kr le 5 novembre 2026. Sources :

- Cadrage (décisions de Kr) : https://claude.ai/artifact/YcKNUkvduWwP1QUqqxxFHo
- Maquettes du site (section « Site silooo.com ») : https://claude.ai/artifact/7uBWLYneMcH3uwBMWNwXbz
- Audit des sites de Rinaorc (le modèle et ce qui doit nous distinguer) : https://claude.ai/artifact/56T2DWZCcM5ijM28WgvLbg
- Plan de construction du site (deux directions, étapes S0 à S5) : https://claude.ai/artifact/Ectn9eoy3zApd2sNDaCEQ2

Sans compte claude.ai, lis les copies de `docs/references/site/` : les maquettes du site en HTML et le prototype
d'Oooscar. Le pilotage est dans `docs/site-direction.md`, l'état et les cases dans `docs/site-passation.md`.

En cas de doute entre ce fichier et le cadrage, le cadrage gagne ; note l'écart dans `docs/passation.md`.


**Deuxième passage, 5 novembre 2026.** Ce fichier est le kit de Kr, remis sur `main` par la deuxième direction du
site après la relecture de ce que la première direction avait livré sans l'avoir lu. Ce qui est construit, et ce
qui s'en écarte, est dans [site-revision.md](site-revision.md) ; la section « Où en est `main` », à la fin de ce
fichier, dit où vivent les choses aujourd'hui (les ressources sont sous `core/src/main/resources/site/public/`, là
où le kit écrit `site/static/`). Le kit d'origine, intact, est dans `docs/references/site-kit/`. Deux choix du kit
sont posés à Kr (questions T1 et T3 de la révision) : les gabarits Pebble avec `composants.css`, et la portée
Discord de la case « Rejoindre aussi le Discord ».
## Ce que fait le site

Quatre choses : donner envie de jouer, expliquer comment rejoindre (sur console d'abord), vendre les Gemmes,
et offrir à chaque joueur un espace où suivre et gérer son entreprise. Avant l'ouverture du jeu, le site est
une page d'attente qui réunit des inscrits.

Hors périmètre, décidé par Kr : aucune carte de l'archipel au lancement (à revoir après l'ouverture), et un
site en français seulement, comme le jeu.

## Les règles du site

Elles s'ajoutent à celles de `CLAUDE.md`.

1. **silooo-core sert le site.** Pages HTML rendues côté serveur par Ktor, dans `core/`. Pas de framework
   JavaScript, pas de conteneur ni de base en plus. Le JavaScript sert à copier l'adresse du serveur,
   rafraîchir le bandeau direct, suivre la liaison d'un compte et animer Oooscar (section « Animations »).
2. **Le site lit, le service décide.** Une page appelle les mêmes services Kotlin que l'API du jeu. Aucun
   solde, prix ou classement n'est calculé dans un gabarit.
3. **Le site ne vend que des Gemmes.** Grades, Pilier, cosmétiques et boosts s'achètent en jeu avec ces
   Gemmes. Aucun autre produit payant sur le site.
4. **Le Guide est généré.** Chaque chiffre, liste et phrase d'explication du Guide vient de `definitions/`.
   Personne n'écrit une page du Guide à la main.
5. **Téléphone d'abord.** On dessine et on teste à 360 px de large, puis à 1280. Tout se fait d'un pouce,
   aucune cible tactile sous 48 × 48 px.
6. **Les textes suivent `design/README.md`** (section « Écrire sans slop ») : tutoiement, phrases courtes,
   un fait ou un chiffre par phrase, aucun mot-réclame, aucun emoji. Tout texte nouveau vu par les joueurs
   est noté « à valider par Kr » dans `docs/passation.md`.
7. **Aucun cookie de suivi**, aucun script tiers, polices hébergées par silooo-core. La mesure d'audience
   tient dans des compteurs côté serveur.
8. **Secrets** : identifiants Discord, Microsoft et PayNow vivent dans le `.env` de `/var/www/silooo`, posé par
   Kr. Le code les lit par variable d'environnement ; personne ne lit ni n'affiche le fichier.

## Les routes

| Route | Page | Accès | Sort avec |
| --- | --- | --- | --- |
| `/` | Accueil : page d'attente, puis vitrine | public | page d'attente |
| `/rejoindre` | Rejoindre | public | bêta |
| `/boutique` | Boutique de Gemmes | public | ouverture (staff seulement pendant la bêta) |
| `/classements` | Classements, en Panthéon | public | bêta |
| `/vote` | Vote | public | bêta |
| `/guide`, `/guide/<sujet>` | Guide | public | bêta |
| `/actus`, `/actus/<slug>` | Actus | public | page d'attente |
| `/mentions-legales`, `/cgv`, `/confidentialite`, `/parents` | Légal | public | page d'attente |
| `/connexion` | Choix Xbox ou Discord | public | page d'attente |
| `/compte` | Mon entreprise | joueur connecté | bêta |
| `/compte/lier` | Lier mon compte (Discord seulement) | joueur connecté | bêta |
| `/compte/entreprise` | Gérer l'entreprise | joueur connecté | bêta |
| `/compte/gemmes` | Mes Gemmes | joueur connecté | bêta |
| `/compte/recompenses` | Mes récompenses | joueur connecté | bêta |
| `/admin` | Tableau de bord | rôle staff | page d'attente |
| `/admin/actus` | Écrire les actus | rôle staff | page d'attente |
| `/admin/inscriptions` | Liste d'attente, invitations, gamertags admis | rôle staff | page d'attente |
| `/admin/ventes` | Ventes et livraisons | rôle staff | bêta |
| `/admin/joueurs` | Fiche d'un joueur | rôle staff | bêta |

Routes techniques, sans page :

| Route | Rôle |
| --- | --- |
| `GET /site/direct.json` | Bandeau direct : joueurs en ligne, état, version de Bedrock, Vote party, dernier prestige. Cache 30 s |
| `GET /site/classements.json` | Les salles du Panthéon. Recalculées toutes les 5 min dans des tables prêtes à lire |
| `GET /connexion/xbox`, `/connexion/xbox/retour` | OAuth Microsoft, puis jetons Xbox Live |
| `GET /connexion/discord`, `/connexion/discord/retour` | OAuth2 Discord, portée `identify` seulement |
| `POST /boutique/commande` | Vérifie le gamertag, crée la commande PayNow, renvoie vers le paiement |
| `POST /paynow/webhook` | Webhook signé de PayNow : écrit dans `shop_deliveries` |
| `GET /sitemap.xml`, `/robots.txt` | Référencement |

## Les pages publiques

Chaque page a un titre et une description à elle, une image de partage (logo et Oooscar), et Oooscar en bas
avec une bulle de 6 mots au plus.

**Accueil, avant l'ouverture (page d'attente).** Le logo, une phrase sur le jeu, Oooscar. Un bouton
« Me prévenir », qui mène à `/connexion` : l'inscription se fait par Discord ou par Xbox et promet l'accès à la
bêta et le Pionnier (un titre et un objet de déco pour l'île). Aucune date d'ouverture : « Bientôt », puis la
liste des étapes du chantier déjà franchies, tirée des Actus marquées « étape ».

**Accueil, après l'ouverture (vitrine).** La bande-annonce, trois captures, ce qu'on fait dans le jeu
(usines, ville, prestige), le bouton Rejoindre. La vitrine met en avant ce que Rinaorc n'a pas : le jeu natif
sur console et téléphone sans appli à installer, les gros chiffres d'un tycoon, la ville à bâtir, l'entreprise
à plusieurs, Oooscar.

**Bandeau direct** (toutes les pages, à partir de la bêta). Joueurs en ligne, serveur ouvert ou en
maintenance, version de Bedrock, Vote party en cours, dernier prestige. Si silooo-core ne répond pas, le
bandeau disparaît ; la page reste lisible.

**Rejoindre.** Trois onglets : Console, Mobile, PC. Sur console, la méthode principale passe par l'ami Xbox
HubyOnMC, pas à pas ; les autres méthodes sont en secours, repliées. Sur mobile et PC : adresse
`play.silooo.com`, port `19142`, chacun avec un bouton Copier. La version de Bedrock acceptée. Un lien vers
l'aide sur le Discord Silooo.

**Boutique.** Les packs de Gemmes de `definitions/boutique.json` (mêmes prix que les cristaux de SileneMC).
Un clic sur un pack ouvre la fenêtre d'achat, décrite plus bas. Sous les packs, une phrase : les grades et le
Pilier s'achètent en jeu, avec des Gemmes.

**Classements, en Panthéon.** Des salles, sur le modèle de French SMP : les plus grandes entreprises, les plus
beaux prestiges, les villes, les votants du mois, « le serveur en chiffres » avec une comparaison parlante, et
une case « Ton nom ici ? ». L'heure du dernier calcul est affichée.

**Vote.** Les sites de vote, la jauge commune de la Vote party, les récompenses d'un vote, la série de jours.

**Guide.** Un wiki par sujet (usines, époques, Silo et Donar, prestige, ville, entreprise), des guides par
étape qui suivent l'ordre des déblocages (bien débuter, faire grandir sa production, premier prestige, fin de
jeu), et une FAQ. Tout est généré : voir « Le Guide généré ».

**Actus.** Liste des articles, puis un article. Plan fixe, comme chez Rinaorc : sommaire chiffré, puis
Nouveau, Changements, Corrections. Un article peut être marqué « étape » pour la page d'attente.

**Légal.** Mentions légales (éditeur : la micro-entreprise de Kr ; hébergeur : OVH), conditions de vente des
Gemmes, confidentialité, note aux parents. Les textes sont des brouillons à faire relire par Kr avant la mise
en ligne.

**Pied de page.** Discord Silooo, pages légales, et une ligne discrète vers SileneMC et Hubyy.

## L'identité des joueurs

Le jeu ne connaît pas le XUID : l'add-on envoie dans le champ `xuid` une empreinte FNV-1a 64 bits du gamertag
en minuscules (décision 25 de `docs/passation.md`, `addon/src/identity.ts`). Le site fait exactement pareil :

- Une fonction Kotlin, `site/Empreinte.kt`, calcule la même empreinte. Son test reprend des vecteurs calculés
  avec `provisionalXuid` de l'add-on (au moins un gamertag avec espace, majuscules et caractère accentué).
- La boutique, la liaison Discord et la connexion Xbox retrouvent un joueur dans `players` par cette empreinte.
- La connexion Xbox donne aussi le vrai XUID : `web_accounts` le garde à part (`xbox_xuid`), sans s'en servir comme
  clé. Le jour où le jeu aura une vraie source de XUID, cette colonne servira à migrer.
- Un gamertag changé est un joueur neuf, côté jeu comme côté site, tant que la décision 25 tient.

## Les votes

Kr a choisi le 5 novembre 2026 : Hubyy d'abord, au format Votifier. La réception des votes est du jeu (phase 5) :
silooo-core écoute Votifier (version 2, avec jeton), écrit `server_votes`, tient la Vote party et les séries. Le site
lit ces données pour `/vote`, la jauge et le bandeau direct. Tant que la phase 5 n'y est pas, le site travaille sur
des votes simulés derrière la même interface Kotlin. Les sites de vote affichés sont lus dans
`definitions/site-votes.json` (nom, adresse de la page de vote) ; Hubyy est le premier.

## Les comptes

Acheter ne demande aucun compte. Un compte ne sert qu'à l'espace joueur et à l'inscription, avec deux portes.

### Xbox

1. « Se connecter avec Xbox » : OAuth Microsoft sur l'autorité `consumers`, portée `XboxLive.signin`
   (sans `User.Read` : le mélange est refusé), puis jeton utilisateur Xbox Live, puis jeton XSTS
   (partie de confiance `http://xboxlive.com`). Le XSTS porte le gamertag et le XUID.
2. On ne garde que le XUID et le gamertag. Aucun e-mail, aucune liste d'amis.
3. Empreinte du gamertag connue dans `players` : session ouverte. Inconnu avant l'ouverture : c'est une inscription (liste
   d'attente). Inconnu après : « Connecte-toi une fois au serveur, puis reviens. »
4. Erreurs XSTS à traiter avec un texte à elles :
   - `2148916238` : compte enfant hors d'une famille Microsoft. « Un parent doit ajouter ton compte à sa
     famille Microsoft. »
   - `2148916233` : pas de profil Xbox sur ce compte Microsoft.
   - `2148916235` : Xbox Live n'existe pas dans ce pays.
5. **Risque à lever en premier** : des développeurs signalent des refus `invalid_scope` selon la
   configuration de l'application Azure. Prototyper ce flux avant tout le reste de la tâche ; s'il échoue,
   noter l'erreur exacte dans « À faire par Kr » (l'application Azure lui appartient) et continuer avec Discord.

### Discord

1. « Se connecter avec Discord » : OAuth2, portée `identify` seulement.
2. Première fois : `/compte/lier` affiche un code de 6 caractères, valable 10 minutes, sans caractère ambigu
   (ni O ni 0, ni I ni 1). Cinq essais faux par code, puis un nouveau code.
3. En jeu : Menu, Mon compte, « Relier Discord », le joueur tape le code. L'add-on appelle silooo-core,
   la page du site se met à jour seule.
4. Un gamertag n'a qu'un compte Discord, et l'inverse. Le lien se défait depuis le site ou le jeu.

### Admin

Le panneau admin s'ouvre seulement par Discord, aux comptes qui ont le rôle staff sur le Discord Silooo. Le bot
le vérifie à chaque connexion. Chaque action admin est écrite dans un journal.

### Sessions

Cookie de session `HttpOnly`, `Secure`, `SameSite=Lax`, 30 jours. Jeton anti-CSRF sur chaque formulaire.
Sessions stockées dans Redis sous `silooo:web:session:`.

## La page d'attente, la bêta et le Pionnier

- Inscription par Xbox : le XUID et le gamertag sont connus tout de suite.
- Inscription par Discord : seul l'identifiant Discord est connu ; le gamertag arrive quand le joueur relie
  son compte en jeu.
- Pendant la bêta (mode `beta`), `session/join` refuse un joueur dont l'empreinte n'est pas dans `beta_access` ; l'add-on affiche un message
  et renvoie le joueur. Le staff admet les joueurs depuis `/admin/inscriptions`.
- Le Pionnier (titre et objet de déco) est donné une seule fois, au premier `session/join` d'un inscrit, ou à
  la liaison Discord d'un inscrit Discord. Source `evenement` dans `player_cosmetics`. Son dessin reste
  « à valider par Kr ».
- Prévenir de l'ouverture : le bot écrit aux inscrits Discord. Les inscrits Xbox l'apprennent sur le site et
  sur Hubyy ; Silooo n'a aucun autre moyen de les joindre, et n'en cherche pas.

## L'achat de Gemmes

1. Le joueur choisit un pack. La fenêtre demande « Ton gamertag Xbox, tel qu'il s'affiche en jeu ». Pas de
   point devant : Silooo est un serveur Bedrock natif.
2. silooo-core cherche l'empreinte du gamertag dans `players`. Absent : « Connecte-toi une fois au serveur avant
   d'acheter. » Limite : 10 essais par adresse IP et par heure.
3. Case à cocher obligatoire : renonciation au délai de rétractation (article L221-28), puis paiement sur la
   boutique PayNow de Silooo.
4. `POST /paynow/webhook` vérifie la signature, puis écrit une ligne dans `shop_deliveries` (clé
   `order_id`, colonne `xuid` remplie avec l'empreinte). Un webhook reçu deux fois ne crée qu'une ligne.
5. Côté jeu (phase 5 de la direction du jeu), silooo-core crédite le solde une seule fois, écrit `currency_ledger` dans la même transaction et
   l'événement `GEMMES_RECUES` ; en jeu, Oooscar annonce les Gemmes.
6. Pendant la bêta, seuls les comptes staff peuvent payer, avec la boutique PayNow en mode test.

## Le Guide généré

- Un générateur, dans `core/`, lit `definitions/` et produit les pages du Guide au démarrage du service.
- Chaque usine, époque, amélioration et bâtiment porte dans `definitions/` un champ de texte français :
  `description` (une phrase, ce que fait l'élément) et, si utile, `astuce` (une phrase). Ajoute ces champs
  aux schémas JSON.
- `tools/verifier.sh` échoue si un élément n'a pas de `description`, ou si une description dépasse 160
  caractères, ou si `tools/anti_ia.py` y trouve un tic.
- Les guides par étape suivent l'ordre de déblocage de `definitions/`. La FAQ vit dans
  `definitions/site-faq.json`, vérifiée par le même schéma et le même anti-IA.

## Données à ajouter

Par une nouvelle migration (numéro suivant, annoncé dans `docs/passation.md`). Noms en anglais, comme le reste.

| Table | Contenu |
| --- | --- |
| `web_accounts` | Un compte du site : empreinte du joueur (nulle tant qu'un compte Discord n'est pas relié), vrai XUID Xbox s'il s'est connecté par Xbox (`xbox_xuid`), identifiant Discord (nul si Xbox), dates |
| `discord_links` | Codes de liaison : code, compte, expiration, essais |
| `waitlist` | Inscrits avant l'ouverture : empreinte et gamertag (Xbox) ou identifiant Discord, date, Pionnier donné |
| `beta_access` | Empreinte admise en bêta, qui l'a admise, quand |
| `news_articles` | Actus : slug, titre, corps en Markdown, marque « étape », publié ou non, dates |
| `admin_audit` | Journal des actions admin : qui, quoi, sur quoi, quand |
| `site_leaderboards` | Salles du Panthéon, recalculées toutes les 5 min |

À ajouter à l'OpenAPI, côté jeu : `POST /v1/links/discord` (le code tapé en jeu), et le refus « pas encore
admis » dans `session/join` pendant la bêta. Contrat d'abord, puis test, puis code.

## Style

Le design system Silooo Atelier, sans le changer. Trois feuilles dans `core/src/main/resources/site/static/css/` :

- `tokens.css` : copie de `design/tokens.css`.
- `composants.css` : `design/composants/bundle.css`, où chaque adresse `/_blob/<id>` est remplacée par le fichier
  de `design/textures/` qui a la même somme SHA-256 (table ci-dessous). Un contrôle de `tools/verifier_site.mjs`
  vérifie que les deux fichiers ne diffèrent que par ces adresses.
- `site.css` : les pièces du site, reprises des maquettes de `docs/references/site/maquettes/` (classes `w-*`,
  textures ×2 sous 700 px de large, ×3 au-dessus).

| Adresse dans `bundle.css` | Fichier |
| --- | --- |
| `/_blob/a4d05c169326a8b4aa0fb334c391b77e` | `design/textures/panneau-fonte.png` |
| `/_blob/94ebf96cdf9eef88349cd743b9315141` | `design/textures/panneau-laiton.png` |
| `/_blob/d7af43cc05748282c79d26d333ba06c6` | `design/textures/plaque-email-bleue.png` |
| `/_blob/dccdac589ecaceba237e63e35e10a765` | `design/textures/plaque-email-rouge.png` |
| `/_blob/c839f61c22c8c591861ea4016a3e10e4` | `design/textures/bouton-rouge.png` |
| `/_blob/840602f48323529d32204a8a0ae10bdd` | `design/textures/bouton-rouge-appuye.png` |
| `/_blob/c53886f5da15f3253b9d1be6925a5535` | `design/textures/bouton-laiton.png` |
| `/_blob/74c34630b76f29c8c49a48ae6e489fbf` | `design/textures/bouton-fonte.png` |
| `/_blob/a6b99c94530b5a3d162af3d96cdd8360` | `design/textures/bouton-fonte-appuye.png` |
| `/_blob/01d554229628700e4bdccbb93ffff2b1` | `design/textures/ticket-papier.png` |
| `/_blob/fba9cfbc87b336b0c34bd8edd9505149` | `design/textures/jauge-allumee.png` |
| `/_blob/9f115281b9b2daa72f487394640a2207` | `design/textures/jauge-eteinte.png` |
| `/_blob/f352a5dcf299bca6ab1fc72e6cae52f2` | `design/textures/puce-fonte.png` |
| `/_blob/7b948b9cc6ff6cd16ce77099f62bc677` | `design/textures/puce-laiton.png` |
| `/_blob/7a5fdd7669e8dcf5d609cc07ba1a9700` | `design/textures/piste.png` |
| `/_blob/6ac1bc4b9e5d3236ea51ebd88d85ee08` | `design/textures/plein.png` |

Gradle copie `design/` dans les ressources du site au moment de la construction (`site/design/`) : aucune texture,
icône ou image d'Oooscar n'est recopiée à la main dans `core/`. Polices Jersey 10 (titres), Pixelify Sans (texte),
Silkscreen (étiquettes), sous licence OFL, en `woff2` dans `site/static/polices/` avec leur licence ; aucun appel à
Google. Pour les textes longs (Guide, Actus, Légal), le corps passe à 18 px au moins. Le ciel en fond, des îles en
blocs, Oooscar en bas des pages.

## Gabarits et mode du site

- Gabarits Pebble (module Ktor officiel, échappement automatique) dans `core/src/main/resources/site/gabarits/` :
  `base.peb` (en-tête, bandeau direct, sol, Oooscar, pied de page) et un gabarit par page. Version relevée sur
  Maven Central, épinglée dans `libs.versions.toml`, notée dans l'ADR.
- `SILOOO_SITE_MODE` : `attente` (page d'attente, connexion, actus, légal, admin), `beta` (tout sauf la vitrine ;
  paiement réservé au staff), `ouvert` (vitrine, boutique pour tous). Lu au démarrage, pas un secret.

## Animations

Le site bouge comme le jeu : par images et par pas, sans flou ni fondu. Quatre animations portent le site ;
tout le reste ne bouge pas.

Règles communes :

- **Par pas** : `steps()` en CSS, images successives pour Oooscar, déplacements entiers. Aucune bibliothèque
  d'animation ; uniquement `transform` et `opacity` pour rester fluide sur un téléphone d'entrée de gamme.
- **Rien n'attend l'animation** : chaque texte est dans le HTML et lisible tout de suite. Une animation qui ne
  démarre pas laisse une page complète.
- **`prefers-reduced-motion`** : chaque scène s'affiche dans son état final, les nombres directement, Oooscar
  change seulement d'expression.
- **Téléphone d'abord** : tout se règle à 360 px, au pouce, puis à 1280.
- Un fichier par animation dans `core/` (ressources statiques) : `oooscar.js`, `ile.js`, `compteur.js`,
  `vote-party.js`. Chacun se branche sur des attributs `data-` du HTML rendu par Ktor.

### L'île qui se construit (vitrine)

Notre version de l'histoire au défilement du SkyBlock de Rinaorc, en blocs. Storyboard : planche « Vitrine :
l'île qui se construit » des maquettes.

- **Mise en page** : sur ordinateur, la scène est `position: sticky` à droite et six blocs de texte défilent à
  gauche ; sur téléphone, la scène tient le haut de l'écran (environ 45 % de la hauteur) et les textes passent
  dessous.
- **Pilotage** : chaque bloc de texte est une étape. Un `IntersectionObserver` donne l'étape en cours ; la
  scène passe à l'état de cette étape. Remonter défait la construction, étape par étape.
- **La scène** : des blocs 16 × 16 px du design system (herbe, terre, pierre, planches), agrandis ×3 sur
  ordinateur et ×2 sur téléphone, plus le SVG d'Oooscar. Rien n'est en 3D réelle.

| Étape | Ce qui bouge | Texte à côté |
| --- | --- | --- |
| 1 | Un seul bloc d'herbe flotte dans le ciel | Titre « Produis de jour comme de nuit » |
| 2 | Les blocs tombent par rangées, la terre puis l'herbe ; chacun se tasse en touchant les autres | « Chaque joueur reçoit une île dans le ciel. » |
| 3 | La Scierie se pose dans un petit nuage de poussière en blocs | « Pose des usines et monte leurs niveaux. » |
| 4 | Le compteur roule au-dessus de l'usine, des « +2,1 Md » montent | « Chaque palier double la production. » |
| 5 | Le Silo se monte cerclage par cerclage, le toit rouge en dernier ; Oooscar sort de derrière | Bulle « Ta Scierie t'attend ! » |
| 6 | La nuit tombe, les fenêtres s'allument, trois maisons apparaissent, Oooscar s'endort, l'usine produit encore | « Hors ligne, tes usines tournent encore à 30 % pendant 8 heures. » puis le bouton Rejoindre |

Les chiffres de l'étape 6 viennent de `definitions/economie.json` (`horsLigne`) : s'ils changent, le texte suit.

### Les compteurs qui roulent

- **Le composant** : `sa-compteur` du design system, un cylindre par chiffre. Chaque cylindre est une bande
  de chiffres 0 à 9 qui défile verticalement par crans ; les cylindres partent de droite à gauche, avec un petit
  décalage. La virgule et l'unité (k, M, Md, aa…) ont leur propre cylindre.
- **Mon entreprise** : à l'ouverture, le Donar et la production par heure roulent depuis la valeur de la visite
  précédente (gardée dans `localStorage`, rien si elle manque). Ensuite le Donar avance en direct à partir de
  `ratePerHour`, comme le compteur de l'add-on, et se recale à chaque rafraîchissement des données.
- **Panthéon** : les valeurs des salles et « le serveur en chiffres » roulent à l'ouverture. Quand le calcul des
  5 minutes change un classement, les lignes échangent leur place par pas.
- Le texte exact de chaque nombre reste dans le HTML (`aria-label`) : un lecteur d'écran lit « 84,3 milliards
  de Donar », pas des cylindres.

### La jauge de la Vote party

- **Où** : page Vote, vitrine, et en petit dans le bandeau direct.
- **Données** : `GET /site/direct.json` donne `votes`, `objectif` (50) et, pendant le boost, `boostFinit`.
  La page Vote l'interroge toutes les 10 s, le reste du site toutes les 30 s.
- **À chaque vote** : une lampe de la jauge (`sa-jauge`) s'allume et un « +1 » monte. Plusieurs votes reçus
  d'un coup s'allument un par un.
- **À 50** : la jauge clignote trois fois, des confettis en blocs (couleurs du design system) tombent pendant
  6 secondes, comme en jeu, Oooscar saute avec la bulle « Vote party ! », puis une bannière annonce
  « Production ×2 pendant 15 minutes » avec sa minuterie.
- **Pendant le boost** : la minuterie reste dans le bandeau direct. Un joueur qui arrive voit la minuterie, pas la
  fête rejouée. À la fin du boost, la jauge repart de zéro.

### Oooscar

Il se tient en bas de chaque page, dans la bande de sol, sans jamais couvrir un bouton. Le prototype jouable fait
foi : https://claude.ai/artifact/QP9SVPhG4ui1zX2wyjMj5H. Reprends-en le SVG découpé (corps, visages, bras dessinés
à plusieurs angles, toit à plusieurs inclinaisons) et le comportement, dans `oooscar.js`.

| Le joueur | Oooscar |
| --- | --- |
| Arrive sur une page | Sa bulle d'accueil (tableau suivant), puis il respire et cligne des yeux ; ses pupilles suivent le doigt ou la souris |
| Le touche | Il saute ; à chaque saut, son toit glisse un peu plus de travers, puis il le remet tout seul |
| Le touche 5 fois en 2,5 s | Le toit tombe : « Mon toit ! », puis il le remet : « Comme neuf. » |
| L'attrape et le lâche | Il panique en l'air (« Repose-moi ! »), tombe, s'écrase un peu : « Ouille. » |
| Passe la souris sur lui | Il fait signe de la main : « Salut ! » |
| Ne fait rien pendant 15 s | Il s'endort, des « z » montent ; un toucher le réveille : « Je dormais pas ! » |
| Vient la nuit (22 h à 7 h, heure du joueur) | Il dort sur toutes les pages ; touché : « Les usines tournent. Moi, je dors. » |
| Est là pendant une Vote party | Il saute, l'air fier : « Production ×2 pour tout le monde ! » |

| Page | Bulle d'arrivée |
| --- | --- |
| Accueil | « Salut ! Moi, c'est Oooscar. » |
| Rejoindre | « Je t'attends sur ton île ! » |
| Boutique | « Les Gemmes arrivent en jeu. » |
| Classements | « Moi, je vise la salle 1. » |
| Vote | « Plus que 3 votes ! » (le nombre vient de la jauge) |
| Guide | « Niveau 25 : production ×2 ! » |
| Mon entreprise | « La Ferme attend son palier. » (l'usine la plus proche de son palier) |
| Page introuvable | Visage « oups » : « Cette page s'est envolée. » |

- Le bouton qui porte Oooscar est un vrai `<button>`, utilisable au clavier ; la bulle est une zone
  `aria-live` lue par les lecteurs d'écran.
- Les textes des bulles restent sous 6 mots et passent par la validation de Kr.

## Les étapes

Le kit découpait le chantier en six étapes S0 à S5. La première direction les a livrées à sa façon ; la révision
([site-revision.md](site-revision.md), « L'ordre des corrections ») les remplace par douze branches, dans l'ordre
des trois sorties : la page d'attente (`socle`, `gabarit`, `attente`, `comptes`, `admin`), la bêta (`rejoindre`,
`boutique`, `pantheon`, `vote`, `guide`, `compte`), l'ouverture (`vitrine`). La file d'attente, avec ses cases, est
dans [site-passation.md](site-passation.md). Le mode du site, `SILOOO_SITE_MODE` (`attente`, `beta`, `ouvert`),
choisit ce qui est visible ; seul Kr le passe à `ouvert`.

## Ce qui dépend de Kr

À recopier dans « À faire par Kr » de `docs/passation.md` quand la sortie concernée approche.

- Acheter `silooo.com` ; faire pointer `silooo.com` et `play.silooo.com` vers le VPS.
- Ouvrir le port 19142 en UDP et TCP.
- Autoriser l'ajout de `silooo.com` dans le Caddy de SileneMC (fichier de SileneMC).
- Créer le serveur Discord Silooo, son application (connexion et bot) et le rôle staff.
- Créer l'application Azure pour la connexion Xbox (comptes personnels Microsoft).
- Brancher Votifier sur Hubyy vers silooo-core (jeton dans le fichier d'environnement) ; choisir plus tard d'autres sites de vote.
- Ajouter Silooo au menu de Hubyy, pour la troisième étape de `/rejoindre`.
- Ouvrir la boutique PayNow de Silooo, packs aux prix des cristaux de SileneMC.
- Poser tous les identifiants dans le `.env`.
- Valider les textes et visuels de chaque page (avant / après), le dessin du Pionnier, les textes légaux.

## Écarts avec les autres documents

- `docs/socle.md`, règle 7, et `docs/plan-construction.md`, phase 5 : les Gemmes ne viennent plus du site de
  SileneMC mais de la boutique PayNow de Silooo, par le webhook de silooo-core. La table `shop_deliveries`
  reste ; le droit `INSERT` accordé au site de SileneMC devient inutile.
- Port Bedrock : 19142, décidé par Kr le 5 novembre 2026 (l'ADR « port Bedrock » le note).
- Identité : le site suit la décision 25 (empreinte du gamertag), voir « L'identité des joueurs ».

## Où en est `main`

L'état au 5 novembre 2026, après les six étapes de la première direction (commit `69d98b1`). Le détail de chaque
écart avec ce fichier est dans [site-revision.md](site-revision.md) : quatorze lignes gardées, vingt-quatre à
corriger, treize à refaire, quatre posées à Kr.

- **Le module** : paquet `com.silooo.core.site`, `Main.kt` démarre un second serveur Ktor sur `SILOOO_SITE_PORT`
  (8081), dans la même JVM que l'API ; `/v1` n'existe pas sur ce port. En-têtes de sécurité posés par le module,
  politique de sécurité sans script (à ouvrir aux scripts du site). Deux modes, `attente` et `ouvert` (`SiteMode.kt`) ;
  `beta` manque. Détail : `core/README.md`, section « Le site ».
- **Les pages** sont écrites en Kotlin avec kotlinx.html (`site/pages/`, `Gabarit.kt` pose la page) ; les textes sont
  des contenus Markdown à en-tête (`titre`, `description`, `url`, `modes`) dans `core/src/main/resources/site/contenu/`,
  où un paragraphe `{{nom}}` est remplacé par un bloc rendu par le code (offres, tableaux du wiki, adresse du jeu).
  En `attente` : `/`, `/mentions/`, `/pionniers/` et ses sous-pages, le webhook. En `ouvert` s'ajoutent `/rejoindre/`,
  `/ce-qui-se-vend/`, `/boutique/`, `/vote/`, `/mises-a-jour/`, `/wiki/` et treize pages, `/classements/`,
  `/entreprise/{id}/`. Une page introuvable en page Silooo.
- **Le style** : `public/site.css` importe `public/atelier/tokens.css` (copie de `design/tokens.css`) et
  `public/atelier.css` (les composants `sa-*` réécrits à la main d'après `bundle.css`) ; textures, icônes, logo et
  `oooscar-joie.png` sont recopiés sous `public/atelier/`, et `SiteAtelierTest` compare chaque copie à `design/`
  octet pour octet. Polices Jersey 10, Pixelify Sans et Silkscreen en `woff2` (OFL) dans `public/polices/`. Corps de
  20 px, texte `fonte-900` sur papier dans le panneau de fonte pour les pages de lecture, mention de Mojang en
  étiquette de 16 px. Le test refuse dans le CSS ce que la charte bannit (arrondis, dégradés, flou, largeur fixe).
- **Les données** : la table `site_registrations` (migration V7 : gamertag en clé, identifiant Discord facultatif,
  `pioneer`, `admitted_at`), lue par `join` pour l'admission (`SiteAdmission`, règle `SILOOO_SITE_ADMISSION`) ; les
  accès de lecture dans `com.silooo.core.sitedata` (`SiteStatsStore` : Lingots de la semaine ISO et fiche
  d'entreprise, cache d'une minute ; `ShopDeliveryStore` : une ligne de `shop_deliveries` par commande du webhook) ;
  les joueurs en ligne lus sur les signaux de vie de `ServerPresence`.
- **Les pionniers** : formulaire sans script (gamertag, case « J'ai lu les règles », jeton signé), cookie
  `silooo_pionnier` signé d'une heure sur `/pionniers/`, limite de cinq essais par minute et par adresse, OAuth2
  Discord `identify` avec l'état dans le cookie, aucun identifiant Discord dans une page ni un journal.
- **Le webhook PayNow** : `POST /paynow/webhook`, `PayNow-Signature` en HMAC-SHA256 hexadécimal du corps, champs
  `order_id`, `gamertag`, `gemmes` ; 503 sans secret, 401, 400, 413 et 405 selon le cas ; une ligne par `order_id`.
  Le format exact est à confirmer par Kr (tout est dans `PayNowWebhook.kt`).
- **Les tests** : `core/src/test/kotlin/com/silooo/core/site/` (pages, style, contenus, wiki, pionniers, webhook,
  statistiques) et les tests SQL de `sitedata` (ignorés sans Docker, joués en CI). `SiteRenderToDisk` écrit les
  pages rendues quand `SILOOO_SITE_RENDER_DIR` est posé : c'est la base des captures de relecture.
- **Les variables** (treize, toutes facultatives, décrites dans `core/README.md`) : `SILOOO_SITE_PORT`,
  `SILOOO_SITE_MODE`, `SILOOO_SITE_URL`, `SILOOO_SITE_URL_DISCORD`, `SILOOO_SITE_ADRESSE_JEU`, `SILOOO_SITE_PORT_JEU`,
  `SILOOO_SITE_URL_BOUTIQUE` (disparaît avec la boutique PayNow), `SILOOO_SITE_SITES_DE_VOTE` (disparaît au profit de
  `definitions/site-votes.json`), `SILOOO_SITE_ADMISSION`, `SILOOO_DISCORD_CLIENT_ID`, `SILOOO_DISCORD_CLIENT_SECRET`,
  `SILOOO_SITE_TRUSTED_PROXY`, `SILOOO_PAYNOW_WEBHOOK_SECRET`.
- **Ce qui manque** par rapport à ce fichier : les comptes (Xbox, Discord avec code en jeu, sessions), l'espace joueur,
  l'admin, les actus, trois des quatre pages légales, le bandeau direct, le Panthéon, la jauge et la série du vote, le
  Guide généré, la fenêtre d'achat et la commande PayNow, les quatre animations, la vitrine, `robots.txt`,
  `sitemap.xml`, l'empreinte du gamertag, `tools/verifier_site.mjs`, la copie de `design/` par Gradle.

## La référence : skyblock.rinaorc.com

Rinaorc est un réseau français de serveurs Minecraft (Java et Bedrock), avec un site par mode de jeu
sur un sous-domaine. Son Skyblock est un cousin de Silooo : des usines qui produisent, des îles à
faire monter, des grades payants.

### Ce que les pages indexées montrent, à confirmer sur le site

| Partie | Adresse | Ce qu'on en sait |
| --- | --- | --- |
| Accueil | `https://skyblock.rinaorc.com/` (français), `/en/` (anglais) | Le pitch en une phrase : partir d'un rocher dans le ciel, bâtir des usines qui travaillent pour soi, faire monter son île au classement, affronter deux boss nommés. Les grades payants dans l'ordre : VIP, VIP+, VIP++, Ultime, puis Empyréen, un abonnement payé en Riozs, la monnaie premium. Une mention d'indépendance vis-à-vis de Mojang et Microsoft. Le launcher Rinaorc recommandé (mods Java). Bedrock par `bedrock.rinaorc.com`, port 19132. |
| Wiki | `/wiki/` et `/en/wiki/`, une page par système (`/wiki/forge/`, `/wiki/enchantements/`) | Organisé en chapitres dans l'ordre du joueur : premiers pas (îles, île de départ, tutoriel, guide en jeu), économie (RinaCoins, boutique `/shop`, vente `/sell`, valeur des blocs, hôtel des ventes `/ah`, échanges et paiements entre joueurs), production (métiers : mineur, bûcheron, fermier, chasseur, pêcheur ; Multitool ; Forge ; usines : Carrière, Scierie, Fosse à mobs, Bassin, Forge ; spawners ; Forgeron). Classements par niveau d'île, prestige, XP de métier, Noblesse et Fortune, avec des tops 10 ; « chaque lundi, les plus riches sont anoblis ». |
| Journal des mises à jour | `https://changelog.rinaorc.com/modes/skyblock/`, et `rinaorc.com/fr/mises-a-jour` | Un journal par mode, entrées datées (dernières fin octobre 2026). |
| Statistiques | `https://tracker.rinaorc.com/` | Fiches de joueurs et de clans, choix de langue. |
| Boutique | `https://store.rinaorc.com/`, catégorie des rangs | Site à part. L'extrait indexé parle de rangs temporaires de trente jours ; les prix ne sont pas dans les extraits. |
| Rejoindre | `https://rinaorc.com/en/rejoindre` | Adresse Java, adresse et port Bedrock, launcher. |
| Forum et réseaux | `rinaorc.com/threads/`, compte X, YouTube | Forum avec fil de vote et questions ; bande-annonce ; annonces de saison. |

### Ce qu'on en retient déjà pour Silooo

À reprendre, dans l'esprit :

- Un wiki organisé comme le parcours du joueur, une page par système, avec les mots du jeu. Pour
  Silooo : première heure, usines et personnel, les trois monnaies, prestige et étoiles, la ville,
  l'entreprise partagée, la boutique ; avec les mots du [glossaire](glossaire.md), sans commandes
  à taper puisque tout passe par des formulaires.
- Un journal des mises à jour daté et public, à part des pages de jeu. Pour Silooo : une page
  « Mises à jour », alimentée par les tags déployés ([deploiement.md](deploiement.md)).
- Une fiche publique par joueur ou par groupe, et des classements visibles sans se connecter. Pour
  Silooo : la fiche d'entreprise et les classements hebdomadaires.
- La mention d'indépendance vis-à-vis de Mojang en pied de page, et une page « Rejoindre » qui donne
  l'adresse et le port pour chaque plate-forme.
- La page d'accueil qui dit en une phrase ce qu'on fait dans le jeu.

À ne pas reprendre :

- L'hôtel des ventes, les échanges et les paiements entre joueurs : la monnaie échangeable est
  interdite dans Silooo ([vision.md](vision.md)). Le marché reste à trancher par Kr.
- La noblesse hebdomadaire des « plus riches » : Silooo classe la progression de la semaine, pas la
  richesse, pour qu'un joueur arrivé hier puisse gagner.
- Les rangs temporaires et la monnaie premium d'abonnement : Silooo a des grades à vie et un seul
  abonnement, le Pilier, en Gemmes ([economie.md](economie.md)).
- Le launcher et les mods : Silooo est Bedrock, sans rien à installer.
- Le ton des annonces sur les réseaux (emoji, superlatifs, points d'exclamation) : la charte les bannit.
- Le bilingue avec un préfixe `/en/` : Silooo est en français seulement.

### Grille d'audit, à remplir sur le site

| Point | Quoi regarder | Relevé |
| --- | --- | --- |
| Structure | Plan du site, menu, pied de page, profondeur des pages, sous-domaines | à relever |
| Accueil | Pitch, appel à l'action principal, adresse et port, joueurs en ligne, vidéo, captures | à relever |
| Textes | Ton, longueur des phrases, tutoiement, tics de la charte, cohérence des noms du jeu | à relever |
| Wiki | Chapitres, recherche, date de mise à jour, captures d'écran, liens entre pages | à relever |
| Classements | Ce qui est classé, fréquence, affichage, lien vers les fiches | à relever |
| Fiches | Joueur, île ou clan : champs affichés, ce qui est public | à relever |
| Boutique | Ce qui se vend, prix avant connexion, monnaie premium, hasard, abonnements, cadeaux, historique d'achats, moyens de paiement, mentions pour les mineurs | à relever |
| Vote | Sites listés, récompenses, jauge ou objectif collectif | à relever |
| Mentions | Mojang, mentions légales, conditions de vente, confidentialité, cookies, âge | à relever |
| Design | Identité, images, icônes, cohérence avec le jeu, mode sombre | à relever |
| Mobile | Lisibilité, menu, poids, boutons | à relever |
| Performance | Poids des pages, nombre de requêtes, délai de chargement, hôtes tiers | à relever |
| Accessibilité | Attribut `lang`, textes de remplacement des images, contrastes, hiérarchie des titres | à relever |
| Référencement | Titre, description, image de partage, `sitemap.xml`, `robots.txt` | à relever |
| Technique | Générateur ou CMS, framework, hébergement, outils de mesure | à relever |
| Communauté | Discord, forum, réseaux, journal des mises à jour | à relever |

### Comment faire le relevé

1. Autoriser `skyblock.rinaorc.com`, `rinaorc.com`, `store.rinaorc.com`, `changelog.rinaorc.com` et
   `tracker.rinaorc.com` dans les domaines réseau de l'environnement cloud, ou faire le relevé depuis
   le PC.
2. Un script Chromium (Playwright, déjà installé dans les sessions cloud) visite chaque adresse en
   version PC et mobile, garde le HTML, le texte, les requêtes, les métadonnées (titre, langue,
   description, titres, liens du menu et du pied de page, hôtes tiers) et une capture pleine page.
   Le script existe dans la session du 5 novembre 2026 ; sa place dans le dépôt, s'il est gardé, est
   à décider par la direction (`tools/`).
3. Remplir la grille, une ligne par point, avec la capture en appui. Les textes cités le sont tels
   quels, entre guillemets, pour que la comparaison avec la charte soit honnête.
4. Reporter dans ce fichier ce qui change la liste « À reprendre » et « À ne pas reprendre ».

