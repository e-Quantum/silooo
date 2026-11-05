# Cinématique

Une cinématique = la caméra bouge, le joueur écoute. Bandes `fonte-900` en haut et en bas (12,5 % chacune), sous-titre centré au-dessus de la bande du bas, consigne pour passer dans la bande.

- Une seule cinématique imposée : l'arrivée (20 secondes, 4 plans). Les autres (prestige, Vote party) durent 6 secondes au plus.
- Sous-titre : le nom de qui parle en `sa-soustitre__qui`, puis **une** phrase de 8 mots au plus, 3 secondes à l'écran au moins. Pas de texte sans voix ni sans personnage : la caméra suffit.
- Toujours passable : maintenir s'accroupir 1 seconde. Rejouable dans Réglages.
- Le HUD est masqué pendant la cinématique.
- Bedrock : `/camera` (preset `minecraft:free`, `ease`), `/hud @s hide all`, `camera fade` pour les coupes ; bandes et sous-titres sont un élément du pack piloté par le canal de titre. L'accroupissement se lit avec `player.isSneaking`.
