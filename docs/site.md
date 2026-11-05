# Le site de Silooo : périmètre, règles, et la référence skyblock.rinaorc.com

Le game design demande un site : wiki et guides, classements en direct, fiche publique par
entreprise, boutique et vote ([game-design.md](game-design.md), tableau « Décisions de cadrage »). La
boutique est celle de SileneMC, qui crédite les Gemmes dans la base `silooo`
([architecture.md](architecture.md)). Ce fichier dit ce que le site doit faire, les règles qu'il suit,
et ce qu'on apprend du site que Kr a choisi comme référence, `skyblock.rinaorc.com`.

Le chantier du site est piloté par [site-direction.md](site-direction.md), son état et ses étapes S0 à S5
sont dans [site-passation.md](site-passation.md), et ses choix d'architecture dans
l'[ADR 0006](adr/0006-site-silooo-com.md) : le site est un module de silooo-core (`core/.../site/`,
`core/src/main/resources/site/`, décision 37 de Kr), sur son propre port, en deux modes (`attente`, `ouvert`),
rendu côté serveur, qui lit la base par les accès du service pour les données vivantes.

État de l'audit de la référence au 5 novembre 2026 : le domaine n'est pas joignable depuis la session cloud
(hors des domaines réseau autorisés), et il ne l'est toujours pas depuis la session de direction du site.
L'inventaire ci-dessous vient des pages indexées par les moteurs de recherche, pas d'une lecture du site :
chaque point est à confirmer sur place. La grille d'audit est prête à remplir dès que le domaine sera autorisé
ou que Kr fera le relevé depuis son PC.

## Ce que le site de Silooo doit faire

| Page | Rôle | D'où ça vient |
| --- | --- | --- |
| Accueil (`contenu/accueil.md` + `pages/Accueil.kt`) | Le jeu en une phrase, l'adresse `play.silooo.com` et son port, les joueurs en ligne, une vidéo de vingt secondes en jeu, une capture du menu, le lien Discord. En mode `attente`, seule la phrase, le logo et les mentions | « Travailler la réticence des joueurs Hubyy » |
| Rejoindre (`contenu/rejoindre.md`, adresse et port par `pages/Joueurs.kt`) | L'adresse et le port, la marche à suivre par plate-forme (PC, téléphone et tablette, console) | « Interface et onboarding console », ADR 0004 |
| Ce qui se vend (`contenu/ce-qui-se-vend.md`, tableaux par `pages/Offres.kt`) | La phrase « tout ce qui s'achète se gagne aussi en jouant », puis la liste exacte de ce qui se vend, avec les prix, visible avant la connexion | Obligations de la boutique |
| Wiki et guides (`contenu/wiki/`, `pages/Wiki.kt` et `pages/WikiBlocs.kt`) | Les systèmes du jeu, dans l'ordre où le joueur les rencontre ; les règles du serveur, qui ne sont pas en jeu à l'arrivée | « Interface et onboarding console » |
| Classements (`contenu/classements.md`, `site/StatsRoutes.kt`, `pages/Classements.kt`) | Hebdomadaires (semaine ISO en UTC), les joueurs par Lingots gagnés dans la semaine, jamais en richesse totale, vingt-cinq par page, avec le lien vers la fiche de leur entreprise ; les villes les mieux votées viendront avec le vote | « Économie sur le long terme » |
| Fiche publique d'entreprise (`/entreprise/{id}/`, `pages/Entreprise.kt`) | Nom, époque, étoiles, prestiges, date de création, membres (PDG d'abord) ; le logo, les couleurs, le slogan et la ville y viendront quand le jeu les écrit | « Identité d'entreprise » |
| Boutique (`contenu/boutique.md`, `pages/Boutique.kt`) | Les packs de Gemmes ([definitions/boutique.json](../definitions/boutique.json)), les grades, le Pilier ; historique d'achats consultable | Catalogue et obligations |
| Vote (`contenu/vote.md`, `pages/Vote.kt`) | Les sites de vote et Hubyy, la récompense par vote, la jauge de la Vote party | « Votes » |
| Mises à jour (`contenu/mises-a-jour.md`, entrées dans `contenu/mises-a-jour/`, `pages/MisesAJour.kt`) | Une entrée datée par tag déployé, écrite pour les joueurs | Référence Rinaorc, ADR 0006 |
| Pionniers (`contenu/pionniers.md`, `site/PioneerRoutes.kt`, `pages/Pionniers.kt`) | L'inscription avant l'ouverture avec le gamertag, le nombre de pionniers, l'état de l'inscription, « Relier Discord » ; rendue en `attente` comme en `ouvert` | Décision 37, ADR 0006 |
| Mentions | « NOT AN OFFICIAL MINECRAFT SERVICE », mentions légales, conditions de vente, protection des mineurs ; le site n'a ni traceur, et un seul cookie de session après une inscription | Obligations |

Aucune liaison de compte (Discord, site) n'est obligatoire pour jouer. Le site est en français
seulement, comme la marque.

## Les règles que le site suit

- Chaque texte passe le filtre anti-IA et l'avant / après validé par Kr ([anti-ia.md](anti-ia.md)).
  Les textes de réclame des serveurs concurrents sont justement ce que la charte bannit.
- Rien de ce que [vision.md](vision.md) interdit n'y apparaît : ni coffre payant au hasard, ni
  monnaie échangeable, ni Lingots, zones ou places de classement à vendre.
- Pas de message d'achat avant la première heure de jeu : le site présente la boutique, il ne la
  pousse pas. Le prix de chaque article est affiché avant la connexion.
- Les chiffres affichés (prix, bonus de grade) viennent de `definitions/`, jamais recopiés à la main.
- Le design du site est Silooo Atelier ([design/README.md](../design/README.md),
  [design/tokens.css](../design/tokens.css)), adapté au web par l'étape S1 ; ce que le web ajoute s'écrit dans
  la section « Comment le site se construit » ci-dessous, à confirmer par Kr.
- Aucun hôte tiers : les polices sont servies par le site lui-même (fichiers des paquets Fontsource, licence
  OFL, commis avec leur licence), pas de script de mesure. Le seul cookie est un cookie de session, posé après une inscription de pionnier, pour retrouver
  l'inscription : signé, d'une heure, nécessaire au service, donc sans bandeau (les mentions le disent).

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

## Comment le site se construit

Section tenue par la direction du site, étape par étape ([site-passation.md](site-passation.md)) :

- S0 : le site est un module de silooo-core, paquet `com.silooo.core.site`. `Main.kt` démarre un second serveur
  Ktor sur `SILOOO_SITE_PORT` (8081 par défaut), à côté de celui de l'API, dans la même JVM ; `/v1` n'existe pas sur
  ce port. Deux modes : `SILOOO_SITE_MODE=attente` (le défaut : la page d'attente et les mentions) et `ouvert`
  (un accueil minimal, que S2 remplace). Les contenus sont des fichiers Markdown à en-tête (`titre`, `description`,
  `url`, `modes`) dans `core/src/main/resources/site/contenu/`, lus et rendus une fois au démarrage ; les fichiers
  statiques sont dans `core/src/main/resources/site/public/` (servis sous `/public/`), le logo y est une copie
  contrôlée de `design/logo/`. Les pages sont écrites en DSL HTML (`pages/Gabarit.kt` pose la page, les autres
  posent le `h1` et le contenu) ; aucun script, en-têtes de sécurité et de cache posés par le module. Les tests sont
  dans `core/src/test/kotlin/com/silooo/core/site/` et `ConfigTest` ; `tools/anti_ia.py` relit les contenus et les
  littéraux du paquet `site`. Détail : `core/README.md`, section « Le site ».
- S1 : Silooo Atelier devient un style web, écrit à la main dans `core/src/main/resources/site/public/` (voir ci-dessous).
  - **Une seule feuille liée** par les pages, `site.css`, qui importe `atelier/tokens.css` (copie de `design/tokens.css`)
    puis `atelier.css` (les composants et la mise en page, écrits à la main d'après `design/composants/bundle.css`).
    Les composants gardent leurs noms `sa-*` : `sa-panneau` (et `--laiton`), `sa-plaque` (`--rouge`, `--petite`),
    `sa-bouton` (rouge, `--laiton`, `--fonte`, état appuyé, focus visible), `sa-ticket`, `sa-monnaie`, `sa-icone`,
    `sa-puce`, `sa-bulle`, `sa-oooscar`. Les `border-image` pointent vers les textures 9-slice copiées, avec la même
    bordure fixe que la charte (15, 12 ou 6 pixels) pour que les coins gardent leur taille de 5 pixels de texture
    à ×3. Le pixel art s'affiche en `image-rendering: pixelated`, Oooscar et le logo restent lisses, à hauteur fixe.
  - **Ce qui est copié de `design/`** : `tokens.css`, les douze textures utiles de `textures/`, les quatorze icônes,
    `oooscar-joie.png`, le logo (SVG, PNG de 512, de 128 et de 64 pixels). `SiteAtelierTest` compare chaque copie à sa
    source octet pour octet : on change `design/`, puis on recopie, jamais l'inverse. Un composant neuf se dessine
    d'abord dans `design/`, puis se recopie ici.
  - **Les polices** sont servies par le site, pas par un tiers : les `woff2` du sous-ensemble latin de Jersey 10 (400),
    Pixelify Sans (400 et 600) et Silkscreen (400), tirés des paquets npm `@fontsource/*` (version 5.3.0, licence OFL 1.1),
    avec leur licence et un `LISEZ-MOI.md` dans `public/polices/<police>/`. Les `@font-face` ont `font-display: swap` et
    les secours de `tokens.css` prennent le relais. La politique de sécurité ajoute `font-src 'self'` et rien d'autre.
  - **Les tailles** : le corps fait 20 px sur 27 px d'interligne (1,35), sur téléphone aussi. Le titre `h1` est une
    plaque en `affiche-titre` (50 px), les `h2`, le nom du site et le bouton rouge sont en `affiche-chiffre` (40 px) : deux
    tailles d'affiche par page, pas une de plus. Les légendes (la mention de Mojang du pied) sont en `etiquette` (16 px,
    la grille de Silkscreen). Les liens de navigation sont en corps gras de 20 px pour que huit tiennent sur un téléphone.
    Les espacements sont les `--pas-N`, donc des multiples de 3 px ; les tailles de police sont celles de la charte.
  - **Le fond des pages de lecture** (mentions, erreurs, plus tard le wiki) : texte `--fonte-900` sur un bloc de
    `--papier`, dans le panneau de fonte. Le contraste est de 15,8 contre 6,6 pour `--papier` sur `--fonte-500` : les deux
    passent la charte, mais un texte long se lit mieux sur le papier. La page d'attente, courte, garde le texte `--papier`
    sur le panneau de fonte, comme les fenêtres du jeu. Les liens des pages de lecture sont en `--email-bleu` sur
    `--papier` (7,9 contre 1), survolés en `--email-rouge-sombre` (9,5 contre 1).
  - **Mobile** : à 360 px de large, pas de défilement horizontal (aucune largeur fixe de plus de 360 px, les `width` des
    images sont des plafonds), liens de navigation, boutons et liens du pied de 60 px au moins. Un seul bouton rouge par
    page : le Discord sur l'attente, le retour à l'accueil sur la page introuvable.
  - **Ce que la charte bannit et que `SiteAtelierTest` refuse dans le CSS** : `border-radius`, les dégradés (`linear-`,
    `radial-` et `conic-gradient`), `blur(`, `backdrop-filter`, toute `width:` fixe au-dessus de 360 px (hors `max-width`),
    toute adresse d'un autre hôte, tout fichier cité par `url(...)` qui n'existe pas dans les ressources. Les seules
    ombres sont `ombre-pixel` et `ombre-texte`.
  - **Le gabarit** (`pages/Gabarit.kt`) : lien d'évitement, en-tête (logo SVG, nom, navigation), `<main>` en panneau de fonte, pied
    (mention de Mojang, Mentions, Discord s'il est configuré). La navigation est une liste d'adresses ordonnée
    (`pages/Navigation.kt`) filtrée par les pages que `SiteContent` rend pour le mode : seulement Mentions en `attente`,
    sans lien mort en `ouvert`. `<head>` : `og:title`, `og:description`, `og:image` absolu (`SILOOO_SITE_URL` et le logo de
    512 px), `theme-color` du ciel, icône du site.
- S2 : les six pages publiques du mode `ouvert` (404 en `attente`). Les textes écrits à la main sont des contenus Markdown
  (`accueil`, `rejoindre`, `ce-qui-se-vend`, `boutique`, `vote`, `mises-a-jour`) ; ce qui vient des définitions ou de la
  configuration est rendu par le code, à la place d'un paragraphe seul `{{nom}}` du contenu (`pages/Insertions.kt`) : les
  packs, grades et abonnements viennent de `definitions/boutique.json` (`pages/Offres.kt`), l'adresse et le port de
  `SILOOO_SITE_ADRESSE_JEU` et `SILOOO_SITE_PORT_JEU` (le défaut du port est contrôlé contre `BDS_PORT` de
  `deploy/compose.yml`), le bouton d'achat de `SILOOO_SITE_URL_BOUTIQUE`, les boutons de vote de `SILOOO_SITE_SITES_DE_VOTE`.
  Aucun chiffre n'est tapé dans un contenu (un test le vérifie), les noms de grade viennent des définitions. La récompense
  par vote n'est pas chiffrée dans `definitions/` : la page dit qu'elle arrive. Les mises à jour sont des fichiers
  `contenu/mises-a-jour/AAAA-MM-JJ-<sujet>.md` (en-tête `titre`, `description`, `date`), listés dans `UpdateEntries.BUNDLED`
  (un test compare la liste au dossier), la plus récente en tête, une par tag déployé ; un nom mal formé ou une entrée sans
  date arrête le démarrage. Aucune entrée n'existe tant que rien n'est déployé.
- S3 : le wiki, treize contenus de `contenu/wiki/` (liste `WikiContent.BUNDLED`, un test la compare au dossier), rendus en
  `ouvert` seulement. L'en-tête est celui des autres contenus plus une clé `ordre` (entier, unique : deux pages de même
  ordre arrêtent le démarrage). Les pages, dans l'ordre où le joueur rencontre les systèmes : `/wiki/` (l'index, un lien et
  une phrase par page), `premiere-heure`, `usines`, `silo-et-hors-ligne`, `personnel`, `monnaies`, `prestige`,
  `incidents-et-electricite`, `commande-du-jour-et-carnet`, `entreprise-partagee`, `ville` (dite en construction),
  `mots-du-jeu` (glossaire des joueurs, écrit à la main) et `regles` (une phrase, **à écrire par Kr**).
  - **Les tableaux** viennent des définitions par des blocs `{{nom}}` (mécanique de S2) rendus par `pages/WikiBlocs.kt` :
    usines (triées par époque puis par ordre du fichier, entrées nommées d'après l'usine qui les produit), raretés du
    personnel, sortes d'incident, plus de courtes listes (agence, postes, hors ligne, époques, électricité, entreprise).
    Un tableau est un vrai `table` ; sous 600 px, le CSS en fait une liste de fiches.
  - **Jamais un chiffre tapé.** Un test refuse dans le Markdown du wiki un nombre suivi de Donar, Lingots, Gemmes, %,
    heures, h, €, joueurs ou associés. Une règle dont le chiffre n'est pas lisible dans les définitions s'écrit en mots.
  - Les textes sont **à valider par Kr**. Le nom de la troisième rareté n'est pas écrit : le tableau affiche l'identifiant
    des définitions en minuscules.

- S4 : les pionniers, Relier Discord, l'admission. Tout est **à valider par Kr** (dessin de la direction du site, textes compris).
  - **La table** `site_registrations` (migration V7) : le gamertag en minuscules (espaces réduits à un seul) en clé, le gamertag tel
    qu'écrit, l'identifiant Discord facultatif (jamais affiché), `pioneer`, `created_at`, `linked_at`, `admitted_at`. Le site n'écrit
    que cette table. `admitted_at` non nul veut dire admis ; le jeu le lira dans `join` (la route est à la direction du jeu).
  - **Les pages**, rendues en `attente` comme en `ouvert` : `/pionniers/` (texte, nombre de pionniers lu avec un cache d'une minute,
    formulaire HTML sans script : gamertag, case « J'ai lu les règles », jeton caché signé valable dix minutes, un seul bouton rouge),
    `POST /pionniers/` (valide, inscrit, pose le cookie de session, 303 vers `/pionniers/etat/`), `/pionniers/etat/` (Inscrit, Compte
    Discord relié, Admis), `/pionniers/discord/` et `/pionniers/discord/retour/` (OAuth2, portée `identify` seule, `state` dans le
    cookie signé). Sans application Discord (variables absentes), ces deux dernières n'existent pas et l'état dit « Relier ton compte
    Discord arrive. ».
  - **L'admission** (`SILOOO_SITE_ADMISSION`) : `discord` (par défaut), admis quand le compte Discord est relié ; `tous`, admis dès
    l'inscription, pour l'ouverture. Kr peut admettre à la main (requête dans `core/README.md`). Une seule fonction,
    `SiteAdmission.admitsAt`.
  - **Le cookie** `silooo_pionnier` : `HttpOnly`, `SameSite=Lax`, `Secure` si `SILOOO_SITE_URL` est en https, une heure, limité au
    chemin `/pionniers/`. Signé par un secret tiré au démarrage : un redémarrage invalide les cookies et les jetons.
  - **La limite de débit** : cinq `POST` par minute et par adresse, en mémoire et bornée, 429 au-delà. L'adresse est celle vue par
    silooo-core, ou le dernier maillon de `X-Forwarded-For` quand `SILOOO_SITE_TRUSTED_PROXY=true`.
  - **Aucune donnée personnelle affichée** : l'identifiant Discord n'entre dans aucune page, aucun journal, aucun message d'erreur
    (un test le cherche dans toutes les pages d'un parcours complet).

- S5 : les données vivantes, le webhook PayNow, la mise en ligne. Les textes neufs sont **à valider par Kr**.
  - **Joueurs en ligne** (accueil, `ouvert`) : le nombre de joueurs des derniers signaux de vie de `ServerPresence`, sans doublon d'un serveur à
    l'autre. Aucun signal depuis plus de deux minutes (`SITE_MAX_BEAT_AGE`, une durée technique) : « Serveur fermé pour l'instant. ». Aucune
    lecture de la base.
  - **Classements** (`/classements/`, `ouvert`) : la semaine ISO en cours en UTC, lue sur l'horloge du service, du lundi 00:00 au dimanche
    23:59:59 ; le titre dit les dates (« Semaine du 2 au 8 novembre 2026 »). Les joueurs sont classés par Lingots gagnés dans la semaine :
    la somme, par joueur, des lignes de `currency_ledger` en `LINGOTS` avec `delta > 0` dont la date tombe dans la semaine, avec le gamertag
    et l'entreprise du joueur (« Sans entreprise » s'il n'en a pas). Les dépenses, les autres monnaies et les soldes ne comptent pas.
    Vingt-cinq par page (`?page=2`), au plus quarante pages. Cache d'une minute par page et par semaine, partagé entre les requêtes.
  - **La fiche d'entreprise** (`/entreprise/{id}/`, `ouvert`, hors de la navigation) : nom, « Époque N », étoiles, prestiges (comptés dans
    `prestige_history`), date de création, membres (gamertag et rôle). Ni Donar, ni Lingots, ni XUID, ni slogan. Un identifiant invalide ou
    inconnu rend le 404 en page Silooo. Cache d'une minute par identifiant, trois cents entrées au plus.
  - **Les accès** sont dans `com.silooo.core.sitedata` (hors du paquet `site`, dont les littéraux passent le filtre de texte) :
    `SiteStatsStore` et son SQL en lecture seule, `ShopDeliveryStore` pour le webhook. Testés sur MariaDB (`SiteStatsSqlTest`,
    `ShopDeliverySqlTest`, ignorés sans Docker, joués dans la CI) et avec des faux en mémoire pour les pages.
  - **Le webhook PayNow** (`POST /paynow/webhook`, `attente` comme `ouvert`) : **le format exact des webhooks de PayNow est à confirmer par
    Kr**. Tout ce qui en dépend est dans la classe `PayNowWebhook` : l'en-tête `PayNow-Signature` porte l'HMAC-SHA256 du corps brut, en
    hexadécimal minuscule, avec `SILOOO_PAYNOW_WEBHOOK_SECRET` pour clé ; le corps JSON porte `order_id`, `gamertag` et `gemmes`. Sans secret
    posé : 503. Signature absente ou fausse (comparaison en temps constant) : 401. Champ manquant ou Gemmes non strictement positives : 400
    qui nomme le champ. Corps de plus de 4 Ko : 413. Sinon une ligne dans `shop_deliveries` (`xuid` et `delivered_at` nuls : le relevé du jeu
    les remplit et crédite) et `{"received": true}`. Un `order_id` déjà écrit rend 200 sans seconde ligne (la clé primaire refuse le doublon).
  - **Les captures** de relecture (360 et 1280 px) se font avec `SILOOO_SITE_RENDER_DIR` (`SiteRenderToDisk` écrit aussi les classements et une fiche).

## Mettre en ligne

La marche à suivre pour Kr et pour la direction du jeu. Rien ici ne s'exécute tout seul : chaque geste est celui de Kr, ou celui de la
direction du jeu quand [site-passation.md](site-passation.md) dit « Prêt à mettre en ligne ». Le site reste en mode `attente` jusqu'à
l'ouverture.

1. **Le DNS.** `silooo.com` (et `www`, si Kr le veut) pointe vers l'adresse du VPS qui porte le Caddy de SileneMC. Le jeu garde son
   adresse à part (`play.silooo.com`, `SILOOO_SITE_ADRESSE_JEU`).
2. **Les variables**, dans le fichier d'environnement du VPS (jamais dans le dépôt ; `deploy/exemple.env.txt` donne les noms) :

   | Variable | Valeur |
   | --- | --- |
   | `SILOOO_SITE_PORT` | `8081` (le défaut ; le Caddy y joint `silooo-core`) |
   | `SILOOO_SITE_MODE` | `attente` (voir la règle plus bas) |
   | `SILOOO_SITE_URL` | `https://silooo.com` |
   | `SILOOO_SITE_URL_DISCORD` | le lien d'invitation du Discord de Silooo |
   | `SILOOO_SITE_ADRESSE_JEU` | `play.silooo.com` |
   | `SILOOO_SITE_PORT_JEU` | le port de `BDS_PORT` dans `deploy/compose.yml` |
   | `SILOOO_SITE_URL_BOUTIQUE` | l'adresse de la boutique de SileneMC où s'achètent les Gemmes |
   | `SILOOO_SITE_SITES_DE_VOTE` | `nom\|url` séparés par des points-virgules |
   | `SILOOO_SITE_ADMISSION` | `discord` (par défaut) ou `tous` à l'ouverture |
   | `SILOOO_DISCORD_CLIENT_ID`, `SILOOO_DISCORD_CLIENT_SECRET` | ceux de l'application Discord (les deux, ou aucune) |
   | `SILOOO_SITE_TRUSTED_PROXY` | `true` : l'adresse du visiteur se lit dans `X-Forwarded-For`, posé par le Caddy |
   | `SILOOO_PAYNOW_WEBHOOK_SECRET` | le secret de signature des webhooks, donné par PayNow |

3. **Le bloc Caddy**, dans le Caddy de SileneMC (`/var/www/minecraft`, que les sessions Silooo ne touchent pas). `silooo-core` est sur le
   réseau Docker `silenemc` ; le Caddy joint le port du site, jamais celui de `/v1` :

   ```
   silooo.com {
       reverse_proxy silooo-core:8081 {
           header_up X-Forwarded-For {remote_host}
       }
   }
   ```

   Le `header_up` remplace tout `X-Forwarded-For` que le visiteur aurait envoyé : la limite de débit des inscriptions et
   `SILOOO_SITE_TRUSTED_PROXY=true` comptent dessus. Le TLS est celui de Caddy.
4. **Les mentions** (`contenu/mentions.md`) : l'éditeur, l'hébergeur et le contact disent « À compléter par Kr ». Les remplir avant l'ouverture
   (identité légale, hébergeur, adresse de contact). La section « Données » dit aussi ce que le site garde ; si le site garde autre chose
   (le pseudo d'une commande de Gemmes), la compléter.
5. **L'application Discord** (portail des développeurs de Discord) : ajouter l'URL de retour OAuth2 `<SILOOO_SITE_URL>/pionniers/discord/retour/`
   (`https://silooo.com/pionniers/discord/retour/`), à l'identique, sans rien d'autre. La portée est `identify` seule.
6. **PayNow** : déclarer l'URL du webhook `<SILOOO_SITE_URL>/paynow/webhook` (`https://silooo.com/paynow/webhook`), et poser son secret dans
   `SILOOO_PAYNOW_WEBHOOK_SECRET`. **Confirmer d'abord le format** (en-tête de signature, calcul, champs du corps) : s'il diffère de ce que
   `PayNowWebhook` suppose, on ne change que cette classe et son test. Sans secret, le webhook répond 503 et PayNow rejoue plus tard.
7. **Vérifier avant d'ouvrir** : `https://silooo.com/` montre la page d'attente et `/mentions/` les mentions ; `/classements/` répond 404 (il n'existe
   qu'en `ouvert`) ; `/v1/` n'existe pas sur le site ; un `POST` sans signature sur `/paynow/webhook` rend 401.
8. **Le mode.** `SILOOO_SITE_MODE` passe à `ouvert` **par Kr seulement, à l'ouverture** : jamais dans un fichier du dépôt, ni dans la CI, ni
   dans un déploiement fait par une session. Pour relire le site entier avant, le lancer sur son propre poste
   (`SILOOO_SITE_MODE=ouvert ./gradlew run`, voir `core/README.md`).

## Ce qui est tranché, provisoirement

Les quatre questions laissées ouvertes par la phase 0 sont tranchées par l'[ADR 0006](adr/0006-site-silooo-com.md),
au statut « Proposée, à valider par Kr » :

- **Où vit le site** : dans silooo-core (décision 37 de Kr), paquet `com.silooo.core.site` et ressources
  `core/src/main/resources/site/`, servi sur son propre port (`SILOOO_SITE_PORT`) derrière le Caddy de SileneMC
  dont Kr écrit le bloc ; `/v1` reste sur l'autre port, hors d'Internet.
- **D'où viennent les classements et les fiches** : de la base, par les accès du service, avec un cache d'une
  minute. Pas de route JSON publique, pas de compte de base à part.
- **Le design du site** : Silooo Atelier, mêmes jetons et textures, adapté au web.
- **La page des mises à jour** : écrite à la main, une entrée par tag déployé.
- **Ce que le site écrit** : les livraisons du webhook PayNow dans `shop_deliveries`, et la table des pionniers et
  de l'admission (migration prise à la fusion). Le jeu crédite et décide.

Ce que Kr décide encore : le DNS de `silooo.com`, le bloc Caddy, l'identité légale des mentions, l'adresse de
la boutique de SileneMC, les sites de vote, l'application Discord, le secret du webhook PayNow, et le passage du
site en mode `ouvert`. La liste à jour est dans
[site-passation.md](site-passation.md), « À faire par Kr ».
