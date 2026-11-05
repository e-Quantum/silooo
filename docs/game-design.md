# Silooo — Game design (Skyblock Bedrock)

Nov 4, 2026 · @Kr

## Pitch et piliers

Un tycoon dans le ciel : on rachète des usines, on embauche, on regarde son archipel travailler, on fait un prestige et on recommence plus fort, seul ou à quatre amis, en sessions de 15 minutes. Nom : Silooo (play.silooo.com), marque indépendante promue par Hubyy.

**Identité** (décidé le 4 nov. 2026) : le logo dessine les trois « o » de Silooo comme trois silos. Oooscar, une mascotte silo rigolote et maladroite, guide le joueur. Pas de promotion croisée avec SileneMC pour l'instant : Silooo pourrait plus tard devenir un mode de jeu de SileneMC avec sa propre boutique. C'est pourquoi ses grades sont calqués sur ceux de SileneMC et sa boutique en ligne reprend celle de SileneMC.

**Public** : tout public, avec en priorité les joueurs console et mobile qui arrivent par Hubyy, souvent en groupe d'amis. Deux rythmes : les usines en sessions courtes au quotidien, la ville en longues sessions.

**Les 4 piliers** (toute idée qui n'en sert aucun est rejetée)

1. **Ça bouge tout le temps** : chaque usine, vente et prestige a une animation ou un effet visible.
2. **15 minutes suffisent** : une session courte fait toujours progresser quelque chose.
3. **Entre amis** : île partagée jusqu'à 4, visites, objectifs communs.
4. **Jamais pay-to-win** : la boutique ne vend que de l'apparence et du confort.

Direction artistique : vos modèles Blockbench et une palette propre à la marque, pour éviter le rendu générique des serveurs tycoon. L'univers évolue d'une époque à l'autre à chaque prestige, de l'atelier artisanal à l'industrie moderne, puis au futuriste.

## Décisions de cadrage

Réponses du 4 nov. 2026 ; le reste du document les applique.

| Sujet | Décision |
| --- | --- |
| Reset | Jamais : économie et îles permanentes |
| Marque | Indépendante, promue par Hubyy, en français uniquement |
| Public et rythme | Tout public ; usines au quotidien, ville en longues sessions |
| Univers | Évolutif : une nouvelle époque à chaque prestige |
| Coop | Entreprise partagée jusqu'à 4 associés, avec un PDG |
| Échanges | Marché de ressources taxé à 10 %, en Lingots ; aucun don de monnaie |
| Combat | Boss PvE commun chaque week-end |
| Hors ligne | Plafond de 8 h, extensible à 24 h et plus via les améliorations du chef et du silo ; salaires hors ligne payés sur cette production, jamais de grève pendant une absence |
| Prestige | Revente des usines seulement, la ville reste ; le Donar se convertit en Lingots (rendement décroissant) ; après la 4e époque, une étoile de prestige par revente, sans fin |
| Usines | Chaînes de production profondes, logistique visible |
| Salariés | Agence d'emploi, progression légère, promotion en chef |
| Ville | 1re zone après la 2e usine, zones suivantes débloquées par la mairie à prix fixe ; préfabriqués + construction libre ; vivante mais légère ; visites publiques et vote |
| Véhicules | Achetés en pièces au garage, skins en boutique |
| Maison | Meubles en catalogue + décoration bloc par bloc |
| Banque | Prêts plafonnés selon les revenus |
| Événements | Commandes mondiales, concours de villes, boss du week-end |
| Alliances | Plus tard |
| Boutique | Cosmétiques, confort et boosts collectifs ; pass gratuit + premium ; grades définitifs + abonnement |
| Technique | BDS + Endstone uniquement : PocketMine-MP n'exécute pas les add-ons (Script API), indispensables au gameplay |
| Modération | Filtre automatique + staff |
| Lancement | Quand c'est prêt, sans date imposée |
| Île principale | 32 × 32 chunks (512 × 512 blocs) une fois complète, découpée en zones débloquées par niveau de mairie, à prix fixe en Lingots par zone |
| Développement | Tout développé maison, sans add-on Skyblock existant |
| Monnaies | Donar : explose de quelques k aux « aa », repart de zéro à chaque prestige · Lingots : monnaie stable de la ville et du marché, jamais remise à zéro · Gemmes : monnaie de boutique achetée en euros sur le site |
| Nom | Silooo, play.silooo.com (UrbaFactory abandonné le 4 nov. 2026) |
| Époques | 4 : usines, véhicules, nouvelles usines et bâtiments changent ; la ville suit au choix du joueur |
| Usines | 12 à 15 à terme ; chaîne du fer complète au lancement ; produits finis vendus, utilisés en ville, livrés en commandes ou au marché |
| Salariés légendaires | Bonus de production + apparence et nom uniques |
| Boss | Catastrophes industrielles |
| Arrivée | Mini-histoire (héritage d'une carrière abandonnée), nom d'entreprise dès le début |
| Saisons | Ne remettent à zéro que le pass et les classements ; îles, ville et Donar ne bougent jamais |
| Votes | Récompense à chaque vote (Hubyy et sites de vote) + vote party quand le serveur atteint un objectif collectif |
| Parrainage | Cosmétique pour le parrain quand le filleul atteint sa 2e usine, sans échange de Donar |
| Alertes | Bot Discord (message privé si le compte est lié, désactivable) : silo plein, commande qui expire |
| Commandes | Tableau de 3 à 5 contrats par jour (délai, prime, refus possible) ; réputation d'entreprise qui débloque de meilleurs contrats et candidats |
| Hub | Bourse et marché, lootbox, boutique en mur comme SileneMC, courses de véhicules, mini-jeux, lieux d'événements et arènes |
| Zone AFK | Rapporte des jetons AFK, échangés contre clés et cosmétiques ; jamais de Donar |
| Lootbox | Plusieurs gammes à prix différents ; clés gagnées en jeu ou achetées en monnaie de boutique |
| Îles d'usines | L'usine est fixe, le reste de l'île se décore librement ; chute dans le vide = retour au dernier point sûr sans perte ; aucun monstre hostile |
| Identité d'entreprise | Nom, logo, couleurs, slogan, uniformes des salariés ; visibles sur enseignes, véhicules et fiche publique |
| Site | Wiki et guides, classements en direct, fiche publique par entreprise, boutique et vote |
| Discord et staff | Discord complet dès le début ; rôles : modérateurs, helpers, builders, animateurs |
| Alpha | Ouverte à tous |
| Appareils | Fluide partout, Switch et vieux mobiles compris : animations et entités limitées |
| Capacité | Plus de 200 joueurs simultanés visés : plusieurs instances derrière un proxy Bedrock |
| Données | Base de données dédiée à Silooo ; sauvegarde de la base toutes les heures, du monde chaque nuit, copie hors du serveur |
| Agence d'emploi | 5 candidats par jour, liste tirée de l'entreprise et du jour, rien à acheter pour la changer (décision 33) ; 75 % communs, 20 % rares, 5 % légendaires ; aucun départ sauf renvoi ; directeur = chef promu après une mission |
| Commandes mondiales | Une par jour, récompensée en jetons |
| Réputation | Livraisons à temps + votes des joueurs sur la ville ; améliore contrats et candidats |
| Jetons de prestige | Remplacés par les Lingots, qui se dépensent dans la boutique de prestige (skins, bâtiments rares, salarié légendaire garanti) |
| Énergie | Électricité obligatoire pour les usines des époques 3 et 4 ; robots sans salaire mais consommateurs d'électricité |
| Rythme | 1er prestige en 10 à 15 h, puis environ deux fois plus long par époque |

## Boucle de jeu

Deux boucles imbriquées : une boucle courte qui fait progresser à chaque session, et le prestige qui relance tout avec un bonus permanent.

&#91;embedded content: boucle de jeu · courte et longue\]

La production continue quand le joueur est déconnecté : 8 h au départ, jusqu'à 24 h et plus avec les améliorations du chef d'équipe et du silo ; les salaires hors ligne se paient sur cette production. Les durées sont des cibles d'équilibrage à vérifier pendant l'alpha.

## Le monde : un archipel par joueur

Chaque joueur possède son archipel privé : une île principale immense à débloquer chunk par chunk, entourée de mini-îles, une par usine.

&#91;embedded content: archipel d’un joueur · exemple en cours de partie\]

Le joueur démarre sur l'île de sa Carrière, l'île principale entièrement verrouillée. Chaque usine possédée reçoit un pont vers l'île principale dès que celle-ci est débloquée. La première zone s'achète après la 2e usine. Complète, l'île principale fait 32 × 32 chunks (512 × 512 blocs), découpée en zones que la mairie débloque niveau par niveau.

## La carte et le déblocage d'une usine

La carte est le cœur de la navigation : elle montre tout l'archipel du joueur, ce qu'il possède et ce qui reste à conquérir.

**Ce que montre la carte**

- Les usines possédées, en couleur, avec leur niveau et leur stock (silo plein ou non).
- Les usines à débloquer, en noir avec « ????? » : on devine la silhouette de l'île, pas ce qu'elle produit.
- L'île principale, avec ses chunks débloqués et verrouillés.
- Un bouton par île : se téléporter (disponible dès la première usine) ou afficher l'itinéraire.

**Débloquer une usine : l'intégration en 4 étapes**

1. **Découverte** : quand le joueur atteint le niveau d'entreprise requis, le « ????? » se révèle et l'usine apparaît, abandonnée.
2. **Rachat** : le joueur paie le prix de l'usine en pièces auprès du PNJ notaire.
3. **Remise en état** : 3 à 5 missions courtes sur l'île de l'usine (réparer la machine, nettoyer l'atelier, recruter les premiers salariés, livrer une première commande).
4. **Prise de possession officielle** : courte cérémonie animée, enseigne avec le nom de l'entreprise du joueur, l'usine démarre. C'est aussi un bon moment à filmer pour TikTok.

**Mise en œuvre** : d'abord un formulaire natif Bedrock avec une icône par île (rapide à construire, lisible à la manette), puis une vraie carte illustrée en interface personnalisée une fois le jeu validé.

## Usines et personnel

Chaque usine est une vraie entreprise : des salariés qui produisent, un chef d'équipe qui fait tourner l'atelier, un directeur qui pilote l'île. Le joueur n'empile pas des machines : il embauche et il gère.

**Les usines** : la liste complète, avec les entrées de chaque usine et leur époque, est dans le Catalogue des usines plus bas.

Le **niveau d'entreprise** est la somme des niveaux de toutes les usines du joueur ; il révèle les « ????? » de la carte.

**La hiérarchie**

| Rôle | Combien | Ce qu'il fait |
| --- | --- | --- |
| Salarié | Plusieurs par usine, nombre de postes selon le niveau de l'usine | Produit : chaque salarié ajoute une part de production |
| Chef d'équipe | 1 par usine | Vide le silo vers l'entrepôt : avec lui, l'usine produit hors ligne jusqu'à son plafond (8 h, extensible par améliorations) ; sans lui, elle s'arrête quand son stock est plein |
| Directeur | 1 par île d'usine | Bonus de production de toute l'île, vente automatique ; c'est un chef d'équipe expérimenté promu après une mission sur l'île |

**Les salaires** sont payés en pièces à chaque cycle : c'est la principale sortie d'argent du jeu, ce qui freine l'inflation. Hors ligne, les salaires se paient sur la production de la période et ne font jamais passer le compte en négatif : pas de grève pendant une absence. En ligne, un salaire impayé met l'usine en grève (production à l'arrêt, sans autre perte) jusqu'au paiement.

**Côté visuel** : quelques salariés animés par usine (vos modèles Blockbench), visibles seulement quand quelqu'un est sur l'île ; le reste existe en chiffres. Le directeur est un PNJ fixe dans son bureau, habillable avec des skins cosmétiques.

**Recrutement** : une agence d'emploi propose chaque jour 5 candidats (la liste est tirée de l'entreprise et du jour ; rien ne s'achète pour la changer, décision 33), avec 75 % de communs, 20 % de rares et 5 % de légendaires ; une bonne réputation d'entreprise améliore ces chances. Le joueur choisit qui embaucher. Un salarié ne part jamais de lui-même : seulement s'il est renvoyé. Un salarié gagne de l'expérience en travaillant et peut être promu chef d'équipe.

**Chaînes de production** : les usines s'alimentent entre elles (par exemple minerai, puis barres de fer à la Fonderie, puis produits finis). Les marchandises voyagent de façon visible : camions sur les ponts, bateaux et avions cargo entre les îles, à acheter et à améliorer. Une chaîne mal alimentée ralentit, ce qui donne au joueur de vraies décisions de logistique.

**Point de vigilance** : les chaînes profondes sont la partie la plus longue à développer et à équilibrer. Au lancement, une seule chaîne complète ; les autres arrivent en mises à jour.

## Époques et chaînes de production

Quatre époques, une par prestige : chacune change l'apparence des usines, les véhicules de la logistique, ajoute une ou deux usines et de nouveaux bâtiments pour la ville. 12 à 15 usines à terme, environ 3 à 4 par époque. Après la 4e époque, chaque prestige ajoute une étoile et un petit bonus permanent, sans fin, en attendant les nouveautés.

| Époque | Usines (style) | Logistique | Ville |
| --- | --- | --- | --- |
| 1 · Artisanale | Bois, pierre, moulins | Charrettes, barques | Maisons de village, marché |
| 2 · Vapeur | Briques, chaudières, cheminées | Camions à vapeur, bateaux à aube | Immeubles de briques, gare |
| 3 · Moderne | Acier, béton, chaînes automatisées | Camions, cargos, avions | Tours, centres commerciaux |
| 4 · Futuriste | Verre, néons, robots | Drones, véhicules volants | Gratte-ciel, places végétalisées |

La ville ne change pas d'époque toute seule : les styles débloqués s'ajoutent au catalogue, et le joueur transforme ses bâtiments quand il le veut.

**La chaîne du lancement : le fer**

1. Carrière : pierre et charbon (combustible).
2. Mine profonde : minerai de fer.
3. Fonderie : minerai + charbon → barres de fer.
4. Atelier d'outils : barres de fer → outils et pièces métalliques, le produit le plus cher.

Scierie et Ferme existent dès le lancement en production simple ; leurs chaînes (menuiserie, conserverie) arrivent en mises à jour.

**Les débouchés d'un produit fini**

- Vente au coffre, au meilleur prix de la chaîne.
- Matériaux de construction de la ville (briques, verre, poutres, meubles).
- Commandes clients : contrats de PNJ avec un délai et une prime ; les commandes mondiales en sont la version collective.
- Marché entre joueurs, taxé à 10 %.

**Les boss du week-end : des catastrophes industrielles** (machine folle, golem de scories, tempête sur l'archipel), avec une variante par époque. Tous les joueurs les affrontent ensemble ; la récompense est un cosmétique ou un bonus temporaire pour tout le serveur, jamais une ressource rare qui pèserait sur l'économie.

**Les incidents d'usine** (décidé le 4 nov. 2026) : de temps en temps, une usine a un petit incident (début d'incendie, machine en panne, courroie cassée). Elle ralentit et une icône apparaît sur la carte ; un mini-jeu d'une trentaine de secondes (éteindre le feu, réparer la machine) la relance et donne une petite récompense. Jamais de perte, et aucun incident pendant que le joueur est hors ligne : c'est une animation qui donne envie de passer voir ses usines, pas une punition.

**Salariés légendaires** : production plus élevée, apparence unique et nom propre, de quoi en faire des personnages que les joueurs reconnaissent et montrent.

## Catalogue des usines

15 usines réparties sur les 4 époques et organisées en 6 chaînes ; la chaîne du fer est la colonne vertébrale et la première complète.

&#91;embedded content: chaînes de production · 15 usines\]

La Carrière fournit le charbon de la Fonderie, de la Briqueterie et de la Centrale ; l'Élevage nourrit à la fois la Conserverie (viande) et l'Usine textile (laine).

| Époque | Usine | Produit | Entrées | Découverte |
| --- | --- | --- | --- | --- |
| 1 · Artisanale | Carrière | Pierre, sable, charbon | — | Possédée au départ |
| 1 · Artisanale | Scierie | Bois | — | Niveau d'entreprise 2 |
| 1 · Artisanale | Ferme | Blé, légumes | — | Niveau d'entreprise 3 |
| 1 · Artisanale | Mine profonde | Minerai de fer | — | Niveau d'entreprise 4 |
| 2 · Vapeur | Fonderie | Barres de fer | Minerai + charbon | Passage à l'époque 2 |
| 2 · Vapeur | Atelier d'outils | Outils, pièces métalliques | Barres de fer | Après la Fonderie |
| 2 · Vapeur | Briqueterie | Briques | Pierre + charbon | Époque 2 |
| 2 · Vapeur | Élevage | Viande, laine, cuir | Blé | Époque 2 |
| 3 · Moderne | Centrale | Électricité | Charbon | Passage à l'époque 3 |
| 3 · Moderne | Verrerie | Verre | Sable + électricité | Époque 3 |
| 3 · Moderne | Menuiserie | Planches, meubles, poutres | Bois + électricité | Époque 3 |
| 3 · Moderne | Conserverie | Conserves | Légumes + viande + électricité | Époque 3 |
| 3 · Moderne | Usine textile | Tissu, uniformes | Laine + électricité | Époque 3 |
| 4 · Futuriste | Usine de robots | Robots | Pièces + électricité | Passage à l'époque 4 |
| 4 · Futuriste | Cristallerie | Cristaux | Minerai + électricité | Époque 4 |

**L'électricité, ressource obligatoire** : à partir de l'époque 3, les usines modernes s'arrêtent sans courant. La Centrale brûle du charbon et produit aussi hors ligne, dans la même limite que les autres usines ; quand le courant manque, l'alerte Discord prévient le joueur. Garde-fou : une usine à l'arrêt faute de courant ne coûte pas de salaire.

**Les robots** (époque 4) remplacent des salariés : pas de salaire, mais une consommation d'électricité et un entretien en pièces. C'est un vrai choix de gestion, et une sortie d'argent de plus en fin de jeu.

**Remise en état d'une usine** (étape 3 du déblocage), toujours dans cet ordre :

1. Dialogue avec l'ancien propriétaire, qui raconte l'histoire du lieu (une minute au plus, passable).
2. Petites tâches sur place : nettoyer, rallumer la chaudière, réparer une courroie.
3. Livraison des matériaux de réparation (planches, barres de fer, briques selon l'époque) : relie l'usine aux chaînes déjà possédées.
4. Recrutement de l'équipe de départ : premiers salariés et chef d'équipe, à l'agence d'emploi.

## Rythme de progression

Le 1er prestige arrive après 10 à 15 heures de jeu ; chaque époque suivante demande environ deux fois plus de temps, pour que le jeu dure des mois sans reset. Ce sont des cibles d'équilibrage, à mesurer pendant l'alpha.

| Étape | Temps de jeu cumulé visé | Ce que ressent le joueur |
| --- | --- | --- |
| Première vente | 2 minutes | « Ça marche tout de suite » |
| 2e usine (Scierie) | 15 à 20 minutes | Première découverte sur la carte |
| 1re zone de ville | environ 1 heure | La ville commence |
| Mine profonde | 2 à 3 heures | La chaîne du fer se dessine |
| 1er prestige, époque vapeur | 10 à 15 heures | Fonderie et Atelier d'outils : la chaîne du fer est complète |
| 2e prestige, époque moderne | 30 à 40 heures | Électricité, nouvelles chaînes |
| 3e prestige, époque futuriste | 70 à 100 heures | Robots et Cristallerie |
| Étoiles de prestige | ensuite, environ 20 à 30 heures par étoile | Progression sans fin, en attendant les nouveautés |

**Comment on tient ces cibles** : la croissance du prix par niveau, les seuils de prestige et la production par étoile sont les trois réglages principaux ; le premier est de loin le plus sensible (passer de 1,11 à 1,12 rallonge le 1er prestige de 15 à 17 heures). Le tableau de bord mesure le temps réel passé à chaque étape ; si un palier prend deux fois plus longtemps que prévu chez la plupart des joueurs, c'est lui qu'on ajuste, sans toucher à ce que les joueurs possèdent déjà. Le tableur « UrbaFactory-equilibrage-v2.xlsx » simule un joueur type jusqu'à la 6e étoile (environ 215 heures de jeu) : avec les valeurs proposées, les 13 étapes tombent dans leur cible (Scierie à 17 minutes, Mine profonde à 2 heures, prestiges à 15, 37 et 82 heures, étoiles en 20 à 26 heures).

## Se déplacer

Les déplacements évoluent avec l'île principale : téléportation par la carte dès la première usine, puis ponts, avion et véhicules en plus une fois l'île principale débloquée.

| Moment | Moyen | Usage |
| --- | --- | --- |
| Dès la première usine, toujours | Téléportation depuis la carte | Aller d'une usine à l'autre |
| Île principale débloquée | Ponts | Relient l'île principale à chaque île d'usine possédée |
| Île principale débloquée | Avion personnel | Vol libre entre toutes les îles de l'archipel |
| Sur l'île principale | Véhicules (voiturette, quad…) | Traverser une île immense sans s'ennuyer |

Par « île principale débloquée », ce document entend le premier chunk acheté ; les chunks suivants agrandissent l'île au fil de la progression.

**Une bonne source de revenus** : les skins d'avion, de véhicules et de ponts sont des cosmétiques très visibles, parfaits pour la boutique.

**Technique** : l'avion et les véhicules sont des entités personnalisées de l'add-on, chevauchables. Chaque île d'usine ne se charge que quand quelqu'un s'y trouve ou la survole.

## La ville : l'île principale à bâtir

L'île principale devient peu à peu la ville du joueur : il débloque des zones, trace des routes, construit des bâtiments et y loge ses salariés, des civils et ses amis, avec sa propre maison à améliorer, un peu comme dans les Sims.

**Zones débloquées par la mairie** : l'île principale est découpée en 16 zones de 8 × 8 chunks. Chaque niveau de mairie débloque la zone suivante, achetée en Lingots à un prix fixe connu à l'avance ; la première arrive vers 1 heure de jeu, payée par la mission de bienvenue de la mairie. Le prix fixe rend l'objectif lisible (« plus que 15 Lingots pour la zone 3 ») et la mairie devient le fil rouge de la ville. Prix proposés : de 5 à 235 Lingots par zone, 1 400 au total. Le joueur type, qui dépense la moitié de ses Lingots en ville, a 4 zones au 1er prestige, 7 au 3e, et termine la ville vers 500 heures de jeu, soit environ un an à 1 h 30 par jour (rythme validé le 4 nov. 2026).

**Le Silo** (décidé le 4 nov. 2026) : bâtiment central de l'île principale, posé dès le départ. Toutes les marchandises des usines y arrivent avant d'être vendues : camions, bateaux et avions cargo convergent vers lui, ce qui rend la logistique visible. Il s'améliore par niveaux (capacité, vitesse de vente) et grandit à l'écran à chaque niveau. C'est lui qui donne son sens au nom du jeu, et Oooscar, la mascotte, est un petit silo rigolo et maladroit qui accompagne le joueur : il guide le tutoriel, présente les missions et fête chaque réussite (un peu trop fort). Il parle par bulles de texte très courtes, une phrase de 6 mots au plus. Il sert aussi de visage du jeu dans les vidéos TikTok et sur le Discord.

**L'outil de construction** : un mode construction ouvert depuis le menu. Le joueur choisit un bâtiment dans un catalogue, voit un aperçu fantôme, le tourne, le pose sur une parcelle et confirme ; les routes se tracent d'un point à un autre. Les bâtiments sont des modèles prêts, améliorables par niveaux ; autour et à l'intérieur, la décoration est libre, bloc par bloc, avec en plus un catalogue de meubles prêts.

| Bâtiment | Rôle dans le jeu |
| --- | --- |
| Mairie | Cœur de la ville : ses niveaux débloquent les nouveaux bâtiments et agrandissent la ville |
| Routes | Relient les ponts et les bâtiments, les véhicules y roulent plus vite |
| Maisons et immeubles | Logent salariés et civils : plus de candidats chaque jour à l'agence d'emploi, et un salarié logé coûte moins cher en salaire |
| Votre maison | Améliorable et décorable, lieu où l'on reçoit ses amis |
| Magasins et commerces | Petit bonus de production de Donar, qui grandit avec le nombre d'habitants |
| Garages | Achat des véhicules et de l'avion en pièces ; chaque niveau rend les camions de livraison plus rapides ; les apparences sont en boutique |
| Banque | Prêts pour acheter chunks et usines plus tôt, remboursés avec intérêts (sortie d'argent) |

**Des bonus visibles** (décidé le 4 nov. 2026) : chaque bâtiment affiche son bonus en icône au-dessus de l'entrée (« +5 % » à côté d'un engrenage, par exemple), compris sans lire. Les bonus restent petits et plafonnés : la ville rend la partie plus agréable sans remplacer les usines ni les étoiles. Ils jouent uniquement sur le Donar, le recrutement et la logistique, jamais sur les Lingots, pour que la monnaie stable le reste. Valeurs exactes à calibrer avec le tableur.

**Les habitants**

- Salariés : logés en ville, ils coûtent moins cher et vont au travail par les ponts (visible quand le joueur est présent).
- Civils : arrivent seuls quand il y a des logements libres et des commerces ; ils font grimper la population, mesure de la ville.
- Amis : un joueur invité peut emménager dans une maison de la ville et la décorer.

**Garder le jeu simple** : pas de besoins à gérer (faim, humeur) comme dans les Sims ; la ville sert à progresser et à se montrer, pas à surveiller.

**Technique** : bâtiments posés comme des structures préfabriquées de l'add-on, placées par script ; habitants visibles en petit nombre, le reste en chiffres. À valider pendant le prototype.

**Périmètre en solo** : la ville est presque un second jeu. Au lancement, seulement la mairie, votre maison, les logements des salariés et les routes ; magasins, garages, banque et civils arrivent pendant la saison 1 et après.

## Contenu

Cette section décrit le contenu cible ; ce qui est présent au lancement est détaillé dans la Roadmap (version de lancement).

**Point de départ** : le joueur arrive sur l'île de sa Carrière, déjà en marche, avec un coffre de vente et un PNJ guide. Rien à construire avant de jouer.

**Prestige** : quand l'entreprise atteint le dernier palier, le joueur peut revendre ses usines (ville, maison et véhicules restent) : les usines repartent à zéro et l'entreprise passe à l'époque suivante, contre des Lingots : le Donar accumulé pendant la course se convertit en Lingots, qui augmentent la production de Donar tant qu'on les garde (voir Économie à deux couches). Les Lingots se dépensent aussi dans la boutique de prestige : décorations, bâtiments rares, skins d'usines animées, et un salarié légendaire garanti.

**Objectifs courts**

- 3 quêtes quotidiennes de 5 minutes chacune.
- Une carte Bingo de 5 × 5 objectifs, renouvelée chaque lundi : chaque ligne complétée donne une récompense, la carte entière un cosmétique de la semaine.

**Événements** (annoncés sur Discord et en jeu)

- Commande mondiale quotidienne : tout le serveur livre ensemble une commande ; chaque participant reçoit des jetons, échangés contre clés et cosmétiques.
- Concours de villes sur un thème, avec vote des joueurs, et un boss PvE commun chaque week-end.

**Animations** : salariés au travail, machines qui tournent et s'allument, coffre de vente qui avale les objets, effet de prestige visible par toute l'île, pets qui suivent le joueur.

**Social** : entreprise partagée jusqu'à 4 associés (le PDG décide ventes, reventes et prestige : une seule course commune pour toute l'entreprise, et les Lingots du prestige sont partagés selon la part que le PDG attribue à chacun ; les associés gèrent au quotidien), visite publique des villes avec vote des plus belles, classement hebdomadaire de la progression de la semaine (pas de la richesse totale).

## Interface et onboarding console

Objectif : moins de 2 minutes entre la connexion et la première vente, mise en scène comprise, sans lire une seule règle.

1. Connexion : 30 secondes de mise en scène, le joueur hérite d'une vieille carrière abandonnée, puis donne un nom à son entreprise (filtré par la modération, affiché sur les enseignes et au classement).
2. Le PNJ guide montre la Carrière qui tourne déjà.
3. Le joueur casse quelques blocs, les jette dans le coffre de vente : première animation et premières pièces.
4. Le PNJ propose d'embaucher un premier salarié : premier achat, production qui accélère.
5. Fin du tutoriel : le menu principal s'ouvre une fois pour montrer les 4 boutons.

**Règles d'interface**

- Un seul objet de menu dans la barre (une boussole) ; tout passe par des formulaires natifs Bedrock, lisibles à la manette.
- Quatre boutons au menu principal : Carte, Usines, Objectifs, Amis. Toute action en 2 clics maximum.
- Textes courts, aucun bloc de plus de deux lignes ; les règles du serveur sont sur le site et le Discord, pas en jeu à l'arrivée.
- Aucune liaison de compte obligatoire (Discord, site) pour jouer.

## Comprendre sans lire

Le jeu est joué par une communauté de tout âge, dont des enfants : chaque écran et chaque mécanique doivent se comprendre en regardant, sans lire une phrase. Règle de validation : un enfant de 8 à 10 ans qui découvre le jeu doit faire sa première vente et sa première embauche seul, en moins de 3 minutes.

| Principe | En pratique |
| --- | --- |
| Une monnaie = une icône et une couleur | Donar : pièce jaune ; Lingots : lingot d'or ; Gemmes : gemme. Toujours affichées au même endroit de l'écran |
| Un seul objectif à la fois | Un objectif permanent en haut de l'écran (« Embauche 1 salarié »), avec une flèche dans le monde qui montre où aller |
| Montrer plutôt qu'expliquer | Le tutoriel se joue : le PNJ montre, le joueur fait. Objets qui brillent, flèches au sol, pas de pavé de texte |
| Boutons lisibles | Icône + 1 ou 2 mots. Vert = possible, gris = pas assez d'argent, avec une barre qui montre combien il manque |
| Nombres simples | Toujours abrégés (1,2 M, 3,4 aa), jamais de formule visible ; « ×2 plus vite » plutôt que « multiplicateur de production » |
| Chaque action a un retour | Animation, son et particules à chaque vente, embauche, amélioration ; les usines grandissent visiblement |
| Le prestige se voit | Écran animé avant/après : « Tu revends tes usines → tu gagnes 12 ★ », avec ce que ça débloque en images |
| Raretés universelles | Gris = commun, bleu = rare, or = légendaire, partout (salariés, lootbox, cosmétiques) |
| Vocabulaire simple | Mots courts du quotidien : « usine », « salarié », « vendre », « améliorer » ; pas de jargon économique en jeu |

Les mécaniques expertes (conversion en racine cubique, bonus par Lingot détenu, chaînes profondes) restent sous le capot : le joueur en voit seulement l'effet, sous forme d'icônes et de chiffres qui montent.

## Économie et boutique

Trois monnaies séparées : le Donar, gagné en jouant (appelé « pièces » dans le reste de ce document), les Lingots, monnaie stable (voir Économie à deux couches), et les Gemmes, monnaie de boutique achetée en euros sur le site. Le nom « Gemmes » est compris par tous les âges, colle à l'icône, et ne reprend ni « Minecoins » (marque déposée) ni la monnaie SileneMC.

**Équilibrage des pièces** : la production d'une usine se calcule à partir du temps écoulé, pas en simulant chaque bloc. Le coût de chaque niveau suit une courbe (par exemple ×1,6 par niveau) réglée pendant le prototype pour que le premier prestige arrive vers 10 à 15 heures de jeu.

**Catalogue de la boutique** (même système que SileneMC : seules les Gemmes s'achètent en euros sur le site, au même taux que les cristaux de SileneMC, décidé le 4 nov. 2026 : 50 Gemmes pour 0,99 €, puis des packs de 275, 600, 1 300 et 3 500 Gemmes de plus en plus avantageux ; tout le reste s'achète avec elles dans la boutique en jeu ; les prix en euros ci-dessous indiquent l'équivalent en Gemmes, à tester pendant l'alpha)

| Article | Contenu | Prix indicatif |
| --- | --- | --- |
| Packs de Gemmes | 5 tailles, plus de Gemmes offertes sur les gros packs (jusqu'à +40 %), comme les cristaux de SileneMC | 0,99 € à 49,99 € |
| Skin d'usine animée | Apparence d'une usine, aucun effet sur la production | 150 à 400 Gemmes |
| Skin de véhicule, d'avion ou de pont | Apparence seulement ; les véhicules s'achètent en pièces au garage | 200 à 600 Gemmes |
| Pet | Modèle Blockbench animé qui suit le joueur | 300 à 800 Gemmes |
| Meubles et décor premium | Objets rares pour la maison et la ville | 50 à 300 Gemmes |
| Effet de prestige | Animation jouée à chaque prestige | 250 Gemmes |
| Boost collectif | Production +25 % pour tout le serveur pendant 1 h, pseudo de l'acheteur annoncé | 1,99 € à 4,99 € |
| Pass de saison premium | Paliers cosmétiques en plus des paliers gratuits | environ 4,99 € par saison |
| Grades à vie, les mêmes que SileneMC : MVIP, VIP, VIP+, Héros, Légende, Ultime, à prendre dans l'ordre | Préfixe et couleur, confort (vol sur ses îles, emplacements de visite, file prioritaire) et bonus de Donar, comme le bonus de coins de SileneMC : +5 % (MVIP, VIP), +10 % (VIP+), +20 % (Héros), +30 % (Légende), +50 % (Ultime) | Les nombres de SileneMC : 100, 500, 1 000, 2 000, 3 500 et 6 000 Gemmes |
| Abonnement Pilier (28 jours) | Comme sur SileneMC : badge ✦ qui évolue avec les mois, +10 % de Donar en plus du grade, 50 Gemmes rendues chaque mois ; option préfixe perso réservée aux Ultime | 400 Gemmes les 28 jours |

**Interdits** : pièces, usines, salariés, niveaux, multiplicateurs personnels et ressources à la vente . Lootbox : plusieurs gammes à prix différents au hub, clés gagnées en jeu (votes, jetons AFK, événements) ou achetées en monnaie de boutique ; taux de chance affichés pour chaque objet.

**Obligations** : prix affichés sur le site et la fiche Hubyy avant la connexion, historique d'achats consultable, mention « NOT AN OFFICIAL MINECRAFT SERVICE » sur le site.

## Économie à deux couches

Le Donar explose, de quelques milliers aux « aa », et repart de zéro à chaque prestige ; les Lingots, stables, portent tout ce qui dure et se partage. L'inflation est voulue dans la première couche et impossible dans la seconde. Le serveur, lui, n'a jamais de reset.

| Couche | Monnaie | Évolution | Sert à | Remise à zéro |
| --- | --- | --- | --- | --- |
| Course | Donar | Exponentielle : k, M, Md, puis aa, ab… | Usines, salariés, améliorations, logistique | À chaque prestige, au choix du joueur |
| Patrimoine | Lingots | Lente, jamais d'explosion | Zones et bâtiments de la ville, marché entre joueurs, boutique de prestige | Jamais |
| Boutique | Gemmes | Achetée en euros | Boutique en jeu | Jamais |

**Faire exploser le Donar** : chaque usine gagne un multiplicateur ×2 quand elle atteint 25, 50, 100, 200… salariés ou niveaux cumulés, et chaque Lingot détenu ajoute +1 % à toute la production de Donar. Dépenser ses Lingots en ville réduit donc ce bonus : c'est le vrai choix stratégique du joueur, entre aller plus vite et bâtir.

**La conversion au prestige** : Lingots gagnés = coefficient × racine cubique (Donar gagnés pendant la course ÷ référence), la référence valant le seuil de prestige de la course ÷ 1 000. Au seuil, le joueur gagne 100 Lingots ; attendre jusqu'à 8 fois le seuil n'en donne que le double. Un joueur deux fois plus rapide gagne environ 26 % de Lingots en plus, pas le double. Comme la référence suit le seuil, les Lingots restent petits et réguliers (environ 100 par prestige) même quand le Donar passe aux « ac » ou aux « ag » : sans cela, la racine cubique d'un nombre qui explose exploserait aussi. Les commandes, les événements et les votes donnent en plus environ 1 Lingot par heure de jeu.

**Les étoiles** : après le 3e prestige, chaque prestige donne une étoile. Une étoile multiplie toute la production par 300, pour toujours, et l'étoile suivante demande environ 1 000 fois plus de Donar : chaque étoile ajoute à peu près une lettre (ac, ad, ae…). Le Donar peut monter ainsi jusqu'à environ 10^308 (suffixe « du »), soit une centaine d'étoiles et plus de 2 000 heures de jeu ; une notation plus large pourra prendre le relais d'ici là.

**Seuils de prestige proposés** : 1 Md (1er prestige), 500 Md (2e), 1 ab (3e), puis 200 ab, 500 ac, 500 ad, 300 ae, 300 af et 100 ag pour les six premières étoiles. Prix des usines : chaque niveau coûte 1,11 fois le précédent, et chaque époque vaut environ mille fois la précédente.

**Notation et technique** : affichage en k, M, Md, puis aa, ab… az, ba, etc. Les scripts Bedrock utilisent des nombres à virgule qui montent jusqu'à environ 10^308 ; en base de données, le Donar se stocke en DOUBLE (un entier BIGINT déborde vers 9,2 × 10^18).

**La boutique la plus vendeuse dans ce modèle** (proposition, à valider)

| Article | Pourquoi ça vend | Effet sur la couche partagée |
| --- | --- | --- |
| Grade à vie et abonnement Pilier avec bonus de Donar permanent (+5 % à +50 %, +10 % avec le Pilier) | Le premier achat des jeux idle : utile à chaque course | Faible : la racine cubique l'écrase en Lingots |
| Avance rapide (2 h ou 8 h de production immédiate) | Achat d'impulsion, quand un déblocage est proche | Faible |
| Boosts ×2 temporaires, personnels et collectifs | Visibles, les collectifs font plaisir à tout le serveur | Faible |
| Plafond hors ligne étendu (8 h → 12 ou 16 h) | Pratique pour ceux qui jouent peu | Faible |
| Pack de départ unique à petit prix | Fait passer un joueur de gratuit à payeur | Nul |
| Pass de saison, cosmétiques, lootbox | Collection et apparence | Nul |

**Jamais en vente** : Lingots, zones de ville, places au classement. Les classements hebdomadaires se mesurent en Lingots gagnés dans la semaine, ce qui limite l'effet des achats. Ce modèle revient à « payer pour aller plus vite » : courant sur les serveurs tycoon, mais la promesse affichée aux joueurs Hubyy devient « tout ce qui s'achète se gagne aussi en jouant », et non plus « 0 pay-to-win ».

## Économie sur le long terme, sans reset

Sans reset de serveur, deux dangers guettent : l'inflation (les pièces s'accumulent, plus rien n'a de valeur) et l'écart entre anciens et nouveaux joueurs. Chaque règle ci-dessous répond à l'un des deux. Le prestige reste un choix personnel du joueur, jamais un reset imposé.

**1. Chaque entrée d'argent a une sortie en face**

| Entrées de pièces | Sorties de pièces |
| --- | --- |
| Ventes au coffre | Salaires (sortie principale, à chaque cycle) |
| Quêtes, Bingo, événements | Rachat d'usines, chunks, bâtiments, améliorations |
| Commerces de la ville | Entretien de la ville (taxe proportionnelle au nombre de bâtiments) |
| Prêts de la banque | Intérêts des prêts |
| — | Cosmétiques et décorations achetables en pièces (sortie pure, sans effet sur la production) |

Objectif : sur une semaine, les sorties détruisent au moins 80 % de ce que les entrées créent (seuil de départ, à ajuster).

**2. Des courbes qui ne s'emballent pas**

- Les coûts montent plus vite que la production (coûts exponentiels, production qui grandit par paliers) : chaque niveau demande un peu plus de temps de jeu que le précédent, sans jamais devenir impossible.
- Les prix de rachat du coffre baissent quand une ressource est vendue en masse sur le serveur, puis remontent.
- Les grands nombres s'affichent en k, M, Md et sont stockés dans un format qui ne déborde pas.

**3. Les échanges entre joueurs sont encadrés**

Le danger principal d'une économie sans reset est le transfert de richesse : un ancien joueur ou un multi-compte qui finance un nouveau. Règle retenue : pas de don de monnaie entre joueurs, et un marché de ressources taxé à 10 %, en Lingots sur chaque vente (nouvelle sortie d'argent).

**4. Les nouveaux joueurs gardent une chance**

- Les coûts d'amélioration des usines suivent les revenus du joueur : un nouveau joueur progresse au même rythme ressenti qu'un ancien.
- Les classements hebdomadaires mesurent la progression de la semaine (Lingots gagnés dans la semaine), pas la richesse totale : un joueur arrivé hier peut gagner.
- Un bonus de rattrapage temporaire pour les nouveaux comptes (par exemple production doublée la première semaine).

**5. Du contenu qui ne s'épuise pas**

Niveaux de mairie sans plafond fixe, nouvelles usines et nouveaux bâtiments ajoutés par mises à jour, saisons de cosmétiques et d'objectifs sans remise à zéro des îles.

**6. Surveiller et corriger sans casser**

- Tableau de bord hebdomadaire : masse de pièces totale, pièces créées et détruites, richesse médiane, temps moyen pour débloquer chaque usine.
- Chaque transaction est journalisée : en cas de faille (duplication), on corrige le compte concerné, pas tout le serveur.
- Aucune baisse rétroactive sans prévenir : un ajustement s'annonce sur Discord une semaine avant, et vise les nouveaux achats, pas ce que les joueurs possèdent déjà.
- Les boosts collectifs vendus en boutique augmentent la création de pièces : ils entrent dans le tableau de bord comme une entrée d'argent à compenser.

## Travailler la réticence des joueurs Hubyy

Un joueur console hésite pour trois raisons : il ne connaît pas le serveur, il craint le pay-to-win, et il a peur de perdre son temps. Chaque raison a sa réponse.

| Crainte | Réponse | Où |
| --- | --- | --- |
| « Je ne connais pas ce serveur » | Fiche Hubyy complète : vidéo de 20 secondes en jeu, capture du menu, nombre de joueurs en ligne | Annuaire Hubyy, réseaux |
| « C'est sûrement pay-to-win » | Phrase claire « tout ce qui s'achète se gagne aussi en jouant » + liste exacte de ce qui se vend, avec les prix | Fiche Hubyy, site, PNJ guide |
| « Je vais perdre mon temps » | Première vente en moins de 2 minutes, progrès visible dès la première session | En jeu |
| « Mes amis ne sont pas là » | Invitation d'un ami en un clic, récompense quand il rejoint l'île | En jeu, Discord |

**Règles de comportement de la boutique** : aucun message d'achat pendant la première heure de jeu ; aucune fenêtre de vente qui s'ouvre toute seule ; un cosmétique gratuit offert à la première connexion, visible par les autres, pour que le joueur reparte avec quelque chose.

**Mesure** : comparer chaque semaine le nombre d'ajouts de HubyOnMC qui rejoignent le serveur et la rétention à J1 ; si la rétention J1 reste basse, le problème est dans les 15 premières minutes, pas dans l'acquisition.

## Technique

Pile retenue (décidé le 4 nov. 2026) : le serveur officiel Bedrock (BDS) avec un add-on maison, et un service Kotlin, **silooo-core**, qui porte toutes les règles. L'add-on (TypeScript, Script API) gère tout ce qui se voit : usines, menus, carte, construction, Oooscar. Il ne décide de rien : chaque achat, vente ou prestige est une demande au service, qui calcule, écrit dans la base et répond. Les deux se parlent par des appels HTTP internes, que BDS sait faire nativement (module `@minecraft/server-net`, réservé aux serveurs dédiés). Endstone devient optionnel : on ne l'ajoute que pour ce que l'add-on ne sait pas faire (permissions, transfert entre serveurs). PocketMine-MP reste écarté : il n'exécute ni les add-ons ni la Script API.

&#91;embedded content: architecture de Silooo · qui affiche, qui décide, qui stocke\]

**Pourquoi ce découpage**

- **Même langage que SileneMC** : les calculs d'économie (grands nombres, prestige, conversion en Lingots) sont écrits en Kotlin et testés comme la boutique de SileneMC, et ce code pourra resservir si Silooo devient un mode de SileneMC.
- **Pas de triche par le client** : seul le service écrit l'argent ; l'add-on ne fait que demander et afficher.
- **Plan B intégré** : rien ne dépend d'Endstone. S'il pose problème, on s'en passe.
- **Gemmes** : le site de SileneMC (boutique et PayNow) crédite directement la base silooo ; le service le signale à l'add-on, qui fête l'arrivée des Gemmes en jeu.

**Organisation** (décidé le 4 nov. 2026)

- **Dépôt séparé** du monorepo SileneMC ; on reprend par copie ce qui sert (modèle des grades et des packs, migrations Flyway, conventions).
- **Hébergement** : au début, tout tourne en conteneurs Docker sur le VPS de SileneMC. Ce VPS est déjà serré en mémoire (un seul Gradle à la fois) : Silooo déménage sur sa propre machine avant l'alpha si la mémoire manque.
- **Base** : une base « silooo » à part dans le MariaDB de SileneMC, avec ses propres comptes (migrateur et application) et ses migrations Flyway. Le site de SileneMC reçoit seulement le droit de créditer les Gemmes.
- **Identité des joueurs** : l'identifiant Xbox (XUID). On stocke aussi l'UUID que Floodgate en déduit sur SileneMC, pour pouvoir relier les comptes si Silooo rejoint SileneMC.
- **Nombres** : Donar en DOUBLE ; Lingots et Gemmes en entiers (BIGINT), car ils ne doivent jamais être arrondis.

**À vérifier pendant le prototype**

- Le temps de réponse des appels add-on → service, qui doit rester imperceptible pour le joueur.
- `server-net` ne fait que des requêtes sortantes : le service ne peut pas « pousser » une info vers l'add-on. L'add-on l'interroge donc à intervalle court pour les événements (Gemmes reçues, commande terminée).
- Le module `server-net` doit être autorisé dans la configuration des permissions de BDS.
- La mémoire du VPS de SileneMC avec BDS et le service en plus.

**Données et échanges (socle v0.1, 4 nov. 2026)** : le schéma de la base, le contrat d'API et les fichiers de définitions sont prêts dans l'archive « silooo-socle », point de départ du dépôt. Le schéma a été testé sur un vrai MariaDB et l'API validée au format OpenAPI.

| Bloc | Tables principales | Ce qu'elles gardent |
| --- | --- | --- |
| Joueurs | players, player\_ranks, player\_subscriptions | XUID, UUID Floodgate, grades à vie, Pilier |
| Monnaies personnelles | player\_balances, currency\_ledger | Lingots, Gemmes, jetons, clés, et le journal de chaque mouvement |
| Boutique | shop\_deliveries, shop\_purchases, player\_cosmetics | Gemmes achetées sur le site, achats en jeu, cosmétiques possédés |
| Entreprises | companies, company\_members, company\_invites, archipelago\_slots | Donar de la course, époque, étoiles, associés et leurs parts, case de l'archipel |
| Usines et personnel | company\_factories, factory\_incidents, staff, agency\_candidates | Niveau et remise en état des usines, incidents, personnel nommé, candidats du jour |
| Prestige | prestige\_history | Chaque prestige, sa durée et ses Lingots, pour le tableau de bord d'équilibrage |
| Ville | city\_zones, city\_buildings, company\_vehicles, city\_votes | Zones achetées, bâtiments posés, véhicules, votes des plus belles villes |
| Jeu commun | player\_missions, world\_orders, market\_listings, market\_trades | Missions, commande mondiale du jour, marché taxé à 10 % |
| Saison et social | season\_pass, season\_claims, server\_votes, friends | Pass, votes pour le serveur, amis |
| Technique | game\_events, api\_requests | Événements à afficher en jeu, réponses gardées pour les appels rejoués |

L'API compte 33 opérations, groupées comme le jeu : session (arrivée, départ), entreprise (état, encaisser la production), usines (monter de niveau, remise en état, incidents), agence et personnel, prestige (aperçu puis confirmation par le PDG), associés et parts, missions du joueur, ville (zones, bâtiments), commande mondiale, marché, boutique en jeu, pass de saison, et le relevé des événements. Un refus renvoie toujours un code simple (PAS\_ASSEZ\_DE\_DONAR, PAS\_LE\_PDG…) : c'est l'add-on qui choisit l'icône et la bulle d'Oooscar, pour que tout se comprenne sans lire.

**Choix faits en autonomie, à confirmer**

- Les Lingots sont personnels : chaque associé a son portefeuille, et paie les zones de ville avec les siens.
- Le bonus de +1 % par Lingot se calcule sur la moyenne des Lingots des membres : une équipe de quatre ne produit pas quatre fois plus vite qu'un joueur seul.
- Les salariés ordinaires se déduisent du niveau de l'usine, comme dans le tableur ; seul le personnel nommé (chefs, directeurs, recrues de l'agence) a sa ligne en base.

| Couche | Outil | Rôle |
| --- | --- | --- |
| Serveur | BDS (+ Endstone si besoin) | Parité vanilla complète, plugins en Python ou C++ ([Endstone](https://pypi.org/project/endstone)) |
| Gameplay | Add-on de comportement (TypeScript, Script API) | Ce qui se voit : usines, menus, carte, construction, Oooscar |
| Visuel | Pack de ressources | Modèles Blockbench, animations, sons, interface |
| Données | silooo-core (Kotlin, Ktor) + MariaDB (base silooo) + Redis | Règles du jeu ; Donar, Lingots, Gemmes, îles, usines, ville, achats |
| Boutique | Site et boutique de SileneMC (PayNow) | Paiement, puis crédit des Gemmes dans la base silooo |

**Choix qui protègent les performances**

- Production calculée au temps écoulé à chaque visite d'île, au lieu de faire tourner les usines quand personne n'est là.
- Archipels sur une grille dans un seul monde (prévoir au moins 600 × 600 blocs par joueur pour l'île principale et les îles d'usines ; l'espace vide ne pèse presque rien, seul ce qui est construit occupe du disque), chargées seulement quand un membre ou un visiteur est présent.
- Animations portées par le pack de ressources (côté joueur), pas par des entités qui calculent côté serveur.

**Mises à jour Bedrock** : les consoles se mettent à jour automatiquement, donc le serveur doit suivre en quelques heures. Prévoir un serveur de test, une procédure écrite de mise à jour et une sauvegarde automatique avant chaque mise à jour.

**Plan B** : déjà prévu par le découpage, puisque rien ne dépend d'Endstone. Si la Script API manque d'une fonction, un petit plugin Endstone la fournit ; si c'est Endstone qui pose problème, on s'en passe.

**Viser plus de 200 joueurs simultanés** change l'architecture : un seul serveur Bedrock ne suffira pas. Il faut plusieurs instances (par exemple le hub sur l'une, les archipels répartis sur les autres) reliées par un proxy Bedrock qui fait passer le joueur d'une instance à l'autre. La compatibilité d'un tel proxy avec BDS + Endstone est à valider dès le prototype ; et ce palier dépassera probablement le budget de 100 €/mois, à financer par les revenus une fois l'audience là. Au lancement, une seule instance suffit : l'architecture doit simplement permettre de la découper plus tard.

## Roadmap Bedrock

Pas de date imposée : chaque étape démarre quand la précédente est prête. Les dates ci-dessous sont des cibles indicatives.

&#91;embedded content: roadmap Bedrock · nov. 2026 à avr. 2027\]

**Contenu minimum de chaque étape**

- Prototype : la chaîne du fer avec son personnel, la carte, le coffre de vente, une zone de ville avec la mairie, un prestige, la sauvegarde en base, une mise à jour Bedrock passée sans casse.
- Alpha ouverte à tous : tout le contenu de la version de lancement (ci-dessous), boutique en mode test, promotion via Hubyy.
- Lancement : la version de lancement, boutique réelle, deux modérateurs.
- Ensuite : une mise à jour de contenu par mois environ, décidée d'après les chiffres du mois précédent.

**Version de lancement : assez complète pour accrocher, avec de la marge pour la suite**

| Domaine | Au lancement | Ajouté ensuite |
| --- | --- | --- |
| Usines | Chaîne du fer complète (Carrière, Mine profonde, Fonderie, Atelier d'outils) + Scierie et Ferme en production simple | Chaînes du bois et alimentaire, Élevage, Cristallerie, usines des époques suivantes |
| Personnel | Agence d'emploi, 3 raretés, chefs d'équipe, directeurs | Raretés supplémentaires, salariés légendaires nommés |
| Époques | Artisanale et vapeur (1er prestige) | Moderne, futuriste, puis étoiles sans fin |
| Ville | Zone 1 : mairie, maison du joueur, logements, routes, un commerce ; préfabriqués + décoration libre | Zones suivantes, banque, garages, véhicules, civils |
| Déplacements | TP par la carte, ponts | Avion perso, véhicules |
| Économie | Coffre de vente, commandes PNJ quotidiennes, plafond hors ligne de 8 h | Marché entre joueurs taxé (une fois l'économie observée), prêts |
| Social | Hub minimal, entreprise à 4, visites, classement hebdomadaire | Concours de villes, alliances |
| Événements | Commandes mondiales | Boss du week-end |
| Boutique | Cosmétiques, un grade, boosts collectifs | Pass de saison, autres grades, abonnement |

Le marché entre joueurs arrive après le lancement à dessein : il faut d'abord observer l'économie quelques semaines avant d'ouvrir les échanges.

## Questions ouvertes

- [ ] Marque : Silooo (play.silooo.com), choisie le 4 nov. 2026 à la place d'UrbaFactory ; reste à réserver silooo.com et silooo.fr, et les pseudos TikTok, Instagram et Discord ; monnaies : Donar (jeu), Lingots (stable), Gemmes (boutique, même taux et mêmes packs que les cristaux de SileneMC).
- [ ] Boutique en ligne : on reprend celle de SileneMC (décidé le 4 nov. 2026). Reste à écrire la livraison des Gemmes vers le serveur Endstone (appel sécurisé du site vers le plugin, ou file d'attente en base lue par le plugin).
- [ ] Tout développer, sans add-on existant (décidé le 4 nov. 2026).
- [ ] Public : tout public, avec filtre automatique et staff pour la modération (décidé le 4 nov. 2026).
- [ ] Lien avec SileneMC : aucune promotion croisée pour l'instant ; intégration possible plus tard comme mode de jeu de SileneMC, avec sa propre boutique. Contrainte technique à garder en tête : Silooo tourne sur un serveur Bedrock natif (Endstone et add-on) ; depuis le réseau SileneMC, seuls les joueurs Bedrock pourraient y être envoyés (transfert de serveur), pas les joueurs Java.

* [x] Téléportation par la carte : disponible dès la première usine, sans limite (décidé le 4 nov. 2026).
* [x] Boutique : cosmétiques, confort et boosts collectifs ; pass gratuit + premium ; grades définitifs + abonnement (décidé le 4 nov. 2026).
* [ ] Île principale de 32 × 32 chunks, zones débloquées par la mairie à prix fixe (décidé le 4 nov. 2026).
