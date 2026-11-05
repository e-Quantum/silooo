Silooo Atelier est l'interface de Silooo, un tycoon d'usines dans le ciel sur Minecraft Bedrock. Le style : un atelier industriel des années 1920 à 1950 (plaques émaillées, fonte rivetée, laiton, tickets de papier, compteurs mécaniques), dessiné en pixel art pour vivre dans Minecraft. Tout ce qui est montré ici doit pouvoir être reproduit par un pack de ressources Bedrock.

## La règle d'or : faisable sur Bedrock

- Chaque panneau, bouton, plaque et ticket est une **texture PNG découpée en 9** (nineslice). Utilise les fichiers du groupe Textures, jamais une boîte dessinée en CSS.
- **1 pixel de texture = 3 pixels d'écran** (échelle d'interface 3). Les tailles et les espacements sont des multiples de 3 : `pas-1` à `pas-12`.
- **Aucun arrondi, aucun dégradé, aucun flou, aucune transparence progressive** : `rayon-zero` partout. Un coin arrondi, c'est un pixel retiré dans la texture.
- **Une seule ombre** : `ombre-pixel` sous un panneau, `ombre-texte` sous un chiffre posé sur le décor. Ce sont les ombres que Bedrock sait faire.
- **Polices bitmap** : en jeu, les polices sont des planches de glyphes du pack. Les maquettes utilisent `affiche` (Jersey 10), `corps` (Pixelify Sans) et `etiquette` (Silkscreen), toujours à des tailles multiples de leur grille.
- **Animations** : seulement par images successives (flipbook) ou par déplacement entier. Pas d'animation de couleur ni de taille progressive.
- Toute image en pixel art s'affiche avec `image-rendering: pixelated` (classe `sa-pixel`) : jamais de lissage. Seules exceptions : les illustrations d'Oooscar et du logo.

## Les mots

- Français, tutoiement, phrases très courtes. Le jeu se comprend sans lire : l'icône et le chiffre portent le sens, le mot confirme.
- Oooscar parle en **6 mots au plus** par bulle (`sa-bulle`), avec un point d'exclamation quand il se réjouit : « La Fonderie t'attend ! ».
- Les titres de fenêtre sont en capitales sur une plaque (`sa-plaque`) : SCIERIE, CARTE, BOUTIQUE.
- Chiffres à la française : virgule décimale, espace fine entre milliers (12 400), suffixes k, M, Md, puis aa, ab, ac… collés à un espace près : « 84,3 Md », « 1,2 aa ».
- Les verbes des boutons disent l'action : « Y aller », « Prestige ! », « Pas encore ». Jamais « OK ».

## Écrire sans « slop »

Le texte écrit à la chaîne se repère tout de suite, et il fait passer le jeu pour une copie. Chaque texte du jeu, du site et de Discord passe ce filtre.

- **Mots bannis** : épique, incroyable, ultime (sauf le grade Ultime), légendaire (sauf le grade Légende), plonge, découvre, aventure, univers, expérience, immersif, sans limites, à portée de main, n'attend que toi, le meilleur serveur.
- **Tournures bannies** : les questions de pub (« Prêt à bâtir ton empire ? »), les listes de trois adjectifs, « Que tu sois… ou… », les points de suspension, les tirets cadratins pour faire joli, les points d'exclamation en série, les emoji, les majuscules d'insistance.
- **Une phrase = un fait ou une action.** Elle contient un chiffre, un nom du jeu ou un verbe d'action. « Ta Scierie produit 4 200 Donar/s. » et pas « Ta production s'envole ! ».
- **Le joueur d'abord, le jeu ensuite** : « Tu as 3 usines à améliorer », pas « Des améliorations sont disponibles ».
- **On nomme les choses par leur nom du jeu** (Silo, Scierie, Donar, prestige), jamais par un synonyme pour varier.
- **Relire à voix haute.** Si ça sonne comme une pub ou un mail de banque, on réécrit. Tout texte écrit avec une IA est relu par Kr avant d'entrer en jeu.

| Pas ça | Ça |
|---|---|
| Plonge dans une aventure industrielle épique ! | Construis des usines sur ton île. |
| Félicitations ! Tu as débloqué une nouvelle fonctionnalité incroyable. | Fonderie débloquée. |
| Prêt à booster ta production ? | Production ×2 pendant 15 min. |
| Oups… Il semblerait que tu n'aies pas assez de Donar ! | Il te manque 240 k Donar. |

Côté dessin, même filtre : pas de dégradé violet, pas de lueur, pas de verre dépoli, pas de carte arrondie à ombre douce, pas d'icône de fusée ou d'étincelles. Si un écran ressemble à un tableau de bord SaaS, il n'est pas dans Silooo.

## Couleurs

- Le monde : `ciel` derrière tout, `ciel-clair` pour les nuages et le fond de la carte.
- Les fenêtres : panneau en fonte (`fonte-500`, bord `fonte-900`) avec texte `papier`. Le laiton (`laiton`) est réservé au prestige et aux grands moments.
- Les titres : plaque `email-creme` avec filet `email-bleu` (information) ou `email-rouge` (action, alerte). Texte `fonte-900`.
- L'action principale d'un écran est **un seul** bouton `email-rouge`. Les autres sont en fonte ou en laiton.
- Chaque monnaie a sa couleur, partout et uniquement pour elle : Donar = `laiton` (jeton percé), Lingots = `or-lingot` (lingot), Gemmes = `amethyste` (gemme taillée).
- États : réussite `email-vert`, incident `flamme`, verrouillé = fonte assombrie + icône cadenas. Un état a toujours une icône en plus de la couleur.
- Contrastes vérifiés : `papier` sur `fonte-500` (6,6:1), `fonte-700` (11:1), `email-rouge` (5,6:1), `email-bleu` (7,9:1), `email-vert` (5,8:1) et `amethyste` (5,5:1) ; `fonte-900` sur `laiton` (6,4:1) et sur `email-creme` (13:1). `email-vert-clair` (3,5:1 sur `fonte-700`) et `or-lingot` (4,3:1 sur `fonte-500`) ne servent qu'aux chiffres de 30 px et plus. Le nombre de Gemmes s'écrit en `papier` : seule l'icône porte l'améthyste.

## Typographie

- `affiche-geante` pour le compteur de Donar et l'écran de prestige ; `affiche-titre` dans les plaques ; `affiche-chiffre` pour les niveaux, les prix et les boutons.
- `corps` pour les phrases (bulles, missions) ; `corps-gras` pour la récompense d'une phrase.
- `etiquette` pour les petites légendes en capitales (NIV. 47 · ÉPOQUE 1).
- Pas plus de deux tailles d'`affiche` dans une même fenêtre.

## Composants

- `sa-panneau` : toute fenêtre. Marge intérieure `pas-6` au-delà des rivets.
- `sa-plaque` : le titre d'une fenêtre, posé à cheval sur le bord haut du panneau.
- `sa-bouton` (rouge), `sa-bouton--laiton`, `sa-bouton--fonte` : hauteur 60 px au moins (20 pixels de texture), cible tactile confortable sur mobile.
- `sa-compteur` : le Donar en cylindres mécaniques, un cylindre par chiffre, l'unité (Md, aa…) en `laiton-clair` à droite.
- `sa-jauge` : une lampe par niveau jusqu'au prochain palier ×2 ; les lampes allumées sont `or-lingot`.
- `sa-monnaie` : icône 16 px + valeur, couleur de la monnaie.
- `sa-ticket` : un prix ou une récompense, sur papier perforé.
- `sa-bulle` : ce que dit Oooscar.
- `sa-barre` : la barre d'actions en bas de l'écran (Carte, Usines, Ville, Marché, Boutique).

## HUD, menus, cinématiques, Vote party

- **HUD en trois étages** (composant HUD) : en haut, les monnaies en `sa-puce` et une `sa-consigne` ; juste au-dessus de la barre d'inventaire, le gain par seconde, la jauge du Silo et les boosts ; en dessous, une ligne d'infos sur PC et console seulement. Le tableau latéral (`sa-tableau`) reste à droite.
- **Menus refaits de zéro** (composants Menu et Fiche) : des tuiles, pas des coffres. Appuyer sur une tuile ouvre sa fiche : sur mobile et console, il n'y a pas de survol, donc pas d'info-bulle.
- **Cinématiques** (composant Cinématique) : bandes, sous-titre d'une phrase avec le nom de qui parle, toujours passables.
- **Vote party** (composant Vote party) : jauge commune en haut de l'écran, fête de 6 secondes, même récompense pour tous.
- Ce qu'on ne reprend pas des serveurs Java : pile ou face et paris, enchères entre joueurs, coffres payants au hasard, monnaie échangeable. Pour des raisons de règles Mojang et de protection des mineurs.

## Icônes

- 16 × 16 pixels, contour `fonte-900` d'un pixel, lumière en haut à gauche, ombre en bas à droite. Affichées à ×2, ×3 (`sa-icone`, 48 px) ou ×4, jamais à une taille intermédiaire.
- Groupe Icônes : donar, lingot, gemme, usine, carte, ville, marche, boutique, cadenas, engrenage, eclair (électricité), flamme (incident), etoile (prestige), fermer.
- Pas d'emoji, jamais.

## Oooscar et le logo

- Oooscar et le logo sont les deux seules **illustrations** du système : des volumes en blocs, cernés d'un trait `#2b211c`, ombrés en trois tons par face (dessus clair, face, côté sombre). Ce ne sont pas des textures 9-slice : en jeu, ce sont des images PNG posées telles quelles, affichées **sans** `sa-pixel`.
- Oooscar est un petit silo en blocs, comme un mob du jeu : corps en tôle claire, cerclages gris, échelle sur le flanc droit, toit rouge en gradins qui glisse de travers (il est maladroit). Gros yeux carrés, joues roses.
- Cinq expressions, une par situation : `joie` (accueil, gains), `fier` (prestige, palier ×2), `oups` (erreur, pas assez de Donar), `panique` (incident à réparer), `dodo` (gains hors ligne). On n'en invente pas d'autre sans la dessiner.
- Oooscar accompagne une bulle (`sa-bulle`), jamais seul au milieu d'une fenêtre ; il se tient en bas à gauche de l'écran, tourné vers le centre.
- Le logo est un pictogramme sans texte : un silo à toit rouge posé sur un bloc d'herbe. Fichiers à 512, 128 et 64 px plus le SVG ; en dessous de 64 px, on garde le fichier de 64 réduit, pas un autre dessin. Fond `ciel`, `ciel-clair` ou transparent ; jamais de cadre ni de plaque autour.

## Décor

- Le groupe Décor contient des tuiles 16 × 16 originales (herbe, terre, pierre, planches) pour dessiner les îles dans les maquettes. Elles ne copient pas les textures de Minecraft : en jeu, ce sont les vrais blocs qui s'affichent.
