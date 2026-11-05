# Anti-IA : les tics de texte et d'image, et la règle de l'avant / après

Le texte écrit à la chaîne se repère tout de suite, et il fait passer le jeu pour une copie. Même
chose pour les images. Chaque texte et chaque visuel vus par les joueurs passent un filtre : la
liste des tics, un hook qui les signale, et un avant / après validé par Kr. Les règles s'appliquent
au jeu, au pack de ressources, au site, à Discord et à la documentation de ce dépôt.

## La liste vit dans la charte

La liste des mots et des tournures bannis est dans [design/README.md](../design/README.md), section
« Écrire sans slop ». Elle n'est pas recopiée ici : une seule liste, un seul endroit à mettre à
jour. Ce qu'elle couvre :

- Des mots de réclame bannis, dont les superlatifs des serveurs concurrents. Deux exceptions, et
  seulement comme noms de grade : Légende et Ultime.
- Des tournures bannies : les questions de pub, les listes de trois adjectifs, les « que tu sois
  ceci ou cela », les points de suspension, les tirets cadratins décoratifs, les points
  d'exclamation en série, les emoji, les majuscules d'insistance.
- Une règle de fond : une phrase, un fait ou une action, avec un chiffre, un nom du jeu ou un verbe.
- Le joueur d'abord, le jeu ensuite. Les choses nommées par leur nom du jeu, jamais par un synonyme.
- Relire à voix haute : si ça sonne comme une pub ou un mail de banque, on réécrit.

La charte donne un tableau « Pas ça / Ça ». Avant d'écrire une bulle d'Oooscar ou un message
d'erreur, relis-le.

## Côté image

Même filtre, détaillé dans la charte : pas de dégradé violet, pas de lueur, pas de verre dépoli, pas
de carte arrondie à ombre douce, pas d'icône de fusée ni d'étincelles. Pas d'emoji, jamais. Un écran
qui ressemble à un tableau de bord SaaS n'est pas dans Silooo. Les règles de dessin sont dans
[design.md](design.md).

## Ce qui compte comme texte vu par les joueurs

- Les bulles d'Oooscar, les titres de fenêtre, les libellés de boutons, les fiches, les messages de
  refus, les sous-titres de cinématique, les missions, les annonces.
- Les fichiers de textes du pack de ressources, les noms des usines, des bâtiments et des articles
  dans `definitions/`.
- Les messages du bot Discord, la fiche Hubyy, le site.
- Pas les identifiants du code : `EPIQUE` dans une énumération SQL n'est pas un texte de joueur. Le
  nom affiché de cette rareté, lui, est un texte de joueur, et il est à confirmer par Kr.

Pour la documentation du dépôt, la même liste s'applique, par hygiène : un fichier de `docs/` ne
contient ni points de suspension, ni emoji, ni mot de réclame. L'outil ne lit pas `docs/` ; c'est la
relecture qui s'en charge.

## La règle de l'avant / après

- Tout texte ou visuel nouveau, ou modifié, vu par les joueurs est présenté à Kr en avant / après :
  ce qu'il y avait, ce qu'il y aura, et pourquoi.
- Tant que Kr ne l'a pas validé, il peut être commité, jamais présenté comme définitif. La session
  le signale dans son message final ; la direction l'inscrit dans [passation.md](passation.md),
  section « À valider par Kr », avec le fichier et la ligne. La marque est « à valider par Kr ».
- Quand Kr valide, la ligne quitte la section. Quand il refuse, la session suivante réécrit.
- Un texte écrit avec une IA est relu par Kr avant d'entrer en jeu, sans exception.

## Le hook `tools/anti_ia.py`

L'outil est [tools/anti_ia.py](../tools/anti_ia.py), adapté de celui de SileneMC. Ses règles sont celles
de la charte : mots-réclame, questions de pub, « que tu sois », points de suspension, tirets cadratins,
séries de points d'exclamation, emoji, majuscules d'insistance. Son en-tête dit comment l'appeler.

- Il lit les textes des joueurs, et seulement eux : les valeurs de chaîne de `definitions/*.json` (pas
  les clés), les chaînes entre guillemets de `addon/src/**/*.ts`, les fichiers `.lang` et `texts/` du
  pack de ressources, les JSON de `world/`. Jamais `docs/` ni `design/`, qui citent les mots bannis en
  exemple ; jamais le code généré ni les dépendances.
- Il connaît deux exceptions : un mot banni qui suit « grade » ou qui est le nom d'un grade dans
  `boutique.json` (Légende, Ultime), et les identifiants en kebab-case, qui ne sont pas du texte lu.
  Quelques sigles (HUD, PDG, VIP, XUID) ne comptent pas comme majuscules d'insistance.
- Il tourne trois fois. En hook après chaque écriture d'une session Claude :
  [.claude/settings.json](../.claude/settings.json) lance [tools/hooks/anti_ia_hook.sh](../tools/hooks/anti_ia_hook.sh),
  qui appelle `anti_ia.py --hook` ; une trouvaille sort en code 2 et la session la voit. Dans
  `tools/verifier.sh`, par `anti_ia.py --tout`. Et dans la CI, qui lance le vérificateur et bloque la fusion.
- Sans Python sur le poste, le hook prévient et laisse passer ; le vérificateur, lui, échoue.
- Il signale, il ne corrige pas : la session réécrit, puis relance.
- Un faux positif se traite en réécrivant la phrase, jamais en désactivant le contrôle. Ses tests sont
  dans `tools/tests/anti-ia/` : un cas qui doit passer et un cas qui doit être signalé par règle.

Les deux autres hooks de `.claude/settings.json` : `tools/etat.sh` au démarrage de la session (branche,
dernier commit, état du dépôt) et `tools/hooks/rappel_verifier.sh` à la fin, qui rappelle de lancer le
vérificateur quand des fichiers ont changé.

## Comment corriger un texte signalé

1. Trouver le fait ou l'action que la phrase doit porter.
2. L'écrire avec un chiffre, un nom du jeu ou un verbe. Couper tout le reste.
3. Vérifier que le joueur est le sujet quand c'est lui qui agit.
4. Relire à voix haute, puis relancer le hook.
5. Noter l'avant / après pour Kr.
