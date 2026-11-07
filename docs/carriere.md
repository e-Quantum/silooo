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

## Brancher l'île dans l'add-on

Rien n'est branché dans `addon/src/` : la pose actuelle (`addon/src/monde/plan.ts`) dessine encore l'ancienne île de
rayon 7. La version 0.1.4 de Codex, qui pose une Carrière par structure, n'est pas sur `main` ; ce qui suit est à
fusionner avec elle.

- **Pose.** `structure load silooo:carriere_<etat> <origine>` où l'origine est le centre de l'île moins (15, 16, 15) :
  l'herbe est à y = 16 dans la structure, à `iles.json` `hauteur` dans le monde. Le sud de la structure est le côté
  « avant » (vers l'île principale) : la rotation est à choisir pour que ce sud regarde l'île principale, et à
  confirmer en jeu.
- **Positions.** Une position `(x, y, z)` de `generer.py` devient, dans la structure, `(x, y + 2, z)`.
- **Entités**, posées par l'add-on en marche seulement, animées par le pack :
  `silooo:ailes_moulin` au moyeu (26.5, 21.5, 14.05), `silooo:pilons` à (21.5, 16, 10.5), propriété `silooo:ruine`
  à vrai en ruine ; deux `silooo:salarie` à (10.5, 15, 7.5) en pioche et (18.5, 15, 10.5) en pelle, propriété
  `silooo:geste` et `minecraft:variant` (0 commun, 1 rare, 2 légendaire). Les extraits sont dans
  `docs/carriere/propositions/`. Les machines regardent le sud (yaw 0) ; si elles sont à l'envers en jeu, tourner de
  180° et changer `SENS` dans `animations.py`. En jeu, les ailes ne repartent pas encore par paliers à la remise en
  route : l'aperçu le montre, l'add-on devra piloter leur vitesse.
- **Vente.** Particules seules, aucune entité : `silooo:miettes_coffre` quand l'objet tombe, `silooo:jeton_donar`
  quand l'API confirme, `joie` sur Oooscar ; un jeton par vente, au plus un toutes les 0,5 s par coffre.
- **Fumée.** `silooo:fumee_poele` relancé toutes les 8 s à (4.5, 23, 14.5), en marche seulement.
- **Sons.** Voir [carriere/sons-inventaire.md](carriere/sons-inventaire.md) : aujourd'hui l'add-on n'en joue aucun.
- **Budget.** 7 entités en marche (ailes, pilons, deux salariés, Oooscar, pierres du tapis, charrette) : la limite fixée
  pour Switch et vieux mobiles ([game-design.md](game-design.md)).

## Valeurs proposées pour `definitions/iles.json`

Repère de la cinématique : `avant`, `droite`, `haut` depuis le centre de l'île ([cinematique.ts](../addon/src/monde/cinematique.ts)).

| Plan | Cadre | Départ | Arrivée | Regard |
| --- | --- | --- | --- | --- |
| 1 | L'île entière, vue du pont | 30, -22, 22 | 19, -14, 13 | -2, 0, 3 |
| 2 | Le front de taille et l'éboulis | 4, 2, 10 | 1, 1, 8 | -10, 1, 3 |
| 3 | Le bocard et le moulin | 10, -9, 5 | 6, -6, 5 | -4, -7, 4 |
| 4 | L'étal de vente et la plaque | 10, -3, 3 | 6.5, -1, 3 | 2.5, 0, 4.5 |

- `apparition.distance` : 9 (2 blocs à l'intérieur de l'entrée, les piliers restent derrière le joueur).
- `apparition.oooscar` : `avant` 7, `droite` 2.5.
- L'anneau : l'île s'étend sur environ 14 blocs du centre vers le pont, contre 7 aujourd'hui. À 280 blocs du centre
  de l'archipel, il reste environ 10 blocs avant l'île principale.

Les textes de la cinématique sont ceux de `main` (`CINEMATIQUE_TEXTES`) ; ceux de la 0.1.4 sont restés sur le PC de Kr.

## À valider par Kr

- Les 5 blocs Silooo, leurs textures et leurs noms affichés (`fr_FR.lang`).
- Les trois skins de salarié et le nom « Salarié ».
- Les gestes, les cinq réactions d'Oooscar (`dodo` en plus des quatre demandées), les machines, les particules.
- Les 27 sons, et la musique vanilla sur les îles (non traitée).
- Rails et wagonnets, choisis au cadrage, alors que l'époque 1 prévoit des charrettes : les deux sont là.
- Les valeurs de `iles.json` ci-dessus.
