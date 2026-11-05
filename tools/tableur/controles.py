"""Contrôles de cohérence : le tableur doit se tenir lui-même avant qu'on en tire des données dorées.

Excel garde en cache le résultat de chaque formule. Si quelqu'un change un réglage sans recalculer, ou
enregistre le fichier avec un autre outil, le cache ment. Ces contrôles recomposent quelques valeurs
(prix, revenu, verdicts, liens entre feuilles) et s'arrêtent à la première incohérence.
"""

from __future__ import annotations

import re

import colonnes as c
from lecture import proche, reglage


def controler(parametres: dict, courses: dict, simulation: dict, ville: dict, synthese: dict) -> list[str]:
    problemes: list[str] = []

    def exiger(condition: bool, message: str) -> None:
        if not condition:
            problemes.append(message)

    croissance = reglage(parametres, "priceGrowthPerLevel")
    plafond = reglage(parametres, "offlineCapHours")
    efficacite = reglage(parametres, "offlineEfficiency")
    heures = reglage(parametres, "playHoursPerDay")
    hors_ligne = reglage(parametres, "offlineMultiplier")
    coefficient = reglage(parametres, "lingotsConversionCoefficient")
    exiger(
        proche(hors_ligne, 1 + min(plafond, 24 - heures) * efficacite / heures),
        "Paramètres!B8 : le multiplicateur hors ligne ne suit plus 1 + MIN(plafond ; 24 - heures) x efficacité / heures",
    )
    usines = {u["name"]: u for u in parametres["factories"]}
    exiger(
        list(usines) == simulation["factories"],
        "Les usines de Paramètres (A22:A36) et de Simulation (T5:AH5) ne sont plus les mêmes, dans le même ordre",
    )
    exiger(
        all(proche(usines[nom]["productionPerLevel"], valeur) for nom, valeur in simulation["productionPerLevel"].items()),
        "Simulation!T4:AH4 ne reprend plus les productions par niveau de Paramètres",
    )

    achats = simulation["purchases"]
    exiger([a["n"] for a in achats] == list(range(1, len(achats) + 1)), "Simulation!A : les numéros d'achat ne vont plus de 1 en 1")
    for a in achats:
        usine = usines.get(a["factory"])
        if usine is None:
            problemes.append(f"Simulation, achat {a['n']} : usine inconnue « {a['factory']} »")
            continue
        prix = (
            usine["level1Price"]
            * croissance ** a["levelBefore"]
            * (croissance ** (a["targetLevel"] - a["levelBefore"]) - 1)
            / (croissance - 1)
        )
        exiger(proche(prix, a["price"]), f"Simulation, achat {a['n']} : le prix {a['price']} n'est pas {prix}")
        revenu = 0.0
        for nom, niveau in a["levelsAfter"].items():
            doublements = (niveau >= c.PALIER_1) + (niveau >= c.PALIER_2) + niveau // c.PAS_REGULIER
            revenu += usines[nom]["productionPerLevel"] * niveau * 2**doublements
        revenu *= a["globalMultiplier"] * hors_ligne
        exiger(proche(revenu, a["incomeAfter"]), f"Simulation, achat {a['n']} : le revenu après {a['incomeAfter']} n'est pas {revenu}")
        exiger(
            a["levelsAfter"].get(a["factory"]) == a["targetLevel"],
            f"Simulation, achat {a['n']} : le niveau après n'est pas le niveau visé",
        )

    par_course: dict[int, list[dict]] = {}
    for a in achats:
        par_course.setdefault(a["course"], []).append(a)
    exiger(
        sorted(par_course) == [k["course"] for k in courses["courses"]],
        "Les courses de Simulation et celles de Courses ne sont pas les mêmes",
    )
    for k in courses["courses"]:
        lignes = par_course.get(k["course"], [])
        drapeaux = "".join(str(int(a["beforePrestige"])) for a in lignes)
        exiger(
            re.fullmatch(r"1+0", drapeaux) is not None,
            f"Course {k['course']} : les achats d'avant le prestige devraient être suivis d'un seul achat non fait, trouvé {drapeaux}",
        )
        faits = [a for a in lignes if a["beforePrestige"] == 1]
        exiger(len(faits) == k["purchasesBeforePrestige"], f"Course {k['course']} : Courses!M ne compte pas les achats de Simulation")
        if faits:
            exiger(
                proche(faits[-1]["courseDurationIfPrestigeFollows"], k["durationHours"]),
                f"Course {k['course']} : Courses!N n'est pas la durée de Simulation!AK",
            )
        racine = (k["prestigeThresholdDonar"] / k["conversionReferenceDonar"]) ** (1 / 3)
        exiger(
            proche(k["lingotsEarnedAtPrestige"], coefficient * racine),
            f"Course {k['course']} : les Lingots gagnés ne suivent plus la racine cubique",
        )

    par_numero = {a["n"]: a for a in achats}
    en_cible = 0
    for cible in synthese["targets"]:
        lien = cible["link"]
        if "simulationPurchase" in lien:
            achat = par_numero[lien["simulationPurchase"]]
            attendu, montant = achat["cumulativeHour"], achat["incomeAfterDisplay"]
        elif "cityZone" in lien:
            attendu, montant = ville["zones"][lien["cityZone"] - 1]["affordableAtHour"], None
        else:
            course = next(k for k in courses["courses"] if k["course"] == lien["course"])
            attendu, montant = course["valueComparedToTarget"], course["thresholdDisplay"]
        exiger(proche(cible["computed"], attendu), f"Synthèse, {cible['id']} : le temps calculé n'est pas celui de sa source")
        if montant is not None:
            exiger(cible["displayedAmount"] == montant, f"Synthèse, {cible['id']} : le montant affiché n'est pas celui de sa source")
        if cible["computed"] < cible["targetMin"]:
            verdict = "Trop court"
        elif cible["computed"] > cible["targetMax"]:
            verdict = "Trop long"
        else:
            verdict = "Dans la cible"
        exiger(cible["verdict"] == verdict, f"Synthèse, {cible['id']} : verdict « {cible['verdict']} », la fourchette donne « {verdict} »")
        exiger(proche(cible["computedMinutes"], cible["computed"] * 60), f"Synthèse, {cible['id']} : les minutes ne sont pas les heures x 60")
        en_cible += cible["verdict"] == "Dans la cible"
    total = len(synthese["targets"])
    exiger(
        synthese["overallVerdict"] == f"{en_cible} étapes sur {total} dans la cible",
        f"Synthèse!E18 : « {synthese['overallVerdict']} », le décompte donne {en_cible} sur {total}",
    )
    return problemes


def controler_notation_ville(notation: dict, ville: dict) -> list[str]:
    problemes = []
    for rang, ligne in enumerate(notation["suffixes"]):
        if ligne["power"] != rang:
            problemes.append(f"Notation : puissance {ligne['power']} à la place de {rang}")
        if not proche(ligne["value"], float(f"1e{3 * rang}")):
            problemes.append(f"Notation : la puissance {rang} vaut {ligne['value']}")
    cumul = 0
    for rang, zone in enumerate(ville["zones"], start=1):
        cumul += zone["priceLingots"]
        if zone["zone"] != rang or zone["cumulativePriceLingots"] != cumul:
            problemes.append(f"Ville : la zone {rang} est mal numérotée ou son prix cumulé est faux")
    return problemes
