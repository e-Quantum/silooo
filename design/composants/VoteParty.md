# Vote party

Chaque vote du serveur remplit une jauge commune. À 50 votes, tout le monde connecté gagne la même chose en même temps.

- Jauge en haut de l'écran : `sa-progression--haute`, libellé « 47 / 50 VOTES ». Elle n'apparaît que dans les 10 derniers votes et pendant la fête, pour ne pas encombrer le haut de l'écran.
- La fête : décompte 3, 2, 1 en titre, puis une cinématique de 6 secondes au Silo, puis le boost `sa-puce` « ×2 · 15:00 » au-dessus de la barre d'inventaire.
- La récompense est la même pour tous et ne s'achète pas : production ×2 et une clé de coffre gratuite. Jamais de Gemmes achetables ni de grade.
- Le menu Vote liste les sites, l'état de chaque vote (« Voté » en vert, sinon le temps restant) et la série de jours.
- Bedrock : les votes arrivent par l'API des sites de vote, interrogée par silooo-core ; le compteur est commun à tout le serveur.
