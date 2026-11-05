"""Lecteurs de tools/anti_ia.py : extraient d'un fichier JSON ou TypeScript les seuls morceaux que
les joueurs lisent (valeurs de chaînes, littéraux), avec leur numéro de ligne."""

from __future__ import annotations

import bisect
import json
import re


def _debuts_de_lignes(texte: str) -> list[int]:
    return [0] + [m.end() for m in re.finditer("\n", texte)]


def _ligne_de(debuts: list[int], position: int) -> int:
    return bisect.bisect_right(debuts, position)


RE_JETON_JSON = re.compile(r'"(?:[^"\\\n]|\\.)*"|[{}\[\],:]')


def valeurs_json(texte: str) -> list[tuple[int, str, list[str]]]:
    """Les chaînes qui sont des valeurs (pas des clés) : (ligne, texte, clés des objets parents).

    Un petit lecteur à pile plutôt que json.loads : il donne le numéro de ligne et marche même sur
    un JSON cassé en cours d'édition.
    """
    debuts = _debuts_de_lignes(texte)
    pile: list[dict] = []
    sortie: list[tuple[int, str, list[str]]] = []
    for m in RE_JETON_JSON.finditer(texte):
        jeton = m.group(0)
        haut = pile[-1] if pile else None
        if jeton == "{":
            pile.append({"obj": True, "cle": None, "attend_cle": True})
        elif jeton == "[":
            pile.append({"obj": False})
        elif jeton in "}]":
            if pile:
                pile.pop()
        elif jeton == ",":
            if haut and haut["obj"]:
                haut["attend_cle"] = True
        elif jeton == ":":
            if haut and haut["obj"]:
                haut["attend_cle"] = False
        else:
            try:
                valeur = json.loads(jeton)
            except ValueError:
                valeur = jeton[1:-1]
            if haut and haut["obj"] and haut["attend_cle"]:
                haut["cle"] = valeur
            else:
                cles = [p["cle"] for p in pile if p["obj"] and p["cle"] is not None]
                sortie.append((_ligne_de(debuts, m.start()), valeur, cles))
    return sortie


def _decoder_echappements(texte: str) -> str:
    """\\u00e9 et \\u{1F600} dans une chaîne TypeScript : sinon un emoji échappé passerait."""
    texte = re.sub(r"\\u\{([0-9a-fA-F]{1,6})\}", lambda m: chr(min(int(m.group(1), 16), 0x10FFFF)), texte)
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), texte)


def litteraux_ts(texte: str) -> list[tuple[int, str]]:
    """Le contenu des chaînes '...', "..." et `...` d'un fichier TypeScript, commentaires exclus."""
    sortie: list[tuple[int, str]] = []
    n, i, ligne = len(texte), 0, 1
    while i < n:
        c = texte[i]
        if c == "\n":
            ligne += 1
            i += 1
        elif texte.startswith("//", i):
            fin = texte.find("\n", i)
            i = n if fin < 0 else fin
        elif texte.startswith("/*", i):
            fin = texte.find("*/", i + 2)
            fin = n if fin < 0 else fin + 2
            ligne += texte.count("\n", i, fin)
            i = fin
        elif c in "\"'":
            j = i + 1
            while j < n and texte[j] not in (c, "\n"):
                j += 2 if texte[j] == "\\" else 1
            if j < n and texte[j] == c:
                sortie.append((ligne, _decoder_echappements(texte[i + 1:j])))
                i = j + 1
            else:
                i += 1  # pas une chaîne (expression régulière, apostrophe isolée)
        elif c == "`":
            j, profondeur, morceau = i + 1, 0, []
            while j < n and not (texte[j] == "`" and profondeur == 0):
                if texte[j] == "\\":
                    morceau.append(texte[j:j + 2])
                    j += 2
                    continue
                if texte.startswith("${", j) and profondeur == 0:
                    profondeur = 1
                    j += 2
                    continue
                if profondeur:
                    profondeur += (texte[j] == "{") - (texte[j] == "}")
                else:
                    morceau.append(texte[j])
                j += 1
            sortie.append((ligne, _decoder_echappements("".join(morceau))))
            ligne += texte.count("\n", i, min(j, n))
            i = j + 1
        else:
            i += 1
    return sortie
