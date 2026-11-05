---
name: silooo-ecran
description: "Ajouter ou refaire un écran Bedrock (menu, fiche, HUD, cinématique, bulle d'Oooscar) selon le design system Silooo Atelier, textes français compris. À utiliser pour tout écran ou texte vu par les joueurs."
---

# Ajouter un écran Bedrock

Un écran Silooo, c'est un composant du design system, des textes courts en français, un formulaire serveur et un JSON UI. Le service renvoie les valeurs, l'add-on les affiche.

## Quand l'utiliser

- Un menu, une fiche, un élément du HUD, une cinématique ou une bulle d'Oooscar s'ajoute ou change.
- Un texte vu par les joueurs s'écrit ou se réécrit : titre, bouton, bulle, ligne d'info.
- L'arborescence `addon/` et `resource-pack/` arrive en phase 2. Tant qu'elle manque, la recette s'arrête à la maquette et aux textes (étapes 1, 2, 3 et 6). Écris le reste dans « Ce qui reste » du compte rendu.

## À lire d'abord

- [`CLAUDE.md`](../../../CLAUDE.md) : les règles du code et les interdits.
- [`design/README.md`](../../../design/README.md), en entier : la règle d'or, « Les mots », « Écrire sans slop », les couleurs, les composants.
- Le fichier du composant choisi dans `design/composants/`, par exemple [`Fiche.md`](../../../design/composants/Fiche.md), [`Bouton.md`](../../../design/composants/Bouton.md) ou [`Menu.md`](../../../design/composants/Menu.md).
- `design/textures/` et `design/icones/` : les seules sources de textures et d'icônes.
- `docs/bedrock.md` (limites de la Script API et du JSON UI), `docs/design.md`, `docs/anti-ia.md` et `docs/glossaire.md`.
- [`api/silooo-core.openapi.yaml`](../../../api/silooo-core.openapi.yaml) : les schémas que l'écran affiche (`CompanyState`, `Factory`, `Balances`) et les codes `reason` de `Refused`.
- [`docs/socle.md`](../../../docs/socle.md) : règles 1 et 2, le service décide et le compteur défile.

## Étapes

1. **Choisis le composant et relis la charte.** Prends-le dans `design/composants/` : Panneau, Menu, Fiche, Bouton, Plaque, Ticket, Monnaie, Jauge, Compteur, Hud, BarreActions, Cinematique, VoteParty. Si aucun ne convient, arrête-toi et dis-le : le design system se complète chez Kr, pas dans un écran. Garde la règle d'or sous les yeux. Fichier touché : aucun.
   - Faisable sur Bedrock : texture 9-slice, échelle ×3, tailles en multiples de 3.
   - Aucun arrondi, dégradé, flou ni transparence progressive.
   - Un seul bouton rouge par écran : l'action principale.
   - Une icône en plus de chaque couleur d'état. Un état verrouillé porte le cadenas et dit ce qui le débloque.
   - Chaque monnaie garde sa couleur, et rien d'autre ne la prend.
   - Pas d'info-bulle : il n'y a pas de survol sur mobile ni sur console.
2. **Fais la maquette.** Écris le plan de l'écran en texte, de haut en bas : chaque composant, sa texture de `design/textures/`, son icône de `design/icones/` (à ×2, ×3 ou ×4, jamais entre deux), et pour chaque valeur affichée le champ du service qui la fournit (`CompanyState.donar`, `Balances.lingots`, `Factory.nextCost`). Une valeur sans champ du service ne s'affiche pas : la route d'abord (`silooo-route`). Fichier touché : aucun, le plan va dans le compte rendu.
3. **Écris les textes en français**, au tutoiement, selon « Écrire sans slop » de `design/README.md`. Avant la phase 2, ils vont dans le compte rendu ; ensuite, dans les fichiers de l'étape 4.
   - Oooscar : 6 mots au plus par bulle, une des cinq expressions existantes (`joie`, `fier`, `oups`, `panique`, `dodo`).
   - Titres de fenêtre : un ou deux mots, en capitales, sur une plaque.
   - Boutons : un verbe d'action (« Y aller », « Prestige ! »), jamais « OK ».
   - Chiffres à la française : virgule décimale, espace fine entre milliers, suffixes k, M, Md, puis aa, ab.
   - Une phrase égale un fait ou une action, avec un chiffre, un nom du jeu ou un verbe. Chaque chose garde son nom du jeu (`docs/glossaire.md`).
   - Pas d'emoji, de question de pub, de points de suspension ni de tiret cadratin décoratif.
   - Pour chaque code `reason` que l'écran peut recevoir, par exemple `PAS_ASSEZ_DE_DONAR` ou `ZONE_VERROUILLEE`, choisis une icône et une bulle d'Oooscar : l'add-on décide de l'affichage, le service ne donne que le code.
4. **Écris le formulaire serveur** (phase 2). Un `ActionFormData` par menu, un second pour la fiche qu'ouvre une tuile. L'appel au service passe par `addon/src/api/` (`silooo-route`). L'add-on affiche ce que le service renvoie : aucun solde, prix ni gain calculé ici. Le compteur de Donar défile à partir de `ratePerHour` et se recale à chaque réponse. Sur `CONFLIT_REESSAYER`, l'add-on rejoue l'appel avec l'état frais. Fichier touché : `addon/src/ui/`.
5. **Écris le JSON UI** (phase 2). `server_form.json` redessine les menus et les fiches ; `hud_screen.json` porte le HUD, la cinématique et la Vote party, nourris par le canal de titre (`setTitle` avec un préfixe que le pack masque) et par la barre d'action. Les textures viennent de `design/textures/`, copiées telles quelles à leur taille native, jamais redessinées ni recolorées. Lis `docs/bedrock.md` avant : le JSON UI casse à certaines versions. Fichiers touchés : `resource-pack/ui/` et les textures copiées dans `resource-pack/`.
6. **Passe le filtre anti-IA.** `tools/anti_ia.py` signale les tics d'IA dans les textes des joueurs, et le hook le lance après chaque écriture. Corrige chaque signalement en réécrivant la phrase, pas en la contournant avec un synonyme. Relis à voix haute : si ça sonne comme une pub ou un mail de banque, réécris. Fichier touché : les fichiers de textes.
7. **Garde l'avant / après** de chaque texte et de chaque visuel nouveau ou changé, pour le compte rendu. Fichier touché : aucun.

## Ce qu'on ne fait pas

- Aucun solde, prix ni gain calculé dans `addon/`, même une somme pour l'affichage.
- Aucune boîte dessinée autrement que par une texture 9-slice de `design/textures/`.
- Pas de second bouton rouge, pas d'état porté par la couleur seule, pas d'info-bulle au survol.
- Pas d'expression d'Oooscar inventée, et jamais Oooscar seul au milieu d'une fenêtre : il accompagne une bulle.
- Pas de nouveau composant ni de nouvelle texture sans accord de Kr.
- Pas de texte ou de visuel présenté comme définitif : tout ce qui est nouveau reste « à valider par Kr ».
- Pas de hook anti-IA désactivé, pas de mot du filtre déguisé en synonyme.
- Aucune mécanique de hasard payant, de pari ni de monnaie échangeable entre joueurs.

## Vérifier

```
tools/verifier.sh   # Git Bash sur le PC : JSON UI, textures, anti-IA, liens
```

- `tools/anti_ia.py` : relance-le sur un fichier de textes si le hook ne l'a pas fait ; son en-tête dit comment l'appeler.
- Contrôle chaque point de la règle d'or sur la maquette ou sur l'écran, un par un.
- Un essai en jeu demande le serveur et un client Bedrock. Si tu ne peux pas le faire, écris-le dans « Ce qui reste ».

## En fin de session

Le compte rendu va dans ton message final. Tu n'écris pas dans `docs/passation.md` : la direction y copie le journal.

- **Ce qui a changé** : l'écran, le composant choisi, les fichiers touchés.
- **Avant / après** : un tableau `Où | Avant | Après` pour chaque texte, et une ligne par visuel (avant : rien ou l'ancien dessin ; après : ce qui s'affiche).
- **Ce qui reste** : les étapes 4 et 5 si `addon/` et `resource-pack/` n'existent pas encore, l'essai en jeu sur mobile et console.
- **Comment vérifier** : les commandes ci-dessus et l'écran à ouvrir.
- **À valider par Kr** : chaque texte et chaque visuel nouveau, un par un.
