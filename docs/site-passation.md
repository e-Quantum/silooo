# Passation du site : l'état du chantier de silooo.com

Dernière mise à jour : 5 novembre 2026, 12 h 55, mise en place de la direction du site.

Ce fichier est tenu par la session de direction du site ([site-direction.md](site-direction.md)). Il ne remplace pas
[passation.md](passation.md), qui reste à la direction du jeu : ici, seulement le site `silooo.com`, décrit dans
[site.md](site.md) et tranché par l'[ADR 0006](adr/0006-site-silooo-com.md).

## État

- Le dépôt n'a pas encore de dossier `site/`. Le cadrage de la phase 0 (`docs/site.md`, resté sur la branche
  `claude/phase0-docs`) est repris sur `main` et mis à jour ; l'ADR 0006 pose l'architecture : site statique à part,
  dans `site/`, conteneur `silooo-site` du projet `silooo`, deux modes (`attente` par défaut, `ouvert`), routes
  publiques de silooo-core en lecture seule pour les données vivantes.
- Le jeu est en phase 4 ([passation.md](passation.md)) : le service connaît les usines, le personnel, le prestige, les
  missions, les incidents, la commande du jour, le Carnet et l'entreprise partagée. Le serveur n'a pas encore tourné
  sur le VPS (base, port, DNS et zip de BDS attendent Kr). Le site se construit donc en mode `attente` : il ne
  promet rien qui ne soit pas joignable.
- `main` au départ de la direction du site (commit `21381cd`) : `tools/verifier.sh` passe sauf l'étape Gradle, qui
  n'a pas pu télécharger ses dépendances depuis la session cloud (Maven Central a répondu 429) ; la CI de ce
  commit est verte d'après [passation.md](passation.md). Relancé, voir le journal.

## Les étapes

Chaque étape est une session de travail, une à la fois, sur une branche `claude/site-sN`, relue et vérifiée
(`tools/verifier.sh`) par la direction avant la fusion en avance rapide sur `main`.

### S0 : le socle de `site/`

Critère de fin : `node site/construire.mjs` écrit le site des deux modes dans un dossier au choix ; `node --test`
passe dans `site/` ; `tools/verifier.sh` a une étape « Site » verte ; `tools/anti_ia.py` lit les textes du site.

- [ ] `site/` : `construire.mjs` (Node 22, les devDependencies de la racine, sans framework ; `--mode attente|ouvert`,
  `--sortie <dossier>`, défaut `attente` et `site/dist/`, déjà ignoré par git), `site/README.md`, `site/contenu/`
  (Markdown avec un en-tête `titre`, `description`, `url`, `modes`), `site/gabarits/` (fonctions JS qui rendent le
  HTML, une page = un fichier), `site/public/` (copié tel quel), `site/site.json` (adresse du serveur, nom du Discord
  à poser par Kr), `site/tests/`
- [ ] `SILOOO_SITE_MODE` : `attente` rend l'accueil d'attente et les mentions ; `ouvert` rend toutes les pages ; une
  autre valeur refuse avec le code 64 et un message ; la variable d'environnement vaut, l'option `--mode` gagne
- [ ] La page d'attente : le logo, le jeu en une phrase, la plate-forme (Minecraft Bedrock), « bientôt » sans date, le
  lien Discord seulement si `site.json` en a un ; la page Mentions avec « NOT AN OFFICIAL MINECRAFT SERVICE. NOT
  APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT » et des trous marqués pour Kr (éditeur, hébergeur, contact) ;
  textes **à valider par Kr**
- [ ] Tests (`node --test site/tests/`) : construction des deux modes dans un dossier temporaire ; chaque page a
  `lang="fr"`, un seul `h1`, un `title`, une `meta description`, un `alt` sur chaque image ; aucun lien interne mort ;
  le mode inconnu refuse ; `site.json` lu et validé (schéma dans `site/`)
- [ ] `tools/verifier.sh` : étape « Site : tests et construction » (les deux modes) ; `tools/anti_ia.py` :
  `site/contenu/**` et `site/gabarits/**` dans `TEXTES_JOUEURS` (pour les `.md`, le texte entier ; pour les `.mjs`,
  les littéraux), avec un cas dans `tools/tests/` ; la CI n'a rien à changer, elle lance le vérificateur
- [ ] `docs/site.md`, section « Comment le site se construit », point S0 écrit ; `site/README.md` dit comment
  construire, servir en local et tester

### S1 : Silooo Atelier pour le web

Critère de fin : la page d'attente et les mentions ont le gabarit commun et le style Silooo Atelier ; lisibles à
360 px de large sans défilement horizontal ; les polices sont servies par le site ; `tools/verifier.sh` vert.

- [ ] `site/public/atelier.css` : les jetons copiés de `design/tokens.css` à la construction (copie contrôlée octet
  pour octet, comme `tools/verifier_pack.mjs`), puis les composants du web : panneau, plaque, bouton, ticket, monnaie,
  par `border-image` sur les textures de `design/textures/` copiées à la construction ; `image-rendering: pixelated` ;
  aucun arrondi, aucun dégradé, une seule ombre ; un seul bouton rouge par page
- [ ] Les trois polices par les paquets npm `@fontsource/jersey-10`, `@fontsource/pixelify-sans`,
  `@fontsource/silkscreen` (licence OFL, fichiers copiés dans `site/dist/polices/` à la construction), avec les
  secours de `tokens.css` ; les tailles du web (corps lisible sur téléphone) notées dans `docs/site.md`
- [ ] Gabarit commun : en-tête (logo, nom, navigation du mode), pied (la mention Mojang, Mentions, Discord si posé,
  « Mises à jour » en mode ouvert), `lang="fr"`, `meta viewport`, `meta description`, image de partage (le logo 512),
  icône de site (le logo 64) ; titres hiérarchisés ; focus visible ; contrastes de la charte
- [ ] Mobile : à 360 px, aucun défilement horizontal, boutons de 60 px au moins ; vérifié par un test qui lit le CSS
  (pas de largeur fixe au-dessus de 360 px hors `max-width`) et, quand Chromium est là, par une capture Playwright
  gardée hors du dépôt
- [ ] `docs/site.md`, section « Comment le site se construit », point S1 : ce que le web ajoute à la charte

### S2 : Accueil, Rejoindre, Ce qui se vend, Mises à jour (mode ouvert)

Critère de fin : les quatre pages construites en mode `ouvert`, absentes en mode `attente` ; aucun chiffre écrit à la
main ; textes marqués à valider ; `tools/verifier.sh` vert.

- [ ] Accueil (mode ouvert) : le jeu en une phrase, l'adresse et le port, l'emplacement des joueurs en ligne (rempli
  en S4, caché tant que l'API ne répond pas), une vidéo et une capture à poser par Kr (emplacements marqués),
  le lien Discord ; quatre paragraphes au plus
- [ ] Rejoindre : l'adresse `play.silooo.com` de `site/site.json` et le port lu dans `deploy/compose.yml` (valeur par
  défaut de `BDS_PORT`, un test compare), la marche à suivre par plate-forme (PC, téléphone et tablette, console :
  l'ajout d'un serveur n'est pas offert par toutes les consoles, la méthode est **à valider par Kr**)
- [ ] Ce qui se vend : la promesse « Tout ce qui s'achète se gagne aussi en jouant », les packs de Gemmes, les grades et
  leur bonus, le Pilier, lus dans `definitions/boutique.json` ; la liste de ce qui n'est jamais en vente
  ([vision.md](vision.md)) ; un test compare la page aux définitions
- [ ] Mises à jour : `site/contenu/mises-a-jour/AAAA-MM-JJ-<sujet>.md`, une entrée par tag déployé, la plus récente en
  tête ; sans entrée, la page dit que le serveur n'a pas encore ouvert ; un test refuse un nom de fichier mal formé
- [ ] `docs/site.md` : le tableau des pages pointe vers les fichiers de `site/contenu/`

### S3 : le wiki

Critère de fin : les pages du wiki construites en mode `ouvert`, dans l'ordre où le joueur rencontre les systèmes ;
les listes (usines, raretés, sortes d'incidents) tirées de `definitions/` ; `tools/verifier.sh` vert.

- [ ] `site/contenu/wiki/` : la première heure ; les usines et les paliers ; le Silo et les gains hors ligne ; le
  personnel et l'agence ; les trois monnaies ; le prestige et les étoiles ; les incidents et l'électricité ; la
  commande du jour et le Carnet ; l'entreprise partagée ; la ville (en construction, dit comme tel) ; les mots du jeu
  (glossaire pour les joueurs, écrit à la main, pas une copie de `docs/glossaire.md`) ; les règles du serveur (page
  vide avec un trou marqué : **à écrire par Kr**)
- [ ] Les tableaux générés : usines de `usines.json` (nom, produit, entrées, époque), raretés et postes de
  `personnel.json`, sortes d'incidents de `incidents.json`, packs de `boutique.json` ; jamais un chiffre tapé ; un test
  compare chaque usine du JSON à la page
- [ ] Pas de commande à taper (tout passe par des formulaires en jeu) ; phrases courtes ; mots du glossaire ; textes
  **à valider par Kr** ; une page d'index du wiki et sa place dans la navigation
- [ ] `docs/site.md` : la liste des pages du wiki

### S4 : classements et fiches publiques

Critère de fin : trois routes publiques de silooo-core au contrat, testées (dont SQL), sans clé, en lecture seule ;
les pages Classements et Entreprise les lisent par `/api/` ; `tools/verifier.sh` vert.

- [ ] OpenAPI d'abord : `GET /public/v1/status` (`publicStatus` : joueurs en ligne d'après le dernier `heartbeat`,
  serveur joignable ou non), `GET /public/v1/leaderboard/weekly` (`publicWeeklyLeaderboard` : semaine ISO en cours,
  les joueurs classés par Lingots gagnés dans la semaine, `currency_ledger` `LINGOTS` à `delta > 0`, gamertag et nom
  d'entreprise, taille de page technique), `GET /public/v1/companies/{companyId}` (`publicCompany` : nom, époque,
  étoiles, membres avec gamertag et rôle, nombre de prestiges, date de création) ; `security: []`, `Erreur` 400, 404 et
  500 ; jamais de XUID ni de solde ; le test de contrat apprend les opérations sans clé (`WRITTEN`)
- [ ] silooo-core : paquet `public/` (`PublicRoutes`, `PublicService`, `JdbcPublicStore`), installé hors de la route
  `/v1` et de son `ServerKeyPlugin` ; cache en mémoire d'une minute par route ; tests du service et tests SQL
  Testcontainers (classement d'une semaine, entreprise inconnue 404, membres dans l'ordre PDG puis arrivée) ;
  `core/README.md`, section « Les routes publiques » ; `docs/architecture.md` : la ligne du site dans le tableau
  « Qui appelle qui »
- [ ] Site : page Classements (table de la semaine, dates de la semaine, message quand l'API ne répond pas), page
  Entreprise (`/entreprise/?id=<id>` : la fiche), les joueurs en ligne sur l'accueil ; JavaScript sans dépendance,
  `fetch` relatif sur `/api/`, rendu sans script possible (le message) ; `site/servir.mjs` pour servir `site/dist/` en
  local en mandatant `/api/` vers une adresse donnée, pour relire
- [ ] Tests : le JavaScript rend une réponse exemple (tests Node avec un faux `fetch`) ; les exemples de l'OpenAPI
  servent de réponses de test

### S5 : Boutique, Vote, déploiement

Critère de fin : pages Boutique et Vote construites en mode `ouvert` ; image `silooo-site` construite par la CI ;
service `silooo-site` dans le compose avec `SILOOO_SITE_MODE=attente` par défaut ; `deploy.sh` le démarre et le teste ;
tests de `tools/tests/` à jour ; `tools/verifier.sh` vert. Le déploiement lui-même revient à Kr.

- [ ] Boutique : les grades et le Pilier lus dans `boutique.json`, où s'achètent les Gemmes (adresse de la boutique de
  SileneMC dans `site/site.json`, vide tant que Kr ne l'a pas donnée : le lien ne s'affiche pas), les interdits, la
  note sur l'historique d'achats (en jeu, pas sur le site tant qu'aucune liaison de compte n'existe)
- [ ] Vote : les sites de vote de `site/site.json` (vide tant que Kr ne les a pas donnés), la récompense par vote
  seulement quand `definitions/` la chiffre (sinon la page dit qu'elle arrive), la jauge de la Vote party quand une
  route la donnera (pas encore)
- [ ] `deploy/site/Dockerfile` (construction des deux modes par Node, image finale Caddy, utilisateur non root),
  `deploy/site/Caddyfile` (racine choisie par `SILOOO_SITE_MODE`, défaut `attente` ; `/api/*` mandaté vers
  `http://silooo-core:8080/public/` ; compression ; en-têtes de sécurité ; cache des fichiers), `deploy/compose.yml` :
  service `silooo-site` (réseaux `silooo` et `silenemc`, mémoire 64 Mo, `restart: unless-stopped`, aucun port publié,
  `SILOOO_SITE_MODE=${SILOOO_SITE_MODE:-attente}`) ; `deploy/exemple.env.txt` : la variable, avec l'avertissement ;
  `deploy/scripts/deploy.sh` : démarre `silooo-site` et vérifie `GET /` 200 ; `tools/tests/deploy-*` à jour ;
  `.github/workflows/ci.yml` : job `image-site` (construit, contrôle les deux racines et le mode par défaut, publie
  sur `main`) ; `docs/deploiement.md` et `deploy/README.md` : les lignes du site
- [ ] `docs/site.md` : « Comment le site se construit » complet, et la marche à suivre de Kr pour le DNS, le bloc Caddy
  et l'ouverture

## À faire par Kr

- [ ] Relire l'[ADR 0006](adr/0006-site-silooo-com.md) (statut « Proposée ») : site à part dans `site/`, conteneur
  `silooo-site`, routes publiques `/public/v1/` de silooo-core sans clé, mandatées par le Caddy du site
- [ ] Le DNS de `silooo.com` (et `www`) vers l'IP du VPS, quand le site sera déployé
- [ ] Le bloc `silooo.com` dans le Caddy de SileneMC (`/var/www/minecraft`, qu'aucune session Silooo ne touche) :
  mandat vers `silooo-site:80` sur le réseau `silenemc`, TLS automatique
- [ ] L'identité légale des mentions : éditeur, hébergeur, contact, conditions de vente, protection des données
- [ ] L'adresse de la boutique de SileneMC pour les Gemmes de Silooo, et les sites de vote (`site/site.json`)
- [ ] Le lien d'invitation Discord (`site/site.json`), s'il existe
- [ ] Le relevé de `skyblock.rinaorc.com` (grille de [site.md](site.md)), depuis le PC ou en autorisant le domaine
- [ ] Le passage de `SILOOO_SITE_MODE` à `ouvert` dans le fichier d'environnement du VPS, à l'ouverture (phase 7)

## Décisions prises sans Kr

1. **La mise en place écrit ce qui manquait.** Au premier tour, ni `docs/site-direction.md` ni `docs/site-passation.md`
   n'existaient sur aucune branche ; `docs/site.md` dormait sur `claude/phase0-docs`. La direction du site a écrit
   les deux premiers d'après [direction.md](direction.md) et le prompt de Kr, repris le troisième sur `main`, et
   découpé le chantier en six étapes S0 à S5 (socle, design web, pages d'accueil, wiki, données vivantes, boutique
   et déploiement). À relire par Kr.
2. **ADR 0006 pris et annoncé ici.** Le numéro suivant de l'index était 0006 ; la direction du site n'écrit pas
   dans `passation.md`, l'index de `docs/adr/README.md` tient lieu d'annonce.
3. **Les quatre questions de `site.md` sont tranchées par l'ADR 0006**, avec les recommandations déjà écrites dans
   ce fichier et dans le plan : site à part, statique, routes publiques en lecture, Silooo Atelier, mises à jour
   à la main. Statut « Proposée, à valider par Kr ».
4. **Les polices par npm.** Jersey 10, Pixelify Sans et Silkscreen sont sous licence OFL et publiées en paquets
   `@fontsource/*` sur le registre npm, joignable depuis la CI, le cloud et le PC : le site les sert lui-même,
   aucun appel à un hôte tiers.

## À valider par Kr

Les textes du site s'ajoutent ici étape par étape, avec le fichier. Rien pour l'instant : `site/` n'existe pas.

## Journal

- 5 novembre 2026, 12 h 45 à 12 h 55 (direction du site, cloud). Lecture de `CLAUDE.md`, `direction.md`,
  `passation.md`, `plan-construction.md`, `site.md` (phase 0), de la charte et de `deploy/`. Vérification de
  référence sur `main` : tout vert sauf Gradle (429 de Maven Central, relancé). Écrit : `site-direction.md`,
  ce fichier, `site.md` sur `main`, ADR 0006 et son index, les lignes du site dans `docs/README.md`. Suite : S0.
