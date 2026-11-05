# Révision du site : ce qui est sur `main` contre ce que Kr a décidé

Dernière mise à jour : 5 novembre 2026, 20 h 42 (direction du site, deuxième passage, en local sur le PC de Kr) : les
verdicts « corrigé » nomment la branche qui a corrigé.

La première direction du site (dans le cloud) a livré S0 à S5 sur `main` sans lire le kit de Kr. Ce fichier compare
ce qu'elle a fait à ce que Kr a décidé, écart par écart, avec une preuve et un verdict. Il se lit avec
[site-passation.md](site-passation.md), qui suit chaque écart en case à cocher et tient le journal.

## Comment lire

**Ce que Kr a décidé**, dans l'ordre où ça tranche un conflit : ce que Kr dit en session ; les décisions 37 et
suivantes de [passation.md](passation.md) ; le kit ([references/site-kit/site.md](references/site-kit/site.md),
le cadrage, le plan de construction, les maquettes de [references/site/maquettes/](references/site/maquettes/),
[references/site/oooscar-vivant.html](references/site/oooscar-vivant.html)) ; enfin les choix de la première
direction (son [site.md](site.md), l'[ADR 0006](adr/0006-site-silooo-com.md), « Décisions prises sans Kr » de la
passation). Ce qui marche et ne contredit rien est gardé.

**Ce qui est sur `main`** : les commits S0 à S5 fusionnés, revus au commit `69d98b1`. Le site a été rendu en local
dans les deux modes (le test `SiteRenderToDisk` avec `SILOOO_SITE_RENDER_DIR`, plus un rendu jetable des pages des
pionniers et de la 404), puis capturé par Chromium à 360 et 1 280 px : 44 pages, 88 captures. Seize d'entre elles,
celles qui servent de preuve, sont dans [references/site/captures-revision/](references/site/captures-revision/).
La seule erreur relevée par Chromium : `/wiki/personnel/` déborde de 64 px à 1 280 (R30).

**Les verdicts** : `gardé` (rien à faire), `à corriger` (la pièce existe, on la rapproche du kit), `à refaire`
(la pièce manque ou contredit le kit), `à trancher par Kr` (deux lectures possibles, avec ma recommandation).
Une ligne `à corriger` ou `à refaire` devient `corrigé` quand sa branche `claude/site-<sujet>` est fusionnée ; la
case correspondante de la passation se coche alors.

## Le bilan en une page

`main` porte un site de lecture, propre et testé : une page d'attente et les mentions ; en mode ouvert, huit pages
(accueil, rejoindre, pionniers, wiki de treize pages, classement hebdomadaire des Lingots, boutique, vote, mises
à jour), une fiche d'entreprise, le webhook PayNow, une inscription de pionnier par gamertag tapé avec un compte
Discord relié en option. Tout est rendu en Kotlin, sans JavaScript (la politique de sécurité interdit tout script),
avec Silooo Atelier recopié à la main dans `core/`.

Kr a décidé autre chose sur plusieurs points qui se voient : deux portes de compte (Xbox, Discord) avec preuve du
gamertag, un espace joueur, un panneau admin, des actus écrites dans l'admin, quatre pages légales, un bandeau
direct sur chaque page, un Panthéon en salles, une page de vote avec la jauge et la série, un Guide généré depuis
`definitions/`, une boutique avec fenêtre d'achat et commande PayNow, quatre animations par pas (Oooscar vivant,
compteurs, jauge, île), et une vitrine à l'ouverture. Rien de tout cela n'est sur `main`.

| Verdict | Lignes |
| --- | --- |
| gardé | 14 |
| à corriger | 24 |
| à refaire | 13 |
| à trancher par Kr | 4 (trois questions) |

Les trois questions à trancher sont rassemblées à la fin, avec ma recommandation. Si Kr ne répond pas, la
recommandation s'applique et se note dans « Décisions prises sans Kr » de la passation.

## A. Le socle et l'outillage

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R1 | Le kit rend les pages avec des gabarits Pebble (`site/gabarits/*.peb`, `base.peb` pour l'en-tête, le bandeau, le sol, Oooscar, le pied). `main` rend tout en Kotlin avec kotlinx.html, et l'ADR 0006 écrit « pas de moteur de gabarits ». | `core/.../site/pages/Gabarit.kt`, `docs/adr/0006-site-silooo-com.md` ligne 35, kit « Gabarits et mode du site » | à trancher par Kr (T1) |
| R2 | Le kit a cinq scripts (`site.js`, `oooscar.js`, `compteur.js`, `vote-party.js`, `ile.js`) branchés sur des attributs `data-` ; `main` n'a aucun fichier `.js` et sa politique de sécurité pose `script-src 'none'`. Sans script, aucune des quatre animations de Kr n'existe. | `core/.../site/SiteModule.kt` ligne 31 ; `find core/src/main/resources/site -name '*.js'` : rien | à refaire ; la politique admet déjà `script-src 'self'` (`claude/site-socle`), les scripts viennent avec `claude/site-gabarit` et les branches suivantes |
| R3 | Le kit dérive `composants.css` de `design/composants/bundle.css` (table des seize `/_blob/`), à côté de `tokens.css` et `site.css`, et un contrôle vérifie que les deux fichiers ne diffèrent que par ces adresses. `main` a un `atelier.css` de 17 Ko écrit à la main. | `core/src/main/resources/site/public/atelier.css`, kit « Style » | corrigé selon la recommandation de T1 (`claude/site-socle`, décision 15 de la passation) : `composants.css` dérivé de `bundle.css` par Gradle, `atelier.css` réduit aux réglages du web et à la mise en page, kotlinx.html gardé ; Kr peut encore trancher autrement |
| R4 | Le kit fait copier `design/` par Gradle à la construction : aucune texture ni icône recopiée à la main. `main` a trente PNG recopiés dans `site/public/atelier/`, avec un test qui compare les copies. | `core/src/main/resources/site/public/atelier/`, `core/src/test/.../site/SiteAtelierTest.kt` | corrigé (`claude/site-socle`) : Gradle copie `design/` dans les ressources, les trente-deux copies sont retirées de `core/` |
| R5 | Le kit demande `tools/verifier_site.mjs` (Chromium à 360 et 1 280 px : débordement, erreur console, image sans `alt`, fichier manquant ; sauté avec un message sans Chromium, toujours en CI). Rien de tel sur `main` : `tools/verifier.sh` ne connaît pas le site. | `tools/verifier.sh`, `tools/` ; le débordement R30 l'aurait arrêté | corrigé (`claude/site-socle`) : `tools/verifier_site.mjs`, étape « Site : pages dans Chromium » de `verifier.sh`, Chromium installé par la CI |
| R6 | Le kit a trois modes `attente`, `beta`, `ouvert` (la bêta montre tout sauf la vitrine et réserve le paiement au staff). `main` n'en a que deux. | `core/.../site/SiteMode.kt` lignes 8 à 10, ADR 0006 ligne 29 | corrigé (`claude/site-comptes`) : `SiteMode.BETA`, la navigation, les contenus (`modes:` des `.md`) et les routes par mode, `SILOOO_SITE_MODE=beta` ; en bêta, `/` reste la page d'attente |
| R7 | `robots.txt` et `sitemap.xml` (S0 du plan) manquent : les routes sont les pages et `site/public`. | `core/.../site/SiteRoutes.kt` lignes 39 à 45 | corrigé (`claude/site-socle`) |
| R8 | L'empreinte FNV-1a 64 bits du gamertag (`site/Empreinte.kt`, vecteurs de test pris sur `addon/src/identity.ts`, décision 25) manque. Elle sert à la boutique, à la liaison Discord et à la connexion Xbox pour retrouver un joueur dans `players`. | aucun fichier `Empreinte.kt` dans `core/.../site/`, kit « L'identité des joueurs » | corrigé (`claude/site-socle`) : `site/Empreinte.kt`, vecteurs calculés avec `addon/src/identity.ts` dans `EmpreinteTest` |
| R9 | Le second serveur Ktor sur `SILOOO_SITE_PORT`, les en-têtes de sécurité, `/v1` hors d'Internet : conforme à la décision 37 et au kit. | `core/.../site/SiteModule.kt`, ADR 0006 | gardé |
| R10 | Les contenus Markdown livrés dans le jar avec un bloc rendu par le code à la place d'un paragraphe `{{nom}}`, la liste `SiteContent.BUNDLED` comparée au dossier par un test : rien dans le kit ne l'interdit, et le Guide généré (R33) s'y branche. | `core/.../site/SiteContent.kt`, `SiteContentTest.kt` | gardé |
| R11 | Les polices Jersey 10, Pixelify Sans et Silkscreen en `woff2` sous licence OFL, auto-hébergées, aucun appel vers un autre hôte : ce que le kit demande. | `core/src/main/resources/site/public/` (quatre `woff2`), passation « Décisions prises sans Kr » point 4 | gardé |
| R12 | `tokens.css` est une copie de `design/tokens.css`, vérifiée par un test. | `core/src/main/resources/site/public/atelier/tokens.css`, `SiteAtelierTest.kt` | gardé |
| R13 | La page 404 : « Cette adresse ne mène à rien. », sans Oooscar. Le plan S0 la prévoit avec Oooscar et sa bulle, comme chaque page. | `core/.../site/pages/Erreur.kt` lignes 8 à 11, capture `introuvable-360.jpg` | corrigé (`claude/site-socle`) : Oooscar « oups » et « Cette page s'est envolée. » |
| R14 | Les tests existants (`SitePublicPagesTest`, `SiteStyleTest`, `SiteWikiTest`, `PioneerRoutesTest`, `PayNowWebhookTest`, `SiteStatsTest`, `SiteRenderToDisk`…) passent et couvrent ce qui est là. Ils suivent chaque correction, aucun n'est affaibli. | `core/src/test/kotlin/com/silooo/core/site/` | gardé |

## B. Les comptes, l'inscription, le Pionnier, l'admin

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R15 | Le kit ouvre deux portes de compte : Xbox (OAuth Microsoft, portée `XboxLive.signin`, puis XSTS ; comptes enfants refusés avec un texte pour les parents ; à prototyper en premier) et Discord (OAuth2 `identify`, puis un code de six caractères tapé en jeu, dix minutes, cinq essais). `main` n'a ni `/connexion`, ni session, ni `/compte/lier` : seulement un formulaire de pionnier. | `core/.../site/PioneerRoutes.kt`, capture `pionniers-360.jpg`, kit « Les comptes » | corrigé (`claude/site-comptes`) : `/connexion/` et ses deux portes (`site/AccountRoutes.kt`) ; Xbox prototypé d'abord avec `FakeXboxIdentity`, puis `registration/HttpXboxIdentity.kt` (OAuth Microsoft `XboxLive.signin`, jeton Xbox Live puis XSTS, les trois refus de Microsoft nommés, dont le compte enfant avec la page pour les parents) ; Discord avec le code de six caractères de `/compte/lier/` (dix minutes, un code vivant par compte, `registration/DiscordLinks.kt`) ; sessions de trente jours. L'application Azure reste à Kr : sans `SILOOO_XBOX_CLIENT_ID`, la porte dit « arrive bientôt » |
| R16 | L'inscription d'avant l'ouverture : le kit la fait par Xbox ou Discord, bouton « Me prévenir » vers `/connexion`, avec la promesse de la bêta et du Pionnier (maquette SiteInscription : « Avec Xbox / Avec Discord », « C'est noté ! », « Microsoft a dit non »). `main` demande un gamertag tapé et une case « J'ai lu les règles », sans preuve que le gamertag est au joueur. La passation le signalait elle-même (« Pour la direction du jeu » point 4, « À faire par Kr » ligne 3) ; la direction du Discord le confirme (discord-passation, « Pour la direction du site » point 1 : la preuve vient du jeu). | capture `pionniers-360.jpg`, `pionniers-etat-360.jpg`, `PioneerRoutes.kt` ligne 147 | corrigé (`claude/site-comptes`) : `PioneerRoutes.kt`, le gamertag tapé et la case des règles disparaissent ; `/pionniers/` montre les deux portes avec la promesse (`pages/Connexion.kt`), « C'est noté ! » et « Microsoft a dit non » suivent la maquette SiteInscription ; le gamertag vient du jeton XSTS ou du jeu ; textes à valider par Kr |
| R17 | Les données : le kit ajoute `web_accounts`, `discord_links`, `waitlist`, `beta_access`, `news_articles`, `admin_audit`, `site_leaderboards` et des sessions Redis sous `silooo:web:session:` (cookie 30 jours, `HttpOnly`, `Secure`, `SameSite=Lax`, jeton anti-CSRF). `main` a la seule table `site_registrations` (V7) et un cookie signé d'une heure. V7 reste (une migration appliquée ne se modifie pas) ; la suite arrive par une migration V9 ou suivante, numéro pris à la fusion. | `core/src/main/resources/db/migration/V7__site_registrations.sql`, kit « Données à ajouter », « Sessions » | corrigé (`claude/site-comptes`) : migration V11 (`web_accounts`, `waitlist`, `discord_links`, `beta_access`, `web_sessions`) ; les sessions vivent dans la table `web_sessions` et non dans Redis (décision 19 de la passation) : cookie `silooo_session` de trente jours, `HttpOnly`, `Secure` derrière https, `SameSite=Lax`, la base ne garde que l'empreinte SHA-256 du jeton, le jeton anti-CSRF est celui de la session ; `news_articles`, `admin_audit` et `site_leaderboards` viennent avec leurs branches |
| R18 | L'admission en bêta : le kit la fait décider par le staff depuis `/admin/inscriptions` et fait refuser `session/join` à un joueur hors de `beta_access` en mode `beta` (contrat OpenAPI d'abord). `main` admet par une règle automatique `SILOOO_SITE_ADMISSION` (`discord` ou `tous`), lue par le jeu à la connexion. La règle automatique reste utile tant que l'admin n'existe pas, puis disparaît. | `core/.../registration/SiteRegistrations.kt`, `Config.kt`, kit « La page d'attente, la bêta et le Pionnier » | corrigé pour moitié (`claude/site-comptes`) : `beta_access` existe et la règle `SILOOO_SITE_ADMISSION` y écrit (`admitted_by` = `regle:<mot>`) en même temps que `site_registrations` ; l'admission par le staff vient avec `claude/site-admin`, le refus de `session/join` en bêta reste à la direction du jeu (contrat d'abord) |
| R19 | Le Pionnier : titre et objet de déco, donnés une seule fois au premier `session/join` d'un inscrit (source `evenement` de `player_cosmetics`, côté jeu). `main` a un drapeau `pioneer` toujours vrai dans V7, même après l'ouverture (la passation le note), et rien côté cosmétiques. Le dessin du Pionnier reste « à valider par Kr ». | passation « Décisions prises sans Kr » point 10, kit idem | corrigé pour moitié (`claude/site-comptes`) : le site ne note le Pionnier qu'à une inscription d'avant l'ouverture (`waitlist`, `site_registrations.pioneer` faux en mode `ouvert`) et `waitlist.pioneer_given_at` attend que le jeu l'ait donné ; la ligne `player_cosmetics` au premier `session/join` reste au jeu, le dessin à Kr |
| R20 | Le panneau admin (`/admin`, `/admin/actus`, `/admin/ventes`, `/admin/joueurs`, `/admin/inscriptions`), ouvert par Discord aux comptes qui ont le rôle staff, chaque action journalisée ; maquette SiteAdmin. Rien sur `main`. | `Navigation.kt` lignes 19 à 27 (aucune route admin), kit « Admin » | à refaire |
| R21 | L'espace joueur (`/compte`, `/compte/lier`, `/compte/entreprise`, `/compte/gemmes`, `/compte/recompenses` ; maquettes SiteCompte, SiteLier). Rien sur `main`. | kit « Les routes », maquettes | corrigé pour `/compte/lier/` (`claude/site-comptes`, `pages/Compte.kt` : le code en six cases, le temps qu'il reste, les trois étapes en jeu, « Actualiser », « Nouveau code », « Relié ! », « Délier », maquette SiteLier) et un `/compte/` réduit (compte du jeu, Discord, Pionnier, déconnexion) ; `/compte/entreprise`, `/compte/gemmes`, `/compte/recompenses` et le vrai `/compte` avec `claude/site-compte` |
| R22 | La fiche publique d'entreprise `/entreprise/{id}/` (époque, étoiles, prestiges, membres) n'est pas dans le kit mais ne contredit rien : elle sert de cible aux liens du Panthéon. | `core/.../site/StatsRoutes.kt` lignes 55 à 61, capture `ouvert-vivant-entreprise-1-360.jpg` | gardé |
| R23 | La porte Discord de `main` (`/pionniers/discord/`, `/pionniers/discord/retour/`, fournisseur `DiscordIdentity`, faux fournisseur de test, état OAuth2 dans le cookie, aucun identifiant Discord dans une page ni un journal) est saine : elle devient `/connexion/discord` du kit. La case « Rejoindre aussi le Discord » demande la portée `guilds.join` en plus de `identify` (discord-passation, point 2), là où le kit dit `identify` seulement. | `PioneerRoutes.kt` lignes 67 à 116, `docs/discord-passation.md` « Pour la direction du site » | corrigé selon la recommandation de T3 (`claude/site-comptes`, décision 19 de la passation) : la porte devient `/connexion/discord/` (`identify` seul) et `/connexion/discord/?rejoindre=oui` (`identify guilds.join`, quand la case est cochée) ; le site appelle `DiscordGuildJoiner.join`, que la direction du Discord branche ; Kr peut encore trancher autrement |
| R24 | Le signataire HMAC et la limite de débit (cinq essais par minute et par adresse) sont réutilisables tels quels pour les sessions, les jetons anti-CSRF et la fenêtre d'achat (dix essais par heure). | `core/.../site/SiteSigner.kt`, `RateLimiter.kt` | gardé |
| R25 | Les pages légales : le kit en veut quatre (`/mentions-legales` avec OVH et la micro-entreprise de Kr, `/cgv` avec la case L221-28 et le consentement parental, `/confidentialite` avec ce que le Discord garde : journal de salon trente jours, `/parents`), brouillons à faire relire par Kr. `main` a une seule page `/mentions/` dont trois sections disent « À compléter par Kr. ». | `core/src/main/resources/site/contenu/mentions.md`, capture `mentions-360.jpg`, discord-passation point 3 | corrigé (`claude/site-attente`) : quatre pages de lecture rendues par `Legal.kt` (`contenu/mentions-legales.md`, `cgv.md`, `confidentialite.md`, `parents.md`), brouillons à relire par Kr (site-passation, « À valider par Kr ») |

## C. Les animations

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R26 | Oooscar vivant : sur chaque page, en bas, avec une bulle de six mots au plus propre à la page, des réactions par pas (table d'`oooscar-vivant.html`), `prefers-reduced-motion` respecté. `main` montre un seul PNG fixe (`oooscar-joie.png`) sur la page d'attente, et rien sur les autres pages. | `core/.../site/pages/Attente.kt` lignes 13 à 38, captures `accueil-360.jpg` (présent) et `ouvert-vivant-360.jpg`, `mentions-360.jpg` (absent) | corrigé (`claude/site-gabarit`) : la scène du gabarit sous chaque page, `oooscar.js` d'après le prototype, les textes en Kotlin sur `data-textes` |
| R27 | Les compteurs qui roulent (`compteur.js`) : Panthéon « en chiffres », étape 4 de l'île. Aucun chiffre ne roule sur `main`. | kit « Les compteurs qui roulent », R2 | à refaire |
| R28 | La jauge de la Vote party (`vote-party.js`, la fête à l'objectif atteint) : `main` n'a qu'une phrase (« Quand le serveur atteint l'objectif de votes… »). | `core/.../site/pages/Vote.kt` ligne 17, capture `ouvert-complet-vote-360.jpg` | à refaire |
| R29 | L'île qui se construit (`ile.js`, six étapes, scène collante à droite sur ordinateur, textes de l'étape 6 tirés de `definitions/economie.json`) : la vitrine n'existe pas sur `main` (R35). | kit « L'île qui se construit », maquette SiteIle | à refaire |

## D. Le gabarit, la page d'attente, la vitrine

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R30 | Le tableau des raretés de `/wiki/personnel/` déborde de 64 px à 1 280 px (cinq colonnes dans un panneau de 640 px). | capture `ouvert-wiki-personnel-1280.jpg`, journal des captures | corrigé (`claude/site-socle`) : les en-têtes se coupent au mot, plus de `nowrap` ; 0 écart sur 44 pages dans Chromium |
| R31 | La navigation : le kit et les maquettes ont Rejoindre, Boutique, Classements, Vote, Guide, Actus, Mon compte (ouvert) et Actus, Discord, Me prévenir (attente). `main` aligne neuf boutons (Accueil, Rejoindre, Pionniers, Wiki, Classements, Boutique, Vote, Mises à jour, Mentions) qui prennent plus de la moitié de l'écran d'un téléphone avant le premier contenu. | `Navigation.kt` lignes 19 à 27, capture `ouvert-vivant-360.jpg` | corrigé (`claude/site-gabarit`) : deux listes par mode, Guide et Actus sur les adresses existantes, Mon compte et Me prévenir sur `/pionniers/` tant que le compte n'existe pas |
| R32 | Le pied de page : le kit veut Discord Silooo, les pages légales et une ligne discrète « Voir aussi : SileneMC · Hubyy ». `main` a la mention de Mojang (obligatoire, gardée), « Mentions » et « Discord » quand l'adresse est posée ; ni SileneMC ni Hubyy. | `core/.../site/pages/Gabarit.kt` lignes 98 à 102, toutes les captures | corrigé (`claude/site-gabarit`) : « Discord Silooo », « Mentions légales », « Voir aussi : SileneMC · Hubyy » quand `SILOOO_SITE_URL_SILENEMC` et `SILOOO_SITE_URL_HUBYY` sont posées |
| R33 | Le décor : les maquettes posent le ciel en fond, des îles en blocs, une bande de sol en bas où Oooscar se tient, les textures à ×2 sous 700 px et ×3 au-dessus. `main` a un fond bleu uni et le panneau de fonte, sans sol ni îles ; à 1 280 px le dessin d'île de la maquette bureau manque. | captures `accueil-1280.jpg`, `ouvert-vivant-1280.jpg`, maquettes SiteAttente, SiteAttenteBureau | corrigé : le sol, les nuages et l'échelle ×2 sous 700 px (`claude/site-gabarit`), le dessin d'île de la maquette bureau en CSS seul, caché sous 700 px (`claude/site-attente`) |
| R34 | La page d'attente : la maquette a le logo, « Produis de jour comme de nuit », le bouton rouge « Me prévenir », « Par Xbox ou Discord. Les inscrits entrent en bêta et reçoivent le titre Pionnier. », le panneau LE CHANTIER (étapes franchies, tirées des actus « étape »), le panneau DANS LE JEU (Usines, Ville, Prestige), Oooscar « On construit. Reviens vite ! ». `main` a le logo, deux phrases, « Rejoins le Discord », Oooscar « Le serveur ouvre bientôt ! ». | capture `accueil-360.jpg`, `Attente.kt`, maquette SiteAttente | corrigé (`claude/site-attente`) : la page des maquettes SiteAttente et SiteAttenteBureau, « Le chantier » tiré des actus « étape », « Dans le jeu », un seul bouton rouge « Me prévenir » ; textes à valider par Kr |
| R35 | La vitrine (mode ouvert) : bande-annonce, trois captures, « Ce qui change » (natif console et téléphone, gros chiffres, ville, entreprise, Oooscar), l'île qui se construit, Panthéon top 3 et jauge. `main` réutilise la page d'attente avec l'adresse, le port, « N joueurs en ligne » et un bouton Rejoindre. Dernière sortie (ouverture, phase 7). | capture `ouvert-vivant-360.jpg`, `core/.../site/pages/Accueil.kt`, maquette SiteVitrine | à refaire |
| R36 | Le bandeau direct sur chaque page à partir de la bêta (joueurs en ligne, serveur ouvert ou en maintenance, version de Bedrock, Vote party, dernier prestige ; `GET /site/direct.json` en cache 30 s ; le bandeau disparaît si le service ne répond pas). `main` n'a que « N joueurs en ligne » sur l'accueil, lu sur la présence du serveur. | `core/.../site/OnlinePlayers.kt` ligne 20, capture `ouvert-vivant-360.jpg`, kit « Bandeau direct » | corrigé (`claude/site-gabarit`) : `GET /site/direct.json` (présence et votes du jour, cache 30 s), bandeau en mode ouvert, caché par `direct.js` si le service ne répond pas ; version et dernier prestige à `null` tant que le jeu ne les donne pas |
| R37 | Chaque page a un titre, une description et une image de partage avec le logo et Oooscar. `main` pose bien le titre, la description et les balises `og:`, mais l'image de partage est le logo seul. | `Gabarit.kt` lignes 72 à 78 | corrigé (`claude/site-socle`) : `public/partage.png` (1200 sur 630), `og:image:width` et `height`, `twitter:card` |

## E. Rejoindre

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R38 | Trois onglets Console, Mobile, PC ; sur console, l'ami Xbox HubyOnMC pas à pas, les autres méthodes repliées ; adresse et port avec un bouton Copier chacun ; la version de Bedrock acceptée ; un lien vers l'aide sur le Discord. `main` a PC et téléphone, tablette, et « La marche à suivre pour les consoles arrive. » ; pas d'onglets, pas de Copier, pas de version. Les six étapes PC et le texte « Aucune commande à taper » sont bons et restent. | `core/src/main/resources/site/contenu/rejoindre.md` lignes 26 à 28, capture `ouvert-rejoindre-360.jpg`, maquette SiteRejoindre | à corriger |

## F. La boutique et le webhook

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R39 | L'achat : le kit affiche les packs de `definitions/boutique.json`, un clic ouvre la fenêtre (« Ton gamertag Xbox, tel qu'il s'affiche en jeu », empreinte cherchée dans `players`, « Connecte-toi une fois au serveur avant d'acheter. », case L221-28, « Moins de 18 ans ? Demande d'abord à un parent. », total, « Payer sur PayNow »), `POST /boutique/commande` crée la commande PayNow ; sous les packs, une phrase sur les grades et le Pilier. `main` renvoie vers une adresse extérieure `SILOOO_SITE_URL_BOUTIQUE` (la boutique de SileneMC), ce que le kit écarte (« Écarts avec les autres documents » : les Gemmes viennent de la boutique PayNow de Silooo). | `core/.../site/pages/Boutique.kt` ligne 24, `SiteSettings.kt` lignes 18 et 19, capture `ouvert-complet-boutique-360.jpg`, maquette SiteBoutique | à refaire |
| R40 | `main` sépare « Boutique » (grades, abonnements, bouton) et « Ce qui se vend » (packs, grades, abonnements, ce qui ne se vend jamais). Le kit a une seule page Boutique. Les packs et les prix viennent bien de `definitions/` ; la liste « ne se vend jamais » (de `vision.md`) est un bon texte de confiance et reste sur la page unique. | `core/.../site/pages/Offres.kt` lignes 3 à 6 et 45, `CeQuiSeVend.kt`, captures des deux pages | à corriger |
| R41 | Le webhook PayNow : `POST /paynow/webhook`, signature HMAC `PayNow-Signature`, champs `order_id`, `gamertag`, `gemmes`, une ligne dans `shop_deliveries` par commande, corps borné, 405 au `GET`, test complet. Conforme au kit, à une colonne près : l'empreinte du gamertag (`xuid`) n'est pas écrite, le jeu retrouve le joueur par le pseudo. Le format exact de PayNow reste dans « À faire par Kr ». | `core/.../site/WebhookRoutes.kt` lignes 23 à 55, `PayNowWebhook.kt`, `PayNowWebhookTest.kt` | gardé (l'empreinte s'ajoute avec R8) |
| R42 | Pendant la bêta, seuls les comptes staff paient, boutique PayNow en mode test : dépend des comptes (R15) et de l'admin (R20). | kit « L'achat de Gemmes » point 6 | à corriger |

## G. Les classements

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R43 | Le kit veut un Panthéon en salles (Entreprises, Prestiges, Villes, Votants du mois, En chiffres avec une comparaison parlante, « Ton nom ici ? »), recalculé toutes les cinq minutes dans `site_leaderboards`, l'heure du dernier calcul affichée, `GET /site/classements.json`. `main` a un classement hebdomadaire des Lingots (semaine ISO, pagination, cache d'une minute). | `core/.../site/pages/Classements.kt`, `StatsRoutes.kt` lignes 35 à 53, capture `ouvert-vivant-classements-360.jpg`, maquette SiteClassements | à refaire |
| R44 | Le classement hebdomadaire des Lingots de `main` marche, est testé et ne contredit rien : il peut rester comme sixième salle « Lingots de la semaine », ou disparaître. | `core/.../sitedata/JdbcSiteStatsStore.kt`, `SiteStatsTest.kt` | à trancher par Kr (T2) |

## H. Le vote

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R45 | Les sites de vote : le kit les lit dans `definitions/site-votes.json` (Hubyy en premier) ; `main` les lit dans la variable `SILOOO_SITE_SITES_DE_VOTE`. Un chiffre ou une liste du jeu vit dans `definitions/`. | `SiteSettings.kt` lignes 20 et 21, `Vote.kt` lignes 25 à 32 | à corriger |
| R46 | La page de vote : la jauge de la Vote party, les récompenses d'un vote, la série de jours, l'état par site (Voté, Dans 4 h, Voter) ; maquette SiteVote. `main` : les boutons des sites et deux phrases. Le jeu a maintenant `definitions/vote.json` et `GET /v1/votes/today` (décision 42) ; la série et l'état par site demandent un joueur connu (R15), la jauge non. | `core/src/main/resources/site/contenu/vote.md`, capture `ouvert-complet-vote-360.jpg` | à corriger |

## I. Le Guide

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R47 | Le kit génère le Guide depuis `definitions/` (champs `description` et `astuce` sur chaque usine, époque, amélioration, bâtiment ; `verifier.sh` échoue s'il en manque un ; FAQ dans `definitions/site-faq.json`), à `/guide`, par sujet, par étape, et une FAQ ; maquette SiteGuide (fiche de la Scierie). `main` a un wiki de treize pages Markdown écrites à la main à `/wiki/`, dont des blocs tirent déjà des nombres de `definitions/`. Le wiki est gardé comme contenu (ce sont les sujets et les guides par étape) ; les fiches générées et la route `/guide` s'y ajoutent. Les champs à ajouter aux schémas sont du ressort du jeu : à demander dans « Pour la direction du jeu ». | `core/.../site/Wiki.kt`, `pages/WikiBlocs.kt`, `contenu/wiki/`, capture `ouvert-wiki-360.jpg` | à corriger |
| R48 | Le contenu du wiki : treize pages justes, relues à la fusion, en « À valider par Kr » ; `regles.md` reste à écrire par Kr. | `SiteWikiTest.kt`, passation « À valider par Kr » S3 | gardé |

## J. Les actus

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R49 | Les actus : écrites dans l'admin (`news_articles`, Markdown, marque « étape » pour la page d'attente, publié ou non), liste à `/actus` et un article à `/actus/<slug>`, plan fixe (sommaire chiffré, Nouveau, Changements, Corrections ; maquette SiteActu), visibles dès la page d'attente ; une actu devient une carte dans #patch-notes avec un champ « aussi dans #annonces » (discord-passation point 4). `main` a `/mises-a-jour/` lue dans des fichiers Markdown du jar, une entrée par tag déployé, sans page par article, en mode ouvert seulement. | `core/.../site/Updates.kt` lignes 19 à 26, capture `ouvert-complet-mises-a-jour-360.jpg` | corrigé pour moitié (`claude/site-attente`) : `/actus/` et `/actus/<slug>/` au plan fixe (`Actus.kt`, `pages/Actus.kt`), lues dans `contenu/actus/<slug>.md` avec la marque « étape » et le chantier ; l'admin et `news_articles` attendent `claude/site-admin` |

## K. Les textes et le style

| N° | Écart | Preuve | Verdict |
| --- | --- | --- | --- |
| R50 | Les textes vus par les joueurs : tous ceux de `main` sont listés « À valider par Kr » dans la passation ; là où une maquette donne le texte (bulle d'Oooscar, page d'attente, Rejoindre, 404, boutique), le texte de la maquette remplace celui de `main`, et reste à valider. | passation « À valider par Kr », maquettes | à corriger |
| R51 | Le style : jetons, textures (panneau de fonte, plaques, boutons), plaque de titre, corps de 20 px sur papier, étiquette Silkscreen pour la mention de Mojang, contraste mesuré : c'est Silooo Atelier, et les maquettes le confirment. | captures, `SiteStyleTest.kt`, passation point 7 | gardé |
| R52 | La mention de Mojang en pied, exigée par Microsoft, en capitales, et le `alt` du logo. | `Gabarit.kt` ligne 34 | gardé |
| R53 | Le déploiement : du jeu (kit « Les étapes », décision 37). Les treize variables du site sont dans l'exemple d'environnement ; la liste bouge à chaque correction (R6, R15, R39, R45) et se transmet par « Pour la direction du jeu ». | `deploy/`, commit `69d98b1` | gardé |
| R54 | Les tests de contrat et les routes `/v1` : rien du site n'y touche aujourd'hui ; `POST /v1/links/discord` et le refus « pas encore admis » de `session/join` viendront par le contrat d'abord (R15, R18). | `api/silooo-core.openapi.yaml` | gardé |
| R55 | Le rendu local pour la relecture (`SiteRenderToDisk`) : gardé, et il s'étend aux pages des pionniers et à la 404 (le rendu jetable de cette révision) pour que la relecture visuelle couvre tout. | `core/src/test/.../site/SiteRenderToDisk.kt` | gardé ; étendu par `claude/site-socle` : les pionniers et la 404 rendus, `core/build/site-rendu/` écrit à chaque `gradlew test` |

## Les points à trancher par Kr

**T1. Le socle technique (R1, R3).** Garder le rendu kotlinx.html et `atelier.css` de `main`, ou refaire en Pebble
avec `composants.css` dérivé de `bundle.css` comme le kit l'écrit. Ma recommandation : garder kotlinx.html (il
échappe tout, il est typé, les quarante-quatre pages et leurs tests existent ; l'ADR 0006 s'amende pour le dire
et prend la date de validation de Kr), mais dériver `composants.css` de `bundle.css` avec la table des `/_blob/`
et faire copier `design/` par Gradle (R4) : c'est ce qui garantit que le site suit le design system sans
recopie à la main. `site.css` garde les pièces propres au site.

**T2. Le classement hebdomadaire (R44).** Le Panthéon le remplace, ou il reste comme sixième salle « Lingots de la
semaine ». Ma recommandation : il reste, comme salle de plus ; il marche, il est testé, et la semaine ISO donne
un rendez-vous que les cinq salles du kit n'ont pas.

**T3. La portée Discord (R23).** La case « Rejoindre aussi le Discord » demande `guilds.join`, le kit dit
`identify` seulement. Ma recommandation : `identify` seul par défaut, et `identify guilds.join` seulement quand la
case est cochée (ce sont deux adresses d'autorisation différentes, le joueur voit ce qu'il accorde). Appliquée par
`claude/site-comptes` sans réponse de Kr (décision 19 de la passation) ; Kr peut la retourner.

## L'ordre des corrections

Une branche `claude/site-<sujet>` par ligne ou groupe de lignes, dans l'ordre des sorties du kit : d'abord ce
qu'il faut pour la page d'attente, puis la bêta, enfin l'ouverture. Chaque branche : code, tests, captures
relues, `tools/verifier.sh`, rebase sur `origin/main`, fusion en avance rapide, poussée, une ligne de journal.

1. `claude/site-socle` : R4, R5, R7, R8, R13, R30, R37, et la politique de sécurité qui admet les scripts du site
   (R2, première moitié). Selon T1, `composants.css` dérivé (R3).
2. `claude/site-gabarit` : R26 (Oooscar vivant sur chaque page), R31, R32, R33, R36 (bandeau et `direct.json`).
3. `claude/site-attente` : R34, R25 (les quatre pages légales), R49 (actus, d'abord lues dans des fichiers avec le
   plan fixe et la marque « étape », puis écrites dans l'admin).
4. `claude/site-comptes` : R6, R15, R16, R17, R18, R19, R21 (`/compte/lier` d'abord), R23 selon T3 ; le contrat
   `POST /v1/links/discord` et le refus de `session/join` en bêta, par « Pour la direction du jeu ». Le flux Xbox se
   prototype en premier avec un faux fournisseur ; l'application Azure est à Kr.
5. `claude/site-admin` : R20, R42, le reste de R49.
6. `claude/site-rejoindre` : R38.
7. `claude/site-boutique` : R39, R40, R41 (l'empreinte).
8. `claude/site-pantheon` : R43, R44 selon T2, R27.
9. `claude/site-vote` : R45, R46, R28.
10. `claude/site-guide` : R47 (la route et les fiches ; les champs `description` et `astuce` par le jeu).
11. `claude/site-compte` : le reste de R21.
12. `claude/site-vitrine` : R35, R29.

Ce qui attend Kr avant d'être fini : l'application Azure (R15, Xbox), l'application Discord et le rôle staff
(R15, R20), la boutique PayNow et le format de son webhook (R39, R41), Votifier branché sur Hubyy (R46), les
textes légaux et les textes de chaque page (R25, R50), le dessin du Pionnier (R19), les captures et la
bande-annonce de la vitrine (R35), le relevé de `skyblock.rinaorc.com` (grille de [site.md](site.md)).
