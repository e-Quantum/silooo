"""Lecture du tableur : une fonction par feuille, qui rend le dictionnaire écrit dans le JSON.

Le tableur est ouvert en data_only : on lit les valeurs calculées que Excel a gardées en cache, jamais
les formules. Une cellule vide à l'endroit d'une valeur veut dire que le cache manque.
"""

from __future__ import annotations

import math
import unicodedata

import openpyxl
from openpyxl.utils import get_column_letter

import colonnes as c


class ErreurTableur(Exception):
    """Le tableur n'a pas la forme attendue, ou n'est pas cohérent avec lui-même."""


def nfc(texte: str) -> str:
    return unicodedata.normalize("NFC", texte)


def feuille(classeur, nom: str):
    for ws in classeur.worksheets:
        if nfc(ws.title) == nfc(nom):
            return ws
    raise ErreurTableur(f"Feuille introuvable : {nom}")


def est_nombre(valeur) -> bool:
    return isinstance(valeur, (int, float)) and not isinstance(valeur, bool)


def proche(a: float, b: float) -> bool:
    return math.isclose(a, b, rel_tol=c.TOLERANCE_COHERENCE, abs_tol=1e-12)


def verifier_genre(valeur, genre: str, lieu: str) -> None:
    if genre == "n" and not est_nombre(valeur):
        raise ErreurTableur(f"{lieu} devrait être un nombre, c'est {valeur!r}")
    if genre == "t" and not isinstance(valeur, str):
        raise ErreurTableur(f"{lieu} devrait être un texte, c'est {valeur!r}")


def cellule(ws, ref: str, genre: str = "n"):
    """Valeur d'une cellule ; genre "n" pour un nombre, "t" pour un texte. Refuse une cellule vide."""
    valeur = ws[ref].value
    if valeur is None:
        raise ErreurTableur(
            f"{ws.title}!{ref} est vide : valeur en cache absente ? "
            "Ouvre le tableur dans Excel, enregistre, relance."
        )
    verifier_genre(valeur, genre, f"{ws.title}!{ref}")
    return valeur


def verifier_entetes(ws, ligne: int, colonnes) -> None:
    """Compare les titres de colonnes du tableur à ceux que ce script connaît : une colonne déplacée
    ou renommée arrête tout au lieu de faire extraire la mauvaise donnée."""
    for lettre, _cle, libelle, *_ in colonnes:
        trouve = ws[f"{lettre}{ligne}"].value
        if not isinstance(trouve, str) or nfc(trouve.strip()) != nfc(libelle):
            raise ErreurTableur(
                f"{ws.title}!{lettre}{ligne} : titre attendu « {libelle} », trouvé {trouve!r}. "
                "Le tableur a changé de forme : mets tools/tableur/colonnes.py à jour."
            )


def lire_lignes(ws, premiere: int, colonnes, derniere: int | None = None) -> list[dict]:
    """Une ligne du tableur -> un dict {clé JSON: valeur}. S'arrête à la première ligne dont la
    première colonne est vide."""
    maximum = derniere if derniere is not None else ws.max_row
    lignes = []
    for numero in range(premiere, maximum + 1):
        if ws[f"{colonnes[0][0]}{numero}"].value is None:
            break
        ligne = {}
        for lettre, cle, _libelle, genre, *_ in colonnes:
            ligne[cle] = cellule(ws, f"{lettre}{numero}", genre)
        lignes.append(ligne)
    if not lignes:
        raise ErreurTableur(f"{ws.title} : aucune ligne à partir de la ligne {premiere}")
    return lignes


def description_colonnes(colonnes) -> dict:
    """Le dictionnaire écrit dans chaque JSON : clé -> lettre de colonne, libellé d'origine, unité."""
    sortie = {}
    for lettre, cle, libelle, _genre, *reste in colonnes:
        entree = {"column": lettre, "label": libelle}
        if reste and reste[0]:
            entree["unit"] = reste[0]
        sortie[cle] = entree
    return sortie


def source(feuilles: list[str], somme: str) -> dict:
    return {
        "file": c.CHEMIN_TABLEUR,
        "generator": c.GENERATEUR,
        "sha256": somme,
        "sheets": feuilles,
        "warning": f"Fichier généré par {c.GENERATEUR} : ne pas modifier à la main.",
    }


def reglage(parametres: dict, identifiant: str):
    return parametres["settings"][identifiant]["value"]


# ----------------------------------------------------------------------------------------------
# Une fonction par fichier de sortie
# ----------------------------------------------------------------------------------------------

def extraire_parametres(classeur, somme: str) -> dict:
    ws = feuille(classeur, "Paramètres")
    verifier_entetes(ws, 3, c.PARAMETRES_COLONNES)
    verifier_entetes(ws, 21, c.USINES_COLONNES)
    reglages = {}
    for ligne, identifiant, libelle, unite in c.REGLAGES:
        lu = cellule(ws, f"A{ligne}", "t")
        if nfc(lu.strip()) != nfc(libelle):
            raise ErreurTableur(f"{ws.title}!A{ligne} : libellé attendu « {libelle} », trouvé « {lu} »")
        reglages[identifiant] = {
            "cell": f"B{ligne}",
            "label": lu,
            "value": cellule(ws, f"B{ligne}"),
            "unit": unite,
            "displayFormat": ws[f"B{ligne}"].number_format,
            "explanation": cellule(ws, f"C{ligne}", "t"),
            "calculated": ligne == c.REGLAGE_CALCULE,
        }
    usines = lire_lignes(ws, c.USINES_PREMIERE_LIGNE, c.USINES_COLONNES, derniere=c.USINES_DERNIERE_LIGNE)
    for decalage, usine in enumerate(usines):
        usine["row"] = c.USINES_PREMIERE_LIGNE + decalage
    if len(usines) != 15:
        raise ErreurTableur(f"Paramètres : 15 usines attendues, {len(usines)} trouvées")
    return {
        "source": source(["Paramètres"], somme),
        "columns": {
            "settings": {
                "label": {"column": "A", "label": "Réglage"},
                "value": {"column": "B", "label": "Valeur"},
                "explanation": {"column": "C", "label": "Explication"},
                "calculated": {"note": "vrai pour le seul réglage qui est une formule (B8)"},
            },
            "factories": description_colonnes(c.USINES_COLONNES),
        },
        "settings": reglages,
        "factories": usines,
        "note": cellule(ws, "A38", "t"),
    }


def extraire_courses(classeur, somme: str) -> dict:
    ws = feuille(classeur, "Courses")
    verifier_entetes(ws, 4, c.COURSES)
    return {
        "source": source(["Courses"], somme),
        "columns": description_colonnes(c.COURSES),
        "courses": lire_lignes(ws, 5, c.COURSES, derniere=13),
    }


def extraire_simulation(classeur, somme: str) -> dict:
    ws = feuille(classeur, "Simulation")
    verifier_entetes(ws, 5, c.SIMULATION + [c.SIMULATION_E])
    debut, fin = c.SIMULATION_USINES
    lettres = [get_column_letter(i) for i in range(debut, fin + 1)]
    noms = [cellule(ws, f"{lettre}5", "t") for lettre in lettres]
    production = {nom: cellule(ws, f"{lettre}4") for nom, lettre in zip(noms, lettres)}
    achats = lire_lignes(ws, c.SIMULATION_PREMIERE_LIGNE, c.SIMULATION)
    for decalage, achat in enumerate(achats):
        numero = c.SIMULATION_PREMIERE_LIGNE + decalage
        niveaux = {}
        for nom, lettre in zip(noms, lettres):
            niveau = cellule(ws, f"{lettre}{numero}")
            if niveau != 0:
                niveaux[nom] = niveau
        achat["levelsAfter"] = niveaux
    return {
        "source": source(["Simulation"], somme),
        "columns": {
            **description_colonnes(c.SIMULATION),
            "levelsAfter": {
                "column": "T:AH",
                "label": "Niveau de chaque usine après l'achat (une colonne par usine)",
                "note": "usines au niveau 0 omises",
            },
        },
        "factories": noms,
        "productionPerLevel": production,
        "purchases": achats,
    }


def extraire_notation(classeur, somme: str) -> dict:
    ws = feuille(classeur, "Notation")
    verifier_entetes(ws, 1, c.NOTATION)
    suffixes = []
    ligne = 2
    while ws[f"A{ligne}"].value is not None:
        suffixe = ws[f"B{ligne}"].value  # vide pour la puissance 0
        suffixes.append(
            {
                "power": cellule(ws, f"A{ligne}"),
                "suffix": "" if suffixe is None else suffixe,
                "value": cellule(ws, f"C{ligne}"),
            }
        )
        ligne += 1
    colonnes = description_colonnes(c.NOTATION)
    colonnes["suffix"]["note"] = "vide pour la puissance 0"
    return {
        "source": source(["Notation"], somme),
        "columns": colonnes,
        "note": cellule(ws, "E2", "t"),
        "suffixes": suffixes,
    }


def extraire_ville(classeur, somme: str) -> dict:
    ws = feuille(classeur, "Ville")
    verifier_entetes(ws, 4, c.VILLE_ZONES + c.VILLE_BUDGETS)
    return {
        "source": source(["Ville"], somme),
        "columns": {
            "zones": description_colonnes(c.VILLE_ZONES),
            "courseBudgets": description_colonnes(c.VILLE_BUDGETS),
        },
        "zones": lire_lignes(ws, 5, c.VILLE_ZONES, derniere=20),
        "courseBudgets": lire_lignes(ws, 5, c.VILLE_BUDGETS, derniere=13),
    }


def extraire_synthese(classeur, somme: str, achats: list[dict]) -> dict:
    ws = feuille(classeur, "Synthèse")
    verifier_entetes(ws, 4, c.SYNTHESE)
    verifier_entetes(ws, 21, c.JOUEUR_VOIT)
    lignes = lire_lignes(ws, 5, c.SYNTHESE, derniere=17)
    if len(lignes) != len(c.CIBLES):
        raise ErreurTableur(f"Synthèse : {len(c.CIBLES)} cibles attendues, {len(lignes)} trouvées")
    cibles = []
    for rang, (ligne, (identifiant, genre, detail)) in enumerate(zip(lignes, c.CIBLES), start=1):
        cible = {
            "id": identifiant,
            "order": rang,
            "sheetRow": 4 + rang,
            "measure": c.MESURE[genre],
            "unit": "h",
            "displayedAmountMeaning": c.SENS_DU_MONTANT[genre],
            **ligne,
        }
        if genre == "purchase":
            usine, niveau = detail
            trouves = [a for a in achats if a["course"] == 1 and a["factory"] == usine and a["targetLevel"] == niveau]
            if len(trouves) != 1:
                raise ErreurTableur(f"Synthèse : {identifiant} devrait viser un seul achat, {len(trouves)} trouvés")
            cible["link"] = {"simulationPurchase": trouves[0]["n"]}
        elif genre == "city-zone":
            cible["link"] = {"cityZone": detail}
        else:
            cible["link"] = {"course": detail}
        cibles.append(cible)
    voit = lire_lignes(ws, 22, c.JOUEUR_VOIT, derniere=30)
    for rang, ligne in enumerate(voit, start=1):
        ligne["course"] = rang
    return {
        "source": source(["Synthèse"], somme),
        "columns": {
            "targets": description_colonnes(c.SYNTHESE),
            "playerSees": description_colonnes(c.JOUEUR_VOIT),
        },
        "verdictRule": (
            "Trop court si computed < targetMin, Trop long si computed > targetMax, sinon Dans la cible : "
            "la fourchette [targetMin, targetMax] est la tolérance de la feuille, bornes comprises."
        ),
        "overallVerdict": cellule(ws, "E18", "t"),
        "targets": cibles,
        "playerSees": voit,
    }
