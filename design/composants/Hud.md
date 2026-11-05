# HUD

Ce que le joueur voit en permanence, rangé en trois étages autour de la barre d'inventaire. Rien d'autre ne s'affiche hors des menus.

- **En haut de l'écran** : une rangée de `sa-puce` (Donar, Lingots, Gemmes, prestige), puis une seule `sa-consigne` (l'objectif en cours). Quatre puces au plus.
- **Juste au-dessus de la barre d'inventaire** : le `sa-gain` par seconde, la `sa-progression` du Silo, les boosts actifs en `sa-puce` avec leur minuterie. C'est la barre d'action de Bedrock (`setActionBar`), redessinée par le pack.
- **Juste en dessous de la barre d'inventaire** : rien sur mobile (les commandes tactiles prennent la place) ; sur PC et console, une ligne d'`etiquette` : île, membres connectés, ping.
- `sa-tableau` (scoreboard) à droite : 8 lignes au plus, une icône par ligne, l'adresse du serveur en pied.
- `sa-gain` au-dessus d'une usine quand elle livre : un chiffre, pas de mot.
- Les puces se mettent à jour une fois par seconde au plus ; le compteur de Donar ne clignote jamais.
- Bedrock : le haut et le dessous de la barre sont des éléments ajoutés à `hud_screen.json` dans le pack, nourris par le canal de titre (`setTitle` avec un préfixe que le pack masque). La barre d'inventaire elle-même reste vanilla.
