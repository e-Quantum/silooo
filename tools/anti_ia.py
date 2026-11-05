#!/usr/bin/env python3
"""Repère les tics d'IA dans les textes vus par les joueurs de Silooo.

Les règles viennent de design/README.md, section « Écrire sans slop » : mots-réclame, questions de
pub, points de suspension, tirets cadratins, séries de « ! », emoji, majuscules d'insistance.

Deux modes :
  anti_ia.py <fichiers...>   vérifie ces fichiers, une trouvaille par ligne
                             « fichier:ligne: règle: extrait », code 1 s'il y en a.
  anti_ia.py --hook          lit sur l'entrée standard le JSON d'un hook PostToolUse de Claude Code,
                             ne regarde que les textes des joueurs, écrit les trouvailles sur la
                             sortie d'erreur et sort en 2 (Claude les voit), sinon en 0.
Option en plus : --tout vérifie tous les fichiers des textes des joueurs du dépôt (utilisé par
tools/verifier.sh). --racine <dossier> change la racine du dépôt (utilisé par les tests).

Python 3.10 ou plus, bibliothèque standard seulement.
"""

from __future__ import annotations

import functools
import json
import os
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

# Les lecteurs JSON et TypeScript vivent dans tools/lib/ (fichiers de 400 lignes au plus).
sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from anti_ia_lecteurs import litteraux_ts, valeurs_json  # noqa: E402

# ----------------------------------------------------------------------------------------------
# Où sont les textes des joueurs ? Motifs relatifs à la racine du dépôt. « * » ne franchit pas un
# dossier, « ** » en franchit. Jamais docs/ ni design/ : ils citent les mots bannis en exemple.
# Pour un .json on lit les valeurs de chaînes seulement (pas les clés), pour un .ts les chaînes
# entre guillemets (le code a des « ... » et des « !! » qui ne sont pas du texte), pour un .lang la
# partie après le « = », pour le reste le texte entier.
# ----------------------------------------------------------------------------------------------
TEXTES_JOUEURS = [
    "definitions/*.json",
    "resource-pack/**/*.lang",
    "resource-pack/**/texts/**",
    "addon/src/**/*.ts",
    "world/**/*.json",
]

# Dossiers qu'on ne parcourt jamais en mode --tout (code généré, dépendances). « generated » : les types
# de l'add-on tirés de definitions/, dont les identifiants sont déjà contrôlés dans leur fichier d'origine.
DOSSIERS_IGNORES = {"node_modules", "build", "dist", "out", ".gradle", ".git", "generated"}

# Clés JSON qui portent des notes pour les développeurs, pas du texte pour les joueurs : on saute
# leur contenu. Exemple : economie.json y met « aa = 1e12, ab = 1e15, ... » (notation.apresMd).
CLES_JSON_TECHNIQUES = {
    "commentaire", "source", "unites", "notation", "formule", "formulePrix",
    "regleGrades", "etoilesSuivantes", "$schema", "$id", "$comment",
}

# Un identifiant (kebab-case, « + » final permis comme vip+) n'est pas un texte lu par un joueur.
IDENTIFIANT = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*\+?$")

# Mots bannis. Écrits sans accent et en minuscules : le texte est « plié » avant la recherche, donc
# « Épique », « epique » et « ÉPIQUE » sont tous trouvés.
MOTS_BANNIS = [
    r"epiques?",
    r"incroyables?",
    r"ultimes?",
    r"legendaires?",
    r"plong(?:e|es|ez|er|eons|ent)",
    r"decouvr(?:e|es|ez|ons)",
    r"aventures?",
    r"univers",
    r"experiences?",
    r"immersi(?:f|fs|ve|ves)",
    r"sans\s+limites?",
    r"a\s+portee\s+de\s+main",
    r"n'attend\s+que\s+toi",
    r"le\s+meilleur\s+serveur",
]
# Ceux-là passent quand ils désignent un grade (« grade Ultime », ou nom du grade dans la boutique).
MOTS_DE_GRADE = ("ultime", "legendaire")

# Sigles permis en majuscules (4 lettres et plus ; les plus courts ne sont jamais regardés).
SIGLES = {
    "HUD", "PDG", "VIP", "MVIP", "JSON", "UDP", "TCP", "DNS", "API",
    "HTTP", "HTTPS", "POST", "PATCH", "HEAD", "DELETE", "OPTIONS", "UUID", "XUID", "HTML",
    "ASCII", "NULL", "TRUE", "FALSE", "TODO", "FIXME", "NOTE", "BDS",
}

# ----------------------------------------------------------------------------------------------
# Les règles
# ----------------------------------------------------------------------------------------------
LIMITE = r"(?<![a-z0-9_])"
FIN = r"(?![a-z0-9_])"
RE_MOTS = [re.compile(LIMITE + motif + FIN, re.IGNORECASE) for motif in MOTS_BANNIS]
RE_QUESTION_PUB = re.compile(
    r"(?:^[\s\-*•>#]*|(?<=[.!?:;»“(\[\"])\s*)(prete?s?\s+a)(?![a-z0-9_])",
    re.IGNORECASE | re.MULTILINE,
)
RE_QUE_TU_SOIS = re.compile(LIMITE + r"que\s+(?:tu\s+sois|vous\s+soyez)" + FIN, re.IGNORECASE)
RE_SUSPENSION = re.compile(r"\.{3,}|…")
RE_EXCLAMATIONS = re.compile(r"!{2,}")
RE_TIRET = re.compile("—")
RE_EMOJI = re.compile(
    "[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B50\u2B55\u2B1B\u2B1C"
    "\u231A\u231B\u23E9-\u23F3\u23F8-\u23FA\uFE0F\u200D]"
)
RE_MAJUSCULES = re.compile(r"(?<![\w])[A-ZÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ]{4,}(?![\w])")
RE_MINUSCULE = re.compile(r"[a-zàâäçéèêëîïôöùûüÿœæ]")
RE_APRES_GRADE = re.compile(r"(?<![a-z0-9_])grade\s+[«\"“']?\s*$", re.IGNORECASE)


@functools.lru_cache(maxsize=None)
def _plier_car(car: str) -> str:
    if car in "’‘":
        return "'"
    base = unicodedata.normalize("NFD", car)
    return base[0] if base else car


def plier(texte: str) -> str:
    """Retire les accents et unifie les apostrophes, sans changer la longueur du texte."""
    return "".join(_plier_car(c) for c in texte)


@dataclass(frozen=True)
class Trouvaille:
    fichier: str
    ligne: int
    regle: str
    extrait: str

    def __str__(self) -> str:
        return f"{self.fichier}:{self.ligne}: {self.regle}: {self.extrait}"


def _extrait(texte: str, debut: int, fin: int) -> str:
    """La ligne qui contient la trouvaille, rognée autour d'elle si elle est longue."""
    d = texte.rfind("\n", 0, debut) + 1
    f = texte.find("\n", fin)
    f = len(texte) if f < 0 else f
    ligne = texte[d:f]
    debut, fin = debut - d, fin - d
    if len(ligne) > 100:
        a = max(0, debut - 35)
        b = min(len(ligne), fin + 35)
        ligne = ("…" if a > 0 else "") + ligne[a:b] + ("…" if b < len(ligne) else "")
    return ligne.strip()


def analyser_segment(texte: str, ligne_depart: int, fichier: str, *, nom_ou_id_boutique: bool = False,
                     code: bool = False) -> list[Trouvaille]:
    """Applique toutes les règles à un morceau de texte (une valeur, une chaîne, une ligne)."""
    trouvailles: list[Trouvaille] = []
    pliee = plier(texte)

    def ajouter(regle: str, debut: int, fin: int) -> None:
        ligne = ligne_depart + texte.count("\n", 0, debut)
        trouvailles.append(Trouvaille(fichier, ligne, regle, _extrait(texte, debut, fin)))

    for motif in RE_MOTS:
        for m in motif.finditer(pliee):
            mot = m.group(0)
            if any(mot.lower().startswith(g[:5]) for g in MOTS_DE_GRADE):
                if RE_APRES_GRADE.search(pliee[: m.start()]):
                    continue
                original = texte[m.start():m.end()]
                if nom_ou_id_boutique and texte.strip() == original and original[:1].isupper():
                    continue
            ajouter(f"mot-banni (« {texte[m.start():m.end()]} »)", m.start(), m.end())

    for m in RE_QUESTION_PUB.finditer(pliee):
        ajouter("question-de-pub", m.start(1), m.end(1))
    for m in RE_QUE_TU_SOIS.finditer(pliee):
        ajouter("que-tu-sois", m.start(), m.end())
    for m in RE_SUSPENSION.finditer(texte):
        # En TypeScript, « ...reste » et « ...[a, b] » sont l'opérateur de décomposition.
        if code and re.match(r"[\w\[{(.]", texte[m.end():m.end() + 1]):
            continue
        ajouter("points-de-suspension", m.start(), m.end())
    for m in RE_EXCLAMATIONS.finditer(texte):
        if code and re.match(r"[\w(\[]", texte[m.end():m.end() + 1]):  # « !!valeur » : double négation
            continue
        ajouter("exclamations-en-serie", m.start(), m.end())
    for m in RE_EMOJI.finditer(texte):
        ajouter("emoji", m.start(), m.end())
    for m in RE_TIRET.finditer(texte):
        ajouter("tiret-cadratin", m.start(), m.end())
    # Un titre ou une étiquette tout en capitales (SCIERIE, NIV. 47 · ÉPOQUE 1) est voulu par le
    # design system : on ne regarde les capitales qu'au milieu d'une phrase qui a des minuscules.
    if RE_MINUSCULE.search(texte):
        for m in RE_MAJUSCULES.finditer(texte):
            if m.group(0) not in SIGLES:
                ajouter("majuscules-d-insistance", m.start(), m.end())
    return trouvailles


RE_CLE_LANG = re.compile(r"^\s*[\w.\-:%]+\s*=")


def analyser_texte(contenu: str, nom: str, affiche: str) -> list[Trouvaille]:
    """Analyse un fichier déjà lu. `nom` est son nom de fichier, `affiche` ce qu'on écrit dans les messages."""
    suffixe = Path(nom).suffix.lower()
    trouvailles: list[Trouvaille] = []
    if suffixe == ".json":
        for ligne, valeur, cles in valeurs_json(contenu):
            if any(c in CLES_JSON_TECHNIQUES for c in cles) or IDENTIFIANT.match(valeur):
                continue
            boutique = Path(nom).name == "boutique.json" and bool(cles) and cles[-1] in ("nom", "id")
            trouvailles += analyser_segment(valeur, ligne, affiche, nom_ou_id_boutique=boutique)
    elif suffixe == ".ts":
        for ligne, valeur in litteraux_ts(contenu):
            trouvailles += analyser_segment(valeur, ligne, affiche, code=True)
    else:
        for numero, ligne in enumerate(contenu.split("\n"), start=1):
            if suffixe == ".lang":
                m = RE_CLE_LANG.match(ligne)
                if m and not ligne.lstrip().startswith("#"):
                    ligne = ligne[m.end():]
            trouvailles += analyser_segment(ligne.rstrip("\r"), numero, affiche)
    vus, uniques = set(), []
    for t in sorted(trouvailles, key=lambda t: (t.ligne, t.regle)):
        if (t.ligne, t.regle, t.extrait) not in vus:
            vus.add((t.ligne, t.regle, t.extrait))
            uniques.append(t)
    return uniques


def analyser_fichier(chemin: Path, affiche: str) -> list[Trouvaille]:
    contenu = chemin.read_text(encoding="utf-8", errors="replace").lstrip("\ufeff")
    return analyser_texte(contenu, chemin.name, affiche)


# ----------------------------------------------------------------------------------------------
# Quels fichiers sont des textes de joueurs ?
# ----------------------------------------------------------------------------------------------
def motif_en_regex(motif: str) -> re.Pattern[str]:
    regex, i = "", 0
    while i < len(motif):
        if motif.startswith("**/", i):
            regex += "(?:.*/)?"
            i += 3
        elif motif.startswith("**", i):
            regex += ".*"
            i += 2
        elif motif[i] == "*":
            regex += "[^/]*"
            i += 1
        elif motif[i] == "?":
            regex += "[^/]"
            i += 1
        else:
            regex += re.escape(motif[i])
            i += 1
    return re.compile(regex + r"\Z")


MOTIFS = [motif_en_regex(m) for m in TEXTES_JOUEURS]
DOSSIERS_RACINE = sorted({m.split("/")[0] for m in TEXTES_JOUEURS})


def normaliser(chemin: str) -> str:
    """Chemin Windows (C:\\x, C:/x) ou POSIX (/c/x, /mnt/c/x) ramené à la forme /c/x."""
    c = chemin.replace("\\", "/")
    m = re.match(r"^([A-Za-z]):/*(.*)$", c) or re.match(r"^/(?:mnt|cygdrive)/([A-Za-z])/(.*)$", c)
    if m:
        c = f"/{m.group(1).lower()}/{m.group(2)}"
    return re.sub(r"/{2,}", "/", c).rstrip("/")


def chemin_natif(chemin: str) -> str:
    """Sous Windows, Python ne comprend pas /c/x : on le rend en C:/x pour ouvrir le fichier."""
    n = normaliser(chemin)
    m = re.match(r"^/([a-z])/(.*)$", n)
    if os.name == "nt" and m:
        return f"{m.group(1).upper()}:/{m.group(2)}"
    return chemin


def candidats_relatifs(chemin: str, racine: Path) -> list[str]:
    """Les formes relatives du chemin à tester contre les motifs."""
    n, r = normaliser(chemin), normaliser(str(racine))
    if n.casefold().startswith(r.casefold() + "/"):
        rel = n[len(r) + 1:]
        # Un worktree niché dans le dépôt : .claude/worktrees/<nom>/definitions/...
        return [re.sub(r"^\.claude/worktrees/[^/]+/", "", rel)]
    # Hors de la racine (autre worktree) : on essaie chaque fin de chemin qui commence à un dossier connu.
    morceaux = n.split("/")
    return ["/".join(morceaux[i:]) for i, p in enumerate(morceaux)
            if p in DOSSIERS_RACINE and not {"docs", "design"} & set(morceaux[:i])]


def est_texte_de_joueur(candidats: list[str]) -> str | None:
    for rel in candidats:
        if any(m.match(rel) for m in MOTIFS):
            return rel
    return None


def fichiers_des_motifs(racine: Path):
    for haut in DOSSIERS_RACINE:
        base = racine / haut
        if not base.is_dir():
            continue
        for dossier, sous_dossiers, fichiers in os.walk(base):
            sous_dossiers[:] = sorted(d for d in sous_dossiers if d not in DOSSIERS_IGNORES)
            for f in sorted(fichiers):
                chemin = Path(dossier) / f
                rel = chemin.relative_to(racine).as_posix()
                if est_texte_de_joueur([rel]):
                    yield chemin, rel


# ----------------------------------------------------------------------------------------------
# Programme
# ----------------------------------------------------------------------------------------------
def mode_hook(racine: Path) -> int:
    try:
        donnees = json.loads(sys.stdin.buffer.read().decode("utf-8", errors="replace") or "{}")
        chemin = (donnees.get("tool_input") or {}).get("file_path")
    except (ValueError, AttributeError):
        print("anti_ia.py: entrée du hook illisible, rien n'est vérifié.", file=sys.stderr)
        return 0
    if not isinstance(chemin, str) or not chemin:
        return 0
    rel = est_texte_de_joueur(candidats_relatifs(chemin, racine))
    fichier = Path(chemin_natif(chemin))
    if rel is None or not fichier.is_file():
        return 0
    trouvailles = analyser_fichier(fichier, rel)
    if not trouvailles:
        return 0
    print("Tics d'IA dans un texte vu par les joueurs (design/README.md, « Écrire sans slop »). "
          "Réécris ces passages :", file=sys.stderr)
    for t in trouvailles:
        print(t, file=sys.stderr)
    return 2


def main(argv: list[str]) -> int:
    for flux in (sys.stdout, sys.stderr):
        flux.reconfigure(encoding="utf-8", errors="replace")
    racine = Path(__file__).resolve().parent.parent
    args, fichiers, mode = list(argv), [], "fichiers"
    while args:
        a = args.pop(0)
        if a in ("-h", "--help"):
            print(__doc__)
            return 0
        if a == "--hook":
            mode = "hook"
        elif a == "--tout":
            mode = "tout"
        elif a == "--racine":
            if not args:
                print("anti_ia.py: --racine demande un dossier", file=sys.stderr)
                return 64
            racine = Path(args.pop(0)).resolve()
        elif a.startswith("--"):
            print(f"anti_ia.py: option inconnue {a}", file=sys.stderr)
            return 64
        else:
            fichiers.append(a)

    if mode == "hook":
        return mode_hook(racine)

    trouvailles: list[Trouvaille] = []
    if mode == "tout":
        cibles = [(chemin, rel) for chemin, rel in fichiers_des_motifs(racine)]
        print(f"anti_ia.py: {len(cibles)} fichier(s) de textes des joueurs à vérifier.")
    elif fichiers:
        cibles = [(Path(f), f.replace("\\", "/")) for f in fichiers]
    else:
        print("usage : anti_ia.py <fichiers...> | --tout | --hook", file=sys.stderr)
        return 64
    for chemin, affiche in cibles:
        if not chemin.is_file():
            print(f"anti_ia.py: {affiche}: fichier introuvable", file=sys.stderr)
            return 64
        trouvailles += analyser_fichier(chemin, affiche)
    for t in trouvailles:
        print(t)
    if trouvailles:
        print(f"\n{len(trouvailles)} tic(s) d'IA trouvé(s).")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
