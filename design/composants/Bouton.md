# Bouton

Le bouton est une plaque d'émail en relief qui s'enfonce d'un pixel quand on appuie. Le consommateur fournit le libellé (verbe ou chiffre) et, si besoin, une icône 16 px.

- **Un seul** bouton rouge (`sa-bouton`) par écran : l'action principale.
- `sa-bouton--laiton` pour une action secondaire qui rapporte (acheter, améliorer), `sa-bouton--fonte` pour annuler, fermer, revenir.
- Hauteur 60 px au moins. L'état appuyé (`is-appuye` ou `:active`) change de texture et descend le texte de 3 px.
- Désactivé : texture assombrie et, si c'est une question d'argent, le prix manquant affiché dans un `sa-ticket` à côté.
