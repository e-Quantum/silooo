# Passation du site : l'état du chantier de silooo.com

Dernière mise à jour : 5 novembre 2026, 13 h 50, S0 fusionnée (direction du site, cloud).

Ce fichier est tenu par la session de direction du site ([site-direction.md](site-direction.md)). Il ne remplace pas
[passation.md](passation.md), qui reste à la direction du jeu : ici, seulement le site `silooo.com`, décrit dans
[site.md](site.md) et tranché par l'[ADR 0006](adr/0006-site-silooo-com.md).

## État

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

- [ ] `public/atelier.css` : les jetons copiés de `design/tokens.css` (copie contrôlée), puis les composants du web
  d'après `design/composants/bundle.css` : panneau, plaque, bouton (rouge, laiton, fonte, appuyé, focus), ticket,
  monnaie, puce, icône, bulle ; `border-image` sur les textures de `design/textures/` copiées dans
  `public/atelier/textures/` ; `image-rendering: pixelated` ; aucun arrondi, dégradé, flou ; une seule ombre ; un seul
  bouton rouge par page ; un test compare chaque copie à sa source et refuse `border-radius`, `linear-gradient`,
  `radial-gradient`, `blur(`, `backdrop-filter` dans le CSS
- [ ] Les trois polices (Jersey 10, Pixelify Sans, Silkscreen) : fichiers `woff2` du sous-ensemble latin tirés des paquets
  `@fontsource/*` (licence OFL, fichier de licence commis à côté, version notée), `@font-face` avec `font-display: swap`,
  secours de `tokens.css` ; rien chargé depuis un autre hôte
- [ ] Les tailles du web : corps lisible sur téléphone (20 px au moins, interligne 1,3), `affiche` pour les titres (h1
  sur une plaque, deux tailles d'affiche au plus par page), `etiquette` pour les légendes, multiples de 3 px ; fond
  des pages de lecture choisi et justifié (fonte et papier, ou papier et fonte), contrastes de la charte
- [ ] Gabarit commun : en-tête (logo SVG, nom, navigation du mode : en `attente` seulement Mentions ; en `ouvert`
  Accueil, Rejoindre, Wiki, Classements, Boutique, Vote, Mises à jour, Mentions, les pages absentes ne s'affichent
  pas), pied (mention Mojang, Mentions, Discord si configuré), `og:title`, `og:description`, `og:image` absolu
  (`SILOOO_SITE_URL`, `https://silooo.com` par défaut), icône de site (logo 64), `theme-color` ; focus visible
- [ ] Mobile : 360 px sans défilement horizontal, cibles de 60 px, texte à 20 px ; captures Playwright à 360 et 1280 px
  hors du dépôt quand Chromium est là (pas dans les tests)
- [ ] Oooscar `joie` sur la page d'attente avec une bulle de six mots au plus (« Le serveur ouvre bientôt ! ») ; texte
  **à valider par Kr** ; `docs/site.md`, point S1 : ce que le web ajoute à la charte

### S2 : les pages publiques du mode ouvert

Critère de fin : Accueil, Rejoindre, Ce qui se vend, Boutique, Vote et Mises à jour rendues en mode `ouvert`,
absentes en `attente` (404) ; aucun chiffre tapé ; textes marqués à valider ; `tools/verifier.sh` vert.

- [ ] Accueil : le jeu en une phrase, l'adresse et le port, l'emplacement des joueurs en ligne (rempli en S5, caché
  tant que la donnée manque), une vidéo et une capture à poser par Kr (emplacements marqués), le lien Discord ;
  quatre paragraphes au plus
- [ ] Rejoindre : l'adresse (`SILOOO_SITE_ADRESSE_JEU`, `play.silooo.com` par défaut) et le port (`SILOOO_SITE_PORT_JEU`,
  même défaut que `BDS_PORT` du compose, un test compare la constante à `deploy/compose.yml`), la marche à suivre par
  plate-forme (PC, téléphone et tablette, console : l'ajout d'un serveur n'est pas offert par toutes les consoles,
  la méthode est **à valider par Kr**)
- [ ] Ce qui se vend : la promesse « Tout ce qui s'achète se gagne aussi en jouant », les packs de Gemmes, les grades
  et leur bonus, le Pilier, lus dans `ShopDefinitions` ; la liste de ce qui n'est jamais en vente ([vision.md](vision.md)) ;
  un test compare la page aux définitions
- [ ] Boutique : les grades et le Pilier (mêmes définitions), où s'achètent les Gemmes (`SILOOO_SITE_URL_BOUTIQUE`, vide
  tant que Kr ne l'a pas donnée : le lien ne s'affiche pas), les interdits, la note sur l'historique d'achats (en jeu)
- [ ] Vote : les sites de vote (`SILOOO_SITE_SITES_DE_VOTE`, liste vide tant que Kr ne les a pas donnés), la récompense
  par vote seulement quand `definitions/` la chiffre (sinon la page dit qu'elle arrive), la jauge de la Vote party
  plus tard
- [ ] Mises à jour : `contenu/mises-a-jour/AAAA-MM-JJ-<sujet>.md`, une entrée par tag déployé, la plus récente en tête ;
  sans entrée, la page dit que le serveur n'a pas encore ouvert ; un test refuse un nom de fichier mal formé
- [ ] `docs/site.md` : le tableau des pages pointe vers les fichiers de `contenu/`

### S3 : le wiki

Critère de fin : les pages du wiki rendues en mode `ouvert`, dans l'ordre où le joueur rencontre les systèmes ;
les listes (usines, raretés, sortes d'incidents) tirées des définitions chargées ; `tools/verifier.sh` vert.

- [ ] `contenu/wiki/` : la première heure ; les usines et les paliers ; le Silo et les gains hors ligne ; le personnel
  et l'agence ; les trois monnaies ; le prestige et les étoiles ; les incidents et l'électricité ; la commande du jour
  et le Carnet ; l'entreprise partagée ; la ville (en construction, dit comme tel) ; les mots du jeu (glossaire pour
  les joueurs, écrit à la main) ; les règles du serveur (page avec un trou marqué : **à écrire par Kr**)
- [ ] Les tableaux générés depuis `Definitions` : usines (nom, produit, entrées, époque), raretés et postes du
  personnel, sortes d'incidents, packs ; jamais un chiffre tapé ; un test compare chaque usine aux pages
- [ ] Pas de commande à taper ; phrases courtes ; mots du glossaire ; textes **à valider par Kr** ; une page d'index du
  wiki et sa place dans la navigation ; `docs/site.md` : la liste des pages du wiki

### S4 : les pionniers, Relier Discord, l'admission

Critère de fin : un joueur s'inscrit comme pionnier avec son gamertag en mode `attente` comme en `ouvert`, relie son
compte Discord quand Kr a posé l'application, et la table dit qui est admis ; la direction du jeu a de quoi refuser
les non-admis dans `join` ; migration prise à la fusion ; `tools/verifier.sh` vert. Le dessin de cette étape est
**à valider par Kr** : il reconstruit ce que la décision 37 et le journal du jeu laissent voir (« Pionnier », « Relier
Discord », « refus des non-admis »), faute des fichiers de Kr.

- [ ] Migration (numéro pris à la fusion ; V6 est prise par la ville depuis le 5 novembre à 13 h, V7 attendue) : table `site_registrations` (gamertag en minuscules en clé,
  gamertag tel que saisi, identifiant Discord facultatif jamais affiché, `created_at`, `admitted_at` facultatif,
  `pioneer` booléen), index sur `admitted_at` ; `docs/base-de-donnees.md` n'est pas touché par la direction du site :
  la table est décrite dans `core/README.md` et demandée dans « Pour la direction du jeu »
- [ ] Page `/pionniers/` : un formulaire (gamertag, case à cocher des règles), limite de débit simple par adresse,
  pas de courriel ; un gamertag déjà inscrit rend la même page avec son état ; le compteur des pionniers inscrits ;
  textes **à valider par Kr**
- [ ] « Relier Discord » : OAuth2 de Discord (identité seule), activé seulement si `SILOOO_DISCORD_CLIENT_ID`,
  `SILOOO_DISCORD_CLIENT_SECRET` et `SILOOO_SITE_URL` sont posés, sinon la page explique que ce n'est pas encore
  ouvert ; l'identifiant Discord se range dans `site_registrations`, jamais affiché ; état et jeton CSRF en cookie de
  session signé, de courte durée, seul cookie du site (les mentions le disent) ; faux fournisseur dans les tests
- [ ] L'admission : `admitted_at` posé par une règle lisible dans `core/README.md` (par exemple : inscrit et relié,
  ou admis à la main par Kr avec une commande ou une requête documentée) ; le site n'écrit rien d'autre ; la
  direction du jeu lit `site_registrations.admitted_at` par le gamertag pour refuser dans `join` (contrat d'abord)
- [ ] La récompense du pionnier (titre et objet) reste au jeu : le site ne touche pas `player_cosmetics` ; la ligne
  `pioneer` suffit au jeu pour la créditer plus tard (demandé dans « Pour la direction du jeu »)

### S5 : les données vivantes et la mise en ligne

Critère de fin : joueurs en ligne sur l'accueil, Classements et fiches d'entreprise rendus en mode `ouvert` ; le
webhook PayNow écrit `shop_deliveries` ; la liste « Prêt à mettre en ligne » écrite ; `tools/verifier.sh` vert.

- [ ] Joueurs en ligne : lus dans `ServerPresence` (le dernier `heartbeat`), affichés sur l'accueil ; « Serveur fermé »
  sans heartbeat récent
- [ ] Classements : la semaine ISO en cours, les joueurs par Lingots gagnés dans la semaine (`currency_ledger`,
  `LINGOTS`, `delta > 0`), gamertag et nom d'entreprise, taille de page technique dans le code ; cache d'une minute ;
  tests SQL Testcontainers
- [ ] Fiche d'entreprise `/entreprise/{id}/` : nom, époque, étoiles, membres (gamertag et rôle, PDG d'abord), nombre de
  prestiges, date de création ; 404 en page Silooo pour une entreprise inconnue ; jamais de XUID ni de solde
- [ ] Webhook PayNow `POST /paynow/webhook` : signature contrôlée avec `SILOOO_PAYNOW_WEBHOOK_SECRET` (le format exact de
  PayNow est **à confirmer par Kr** ; en attendant, HMAC-SHA256 du corps dans un en-tête nommé, à changer en une
  constante), une ligne `shop_deliveries` par commande (`order_id`, gamertag, Gemmes), rejeu sans double ligne
  (clé primaire), 401 sans signature, 503 sans secret configuré ; tests SQL
- [ ] « Prêt à mettre en ligne » : la section est écrite ici quand S0 à S5 sont sur `main`, avec la marche à suivre de
  Kr (DNS, bloc Caddy vers `silooo-core:8081`, mentions remplies, variables posées, `SILOOO_SITE_MODE` laissé à
  `attente`) ; `docs/site.md` complet

## Pour la direction du jeu

Lu à chaque bilan de la direction du jeu (décision 37, point 3). Réponses attendues dans son journal.

1. **Les fichiers de Kr.** Vous voyez `docs/site-direction.md`, `docs/site-passation.md` et `docs/references/site/`
   non suivis dans votre dossier. La direction du site du cloud ne les a pas. Si vous pouvez les lire sans les
   ajouter à votre index, copiez leur texte tel quel dans votre journal, ou dites à Kr de les pousser (branche
   `claude/site-kr`, ou le worktree `silooo-site`) : la direction du site réalignera ses étapes dessus.
2. **Fichiers partagés de `core/`.** S0 ajoute des lignes dans `Config.kt`, `Main.kt`, `Application.kt`
   (`Dependencies`), `libs.versions.toml` et `core/README.md`, et crée le paquet `site/`. Dites à vos tranches de ne
   pas réécrire ces fichiers d'un bloc, et prévenez ici si l'une d'elles y travaille.
3. **Variables d'environnement du site**, lues par silooo-core par `env_file` (rien à changer dans le compose) :
   `SILOOO_SITE_PORT` (8081), `SILOOO_SITE_MODE` (`attente`), plus tard `SILOOO_SITE_URL`, `SILOOO_DISCORD_CLIENT_ID`,
   `SILOOO_DISCORD_CLIENT_SECRET`, `SILOOO_PAYNOW_WEBHOOK_SECRET`. `deploy/exemple.env.txt` et le tableau des variables
   de `docs/deploiement.md` sont à vous : merci d'y ajouter ces lignes (S0 est sur `main` : les quatre premières
   sont lues par `Config.kt`), avec l'avertissement « jamais `ouvert` par une session », puis de retirer leurs noms
   de la liste `reglees` de `tools/tests/deploy-compose.mjs`, où S0 les a exemptés faute de pouvoir toucher `deploy/`.
4. **L'admission (S4).** La table proposée est `site_registrations` (gamertag en minuscules en clé, `admitted_at`
   facultatif, `pioneer` booléen) : « admis » veut dire `admitted_at IS NOT NULL` pour `LOWER(players.gamertag)`.
   `join` reçoit le gamertag : pas besoin de l'empreinte. Le numéro de migration se prend à la fusion de S4 (V7 si
   personne ne l'a pris : la ville a pris V6). Le titre et l'objet du pionnier restent au jeu : le site pose seulement `pioneer`.
5. **Rien à vous demander pour S0 à S3** : contenus, style et wiki ne touchent ni `api/`, ni `addon/`, ni `deploy/`.

## À faire par Kr

- [ ] Pousser les fichiers de cadrage du worktree `silooo-site` (`docs/site-direction.md`, `docs/site-passation.md`,
  `docs/references/site/`) : la direction du site du cloud a écrit les siens sans les lire et se réalignera
- [ ] Relire l'[ADR 0006](adr/0006-site-silooo-com.md) (statut « Proposée ») : le site dans silooo-core, sur son propre
  port, deux modes, rendu Kotlin, ce que le site écrit
- [ ] Relire le dessin de S4 (pionniers, Relier Discord, admission) avant que la session de travail le commence
- [ ] Le DNS de `silooo.com` (et `www`) vers l'IP du VPS, quand le site sera déployé
- [ ] Le bloc `silooo.com` dans le Caddy de SileneMC (`/var/www/minecraft`, qu'aucune session Silooo ne touche) :
  mandat vers `silooo-core:8081` sur le réseau `silenemc`, TLS automatique
- [ ] L'identité légale des mentions : éditeur, hébergeur, contact, conditions de vente, protection des données
- [ ] L'adresse de la boutique de SileneMC pour les Gemmes de Silooo, et les sites de vote
- [ ] Le lien d'invitation Discord, l'application Discord (identifiant et secret) pour « Relier Discord »
- [ ] Le format exact du webhook PayNow (en-tête de signature, corps) et son secret
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
