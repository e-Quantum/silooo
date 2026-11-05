# Glossaire : les mots du jeu et leur nom dans le code

Les textes des joueurs et la documentation sont en français. Les identifiants du code sont en
anglais, tels qu'ils apparaissent dans [api/silooo-core.openapi.yaml](../api/silooo-core.openapi.yaml)
et dans [V1__init.sql](../core/src/main/resources/db/migration/V1__init.sql). Les noms propres du
jeu (Donar, Lingots, Gemmes, Silo, PDG, mairie) restent en français dans le code : c'est la règle de
l'[ADR 0005](adr/0005-langues-du-code.md). Un mot absent d'ici se cherche dans
[game-design.md](game-design.md), puis s'ajoute à ce tableau.

## Monnaies

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Donar | `companies.donar`, schéma `Donar` (double) | Monnaie de la course : gagnée par les usines, dépensée en usines et salaires, remise à zéro au prestige. Le game design l'appelle aussi « pièces ». |
| Donar gagnés | `companies.donar_earned`, `CompanyState.donarEarned` | Total gagné depuis le début de la course ; sert à la conversion en Lingots. |
| Lingots | `player_balances.lingots`, `Balances.lingots`, `LINGOTS` | Monnaie stable et personnelle : gagnée surtout au prestige, dépensée en ville et au marché, jamais remise à zéro. Chaque Lingot détenu augmente la production de Donar. |
| Gemmes | `player_balances.gemmes`, `Balances.gemmes`, `GEMMES` | Monnaie de boutique, achetée en euros sur le site de SileneMC, dépensée dans la boutique en jeu. |
| Jetons | `player_balances.jetons`, `JETONS` | Monnaie de la zone AFK, échangée contre des clés et des cosmétiques. Jamais contre du Donar. Le game design en donne aussi pour la commande mondiale, que V1 récompense en Lingots : à confirmer par Kr. |
| Clés | `player_balances.cles`, `CLES` | Clés de coffre gagnées en jeu (votes, jetons, événements). Leur usage dépend d'une décision de Kr sur les lootbox, voir [vision.md](vision.md). |
| Journal des mouvements | `currency_ledger` | Une ligne par mouvement de Lingots, Gemmes, jetons ou clés, écrite dans la même transaction que le solde. |
| Notation | `definitions/economie.json`, clé `notation` | Suffixes d'affichage des grands nombres : k, M, Md, puis aa, ab, ac. Voir [economie.md](economie.md). |

## Joueurs et entreprises

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Joueur | `players` | Un compte Bedrock, identifié par son XUID. |
| XUID | `players.xuid`, schéma `Xuid` | Identifiant Xbox du joueur. Entier 64 bits en base, chaîne dans le JSON. |
| Gamertag | `players.gamertag` | Le pseudo Xbox du joueur. |
| UUID Floodgate | `players.floodgate_uuid` | L'UUID que Floodgate déduit du XUID sur SileneMC, gardé pour relier les comptes un jour. |
| Entreprise | `companies`, `CompanyState` | L'unité de jeu : un nom, un archipel, une course de Donar, des usines, une ville. Un joueur n'appartient qu'à une entreprise. |
| PDG | `company_members.role` = `PDG` | Le fondateur de l'entreprise. Seul lui lance le prestige, invite des associés et fixe les parts. |
| Associé | `company_members.role` = `ASSOCIE` | Un membre invité par le PDG. Il gère au quotidien et reçoit sa part des Lingots au prestige. |
| Part | `company_members.share_percent`, `setShares` | Pourcentage des Lingots du prestige attribué à chaque membre par le PDG. |
| Invitation | `company_invites`, `invite`, événement `INVITATION` | Proposition de rejoindre une entreprise, avec une date d'expiration. |
| Case de l'archipel | `archipelago_slots`, `companies.slot_index` | La position de l'archipel d'une entreprise sur la grille du monde unique. |
| Archipel | (pas de table : la case et les usines) | L'île principale et les îles d'usines d'une entreprise. |
| Île principale | `city_zones` | La grande île verrouillée au départ, débloquée zone par zone, qui devient la ville. |
| Île d'usine | `company_factories` | La petite île qui porte une usine. Elle ne se charge que quand quelqu'un s'y trouve. |
| Identité d'entreprise | `companies.slogan`, `logo_id`, `colors` | Nom, logo, couleurs et slogan, visibles sur les enseignes, les véhicules et la fiche publique. |
| Réputation | `companies.reputation` | Monte avec les livraisons à temps et les votes sur la ville ; améliore contrats et candidats. |
| Amis | `friends` | Liste d'amis en jeu ; un ami invité peut emménager dans la ville. |
| Tutoriel | `players.tutorial_step`, `JoinResponse.firstJoin` | La mise en scène d'arrivée et les premières actions guidées par Oooscar. |

## Usines et personnel

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Usine | `company_factories`, `Factory`, `definitions/usines.json` | Une unité de production sur sa propre île. Ses chiffres vivent dans les définitions ; la base ne garde que son identifiant et son niveau. |
| Identifiant d'usine | `factory_id` (`carriere`, `scierie`, `mine-profonde`) | La clé `id` de chaque usine dans `definitions/usines.json`. |
| Produit, entrées | clés `produit`, `entrees` de `usines.json` | Ce qu'une usine fabrique et ce qu'elle consomme d'autres usines. |
| Chaîne de production | (dans les définitions, par les entrées) | Des usines qui s'alimentent entre elles. La chaîne du fer est la première complète. |
| Niveau | `company_factories.level`, `Factory.level` | Regroupe salariés ordinaires et améliorations d'une usine. Chaque niveau coûte plus que le précédent. |
| Niveau d'entreprise | (calculé : somme des niveaux) | Révèle les usines « ????? » de la carte. Clé `decouverte` de `usines.json`. |
| Palier ×2 | `Factory.nextMilestone`, clé `niveaux.paliersX2` | Niveau où la production d'une usine double. Le bouton « palier » monte jusqu'au prochain. |
| Revenu par heure | `Factory.ratePerHour`, `CompanyState.ratePerHour` | Production de Donar par heure, salaires déduits, multiplicateurs appliqués. L'add-on anime le compteur à partir de lui. |
| Multiplicateur | `CompanyState.multiplier` | Regroupe les bonus : Lingots détenus, grade, Pilier, étoiles, bâtiments, boosts. La façon de les combiner est celle du tableur. |
| Encaisser | `collect`, `companies.last_collect_at` | Calcul de la production depuis le dernier passage, hors ligne compris. |
| Hors ligne | `companies.offline_cap_hours`, clé `horsLigne` | Production pendant l'absence, plafonnée en heures et à efficacité réduite. Jamais de grève ni d'incident pendant l'absence. |
| En ruine | `state` = `EN_RUINE` | Usine révélée sur la carte mais pas encore rachetée ni remise en état. |
| Remise en état | `state` = `REMISE_EN_ETAT`, `restore_step`, `restoreStep` | Les quatre étapes après le rachat : dialogue, tâches, livraison, recrutement. |
| Notaire | (PNJ de l'add-on) | Le personnage auprès duquel on rachète une usine. |
| Active | `state` = `ACTIVE` | Usine qui produit. |
| Incident | `factory_incidents`, `resolveIncident`, événement `INCIDENT` | Petit accident (incendie, panne, courroie) qui ralentit une usine jusqu'à un mini-jeu de réparation. Jamais de perte. |
| Électricité | `Factory.stoppedNoPower`, clé `electricite` | Ressource de la Centrale, obligatoire pour les usines des dernières époques. Une usine à l'arrêt faute de courant ne coûte pas de salaire. |
| Salarié | `staff.role` = `SALARIE` | Produit. Les salariés ordinaires se déduisent du niveau ; seuls les salariés nommés ont une ligne. |
| Chef d'équipe | `staff.role` = `CHEF` | Un par usine. Avec lui, l'usine produit hors ligne jusqu'au plafond. |
| Directeur | `staff.role` = `DIRECTEUR` | Un par île d'usine. Bonus de toute l'île et vente automatique ; c'est un chef promu après une mission. |
| Rareté | `staff.rarity` : `COMMUN`, `RARE`, `EPIQUE` | Trois raretés de personnel, avec un bonus croissant. Le game design nomme la troisième avec un mot que la charte bannit ; le nom affiché est à confirmer par Kr. |
| Logé | `staff.housed` | Un salarié logé en ville coûte moins cher en salaire. |
| Agence d'emploi | `agency_candidates`, `getCandidates`, `hire` | Les candidats du jour, par entreprise. Une nouvelle liste s'achète en Donar. |
| Affecter, renvoyer | `assignStaff`, `fireStaff` | Changer un membre du personnel d'usine ; le renvoi est le seul cas où il part. |
| Robots | usine `usine-robots` | Remplacent des salariés : pas de salaire, mais de l'électricité et un entretien. |
| Grève | (règle du service, pas de colonne) | Un salaire impayé en ligne arrête l'usine jusqu'au paiement, sans autre perte. |

## Progression

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Course | `companies.course_number` | La période entre deux prestiges. Le Donar repart de zéro à chaque course. |
| Époque | `companies.era`, `CompanyState.era` | Le style et le catalogue d'usines d'une course : artisanale, vapeur, moderne, futuriste. Le prestige passe à la suivante. |
| Seuil de prestige | `CompanyState.threshold`, clé `prestige.seuils` | Donar à gagner dans la course pour avoir le droit de faire un prestige. |
| Prestige | `prestige`, `prestigePreview`, `prestige_history`, événement `PRESTIGE_ENTREPRISE` | Revente des usines par le PDG : Donar et usines à zéro, époque suivante ou étoile, Lingots partagés. La ville reste. |
| Aperçu du prestige | `PrestigePreview` | Ce qu'un prestige rapporterait maintenant, avec la part de chacun et l'effet du rendement décroissant. |
| Conversion | clé `prestige.conversionLingots` | Formule qui transforme les Donar gagnés d'une course en Lingots. Voir [economie.md](economie.md). |
| Étoile | `companies.stars`, `PrestigePreview.next` = `ETOILE` | Après la dernière époque, chaque prestige donne une étoile et un bonus permanent de production, sans fin. |
| Mission | `player_missions` | Objectif court (quête quotidienne, mission de la mairie, remise en état). Les définitions des missions sont à écrire. |
| Bingo | (à définir) | Carte d'objectifs renouvelée chaque semaine. Pas encore dans les contrats. |
| Carnet de trouvailles | `player_findings`, `getFindings`, `ANNONCE` avec `payload.action = "trouvaille"` | Les ressources du jeu que le joueur a vu sortir d'une de ses usines, une prime en Lingots chacune ; règle dans [economie.md](economie.md), à valider par Kr. |
| Classement hebdomadaire | (calculé depuis `currency_ledger`) | Mesure les Lingots gagnés dans la semaine, pas la richesse totale. |

## Ville

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Ville | `city_zones`, `city_buildings` | L'île principale bâtie : zones, routes, bâtiments, habitants. |
| Mairie | `companies.mairie_level`, bâtiment `mairie` | Cœur de la ville : ses niveaux débloquent les zones et les nouveaux bâtiments. |
| Zone | `city_zones.zone_index`, `buyZone`, clé `ville.zonesPrixLingots` | Une portion de l'île principale, achetée en Lingots à prix fixe par un associé. |
| Silo | `companies.silo_level`, bâtiment `silo` | Bâtiment central où arrivent toutes les marchandises avant la vente. S'améliore par niveaux. |
| Bâtiment | `city_buildings.building_type`, `placeBuilding`, `upgradeBuilding` | Préfabriqué posé avec l'outil de construction, améliorable par niveaux. La décoration libre vit dans le monde, pas en base. |
| Maison du joueur | bâtiment `maison` | Améliorable et décorable, lieu où l'on reçoit ses amis. |
| Logements | bâtiments `maison`, `immeuble` | Logent salariés et civils. |
| Commerce | bâtiment `commerce` | Petit bonus de Donar qui grandit avec la population. |
| Garage | bâtiment `garage` | Achat des véhicules en pièces ; ses niveaux accélèrent les camions. |
| Banque | bâtiment `banque` | Prêts plafonnés selon les revenus, remboursés avec intérêts. Pas dans la version de lancement. |
| Routes | bâtiment `route` | Relient ponts et bâtiments ; les véhicules y roulent plus vite. |
| Véhicule | `company_vehicles.vehicle_type` | Camion, bateau, avion cargo, avion personnel. Achetés en pièces, apparences en boutique. |
| Habitants | (chiffres calculés) | Salariés logés, civils venus seuls, amis invités. Pas de besoins à gérer. |
| Vote des villes | `city_votes` | Visites publiques et notes données aux plus belles villes, par semaine. |

## Jeu commun

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Commande mondiale | `world_orders`, `todayOrder`, événement `COMMANDE_TERMINEE` | Une commande par jour que tout le serveur livre ensemble ; la ressource suit le rang du jour, la prime est en Lingots ([economie.md](economie.md)). |
| Livraison | `world_order_contributions`, `deliverOrder` | La part livrée par un joueur à la commande du jour. |
| Commande client | (à définir) | Contrat de PNJ avec délai et prime, choisi sur un tableau quotidien. Pas encore dans les contrats. |
| Marché | `market_listings`, `market_trades` | Échange de ressources entre joueurs, en Lingots, taxé. Sa forme reste à trancher par Kr, voir [vision.md](vision.md). |
| Annonce | `Listing`, `createListing`, `buyListing`, événement `VENTE_MARCHE` | Une offre de vente sur le marché. |
| Taxe | `market_trades.tax` | Part de chaque vente détruite : une sortie d'argent. |
| Vote serveur | `server_votes` | Un vote sur un site de vote ou Hubyy, récompensé une fois. |
| Vote party | (jauge commune, pas de table) | Quand le serveur atteint un nombre de votes, tout le monde connecté reçoit la même récompense. |
| Boost collectif | événement `BOOST_COLLECTIF` | Production augmentée pour tout le serveur pendant un temps, acheté en Gemmes ou gagné par la Vote party. |
| Boss du week-end | (après le lancement) | Catastrophe industrielle affrontée par tout le serveur ; récompense cosmétique ou bonus temporaire. |
| Zone AFK | (dans le hub) | Rapporte des jetons, jamais de Donar. |
| Annonce serveur | événement `ANNONCE` | Message pour tout le serveur, poussé par le service. |

## Boutique et grades

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Boutique | `catalog`, `buyItem`, `shop_purchases` | Ce qui s'achète en Gemmes en jeu : grades, Pilier, cosmétiques, boosts, pass. |
| Pack de Gemmes | `definitions/boutique.json`, clé `packsGemmes` | Les tailles de packs vendus en euros sur le site. |
| Livraison de Gemmes | `shop_deliveries`, événement `GEMMES_RECUES` | Une commande du site, créditée une seule fois par silooo-core. |
| Cosmétique | `player_cosmetics` | Apparence possédée : skin d'usine, de véhicule, pet, meuble, effet de prestige. Aucun effet sur la production. |
| Skin | `skin_id` sur usines, personnel, véhicules | L'apparence choisie pour un objet du jeu. |
| Grade | `player_ranks.rank_id`, clé `grades` de `boutique.json` | Grade à vie, à prendre dans l'ordre : MVIP, VIP, VIP+, Héros, Légende, Ultime. Préfixe, confort et bonus de Donar. |
| Pilier | `player_subscriptions.subscription_id` = `pilier`, `JoinResponse.pilierUntil` | Abonnement mensuel : badge qui évolue, bonus de Donar, Gemmes rendues chaque mois. |
| Préfixe perso | `subscription_id` = `perso` | Option réservée au grade Ultime. |
| Pass de saison | `season_pass`, `getPass`, `claimPass` | Paliers de récompenses, piste `GRATUIT` et piste `PREMIUM`. |
| Palier du pass | `season_claims.tier`, `track` | Un niveau du pass réclamé par le joueur. |
| Saison | `season_pass.season_id` | Période qui remet à zéro le pass et les classements, jamais les îles ni le Donar. |
| Cadeau | `shop_purchases.for_xuid`, `buyItem.forXuid` | Un article acheté pour un autre joueur. |

## Interface et mascotte

| Mot du jeu | Dans le code | Définition |
| --- | --- | --- |
| Oooscar | entité et textures du pack | La mascotte : un petit silo maladroit, cinq expressions. Voir [design.md](design.md). |
| Bulle | composant `sa-bulle` | Ce que dit Oooscar : six mots au plus. |
| Carte | écran de l'add-on | Montre l'archipel, les usines possédées et les « ????? », avec téléportation. |
| Compteur | composant `sa-compteur` | Le Donar affiché en cylindres mécaniques, animé à partir du revenu par heure. |
| HUD | `hud_screen.json` | Les trois étages d'information autour de la barre d'inventaire. |
| Fiche | composant `sa-fiche` | Le détail d'une usine, d'un bâtiment ou d'un article, toujours dans le même ordre. |
| Barre d'actions | composant `sa-barre` | Les cinq grandes fenêtres : Carte, Usines, Ville, Marché, Boutique. |
| Code de refus | réponse `Refused.reason` | Code stable renvoyé par le service (`PAS_ASSEZ_DE_DONAR`, `PAS_LE_PDG`). L'add-on choisit l'icône et la bulle. |

## Technique

| Mot | Dans le code | Définition |
| --- | --- | --- |
| silooo-core | `core/` | Le service Kotlin qui décide de tout. |
| Add-on | `addon/` | Le pack de comportement TypeScript qui affiche et envoie des intentions. |
| Pack de ressources | `resource-pack/` | Textures, interface JSON UI, police, modèle d'Oooscar. |
| BDS | conteneur `silooo-bds` | Le serveur Bedrock officiel, version épinglée. |
| Clé serveur | en-tête `X-Silooo-Key` | Le secret partagé entre l'add-on et silooo-core. Jamais dans le code. |
| Battement | `heartbeat` | Le serveur Bedrock dit qu'il tourne et qui est en ligne. |
| Événement de jeu | `game_events`, `GameEvent`, `pollEvents` | Ce que le service a à dire au jeu ; l'add-on vient le chercher avec un curseur. |
| Curseur | `pollEvents.after`, réponse `cursor` | Le dernier identifiant d'événement reçu. |
| Idempotence | `api_requests`, `ActionBase.requestId` | Un appel rejoué renvoie la réponse gardée sans refaire l'action. |
| Verrou optimiste | `companies.version`, `player_balances.version` | Numéro qui change à chaque écriture ; un conflit renvoie `CONFLIT_REESSAYER`. |
| Définitions | `definitions/` | Les chiffres du jeu, vérifiés par schéma, chargés par le service et traduits en types pour l'add-on. |
| Migration | `core/src/main/resources/db/migration/` | Un changement de schéma, numéroté, jamais modifié après coup. |
| Hubyy | (hors dépôt) | L'annuaire de serveurs par lequel arrivent les joueurs. |
| SileneMC | (hors dépôt) | Le serveur voisin sur le même VPS, dont Silooo réutilise MariaDB, Redis, la boutique et les grades. |
