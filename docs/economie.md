# Économie : formules, paliers, prestige, notation

Les chiffres du jeu vivent dans [definitions/economie.json](../definitions/economie.json),
[definitions/usines.json](../definitions/usines.json) et
[definitions/boutique.json](../definitions/boutique.json). Le tableur
[silooo-equilibrage-v2.xlsx](references/silooo-equilibrage-v2.xlsx) simule un joueur type et
vérifie treize cibles de rythme. Ce fichier explique les mécanismes et renvoie aux clés ; il ne
recopie aucune valeur. Pour changer un chiffre, suis la recette `silooo-equilibrage` et ajoute une
ligne à l'historique en bas de ce fichier.

## Les trois couches

| Couche | Monnaie | Évolution | Sert à | Remise à zéro |
| --- | --- | --- | --- | --- |
| Course | Donar | Exponentielle | Usines, salariés, améliorations, logistique | À chaque prestige |
| Patrimoine | Lingots | Lente, jamais d'explosion | Zones et bâtiments de la ville, marché, boutique de prestige | Jamais |
| Boutique | Gemmes | Achetée en euros | Boutique en jeu | Jamais |

L'inflation est voulue dans la première couche et impossible dans la seconde. Le Donar est un
`DOUBLE`, les Lingots et les Gemmes des `BIGINT` : voir [base-de-donnees.md](base-de-donnees.md).

## Le prix des niveaux

- Chaque niveau d'une usine coûte un multiple du précédent : clé `niveaux.croissancePrix`.
- Le prix du niveau 1 de chaque usine : clé `prixNiveau1` de l'usine dans `usines.json`.
- Le prix pour monter de `k` niveaux d'un coup est une somme géométrique, écrite dans la clé
  `niveaux.formulePrix` :

  ```
  prix(n -> n+k) = prixNiveau1 * croissance^n * (croissance^k - 1) / (croissance - 1)
  ```

- L'opération `buyLevels` accepte `1`, `10`, `palier` (jusqu'au prochain ×2) et `max` (tout ce que
  le solde permet). C'est le service qui calcule, jamais l'add-on.
- La croissance du prix est le réglage le plus sensible du jeu : une variation au centième rallonge
  le premier prestige de plusieurs heures.

## La production et les paliers

- La production d'un niveau, salaires déduits et avant multiplicateurs : clé `productionParNiveau`
  de chaque usine, dans l'unité donnée par la clé `unites` de `usines.json`.
- La production se calcule au temps écoulé, jamais bloc par bloc.
- Paliers ×2 : la production d'une usine double à certains niveaux, fixés par la clé
  `niveaux.paliersX2` (des paliers fixes, puis un pas régulier). Le champ `Factory.nextMilestone`
  donne le prochain, et la jauge à lampes du HUD compte les niveaux qui restent.
- Le multiplicateur global `CompanyState.multiplier` regroupe plusieurs bonus : Lingots détenus,
  grade, Pilier, étoiles, bâtiments de la ville, boosts. La façon de les combiner est celle de la
  feuille « Courses » du tableur, colonne « Multiplicateur global » : à reprendre telle quelle. Les bonus de grade et de Pilier
  sont dans `boutique.json`, clés `grades[].bonusDonarPourcent` et `abonnements[].bonusDonarPourcent`.
- Les salaires sont la sortie d'argent principale. Hors ligne, ils se paient sur la production de la
  période et ne font jamais passer le compte en négatif.

## Hors ligne

- La production continue pendant l'absence, jusqu'à un plafond en heures : clé
  `horsLigne.plafondHeures` au départ, puis la colonne `companies.offline_cap_hours` quand les
  améliorations du chef d'équipe et du Silo l'ont étendu.
- Elle est comptée à efficacité réduite : clé `horsLigne.efficacite`.
- L'opération `collect` applique le plafond et renvoie `offlineHours`, les heures retenues. À
  l'arrivée, `JoinResponse.offlineCollected` donne ce qui a été encaissé pendant l'absence.
- Sans chef d'équipe, une usine s'arrête quand son stock est plein. Jamais de grève ni d'incident
  pendant une absence.

## Le prestige et les Lingots

- Le prestige est permis quand les Donar gagnés dans la course atteignent le seuil de la course :
  clé `prestige.seuils`, une entrée par course, et `CompanyState.threshold` côté API. Au-delà des
  seuils listés, la clé `prestige.etoilesSuivantes` dit comment calculer les suivants.
- Les Lingots gagnés suivent la clé `prestige.conversionLingots` :

  ```
  lingots = coefficient * cbrt(donarGagnes / (seuil / diviseurReference))
  ```

  La racine cubique donne un rendement décroissant : attendre bien au-delà du seuil rapporte peu de
  plus. Comme la référence suit le seuil, les Lingots restent petits et réguliers d'une course à
  l'autre, même quand le Donar change de lettre. `PrestigePreview.lingotsIfDoubled` sert à montrer
  ce rendement au joueur.
- Chaque Lingot détenu ajoute un bonus à toute la production : clé `prestige.bonusParLingotDetenu`.
  Pour une entreprise, le bonus se calcule sur la moyenne des Lingots des membres (choix du socle, à
  confirmer, voir [socle.md](socle.md)). Dépenser ses Lingots en ville réduit donc ce bonus : c'est
  le vrai choix du joueur.
- Les Lingots sont personnels. Au prestige, le total se partage selon `company_members.share_percent`.
- Après la dernière époque, chaque prestige donne une étoile, qui multiplie toute la production :
  clé `prestige.productionParEtoile`.
- Lingots hors prestige : la mission de bienvenue de la mairie et les missions, commandes et votes,
  clé `lingotsHorsPrestige`.
- Chaque prestige s'enregistre dans `prestige_history` avec sa durée : c'est la matière du tableau
  de bord d'équilibrage.

## La ville : zones et bonus

- Chaque zone de l'île principale a un prix fixe en Lingots, connu à l'avance : clé
  `ville.zonesPrixLingots`, une entrée par zone, dans l'ordre. L'opération `buyZone` vérifie le
  niveau de mairie et le solde de l'associé qui paie.
- Les bonus des bâtiments restent petits et plafonnés. Ils jouent sur le Donar, le recrutement et la
  logistique, jamais sur les Lingots. Leurs valeurs ne sont pas encore dans `definitions/` : à
  calibrer avec le tableur avant la phase 4.

## La notation des grands nombres

- Affichage : k, M, Md, puis aa, ab, ac et ainsi de suite, deux lettres par puissance de mille :
  clé `notation.apresMd`. La feuille « Notation » du tableur donne la table complète.
- Le Donar est stocké en `DOUBLE` ; sa limite et le dernier suffixe atteignable sont dans la clé
  `notation.limiteDouble`. Une notation plus large pourra prendre le relais d'ici là.
- Format français : virgule décimale, espace entre les milliers, trois chiffres au plus avant
  l'unité. Les règles d'affichage sont dans [design/README.md](../design/README.md), section
  « Les mots », et dans [design/composants/Compteur.md](../design/composants/Compteur.md).

## Les treize cibles du tableur

La feuille « Synthèse » du tableur compare, pour un joueur type, le temps calculé à une fourchette
cible pour treize étapes : première amélioration, deuxième usine, première zone de ville, Mine
profonde, les trois prestiges, puis les six premières étoiles. Les tests dorés de silooo-core,
écrits en phase 1, rejoueront ces treize étapes ; un chiffre qui sort d'une cible fera échouer la CI. La feuille
« Paramètres » liste les réglages, dans le même ordre que `economie.json`. Le tableur portait
encore le nom UrbaFactory : c'est le même fichier.

### Ce que le moteur de la phase 1 a dû trancher

Le moteur (`core/.../economy/`) rejoue le tableur à 1e-14 près en relatif, soit l'arrondi à 15 chiffres
des JSON dorés et rien d'autre. Pour y arriver, il applique les règles suivantes, toutes **à valider par Kr**
(points 1, 2, 4, 5 et 6 de `core/src/test/resources/golden/README.md`) :

- Paliers ×2 aux niveaux 25 et 50, puis à chaque multiple de 100 (clé `niveaux.paliersX2`).
- Lingots du prestige : valeur exacte de la formule, arrondie à l'entier inférieur, sauf si elle tombe à
  moins de 1e-9 (relatif) d'un entier, auquel cas on garde cet entier (100 Lingots au seuil, pas 99).
- Le multiplicateur de la course est calculé au départ et figé jusqu'au prestige. Il compte les Lingots
  gardés tels quels, fractionnaires (le joueur type du tableur en garde la moitié).
- Époque de la course = min(course, plus grande époque de `usines.json`) ; étoiles = course moins cette
  époque, jamais négatif.
- Les seuils n'existent que pour les courses de `prestige.seuils` (9 en phase 1). Au-delà, `prestigeThreshold`
  lève une erreur au lieu d'inventer un chiffre : la clé `etoilesSuivantes` reste du texte à traduire en
  formule quand Kr aura tranché.
- Affichage : trois chiffres au plus, virgule, décimale tronquée (le tableur écrit « 195.5 M », la charte
  veut « 195 M »). Sous 1 000, entier arrondi à l'unité la plus proche.

## Les garde-fous du long terme

- Chaque entrée d'argent a une sortie en face : salaires, rachats, entretien de la ville, intérêts,
  taxe du marché, cosmétiques en Donar. L'objectif de destruction hebdomadaire est dans
  [game-design.md](game-design.md), section « Économie sur le long terme ».
- Aucune baisse rétroactive : un ajustement s'annonce une semaine avant et vise les nouveaux
  achats, jamais ce que les joueurs possèdent.
- Chaque transaction est journalisée : en cas de faille, on corrige le compte, pas le serveur.
- Les classements hebdomadaires mesurent les Lingots gagnés dans la semaine, pas la richesse.

## Le marché

L'OpenAPI prévoit des annonces entre joueurs en Lingots, taxées (`market_listings`,
`market_trades.tax`). Le plan recommande plutôt une vente au Silo à prix fixé par le jeu, sans
annonces, pour qu'aucune monnaie ne circule entre joueurs. Tant que Kr n'a pas tranché, les
opérations de marché répondent « pas fait » et aucune valeur de marché n'entre dans `definitions/`.

## Historique des réglages

Une session qui change un chiffre de `definitions/` ajoute ici une ligne : date, clé changée,
ancienne valeur, nouvelle valeur, pourquoi, et le résultat du simulateur sur les treize cibles.

| Date | Clé | Avant | Après | Pourquoi |
| --- | --- | --- | --- | --- |
| 5 nov. 2026 | `prestige.departDeCourse` (`usine`, `niveau`) | absente | `carriere`, niveau `1` | Le tableur démarre chaque course avec la Carrière au niveau 1 (Paramètres!B17), jamais à zéro : règle du jeu sans clé, dont le moteur a besoin. **À valider par Kr** (point 2 de `core/src/test/resources/golden/README.md`). Treize cibles : inchangées, le rejeu des tests dorés les retrouve. |
