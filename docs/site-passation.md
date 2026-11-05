# Passation du site : l'état du chantier de silooo.com

Dernière mise à jour : 5 novembre 2026, 15 h 48, mise en ligne suspendue, révision en cours (direction du site, deuxième passage, en local).

Ce fichier est tenu par la session de direction du site ([site-direction.md](site-direction.md)). Il ne remplace pas
[passation.md](passation.md), qui reste à la direction du jeu : ici, seulement le site `silooo.com`, décrit dans
[site.md](site.md) et tranché par l'[ADR 0006](adr/0006-site-silooo-com.md).

## État

- **Suspendu : révision en cours** (5 novembre 2026, 15 h 48). Une deuxième direction du site, en local sur le poste de Kr,
  relit S0 à S5 contre le kit de Kr (`docs/references/site-kit/`, `docs/references/site/`), que la première direction,
  dans le cloud, n'avait pas lu. Le site ne se met pas en ligne avant la fin de cette révision (section du même nom).
- **S5 est sur `main`** : les joueurs en ligne sur l'accueil (d'après le dernier `heartbeat`, « Serveur fermé pour
  l'instant. » sans battement récent), les classements de la semaine ISO en Lingots gagnés (`/classements/`, pages de
  25, cache d'une minute), la fiche d'entreprise (`/entreprise/{id}/`), le webhook PayNow (`POST /paynow/webhook`,
  signature HMAC-SHA256 supposée, une ligne `shop_deliveries` par commande, rejeu sans doublon), et la section
  « Mettre en ligne » de `docs/site.md`. Les accès SQL sont dans `com.silooo.core.sitedata`, en lecture seule hors
  des livraisons. **S0 à S5 sont cochées sauf les cases de Kr : le site est prêt à mettre en ligne** (section plus bas).
- **S4 est sur `main`** : la migration V7 (`site_registrations`), la page `/pionniers/` dans les deux modes
  (inscription par gamertag, jeton de formulaire signé, cookie de session signé d'une heure, limite de débit,
  compteur des inscrits), « Relier Discord » par OAuth2 (`identify`) seulement si Kr pose l'application, la règle
  d'admission `SILOOO_SITE_ADMISSION` (`discord` par défaut, `tous` pour l'ouverture), les mentions qui disent le
  cookie et les données gardées ; tests Ktor avec un faux fournisseur, tests SQL Testcontainers (CI).
- **S3 est sur `main`** : le wiki, treize pages sous `/wiki/` en mode `ouvert` (index, première heure, usines, Silo et
  hors ligne, personnel, monnaies, prestige, incidents et électricité, commande du jour et Carnet, entreprise
  partagée, ville, mots du jeu, règles à écrire par Kr), les tableaux et les chiffres rendus depuis les définitions
  par des blocs `{{nom}}`, un test qui refuse tout chiffre tapé dans un contenu ; 1196 tests Gradle dont 169 ignorés.
- **S2 est sur `main`** : en mode `ouvert`, l'accueil (adresse et port sur des tickets, emplacement des joueurs en
  ligne pour S5, Discord), Rejoindre (PC, téléphone, tablette ; la console attend Kr), Ce qui se vend (packs, grades,
  abonnements rendus depuis `definitions/boutique.json`, la liste de ce qui ne se vend jamais), Boutique, Vote (sites
  de vote et adresse de la boutique lus dans l'environnement, vides tant que Kr ne les donne pas), Mises à jour (une
  entrée par fichier daté, aucune pour l'instant) ; quatre variables de plus (`SILOOO_SITE_ADRESSE_JEU`,
  `SILOOO_SITE_PORT_JEU` comparé au compose par un test, `SILOOO_SITE_URL_BOUTIQUE`, `SILOOO_SITE_SITES_DE_VOTE`) ;
  1182 tests Gradle dont 169 ignorés. Captures relues par la direction.
- **S1 est sur `main`** : Silooo Atelier pour le web (`site.css` importe `atelier/tokens.css` puis `atelier.css` ;
  textures, icônes, Oooscar et logos copiés de `design/` et comparés octet pour octet ; polices Jersey 10, Pixelify
  Sans et Silkscreen servies par le site, paquets Fontsource 5.3.0, licence OFL ; gabarit commun avec navigation par
  mode, `og:*`, lien d'évitement ; page d'attente avec Oooscar et sa bulle ; pages de lecture sur papier ; 1155 tests
  Gradle dont 169 ignorés). Captures à 360 et 1280 px relues par la direction : rien ne déborde, le style tient.
- **S0 est sur `main`** : silooo-core démarre un second serveur Ktor sur `SILOOO_SITE_PORT` (8081) qui sert la page
  d'attente et les mentions en mode `attente`, un accueil minimal en mode `ouvert`, les fichiers de
  `core/src/main/resources/site/public/`, une page 404 Silooo ; `tools/anti_ia.py` relit les contenus et les
  littéraux du paquet `site` (1142 tests Gradle, 168 ignorés sans Docker ; `tools/verifier.sh` vert).
- Kr a tranché le cadre le 5 novembre 2026 (décision 37 de [passation.md](passation.md)) :
  le site vit **dans silooo-core** (`core/src/main/kotlin/com/silooo/core/site/`, `core/src/main/resources/site/`), il
  écrit dans `shop_deliveries` les commandes du webhook PayNow, il dit quelle table rend un joueur « admis », et la
  direction du jeu déploie silooo-core, site compris, quand ce fichier dit « Prêt à mettre en ligne ». L'ADR 0006
  applique ce cadre : son propre port (`SILOOO_SITE_PORT`), deux modes (`SILOOO_SITE_MODE`, `attente` par défaut),
  rendu côté serveur en Kotlin, lecture de la base par les accès du service.
- Les fichiers de cadrage de Kr (`docs/site-direction.md`, `docs/site-passation.md`, `docs/references/site/`, dans le
  worktree `silooo-site` de son PC) **ne sont pas poussés** : la direction du jeu les voit non suivis dans son dossier
  ([passation.md](passation.md), « À faire par Kr »). La direction du site lancée dans le cloud ne les a donc pas lus ;
  elle a écrit les siens d'après [direction.md](direction.md), la décision 37 et les réponses du journal du jeu, et
  les réalignera sur ceux de Kr dès qu'ils seront sur `main`.
- Le jeu est en phase 4 : le service connaît les usines, le personnel, le prestige, les missions, les incidents, la
  commande du jour, le Carnet et l'entreprise partagée ; l'add-on de l'entreprise partagée est fusionné. Le serveur
  n'a pas encore tourné sur le VPS (base, port, DNS et zip de BDS attendent Kr). Le site se construit en mode
  `attente` : il ne promet rien qui ne soit pas joignable.
- `main` au départ de la direction du site (`21381cd`) est vert en CI ; dans la session cloud, `tools/verifier.sh`
  passe aussi (Gradle a eu besoin de cinq essais et d'un seul téléchargement à la fois, Maven Central limitant le
  débit : `GRADLE_OPTS="-Dorg.gradle.workers.max=1"`), tests SQL ignorés sans Docker, comme prévu.
- Une première version de l'ADR 0006 et des étapes (site statique à part dans un conteneur, générateur Node) a été
  écrite et poussée sur la branche de la direction avant la lecture de la décision 37, puis remplacée le même jour ;
  la session de travail S0 lancée sur cette version a été arrêtée avant toute fusion, sa branche effacée.

## Les étapes

Chaque étape est une session de travail, une à la fois, sur une branche `claude/site-sN`, relue et vérifiée
(`tools/verifier.sh`) par la direction avant la fusion en avance rapide sur `main`. Le code du site reste dans
`core/.../site/` et `core/src/main/resources/site/` ; les fichiers partagés de `core/` reçoivent des lignes, pas des
réécritures, parce que deux tranches du jeu y travaillent en même temps.

### S0 : le socle du site dans silooo-core

Critère de fin : silooo-core écoute aussi sur `SILOOO_SITE_PORT` et y sert la page d'attente et les mentions ;
`SILOOO_SITE_MODE` est lu et contrôlé ; tests Ktor sans Docker ; `tools/anti_ia.py` lit les textes du site ;
`tools/verifier.sh` vert.

- [x] `Config.kt` : `SILOOO_SITE_PORT` (8081 par défaut, distinct de `SILOOO_PORT`) et `SILOOO_SITE_MODE` (`attente`
  par défaut, `ouvert` ; toute autre valeur est une erreur de configuration listée avec les autres, code 78) ;
  `ServiceConfig` et `Dependencies` les portent (avec des valeurs par défaut pour ne pas toucher aux tests) ;
  `Main.kt` démarre un second serveur Ktor pour le site, dans la même JVM, sur `0.0.0.0:SILOOO_SITE_PORT`, à côté
  de celui de l'API ; les deux s'arrêtent ensemble
- [x] Paquet `com.silooo.core.site` : `siteModule` (le module Ktor du site, sans `/v1`, sans clé), `SiteMode`,
  `SiteRoutes`, le gabarit de page en DSL HTML (`ktor-server-html-builder`, version du catalogue), le rendu du
  Markdown des ressources (commonmark-java, version épinglée), les fichiers statiques de
  `core/src/main/resources/site/public/` servis sous `/public/` ; en-têtes `Cache-Control` raisonnables ; aucun
  JavaScript nécessaire
- [x] Ressources `core/src/main/resources/site/` : `contenu/` (Markdown avec un en-tête `titre`, `description`, `url`,
  `modes`), `public/site.css` (court : les couleurs des jetons recopiées en variables, polices de secours ; le style
  Silooo Atelier arrive en S1), le logo copié de `design/logo/` (un test compare la copie à la source octet pour octet,
  le dossier `design/` se retrouvant à côté de `definitions/`)
- [x] Les pages : en mode `attente`, `/` (le logo, le jeu en une phrase, « Le serveur ouvre bientôt. », le lien Discord
  seulement s'il est configuré) et `/mentions/` ; en mode `ouvert`, `/` est un accueil minimal que S2 remplace, et
  `/mentions/` ; tout le reste 404 avec une page Silooo ; la mention « NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED
  BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. » dans le pied de chaque page et dans les mentions ; les trous des
  mentions (éditeur, hébergeur, contact) marqués « À compléter par Kr. » ; textes **à valider par Kr**
- [x] Tests : `ConfigTest` (défauts, mode inconnu refusé, port du site distinct) ; `SiteModuleTest` par
  `testApplication` (les deux modes, `lang="fr"`, un seul `h1`, `title`, `meta description`, `alt` sur chaque image,
  `/v1/server/heartbeat` répond 404 sur le module du site, le CSS et le logo servis avec le bon type, 404 en page
  Silooo) ; le test de copie du logo
- [x] `tools/anti_ia.py` : `core/src/main/resources/site/**/*.md` (texte entier) et
  `core/src/main/kotlin/com/silooo/core/site/**/*.kt` (littéraux de chaînes, lecteur étendu à `.kt`) dans
  `TEXTES_JOUEURS` ; exception explicite et visible `MENTIONS_PERMISES` pour la seule phrase de Mojang ; cas dans
  `tools/tests/` (hook et `--tout`) ; `python3 tools/anti_ia.py --tout` vert
- [x] `core/README.md`, section « Le site » (variables, port, modes, où sont les pages, comment relire en local avec
  `SILOOO_SITE_MODE=ouvert` sur son poste) ; `docs/site.md`, « Comment le site se construit », point S0

### S1 : Silooo Atelier pour le web

Critère de fin : la page d'attente et les mentions ont le gabarit commun et le style Silooo Atelier ; lisibles à
360 px de large sans défilement horizontal ; polices servies par le site ; `tools/verifier.sh` vert.

- [x] `public/atelier.css` : les jetons copiés de `design/tokens.css` (copie contrôlée), puis les composants du web
  d'après `design/composants/bundle.css` : panneau, plaque, bouton (rouge, laiton, fonte, appuyé, focus), ticket,
  monnaie, puce, icône, bulle ; `border-image` sur les textures de `design/textures/` copiées dans
  `public/atelier/textures/` ; `image-rendering: pixelated` ; aucun arrondi, dégradé, flou ; une seule ombre ; un seul
  bouton rouge par page ; un test compare chaque copie à sa source et refuse `border-radius`, `linear-gradient`,
  `radial-gradient`, `blur(`, `backdrop-filter` dans le CSS
- [x] Les trois polices (Jersey 10, Pixelify Sans, Silkscreen) : fichiers `woff2` du sous-ensemble latin tirés des paquets
  `@fontsource/*` (licence OFL, fichier de licence commis à côté, version notée), `@font-face` avec `font-display: swap`,
  secours de `tokens.css` ; rien chargé depuis un autre hôte
- [x] Les tailles du web : corps lisible sur téléphone (20 px au moins, interligne 1,3), `affiche` pour les titres (h1
  sur une plaque, deux tailles d'affiche au plus par page), `etiquette` pour les légendes, multiples de 3 px ; fond
  des pages de lecture choisi et justifié (fonte et papier, ou papier et fonte), contrastes de la charte
- [x] Gabarit commun : en-tête (logo SVG, nom, navigation du mode : en `attente` seulement Mentions ; en `ouvert`
  Accueil, Rejoindre, Wiki, Classements, Boutique, Vote, Mises à jour, Mentions, les pages absentes ne s'affichent
  pas), pied (mention Mojang, Mentions, Discord si configuré), `og:title`, `og:description`, `og:image` absolu
  (`SILOOO_SITE_URL`, `https://silooo.com` par défaut), icône de site (logo 64), `theme-color` ; focus visible
- [x] Mobile : 360 px sans défilement horizontal, cibles de 60 px, texte à 20 px ; captures Playwright à 360 et 1280 px
  hors du dépôt quand Chromium est là (pas dans les tests)
- [x] Oooscar `joie` sur la page d'attente avec une bulle de six mots au plus (« Le serveur ouvre bientôt ! ») ; texte
  **à valider par Kr** ; `docs/site.md`, point S1 : ce que le web ajoute à la charte

### S2 : les pages publiques du mode ouvert

Critère de fin : Accueil, Rejoindre, Ce qui se vend, Boutique, Vote et Mises à jour rendues en mode `ouvert`,
absentes en `attente` (404) ; aucun chiffre tapé ; textes marqués à valider ; `tools/verifier.sh` vert.

- [x] Accueil : le jeu en une phrase, l'adresse et le port, l'emplacement des joueurs en ligne (rempli en S5, caché
  tant que la donnée manque), une vidéo et une capture à poser par Kr (emplacements marqués), le lien Discord ;
  quatre paragraphes au plus
- [x] Rejoindre : l'adresse (`SILOOO_SITE_ADRESSE_JEU`, `play.silooo.com` par défaut) et le port (`SILOOO_SITE_PORT_JEU`,
  même défaut que `BDS_PORT` du compose, un test compare la constante à `deploy/compose.yml`), la marche à suivre par
  plate-forme (PC, téléphone et tablette, console : l'ajout d'un serveur n'est pas offert par toutes les consoles,
  la méthode est **à valider par Kr**)
- [x] Ce qui se vend : la promesse « Tout ce qui s'achète se gagne aussi en jouant », les packs de Gemmes, les grades
  et leur bonus, le Pilier, lus dans `ShopDefinitions` ; la liste de ce qui n'est jamais en vente ([vision.md](vision.md)) ;
  un test compare la page aux définitions
- [x] Boutique : les grades et le Pilier (mêmes définitions), où s'achètent les Gemmes (`SILOOO_SITE_URL_BOUTIQUE`, vide
  tant que Kr ne l'a pas donnée : le lien ne s'affiche pas), les interdits, la note sur l'historique d'achats (en jeu)
- [x] Vote : les sites de vote (`SILOOO_SITE_SITES_DE_VOTE`, liste vide tant que Kr ne les a pas donnés), la récompense
  par vote seulement quand `definitions/` la chiffre (sinon la page dit qu'elle arrive), la jauge de la Vote party
  plus tard
- [x] Mises à jour : `contenu/mises-a-jour/AAAA-MM-JJ-<sujet>.md`, une entrée par tag déployé, la plus récente en tête ;
  sans entrée, la page dit que le serveur n'a pas encore ouvert ; un test refuse un nom de fichier mal formé
- [x] `docs/site.md` : le tableau des pages pointe vers les fichiers de `contenu/`

### S3 : le wiki

Critère de fin : les pages du wiki rendues en mode `ouvert`, dans l'ordre où le joueur rencontre les systèmes ;
les listes (usines, raretés, sortes d'incidents) tirées des définitions chargées ; `tools/verifier.sh` vert.

- [x] `contenu/wiki/` : la première heure ; les usines et les paliers ; le Silo et les gains hors ligne ; le personnel
  et l'agence ; les trois monnaies ; le prestige et les étoiles ; les incidents et l'électricité ; la commande du jour
  et le Carnet ; l'entreprise partagée ; la ville (en construction, dit comme tel) ; les mots du jeu (glossaire pour
  les joueurs, écrit à la main) ; les règles du serveur (page avec un trou marqué : **à écrire par Kr**)
- [x] Les tableaux générés depuis `Definitions` : usines (nom, produit, entrées, époque), raretés et postes du
  personnel, sortes d'incidents, packs ; jamais un chiffre tapé ; un test compare chaque usine aux pages
- [x] Pas de commande à taper ; phrases courtes ; mots du glossaire ; textes **à valider par Kr** ; une page d'index du
  wiki et sa place dans la navigation ; `docs/site.md` : la liste des pages du wiki

### S4 : les pionniers, Relier Discord, l'admission

Critère de fin : un joueur s'inscrit comme pionnier avec son gamertag en mode `attente` comme en `ouvert`, relie son
compte Discord quand Kr a posé l'application, et la table dit qui est admis ; la direction du jeu a de quoi refuser
les non-admis dans `join` ; migration prise à la fusion ; `tools/verifier.sh` vert. Le dessin de cette étape est
**à valider par Kr** : il reconstruit ce que la décision 37 et le journal du jeu laissent voir (« Pionnier », « Relier
Discord », « refus des non-admis »), faute des fichiers de Kr.

- [x] Migration (numéro pris à la fusion ; V6 est prise par la ville depuis le 5 novembre à 13 h, V7 attendue) : table `site_registrations` (gamertag en minuscules en clé,
  gamertag tel que saisi, identifiant Discord facultatif jamais affiché, `created_at`, `admitted_at` facultatif,
  `pioneer` booléen), index sur `admitted_at` ; `docs/base-de-donnees.md` n'est pas touché par la direction du site :
  la table est décrite dans `core/README.md` et demandée dans « Pour la direction du jeu »
- [x] Page `/pionniers/` : un formulaire (gamertag, case à cocher des règles), limite de débit simple par adresse,
  pas de courriel ; un gamertag déjà inscrit rend la même page avec son état ; le compteur des pionniers inscrits ;
  textes **à valider par Kr**
- [x] « Relier Discord » : OAuth2 de Discord (identité seule), activé seulement si `SILOOO_DISCORD_CLIENT_ID`,
  `SILOOO_DISCORD_CLIENT_SECRET` et `SILOOO_SITE_URL` sont posés, sinon la page explique que ce n'est pas encore
  ouvert ; l'identifiant Discord se range dans `site_registrations`, jamais affiché ; état et jeton CSRF en cookie de
  session signé, de courte durée, seul cookie du site (les mentions le disent) ; faux fournisseur dans les tests
- [x] L'admission : `admitted_at` posé par une règle lisible dans `core/README.md` (par exemple : inscrit et relié,
  ou admis à la main par Kr avec une commande ou une requête documentée) ; le site n'écrit rien d'autre ; la
  direction du jeu lit `site_registrations.admitted_at` par le gamertag pour refuser dans `join` (contrat d'abord)
- [x] La récompense du pionnier (titre et objet) reste au jeu : le site ne touche pas `player_cosmetics` ; la ligne
  `pioneer` suffit au jeu pour la créditer plus tard (demandé dans « Pour la direction du jeu »)

### S5 : les données vivantes et la mise en ligne

Critère de fin : joueurs en ligne sur l'accueil, Classements et fiches d'entreprise rendus en mode `ouvert` ; le
webhook PayNow écrit `shop_deliveries` ; la liste « Prêt à mettre en ligne » écrite ; `tools/verifier.sh` vert.

- [x] Joueurs en ligne : lus dans `ServerPresence` (le dernier `heartbeat`), affichés sur l'accueil ; « Serveur fermé »
  sans heartbeat récent
- [x] Classements : la semaine ISO en cours, les joueurs par Lingots gagnés dans la semaine (`currency_ledger`,
  `LINGOTS`, `delta > 0`), gamertag et nom d'entreprise, taille de page technique dans le code ; cache d'une minute ;
  tests SQL Testcontainers
- [x] Fiche d'entreprise `/entreprise/{id}/` : nom, époque, étoiles, membres (gamertag et rôle, PDG d'abord), nombre de
  prestiges, date de création ; 404 en page Silooo pour une entreprise inconnue ; jamais de XUID ni de solde
- [x] Webhook PayNow `POST /paynow/webhook` : signature contrôlée avec `SILOOO_PAYNOW_WEBHOOK_SECRET` (le format exact de
  PayNow est **à confirmer par Kr** ; en attendant, HMAC-SHA256 du corps dans un en-tête nommé, à changer en une
  constante), une ligne `shop_deliveries` par commande (`order_id`, gamertag, Gemmes), rejeu sans double ligne
  (clé primaire), 401 sans signature, 503 sans secret configuré ; tests SQL
- [x] « Prêt à mettre en ligne » : la section est écrite ici quand S0 à S5 sont sur `main`, avec la marche à suivre de
  Kr (DNS, bloc Caddy vers `silooo-core:8081`, mentions remplies, variables posées, `SILOOO_SITE_MODE` laissé à
  `attente`) ; `docs/site.md` complet

## Suspendu : révision en cours

Depuis le 5 novembre 2026, 15 h 48, cette section remplace « Prêt à mettre en ligne ». La première direction du site
(cloud) a construit S0 à S5 sans lire le kit de Kr : `docs/site.md`, `docs/site-direction.md`, `docs/site-passation.md`
et `docs/references/site/`, non suivis dans son dossier. Une deuxième direction, en local sur le poste de Kr (worktree
`silooo-site`, branche `claude/site-revision`), relit ce qui est sur `main` contre ce que Kr a décidé (kit, cadrage, plan,
maquettes) et écrit chaque écart dans `docs/site-revision.md`, avec un verdict : garder, corriger, refaire, à trancher par Kr.
Le kit est versionné tel quel dans `docs/references/site-kit/` et `docs/references/site/`.

**Le site ne se met pas en ligne avant la fin de la révision.** Rien ne change au déploiement du jeu : le site est dans
l'image de silooo-core, en mode `attente` par défaut, sur un port que le compose ne publie pas, et aucun bloc Caddy ne le
joint. Cette section redeviendra « Prêt à mettre en ligne » quand chaque ligne de `docs/site-revision.md` sera gardée
ou corrigée, sauf ce qui attend Kr.

## Pour la direction du jeu

Lu à chaque bilan de la direction du jeu (décision 37, point 3). Réponses attendues dans son journal.

**Depuis le 5 novembre 2026, 15 h 48 : ne déployez pas le site avant la fin de la révision.** La deuxième direction du site
relit S0 à S5 contre le kit de Kr (section « Suspendu : révision en cours »). Déployer silooo-core pour le jeu reste
possible : le site y reste en `attente`, sans bloc Caddy ni variable posée. Ne demandez pas à Kr le DNS, le bloc Caddy
ni les variables du site tant que cette passation ne dit pas de nouveau « Prêt à mettre en ligne ».

**Le kit de Kr est sur `main`** : `docs/references/site-kit/` (ses trois `site*.md`) et `docs/references/site/` (maquettes,
prototype d'Oooscar). Votre dossier `silooo` garde une copie non suivie de `docs/references/site/`, identique octet pour
octet : supprimez-la avant de fusionner `origin/main`, sinon git refuse d'écraser des fichiers non suivis. Le point 1
ci-dessous est donc réglé.

1. **Les fichiers de Kr.** Vous voyez `docs/site-direction.md`, `docs/site-passation.md` et `docs/references/site/`
   non suivis dans votre dossier. La direction du site du cloud ne les a pas. Si vous pouvez les lire sans les
   ajouter à votre index, copiez leur texte tel quel dans votre journal, ou dites à Kr de les pousser (branche
   `claude/site-kr`, ou le worktree `silooo-site`) : la direction du site réalignera ses étapes dessus.
2. **Fichiers partagés de `core/`.** S0 ajoute des lignes dans `Config.kt`, `Main.kt`, `Application.kt`
   (`Dependencies`), `libs.versions.toml` et `core/README.md`, et crée le paquet `site/`. Dites à vos tranches de ne
   pas réécrire ces fichiers d'un bloc, et prévenez ici si l'une d'elles y travaille.
3. **Variables d'environnement du site**, lues par silooo-core par `env_file` (rien à changer dans le compose) :
   `SILOOO_SITE_PORT` (8081), `SILOOO_SITE_MODE` (`attente`), `SILOOO_SITE_URL`, `SILOOO_SITE_URL_DISCORD`,
   `SILOOO_SITE_ADRESSE_JEU`, `SILOOO_SITE_PORT_JEU` (le défaut de `BDS_PORT`, un test les compare), `SILOOO_SITE_URL_BOUTIQUE`,
   `SILOOO_SITE_SITES_DE_VOTE` (`nom|url;nom|url`), `SILOOO_SITE_ADMISSION`, `SILOOO_DISCORD_CLIENT_ID`,
   `SILOOO_DISCORD_CLIENT_SECRET`, `SILOOO_SITE_TRUSTED_PROXY`, `SILOOO_PAYNOW_WEBHOOK_SECRET` : treize variables, toutes
   facultatives avec un défaut sûr, toutes décrites dans le tableau des variables de `core/README.md` et dans
   `docs/site.md`, « Mettre en ligne ». `deploy/exemple.env.txt` et le tableau des variables
   de `docs/deploiement.md` sont à vous : merci d'y ajouter ces lignes (S0 est sur `main` : les quatre premières
   sont lues par `Config.kt`), avec l'avertissement « jamais `ouvert` par une session », puis de retirer leurs noms
   de la liste `reglees` de `tools/tests/deploy-compose.mjs`, où S0 les a exemptés faute de pouvoir toucher `deploy/`.
4. **L'admission (S4, sur `main`).** La table est `site_registrations` (migration V7) (gamertag en minuscules en clé, `admitted_at`
   facultatif, `pioneer` booléen) : « admis » veut dire `admitted_at IS NOT NULL` pour `LOWER(players.gamertag)`.
   `join` reçoit le gamertag : pas besoin de l'empreinte. La clé se calcule par `SiteRegistrations.key(gamertag)`
   (`core/src/main/kotlin/com/silooo/core/registration/`) : espaces des bords retirés, espaces réduits à un seul,
   minuscules ; la requête est dans `core/README.md`, « Le site ». Un code `Refused` nommé (par exemple `PAS_ADMIS`)
   est à déclarer dans `api/` par vous, puis la route. Réserve connue : le site ne prouve pas qu'un visiteur possède le
   gamertag qu'il inscrit (pas de courriel, pas de connexion Xbox) ; quelqu'un peut inscrire le gamertag d'un autre et
   y relier son Discord. Si l'admission doit être sûre, il faut une preuve côté jeu (un code montré en jeu, saisi sur
   le site) : c'est un choix de Kr, noté dans « À faire par Kr ». Le titre et l'objet du pionnier restent au jeu : le site pose seulement `pioneer`.
5. **Rien à vous demander pour S0 à S3** : contenus, style et wiki ne touchent ni `api/`, ni `addon/`, ni `deploy/`.
7. **Un nom affiché pour les raretés du personnel.** `definitions/personnel.json` et le code n'ont que les
   identifiants `COMMUN`, `RARE`, `EPIQUE` ; le wiki affiche donc les identifiants en minuscules, dont le troisième
   est un mot que la charte bannit (`docs/glossaire.md` le note déjà : « le nom affiché est à confirmer par Kr »).
   Un `nom` par rareté dans `personnel.json` réglerait le wiki et l'add-on d'un coup.
9. **Ce que le site attend du jeu, après S5.** (a) La route de `join` qui refuse les non-admis (point 4), avec un code
   `Refused` nommé dans `api/`. (b) Le crédit du titre et de l'objet du pionnier d'après `site_registrations.pioneer`.
   (c) Les livraisons : le webhook écrit `shop_deliveries` (`order_id`, `gamertag`, `gemmes`), votre relevé
   (`DeliveryRelief`) les crédite ; le pseudo est écrit tel que PayNow l'envoie, à rapprocher d'un gamertag Bedrock
   (préfixe Floodgate, casse) de votre côté ou du côté de PayNow. (d) `docs/base-de-donnees.md` : la table
   `site_registrations` (V7) y manque, ce fichier est à vous.
8. **L'agence : `game-design.md` et `economie.md` se contredisent.** Le game design parle d'une nouvelle liste de
   candidats achetée en Donar ; le service (décision 33) et `economie.md` disent « rien à acheter ». Le wiki suit le
   service. À trancher par Kr, et le game design à mettre d'accord.
6. **Un nom lisible pour les abonnements dans `definitions/boutique.json`.** Les grades ont un `nom`, les
   abonnements (`pilier`, `perso`) n'ont qu'un `id` : le site écrit « Pilier » et « Préfixe perso » dans son code
   (`site/pages/Offres.kt`), ce qui contredit « une seule source par donnée ». Si la tranche de la boutique ajoute
   un `nom` à chaque abonnement (schéma compris), le site le lira à la place, et l'add-on aussi.

## À faire par Kr

- [ ] Pousser les fichiers de cadrage du worktree `silooo-site` (`docs/site-direction.md`, `docs/site-passation.md`,
  `docs/references/site/`) : la direction du site du cloud a écrit les siens sans les lire et se réalignera
- [ ] Relire l'[ADR 0006](adr/0006-site-silooo-com.md) (statut « Proposée ») : le site dans silooo-core, sur son propre
  port, deux modes, rendu Kotlin, ce que le site écrit
- [ ] Relire le dessin de S4 (pionniers, Relier Discord, admission), maintenant sur `main` : en particulier, décider si
  l'inscription doit prouver la possession du gamertag (un code montré en jeu, saisi sur le site), et la règle
  d'admission (`SILOOO_SITE_ADMISSION` : `discord` ou `tous`)
- [ ] Le DNS de `silooo.com` (et `www`) vers l'IP du VPS, quand le site sera déployé
- [ ] Le bloc `silooo.com` dans le Caddy de SileneMC (`/var/www/minecraft`, qu'aucune session Silooo ne touche) :
  mandat vers `silooo-core:8081` sur le réseau `silenemc`, TLS automatique
- [ ] L'identité légale des mentions : éditeur, hébergeur, contact, conditions de vente, protection des données
- [ ] L'adresse de la boutique de SileneMC pour les Gemmes de Silooo, et les sites de vote
- [ ] Le lien d'invitation Discord, l'application Discord (identifiant et secret) pour « Relier Discord »
- [ ] Le format exact du webhook PayNow (en-tête de signature, calcul, champs du corps) et son secret : le site suppose
  `PayNow-Signature` en HMAC-SHA256 hexadécimal du corps et les champs `order_id`, `gamertag`, `gemmes`
  (`core/.../site/PayNowWebhook.kt`, une seule classe à changer)
- [ ] Compléter la section « Données » des mentions si le pseudo d'une commande de Gemmes doit y être déclaré
- [ ] Le relevé de `skyblock.rinaorc.com` (grille de [site.md](site.md)), depuis le PC ou en autorisant le domaine
- [ ] Le passage de `SILOOO_SITE_MODE` à `ouvert` dans le fichier d'environnement du VPS, à l'ouverture (phase 7)

## Décisions prises sans Kr

1. **La mise en place écrit ce qui manquait.** Au premier tour, ni `docs/site-direction.md` ni `docs/site-passation.md`
   n'étaient sur une branche poussée ; `docs/site.md` dormait sur `claude/phase0-docs`. La direction du site a écrit
   les deux premiers d'après [direction.md](direction.md) et le prompt de Kr, repris le troisième sur `main`, et
   découpé le chantier en six étapes S0 à S5. À relire par Kr, et à remplacer par ses fichiers s'ils disent autre chose.
2. **ADR 0006 pris et annoncé ici.** Le numéro suivant de l'index était 0006 ; la direction du site n'écrit pas
   dans `passation.md`, l'index de `docs/adr/README.md` tient lieu d'annonce (décision 37, point 5 : si le jeu
   avait pris 0006 entre-temps, le site aurait pris 0007).
3. **Réalignement sur la décision 37.** La première version de l'ADR (site statique à part, conteneur, routes JSON
   publiques) a été remplacée dès la lecture de la décision 37 : le site vit dans silooo-core, sur son propre port,
   rendu en Kotlin, lecture de la base par les accès du service, deux serveurs Ktor dans la même JVM plutôt qu'un
   sélecteur de port (les tests existants n'ont pas à changer). La session S0 lancée sur l'ancien plan a été arrêtée
   avant toute fusion.
4. **Les polices commises.** Jersey 10, Pixelify Sans et Silkscreen sont sous licence OFL ; leurs fichiers `woff2`
   viennent des paquets `@fontsource/*` et sont commis dans les ressources avec la licence : silooo-core n'a pas de
   Node, et le site ne charge rien depuis un autre hôte.
5. **Le découpage S0 à S5** suit l'ordre de dépendance : socle, style, contenus, wiki, inscriptions, données vivantes
   et mise en ligne. Les pages qui dépendent d'une donnée de Kr (adresse de la boutique, sites de vote, application
   Discord, secret PayNow) s'écrivent avec une valeur vide et s'affichent sans le lien ou sans le bouton.
6. **S0, tranché par la session de travail et gardé à la fusion** : les quatre variables du site sont exemptées dans
   `tools/tests/deploy-compose.mjs` tant que la direction du jeu ne les a pas posées dans `deploy/exemple.env.txt`
   (le site ne touche pas `deploy/`) ; une adresse (`SILOOO_SITE_URL`, `SILOOO_SITE_URL_DISCORD`) qui ne commence pas
   par `http` est une erreur de configuration ; les contenus livrés dans le jar sont listés dans `SiteContent.BUNDLED`
   et un test compare la liste au dossier ; les liens Markdown passent par `sanitizeUrls` de commonmark ; les en-têtes
   de sécurité sont posés par un plugin du module. Les fichiers de la direction du site n'étaient pas encore sur
   `main` quand la session a commencé : la case `docs/site.md` a été écrite par la direction à la fusion.
7. **S1, tranché par la session et gardé à la fusion** : les pages de lecture (mentions, erreurs, wiki plus tard)
   posent le texte `fonte-900` sur un bloc de papier dans le panneau de fonte (contraste 15,8 contre 6,6) ; la page
   d'attente garde le texte papier sur fonte comme une fenêtre du jeu ; la mention de Mojang du pied est en
   `etiquette` de 16 px (la grille de Silkscreen), seule exception au corps de 20 px ; un bouton de navigation
   `sa-bouton--nav` en corps de 20 px pour que huit liens tiennent sur un téléphone ; `SiteShell` porte l'adresse du
   site, le Discord et la navigation. À la fusion, la direction a retiré du contenu d'attente le paragraphe « Le
   serveur ouvre bientôt. », que la bulle d'Oooscar disait déjà juste dessous, et a resserré le test.
8. **S2, tranché par la session et gardé à la fusion** : un contenu Markdown place un bloc rendu par le code par un
   paragraphe `{{nom}}` (un nom inconnu ou un bloc jamais appelé fait échouer le rendu) ; les offres sont des listes,
   pas des tableaux (lisibles à 360 px) ; les packs ne sont que sur « Ce qui se vend », la Boutique montre les grades et
   les abonnements ; la date d'une mise à jour doit être celle de son nom de fichier ; le texte des consoles n'est pas
   inventé : la page dit qu'il arrive ; les liens internes du Markdown perdent le `rel="nofollow"` que commonmark pose.
9. **S3, tranché par la session et gardé à la fusion** : les pages du wiki ont leur propre liste de contenus livrés
   (`WikiContent`) avec une clé `ordre` unique ; les tableaux sont de vrais `table`, en fiches sous 600 px ; le
   nombre d'époques se lit sur la plus haute époque des usines ; les suffixes des grands nombres sont écrits en mots ;
   les raretés s'affichent par leur identifiant faute de nom. À la fusion, la direction a corrigé deux phrases : le
   nom de l'entreprise n'est pas encore choisi par le joueur (« arrive plus tard »), et la remise en état n'est
   gratuite que « pour l'instant » (le coût attend Kr, `passation.md`).
10. **S4, tranché par la session et gardé à la fusion** : le cookie et le jeton de formulaire sont signés en HMAC-SHA256
    avec un secret tiré au démarrage (un redémarrage les invalide, c'est voulu), la charge porte une lettre qui dit
    son rôle ; l'état OAuth2 vit dans le cookie et sert une fois ; le compteur des inscrits est mis en cache une
    minute ; la limite de débit est de cinq essais par minute et par adresse, table bornée ; un gamertag déjà inscrit
    rend la page d'état sans cookie si un compte Discord y est déjà relié ; aucun identifiant Discord ne sort dans une
    page ni un journal, les erreurs du fournisseur ne gardent que leur sorte. La direction a relu le signataire, les
    routes, le fournisseur Discord et le SQL. Réserve de conception (voir « Pour la direction du jeu », point 4) :
    l'inscription ne prouve pas la possession du gamertag. Points que la session a elle-même relevés, à revoir avec Kr :
    `pioneer` est toujours vrai, même après l'ouverture (à lier au mode à l'ouverture) ; le jeton du formulaire se
    rejoue (sans effet : l'inscription est idempotente) ; un espace au bord du gamertag est refusé plutôt que retiré ;
    les styles du formulaire (champ, case, erreur) sont écrits dans `atelier.css` sans texture de `design/`, à
    dessiner dans la charte avant de les réutiliser ; le retour Discord n'a pas de limite de débit.
11. **S5, tranché par la session et gardé à la fusion** : la semaine du classement est la semaine ISO en UTC, lue sur
    l'horloge du service ; à égalité le gamertag départage ; un joueur sans entreprise s'affiche « Sans entreprise » ;
    la fiche garde en cache une minute même une entreprise inconnue (bornée à quelques centaines d'entrées) ; le
    webhook n'accepte que la signature hexadécimale sans préfixe, refuse un corps de plus de quatre kilo-octets, un
    pseudo de plus de 32 caractères ou un identifiant de plus de 64, et rend 405 à un `GET` ; les Lingots du tableau
    sont écrits en `fonte-900` sur le papier, l'icône seule porte l'or (lisibilité). À la fusion, la direction a mis la
    plaque du titre en taille « petite » sous 480 px (la charte le prévoit sur mobile) : « CLASSEMENTS » se coupait au
    milieu du mot à 360 px.

## À valider par Kr

Les textes du site s'ajoutent ici étape par étape, avec le fichier et la ligne. Tous ceux de S0 sont dans
`core/src/main/resources/site/contenu/` et `core/src/main/kotlin/com/silooo/core/site/pages/` :

- `contenu/attente.md` : la description « Silooo, un tycoon d'usines dans le ciel sur Minecraft Bedrock. Le serveur
  ouvre bientôt. » ; « Un tycoon d'usines dans le ciel, sur Minecraft Bedrock. » ; « Tu hérites d'une carrière
  abandonnée sur une île. Tu rachètes des usines, tu embauches, tu regardes ton archipel produire. » ; « Le serveur
  ouvre bientôt. »
- `contenu/accueil.md` (mode ouvert, provisoire jusqu'à S2) : les deux premières phrases ci-dessus et la description
  sans l'annonce.
- `contenu/mentions.md` : la description « Les mentions du site Silooo : éditeur, hébergeur, contact, marque Minecraft
  et données. » ; les sections « Éditeur », « Hébergeur », « Contact » (« À compléter par Kr. »), « Minecraft »
  (la mention de Mojang en capitales, puis « Silooo est un serveur indépendant. Minecraft est une marque de Mojang AB
  et de Microsoft. »), « Données » (« Ce site ne pose aucun cookie et n'utilise aucun outil de mesure. Il ne charge
  rien depuis un autre hôte. »).
- `pages/Gabarit.kt` : le pied (la mention de Mojang, « Mentions »), le `alt` du logo « Logo de Silooo : un silo à
  toit rouge sur un bloc d'herbe », le titre « <page> · Silooo ».
- `pages/Attente.kt` : « Rejoins le Discord » (seulement si `SILOOO_SITE_URL_DISCORD` est posée).
- `pages/Erreur.kt` : « Page introuvable », « Cette adresse ne mène à rien. », « Retour à l'accueil » ; « Erreur »,
  « Le site a eu un problème. Réessaie dans un moment. »
- S1, `pages/Attente.kt` : la bulle d'Oooscar « Le serveur ouvre bientôt ! » et son `alt` « Oooscar, la mascotte de
  Silooo, content » ; « Rejoins le Discord » devient le bouton rouge de la page.
- S1, `pages/Gabarit.kt` : « Aller au contenu » (lien d'évitement), « Discord » (pied), « Pages du site » (nom de la
  navigation pour les lecteurs d'écran).
- S1, `pages/Navigation.kt` : les mots de la navigation « Accueil », « Rejoindre », « Wiki », « Classements »,
  « Boutique », « Vote », « Mises à jour », « Mentions ».
- S2, `contenu/accueil.md` : « Tu hérites d'une carrière abandonnée sur une île. Tu rachètes des usines, tu embauches des
  salariés, puis tu fais un prestige : ta course repart de zéro et te rapporte des Lingots. » ; « Tout ce qui s'achète
  se gagne aussi en jouant. Voir ce qui se vend. » ; la description.
- S2, `contenu/rejoindre.md` : « Pour jouer, ajoute Silooo à ta liste de serveurs dans Minecraft Bedrock. », les six
  étapes (« Ouvre Minecraft Bedrock. », « Choisis Jouer, puis Serveurs. », « Choisis Ajouter un serveur. », « Écris
  Silooo comme nom. », « Recopie l'adresse et le port de cette page. », « Enregistre, puis choisis Silooo dans la
  liste. »), « Tout passe par des menus en jeu. Aucune commande à taper. », « Le chemin est le même que sur PC et
  téléphone. », et pour les consoles « Sur Xbox, PlayStation et Switch, le menu de certaines consoles n'offre pas
  d'ajouter un serveur. La marche à suivre pour les consoles arrive. » (**à écrire par Kr**).
- S2, `contenu/ce-qui-se-vend.md` : « Tout ce qui s'achète se gagne aussi en jouant. », « Les Gemmes sont la seule
  monnaie en vente. Elles se dépensent dans la boutique en jeu. Les prix sont ici, avant ta première connexion. »,
  « Les grades se prennent dans l'ordre. », « Ni contre des Gemmes, ni contre des euros : » et la liste de
  `vision.md`.
- S2, `contenu/boutique.md` : « Des choses ne s'achètent jamais. Voir la liste. », « Ton historique d'achats se lit en
  jeu, dans la boutique. » ; `pages/Boutique.kt` : « Acheter des Gemmes », « La boutique ouvre avec le serveur. »
- S2, `contenu/vote.md` : « Vote pour Silooo sur les sites ci-dessous. », « La récompense par vote arrive. », « Quand le
  serveur atteint l'objectif de votes, tout le monde reçoit la même récompense. » ; `pages/Vote.kt` : « Les sites de
  vote arrivent avec le serveur. »
- S2, `contenu/mises-a-jour.md` : « Ici, les changements du serveur, la plus récente en haut. » ; `pages/MisesAJour.kt` :
  « Le serveur n'a pas encore ouvert. Les mises à jour s'écriront ici. »
- S2, `pages/Offres.kt` : « Sans bonus », « Bonus N % », « Bonus de Donar N % », « N Gemmes rendues », « Réservé au
  grade X », « N jours », et les noms « Pilier » et « Préfixe perso » écrits dans le code (voir « Pour la direction du
  jeu », point 6) ; `pages/Joueurs.kt` : « Adresse », « Port », « N joueurs en ligne » ; `pages/Accueil.kt` :
  « Rejoindre ».
- S3, tout `core/src/main/resources/site/contenu/wiki/` (treize fichiers, en entier) et les libellés de
  `pages/WikiBlocs.kt` (légendes, en-têtes de colonnes, phrases des blocs : « L'agence propose N candidats par
  jour », « Le plafond de départ est de N heures », « Une entreprise compte N membres au plus », « Le courant est
  demandé à partir de l'époque N. Il vient de la Centrale. ») ; `regles.md` est **à écrire par Kr**.
- S4, `contenu/pionniers.md` (« Tu t'inscris avant l'ouverture. Tu entres parmi les premiers. », « Ton gamertag
  suffit : pas de courriel, pas de mot de passe. »), `contenu/mentions.md` section « Données » (le cookie de session,
  les données gardées, l'effacement par le contact), et tous les libellés de `pages/Pionniers.kt` (formulaire,
  « M'inscrire », « J'ai lu les règles », les refus du gamertag, « Tu es déjà inscrit. », « Inscrit. », « Compte Discord
  relié. », « Admis : tu peux entrer dès l'ouverture. », « Relier Discord », « Relier ton compte Discord arrive. »,
  « Trop d'essais. Réessaie dans une minute. », « Ce compte Discord est déjà relié à un autre joueur. », « N pionniers
  inscrits »), plus « Pionniers » dans la navigation. Le dessin entier de S4 (gamertag seul, admission par Discord)
  est **à valider par Kr**.
- S5, `contenu/classements.md` (« Les joueurs qui ont gagné le plus de Lingots cette semaine, avec leur entreprise. »,
  « Le classement compte les Lingots gagnés cette semaine, pas la richesse totale. »), `pages/Classements.kt`
  (« Personne n'a encore gagné de Lingots cette semaine. », « Sans entreprise », « Page précédente », « Page
  suivante », Rang, Joueur, Entreprise, Lingots, « Semaine du N au N mois »), `pages/Entreprise.kt` (« Époque N »,
  « N étoiles », « N prestiges », « Créée le … », « Membres », « PDG », « Associé », « Voir les classements », la
  description), `pages/Joueurs.kt` (« Serveur fermé pour l'instant. », « Personne en ligne pour l'instant. »,
  « N joueurs en ligne »).

## Journal

- 5 novembre 2026, 12 h 45 à 12 h 55 (direction du site, cloud). Lecture de `CLAUDE.md`, `direction.md`,
  `passation.md`, `plan-construction.md`, `site.md` (phase 0), de la charte et de `deploy/`. Vérification de
  référence sur `main` : tout vert sauf Gradle (429 de Maven Central). Écrit : `site-direction.md`, ce fichier,
  `site.md` sur `main`, ADR 0006 (première version) et son index, les lignes du site dans `docs/README.md`.
- 5 novembre 2026, 12 h 55 à 13 h 25 (direction du site, cloud). JDK 17 installé dans la session, Gradle vert au
  cinquième essai (un téléchargement à la fois). Session S0 lancée sur le plan statique, puis arrêtée à la lecture de
  la décision 37 arrivée sur `main` à 12 h 47 : le site vit dans silooo-core. ADR 0006 réécrit, `site-direction.md`,
  `site.md` et ce fichier réalignés, deux lignes dans `CLAUDE.md`. Suite : S0 sur la bonne architecture.
- 5 novembre 2026, 13 h 25 à 13 h 50 (direction du site, cloud). Mise en place poussée sur `main` (`9f64127`, vérifiée
  localement, CI verte) ; `__pycache__` ignoré et le `.pyc` commis retiré de l'index. Session S0 (Sonnet, worktree)
  lancée à 13 h 12 sur la bonne architecture, revenue à 13 h 40 avec quatre commits, `tools/verifier.sh` vert, 1142
  tests Gradle dont 168 ignorés sans Docker. Relu en entier : `Config`, `Main` (deux serveurs), `Dependencies`
  (trois champs ajoutés), le paquet `site/` (module, routes, contenus, Markdown, cinq pages), les trois contenus, le
  CSS, les quatre fichiers de tests, l'anti-IA (`litteraux_kt`, `MENTIONS_PERMISES`, six cas de test). Rien hors du
  périmètre, aucun chiffre du jeu, aucun secret, textes conformes à la charte. Fusionnée (`9de34a8`) ; la direction a
  écrit le point S0 de `docs/site.md` et corrigé le commentaire du test de compose (« S6 » n'existe pas). Suite : S1.
- 5 novembre 2026, 13 h 50 à 14 h 10 (direction du site, cloud). `main` poussé avec S0 (`fd39d22`, après une
  fusion de la passation du jeu : phase 5 ouverte, décision 39). Session S1 (Sonnet, worktree, partie de `main`)
  lancée à 13 h 36, revenue à 13 h 48 avec quatre commits et douze captures Playwright à 360 et 1280 px. Relu : le
  gabarit et la navigation, les pages, le CSS (313 lignes, bordures des textures identiques à `bundle.css`), les
  trois tests nouveaux et le rendu sur disque, les captures (rien ne déborde à 360 px, les polices se chargent, la
  plaque, le panneau riveté et Oooscar sont ceux de la charte). Fusionnée en avance rapide (`7996856`), puis le
  paragraphe en double de la page d'attente retiré. Suite : S2.
- 5 novembre 2026, 13 h 50 à 14 h 05 (direction du site, cloud). `main` poussé avec S1 (`6301067`). Session S2
  (Sonnet, worktree, partie de `main`) lancée à 13 h 50, revenue à 14 h 02 avec trois commits et vingt captures.
  Relu : les quatre variables et `SiteSettings`, le mécanisme `{{nom}}`, `Offres.kt` (rien que les définitions),
  `Updates.kt`, les six contenus, les deux fichiers de tests nouveaux, le `rel="nofollow"` retiré des liens
  internes, les captures de l'accueil complet, de « Ce qui se vend » et de « Rejoindre ». Fusionnée en avance
  rapide (`0834dce`).
- **5 novembre 2026, 14 h 05 : bilan de deux heures (direction du site).**
  - Fait depuis 12 h 45 : la mise en place (direction, passation, `site.md` sur `main`, ADR 0006 réécrit sur la
    décision 37), S0 (le site dans silooo-core, second serveur, deux modes, attente et mentions, anti-IA), S1
    (Silooo Atelier pour le web, polices servies par le site, gabarit et navigation), S2 (les six pages publiques
    du mode ouvert) ; tout sur `main`, chaque fusion vérifiée par `tools/verifier.sh` dans la session (Gradle
    compris, tests SQL ignorés sans Docker) et par la CI.
  - Reste : S3 (le wiki), S4 (pionniers, Relier Discord, admission, migration V7), S5 (joueurs en ligne, classements,
    fiches, webhook PayNow, marche à suivre de la mise en ligne). Lancement de S3 à ce bilan.
  - Décisions prises seule : 1 à 8 ci-dessus ; la 3 (réalignement) est la seule qui change le cadre, et elle suit
    la décision 37 de Kr.
  - Pour Kr : la liste « À faire par Kr » (ses fichiers de cadrage à pousser, l'ADR 0006, le dessin de S4, le DNS, le
    bloc Caddy, les mentions, la boutique, les sites de vote, Discord, PayNow, le texte des consoles) ; les textes
    « À valider par Kr » de S0, S1 et S2.
  - Pour la direction du jeu : les six points de la section, dont les variables du site à poser dans
    `deploy/exemple.env.txt` et le nom des abonnements dans `boutique.json`.
- 5 novembre 2026, 14 h 05 à 14 h 20 (direction du site, cloud). `main` poussé avec S2 et le bilan (`75256e9`).
  Session S3 (Sonnet, worktree, partie de `main`) lancée à 14 h 06, revenue à 14 h 17 avec trois commits. Relu : les
  treize textes en entier (justes contre `economie.md` et `core/README.md`, sans slop, en mots du jeu), les blocs
  rendus (aucun chiffre hors des définitions), la capture de la page des usines à 360 px (tableau en fiches). Deux
  phrases corrigées à la fusion (décision 9). Deux points ouverts remontés au jeu (noms des raretés, agence payante
  ou non). Suite : S4.
- 5 novembre 2026, 14 h 20 à 14 h 36 (direction du site, cloud). `main` poussé avec S3 (`ba9cd57`). Session S4
  (Sonnet, worktree, partie de `main`) lancée à 14 h 22, revenue à 14 h 34 avec trois commits. Relu en entier :
  la migration V7, `SiteRegistrations` (clé, contrôle du gamertag, règle d'admission), `SiteSigner` (HMAC, rôles,
  durées, comparaison en temps constant), `RateLimiter`, `PioneerRoutes` (les cinq routes, le cookie, l'état
  OAuth2, les refus), `HttpDiscordIdentity` (échange du code, lecture de l'identifiant, rien de sensible dans les
  erreurs), le SQL du store (INSERT simple, doublon par la clé, UPDATE conditionnel), les deux contenus. Réserve de
  conception notée pour Kr et pour le jeu : la possession du gamertag n'est pas prouvée. Fusionnée. Suite : S5.
- 5 novembre 2026, 14 h 36 à 14 h 41 (direction du site, cloud). `main` avait avancé de quatre commits (Gemmes et
  grades du jeu, décision 40, votes lancés) : fusionné dans la branche de la direction, deux conflits de lignes
  ajoutées des deux côtés (`Main.kt` : imports ; `Config.kt` : le `toString`), résolus en gardant les deux ;
  `tools/verifier.sh` vert sur l'arbre fusionné (1316 tests Gradle dont les SQL ignorés). Points « à revoir » de la
  session S4 consignés (décision 10). `main` poussé. Suite : S5.
- 5 novembre 2026, 14 h 41 à 15 h 00 (direction du site, cloud). `main` poussé avec S4 et la fusion des Gemmes
  (`fad5319`). Session S5 (Sonnet, worktree, partie de `main`) lancée à 14 h 42, revenue à 14 h 51 avec trois commits
  et cinq captures. Relu : `PayNowWebhook` (signature en temps constant, corps borné à quatre kilo-octets, champs
  nommés, rien du corps dans les réponses), `WebhookRoutes` (503, 413, 401, 400, 200, journal une fois), le SQL des
  classements (somme des deltas positifs de Lingots dans la semaine, jointures gauches, tri stable) et de la fiche
  (membres PDG d'abord), `ShopDeliveryStore` (INSERT simple, doublon par 1062), la section « Mettre en ligne », la
  capture des classements à 360 px (fiches lisibles ; la plaque coupait le mot, corrigé). `main` avait encore avancé
  (add-on de la ville, phase 4 close sauf Kr, décision 41) : fusionné sans conflit. Les heures du journal depuis 13 h 50
  ont été remises sur l'horloge de Paris (elles avaient dérivé d'une demi-heure).
- **5 novembre 2026, 15 h 00 : dernier bilan (direction du site).**
  - Fait depuis le bilan de 14 h 05 : S3 (le wiki, treize pages), S4 (pionniers, Relier Discord, admission, migration
    V7), S5 (joueurs en ligne, classements, fiche, webhook PayNow, marche à suivre de la mise en ligne), les deux
    fusions de `main` du jeu (Gemmes et grades, add-on de la ville). S0 à S5 sont cochées sur `main`, sauf les cases
    de Kr ; `main` est vert ; la section « Prêt à mettre en ligne » est écrite.
  - Reste : rien côté site hors validation. Pour Kr, la liste « À faire par Kr » (pousser ses fichiers de cadrage,
    relire l'ADR 0006 et le dessin de S4, DNS, bloc Caddy, mentions, boutique, sites de vote, Discord, PayNow, texte
    des consoles, règles du serveur, passage à `ouvert`) et tous les textes « À valider par Kr ». Pour la direction du
    jeu, les neuf points de « Pour la direction du jeu ».
  - Décisions prises seule : 1 à 11. Aucune session de travail en cours : les cinq sessions S0 à S5 (six lancées, la
    première arrêtée avant toute fusion) sont finies, leurs worktrees retirés, leurs branches `claude/site-s0` à
    `s5` poussées et fusionnées.
  - Ce que seule la CI vérifie : les tests SQL Testcontainers de S4 et S5 (`SiteRegistrationSqlTest`,
    `SiteStatsSqlTest`, `ShopDeliverySqlTest`) et `MigrationTest` avec V7, ignorés sans Docker dans la session.
- 5 novembre 2026, 15 h 30 à 15 h 48 (direction du site, deuxième passage, en local sur le PC de Kr). Le kit de Kr, non suivi
  dans le dossier `silooo`, est copié tel quel dans `docs/references/site-kit/` (ses trois `site*.md`) et
  `docs/references/site/` (maquettes, prototype d'Oooscar), versionnés sur `claude/site-revision` (worktree `silooo-site`,
  partie de `origin/main`). Lu : `CLAUDE.md`, `docs/README.md`, `docs/passation.md` (décisions 37 à 42), le kit, le cadrage
  et le plan de construction de Kr, l'ADR 0006, `docs/site*.md` de `main`, les paquets `site/`, `registration/`,
  `sitedata/` et les ressources du site. Premier geste : « Prêt à mettre en ligne » devient « Suspendu : révision en
  cours », une ligne pour la direction du jeu. Suite : le site rendu en local, captures à 360 et 1 280 px, puis
  `docs/site-revision.md`.
