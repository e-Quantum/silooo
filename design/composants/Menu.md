# Menu

Les menus de Silooo sont des écrans à tuiles, pas des coffres. Un menu = un `sa-panneau` plein écran, une `sa-plaque` pour le titre, des onglets à gauche si besoin (`sa-onglet`, l'actif en laiton) et une grille de `sa-tuile`.

- Une tuile = une icône, un nom d'un mot, **une** ligne d'info qui donne un chiffre ou un état (« 3 à améliorer », « Prestige 3 requis »). Pas de phrase.
- `sa-pastille` dit combien de choses attendent le joueur. Jamais sur plus de deux tuiles à la fois, sinon elle ne veut plus rien dire.
- Tuile verrouillée : `is-verrouillee`, icône cadenas, et l'info dit ce qui la débloque. On ne cache jamais une fonction verrouillée.
- 8 tuiles par écran au plus (grille 4 × 2). Au-delà, on fait un sous-menu.
- Bas du panneau : « Retour » en `sa-bouton--fonte` à gauche, et c'est tout. Pas de « Fermer » en plus : le bouton du jeu ferme déjà.
- Bedrock : chaque menu est un formulaire serveur (`ActionFormData`) redessiné par `server_form.json` dans le pack. Appuyer sur une tuile ouvre sa `sa-fiche` dans un second formulaire : il n'y a pas d'info-bulle au survol sur mobile ni sur console.
