# Design : le design system et le pack de ressources

L'interface de Silooo s'appelle Silooo Atelier : un atelier industriel des années 1920 à 1950,
dessiné en pixel art pour vivre dans Minecraft. La charte est
[design/README.md](../design/README.md) ; chaque composant a sa fiche dans
[design/composants/](../design/composants/). Ce fichier dit comment ces règles se traduisent dans le
pack de ressources et où vont les fichiers. Rien ici ne remplace la charte : en cas de doute, elle
gagne.

## Où regarder avant de dessiner

| Besoin | Fichier |
| --- | --- |
| Les règles, les couleurs, les mots, l'anti-slop | [design/README.md](../design/README.md) |
| Un composant précis (panneau, bouton, fiche, HUD) | [design/composants/](../design/composants/), un fichier par composant |
| Les couleurs et les tailles en jetons | [design/tokens.json](../design/tokens.json), [design/tokens.css](../design/tokens.css) |
| Les textures 9-slice et HUD, taille native | `design/textures/` |
| Les icônes | `design/icones/` |
| Oooscar et le logo | `design/mascotte/`, `design/logo/` |
| Comment les fichiers du design s'articulent | [design/LISEZ-MOI-FICHIERS.md](../design/LISEZ-MOI-FICHIERS.md) |
| Les maquettes | artifact privé de Kr, lien dans le LISEZ-MOI |

Le CSS de [design/composants/bundle.css](../design/composants/bundle.css) sert aux maquettes, pas
au jeu : en jeu, tout passe par des textures et du JSON UI.

## La règle d'or : faisable sur Bedrock

- Chaque panneau, bouton, plaque et ticket est une texture PNG découpée en neuf (9-slice). Les
  coins restent fixes, les bords et le centre s'étirent. Jamais une boîte dessinée autrement.
- Un pixel de texture vaut trois pixels d'écran : échelle d'interface 3. Les tailles et les
  espacements sont des multiples de trois.
- Aucun arrondi, aucun dégradé, aucun flou, aucune transparence progressive. Un coin arrondi est un
  pixel retiré dans la texture.
- Une seule ombre : un décalage plein sous un panneau ou sous un chiffre posé sur le décor.
- Polices bitmap : des planches de glyphes dans le pack, affichées à des tailles multiples de leur
  grille. Trois rôles : affiche (titres et chiffres), corps (phrases), étiquette (petites légendes
  en capitales). Au plus deux tailles d'affiche dans une fenêtre.
- Animations par images successives ou par déplacement entier. Jamais de couleur ni de taille qui
  change progressivement.
- Tout le pixel art s'affiche sans lissage. Seules exceptions : Oooscar et le logo.

## Les couleurs et les états

- Les couleurs ont un nom dans les jetons et un seul usage : fonte pour les fenêtres, émail crème
  pour les titres, laiton pour le prestige et les grands moments, un seul bouton rouge par écran.
- Chaque monnaie a sa couleur et son icône, partout et pour elle seule : Donar en laiton (jeton
  percé), Lingots en or (lingot), Gemmes en améthyste (gemme taillée).
- Un état a toujours une icône en plus de sa couleur : réussite, incident, verrouillé.
- Les contrastes sont vérifiés dans la charte ; les couleurs à faible contraste ne servent qu'aux
  grands chiffres.

## Les icônes

- Seize pixels de côté, contour d'un pixel, lumière en haut à gauche, ombre en bas à droite.
- Affichées à ×2, ×3 ou ×4, jamais à une taille intermédiaire.
- Le jeu d'icônes est dans `design/icones/` : une par monnaie, une par grande fenêtre, et les états.
  Une icône qui manque se dessine dans le même style, puis s'ajoute à la charte.
- Pas d'emoji, jamais.

## Oooscar

Oooscar est un petit silo en blocs, comme un mob du jeu : corps en tôle claire, cerclages gris,
échelle sur le flanc droit, toit rouge en gradins qui glisse de travers. Gros yeux carrés, joues
roses. C'est une illustration, pas une texture 9-slice : une image posée telle quelle, sans
lissage forcé.

| Expression | Fichier | Quand |
| --- | --- | --- |
| joie | `design/mascotte/oooscar-joie` | Accueil, gains |
| fier | `design/mascotte/oooscar-fier` | Prestige, palier ×2 |
| oups | `design/mascotte/oooscar-oups` | Erreur, pas assez de Donar |
| panique | `design/mascotte/oooscar-panique` | Incident à réparer |
| dodo | `design/mascotte/oooscar-dodo` | Gains hors ligne |

- Chaque expression existe en PNG et en SVG dans `design/mascotte/`.
- On n'invente pas de sixième expression sans la dessiner d'abord et l'ajouter à la charte.
- Oooscar accompagne toujours une bulle de six mots au plus. Il se tient en bas à gauche, tourné
  vers le centre, jamais seul au milieu d'une fenêtre.
- Chaque code de refus de l'API ([architecture.md](architecture.md)) a une expression et une
  bulle : le tableau de correspondance s'écrit dans `addon/` et passe le filtre anti-IA.
- En jeu, Oooscar est aussi une entité du pack de comportement, avec son modèle et ses animations ;
  ses expressions à l'écran restent des images.

## Le logo

Un pictogramme sans texte : un silo à toit rouge sur un bloc d'herbe. Fichiers à 512, 128 et 64
pixels plus le SVG dans `design/logo/`. En dessous de 64 pixels, on réduit le fichier de 64, on ne
redessine pas. Fond ciel, ciel clair ou transparent ; jamais de cadre ni de plaque autour.

## Les mots à l'écran

- Français, tutoiement, phrases très courtes. L'icône et le chiffre portent le sens, le mot confirme.
- Titres de fenêtre en capitales sur une plaque, un ou deux mots.
- Les boutons disent l'action : « Y aller », « Pas encore ». Jamais « OK ».
- Chiffres à la française, suffixes k, M, Md, aa : voir [economie.md](economie.md).
- Tout texte nouveau passe [anti-ia.md](anti-ia.md) et l'avant / après validé par Kr.

## Où vont les fichiers dans `resource-pack/`

Le dossier n'existe pas encore ; il se crée en phase 2. Arrangement proposé, à fixer à ce moment :

| Contenu | Emplacement | Source |
| --- | --- | --- |
| Textures 9-slice et HUD | `resource-pack/textures/ui/silooo/` | `design/textures/`, même nom |
| Icônes | `resource-pack/textures/ui/silooo/icones/` | `design/icones/` |
| Expressions d'Oooscar | `resource-pack/textures/ui/silooo/oooscar/` | `design/mascotte/`, PNG |
| Modèle et texture de l'entité Oooscar | `resource-pack/models/entity/`, `resource-pack/textures/entity/` | à dessiner depuis les SVG |
| Formulaires et HUD | `resource-pack/ui/server_form.json`, `resource-pack/ui/hud_screen.json` | [bedrock.md](bedrock.md) |
| Polices bitmap | `resource-pack/font/` | planches de glyphes à produire depuis les trois polices de la charte |
| Textes | `resource-pack/texts/fr_FR.lang` | les chaînes vues par les joueurs, en français seulement |
| Logo | icône du pack (`pack_icon.png`) | `design/logo/` |

Les textures restent à leur taille native dans le pack ; c'est l'interface qui agrandit ×3. Les
fichiers de `design/` sont la source : on les copie, on ne les retouche pas dans le pack. Une
texture nouvelle se dessine d'abord dans `design/`, puis se copie.

## Ce qu'on vérifie avant de fusionner

- JSON UI valide, textures aux tailles de la charte : ces contrôles entrent dans `tools/verifier.sh`
  et bloquent la fusion.
- Aucun dégradé, aucune lueur, aucun verre dépoli, aucune carte arrondie à ombre douce, aucune icône
  de fusée ni d'étincelles. Si un écran ressemble à un tableau de bord SaaS, il n'est pas dans Silooo.
- Un écran se comprend sans lire une phrase : un enfant doit faire sa première vente seul.
