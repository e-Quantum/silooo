# La Carrière, époque 1 : l'île de départ

La Carrière est l'île où le joueur arrive. Ce fichier dit ce qu'elle contient, où sont ses fichiers, comment les refaire
et les contrôler, et ce qu'il reste à brancher dans l'add-on. Tout ce qui est décrit ici est **à valider par Kr** :
textures, skins, animations, sons, noms affichés, valeurs proposées pour `definitions/iles.json`.

L'aperçu 3D (ruine, marche, cinématique d'arrivée, remise en route, sons) :
<https://claude.ai/artifact/Ux4C1JkftMjikbmej3NLr8>. Il est privé, à partager depuis son menu.

## Ce qu'est l'île

Époque 1, artisanale ([game-design.md](game-design.md), « Époques ») : bois, pierre, moulin. Une île flottante de 31 × 31
blocs, 27 de haut, dessous en pointe. Du nord au sud :

- le front de taille en trois gradins, avec deux veines de charbon ;
- les rails sur un chevalement en bois, jusqu'à la trémie du bocard ;
- le bocard à trois pilons, entraîné par un moulin à vent dont les ailes regardent le sud ;
- le tapis en toile, surélevé de 3 blocs, qui porte les pierres jusqu'au coffre de vente ;
- au centre, l'étal de vente à rayures rouges et crème, la plaque CARRIÈRE au-dessus : c'est ce que le joueur voit en
  premier en arrivant ;
- à l'ouest, le bureau du contremaître, avec son poêle et sa cheminée ;
- au sud, l'entrée où arrivera le pont.

Deux états : **ruine** (la cinématique et le prestige : toits effondrés, une aile au sol, fenêtres condamnées, éboulis)
et **marche**. Ils ont la même emprise : charger « marche » par-dessus « ruine » remet l'île en état, gravats compris,
parce que les cases vides de la structure sont de l'air.

## Où sont les fichiers

| Quoi | Où | Refait par |
| --- | --- | --- |
| Structures | `addon/pack/structures/silooo/carriere_ruine.mcstructure`, `carriere_marche.mcstructure` | `tools/carriere/structure.py` |
| La structure, bloc par bloc | `tools/carriere/generer.py` | source unique |
| Identifiants et états de Bedrock 1.26.60 | `tools/carriere/blocs-bedrock-1.26.60.json` | réduit de bedrock-samples v1.26.60.5 |
| Blocs Silooo (pack de comportement) | `addon/pack/blocks/*.json` | `tools/carriere/atelier/blocs_pack.py` |
| Textures des blocs | `design/blocs/*.png` (sources), `resource-pack/textures/blocks/silooo/` (copies) | `tools/carriere/atelier/textures.py` |
| Atlas, textures animées, sons des blocs | `resource-pack/textures/terrain_texture.json`, `flipbook_textures.json`, `resource-pack/blocks.json`, `resource-pack/models/blocks/` | `blocs_pack.py`, `textures.py` |
| Salariés | `resource-pack/models/entity/salarie.geo.json`, `entity/salarie.entity.json`, `render_controllers/salarie.render_controllers.json`, `design/skins/` et `textures/entity/salarie_*.png` | `tools/carriere/atelier/salaries.py` |
| Gestes, réactions d'Oooscar, machines, particules | `resource-pack/animations/`, `animation_controllers/`, `particles/`, `models/entity/{pilons,ailes_moulin}.geo.json`, `entity/{pilons,ailes_moulin}.entity.json`, `render_controllers/machines.render_controllers.json`, `textures/entity/machines_carriere.png`, `textures/particle/silooo_particules.png` | `tools/carriere/atelier/animations.py` |
| Oooscar | `resource-pack/entity/oooscar.entity.json` gagne `joie`, `oups`, `panique`, `fier`, `dodo` | |
| Sons | `resource-pack/sounds/sound_definitions.json`, `resource-pack/sounds/silooo/**/*.ogg` (27 sons, 276 Ko) | `tools/generer_sons.py` |
| Où brancher chaque son | [carriere/sons-inventaire.md](carriere/sons-inventaire.md) | |
| Extraits proposés pour les entités de comportement | `docs/carriere/propositions/` | |
| Noms affichés | `resource-pack/texts/fr_FR.lang`, section « Carrière » | |

Les scripts de `tools/carriere/atelier/` sont ceux qui ont produit les fichiers, tels qu'ils ont tourné dans l'atelier
(`/var/www/silooo/atelier-carriere/` sur le VPS) : leurs chemins par défaut visent l'atelier, pas le dépôt. Les fichiers
du pack sont la référence ; pour les refaire dans le dépôt, il faut d'abord adapter ces chemins.

## Refaire et contrôler

```bash
python3 -I tools/carriere/structure.py addon/pack/structures/silooo
```

```bash
python3 -I tools/generer_sons.py --catalogue tools/generer_sons.catalogue.json
```

`tools/verifier.sh` passe trois contrôles :

- `node tools/verifier_pack.mjs` : JSON, entités reliées (contrôleurs d'animation compris), et désormais copies
  identiques de `design/blocs/` dans `resource-pack/textures/blocks/silooo/`. Deux textures vanilla sont citées exprès
  (`planks_spruce`, `log_big_oak_top`, vues dans bedrock-samples v1.26.60.5) ; toute autre doit être dans le pack.
- `tools/generer_sons.py --verifier` : refait les 27 signaux et compare l'empreinte des échantillons avant encodage. Les
  octets Ogg changent à chaque encodage, on ne les compare pas. Essayé avec numpy 2.3.5 et scipy 1.16.3 : à épingler
  en CI.
- `tools/carriere/structure.py --verifier` : relit chaque `.mcstructure`, vérifie chaque bloc et chacun de ses états
  contre Bedrock 1.26.60 et les blocs Silooo, et refuse une structure qui diffère de ce qu'écrit `generer.py`.

### Essai sur un vrai serveur

Le 7 novembre 2026, sur une copie séparée de BDS 1.26.62.1 (monde neuf, port 19600, pack d'essai avec les structures et
les 5 blocs Silooo) : `structure load silooo:carriere_marche 0 100 0` puis `carriere_ruine` par-dessus. Huit
`testforblock` ont trouvé le bloc attendu (coffre de vente, plaque, escalier du toit, tapis, barrière de l'étal, chemin ;
puis, après la ruine, de l'air à la place du coffre et le rouet grippé). Le seul défaut relevé, des matériaux opaques
et transparents mêlés dans le tapis déchiré, est corrigé. Aucun client n'a vu l'île : couleurs, sens des rotations
et faces orientées restent à regarder en jeu.

## Branchée dans l'add-on (0.1.5)

La Carrière de l'époque 1 remplace celle de la 0.1.4 (`carriere_depart`) dans le modèle de Codex : la section
`atelier` de `definitions/iles.json` dit tout, l'add-on ne garde aucun chiffre.

- **Pose.** `planIsland` pose `atelier.structure` (`carriere_marche`) quand la Carrière est active, sinon
  `atelier.structureRuine` (`carriere_ruine`), à `hauteur - atelier.solStructure` (16) pour que la face du dessus tombe
  à la hauteur des îles. `tools/carriere/structure.py` écrit les structures déjà tournées dans le repère fixe de la
  Carrière (angle 0) : leur sud, l'entrée et l'étal, regarde l'île principale, à l'ouest du monde. `atelier.revision`
  passe à 3 : les Carrières déjà posées sont refaites une fois.
- **Machines** (`atelier.machines`, contrôlées par `validateWorldAssets` au démarrage) : deux rouets (rôle broyeur),
  les cinq tapis du tronçon nord-sud (convoyeur, où avancent les trois pierres de `atelier.transport`), le coffre de
  vente (stock) et le bureau d'administration de la 0.1.4, posé sur le bureau du contremaître. Ces blocs portent le
  composant `silooo:atelier` : un clic ouvre le bureau ou la production.
- **Salarié et enseigne** : le salarié de la 0.1.4 (`silooo:ouvrier`) au pied du front de taille ; l'add-on écrit le nom
  de la Carrière sur l'enseigne du bureau, présente dans les deux états.
- **Machines animées** (`atelier.machinerie`) : `silooo:ailes_moulin` et `silooo:pilons` apparaissent avec le salarié,
  tournés de 90° (vers l'ouest), et sont retirés avec lui. En ruine, ils ne sont pas posés : la tour du moulin reste
  sans ailes, au lieu des ailes cassées de la maquette.
- **Arrivée et cinématique** : `usines[carriere].rayon` 15, `apparition` 9, Oooscar à 7 en avant et 2 à droite ; les
  quatre plans ci-dessous.
- **Mode de secours** (monde sans structures, tests) : `atelier.volumes` décrit la Carrière en grandes masses.
- **Pas encore fait** : les salariés `silooo:salarie` (modèle et skins de l'atelier) ne remplacent pas encore
  l'ouvrier de la 0.1.4 ; aucun son n'est joué par l'add-on ([carriere/sons-inventaire.md](carriere/sons-inventaire.md)) ;
  la vente par particules (`silooo:miettes_coffre`, `silooo:jeton_donar`) et la fumée du poêle ne sont pas branchées.

## Audit après la première visite en jeu (0.1.6)

Corrigé :

- **Entités tuables.** En `format_version` 1.21.0, `deals_damage` doit être le booléen `false` : la chaîne `"no"`
  est ignorée et un coup tuait Oooscar, l'ouvrier, les pierres, les ailes et les pilons. Vu sur BDS 1.26.62.1 (la
  chaîne `"no"` ne vaut qu'à partir de 1.21.50). Toutes les entités du pack sont passées à `false` ; un test de
  `addon/test/pack.test.ts` le vérifie pour chacune.
- **Sous-titres en double.** Le sous-titre natif n'était pas caché pendant la cinématique : le test « commence par
  silooo:parole| » (concaténation et soustraction de chaînes) échoue sur un vrai client. Il est caché par l'égalité
  du titre (`silooo:cinema|`), celle qui fait déjà apparaître le récit d'Oooscar.
- **L'ouvrier de la 0.1.4** porte le modèle et le skin commun des salariés de l'atelier : il pioche quand l'usine
  tourne (son et éclats à l'impact), il se repose quand elle est arrêtée.
- **Pierres transportées** : un caillou irrégulier d'un quart de bloc dans les tons de la tuile de pierre, posé sur
  la toile (`atelier.transport.hauteur`), qui sautille d'un pixel au lieu de tourner sur lui-même.
- **Bureau d'administration** : redessiné en bureau de bois de l'époque 1 (registre, encrier).
- **Ambiance** (`atelier.ambiance`) : le moulin et le tapis s'entendent, le poêle fume, seulement quand la Carrière
  tourne.

Ouvert, à trancher ou à voir en jeu :

- **Blocs cassables.** Aucune protection : un joueur peut casser toute la Carrière. Le tutoriel veut qu'il casse des
  pierres du front de taille ; reste à décider ce qui est protégé.
- **Orientation des ailes et des pilons** : lacet 90 supposé d'après le modèle ; à confirmer en jeu.
- **Un seul salarié** au lieu des deux de la maquette ; la vente par particules et les sons de vente ne sont pas
  branchés ; en ruine, le moulin n'a pas d'ailes.
- **Pose** : `StructureManager.place` fige le script 349 ms à la première pose (avertissement Watchdog).

## Les valeurs de `definitions/iles.json`

Repère de la cinématique : `avant`, `droite`, `haut` depuis le centre de l'île ([cinematique.ts](../addon/src/monde/cinematique.ts)).

| Plan | Cadre | Départ | Arrivée | Regard |
| --- | --- | --- | --- | --- |
| 1 | L'île entière, vue du pont | 30, -22, 22 | 19, -14, 13 | -2, 0, 3 |
| 2 | Le front de taille | 4, 2, 10 | 1, 1, 8 | -10, 1, 3 |
| 3 | Le bocard et le moulin | 10, -9, 5 | 6, -6, 5 | -4, -7, 4 |
| 4 | L'étal de vente et la plaque | 10, -3, 3 | 6.5, -1, 3 | 2.5, 0, 4.5 |

- Arrivée : `usines[carriere].apparition` 9 (2 blocs à l'intérieur de l'entrée, les piliers restent derrière le joueur).
- `apparition.oooscar` : `avant` 7, `droite` 2.
- L'anneau : l'île s'étend sur environ 14 blocs du centre vers le pont, contre 7 aujourd'hui. À 280 blocs du centre
  de l'archipel, il reste environ 10 blocs avant l'île principale.

Les textes de la cinématique et le récit au-dessus de l'inventaire sont ceux de la 0.1.4.

## À valider par Kr

- Les 5 blocs Silooo, leurs textures et leurs noms affichés (`fr_FR.lang`).
- Les trois skins de salarié et le nom « Salarié ».
- Les gestes, les cinq réactions d'Oooscar (`dodo` en plus des quatre demandées), les machines, les particules.
- Les 27 sons, et la musique vanilla sur les îles (non traitée).
- Rails et wagonnets, choisis au cadrage, alors que l'époque 1 prévoit des charrettes : les deux sont là.
- Les valeurs de `iles.json` ci-dessus.
