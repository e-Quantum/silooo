"""Ce que ce script sait du tableur : où est chaque donnée, sous quel titre et sous quelle clé JSON.

Chaque colonne est un tuple (lettre, clé JSON, titre exact dans le tableur, genre, unité). Genre "n" :
nombre ; "t" : texte. Les titres servent de garde-fou : si le tableur change de forme, l'extraction
s'arrête au lieu de lire la mauvaise colonne (voir lecture.verifier_entetes).

Les clés JSON sont en anglais (ADR 0005) ; les noms propres du jeu restent en français (Donar,
Lingots, Carrière). Les textes d'origine du tableur ne sont jamais traduits.
"""

from __future__ import annotations

from openpyxl.utils import column_index_from_string

CHEMIN_TABLEUR = "docs/references/silooo-equilibrage-v2.xlsx"
GENERATEUR = "tools/tableur/extraire.py"

# Les paliers de production lus dans la formule de Simulation!AI : 2 ^ ((niveau >= 25) + (niveau >= 50)
# + partie entière de niveau / 100). Les contrôles de cohérence les utilisent ; --ecarts relit la
# formule pour vérifier que ces trois nombres sont toujours ceux du tableur.
PALIER_1, PALIER_2, PAS_REGULIER = 25, 50, 100

# Écart relatif admis entre une valeur du tableur et sa recomposition par les contrôles.
TOLERANCE_COHERENCE = 1e-9

# ----------------------------------------------------------------------------------------------
# Synthèse
# ----------------------------------------------------------------------------------------------

SYNTHESE = [
    ("A", "stage", "Étape", "t"),
    ("B", "computed", "Temps calculé (h)", "n", "h"),
    ("C", "targetMin", "Cible min (h)", "n", "h"),
    ("D", "targetMax", "Cible max (h)", "n", "h"),
    ("E", "verdict", "Verdict", "t"),
    ("F", "computedMinutes", "En minutes", "n", "min"),
    ("G", "displayedAmount", "Donar du joueur à ce moment", "t"),
]

JOUEUR_VOIT = [
    ("A", "stage", "Course", "t"),
    ("B", "thresholdDisplay", "Seuil de prestige", "t"),
    ("C", "incomeAtEndDisplay", "Revenu à la fin, par heure de jeu", "t"),
    ("D", "globalMultiplier", "Multiplicateur global", "n", "×"),
    ("E", "lingotsEarned", "Lingots gagnés", "n", "Lingots"),
    ("F", "endHour", "Heure de fin (cumulée)", "n", "h"),
    ("G", "realDays", "Jours réels (joueur type)", "n", "jours"),
]

# Les 13 cibles, dans l'ordre de la feuille Synthèse (lignes 5 à 17).
# (identifiant, d'où vient le temps calculé, détail)
#   "purchase"        : heure cumulée du premier achat (usine, niveau visé) de la course 1
#   "city-zone"       : heure de la première zone (feuille Ville)
#   "course-end"      : heure cumulée de la fin de la course (feuille Courses, colonne O)
#   "course-duration" : durée de la course (feuille Courses, colonne N)
CIBLES = [
    ("first-upgrade", "purchase", ("Carrière", 5)),
    ("second-factory", "purchase", ("Scierie", 1)),
    ("first-city-zone", "city-zone", 1),
    ("deep-mine", "purchase", ("Mine profonde", 1)),
    ("prestige-1", "course-end", 1),
    ("prestige-2", "course-end", 2),
    ("prestige-3", "course-end", 3),
    ("star-1", "course-duration", 4),
    ("star-2", "course-duration", 5),
    ("star-3", "course-duration", 6),
    ("star-4", "course-duration", 7),
    ("star-5", "course-duration", 8),
    ("star-6", "course-duration", 9),
]
MESURE = {
    "purchase": "cumulativeHour",
    "city-zone": "cumulativeHour",
    "course-end": "cumulativeHour",
    "course-duration": "courseDuration",
}
# Ce que la colonne G de la Synthèse contient selon la cible : malgré son titre, ce n'est pas toujours
# un solde de Donar.
SENS_DU_MONTANT = {
    "purchase": "incomeAfterPerHour",
    "city-zone": "none",
    "course-end": "prestigeThreshold",
    "course-duration": "prestigeThreshold",
}

# ----------------------------------------------------------------------------------------------
# Paramètres
# ----------------------------------------------------------------------------------------------

PARAMETRES_COLONNES = [
    ("A", "label", "Réglage", "t"),
    ("B", "value", "Valeur", "n"),
    ("C", "explanation", "Explication", "t"),
]

# (ligne, identifiant, libellé du tableur, unité). Les identifiants sont ceux que les tests dorés lisent.
REGLAGES = [
    (4, "priceGrowthPerLevel", "Croissance du prix par niveau", "×"),
    (5, "offlineCapHours", "Plafond de production hors ligne", "h"),
    (6, "offlineEfficiency", "Efficacité hors ligne", "fraction"),
    (7, "playHoursPerDay", "Heures de jeu par jour (joueur type)", "h"),
    (8, "offlineMultiplier", "Multiplicateur hors ligne", "×"),
    (9, "productionBonusPerLingotKept", "Bonus de production par Lingot gardé", "fraction"),
    (10, "lingotsKeptShare", "Part des Lingots gardés (non dépensés en ville)", "fraction"),
    (11, "lingotsConversionCoefficient", "Coefficient de conversion en Lingots", ""),
    (12, "lingotsConversionDivisor", "Diviseur de référence (seuil ÷ …)", ""),
    (13, "welcomeLingots", "Lingots de bienvenue (mission de la mairie)", "Lingots"),
    (14, "welcomeMissionHour", "Heure de la mission de bienvenue", "h"),
    (15, "missionLingotsPerPlayHour", "Lingots de missions par heure de jeu", "Lingots/h"),
    (16, "productionPerStar", "Production multipliée par chaque étoile", "×"),
    (17, "startingCarriereLevel", "Niveau de la Carrière au début d'une course", "niveau"),
]
REGLAGE_CALCULE = 8  # ligne du seul réglage qui est une formule

USINES_COLONNES = [
    ("A", "name", "Usine", "t"),
    ("B", "era", "Époque", "n"),
    ("C", "level1Price", "Prix du niveau 1 (Donar)", "n", "Donar"),
    ("D", "productionPerLevel", "Production par niveau (Donar/h, salaires déduits)", "n", "Donar/h"),
    ("E", "paybackHours", "Retour sur investissement du niveau 1 (h)", "n", "h"),
]
USINES_PREMIERE_LIGNE, USINES_DERNIERE_LIGNE = 22, 36

# ----------------------------------------------------------------------------------------------
# Courses
# ----------------------------------------------------------------------------------------------

COURSES = [
    ("A", "course", "Course", "n"),
    ("B", "endsWith", "Ce qui la termine", "t"),
    ("C", "mostAdvancedEra", "Époque la plus avancée", "n"),
    ("D", "starsAlreadyEarned", "Étoiles déjà gagnées", "n"),
    ("E", "prestigeThresholdDonar", "Seuil de prestige (Donar)", "n", "Donar"),
    ("F", "thresholdDisplay", "Seuil affiché au joueur", "t"),
    ("G", "targetMin", "Cible min (h)", "n", "h"),
    ("H", "targetMax", "Cible max (h)", "n", "h"),
    ("I", "startHour", "Heure de départ (cumulée)", "n", "h"),
    ("J", "lingotsEarnedBeforeStart", "Lingots gagnés avant le départ", "n", "Lingots"),
    ("K", "lingotsKept", "Lingots gardés", "n", "Lingots"),
    ("L", "globalMultiplier", "Multiplicateur global", "n", "×"),
    ("M", "purchasesBeforePrestige", "Achats faits avant le prestige", "n"),
    ("N", "durationHours", "Durée de la course (h)", "n", "h"),
    ("O", "endHour", "Heure de fin (cumulée)", "n", "h"),
    ("P", "conversionReferenceDonar", "Référence de conversion (Donar)", "n", "Donar"),
    ("Q", "lingotsEarnedAtPrestige", "Lingots gagnés au prestige", "n", "Lingots"),
    ("R", "valueComparedToTarget", "Valeur comparée à la cible (h)", "n", "h"),
    ("S", "verdict", "Verdict", "t"),
    ("T", "exhaustedSteps", "Paliers épuisés ?", "t"),
    ("U", "incomeAtEndDisplay", "Revenu à la fin (par h de jeu)", "t"),
]

# ----------------------------------------------------------------------------------------------
# Simulation
# ----------------------------------------------------------------------------------------------

# La colonne E (« N° usine ») est vérifiée mais pas extraite ; T:AH sont lues à part (levelsAfter).
SIMULATION = [
    ("A", "n", "N°", "n"),
    ("B", "course", "Course", "n"),
    ("C", "factory", "Usine", "t"),
    ("D", "targetLevel", "Niveau visé", "n", "niveau"),
    ("F", "newCourse", "Nouvelle course ?", "n"),
    ("G", "globalMultiplier", "Multiplicateur global", "n", "×"),
    ("H", "levelBefore", "Niveau avant", "n", "niveau"),
    ("I", "price", "Prix de l'achat (Donar)", "n", "Donar"),
    ("J", "incomeBefore", "Revenu avant (Donar/h de jeu)", "n", "Donar/h"),
    ("K", "balanceBefore", "Solde avant", "n", "Donar"),
    ("L", "waitHours", "Attente (h)", "n", "h"),
    ("M", "balanceAfter", "Solde après", "n", "Donar"),
    ("N", "donarEarnedInCourse", "Donar gagnés dans la course", "n", "Donar"),
    ("O", "courseHour", "Heure dans la course", "n", "h"),
    ("P", "cumulativeHour", "Heure cumulée", "n", "h"),
    ("Q", "beforePrestige", "Avant le prestige ?", "n"),
    ("R", "priceDisplay", "Prix affiché", "t"),
    ("S", "incomeAfterDisplay", "Revenu après, affiché", "t"),
    ("AI", "incomeAfter", "Revenu après (Donar/h de jeu)", "n", "Donar/h"),
    ("AJ", "courseStartHour", "Heure de départ de la course", "n", "h"),
    ("AK", "courseDurationIfPrestigeFollows", "Durée de la course si le prestige suit (h)", "n", "h"),
]
SIMULATION_E = ("E", None, "N° usine", "n")
SIMULATION_PREMIERE_LIGNE = 6
# Les 15 colonnes d'usines : niveau après l'achat (lignes 6 et suivantes), nom (ligne 5), production
# par niveau (ligne 4).
SIMULATION_USINES = (column_index_from_string("T"), column_index_from_string("AH"))

# ----------------------------------------------------------------------------------------------
# Notation et Ville
# ----------------------------------------------------------------------------------------------

NOTATION = [
    ("A", "power", "Puissance de 1 000", "n"),
    ("B", "suffix", "Suffixe", "t"),
    ("C", "value", "Vaut", "n"),
]

VILLE_ZONES = [
    ("A", "zone", "Zone", "n"),
    ("B", "priceLingots", "Prix (Lingots)", "n", "Lingots"),
    ("C", "cumulativePriceLingots", "Prix cumulé", "n", "Lingots"),
    ("D", "affordableInCourse", "Course où elle devient abordable", "n"),
    ("E", "affordableAtHour", "Heure de jeu (cumulée)", "n", "h"),
    ("F", "realDays", "Jours réels (au rythme du joueur type)", "n", "jours"),
]
VILLE_BUDGETS = [
    ("H", "course", "Course", "n"),
    ("I", "endHour", "Fin (h)", "n", "h"),
    ("J", "cumulativePrestigeLingots", "Lingots de prestige cumulés", "n", "Lingots"),
    ("K", "cityBudgetJustAfter", "Budget ville juste après", "n", "Lingots"),
]
