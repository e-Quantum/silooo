# Vision : Silooo en une page

Silooo est un jeu Minecraft Bedrock en français, joué sur `play.silooo.com`. C'est un tycoon
d'usines dans le ciel. Le joueur hérite d'une carrière abandonnée sur une île, rachète d'autres
usines, embauche du personnel, regarde son archipel produire du Donar, puis fait un prestige et
recommence plus fort. Ce fichier résume le jeu. Le détail est dans [game-design.md](game-design.md).

## Le jeu en quelques lignes

- Un archipel privé par entreprise : une île principale, verrouillée au départ, qui devient la ville
  du joueur, et une petite île par usine.
- Des usines qui s'alimentent entre elles. La chaîne du fer (Carrière, Mine profonde, Fonderie,
  Atelier d'outils) est la première complète. La liste des usines vit dans
  [definitions/usines.json](../definitions/usines.json).
- Toutes les marchandises passent par le Silo, bâtiment central de l'île principale. Oooscar, la
  mascotte, est un petit silo maladroit qui guide le joueur et fête ses réussites.
- Le prestige revend les usines, fait passer l'entreprise à l'époque suivante et convertit le Donar
  de la course en Lingots. La ville reste. Après la dernière époque, chaque prestige donne une étoile.
- Seul ou en entreprise partagée : un PDG, des associés, une seule course commune.
- Trois monnaies : le Donar pour la course, les Lingots pour ce qui dure, les Gemmes pour la
  boutique. Voir [economie.md](economie.md) et [glossaire.md](glossaire.md).
- Une ville à bâtir sur l'île principale : la mairie débloque les zones, achetées en Lingots à prix
  fixe, et le joueur pose des bâtiments préfabriqués puis décore bloc par bloc.
- Un serveur sans reset : les îles, la ville et les Lingots sont permanents. Les saisons ne remettent
  à zéro que le pass et les classements.

## Pour qui

- Tout public, enfants compris : chaque écran se comprend en regardant, sans lire une phrase.
- D'abord les joueurs console et mobile venus par Hubyy, souvent en groupe d'amis.
- Deux rythmes : les usines en sessions courtes au quotidien, la ville en longues sessions.
- Un joueur console hésite pour trois raisons : il ne connaît pas le serveur, il craint le
  pay-to-win, il a peur de perdre son temps. La première vente arrive dans les toutes premières
  minutes, mise en scène comprise.

## Les quatre piliers

Toute idée qui ne sert aucun pilier est rejetée.

1. Ça bouge tout le temps : chaque usine, vente et prestige a une animation ou un effet visible.
2. Une session courte suffit : quinze minutes font toujours avancer quelque chose.
3. Entre amis : entreprise partagée, visites des villes, objectifs communs.
4. Jamais pay-to-win : la boutique vend de l'apparence et du confort. La promesse affichée aux
   joueurs est « tout ce qui s'achète se gagne aussi en jouant ».

## Ce qu'on ne fait pas

Ces interdits sont dans [CLAUDE.md](../CLAUDE.md) et dans [design/README.md](../design/README.md),
section « HUD, menus, cinématiques, Vote party ». Ils valent pour le jeu, le site et Discord.

- Pas de hasard payant : aucun coffre ouvert au hasard contre une clé achetée, aucun pile ou face.
- Pas de pari ni d'enchère entre joueurs.
- Pas de monnaie échangeable entre joueurs : aucun don de Donar, de Lingots ni de Gemmes.
- Jamais en vente : Lingots, zones de ville, places au classement, usines, salariés, niveaux,
  multiplicateurs personnels, ressources.
- Pas de tic d'IA dans un texte ou une image vus par les joueurs : voir [anti-ia.md](anti-ia.md).
- Pas de message d'achat pendant la première heure de jeu, pas de fenêtre de vente qui s'ouvre seule.
- Pas de besoins à gérer en ville (faim, humeur) : la ville sert à progresser et à se montrer.
- Pas de punition : jamais de grève ni d'incident pendant une absence, et une chute dans le vide
  ramène au dernier point sûr sans perte.

## Deux points que le game design laisse ouverts

Les deux sont dans « À faire par Kr » de [passation.md](passation.md) ; rien ne s'écrit dessus avant sa
réponse.

- Le game design cite encore des lootbox avec des clés achetables. Les règles du dépôt interdisent
  le hasard payant. Tant que Kr n'a pas tranché, aucun écran ni aucune route de lootbox ne s'écrit :
  à confirmer par Kr.
- L'OpenAPI prévoit un marché d'annonces entre joueurs (`createListing`, `buyListing`). Le plan
  recommande un marché où l'on vend au Silo à un prix fixé par le jeu, et de retirer les annonces de
  l'API : à confirmer par Kr. Voir [economie.md](economie.md), section « Le marché ».

## Où lire ensuite

- [game-design.md](game-design.md) : le jeu en entier.
- [plan-construction.md](plan-construction.md) : les phases et l'ordre de construction.
- [glossaire.md](glossaire.md) : chaque mot du jeu et son nom dans le code.
- [architecture.md](architecture.md) : qui décide, qui affiche, qui stocke.
