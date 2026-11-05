# Passation du site : l'état du chantier de silooo.com

Dernière mise à jour : 5 novembre 2026, 16 h 19, révision écrite, fichiers du site réalignés sur le kit (direction du
site, deuxième passage, en local). Tenue par la direction du site (`docs/site-direction.md`). La direction du jeu
lit « Pour la direction du jeu », la direction du Discord lit « Pour la direction du Discord ».

## État

- **Suspendu : révision en cours** (depuis le 5 novembre 2026, 15 h 48). Le site ne se met pas en ligne avant la fin
  de la révision ; la section « Suspendu » dit ce qui doit être vrai pour que « Prêt à mettre en ligne » revienne.
- **Le jeu** : phase 5 en cours (`docs/passation.md`). Dernière migration du site sur `main` : `V7__site_registrations.sql` ;
  la suivante prend le numéro libre à la fusion.
- **Le site** : les six étapes S0 à S5 de la première direction sont sur `main` (commit `69d98b1`), revues dans
  [site-revision.md](site-revision.md) : cinquante-cinq lignes, quatorze gardées, treize corrigées (`claude/site-socle`,
  `claude/site-gabarit`), treize à corriger, douze à refaire, trois posées à Kr (T1 appliquée par sa recommandation,
  décision 15 ; T2 et T3 attendent). Le kit de Kr est sur `main` : `docs/site.md` le reprend, complété par « Où en est `main` » ; l'original
  est dans `docs/references/site-kit/`.
- **Étape en cours** : `claude/site-socle` et `claude/site-gabarit` sont finies (fusionnées sur `main` après
  `tools/verifier.sh` vert) ; la suivante est `claude/site-attente`.
- **Kr** : deux questions encore ouvertes dans la révision (T2, T3) ; T1 a été appliquée par sa recommandation
  (décision 15), Kr peut la retourner. Sans réponse, la recommandation s'applique (décision 12 ci-dessous).

## Les écarts à corriger

La file d'attente, dans l'ordre des sorties du kit : la page d'attente, la bêta, l'ouverture. Les `R` renvoient aux
lignes de [site-revision.md](site-revision.md). Une case se coche quand la branche est fusionnée sur `main` et que
ses lignes sont passées à « corrigé ».

La page d'attente :

- [x] `claude/site-socle` (5 novembre 2026) : R4 (Gradle copie `design/`), R5 (`tools/verifier_site.mjs`), R7 (`robots.txt`,
  `sitemap.xml`), R8 (`Empreinte.kt` et ses vecteurs), R13 (404 avec Oooscar), R30 (débordement de
  `/wiki/personnel/`), R37 (image de partage logo et Oooscar), R2 pour moitié (la politique de sécurité admet les
  scripts du site), R3 selon T1 (`composants.css` dérivé de `bundle.css`)
- [x] `claude/site-gabarit` (5 novembre 2026) : R26 (Oooscar vivant sur chaque page, `oooscar.js`), R31 (navigation du
  kit), R32 (pied avec SileneMC et Hubyy), R33 (ciel, sol, nuages et échelle ×2 ; le dessin d'île des maquettes va
  dans `claude/site-attente`), R36 (bandeau direct et `/site/direct.json`)
- [ ] `claude/site-attente` : R34 (page d'attente de la maquette), R25 (quatre pages légales), R49 pour moitié
  (`/actus` et `/actus/<slug>` au plan fixe, lues dans des fichiers avec la marque « étape »)
- [ ] `claude/site-comptes` : R6 (mode `beta`), R15 (Xbox prototypé en premier, Discord), R16 (inscription), R17
  (migration des comptes et sessions Redis), R18 (admission par le staff, refus de `join` en bêta par le contrat),
  R19 (Pionnier côté jeu), R21 pour `/compte/lier`, R23 selon T3
- [ ] `claude/site-admin` : R20 (les cinq pages admin, rôle staff, journal), R42 (bêta : seul le staff paie), le reste
  de R49 (actus écrites dans l'admin)

La bêta :

- [ ] `claude/site-rejoindre` : R38
- [ ] `claude/site-boutique` : R39 (fenêtre d'achat, `POST /boutique/commande`), R40 (une seule page Boutique),
  R41 (l'empreinte dans `shop_deliveries`)
- [ ] `claude/site-pantheon` : R43 (les salles, `site_leaderboards`, `/site/classements.json`), R44 selon T2, R27
  (`compteur.js`)
- [ ] `claude/site-vote` : R45 (`definitions/site-votes.json`), R46 (jauge, récompenses, série), R28 (`vote-party.js`)
- [ ] `claude/site-guide` : R47 (`/guide` généré ; les champs `description` et `astuce` demandés au jeu)
- [ ] `claude/site-compte` : le reste de R21 (`/compte`, `/compte/entreprise`, `/compte/gemmes`, `/compte/recompenses`)

L'ouverture :

- [ ] `claude/site-vitrine` : R35 (la vitrine), R29 (`ile.js`)

Dans chaque branche : R50, les textes des maquettes remplacent ceux de `main` page par page, et restent « à valider
par Kr ». Fini quand chaque ligne de la révision est « gardé » ou « corrigé », sauf ce qui attend Kr.

## Ce que la première direction a livré

S0 à S5, du 5 novembre 2026, 12 h 45 à 15 h 30, dans le cloud : le module `site` dans silooo-core sur son propre
port, Silooo Atelier pour le web, les pages publiques du mode `ouvert`, le wiki de treize pages, les pionniers avec
« Relier Discord » et l'admission, le classement hebdomadaire, la fiche d'entreprise, les joueurs en ligne et le
webhook PayNow. Le détail de ce qui existe est dans `docs/site.md`, « Où en est `main` » ; ce qui en est gardé, et
pourquoi, ligne par ligne, dans [site-revision.md](site-revision.md). Les cases S0 à S5 de l'ancienne passation sont
dans l'historique git (`git show 69d98b1:docs/site-passation.md`).

## Suspendu : révision en cours

Depuis le 5 novembre 2026, 15 h 48. La première direction du site avait écrit « Prêt à mettre en ligne » pour la page
d'attente ; cette page ne suit pas le kit de Kr (R34), n'a ni inscription par Xbox ou Discord (R15, R16), ni actus
(R49), ni pages légales (R25). **Le site ne se met pas en ligne avant la fin de la révision.** Le déploiement du jeu ne
change pas : le site y reste en `attente`, son port n'est pas publié, aucun bloc Caddy n'existe.

Cette section redevient « Prêt à mettre en ligne » quand les branches `socle`, `gabarit`, `attente`, `comptes` et
`admin` sont fusionnées, que leurs lignes sont « corrigé » (sauf ce qui attend Kr : l'application Azure, l'application
Discord, les textes), et que `tools/verifier.sh` et `tools/verifier_site.mjs` sont verts. Elle dira alors le commit,
et ce que Kr doit avoir fait avant.

## Pour la direction du jeu

Lu à chaque bilan de la direction du jeu (décision 37, point 3). Réponses attendues dans son journal.

**Depuis le 5 novembre 2026, 15 h 48 : ne déployez pas le site avant la fin de la révision.** Déployer silooo-core pour
le jeu reste possible : le site y reste en `attente`, sans bloc Caddy ni variable posée. Ne demandez pas à Kr le DNS,
le bloc Caddy ni les variables du site tant que cette passation ne dit pas de nouveau « Prêt à mettre en ligne ».

**Le kit de Kr est sur `main`** : `docs/references/site-kit/` (ses trois `site*.md`), `docs/references/site/`
(maquettes, prototype d'Oooscar, captures de la révision), et `docs/site.md` qui le reprend. Votre dossier `silooo`
garde une copie non suivie de `docs/references/site/`, identique octet pour octet : supprimez-la avant de fusionner
`origin/main`, sinon git refuse d'écraser des fichiers non suivis.

1. **Les variables du site** (treize aujourd'hui, dans `deploy/exemple.env.txt` et `docs/deploiement.md`, merci) vont
   bouger avec les corrections : `SILOOO_SITE_URL_BOUTIQUE` disparaît (les Gemmes passent par la boutique PayNow de
   Silooo, kit « L'achat de Gemmes »), `SILOOO_SITE_SITES_DE_VOTE` aussi (les sites de vote vivent dans
   `definitions/site-votes.json`), `SILOOO_SITE_MODE` gagne la valeur `beta`, et des variables arrivent pour
   l'application Azure de la connexion Xbox, l'API de commande PayNow et Redis (sessions sous `silooo:web:session:`).
   Chaque branche qui en change une l'annonce ici avant sa fusion ; rien à faire d'ici là.
2. **L'admission et la preuve du gamertag.** Le kit répond à la réserve de S4 : l'inscription se fait par Xbox (le
   gamertag vient du jeton XSTS) ou par Discord suivi d'un code de six caractères tapé en jeu (le gamertag vient du
   jeu). Deux choses vous reviennent, contrat d'abord (`api/silooo-core.openapi.yaml`, puis le test de contrat, puis
   le code) : `POST /v1/links/discord` (l'add-on envoie le code tapé ; le service relie le compte) et, en mode
   `beta` du site, le refus de `session/join` pour un joueur absent de `beta_access`, avec un code `Refused` nommé
   (par exemple `PAS_ADMIS`). D'ici là, la règle `SILOOO_SITE_ADMISSION` sur `site_registrations` (V7) reste telle
   quelle. La direction du site vous écrira le dessin exact de la table avant la migration.
3. **Le Pionnier** : le kit le donne une seule fois, au premier `session/join` d'un inscrit, par une ligne de
   `player_cosmetics` de source `evenement` ; le drapeau `pioneer` de V7 ne sert plus. Le titre et l'objet de déco
   restent à dessiner (Kr).
4. **Le Guide généré** : le kit demande un champ `description` (une phrase, 160 caractères au plus) et, si utile,
   `astuce` sur chaque usine, époque, amélioration et bâtiment de `definitions/`, dans les schémas, plus
   `definitions/site-faq.json` et `definitions/site-votes.json`. `tools/verifier.sh` échouera sur une description
   manquante ou un tic d'IA. La direction du site propose de les ajouter elle-même sur une branche `claude/site-guide`,
   textes « à valider par Kr », en touchant `definitions/` et ses schémas et rien d'autre du jeu. Dites ici si vous
   préférez les écrire, ou si une tranche du jeu travaille sur ces fichiers.
5. **Ce que le site attend du jeu, plus tard** : (a) les votes (Votifier, `server_votes`, Vote party, séries, décision
   42 : `GET /v1/votes/today` existe ; le site lira la jauge et les séries par les accès du service) ; (b) les
   livraisons : le webhook écrira aussi l'empreinte du gamertag dans `shop_deliveries.xuid` (kit « L'identité des
   joueurs », décision 25) dès que `Empreinte.kt` existe, en plus du pseudo tel que PayNow l'envoie ; (c) un
   `nom` par rareté dans `definitions/personnel.json` et par abonnement dans `definitions/boutique.json` (le wiki
   et la boutique affichent les identifiants faute de nom) ; (d) `docs/base-de-donnees.md` : `site_registrations`
   (V7) y manque, ce fichier est à vous.
6. **Fichiers partagés de `core/`** : les branches du site touchent `Config.kt`, `Main.kt`, `Application.kt`
   (`Dependencies`), `libs.versions.toml`, `core/build.gradle.kts` (la copie de `design/`) et `core/README.md` par
   quelques lignes. Dites à vos tranches de ne pas réécrire ces fichiers d'un bloc, et prévenez ici si l'une d'elles
   y travaille.
7. **L'agence : `game-design.md` et `economie.md` se contredisent.** Le game design parle d'une nouvelle liste de
   candidats achetée en Donar ; le service (décision 33) et `economie.md` disent « rien à acheter ». Le wiki suit le
   service. À trancher par Kr, et le game design à mettre d'accord.
8. **Fichiers partagés touchés par `claude/site-socle`** (5 novembre 2026) : `core/build.gradle.kts` (Gradle copie
   `design/` dans les ressources du site et dérive `composants.css` ; `SILOOO_SITE_RENDER_DIR` posé pour les tests),
   `core/Dockerfile` et `core/Dockerfile.dockerignore` (`design/` entre dans le contexte et dans l'étape de construction,
   sans ses `.md`, `tokens.json` ni `decor/`), `package.json` et `package-lock.json` de la racine (`playwright@1.62.1` en
   devDependency exacte), `.github/workflows/ci.yml` (une étape `npm ci` puis `npx playwright install --with-deps chromium`
   avant le verifier), `tools/verifier.sh` (étape « Site : pages dans Chromium ») et `tools/tests/run.sh` (ses tests). Sur
   votre poste, `bash tools/verifier.sh` dit « IGNORÉ Site : pages dans Chromium » tant que `npx playwright install
   chromium` n'a pas tourné ; la CI, elle, joue l'étape.
9. **Ce que `claude/site-gabarit` touche et attend** (5 novembre 2026). Deux variables de plus, facultatives, déjà
   dans le modèle d'environnement de `deploy/` et dans `docs/deploiement.md` : `SILOOO_SITE_URL_SILENEMC` et
   `SILOOO_SITE_URL_HUBYY` (les liens « Voir aussi » du pied de page ; sans elles, pas de lien). À poser sur le VPS
   quand Kr voudra les liens. Fichiers partagés : `Config.kt` (les deux
   constantes et `siteSettings()`), `core/build.gradle.kts` (Gradle copie aussi `design/decor/`, le sol de la scène),
   `core/Dockerfile.dockerignore` (`decor/` entre dans l'image). Le bandeau direct lit la présence et les votes du jour ;
   `version` (la version de Bedrock) et `dernierPrestige` restent à `null` : si le signal de vie du serveur portait la
   version, et si le dépôt des prestiges offrait une lecture du dernier, le bandeau les montrerait sans autre changement
   (`SiteDirect.kt`). Rien d'urgent : le bandeau cache ce qu'il n'a pas.

## Pour la direction du Discord

Lu par la direction du Discord à chaque bilan (`docs/discord-passation.md`, « Pour la direction du site »). Réponses
à vos quatre points :

1. **La liaison** : d'accord. Le site affiche « relié » dès l'OAuth2 ; « prouvé » vient du jeu, par le code tapé en
   jeu (`POST /v1/links/discord`, demandé à la direction du jeu). Le bot donne le pseudo et les rôles après ce code.
2. **La case « Rejoindre aussi le Discord »** : la portée `guilds.join` est demandée seulement quand la case est
   cochée (deux adresses d'autorisation), `identify` seul sinon. Question T3 de `docs/site-revision.md`, posée à Kr.
3. **Les textes** : le site publiera les règles des tirages et la page Confidentialité (journal de salon gardé trente
   jours) dans `claude/site-attente` (R25). Envoyez vos brouillons par votre passation ; en attendant, le site écrit
   une phrase « à compléter par la direction du Discord ».
4. **Les actus** : une actu publiée depuis l'admin du site (R49) deviendra un événement du service pour le bot
   (décision 43 : le service publie, le bot écoute), avec un champ « aussi dans #annonces ». Le dessin de
   l'événement vous sera proposé ici avant la branche `claude/site-admin`.

## À faire par Kr

- [ ] Répondre aux trois questions de [site-revision.md](site-revision.md) (T1 socle technique, T2 classement
  hebdomadaire, T3 portée Discord) ; sans réponse, la recommandation s'applique
- [ ] Relire l'[ADR 0006](adr/0006-site-silooo-com.md) (statut « Proposée »), que `claude/site-socle` amende selon T1
- [ ] Le DNS de `silooo.com`, `www` et `play.silooo.com` vers l'IP du VPS ; ouvrir le port 19142 en UDP et TCP
- [ ] Le bloc `silooo.com` dans le Caddy de SileneMC (`/var/www/minecraft`, qu'aucune session Silooo ne touche) :
  mandat vers `silooo-core:8081` sur le réseau `silenemc`, TLS automatique
- [ ] Créer le serveur Discord Silooo, son application (connexion et bot) et le rôle staff ; poser les identifiants
- [ ] Créer l'application Azure de la connexion Xbox (comptes Microsoft personnels) ; poser ses identifiants
- [ ] Brancher Votifier sur Hubyy vers silooo-core et poser le jeton ; choisir plus tard d'autres sites de vote
- [ ] Ajouter Silooo au menu de Hubyy (troisième étape de `/rejoindre`)
- [ ] Ouvrir la boutique PayNow de Silooo en mode test, poser la clé du webhook ; confirmer le format exact du webhook
  (le site suppose `PayNow-Signature` en HMAC-SHA256 hexadécimal du corps et les champs `order_id`, `gamertag`,
  `gemmes`, `core/.../site/PayNowWebhook.kt`) ; plus tard, la passer en réel
- [ ] L'identité légale (micro-entreprise, OVH) et la relecture des quatre brouillons légaux ; les textes de chaque
  page (section « À valider par Kr ») ; le dessin du Pionnier ; les captures et la bande-annonce de la vitrine
- [ ] Le relevé de `skyblock.rinaorc.com` (grille de [site.md](site.md), « La référence »), depuis le PC : le domaine
  n'est pas joignable depuis les sessions
- [ ] À l'ouverture : `SILOOO_SITE_MODE=ouvert` dans le fichier d'environnement du VPS, après la vitrine validée

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

12. **Le deuxième passage (5 novembre 2026, 15 h 30 à 16 h 19).** Le kit de Kr l'emporte sur les choix de la première
    direction (ordre du brief de Kr : ce qu'il dit en session, puis `docs/passation.md`, puis le kit, le cadrage et les
    maquettes, puis la première direction) ; ce qui marche et ne contredit rien est gardé. Les verdicts sont dans
    `docs/site-revision.md`. Les trois questions T1 (kotlinx.html et `atelier.css` contre Pebble et `composants.css`),
    T2 (le classement hebdomadaire comme sixième salle du Panthéon) et T3 (`guilds.join` seulement quand la case est
    cochée) attendent Kr ; sans réponse au moment de la branche concernée, la recommandation de la révision
    s'applique et la ligne le dit.
13. **Les fichiers réalignés.** `docs/site.md` est le kit, précédé d'un paragraphe de deuxième passage et suivi de
    « Où en est `main` » et de la grille rinaorc de la première direction ; `docs/site-direction.md` est le kit adapté
    au travail en local ; cette passation remplace les cases S0 à S5 par la file d'attente des écarts. Les quatre
    décisions du kit sont reprises : Pebble (sous T1), la page Vote rafraîchie toutes les 10 s et le reste toutes les
    30 s, Oooscar qui dort de 22 h à 7 h à l'heure du joueur, la bulle « Production ×2 pour tout le monde ! » pendant
    une Vote party.
14. **Les captures comme preuves.** Seize captures JPEG du site rendu en local sont commises dans
    `docs/references/site/captures-revision/` (1,1 Mo) ; les quatre-vingt-huit captures complètes se régénèrent avec
    `SiteRenderToDisk` et Chromium, ce que `tools/verifier_site.mjs` (R5) outillera.
15. **T1 appliquée par sa recommandation** (5 novembre 2026, `claude/site-socle`, sans réponse de Kr). kotlinx.html
    reste le moteur des pages (le gabarit est une fonction, Pebble se greffe plus tard si Kr le demande) ;
    `composants.css` est dérivé de `design/composants/bundle.css` par Gradle, table `atelierBlobs` de
    `core/build.gradle.kts` reprise dans `docs/site.md` (les identifiants `/_blob/` ne sont pas des sommes SHA-256 des
    textures, vérifié : la table s'écrit, elle ne se calcule pas) ; `atelier.css` ne garde que les polices, la base, les
    réglages que le web demande aux composants et la mise en page, et `SiteAtelierTest` refuse qu'il redéfinisse un
    `border-image`. Les trente-deux copies de `design/` quittent `core/` : Gradle les pose, l'image Docker reçoit `design/`.
16. **Playwright à la racine, Chromium en CI, l'image de partage commise.** `playwright@1.62.1` (version exacte, celle
    déjà présente sur le poste de Kr) est une devDependency du `package.json` de la racine ; `tools/verifier_site.mjs`
    l'importe, la CI installe Chromium (`npx playwright install --with-deps chromium`), et sur un poste sans Chromium
    l'étape de `verifier.sh` est ignorée en nommant la commande. L'image de partage est un PNG commis
    (`site/public/partage.png`), refait à la main avec `tools/partage/capturer.mjs` : Chromium n'entre pas dans Gradle.
17. **Le gabarit, tranché par la branche `claude/site-gabarit`** (5 novembre 2026). Les mots de la navigation sont ceux
    du kit, posés sur les adresses qui existent : Guide mène à `/wiki/`, Actus à `/mises-a-jour/`, Mon compte et Me
    prévenir à `/pionniers/` en bouton de fonte, jusqu'aux branches des comptes et de l'attente qui leur donneront leurs
    pages. `GET /site/direct.json` est une route du site, hors du contrat OpenAPI (le contrat ne décrit que `/v1`, et
    `ContractTest` envoie chaque opération au module de l'API) ; le bandeau n'existe qu'en mode ouvert, en attente aucun
    serveur ne bat. L'échelle ×2 des maquettes s'applique sous 700 px, dans `atelier.css` et non dans le bundle (c'est
    une règle des maquettes, pas de la charte). La bulle de l'attente est celle de la maquette, « On construit. Reviens
    vite ! », à la place de « Le serveur ouvre bientôt ! ». Oooscar fait 120 px de haut sur téléphone et 150 sur
    ordinateur, dans une scène de 196 et 240 px (le prototype faisait 190 et 250 dans une page vide) ; son ombre est
    une bande plate (la charte refuse l'arrondi). Les textes des réactions vivent en Kotlin (`pages/Oooscar.kt`), posés
    sur `data-textes`, pour que `tools/anti_ia.py` les relise ; les scripts n'en contiennent aucun. La nuit (22 h à 7 h,
    heure du joueur) endort Oooscar sans changer le ciel : le ciel de nuit des maquettes attend un dessin.

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

- Le kit : les bulles d'Oooscar (`docs/site.md`, « Animations ») ; la page d'attente, l'inscription, Rejoindre, la boutique,
  le Panthéon, le Guide de la Scierie, l'article d'exemple (maquettes de `docs/references/site/maquettes/`) : à reprendre
  tels quels jusqu'à son avis.

- `claude/site-socle` (5 novembre 2026) : `pages/Erreur.kt`, la page introuvable : « Page introuvable », la bulle
  d'Oooscar « Cette page s'est envolée. » (le texte du kit), le bouton « Retour à l'accueil », la description « Cette
  adresse ne mène à rien. » ; `pages/Attente.kt`, l'`alt` du visage oups « Oooscar, la mascotte de Silooo, surpris » ;
  `pages/Gabarit.kt`, l'`alt` de l'image de partage « Le logo de Silooo et Oooscar, sa mascotte, sur le ciel » ;
  l'image elle-même, `site/public/partage.png` (le logo, « SILOOO » sur une plaque, Oooscar joie et sa bulle « Salut !
  Moi, c'est Oooscar. », `tools/partage/partage.html`), à regarder dans un aperçu Discord.
- `claude/site-gabarit` (5 novembre 2026) : `pages/Oooscar.kt`, les bulles d'arrivée « On construit. Reviens vite ! »
  (attente, à la place de « Le serveur ouvre bientôt ! »), « Salut ! Moi, c'est Oooscar. », « Je t'attends sur ton
  île ! », « Les Gemmes arrivent en jeu. », « Moi, je vise la salle 1. », « Réessaie dans un moment. » (erreur 500),
  « Niveau N : production ×2 ! » (guide, N lu dans `economie.json`) ; les réactions « Hé, ça chatouille ! », « Encore
  une fois ! », « Doucement, je suis en tôle. », « Ta Scierie t'attend ! », « Mon toit glisse, là. », « Tu me
  cherches ? », « Mon toit ! », « Comme neuf. », « Je remets mon toit. », « Repose-moi ! », « Ouille. », « Salut ! »,
  « Je dormais pas ! », « Les usines tournent. Moi, je dors. », « Production ×2 pour tout le monde ! » ; les `alt` des
  cinq visages (« Oooscar, la mascotte de Silooo, content / fier / surpris / affolé / endormi »). `pages/Gabarit.kt` :
  « Menu », « Discord Silooo », « Mentions légales », « Voir aussi : », et les mots du bandeau « en ligne », « Ouvert »,
  « Fermé », « Bedrock », « Vote party ». `pages/Navigation.kt` : « Guide », « Actus », « Mon compte », « Me prévenir ».
  `pages/Erreur.kt` : la description de la 500 « Le site a eu un problème. ». La scène elle-même (sol, nuages, Oooscar
  qui saute, dort et se laisse attraper) est à regarder sur `http://localhost:8081/` ou dans les captures.

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
- 5 novembre 2026, 15 h 48 à 16 h 19 (direction du site, deuxième passage, en local). Le site rendu en local dans les
  deux modes (44 pages), captures Chromium à 360 et 1 280 px (88 ; une seule erreur : `/wiki/personnel/` déborde à
  1 280), maquettes relues. Écrit `docs/site-revision.md` (55 lignes, 14 gardées, 24 à corriger, 13 à refaire, 4 posées
  à Kr), seize captures commises, puis `docs/site.md`, `docs/site-direction.md` et cette passation réalignés sur le
  kit. Envoyé la révision à Kr avec les trois questions. Suite : `claude/site-socle` (R4, R5, R7, R8, R13, R30, R37,
  R2 pour moitié, R3 selon T1).
- 5 novembre 2026, 16 h 38 à 17 h 08 (direction du site, deuxième passage, en local). `claude/site-socle` : Gradle copie
  `design/` et dérive `composants.css` (T1 par sa recommandation, décision 15), `atelier.css` réduit aux réglages du web
  et à la mise en page, `robots.txt` et `sitemap.xml`, `Empreinte.kt` et ses vecteurs, la 404 avec Oooscar oups, l'image
  de partage (`tools/partage/`), `script-src 'self'`, le tableau des raretés qui ne déborde plus, `tools/verifier_site.mjs`
  (étape du verifier, tests de l'outillage, Chromium en CI, décision 16). Tests Gradle verts en local (SQL ignorés sans
  Docker), 44 pages à 360 et 1 280 px sans écart, captures relues (404, personnel, attente, boutique). Fusion sur `main`
  après `tools/verifier.sh` complet ; les deux commits de documentation de `claude/site-revision` partent avec elle.
  Suite : `claude/site-gabarit`.
- 5 novembre 2026, 17 h 18 à 18 h 19 (direction du site, deuxième passage, en local ; trois agents Sonnet en parallèle
  pour les scripts, la feuille de style et les tests, le Kotlin, les docs et la relecture par la direction).
  `claude/site-gabarit` : le gabarit des maquettes (menu replié sous 700 px, navigation par mode, bandeau direct en
  mode ouvert, scène d'Oooscar sur le sol de chaque page, pied sombre avec SileneMC et Hubyy), `oooscar.js` d'après le
  prototype (le SVG articulé, les réactions, la nuit, `prefers-reduced-motion`), `menu.js`, `direct.js` et
  `GET /site/direct.json`, l'échelle ×2 sous 700 px, deux variables facultatives (décision 17 ; point 9 pour la
  direction du jeu). R26, R31, R32, R33 (sauf le dessin d'île), R36 corrigés. Tests Gradle verts en local (SQL ignorés
  sans Docker), 44 pages à 360 et 1 280 px sans écart, captures relues (attente, accueil ouvert, 404), menu replié et
  réveil d'Oooscar vérifiés dans un navigateur. `tools/verifier.sh` vert au second passage (au premier, les deux
  variables manquaient au modèle d'environnement de `deploy/`). Rebase sur `origin/main` (le Discord D2 avait avancé),
  fusion sur `main`. Suite : `claude/site-attente`.
