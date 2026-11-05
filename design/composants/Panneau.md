# Panneau

Le panneau est la fenêtre de Silooo : une texture de fonte rivetée découpée en 9, agrandie ×3. Le consommateur fournit le contenu ; le panneau ne fixe que le cadre et le texte papier.

- Utilise `sa-panneau` pour toute fenêtre de jeu (usine, carte, boutique), `sa-panneau--laiton` seulement pour le prestige et les récompenses rares.
- Garde une marge intérieure de `pas-6` au-delà des rivets ; ne pose rien sur les coins.
- Le titre va dans une `sa-plaque` en haut du panneau.
- Ne dessine jamais un panneau en CSS (bordure, arrondi, ombre floue) : en jeu ce sera la texture `panneau-fonte.png`.
