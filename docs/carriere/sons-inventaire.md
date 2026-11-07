# Inventaire des sons de Silooo

Relevé fait le 7 novembre 2026 sur `depot/` (copie de `main`). Les fichiers et les réglages livrés sont dans
`resource-pack/sounds/`, et le détail mesuré de chaque son (durée, poids, crête, niveau moyen) est dans `catalogue.json`.

## Ce que le serveur joue aujourd'hui

Rien. Le relevé :

- `addon/src/` et `addon/pack/` n'appellent jamais `playSound`, `playMusic`, `queueMusic` ni la commande `/playsound`.
  La seule commande lancée par l'add-on est `title @s clear` (`monde/bedrock.ts:243`).
- `resource-pack/` n'a ni dossier `sounds/`, ni `sound_definitions.json`, ni `sounds.json`, ni `music_definitions.json`.
- `docs/design.md` (« Oooscar côté client ») : l'entité Oooscar n'a pas de son, et son fichier client n'a pas de clé
  `sound_effects`.
- `deploy/bds/start.sh` copie `resource-pack/` tel quel dans `resource_packs/silooo` et `server.properties` impose le
  pack (`texturepack-required=true`) : un dossier `sounds/` ajouté au pack arrive donc chez chaque joueur sans autre
  réglage.

Le joueur entend donc seulement ce que le moteur de Bedrock joue de lui-même :

| Son vanilla | Quand |
| --- | --- |
| Pas, sauts, chutes (son du bloc sous les pieds) | Joueurs et salariés qui marchent |
| Bloc posé, cassé, frappé (son du matériau) | Construction, décoration libre |
| `random.click` joué par l'interface du client | Bouton touché dans un formulaire serveur (ActionFormData, ModalFormData) |
| Musique de fond de Bedrock (réglage « Musique » du joueur) | Partout, au hasard |
| Rien | Vente, embauche, amélioration, palier, prestige, incident, vote, cinématique, fondu, voyage, machines |

## Les moments du jeu et leur son

Règle suivie : un son Silooo pour chaque retour d'action et chaque machine, parce que `docs/game-design.md`
(« Comprendre sans lire ») veut un son à chaque vente, embauche et amélioration, et qu'un son vanilla (orbe
d'expérience, enclume, `random.levelup`) fait tout de suite « serveur Minecraft de plus ». Le vanilla reste pour ce qui
est physique et que le moteur joue déjà sans latence : pas, blocs, clic des boutons de formulaire.

« Joueur » veut dire `player.playSound(id)` : le son n'est joué qu'à ce joueur, sur lui, sans portée. « Monde » veut dire
`dimension.playSound(id, position)` : tout joueur à portée l'entend, avec `min_distance` et `max_distance` de
`sound_definitions.json`. Les positions viennent de `carriere/carriere.json`, clé `emetteurs.marche`.

### Interface

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Ouverture d'un écran du menu | `silooo.ui.ouvrir` | Silooo | joueur | `ui/formulaires.ts:65` et `:74`, au `show` | Deux tocs de bois qui montent : un tiroir qui s'ouvre |
| Fermeture, retour | `silooo.ui.fermer` | Silooo | joueur | `ui/formulaires.ts`, réponse annulée (`FormCancelationReason`) | Les mêmes tocs, qui descendent |
| Bouton touché | `random.click` | vanilla | client | rien à brancher | Le client le joue déjà, sans attendre le serveur ; un clic joué par le script arriverait en retard et doublerait. `silooo.ui.clic` est prêt pour le jour où `ui/server_form.json` (à faire) remplace le clic des boutons (`sound_name`) |
| Boussole utilisée | `silooo.ui.ouvrir` | Silooo | joueur | `ui/boussole.ts:130` | Le menu s'ouvre : même son que l'ouverture, une seule fois |
| Refus du service (409) | `silooo.ui.refus` | Silooo | joueur | `ui/menu.ts:507` (`refused`), `:509` (`failed`) | Deux tocs graves qui descendent : un « non » doux, pas de buzzer. L'enfant comprend au son et à la bulle d'Oooscar |

### Vente et monnaie

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Le coffre de vente avale les marchandises | `silooo.vente.coffre` | Silooo | monde, `emetteurs.marche.vente_coffre` | à écrire (le coffre n'a pas encore de code dans l'add-on) | Couvercle de bois et pierres qui glissent : on entend ce qui se vend |
| Le jeton de Donar sort | `silooo.vente.jeton` | Silooo | monde, même point | avec la particule `silooo:jeton_donar` | Pièce de laiton : le Donar est le jeton en laiton de la charte |
| Encaisser (collect), gains hors ligne | `silooo.vente.encaisse` | Silooo | joueur | `ui/menu.ts:472` (`collected`) | Une pluie de pièces : plus long que le jeton, parce que c'est une somme |
| Achat en boutique | `silooo.vente.jeton` | Silooo | joueur | `ui/menu.ts:503` (`itemBought`) | Une pièce qui part : pas besoin d'un son de plus |
| Palier du pass réclamé | `silooo.vente.encaisse` | Silooo | joueur | `ui/menu.ts:505` (`passClaimed`) | Une récompense versée, comme un encaissement |

### Progression

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Embauche | `silooo.progression.embauche` | Silooo | joueur | `ui/menu.ts:485` (`hired`) | Deux notes de marimba qui montent : quelqu'un arrive |
| Amélioration (niveaux achetés) | `silooo.progression.amelioration` | Silooo | joueur | `ui/menu.ts:477` (`levelsBought`) | Trois crans d'engrenage en bois, trois notes qui montent |
| Bâtiment de la ville amélioré | `silooo.progression.amelioration` | Silooo | joueur | `ui/menu.ts:501` (`buildingUpgraded`) | Même geste, même son |
| Palier ×2 franchi | `silooo.progression.palier` | Silooo | joueur | `ui/menu.ts:477`, quand `nextMilestone` de l'usine change entre l'état d'avant et celui que rend le service | L'arpège complet et une clochette de laiton : plus fort que l'amélioration, moins que le prestige. Joué à la place de `amelioration`, pas en plus |
| Prestige | `silooo.progression.prestige` | Silooo | joueur | `ui/menu.ts:483` (`prestiged`) ; pour les associés, l'événement `PRESTIGE_ENTREPRISE` | 2,9 s : souffle, deux octaves d'arpège, deux cloches, des pièces. Le son le plus long du jeu |
| Étape de remise en état, mission | `silooo.progression.etape` | Silooo | joueur | `ui/menu.ts:479` (`stepDone`) | Toc toc au maillet, puis une note |
| Zone achetée, bâtiment posé | `silooo.ville.pose` | Silooo | joueur | `ui/menu.ts:497` (`zoneBought`), `:499` (`buildingPlaced`) | Trois coups de maillet sur une poutre |
| Affectation, renvoi, invitation, parts | aucun | | | `ui/menu.ts:487` à `:495` | Gestion courante : la bulle suffit, un son partout finirait par lasser |

### Incidents

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Un incident s'ouvre | `silooo.incident.alerte` | Silooo | monde, à la machine, portée 20 | `ui/incidents.ts`, action `opened` | Crécelle de bois de l'atelier : elle appelle sur toute la petite île sans faire sirène |
| L'engrenage grippé, pendant l'incident | `silooo.carriere.grince` | Silooo | monde, à l'engrenage, portée 8 | timer de l'incident, toutes les 3 à 5 s, tant que le bloc est `[grippe]` | On trouve la panne à l'oreille |
| Incident réparé | `silooo.incident.repare` | Silooo | joueur | `ui/menu.ts:481` (`incidentResolved`) | Deux coups de maillet et deux notes qui montent : ça repart |

### Annonces et votes

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Vote reçu | `silooo.vote.recu` | Silooo | joueur | `ui/votes.ts`, `ANNONCE` `vote` | Une cloche et une pièce |
| Vote party | `silooo.vote.party` | Silooo | chaque joueur présent | `ui/votes.ts`, `ANNONCE` `voteParty` | Quatre cloches, un petit air, des pièces : une fête de tout le serveur |
| Gemmes reçues, commande terminée, invitation, boost collectif | `silooo.annonce.cloche` | Silooo | joueur concerné | `ui/annonces.ts` (dont `:135` pour `BOOST_COLLECTIF`), `ui/invitations.ts` | Une seule clochette pour « regarde le tchat » |

### Cinématique, voyage

| Moment | Événement | Origine | Joué à | Où le brancher | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| Fondu au noir | `silooo.cinematique.fondu` | Silooo | joueur | pas `fade` de `monde/cinematique.ts:68` et `:85` | Un souffle d'air doux qui accompagne le noir |
| Voyage d'une île à l'autre | `silooo.cinematique.fondu` | Silooo | joueur | `monde/voyage.ts`, à la téléportation | Le même passage d'air |
| Chute dans le vide, retour au point sûr | `silooo.cinematique.fondu` | Silooo | joueur | à écrire | Pas de son de chute : le jeu dit « sans perte », le son reste neutre |
| Fin de la cinématique | `silooo.cinematique.fin` | Silooo | joueur | `monde/cinematique.ts`, fin du dernier plan | Deux cloches : à toi de jouer. La boussole arrive avec |
| Sous-titres de la cinématique | aucun | | | | Le fondu marque déjà chaque plan |

### Ambiance des îles

| Île | Événement | Origine | Comment | Pourquoi |
| --- | --- | --- | --- | --- |
| Carrière (en ruine et en marche) | `silooo.ambiance.vent` | Silooo | boucle de 8 s jouée au joueur sur l'île, rejouée tous les 160 ticks, volume 0,25 | Le vent du ciel, grave et discret : le fond de toutes les îles |
| Scierie, Ferme, Mine profonde (époque 1, pas encore construites) | `silooo.ambiance.vent` d'abord | Silooo | même boucle | Un son propre par île quand l'île existe (scie, poules, galerie), à fabriquer avec le même script |
| Île principale, ville | aucun pour l'instant | | | La ville n'est pas construite |
| Musique de fond | vanilla | client | réglage du joueur | Silooo n'a pas de musique ; couper la musique de Bedrock sur les îles demande `music_definitions.json`, point ouvert |

### Machines de la Carrière (état `marche`)

| Machine | Événement | Origine | Point (`emetteurs.marche`) | Portée | Comment |
| --- | --- | --- | --- | --- | --- |
| Moulin à vent | `silooo.carriere.moulin` | Silooo | `moulin_moyeu` | 2 à 10 blocs | boucle de 4 s (un tour d'ailes : quatre passages d'aile, deux grincements d'arbre, 24 dents d'engrenage) |
| Bocard, trois pilons | `silooo.carriere.pilon` | Silooo | `poussiere_bocard`, un point par pilon | 2 à 10 blocs | un son par coup, en même temps que la particule `silooo:poussiere_bocard` ; trois hauteurs tirées au hasard |
| Tapis en toile | `silooo.carriere.tapis` | Silooo | `tapis`, le point le plus proche du joueur | 1 à 6 blocs | boucle de 2 s, quatre tocs de rouleau |
| Salarié qui pioche | `silooo.carriere.pioche` | Silooo | `eclats_pioche` ou le salarié | 1 à 8 blocs | à l'image du coup, avec `silooo:eclats_pierre` |
| Salarié qui pellette | `silooo.carriere.pelle` | Silooo | le salarié | 1 à 6 blocs | à l'image où la pelle entre dans le tas |
| Poêle du bureau | aucun | | `fumee_poele` | | La fumée se voit ; un feu qui crépite partout fatigue |
| Moulin cassé, pilons tombés (état `ruine`) | aucun | | | | L'île en ruine n'a que le vent : le silence dit qu'elle est à l'arrêt |

Une boucle qui s'entend sur toute l'île finit par couvrir tout le reste : chaque machine s'éteint au-delà de 6 à 10 blocs,
et le moulin et le bocard, à 6 blocs l'un de l'autre, ne se mêlent qu'entre les deux. Le coffre de vente, au centre
(`vente_coffre`), est à 11 blocs du moulin : on n'y entend plus les machines, seulement la vente et le vent.

### Blocs et entités Silooo

| Objet | Son | Origine | Où | Pourquoi |
| --- | --- | --- | --- | --- |
| `silooo:tremie_bois`, `silooo:engrenage_bois`, `silooo:coffre_vente` | `wood` | vanilla | clé `sound` de `blocks.json` | Des blocs de bois sonnent comme du bois, sans rien fabriquer |
| `silooo:tapis_toile` | `cloth` | vanilla | `blocks.json` | Toile |
| `silooo:plaque_emaillee` | `stone` | vanilla | `blocks.json` | Plaque lourde, pas de métal brillant à l'époque 1 |
| `silooo:salarie` | pas vanilla, `pioche` et `pelle` par l'animation | vanilla, Silooo | clé `sound_effects` de l'entité cliente, déclenchée dans les animations `piocher` et `pelleter` | Joué côté client à l'image exacte, sans charge pour le serveur |
| `silooo:oooscar` | aucun | | | `docs/design.md` : Oooscar n'a pas de son. Ses réussites sonnent par le son de l'action (embauche, palier), pas par lui |

`blocks.json` et l'entité cliente des salariés ne sont pas dans le dossier `sons/` : la clé `sound` revient au chef ou à
la session textures, la clé `sound_effects` à la session animations. Aucun `sounds.json` n'est livré : les blocs se
servent des jeux vanilla ci-dessus, et les sons d'entités passent par les animations.
