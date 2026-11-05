# Données dorées du tableur d'équilibrage

Ces six fichiers JSON sont une copie lisible par les tests du tableur
[silooo-equilibrage-v2.xlsx](../../../../../docs/references/silooo-equilibrage-v2.xlsx). Le tableur simule
un joueur type, du premier Donar à la 6e étoile, et vérifie treize cibles de rythme. Les tests dorés de
silooo-core rejouent ce joueur avec les chiffres de [definitions/](../../../../../definitions/) et comparent
leur résultat à ces fichiers.

Le tableur reste la référence. Les JSON sont générés par
[tools/tableur/extraire.py](../../../../../tools/tableur/extraire.py) : on ne les édite jamais à la main. Ce
dossier est le seul endroit de la documentation où les chiffres du jeu sont recopiés, pour que le lecteur
d'un test voie d'où vient une valeur.

## Origine et régénération

- Tableur : `docs/references/silooo-equilibrage-v2.xlsx`, 7 feuilles (Lisez-moi, Synthèse, Paramètres,
  Courses, Simulation, Notation, Ville). Seule Lisez-moi n'est pas extraite : c'est du texte
  d'explication.
- SHA-256 du tableur d'où viennent les fichiers (aussi écrit dans l'entrée `source` de chaque JSON) :
  `553035cc854e45b506f062e633dc051068621ab1fa0178009bf107e8068e4652`.
- Lecture : openpyxl en `data_only`. On obtient les valeurs que Excel a gardées en cache, les formules ne
  sont pas recalculées. Le tableur n'est jamais modifié.

Commandes, depuis n'importe quel dossier (Python 3.10 ou plus ; `uv` apporte openpyxl à la volée) :

```
uv run --quiet --with openpyxl python -I tools/tableur/extraire.py             # écrit les six JSON
uv run --quiet --with openpyxl python -I tools/tableur/extraire.py --verifier  # n'écrit rien, sort en 1 si un JSON est périmé
uv run --quiet --with openpyxl python -I tools/tableur/extraire.py --ecarts    # n'écrit rien, compare le tableur à definitions/
```

- Sortie déterministe : clés triées, indentation 2, UTF-8 sans échappement, fin de ligne LF, nombres tels
  que le tableur les donne, sans arrondi. Aucune date, aucun nom de machine. Deux lancements de suite
  donnent les mêmes octets.
- Garde-fous : le script compare les titres de colonnes du tableur à ceux qu'il connaît, refuse une
  cellule vide (cache absent), et recompose prix, revenus, verdicts et liens entre feuilles. Si le
  tableur n'est pas cohérent avec lui-même, il s'arrête sans rien écrire. Dans ce cas, rouvre le
  tableur dans Excel, enregistre, relance.
- Quand régénérer : dès que le tableur change. Relis `git diff` de ce dossier, et si un chiffre bouge,
  c'est une décision de Kr, pas une correction.
- Clés JSON en anglais (ADR [0005](../../../../../docs/adr/0005-langues-du-code.md)). Les libellés, les
  textes et les noms propres du jeu (Donar, Lingots, Carrière) gardent leur forme d'origine.

## Ce que le tableur modélise

Le détail est dans les formules du tableur ; voici ce qu'il faut pour lire les fichiers.

- Prix : passer du niveau `n` au niveau `n + k` d'une usine coûte
  `prixNiveau1 × g^n × (g^k - 1) / (g - 1)`, avec `g` la croissance du prix par niveau.
- Production d'une usine au niveau `n` : `productionParNiveau × n × 2^p`, où `p` vaut 1 de plus à 25, à
  50, puis à chaque multiple de 100 (`p = (n >= 25) + (n >= 50) + partie entière de n / 100`).
- Revenu par heure de jeu : somme des productions des usines, fois le multiplicateur global, fois le
  multiplicateur hors ligne. Ce dernier vaut `1 + MIN(plafond ; 24 - heures de jeu par jour) × efficacité
  / heures de jeu par jour` : le joueur type joue 1,5 h par jour, et ses heures hors ligne rapportent à
  30 %.
- Multiplicateur global d'une course : `(1 + bonus par Lingot × part gardée × Lingots cumulés) × 300^étoiles`,
  figé pendant toute la course. Les Lingots cumulés sont les Lingots de bienvenue, plus 1 Lingot par heure
  de jeu écoulée à l'heure de départ de la course, plus les Lingots de chaque prestige précédent.
- Une course commence à zéro Donar, avec la Carrière au niveau 1 et les autres usines à zéro. Le joueur
  achète dans l'ordre fixé par le tableur (les colonnes C et D de Simulation sont saisies à la main), en
  attendant à chaque fois d'avoir le prix. Il prestige exactement au seuil de la course, donc il gagne
  toujours 100 Lingots (`10 × racine cubique de (seuil / (seuil / 1000))`).
- Avant une course, la moitié des Lingots cumulés compte dans le multiplicateur (réglage « part gardée »),
  l'autre moitié forme le budget de la ville.

## Les six fichiers

Chaque JSON commence par `source` (fichier, générateur, SHA-256, feuilles lues, avertissement) et
`columns` (clé, lettre de colonne, libellé d'origine, unité). Ce qui suit résume ces entrées.

| Fichier | Feuilles | Contenu | Taille |
| --- | --- | --- | --- |
| `synthese.json` | Synthèse | les 13 cibles, ce que voit le joueur à chaque course, le verdict global | 11 Ko |
| `simulation.json` | Simulation | les 965 achats du joueur type, un par ligne | 1 Mo |
| `parametres.json` | Paramètres | les 14 réglages et les 15 usines | 9 Ko |
| `courses.json` | Courses | les 9 courses, une par ligne | 9 Ko |
| `notation.json` | Notation | la table des suffixes (k, M, Md, aa, ab, jusqu'à du) | 9 Ko |
| `ville.json` | Ville | les 16 zones et le budget ville après chaque course | 6 Ko |

### synthese.json

Clés de premier niveau : `source`, `columns`, `verdictRule`, `overallVerdict` (Synthèse!E18, « 13 étapes
sur 13 dans la cible »), `targets` (13 entrées), `playerSees` (9 entrées).

Entrée de `targets` (Synthèse!A5:G17) :

| Clé | Colonne | Libellé d'origine | Sens |
| --- | --- | --- | --- |
| `stage` | A | Étape | nom de l'étape |
| `computed` | B | Temps calculé (h) | heure cumulée ou durée de course, selon `measure` |
| `targetMin`, `targetMax` | C, D | Cible min (h), Cible max (h) | la tolérance du tableur |
| `verdict` | E | Verdict | Trop court, Dans la cible ou Trop long |
| `computedMinutes` | F | En minutes | `computed` fois 60 |
| `displayedAmount` | G | Donar du joueur à ce moment | texte affiché, voir `displayedAmountMeaning` |
| `id`, `order`, `sheetRow` | | ajoutés par le script | identifiant stable, rang 1 à 13, ligne de la feuille |
| `measure` | | ajouté | `cumulativeHour` (heure depuis le tout début) ou `courseDuration` |
| `displayedAmountMeaning` | | ajouté | `incomeAfterPerHour`, `prestigeThreshold` ou `none` |
| `link` | | ajouté | d'où vient `computed` : `simulationPurchase` (n° d'achat), `cityZone` ou `course` |

Le titre de la colonne G est trompeur : ce n'est pas un solde de Donar. Pour les cibles 1, 2 et 4, c'est
le revenu par heure après l'achat ; pour 5 à 13, le seuil de prestige de la course ; pour la cible 3, un
tiret cadratin seul, c'est-à-dire rien.

Entrée de `playerSees` (Synthèse!A22:G30) : `course` (rang ajouté), `stage`, `thresholdDisplay` (seuil de
prestige), `incomeAtEndDisplay` (revenu à la fin, par heure de jeu), `globalMultiplier`, `lingotsEarned`,
`endHour` (heure de fin cumulée), `realDays` (jours réels du joueur type).

### simulation.json

Clés : `source`, `columns`, `factories` (les 15 noms d'usines, dans l'ordre de Paramètres),
`productionPerLevel` (nom d'usine vers production par niveau, Simulation!T4:AH4), `purchases` (965
achats, Simulation!A6:AK970).

| Clé | Colonne | Sens |
| --- | --- | --- |
| `n` | A | numéro d'achat, de 1 à 965 |
| `course` | B | numéro de course, de 1 à 9 |
| `factory` | C | usine achetée (donnée saisie dans le tableur) |
| `targetLevel` | D | niveau visé après l'achat (donnée saisie) |
| `newCourse` | F | 1 pour le premier achat d'une course, sinon 0 |
| `globalMultiplier` | G | multiplicateur global de la course |
| `levelBefore` | H | niveau de l'usine avant l'achat |
| `price` | I | prix pour passer de `levelBefore` à `targetLevel`, en Donar |
| `incomeBefore` | J | revenu par heure de jeu avant l'achat, en Donar/h |
| `balanceBefore` | K | solde avant l'achat : 0, le joueur attend d'avoir le prix (sauf un résidu de calcul de -1 Donar à l'achat n° 551) |
| `waitHours` | L | attente avant l'achat : `max(0, (price - balanceBefore) / incomeBefore)` |
| `balanceAfter` | M | solde après l'achat : 0, sauf le résidu de -1 Donar de l'achat n° 550 |
| `donarEarnedInCourse` | N | Donar gagnés depuis le début de la course, attente de cet achat comprise |
| `courseHour` | O | heure dans la course à laquelle l'achat a lieu |
| `cumulativeHour` | P | heure depuis le tout début de la partie |
| `beforePrestige` | Q | 1 si `donarEarnedInCourse` est sous le seuil de la course : l'achat est fait |
| `priceDisplay`, `incomeAfterDisplay` | R, S | prix et revenu après l'achat, tels qu'affichés au joueur |
| `incomeAfter` | AI | revenu par heure de jeu après l'achat, en Donar/h |
| `courseStartHour` | AJ | heure cumulée du départ de la course |
| `courseDurationIfPrestigeFollows` | AK | durée de la course si le prestige suit cet achat, en h |
| `levelsAfter` | T:AH | niveau de chaque usine après l'achat, usines à zéro omises |

La colonne E (rang de l'usine dans Paramètres) n'est pas extraite : elle se déduit de `factory`.

Les lignes avec `beforePrestige = 1` sont les achats réellement faits : 956 sur les 9 courses (21, 34, 64,
81, 108, 139, 155, 169 et 185). La dernière ligne de chaque course a `beforePrestige = 0` : c'est l'achat
que le joueur n'a pas le temps de faire, car le seuil est atteint avant. Elle sert de borne et ne se
rejoue pas. La durée d'une course est `courseDurationIfPrestigeFollows` du dernier achat fait (c'est
Courses!N).

### parametres.json

Clés : `source`, `columns`, `settings`, `factories`, `note` (la remarque de Paramètres!A38).

`settings` : un objet par réglage, indexé par un identifiant anglais. Chaque entrée porte `cell`, `label`
(libellé d'origine), `value`, `unit`, `displayFormat` (format de cellule d'Excel), `explanation` (la
colonne C du tableur) et `calculated` (vrai pour B8, le seul réglage qui est une formule).

| Identifiant | Cellule | Valeur | Unité | Libellé d'origine |
| --- | --- | --- | --- | --- |
| `priceGrowthPerLevel` | B4 | 1,11 | × | Croissance du prix par niveau |
| `offlineCapHours` | B5 | 8 | h | Plafond de production hors ligne |
| `offlineEfficiency` | B6 | 0,3 | fraction | Efficacité hors ligne |
| `playHoursPerDay` | B7 | 1,5 | h | Heures de jeu par jour (joueur type) |
| `offlineMultiplier` | B8 | 2,6 | × | Multiplicateur hors ligne (calculé) |
| `productionBonusPerLingotKept` | B9 | 0,01 | fraction | Bonus de production par Lingot gardé |
| `lingotsKeptShare` | B10 | 0,5 | fraction | Part des Lingots gardés (non dépensés en ville) |
| `lingotsConversionCoefficient` | B11 | 10 | | Coefficient de conversion en Lingots |
| `lingotsConversionDivisor` | B12 | 1000 | | Diviseur de référence |
| `welcomeLingots` | B13 | 10 | Lingots | Lingots de bienvenue (mission de la mairie) |
| `welcomeMissionHour` | B14 | 1 | h | Heure de la mission de bienvenue |
| `missionLingotsPerPlayHour` | B15 | 1 | Lingots/h | Lingots de missions par heure de jeu |
| `productionPerStar` | B16 | 300 | × | Production multipliée par chaque étoile |
| `startingCarriereLevel` | B17 | 1 | niveau | Niveau de la Carrière au début d'une course |

`factories` : 15 entrées (Paramètres!A22:E36) avec `row` (ligne de la feuille), `name` (Usine), `era`
(Époque, de 1 à 4), `level1Price` (Prix du niveau 1, en Donar), `productionPerLevel` (Production par
niveau, en Donar/h, salaires déduits) et `paybackHours` (Retour sur investissement du niveau 1, en h).
Il y a 4 usines en époque 1, 4 en époque 2, 5 en époque 3 et 2 en époque 4.

### courses.json

Clés : `source`, `columns`, `courses` (9 entrées, Courses!A5:U13). Colonnes : `course` (A),
`endsWith` (B), `mostAdvancedEra` (C), `starsAlreadyEarned` (D, l'exposant de 300 dans le multiplicateur),
`prestigeThresholdDonar` (E), `thresholdDisplay` (F), `targetMin` et `targetMax` (G, H), `startHour` (I),
`lingotsEarnedBeforeStart` (J), `lingotsKept` (K), `globalMultiplier` (L), `purchasesBeforePrestige`
(M), `durationHours` (N), `endHour` (O), `conversionReferenceDonar` (P), `lingotsEarnedAtPrestige` (Q),
`valueComparedToTarget` (R), `verdict` (S), `exhaustedSteps` (T), `incomeAtEndDisplay` (U).

Pour la lecture, avec des valeurs arrondies (les exactes sont dans le fichier) :

| Course | Elle se termine par | Seuil | Départ | Lingots avant | Multiplicateur | Durée | Fin | Achats | Cible |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1er prestige, époque 2 | 1 Md | 0 h | 10 | 1,05 | 14,70 h | 14,70 h | 21 | 10 à 15 h (fin) |
| 2 | 2e prestige, époque 3 | 500 Md | 14,70 h | 124,70 | 1,62 | 22,81 h | 37,51 h | 34 | 30 à 40 h (fin) |
| 3 | 3e prestige, époque 4 | 1 ab | 37,51 h | 247,51 | 2,24 | 44,83 h | 82,34 h | 64 | 70 à 100 h (fin) |
| 4 | 1re étoile | 200 ab | 82,34 h | 392,34 | 2,96 | 22,20 h | 104,54 h | 81 | 20 à 30 h (durée) |
| 5 | 2e étoile | 500 ac | 104,54 h | 514,54 | 1072 | 20,98 h | 125,52 h | 108 | 20 à 30 h (durée) |
| 6 | 3e étoile | 500 ad | 125,52 h | 635,52 | 3,76e5 | 20,17 h | 145,69 h | 139 | 20 à 30 h (durée) |
| 7 | 4e étoile | 300 ae | 145,69 h | 755,69 | 1,29e8 | 20,88 h | 166,57 h | 155 | 20 à 30 h (durée) |
| 8 | 5e étoile | 300 af | 166,57 h | 876,57 | 4,36e10 | 25,98 h | 192,55 h | 169 | 20 à 30 h (durée) |
| 9 | 6e étoile | 100 ag | 192,55 h | 1002,55 | 1,46e13 | 21,58 h | 214,12 h | 185 | 20 à 30 h (durée) |

### notation.json

Clés : `source`, `columns`, `note` (Notation!E2), `suffixes` (103 entrées, Notation!A2:C104) avec `power`
(la puissance de 1000, de 0 à 102), `suffix` (vide pour la puissance 0, puis k, M, Md, aa, ab, ac
et ainsi de suite jusqu'à du) et `value` (la valeur, `1e(3 × power)`).

### ville.json

Clés : `source`, `columns`, `zones`, `courseBudgets`.

`zones` (Ville!A5:F20, 16 entrées) : `zone` (A), `priceLingots` (B), `cumulativePriceLingots` (C),
`affordableInCourse` (D, la course où le budget ville la paie), `affordableAtHour` (E, l'heure cumulée),
`realDays` (F). Les prix vont de 5 à 235 Lingots, 1400 au cumul. La zone 1 est payée à 1 h, les zones 2 à
4 à 14,70 h, les zones 5 et 6 à 37,51 h, la zone 7 à 82,34 h, la zone 8 à 104,54 h, la zone 9 à 125,52 h,
la zone 10 à 166,57 h, les zones 11 à 16 entre 200 et 511,53 h.

`courseBudgets` (Ville!H5:K13, 9 entrées) : `course`, `endHour`, `cumulativePrestigeLingots` (100 de plus à
chaque course) et `cityBudgetJustAfter` (le budget ville juste après le prestige, en Lingots).

## Les 13 cibles

Chaque cible est une fourchette `[targetMin, targetMax]` en heures, bornes comprises : c'est la tolérance
du tableur. `computed` en est la valeur calculée par le tableur. Un test doré vérifie deux choses : que
son rejeu retrouve `computed` à 1e-9 près en relatif, et que cette valeur tombe dans la fourchette.

| N° | Identifiant | Étape (libellé d'origine) | Ce que mesure `computed` | Fourchette | Valeur |
| --- | --- | --- | --- | --- | --- |
| 1 | `first-upgrade` | 1re amélioration (Carrière niveau 5) | heure cumulée de l'achat n° 1 de Simulation | 0 à 0,083 h | 0,080 h |
| 2 | `second-factory` | 2e usine (Scierie) | heure cumulée de l'achat n° 4 (Scierie niveau 1) | 0,25 à 0,333 h | 0,282 h |
| 3 | `first-city-zone` | 1re zone de ville | heure où le budget ville paie la zone 1 (Ville!E5) | 0,75 à 1,5 h | 1 h |
| 4 | `deep-mine` | Mine profonde | heure cumulée de l'achat n° 12 (Mine profonde niveau 1) | 2 à 3 h | 2,017 h |
| 5 | `prestige-1` | 1er prestige : époque 2 (vapeur) | heure cumulée de fin de la course 1 (Courses!O5) | 10 à 15 h | 14,701 h |
| 6 | `prestige-2` | 2e prestige : époque 3 (moderne) | heure cumulée de fin de la course 2 | 30 à 40 h | 37,508 h |
| 7 | `prestige-3` | 3e prestige : époque 4 (futuriste) | heure cumulée de fin de la course 3 | 70 à 100 h | 82,338 h |
| 8 | `star-1` | 1re étoile (durée de la course en époque 4) | durée de la course 4 (Courses!N8) | 20 à 30 h | 22,204 h |
| 9 | `star-2` | 2e étoile (durée de la course) | durée de la course 5 | 20 à 30 h | 20,981 h |
| 10 | `star-3` | 3e étoile (durée de la course) | durée de la course 6 | 20 à 30 h | 20,168 h |
| 11 | `star-4` | 4e étoile (durée de la course) | durée de la course 7 | 20 à 30 h | 20,880 h |
| 12 | `star-5` | 5e étoile (durée de la course) | durée de la course 8 | 20 à 30 h | 25,977 h |
| 13 | `star-6` | 6e étoile (durée de la course) | durée de la course 9 | 20 à 30 h | 21,575 h |

Les valeurs sont arrondies à trois décimales pour la lecture ; `synthese.json` garde les valeurs exactes.
Les treize cibles sont dans la fourchette (« 13 étapes sur 13 dans la cible »). La première est la plus
serrée : 4,79 minutes pour une borne à 5.

## Rejouer le joueur type

Pour chaque ligne de `purchases` dans l'ordre, un test doré refait le calcul à partir de `definitions/`
et de `parametres.json`, puis compare :

1. le prix : `price` contre la formule de prix, de `levelBefore` à `targetLevel` ;
2. le revenu : `incomeAfter` contre la somme des productions de `levelsAfter`, fois `globalMultiplier`,
   fois le multiplicateur hors ligne ;
3. l'heure : `waitHours`, `courseHour` et `cumulativeHour` ;
4. à chaque fin de course, la durée, l'heure de fin et les 100 Lingots gagnés ;
5. pour chacune des 13 cibles, la fourchette.

L'ordre des achats (`factory`, `targetLevel`) est une donnée du tableur : le test le lit, il ne le décide
pas. Une valeur du tableur qui n'a pas de clé dans `definitions/` (voir plus bas) se lit dans
`parametres.json`, comme constante du joueur type et non comme règle du jeu.

Un rejeu indépendant, écrit à part et jeté après usage, a retrouvé les 965 lignes à 5e-15 près en relatif
avec les valeurs de `definitions/` et le modèle ci-dessus. L'extraction et `definitions/` disent donc la
même chose. Une tolérance de 1e-9 en relatif laisse une grande marge au moteur Kotlin.

## Écarts relevés

Comparaison du tableur (`--ecarts` la refait à chaque lancement) aux clés de
[economie.json](../../../../../definitions/economie.json) et
[usines.json](../../../../../definitions/usines.json). Résultat : 84 valeurs en accord, aucune en écart,
6 réglages du tableur sans clé, 4 clés sans équivalent dans le tableur. Rien dans `definitions/` n'a été
modifié : les points ci-dessous sont à trancher par Kr.

### Ce qui correspond

| Clé de `definitions/` | Paramètre du tableur | Valeur | État |
| --- | --- | --- | --- |
| `economie.niveaux.croissancePrix` | Paramètres!B4 | 1,11 | accord |
| `economie.niveaux.paliersX2.fixes` | formule de Simulation!AI (niveau >= 25, >= 50) | 25 et 50 | accord |
| `economie.niveaux.paliersX2.ensuiteTous` | formule de Simulation!AI (partie entière de niveau / 100) | 100 | accord sur le nombre, lecture à confirmer (point 1) |
| `economie.horsLigne.plafondHeures` | Paramètres!B5 | 8 | accord |
| `economie.horsLigne.efficacite` | Paramètres!B6 | 0,3 | accord |
| `economie.prestige.seuils[0 à 8].donar` | Courses!E5:E13 | 1e9, 5e11, 1e15, 2e17, 5e20, 5e23, 3e26, 3e29, 1e32 | accord, 9 sur 9 |
| `economie.prestige.conversionLingots.coefficient` | Paramètres!B11 | 10 | accord |
| `economie.prestige.conversionLingots.diviseurReference` | Paramètres!B12 | 1000 | accord |
| `economie.prestige.conversionLingots.formule` | Courses!Q5 (racine cubique) | | accord |
| `economie.prestige.bonusParLingotDetenu` | Paramètres!B9 | 0,01 | accord |
| `economie.prestige.productionParEtoile` | Paramètres!B16 | 300 | accord |
| `economie.lingotsHorsPrestige.missionBienvenue` | Paramètres!B13 | 10 | accord |
| `economie.lingotsHorsPrestige.moyenneParHeureDeJeu` | Paramètres!B15 | 1 | accord |
| `economie.ville.zonesPrixLingots` | Ville!B5:B20 | 16 prix, de 5 à 235 | accord |
| `usines.usines[i].nom` | Paramètres!A22:A36 | 15 usines | accord, 15 sur 15 |
| `usines.usines[i].epoque` | Paramètres!B22:B36 | | accord, 15 sur 15 |
| `usines.usines[i].prixNiveau1` | Paramètres!C22:C36 | | accord, 15 sur 15 |
| `usines.usines[i].productionParNiveau` | Paramètres!D22:D36 | | accord, 15 sur 15 |

Les paliers de production, le prix du niveau 1 et la production de chaque usine sont donc ceux du tableur.
Le multiplicateur de prestige, le plafond hors ligne et le prix des zones aussi.

### Réglages du tableur sans clé dans `definitions/`

| Cellule | Valeur | Rôle dans le tableur | À faire |
| --- | --- | --- | --- |
| B7 | 1,5 h | heures de jeu par jour du joueur type ; sert aux jours réels et au multiplicateur hors ligne | hypothèse du joueur type, pas une règle du jeu : constante de test |
| B8 | 2,6 | multiplicateur hors ligne, calculé depuis B5, B6, B7 | idem ; le moteur le recompose, il ne le lit pas comme règle |
| B10 | 0,5 | part des Lingots comptée dans le multiplicateur ; l'autre moitié est le budget ville | hypothèse sur la dépense en ville ; à garder comme constante de test |
| B14 | 1 h | heure plancher des zones de ville : la mission de bienvenue paie la zone 1 | idem |
| B17 | 1 | niveau de la Carrière au début d'une course | règle du jeu sans clé : à ajouter dans `definitions/` si Kr la confirme (point 2) |
| Courses!C, D | une valeur par course | époque la plus avancée et étoiles déjà gagnées | se déduisent du rang de la course ; D sert d'exposant de 300 |

### Clés de `definitions/` que le tableur ne porte pas

- `niveaux.formulePrix` : texte de la formule. Le tableur la calcule, elle est identique.
- `prestige.etoilesSuivantes` : seuils au-delà de la course 9, « seuil précédent fois 1000 ». Le tableur
  s'arrête à la 6e étoile (point 5).
- `usines[*].decouverte`, `entrees`, `produit`, `electricite` : conditions de découverte et chaînes de
  production. Le tableur ignore tout cela (point 3).
- `notation.apresMd`, `notation.limiteDouble` : textes. La feuille Notation les confirme (Md, puis aa, ab,
  jusqu'à du pour 1e306).

### Points d'interprétation, à trancher par Kr

1. **Les paliers « tous les 100 niveaux ».** `definitions/` dit que la production double à 25, 50, puis
   tous les 100 niveaux. Le tableur double à chaque multiple de 100 : aux niveaux 100, 200, 300 et 400.
   Une lecture « tous les 100 niveaux à partir de 50 » donnerait 150, 250, 350 et d'autres revenus. Le moteur doit suivre le
   tableur tant que Kr ne dit pas autrement, et le commentaire de la clé gagnerait à le dire.
2. **Carrière au niveau 1 à chaque course.** Le tableur démarre chaque course avec la Carrière au
   niveau 1 (B17), jamais à zéro. C'est une règle du jeu qui n'a pas de clé. Les premiers achats
   (Carrière niveau 5, puis 10, puis 25) en dépendent.
3. **Usines à découvrir et chaînes de production.** Le tableur achète les usines dans l'ordre qu'on lui
   a saisi, sans condition. Il ne modélise ni la découverte, ni les entrées et produits, ni
   l'électricité. Si ces conditions retardent un achat dans le jeu, les temps réels seront plus longs
   que ceux du tableur : ses temps sont des temps au plus court.
4. **Multiplicateur figé, Lingots fractionnaires.** Le multiplicateur global est constant sur toute la
   course et compte les 10 Lingots de bienvenue dès l'heure 0, alors que la mission les verse à l'heure
   B14 (1 h). Les Lingots de missions (1 par heure de jeu) sont comptés avec leurs fractions, alors que
   les Lingots du jeu sont des entiers (BIGINT, jamais arrondis). Aucun texte du dépôt ne dit comment
   arrondir un gain de Lingots ni une heure de jeu : Kr doit fixer la règle.
5. **Étoiles au-delà de la 6e.** `etoilesSuivantes` dit « fois 1000 », mais les seuils du tableur se
   suivent, d'une étoile à la suivante, avec des rapports de 2500, 1000, 600, 1000 et 333. Le texte
   est un ordre de grandeur et le tableur n'en dit rien de plus.
6. **Affichage des nombres.** Le tableur écrit « 32.7 k » (point décimal, une seule décimale, tronquée). La charte [design/README.md](../../../../../design/README.md) veut la virgule
   décimale et une espace fine entre milliers. Les textes `priceDisplay`, `incomeAfterDisplay`,
   `thresholdDisplay` et `displayedAmount` servent de repère, pas de format à copier.
7. **« Première vente en 2 minutes ».** [game-design.md](../../../../../docs/game-design.md) fixe cette
   cible. Le tableur n'a pas de ligne pour elle ; la plus proche est `first-upgrade`, à moins de 5
   minutes. La première vente n'est donc pas couverte par ces fichiers.
8. **Mots de `docs/game-design.md`.** Le texte cite le tableur sous le nom « UrbaFactory-equilibrage-v2.xlsx »
   (le fichier du dépôt s'appelle silooo-equilibrage-v2.xlsx) et donne « par exemple ×1,6 par niveau »
   pour le prix, alors que la clé vaut 1,11. Le paragraphe « Comment on tient ces cibles » (Scierie à 17
   minutes, Mine profonde à 2 heures, prestiges à 15, 37 et 82 heures, environ 215 heures au total,
   passage de 1,11 à 1,12 qui rallonge le 1er prestige de 15 à 17 heures) correspond aux fichiers d'ici.

## À valider par Kr

- Les huit points d'interprétation ci-dessus, en particulier le 1 (paliers), le 2 (Carrière au niveau 1)
  et le 4 (arrondi des Lingots), qui changent des chiffres du moteur.
- Que les 13 cibles et leurs fourchettes (colonnes C et D de Synthèse) sont bien celles voulues : le
  script les lit, il ne les juge pas.
- Rien ici n'est un texte ou un visuel vu par les joueurs. Aucun fichier de `definitions/` n'est touché.
