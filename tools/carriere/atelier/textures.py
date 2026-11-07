"""Les textures de la Carrière, en PNG 16 × 16, dans les couleurs de design/tokens.css et le style de design/decor/.

- Les tuiles de design/decor/ sont reprises telles quelles (herbe, terre-herbe, terre, pierre, planches).
- Les autres blocs vanilla sont redessinés dans le même style pour l'aperçu : deux ou trois tons, sans dégradé.
  En jeu, ce sont les vrais blocs de Minecraft.
- Les blocs Silooo (époque 1, artisanale) sont dessinés ici, pixel par pixel. Ils sont écrits dans <sortie>/blocs/
  pour l'aperçu, et dans le dossier de ce script : design/blocs/ (les sources) et resource-pack/textures/blocks/silooo/
  (les copies du pack), avec terrain_texture.json et flipbook_textures.json. Un bloc animé est une bande verticale
  d'images de 16 × 16, comme les textures animées de Bedrock.

Usage : python3 -I textures.py <dossier du dépôt> <dossier de sortie>
"""

import base64
import io
import json
import math
import random
import sys
from pathlib import Path

from PIL import Image

DEPOT = Path(sys.argv[1])
SORTIE = Path(sys.argv[2])
(SORTIE / "blocs").mkdir(parents=True, exist_ok=True)

T = {  # jetons de design/tokens.css, et tons des tuiles de design/decor/
    "fonte-900": "#1e1c1a", "fonte-700": "#3a3633", "fonte-500": "#5b5853", "fonte-300": "#8c8780", "acier-100": "#bab5ac",
    "papier": "#fff6de", "papier-ombre": "#c9ba98", "email-creme": "#efe3c6", "email-bleu": "#2d4e7a", "email-rouge": "#b3362b", "email-rouge-sombre": "#7a2019",
    "email-rouge-clair": "#e0614f", "email-bleu-clair": "#5c84b8",
    "email-vert": "#2f6b4f", "email-vert-clair": "#4f9a74", "laiton": "#c8962e", "laiton-clair": "#f0cf72", "laiton-sombre": "#8a6420",
    "or-lingot": "#f2c440", "flamme": "#e07a2b", "ciel-clair": "#bedaec",
    "herbe-clair": "#8cbe5c", "herbe": "#6a9e48", "herbe-sombre": "#4e7834",
    "bois-clair": "#9a6c43", "bois": "#6b4a2e", "bois-sombre": "#46301e",
}


def c(name, a=255):
    v = T[name]
    return (int(v[1:3], 16), int(v[3:5], 16), int(v[5:7], 16), a)


VIDE = (0, 0, 0, 0)


class Px:
    def __init__(self, w=16, h=16, fond=VIDE):
        self.im = Image.new("RGBA", (w, h), fond)
        self.p = self.im.load()
        self.w, self.h = w, h

    def set(self, x, y, col):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[x, y] = col

    def get(self, x, y):
        return self.p[x, y]


def tons(seed, tones, poids):
    r = random.Random(seed)
    im = Px()
    for y in range(16):
        for x in range(16):
            im.set(x, y, c(r.choices(tones, poids)[0]))
    return im


def charger(nom):
    return Image.open(DEPOT / "design" / "decor" / f"{nom}.png").convert("RGBA")


def recolor(im, table):
    out = im.copy()
    p = out.load()
    for y in range(out.height):
        for x in range(out.width):
            k = "#%02x%02x%02x" % p[x, y][:3]
            if k in table:
                p[x, y] = c(table[k], p[x, y][3])
    return out


# --- Tuiles de la charte ---------------------------------------------------------------------------------
herbe, terre_herbe, terre, pierre, planches = (charger(n) for n in ("herbe", "terre-herbe", "terre", "pierre", "planches"))
sapin = recolor(planches, {"#9a6c43": "bois", "#6b4a2e": "bois-sombre"})


def cobble(seed, base=("fonte-300", "acier-100"), joint="fonte-500"):
    r = random.Random(seed)
    im = Px(fond=c(joint))
    for _ in range(14):
        x0, y0, w, h = r.randrange(16), r.randrange(16), r.randint(3, 5), r.randint(3, 4)
        col = c(r.choice(base))
        for y in range(y0, y0 + h - 1):
            for x in range(x0, x0 + w - 1):
                im.set(x % 16, y % 16, col)
        im.set(x0 % 16, y0 % 16, c("acier-100"))
    return im.im


def briques_pierre():
    im = Px()
    r = random.Random(5)
    for y in range(16):
        for x in range(16):
            row = y // 8
            off = 4 if row % 2 else 0
            joint = y % 8 == 7 or (x + off) % 8 == 7
            im.set(x, y, c("fonte-500") if joint else c(r.choices(["fonte-300", "acier-100"], [4, 1])[0]))
    return im.im


def charbon():
    im = Image.open(DEPOT / "design" / "decor" / "pierre.png").convert("RGBA")
    p = im.load()
    for (x0, y0) in [(2, 3), (9, 2), (5, 9), (12, 10), (1, 13)]:
        for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1), (2, 1)]:
            p[(x0 + dx) % 16, (y0 + dy) % 16] = c("fonte-900")
        p[x0, y0] = c("fonte-700")
    return im


def gravier():
    return tons(7, ["fonte-300", "acier-100", "fonte-500", "bois"], [5, 3, 3, 1]).im


def terre_sterile():
    im = terre.copy()
    p = im.load()
    r = random.Random(8)
    for _ in range(14):
        p[r.randrange(16), r.randrange(16)] = c(r.choice(["fonte-300", "fonte-500"]))
    return im


def chemin_dessus():
    """Chemin de terre tassée : un ton de fond et deux tons proches en taches d'un ou deux pixels, comme terre.png."""
    r = random.Random(9)
    im = Px(fond=c("bois-clair"))
    for _ in range(26):
        x, y = r.randrange(16), r.randrange(16)
        col = c(r.choice(["bois", "laiton-sombre", "laiton-sombre"]))
        im.set(x, y, col)
        if r.random() < 0.35:
            im.set((x + 1) % 16, y, col)
    return im.im


def chemin_cote():
    im = terre.copy()
    p = im.load()
    for x in range(16):
        p[x, 0] = c("bois-clair")
        p[x, 1] = c("laiton-sombre") if x % 5 == 2 else c("bois-clair")
    return im


def buche_cote(clair="bois-sombre", sombre="fonte-700"):
    r = random.Random(11)
    im = Px()
    for y in range(16):
        for x in range(16):
            im.set(x, y, c(sombre) if (x % 4 == 0 or (x % 4 == 2 and r.random() < 0.3)) else c(clair))
    return im.im


def buche_dessus():
    im = Px()
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            im.set(x, y, c("bois-sombre") if d > 6.5 else c("bois") if int(d) % 2 else c("bois-clair"))
    return im.im


def feuilles():
    r = random.Random(12)
    im = Px()
    for y in range(16):
        for x in range(16):
            if r.random() < 0.16:
                continue
            im.set(x, y, c(r.choices(["email-vert", "herbe-sombre", "herbe"], [3, 3, 1])[0]))
    return im.im


def vitre():
    im = Px()
    for y in range(16):
        for x in range(16):
            bord = x in (0, 15) or y in (0, 15)
            im.set(x, y, c("bois-sombre") if bord else c("ciel-clair", 90))
    for i in range(3, 7):
        im.set(i, 10 - i, c("papier", 200))
    return im.im


def porte(face):
    out = sapin.copy()
    p = out.load()
    if face != "top":
        for y in range(2, 7):
            for x in range(4, 12):
                p[x, y] = c("ciel-clair") if (x, y) != (5, 3) else c("papier")
        for x in range(4, 12):
            p[x, 7] = c("bois-sombre")
        p[12, 9] = c("fonte-900")
    return out


def enseigne():
    out = planches.copy()
    p = out.load()
    for y in (5, 9):
        for x in range(3, 13):
            p[x, y] = c("fonte-900")
    return out


def lanterne():
    im = Px()
    for y in range(16):
        for x in range(16):
            cadre = x < 3 or x > 12 or y < 2 or y > 13 or x in (7, 8)
            im.set(x, y, c("fonte-900") if cadre else c("or-lingot") if (x + y) % 3 else c("flamme"))
    return im.im


def rail():
    im = Px()
    for y in range(16):
        for x in range(16):
            if x in (3, 12):
                im.set(x, y, c("acier-100"))
            elif x in (2, 13):
                im.set(x, y, c("fonte-500"))
            elif y % 4 in (1, 2):
                im.set(x, y, c("bois" if y % 4 == 1 else "bois-sombre"))
    return im.im


def toile_araignee():
    im = Px()
    for i in range(16):
        for (x, y) in [(i, i), (15 - i, i), (7, i), (i, 8)]:
            im.set(x, y, c("papier", 220))
    for k in (3, 6):
        for j in range(k, 16 - k):
            for (x, y) in [(j, k), (j, 15 - k), (k, j), (15 - k, j)]:
                im.set(x, y, c("papier", 170))
    return im.im


def laine(clair, sombre, seed):
    r = random.Random(seed)
    im = Px()
    for y in range(16):
        for x in range(16):
            im.set(x, y, c(sombre) if (x + 2 * y) % 7 == 0 or r.random() < 0.12 else c(clair))
    return im.im


def touffe(seed, fleur=None):
    r = random.Random(seed)
    im = Px()
    if fleur:
        for y in range(8, 16):
            im.set(7, y, c("herbe-sombre"))
        im.set(6, 11, c("herbe"))
        for (x, y) in [(6, 5), (7, 5), (8, 5), (6, 6), (7, 6), (8, 6), (7, 4), (7, 7)]:
            im.set(x, y, c(fleur))
        im.set(7, 5, c("laiton-sombre") if fleur == "or-lingot" else c("fonte-900"))
        return im.im
    for _ in range(7):
        x, top = r.randint(1, 14), r.randint(5, 10)
        for y in range(top, 16):
            im.set(x, y, c("herbe" if y < top + 3 else "herbe-sombre"))
    return im.im


def racines():
    r = random.Random(14)
    im = Px()
    for _ in range(6):
        x = r.randint(2, 13)
        for y in range(0, r.randint(6, 15)):
            im.set(x, y, c(r.choice(["bois-clair", "bois"])))
            if r.random() < 0.2:
                x += r.choice((-1, 1))
    return im.im


def stalactite():
    im = Px()
    for y in range(16):
        w = max(0, round(4 - y / 4))
        for x in range(7 - w, 9 + w):
            im.set(x, y, c("fonte-300" if x < 8 else "fonte-500"))
    return im.im


def tonneau(face):
    out = sapin.copy()
    p = out.load()
    if face in ("top", "bottom"):
        for y in range(16):
            for x in range(16):
                if max(abs(x - 7.5), abs(y - 7.5)) > 6.5:
                    p[x, y] = c("fonte-700")
    else:
        for x in range(16):
            p[x, 3] = c("fonte-700")
            p[x, 12] = c("fonte-700")
    return out


def etabli(face):
    out = planches.copy()
    p = out.load()
    if face == "top":
        for i in range(1, 15):
            for (x, y) in [(i, 5), (i, 10), (5, i), (10, i)]:
                p[x, y] = c("bois-sombre")
    else:
        for (x, y) in [(3, 4), (4, 4), (4, 5), (11, 4), (11, 5), (12, 5)]:
            p[x, y] = c("acier-100")
    return out


def bibliotheque(face):
    if face in ("top", "bottom"):
        return planches.copy()
    r = random.Random(16)
    out = planches.copy()
    p = out.load()
    for y0 in (1, 9):
        for x in range(1, 15):
            col = c(r.choice(["email-bleu", "email-rouge", "email-vert", "laiton-sombre", "bois-sombre"]))
            for y in range(y0, y0 + 6):
                p[x, y] = col
    return out


def dalle_pierre():
    return tons(17, ["acier-100", "fonte-300"], [3, 1]).im


# --- Blocs Silooo, époque 1 ----------------------------------------------------------------------------------
# Dessinés pixel par pixel : une grille de 16 lignes par face, une lettre par couleur (légende LEG). La lumière vient
# d'en haut à gauche : arête claire en haut et à gauche, ombre en bas et à droite. Chaque matière tient en trois tons
# (bois clair / bois / bois sombre, émail rouge clair / rouge / sombre, laiton clair / laiton / sombre, fonte), plus le
# trait fonte-900 des objets, comme les icônes de la charte.
LEG = {
    "0": "fonte-900", "1": "fonte-700", "2": "fonte-500", "3": "fonte-300", "4": "acier-100",
    "w": "bois-sombre", "b": "bois", "B": "bois-clair",
    "o": "papier-ombre", "c": "email-creme", "p": "papier",
    "s": "email-rouge-sombre", "r": "email-rouge", "R": "email-rouge-clair",
    "d": "laiton-sombre", "l": "laiton", "L": "laiton-clair",
    "u": "email-bleu", "g": "herbe-sombre", "G": "herbe",
}


def grille(lignes, fond=None):
    """Une image depuis une grille de lettres ; « . » est transparent (ou laisse voir `fond`)."""
    h, w = len(lignes), len(lignes[0])
    out = fond.copy() if fond is not None else Image.new("RGBA", (w, h), VIDE)
    p = out.load()
    for y, ligne in enumerate(lignes):
        assert len(ligne) == w, (y, ligne)
        for x, ch in enumerate(ligne):
            if ch != ".":
                p[x, y] = c(LEG[ch])
    return out


# Le coffre de vente : l'action principale de l'île. Couvercle en émail rouge (la couleur de l'action, comme l'étal),
# caisse en bois sombre cerclée de fonte, et sur la face le jeton de Donar en laiton, percé, accroché au moraillon.
# Dessus : une fente cerclée de laiton, là où on jette ce qu'on vend.
COFFRE_FACE = [
    "0000000000000000",
    "0RRRRRRRRRRRRRs0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrr32rrrrrs0",
    "0ssssss32ssssss0",
    "0000000320000000",
    "032BBBB32BBBB210",
    "02bbbbbbbbbbbb10",
    "0bbwbbbbbbbbbbb0",
    "0wwwwwwwwwwwwww0",
    "0BBBBBBBBBBBBBB0",
    "0bbbbbbbbbbbwbb0",
    "0bbbbbbbbbbbbbb0",
    "02bbbbbbbbbbbb10",
    "022wwwwwwwwww110",
    "0000000000000000",
]
JETON = [  # le jeton de Donar de design/icones/donar.png, réduit à 8 × 8
    "..0000..",
    ".0LLLl0.",
    "0LLLlll0",
    "0LL00ld0",
    "0Ll00dd0",
    "0lllddd0",
    ".0lddd0.",
    "..0000..",
]
COFFRE_COTE = [
    "0000000000000000",
    "0RRRRRRRRRRRRRs0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrrrrrrrrrs0",
    "0ssssssssssssss0",
    "0000000000000000",
    "032BBBBBBBBBB210",
    "02bbbbbbbbbbbb10",
    "0bbbbb3332bbbbb0",
    "0wwwww2221wwwww0",
    "0BBBBBBBBBBBBBB0",
    "0bbbwbbbbbbbbbb0",
    "0bbbbbbbbbbbbbb0",
    "02bbbbbbbbbbbb10",
    "022wwwwwwwwww110",
    "0000000000000000",
]
COFFRE_DESSUS = [
    "0000000000000000",
    "0RRRRRRRRRRRRRs0",
    "0R3rrrrrrrrrr2s0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrrrrrrrrrs0",
    "0RrLLLLLLLLLLrs0",
    "0RrL00000000drs0",
    "0RrL00000000drs0",
    "0Rrldddddddddrs0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrrrrrrrrrs0",
    "0Rrrrrrrrrrrrrs0",
    "0R2rrrrrrrrrr1s0",
    "0ssssssssssssss0",
    "0000000000000000",
]


def coffre(face):
    if face == "front":
        out = grille(COFFRE_FACE)
        out.alpha_composite(grille(JETON), (4, 7))
        return out
    return grille(COFFRE_DESSUS if face == "top" else COFFRE_COTE)


# La trémie : planches verticales entre deux bandes de fonte rivetées. Vue de dessus, un entonnoir : les parois du bas
# et de droite reçoivent la lumière, celles du haut et de gauche sont dans l'ombre ; au fond, la goulotte et du gravier.
TREMIE_COTE = [
    "3333333333333333",
    "2242224222422242",
    "1111111111111111",
    "BbbwBbbwBbbwBbbw",
    "BbbwBbbwBwbwBbbw",
    "BwbwBbbwBbbwBbbw",
    "BbbwBbbwBbbwBbbw",
    "BbbwBbbwBbbwBbww",
    "BbbwBwbwBbbwBbbw",
    "BbbwBbbwBbbwBbbw",
    "BbbwBbbwBbbwBbbw",
    "BbbwBbbwBbwwBbbw",
    "BbbwBbbwBbbwBbbw",
    "3333333333333333",
    "2242224222422242",
    "1111111111111111",
]
TREMIE_DESSUS = [
    "BBBBBBBBBBBBBBBw",
    "BbbbbbbbbbbbbbBw",
    "Bb0000000000000w",
    "Bb0wwwwwwwwwwb0w",
    "Bb0wwwwwwwwwbB0w",
    "Bb0ww1111111bB0w",
    "Bb0ww10002bbBB0w",
    "Bb0ww10302bbBB0w",
    "Bb0ww12002bbBB0w",
    "Bb0ww1220bbbBB0w",
    "Bb0wwBBBBBBBBB0w",
    "Bb0wBBBBBBBBBB0w",
    "Bb0bBBBBBBBBBB0w",
    "Bb0000000000000w",
    "BbwwwwwwwwwwwwBw",
    "wwwwwwwwwwwwwwww",
]


# Le rouet du bocard : une roue pleine en planches (quatre joints en croix), huit dents, un moyeu percé, cernée de
# fonte-900 comme l'icône engrenage, sur le bâti en chêne noir. Elle tourne d'un huitième de tour en quatre images
# (22,5° par image) : avec huit dents et des joints en croix, la dernière image raccorde la première. La lumière ne tourne pas avec la roue : l'ombre reste en bas à droite.
def bati():
    lignes = []
    for y in range(16):
        lignes.append("".join("1" if x % 4 == 3 else "w" for x in range(16)))
    return grille(lignes)


def rouet(angle, grippe=False):
    out = bati()
    p = out.load()
    classes = {}
    for y in range(16):
        for x in range(16):
            votes = {}
            for sy in range(4):
                for sx in range(4):
                    dx, dy = x + (sx + 0.5) / 4 - 8, y + (sy + 0.5) / 4 - 8
                    r = math.hypot(dx, dy)
                    a = math.atan2(dy, dx) - angle
                    k = None
                    if r < 1.2:
                        k = "axe"
                    elif r < 2.5:
                        k = "moyeu"
                    elif r < 5.4:
                        ecart = min(abs(math.remainder(a - i * math.pi / 2, 2 * math.pi)) for i in range(4))
                        k = "joint" if ecart * r < 0.6 else "toile"
                    elif r < 7.6 and math.cos(8 * a) > 0.1:
                        k = "dent"
                    if k:
                        votes[k] = votes.get(k, 0) + 1
            if sum(votes.values()) >= 8:
                gagnant = max(votes, key=votes.get)
                if votes.get("joint", 0) >= 4:  # le joint des planches gagne dès qu'il couvre un tiers du pixel
                    gagnant = "joint"
                classes[(x, y)] = gagnant
    if grippe:  # deux dents cassées, en haut à droite
        for (x, y), k in list(classes.items()):
            if k == "dent" and x >= 9 and y <= 5:
                del classes[(x, y)]
    for (x, y), k in classes.items():
        lum = (x - 7.5) + (y - 7.5)  # négatif vers la lumière
        col = {"axe": "0", "moyeu": "b" if lum < 0 else "w",
               "joint": "w",
               "toile": "o" if lum < -6 else "B" if lum < 3 else "b",
               "dent": "B" if lum < -3 else "b"}[k]
        p[x, y] = c(LEG[col])
    for y in range(16):  # le trait autour de la roue
        for x in range(16):
            if (x, y) not in classes and any((x + dx, y + dy) in classes for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                p[x, y] = c("fonte-900")
    if grippe:
        r = random.Random(23)
        for (x, y), k in classes.items():  # mousse sur le bas de la roue
            if k in ("toile", "dent") and y >= 9 and r.random() < 0.5:
                p[x, y] = c(r.choice(["herbe-sombre", "herbe-sombre", "herbe"]))
        for (x, y) in [(4, 5), (5, 4), (3, 6)]:  # une planche fendue
            p[x, y] = c("fonte-900")
        for (x, y, col) in [(12, 1, "B"), (13, 2, "B"), (12, 2, "b"), (14, 3, "b"), (13, 3, "w")]:  # une cale coincée
            p[x, y] = c(LEG[col])
    return out


def bande(images):
    strip = Image.new("RGBA", (16, 16 * len(images)), VIDE)
    for i, im in enumerate(images):
        strip.paste(im, (0, 16 * i))
    return strip


# Le tapis en toile : une toile écrue tendue entre deux longerons, avec une latte de bois tous les 4 pixels. À
# l'orientation 0, la toile défile vers le nord de la texture (vers le haut) d'un pixel par image : 4 images, puis la
# première revient. La latte projette son ombre sous elle ; le longeron de gauche, son ombre sur la toile.
def toile(k, dechire=False):
    lignes = []
    for y in range(16):
        phase = (y + k) % 4
        ligne = ""
        for x in range(16):
            if x in (0, 15):
                ligne += "w"
            elif x == 1:
                ligne += "b" if y % 8 == 3 else "B"
            elif x == 14:
                ligne += "w" if y % 8 == 3 else "b"
            elif phase == 0:
                ligne += "B" if x < 13 else "b"
            elif phase == 1:
                ligne += "o"
            elif x == 2:
                ligne += "o"
            elif phase == 3 and x % 4 == 1:
                ligne += "o"
            else:
                ligne += "c"
        lignes.append(ligne)
    out = grille(lignes)
    if dechire:
        p = out.load()
        trou = set()
        for y, (x0, x1) in {4: (7, 8), 5: (6, 9), 6: (5, 10), 7: (5, 11), 8: (6, 11), 9: (6, 10), 10: (7, 9), 11: (8, 8),
                            13: (3, 4), 14: (3, 3)}.items():
            trou |= {(x, y) for x in range(x0, x1 + 1)}
        for (x, y) in trou:
            p[x, y] = VIDE
        for (x, y) in [(7, 9), (9, 6), (10, 8)]:  # des fils qui pendent dans le trou
            p[x, y] = c("papier-ombre")
        for y in range(16):  # le bord déchiré s'effiloche
            for x in range(2, 14):
                if (x, y) not in trou and any((x + dx, y + dy) in trou for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    p[x, y] = c("papier-ombre") if (x + y) % 2 else c("bois-clair")
        for (x, y) in [(3, 2), (11, 13), (12, 12), (4, 10)]:  # la toile est sale
            p[x, y] = c("bois")
    return out


# Le flanc du tapis : seules les lignes 5 à 10 servent (le bloc fait 6 pixels de haut ; le modèle du pack prend ces
# lignes-là). Le bord de la toile, le longeron, deux bouts d'axe de rouleau.
TAPIS_COTE = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "cccccccccccccccc",
    "BBBBBBBBBBBBBBBB",
    "bbb32bbbbbb32bbb",
    "bbb21bbbbbb21bbb",
    "bbbbbbbwbbbbbbbb",
    "wwwwwwwwwwwwwwww",
    "................",
    "................",
    "................",
    "................",
    "................",
]

# La plaque CARRIÈRE : une plaque émaillée de 48 × 16 sur trois blocs, comme design/textures/plaque-email-bleue.png :
# trait fonte-900, anneau crème (ombré en bas et à droite), filet bleu, lettres fonte-900 de 4 × 5, vis aux coins.
GLYPHES = {
    "C": [".###", "#...", "#...", "#...", ".###"],
    "A": [".##.", "#..#", "####", "#..#", "#..#"],
    "R": ["###.", "#..#", "###.", "#.#.", "#..#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"],
    "E": ["####", "#...", "###.", "#...", "####"],
}
GLYPHES["È"] = GLYPHES["E"]


def plaque_images(texte="CARRIÈRE"):
    W = 48
    out = Px(W, 16, c("email-creme"))
    for x in range(W):
        for y in range(16):
            bord = min(x, y, W - 1 - x, 15 - y)
            if bord == 0:
                col = "fonte-900"
            elif bord == 1:
                col = "papier-ombre" if (x == W - 2 or y == 14) else "email-creme"
            elif bord == 2:
                col = "email-bleu"
            else:
                col = "email-creme"
            out.set(x, y, c(col))
    for (x, y) in [(0, 0), (W - 1, 0), (0, 15), (W - 1, 15)]:  # coins arrondis : le bois du bloc derrière
        out.set(x, y, c("bois-sombre"))
    for (x, y) in [(1, 1), (W - 2, 1), (1, 14), (W - 2, 14)]:  # vis
        out.set(x, y, c("fonte-500"))
    largeur = sum(len(GLYPHES[ch][0]) for ch in texte) + len(texte) - 1
    x = (W - largeur) // 2
    for ch in texte:
        g = GLYPHES[ch]
        for gy, row in enumerate(g):
            for gx, v in enumerate(row):
                if v == "#":
                    out.set(x + gx, 6 + gy, c("fonte-900"))
        if ch == "È":
            out.set(x + 1, 3, c("fonte-900"))
            out.set(x + 2, 4, c("fonte-900"))
        x += len(g[0]) + 1
    return [out.im.crop((16 * k, 0, 16 * k + 16, 16)) for k in range(3)]


# --- Écriture --------------------------------------------------------------------------------------------------
def uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


tex = {
    "herbe": herbe, "terre-herbe": terre_herbe, "terre": terre, "pierre": pierre, "planches": planches, "sapin": sapin,
    "pierres": cobble(1), "pierres-moussues": recolor(cobble(1), {"#bab5ac": "herbe-sombre"}), "gravier": gravier(),
    "charbon": charbon(), "andesite": recolor(pierre, {"#5b5853": "fonte-300"}), "tuf": recolor(pierre, {"#bab5ac": "fonte-300"}),
    "pierre-taillee": briques_pierre(), "terre-sterile": terre_sterile(), "chemin-dessus": chemin_dessus(), "chemin-cote": chemin_cote(),
    "chene-noir": buche_cote(), "chene-noir-dessus": buche_dessus(), "sapin-buche": buche_cote("bois", "bois-sombre"),
    "feuilles": feuilles(), "vitre": vitre(), "porte": porte("side"), "porte-dessus": porte("top"), "enseigne": enseigne(),
    "lanterne": lanterne(), "rail": rail(), "toile-araignee": toile_araignee(), "herbes": touffe(41), "pissenlit": touffe(42, "or-lingot"),
    "coquelicot": touffe(43, "email-rouge"), "racines": racines(), "stalactite": stalactite(), "tonneau": tonneau("side"),
    "tonneau-dessus": tonneau("top"), "etabli": etabli("side"), "etabli-dessus": etabli("top"), "bibliotheque": bibliotheque("side"),
    "dalle-pierre": dalle_pierre(), "laine-rouge": laine("email-rouge", "email-rouge-sombre", 61), "laine-creme": laine("email-creme", "papier-ombre", 62),
    # blocs Silooo
    "tremie_bois": grille(TREMIE_COTE), "tremie_bois_dessus": grille(TREMIE_DESSUS),
    "engrenage_bois": bande([rouet(k * math.pi / 8) for k in range(4)]), "engrenage_bois_grippe": rouet(0.3, True),
    "tapis_toile": bande([toile(k) for k in range(4)]), "tapis_toile_dechire": toile(0, True), "tapis_toile_cote": grille(TAPIS_COTE),
    "coffre_vente": coffre("side"), "coffre_vente_face": coffre("front"), "coffre_vente_dessus": coffre("top"),
}
for k, im in zip(("plaque_emaillee_gauche", "plaque_emaillee_milieu", "plaque_emaillee_droite"), plaque_images()):
    tex[k] = im

NEUFS = [k for k in tex if k.split("_")[0] in ("tremie", "engrenage", "tapis", "coffre", "plaque")]
ANIMES = {"engrenage_bois": 3, "tapis_toile": 3}  # ticks par image dans flipbook_textures.json (20 ticks = 1 s)

# Les sources du pack (design/blocs/) et leurs copies (resource-pack/textures/blocks/silooo/), dans le dossier de la
# session, à chaque passage : la copie ne peut pas différer de sa source.
ICI = Path(__file__).resolve().parent
for dossier in (SORTIE / "blocs", ICI / "design" / "blocs", ICI / "resource-pack" / "textures" / "blocks" / "silooo"):
    dossier.mkdir(parents=True, exist_ok=True)
    for k in NEUFS:
        tex[k].save(dossier / f"{k}.png")
terrain = {
    "resource_pack_name": "silooo",
    "texture_name": "atlas.terrain",
    "padding": 8,
    "num_mip_levels": 4,
    "texture_data": {
        **{f"silooo_{k}": {"textures": f"textures/blocks/silooo/{k}"} for k in NEUFS},
        # faces qui restent des blocs vanilla : planches de sapin, dessus de bûche de chêne noir
        "silooo_planches_sapin": {"textures": "textures/blocks/planks_spruce"},
        "silooo_chene_noir_dessus": {"textures": "textures/blocks/log_big_oak_top"},
    },
}
(ICI / "resource-pack" / "textures" / "terrain_texture.json").write_text(json.dumps(terrain, indent=2, ensure_ascii=False) + "\n")
flipbook = [{"flipbook_texture": f"textures/blocks/silooo/{k}", "atlas_tile": f"silooo_{k}", "ticks_per_frame": t} for k, t in ANIMES.items()]
(ICI / "resource-pack" / "textures" / "flipbook_textures.json").write_text(json.dumps(flipbook, indent=2) + "\n")

# aperçu agrandi ×8 des blocs Silooo, pour la relecture
planche = Image.new("RGBA", (len(NEUFS) * 18 * 8, 18 * 8 * 4), c("fonte-700"))
for i, k in enumerate(NEUFS):
    im = tex[k]
    grand = im.resize((im.width * 8, im.height * 8), Image.NEAREST)
    planche.alpha_composite(grand, (i * 18 * 8, 8))
planche.save(SORTIE / "blocs-silooo-x8.png")

json.dump({"textures": {k: uri(v) for k, v in tex.items()}, "images": {k: v.height // 16 for k, v in tex.items()}},
          open(SORTIE / "textures.json", "w"), separators=(",", ":"))
print(len(tex), "textures,", len(NEUFS), "blocs Silooo")
