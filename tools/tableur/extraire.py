#!/usr/bin/env python3
"""Extrait les données dorées du tableur d'équilibrage de Silooo.

Le tableur docs/references/silooo-equilibrage-v2.xlsx simule un joueur type, du premier Donar à la
6e étoile, et vérifie treize cibles de rythme. Ce script le lit sans le modifier et écrit des JSON
dans core/src/test/resources/golden/ : les tests dorés de silooo-core les rejouent. Le README de ce
dossier explique chaque fichier, colonne par colonne.

Le tableur est lu avec openpyxl en data_only : ce sont les valeurs calculées, gardées en cache par
Excel, qu'on obtient ; les formules ne se recalculent pas ici. Si une valeur manque, ou si le tableur
n'est pas cohérent avec lui-même (un prix qui ne suit plus la formule, un verdict faux), le script
s'arrête sans rien écrire : rouvre le tableur dans Excel, enregistre-le, relance.

Lancement, depuis n'importe quel dossier (Python 3.10 ou plus, avec openpyxl) :

  uv run --quiet --with openpyxl python -I tools/tableur/extraire.py             écrit les JSON
  uv run --quiet --with openpyxl python -I tools/tableur/extraire.py --verifier  n'écrit rien ; sort en 1 si
                                                                                 un fichier sur disque n'est
                                                                                 plus celui du tableur
  uv run --quiet --with openpyxl python -I tools/tableur/extraire.py --ecarts    n'écrit rien ; compare le
                                                                                 tableur à definitions/
Options pour les essais : --tableur <fichier.xlsx> et --sortie <dossier>.

Sortie déterministe : clés triées, indentation 2, UTF-8 sans échappement, fin de ligne LF, nombres tels
que le tableur les donne (aucun arrondi). Aucune date, aucun nom de machine : deux lancements de suite
donnent les mêmes octets. Chaque fichier garde la somme SHA-256 du tableur d'où il vient.

Qui donne quoi. Les lettres sont celles des colonnes du tableur ; les clés JSON sont en anglais et
les textes gardent les libellés d'origine. Tout le détail (titre, lettre, clé, unité de chaque colonne)
est dans colonnes.py et recopié dans l'entrée "columns" de chaque JSON.

  Synthèse    A5:G17     les 13 cibles                 -> synthese.json, "targets"
                           A étape (stage), B temps calculé (computed), C et D cible min et max
                           (targetMin, targetMax), E verdict, F le temps en minutes, G montant affiché
                           (selon la cible : revenu après l'achat, ou seuil de prestige)
              A22:G30    ce que voit le joueur         -> "playerSees"
              E18        le verdict global             -> "overallVerdict"
  Paramètres  A4:C17     les 14 réglages               -> parametres.json, "settings" (libellé, valeur,
                           explication ; B8 est le seul réglage calculé)
              A22:E36    les 15 usines                 -> "factories" (époque, prix du niveau 1,
                           production par niveau, retour sur investissement)
  Courses     A5:U13     une ligne par course          -> courses.json, "courses"
  Simulation  A6:AK970   un achat par ligne            -> simulation.json, "purchases". Q = 1 : l'achat
                           a lieu avant le prestige ; la dernière ligne de chaque course (Q = 0) est
                           celle que le joueur n'a pas le temps de faire. T:AH, le niveau de chaque
                           usine après l'achat -> "levelsAfter" (sans les usines à zéro). T4:AH4 ->
                           "productionPerLevel". La colonne E (n° d'usine) n'est pas extraite : elle se
                           déduit de l'ordre des usines.
  Notation    A2:C104    la table des suffixes         -> notation.json, "suffixes"
  Ville       A5:F20     les 16 zones                  -> ville.json, "zones"
              H5:K13     le budget ville par course    -> "courseBudgets"
  Lisez-moi                                            -> rien (texte d'explication)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

# python -I n'ajoute pas le dossier du script au chemin de recherche : on le fait nous-mêmes. Pas de
# __pycache__ non plus, pour que le dépôt reste propre.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    import openpyxl  # noqa: E402
except ImportError:  # pragma: no cover
    sys.exit("openpyxl manque. Lance : uv run --quiet --with openpyxl python -I tools/tableur/extraire.py")

import ecarts  # noqa: E402
import lecture  # noqa: E402
from controles import controler, controler_notation_ville  # noqa: E402

RACINE = Path(__file__).resolve().parents[2]
TABLEUR_DEFAUT = RACINE / "docs" / "references" / "silooo-equilibrage-v2.xlsx"
SORTIE_DEFAUT = RACINE / "core" / "src" / "test" / "resources" / "golden"
DEFINITIONS = RACINE / "definitions"


def en_json(donnees) -> str:
    return json.dumps(donnees, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False) + "\n"


def construire(chemin_tableur: Path) -> dict[str, str]:
    """Lit le tableur, le contrôle, et rend {nom de fichier: texte JSON}."""
    somme = hashlib.sha256(chemin_tableur.read_bytes()).hexdigest()
    classeur = openpyxl.load_workbook(chemin_tableur, data_only=True)
    parametres = lecture.extraire_parametres(classeur, somme)
    courses = lecture.extraire_courses(classeur, somme)
    simulation = lecture.extraire_simulation(classeur, somme)
    notation = lecture.extraire_notation(classeur, somme)
    ville = lecture.extraire_ville(classeur, somme)
    synthese = lecture.extraire_synthese(classeur, somme, simulation["purchases"])
    problemes = controler(parametres, courses, simulation, ville, synthese) + controler_notation_ville(notation, ville)
    if problemes:
        reste = len(problemes) - 15
        raise lecture.ErreurTableur(
            "Le tableur n'est pas cohérent avec lui-même (cache périmé ?) :\n  - "
            + "\n  - ".join(problemes[:15])
            + (f"\n  ... et {reste} autre(s)" if reste > 0 else "")
        )
    return {
        "synthese.json": en_json(synthese),
        "simulation.json": en_json(simulation),
        "parametres.json": en_json(parametres),
        "courses.json": en_json(courses),
        "notation.json": en_json(notation),
        "ville.json": en_json(ville),
    }


def main(argv: list[str] | None = None) -> int:
    for flux in (sys.stdout, sys.stderr):
        if hasattr(flux, "reconfigure"):
            flux.reconfigure(encoding="utf-8")
    analyseur = argparse.ArgumentParser(description="Extrait les données dorées du tableur d'équilibrage.")
    analyseur.add_argument("--tableur", type=Path, default=TABLEUR_DEFAUT, help="le fichier .xlsx à lire")
    analyseur.add_argument("--sortie", type=Path, default=SORTIE_DEFAUT, help="le dossier où écrire les JSON")
    mode = analyseur.add_mutually_exclusive_group()
    mode.add_argument("--verifier", action="store_true", help="compare aux fichiers sur disque, n'écrit rien")
    mode.add_argument("--ecarts", action="store_true", help="compare le tableur à definitions/, n'écrit rien")
    options = analyseur.parse_args(argv)
    try:
        if options.ecarts:
            return ecarts.afficher(ecarts.comparer(options.tableur, DEFINITIONS))
        fichiers = construire(options.tableur)
    except lecture.ErreurTableur as erreur:
        print(f"Erreur : {erreur}", file=sys.stderr)
        return 1
    if options.verifier:
        perimes = [
            nom
            for nom, texte in fichiers.items()
            if not (options.sortie / nom).is_file() or (options.sortie / nom).read_bytes() != texte.encode("utf-8")
        ]
        if perimes:
            print("Fichiers périmés ou absents : " + ", ".join(perimes) + ". Relance sans --verifier.")
            return 1
        print(f"{len(fichiers)} fichiers à jour dans {options.sortie}.")
        return 0
    options.sortie.mkdir(parents=True, exist_ok=True)
    for nom, texte in fichiers.items():
        (options.sortie / nom).write_text(texte, encoding="utf-8", newline="\n")
        print(f"écrit {nom} ({len(texte.encode('utf-8'))} octets)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
