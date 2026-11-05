# Passation du site : l'état du chantier silooo.com

Dernière mise à jour : 5 novembre 2026, kit de départ du site. Tenue par la direction du site
(`docs/site-direction.md`). La direction du jeu lit la section « Pour la direction du jeu ».

## État

- Le jeu : phases 0 et 1 closes, 2 et 3 closes sauf ce qui attend Kr, phase 4 en cours (`docs/passation.md`).
  silooo-core tourne en Ktor 3.6.1 ; dernière migration sur `main` au départ : `V3__carnet.sql`.
- Le site : rien dans le dépôt avant le kit. `docs/site.md` dit quoi construire.
- Étape en cours : **S0**.

## S0 : le socle du site

- [ ] Premier tour (`docs/site-direction.md`) : kit commité, lignes ajoutées à `docs/README.md` et à `CLAUDE.md`
- [ ] ADR : le site dans silooo-core, rendu serveur, gabarits Pebble (version relevée sur Maven Central)
- [ ] ADR : les Gemmes par la boutique PayNow de Silooo et `POST /paynow/webhook`, qui remplace la règle 7 de `docs/socle.md`
- [ ] Paquet `com.silooo.core.site`, `siteRoutes(dependencies)` dans `routing { }` hors de `/v1`, `SILOOO_SITE_MODE` lu au démarrage
- [ ] Gradle copie `design/` dans les ressources (`site/design/`) ; aucun fichier de `design/` recopié à la main
- [ ] `tokens.css`, `composants.css` (table de `docs/site.md`, « Style »), `site.css` repris des maquettes
- [ ] Polices `woff2` (Jersey 10, Pixelify Sans, Silkscreen) et leurs licences OFL dans `site/static/polices/`
- [ ] Gabarit `base.peb` : en-tête, bandeau direct (vide tant que S2 n'y est pas), sol, Oooscar, pied de page avec SileneMC et Hubyy
- [ ] `site/Empreinte.kt` et son test : mêmes résultats que `provisionalXuid` de `addon/src/identity.ts` sur des vecteurs écrits dans le test
- [ ] `oooscar.js` repris de `docs/references/site/oooscar-vivant.html` (pièces SVG, réactions, nuit de 22 h à 7 h, `prefers-reduced-motion`), bulle d'arrivée par page
- [ ] Page introuvable (Oooscar « oups »), `robots.txt`, `sitemap.xml`, titres et descriptions par page
- [ ] `tools/verifier_site.mjs` : pages rendues par les tests, ouvertes dans Chromium à 360 et 1 280 px (débordement, console, `alt`, fichiers manquants), contrôle de `composants.css` ; anti-IA sur les gabarits ; branché dans `tools/verifier.sh` et la CI (dans la CI, seulement quand un fichier du site a changé ; sur un poste sans Chromium, ignoré avec un message, jamais réussi à vide)
- Fini quand : le gabarit de base s'affiche à 360 et 1 280 px sans débordement, Oooscar réagit comme dans le
  prototype au doigt et au clavier, `tools/verifier.sh` et la CI sont verts.

## S1 : la page d'attente

- [ ] Migration : `web_accounts`, `waitlist`, `news_articles`, `admin_audit` (numéro pris à la fusion)
- [ ] Sessions dans Redis sous `silooo:web:session:`, cookie `HttpOnly` `Secure` `SameSite=Lax`, jeton anti-CSRF sur chaque formulaire
- [ ] Prototype de la connexion Xbox (OAuth Microsoft `consumers`, `XboxLive.signin`, jeton utilisateur, XSTS), erreurs XSTS traitées ; si `invalid_scope`, noter l'erreur exacte pour Kr et continuer
- [ ] Connexion Discord (OAuth2, portée `identify`) ; rôle staff vérifié par le bot pour l'admin
- [ ] `/` en mode `attente` : logo, « Produis de jour comme de nuit », bouton « Me prévenir », étapes du chantier tirées des Actus « étape »
- [ ] `/connexion` : les deux portes, l'inscription, les écrans « C'est noté ! » et « compte enfant refusé » (maquette « Inscription »)
- [ ] `/actus`, `/actus/<slug>` (plan fixe : sommaire, Nouveau, Changements, Corrections)
- [ ] `/mentions-legales`, `/cgv`, `/confidentialite`, `/parents` : brouillons marqués « à relire »
- [ ] `/admin`, `/admin/actus` (écrire, prévisualiser, publier), `/admin/inscriptions` (liste, filtre, admission), journal admin
- [ ] Tests : inscription Xbox et Discord avec fournisseurs simulés, les trois erreurs XSTS, CSRF, admin refusé sans rôle
- Fini quand : sur un écran de 360 px, un inscrit par Xbox et un par Discord apparaissent dans `/admin/inscriptions`,
  et un article publié s'affiche dans les étapes de la page d'attente.

## S2 : les pages publiques

- [ ] `/site/direct.json` et le bandeau direct (joueurs en ligne, état, version, Vote party, dernier prestige), rafraîchi toutes les 30 s
- [ ] `/rejoindre` : onglets Console, Mobile, PC ; HubyOnMC pas à pas ; adresse et port 19142 avec Copier ; version acceptée
- [ ] Le Guide : champs `description` et `astuce` dans `definitions/` et leurs schémas (une phrase par usine, époque, amélioration, bâtiment), `definitions/site-faq.json`, générateur, vérification qui refuse un trou ou un tic
- [ ] `/guide`, `/guide/<sujet>` et les guides par étape dans l'ordre des déblocages
- [ ] `/classements` : `site_leaderboards` recalculées toutes les 5 min, salles du Panthéon, « le serveur en chiffres »
- [ ] `compteur.js` : cylindres qui roulent cran par cran, `aria-label` avec le nombre exact
- [ ] `/vote` et `vote-party.js` sur une interface Kotlin de votes, simulée tant que la phase 5 du jeu n'écrit pas `server_votes` ; `definitions/site-votes.json` avec Hubyy en premier
- Fini quand : une page du Guide change quand un prix change dans `definitions/` ; la jauge atteint 50 dans un test
  et la fête se joue ; les compteurs roulent ; tout passe à 360 et 1 280 px.

## S3 : l'espace joueur

- [ ] Migration : `discord_links`, `beta_access`
- [ ] `/compte` (Mon entreprise, compteur en direct depuis `ratePerHour`), `/compte/entreprise`, `/compte/gemmes`, `/compte/recompenses`
- [ ] `/compte/lier` : code de 6 caractères sans ambiguïté, 10 minutes, 5 essais ; la page se met à jour seule
- [ ] Contrat : `POST /v1/links/discord`, puis test de contrat, puis code (recette `silooo-route`)
- [ ] Add-on : Menu, Mon compte, « Relier Discord », champ du code (annoncé avant dans « Pour la direction du jeu »)
- [ ] Mode `beta` : `join` refuse un joueur absent de `beta_access`, message de l'add-on ; admission depuis `/admin/inscriptions`
- [ ] Pionnier : une ligne `player_cosmetics` (source `evenement`) au premier `join` d'un inscrit Xbox, ou à la liaison d'un inscrit Discord
- [ ] `/admin/joueurs` : recherche par gamertag ou Discord, liaison, Gemmes, entreprise
- Fini quand : un inscrit Discord relie son compte avec un code (test de bout en bout avec l'add-on simulé), un
  joueur pas admis est refusé en mode `beta`, le Pionnier n'est donné qu'une fois.

## S4 : la boutique en mode test

- [ ] `/boutique` : packs de `definitions/boutique.json`, fenêtre d'achat (gamertag, empreinte vérifiée, 10 essais par adresse IP et par heure, renonciation cochée, note aux parents)
- [ ] Commande PayNow (API publique de PayNow, documentation citée dans le code)
- [ ] `POST /paynow/webhook` : signature vérifiée, une ligne par `order_id` dans `shop_deliveries`, rien d'autre
- [ ] En mode `beta`, seul un compte staff peut payer ; `/admin/ventes` (ventes, livraisons en attente ou en échec)
- Fini quand : deux webhooks identiques ne donnent qu'une ligne, une signature fausse est refusée, et un achat de test
  du staff va du site à `shop_deliveries` sur la boutique PayNow de test (**Kr** : créer la boutique de test).

## S5 : la vitrine

- [ ] La vitrine en mode `ouvert` : « Produis de jour comme de nuit », ce qui change, captures (emplacements), Panthéon, Vote party
- [ ] `ile.js` : les six étapes du storyboard (maquette « L'île qui se construit »), scène fixe, `IntersectionObserver`, remonter défait ; sans animation, l'étape 6
- [ ] Bascule par `SILOOO_SITE_MODE=ouvert`, essayée en local seulement
- Fini quand : la vitrine se joue à 360 et 1 280 px en local, remonter défait la construction, le mode `attente` reste intact.

## Prêt à mettre en ligne

(rien encore. Une ligne par sortie prête : laquelle, le commit, ce que Kr doit avoir fait avant.)

## Pour la direction du jeu

Lu par la direction du jeu à chaque bilan ; elle répond dans son journal.

1. **Votes (phase 5)** : Kr a choisi Hubyy d'abord, au format Votifier. silooo-core reçoit Votifier v2 (jeton), écrit
   `server_votes`, tient la Vote party et les séries. Le site lit ces données ; il travaille sur des votes simulés en
   attendant.
2. **Gemmes (phase 5)** : le site écrit les commandes PayNow dans `shop_deliveries` (S4). La phase 5 relève les lignes
   non livrées, crédite une fois, publie `GEMMES_RECUES`. La ligne « livraisons de Gemmes depuis le site SileneMC » du
   plan devient « depuis le webhook PayNow de silooo-core ».
3. **Pionnier en jeu** : le site écrit `player_cosmetics` ; le titre et l'objet de déco s'affichent en jeu quand les
   cosmétiques existeront.
4. **Sessions** : la direction du site lance une session à la fois ; la direction du jeu passe à deux.
5. **À venir (S3)** : l'écran « Relier Discord » dans l'add-on et le refus des non-admis dans `join`.

## À faire par Kr

- [ ] Acheter `silooo.com` et le faire pointer vers le VPS
- [ ] Autoriser l'ajout de `silooo.com` dans le Caddy de SileneMC, vers silooo-core sur le réseau Docker
- [ ] Créer le serveur Discord Silooo, son application (connexion et bot) et le rôle staff ; poser les identifiants
- [ ] Créer l'application Azure de la connexion Xbox (comptes Microsoft personnels) ; poser ses identifiants
- [ ] Brancher Votifier sur Hubyy vers silooo-core et poser le jeton ; choisir plus tard d'autres sites de vote
- [ ] Ajouter Silooo au menu de Hubyy (troisième étape de `/rejoindre`)
- [ ] Ouvrir la boutique PayNow de Silooo en mode test, poser la clé du webhook ; plus tard, la passer en réel
- [ ] Valider les textes (section suivante), le dessin du Pionnier, les brouillons légaux
- [ ] À l'ouverture : `SILOOO_SITE_MODE=ouvert`, après avoir validé la vitrine et la bande-annonce

## Textes à valider par Kr

Déjà proposés dans le cadrage et les maquettes, à reprendre tels quels jusqu'à son avis :

- Les bulles d'Oooscar (`docs/site.md`, « Animations », deux tableaux).
- La page d'attente, l'inscription, Rejoindre, la boutique, le Panthéon, le Guide de la Scierie, l'article d'exemple
  (maquettes de `docs/references/site/maquettes/`).

## Décisions prises sans Kr

Proposées par le plan, à confirmer par Kr ; la direction du site les applique en attendant.

1. Gabarits Pebble plutôt qu'un DSL Kotlin : un gabarit se compare ligne à ligne aux maquettes.
2. La page Vote se rafraîchit toutes les 10 s, le reste du site toutes les 30 s.
3. Oooscar dort de 22 h à 7 h, à l'heure du joueur.
4. Pendant une Vote party, Oooscar dit « Production ×2 pour tout le monde ! ».

## Journal

(une entrée par fusion ou par bilan : date et heure, ce qui a changé, ce qui reste, comment vérifier)
