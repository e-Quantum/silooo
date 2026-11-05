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
- En jeu, Oooscar est aussi une entité : son comportement est dans le pack de comportement, son modèle, sa texture
  et ses animations dans le pack de ressources ; ses expressions à l'écran restent des images.

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

Le dossier existe depuis la phase 3. `node tools/verifier_pack.mjs`, lancé par `tools/verifier.sh`, lit tous les JSON,
compare chaque copie à sa source octet pour octet, vérifie que les fichiers cités existent et relie l'entité d'Oooscar
à son modèle, ses animations et son contrôleur de rendu.

| Contenu | Emplacement | Source | État |
| --- | --- | --- | --- |
| Textures 9-slice et HUD | `resource-pack/textures/ui/silooo/` | `design/textures/`, même nom, copies | fait |
| Icônes | `resource-pack/textures/ui/silooo/icones/` | `design/icones/`, copies | fait |
| Expressions d'Oooscar | `resource-pack/textures/ui/silooo/oooscar/` | `design/mascotte/`, PNG, copies | fait |
| Entité Oooscar, côté client | `entity/oooscar.entity.json`, `models/entity/oooscar.geo.json`, `textures/entity/oooscar.png`, `animations/oooscar.animation.json`, `render_controllers/` | modèle et texture dessinés par `tools/generer_oooscar.mjs` depuis `design/tokens.json` | fait |
| HUD | `resource-pack/ui/hud_screen.json`, `ui/silooo/hud.json`, `ui/_ui_defs.json` | [bedrock.md](bedrock.md) | fait |
| Formulaires | `resource-pack/ui/server_form.json` | [bedrock.md](bedrock.md) | à faire |
| Polices bitmap | `resource-pack/font/` | planches de glyphes à produire depuis les trois polices de la charte | à faire |
| Textes | `resource-pack/texts/fr_FR.lang`, `languages.json` | le nom et la description du pack, en français seulement | fait, à valider par Kr |
| Logo | `resource-pack/pack_icon.png` | `design/logo/silooo-logo-128.png`, copie | fait |

Les textures restent à leur taille native dans le pack ; c'est l'interface qui agrandit ×3. Les
fichiers de `design/` sont la source : on les copie, on ne les retouche pas dans le pack. Une
texture nouvelle se dessine d'abord dans `design/`, puis se copie, et la vérification refuse une copie qui diffère ou
qui n'a pas de source.

### Le HUD : les monnaies et la consigne passent par le titre

L'add-on écrit le haut du HUD par le canal de titre de Bedrock. Il envoie les monnaies dans le titre, précédées de
`silooo:hud|`, et la consigne dans le sous-titre, précédée de `silooo:consigne|` (constantes de `addon/src/ui/hud.ts`,
que `verifier_pack.mjs` retrouve dans les JSON du pack). Le pack s'en sert comme d'un canal de données :

- `ui/hud_screen.json` masque le titre et le sous-titre d'origine, avec leurs fonds, quand le texte commence par le
  préfixe. Le test de début de chaîne est une expression de liaison : `(('p' + (#text - 'p')) = #text)` est vraie
  quand `#text` commence par `p` (on retire `p` puis on le remet). Un titre sans préfixe, comme ceux de la
  cinématique, reste visible et inchangé.
- `ui/silooo/hud.json` lit les mêmes chaînes globales (`#hud_title_text_string`, `#hud_subtitle_text_string`), retire
  le préfixe et dessine le reste sur une puce `puce-fonte`, en haut à gauche pour les monnaies et juste dessous pour la
  consigne. Rien ne s'affiche quand le reste est vide.

Seul un vrai client Bedrock confirme le rendu : la portée des liaisons globales pour un contrôle ajouté, l'évaluation
des expressions de chaîne, la mise en page et un éventuel éclair du titre d'origine avant son masquage. Le JSON UI
change d'une version du jeu à l'autre ([bedrock.md](bedrock.md)) ; en cas d'écart, on corrige dans `ui/`, pas dans
l'add-on. Tant que ce n'est pas confirmé, `config.ui.hud.titleChannel` reste à `false` dans l'add-on : le HUD passe
alors par la barre d'action seule, et le préfixe ne s'affiche jamais.

### Oooscar côté client

`tools/generer_oooscar.mjs` écrit le modèle et la texture d'un seul coup, pour que chaque cube trouve ses pixels ;
`--verifier` compare les fichiers au script. L'entité cliente s'appelle `silooo:oooscar`, comme celle du pack de
comportement : un balancement lent en continu, et un geste d'accueil pendant les deux premières secondes de vie de
l'entité. Pas de son. Le côté de l'échelle est la constante `X_ECHELLE` du script : à confirmer sur un client, un seul
chiffre à changer s'il est à l'envers.

### Installation sur le serveur

L'image `silooo-bds` embarque `resource-pack/` dans `/opt/silooo/resource-pack`. À chaque démarrage, `start.sh` le copie
dans `resource_packs/silooo` de BDS et inscrit son uuid et sa version (lus dans son manifeste) dans
`world_resource_packs.json` du monde, comme pour le pack de comportement. `server.properties` fixe
`texturepack-required=true` : le HUD en dépend, un joueur qui refuse le pack ne se connecte pas. Le pack pèse environ
570 Ko.

## Ce qu'on vérifie avant de fusionner

- JSON UI valide, textures aux tailles de la charte : ces contrôles entrent dans `tools/verifier.sh`
  et bloquent la fusion.
- Aucun dégradé, aucune lueur, aucun verre dépoli, aucune carte arrondie à ombre douce, aucune icône
  de fusée ni d'étincelles. Si un écran ressemble à un tableau de bord SaaS, il n'est pas dans Silooo.
- Un écran se comprend sans lire une phrase : un enfant doit faire sa première vente seul.
