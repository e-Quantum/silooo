# Le site de Silooo : périmètre, règles, et la référence skyblock.rinaorc.com

Le game design demande un site : wiki et guides, classements en direct, fiche publique par
entreprise, boutique et vote ([game-design.md](game-design.md), tableau « Décisions de cadrage »). La
boutique est celle de SileneMC, qui crédite les Gemmes dans la base `silooo`
([architecture.md](architecture.md)). Ce fichier dit ce que le site doit faire, les règles qu'il suit,
et ce qu'on apprend du site que Kr a choisi comme référence, `skyblock.rinaorc.com`.

État de l'audit au 5 novembre 2026 : le domaine n'est pas joignable depuis la session cloud (hors des
domaines réseau autorisés). L'inventaire ci-dessous vient des pages indexées par les moteurs de
recherche, pas d'une lecture du site : chaque point est à confirmer sur place. La grille d'audit est
prête à remplir dès que le domaine sera autorisé.

## Ce que le site de Silooo doit faire

| Page | Rôle | D'où ça vient |
| --- | --- | --- |
| Accueil | Le jeu en une phrase, l'adresse `play.silooo.com` et son port, les joueurs en ligne, une vidéo de vingt secondes en jeu, une capture du menu, le lien Discord | « Travailler la réticence des joueurs Hubyy » |
| Ce qui se vend | La phrase « tout ce qui s'achète se gagne aussi en jouant », puis la liste exacte de ce qui se vend, avec les prix, visible avant la connexion | Obligations de la boutique |
| Wiki et guides | Les systèmes du jeu, dans l'ordre où le joueur les rencontre ; les règles du serveur, qui ne sont pas en jeu à l'arrivée | « Interface et onboarding console » |
| Classements | Hebdomadaires, en Lingots gagnés dans la semaine, jamais en richesse totale ; les villes les mieux votées | « Économie sur le long terme » |
| Fiche publique d'entreprise | Nom, logo, couleurs, slogan, membres, époque, étoiles, ville | « Identité d'entreprise » |
| Boutique | Les packs de Gemmes ([definitions/boutique.json](../definitions/boutique.json)), les grades, le Pilier ; historique d'achats consultable | Catalogue et obligations |
| Vote | Les sites de vote et Hubyy, la récompense par vote, la jauge de la Vote party | « Votes » |
| Mentions | « NOT AN OFFICIAL MINECRAFT SERVICE », mentions légales, conditions de vente, protection des mineurs | Obligations |

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
- Le design du site reprend-il Silooo Atelier ([design/README.md](../design/README.md),
  [design/tokens.css](../design/tokens.css)) ? À confirmer par Kr.

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

## Ce qui reste à trancher

- **Où vit le site.** Un espace Silooo dans le site de SileneMC (même pile Kotlin, même boutique), ou
  un site à part sur `silooo.com` servi par le Caddy du VPS ? À trancher par Kr. Le plan n'en parle pas.
- **D'où viennent les classements et les fiches.** Le site doit lire la base `silooo` : par un compte
  en lecture seule sur quelques tables, ou par des routes publiques de silooo-core, qui aujourd'hui ne
  répond que sur le réseau Docker ([architecture.md](architecture.md)). Décision d'architecture : un
  ADR à écrire quand Kr aura choisi ([adr/README.md](adr/README.md)).
- **Le design du site.** Silooo Atelier, ou une charte web à part dérivée des mêmes jetons ?
- **La page des mises à jour.** Écrite à la main à chaque déploiement, ou tirée des tags.

Ces questions sont à reporter par la direction dans « À faire par Kr » de [passation.md](passation.md).
