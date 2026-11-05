"""--ecarts : compare le tableur aux clés de definitions/economie.json et definitions/usines.json.

Ne modifie rien. Quatre statuts :
  ACCORD         la clé de definitions/ et le réglage du tableur ont la même valeur
  ECART          les deux existent et diffèrent : c'est Kr qui tranche
  SANS CLE       un réglage du tableur n'a pas de clé dans definitions/
  SANS TABLEUR   une clé de definitions/ que le tableur ne porte pas
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

import openpyxl

from lecture import (
    est_nombre,
    extraire_courses,
    extraire_parametres,
    extraire_ville,
    feuille,
    nfc,
    proche,
    reglage,
)


def lire_paliers_formule(chemin_tableur: Path) -> tuple[int, int, int] | None:
    """Les trois nombres de la formule de Simulation!AI6 : 2^((n>=25)+(n>=50)+INT(n/100))."""
    classeur = openpyxl.load_workbook(chemin_tableur, data_only=False)
    formule = feuille(classeur, "Simulation")["AI6"].value
    trouve = re.search(r"2\^\(\(\S+?>=(\d+)\)\+\(\S+?>=(\d+)\)\+INT\(\S+?/(\d+)\)\)", str(formule))
    if trouve is None:
        return None
    a, b, pas = (int(x) for x in trouve.groups())
    return a, b, pas


def formule_racine_cubique(chemin_tableur: Path) -> bool:
    """Vrai si Courses!Q5 (Lingots gagnés) est bien une racine cubique : la formule contient ^(1/3)."""
    classeur = openpyxl.load_workbook(chemin_tableur, data_only=False)
    return "^(1/3)" in str(feuille(classeur, "Courses")["Q5"].value).replace(" ", "")


def comparer(chemin_tableur: Path, dossier_definitions: Path) -> list[tuple[str, str, object, object]]:
    """Retourne des tuples (statut, sujet, valeur dans definitions/, valeur dans le tableur)."""
    somme = hashlib.sha256(chemin_tableur.read_bytes()).hexdigest()
    classeur = openpyxl.load_workbook(chemin_tableur, data_only=True)
    parametres = extraire_parametres(classeur, somme)
    courses = extraire_courses(classeur, somme)["courses"]
    zones = extraire_ville(classeur, somme)["zones"]
    eco = json.loads((dossier_definitions / "economie.json").read_text(encoding="utf-8"))
    usi = json.loads((dossier_definitions / "usines.json").read_text(encoding="utf-8"))
    sortie: list[tuple[str, str, object, object]] = []

    def ligne(sujet: str, definition, tableur) -> None:
        if est_nombre(definition) and est_nombre(tableur):
            egal = proche(definition, tableur)
        elif isinstance(definition, str) and isinstance(tableur, str):
            egal = nfc(definition) == nfc(tableur)
        else:
            egal = definition == tableur
        sortie.append(("ACCORD" if egal else "ECART", sujet, definition, tableur))

    def sans_cle(sujet: str, tableur) -> None:
        sortie.append(("SANS CLE", sujet, None, tableur))

    def sans_tableur(sujet: str, definition) -> None:
        sortie.append(("SANS TABLEUR", sujet, definition, None))

    ligne("niveaux.croissancePrix / Paramètres!B4", eco["niveaux"]["croissancePrix"], reglage(parametres, "priceGrowthPerLevel"))
    paliers = lire_paliers_formule(chemin_tableur)
    if paliers is None:
        sortie.append(("ECART", "niveaux.paliersX2 / formule de Simulation!AI6 non reconnue", eco["niveaux"]["paliersX2"], None))
    else:
        ligne("niveaux.paliersX2.fixes / formule de Simulation!AI6", eco["niveaux"]["paliersX2"]["fixes"], list(paliers[:2]))
        ligne("niveaux.paliersX2.ensuiteTous / formule de Simulation!AI6", eco["niveaux"]["paliersX2"]["ensuiteTous"], paliers[2])
    ligne("horsLigne.plafondHeures / Paramètres!B5", eco["horsLigne"]["plafondHeures"], reglage(parametres, "offlineCapHours"))
    ligne("horsLigne.efficacite / Paramètres!B6", eco["horsLigne"]["efficacite"], reglage(parametres, "offlineEfficiency"))
    seuils = [s["donar"] for s in eco["prestige"]["seuils"]]
    ligne("prestige.seuils, nombre de courses / Courses!A5:A13", len(seuils), len(courses))
    for definition, course in zip(seuils, courses):
        ligne(
            f"prestige.seuils, course {course['course']} / Courses!E{4 + course['course']}",
            definition,
            course["prestigeThresholdDonar"],
        )
    conversion = eco["prestige"]["conversionLingots"]
    ligne("prestige.conversionLingots.coefficient / Paramètres!B11", conversion["coefficient"], reglage(parametres, "lingotsConversionCoefficient"))
    ligne("prestige.conversionLingots.diviseurReference / Paramètres!B12", conversion["diviseurReference"], reglage(parametres, "lingotsConversionDivisor"))
    ligne("prestige.conversionLingots.formule, racine cubique / Courses!Q5", True, formule_racine_cubique(chemin_tableur))
    ligne("prestige.bonusParLingotDetenu / Paramètres!B9", eco["prestige"]["bonusParLingotDetenu"], reglage(parametres, "productionBonusPerLingotKept"))
    ligne("prestige.productionParEtoile / Paramètres!B16", eco["prestige"]["productionParEtoile"], reglage(parametres, "productionPerStar"))
    ligne("lingotsHorsPrestige.missionBienvenue / Paramètres!B13", eco["lingotsHorsPrestige"]["missionBienvenue"], reglage(parametres, "welcomeLingots"))
    ligne("lingotsHorsPrestige.moyenneParHeureDeJeu / Paramètres!B15", eco["lingotsHorsPrestige"]["moyenneParHeureDeJeu"], reglage(parametres, "missionLingotsPerPlayHour"))
    ligne("ville.zonesPrixLingots / Ville!B5:B20", eco["ville"]["zonesPrixLingots"], [z["priceLingots"] for z in zones])
    ligne("usines, nombre / Paramètres!A22:A36", len(usi["usines"]), len(parametres["factories"]))
    for definition, usine in zip(usi["usines"], parametres["factories"]):
        identifiant, rang = definition["id"], usine["row"]
        ligne(f"usines[{identifiant}].nom / Paramètres!A{rang}", definition["nom"], usine["name"])
        ligne(f"usines[{identifiant}].epoque / Paramètres!B{rang}", definition["epoque"], usine["era"])
        ligne(f"usines[{identifiant}].prixNiveau1 / Paramètres!C{rang}", definition["prixNiveau1"], usine["level1Price"])
        ligne(f"usines[{identifiant}].productionParNiveau / Paramètres!D{rang}", definition["productionParNiveau"], usine["productionPerLevel"])

    sans_cle("Paramètres!B7 heures de jeu par jour du joueur type", reglage(parametres, "playHoursPerDay"))
    sans_cle("Paramètres!B8 multiplicateur hors ligne (calculé)", reglage(parametres, "offlineMultiplier"))
    sans_cle("Paramètres!B10 part des Lingots gardés", reglage(parametres, "lingotsKeptShare"))
    sans_cle("Paramètres!B14 heure de la mission de bienvenue", reglage(parametres, "welcomeMissionHour"))
    sans_cle("Paramètres!B17 niveau de la Carrière au début d'une course", reglage(parametres, "startingCarriereLevel"))
    sans_cle("Courses!C et D, époque et étoiles de chaque course (déduites du rang de la course)", "une valeur saisie par course")
    sans_tableur("niveaux.formulePrix (texte)", eco["niveaux"]["formulePrix"])
    sans_tableur("prestige.etoilesSuivantes (seuils au-delà de la course 9)", eco["prestige"]["etoilesSuivantes"])
    sans_tableur("usines[*].decouverte, entrees, produit, electricite", "conditions de découverte et chaînes de production")
    sans_tableur("notation.apresMd, notation.limiteDouble (texte)", eco["notation"]["limiteDouble"])
    return sortie


def afficher(lignes: list[tuple[str, str, object, object]]) -> int:
    largeur = max(len(sujet) for _statut, sujet, _d, _t in lignes)
    for statut, sujet, definition, tableur in lignes:
        print(f"{statut:<12} {sujet:<{largeur}}  definitions={definition!r}  tableur={tableur!r}")
    compte = {statut: sum(1 for s, *_ in lignes if s == statut) for statut in ("ACCORD", "ECART", "SANS CLE", "SANS TABLEUR")}
    print(
        f"\n{compte['ACCORD']} accord(s), {compte['ECART']} écart(s), {compte['SANS CLE']} réglage(s) du tableur "
        f"sans clé, {compte['SANS TABLEUR']} clé(s) de definitions/ sans équivalent dans le tableur."
    )
    return 1 if compte["ECART"] else 0
