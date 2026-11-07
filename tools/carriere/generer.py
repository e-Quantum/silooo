"""Carrière de Silooo, époque 1 (artisanale : bois, pierre, moulins). Deux états : ruine et marche.

Source unique de l'aperçu 3D et, une fois validée, du .mcstructure. Aucun hasard : chaque choix vient d'un hachage
des coordonnées, la structure est la même à chaque génération.

Repère : x vers l'est, z vers le sud, y vers le haut. Origine au coin nord-ouest. Le sud est « avant » au sens de
definitions/iles.json : le côté de l'île principale, du pont et de l'apparition. Le joueur arrive au sud et regarde le
centre de l'île : c'est là qu'est le coffre de vente, sous l'étal rouge, avec la plaque CARRIÈRE.

Ce qui bouge n'est pas un bloc : ailes du moulin, pilons, pierres, wagonnets, charrette, salariés et Oooscar sont des
entités du pack de ressources, animées côté joueur (docs/game-design.md). Le générateur donne seulement leur place.
"""

import json
import math
import sys

W, D = 31, 31
Y0 = 14
CX, CZ = 15.5, 15.0     # centre de l'île : le joueur le regarde en arrivant
R0 = 13.8

PALETTE = [
    ("minecraft:grass_block", "Herbe", "cube", {"top": "herbe", "side": "terre-herbe", "bottom": "terre"}, False),
    ("minecraft:dirt", "Terre", "cube", {"all": "terre"}, False),
    ("minecraft:coarse_dirt", "Terre stérile", "cube", {"all": "terre-sterile"}, False),
    ("minecraft:dirt_path", "Chemin", "path", {"top": "chemin-dessus", "side": "chemin-cote", "bottom": "terre"}, False),
    ("minecraft:stone", "Pierre", "cube", {"all": "pierre"}, False),
    ("minecraft:andesite", "Andésite", "cube", {"all": "andesite"}, False),
    ("minecraft:tuff", "Tuf", "cube", {"all": "tuf"}, False),
    ("minecraft:cobblestone", "Pierres", "cube", {"all": "pierres"}, False),
    ("minecraft:mossy_cobblestone", "Pierres moussues", "cube", {"all": "pierres-moussues"}, False),
    ("minecraft:gravel", "Gravier", "cube", {"all": "gravier"}, False),
    ("minecraft:coal_ore", "Minerai de charbon", "cube", {"all": "charbon"}, False),
    ("minecraft:stone_bricks", "Pierre taillée", "cube", {"all": "pierre-taillee"}, False),
    ("minecraft:oak_planks", "Planches de chêne", "cube", {"all": "planches"}, False),
    ("minecraft:spruce_planks", "Planches de sapin", "cube", {"all": "sapin"}, False),
    ("minecraft:spruce_stairs[monte=nord]", "Toit en sapin", "stairs", {"all": "sapin"}, False),
    ("minecraft:spruce_stairs[monte=sud]", "Toit en sapin", "stairs", {"all": "sapin"}, False),
    ("minecraft:spruce_stairs[monte=est]", "Toit en sapin", "stairs", {"all": "sapin"}, False),
    ("minecraft:spruce_stairs[monte=ouest]", "Toit en sapin", "stairs", {"all": "sapin"}, False),
    ("minecraft:spruce_slab", "Dalle de sapin", "slab", {"all": "sapin"}, False),
    ("minecraft:dark_oak_log", "Bûche de chêne noir", "cube", {"top": "chene-noir-dessus", "bottom": "chene-noir-dessus", "side": "chene-noir"}, False),
    ("minecraft:spruce_log", "Bûche de sapin", "cube", {"top": "chene-noir-dessus", "bottom": "chene-noir-dessus", "side": "sapin-buche"}, False),
    ("minecraft:spruce_fence", "Barrière de sapin", "post", {"all": "sapin"}, False),
    ("minecraft:spruce_leaves", "Feuilles de sapin", "leaves", {"all": "feuilles"}, False),
    ("minecraft:glass_pane", "Vitre", "pane", {"all": "vitre"}, False),
    ("minecraft:spruce_door", "Porte en sapin", "door", {"side": "porte", "top": "porte-dessus", "bottom": "porte-dessus"}, False),
    ("minecraft:smooth_stone_slab", "Dalle de pierre", "slab", {"all": "dalle-pierre"}, False),
    ("minecraft:lantern", "Lanterne", "lantern", {"all": "lanterne"}, False),
    ("minecraft:barrel", "Tonneau", "cube", {"side": "tonneau", "top": "tonneau-dessus", "bottom": "tonneau-dessus"}, False),
    ("minecraft:crafting_table", "Établi", "cube", {"side": "etabli", "top": "etabli-dessus", "bottom": "planches"}, False),
    ("minecraft:bookshelf", "Bibliothèque", "cube", {"side": "bibliotheque", "top": "planches", "bottom": "planches"}, False),
    ("minecraft:rail", "Rails", "rail", {"all": "rail"}, False),
    ("minecraft:red_wool", "Toile rouge de l'étal", "cube", {"all": "laine-rouge"}, False),
    ("minecraft:white_wool", "Toile crème de l'étal", "cube", {"all": "laine-creme"}, False),
    ("minecraft:cobweb", "Toile d'araignée", "cross", {"all": "toile-araignee"}, False),
    ("minecraft:short_grass", "Herbes", "cross", {"all": "herbes"}, False),
    ("minecraft:dandelion", "Pissenlit", "cross", {"all": "pissenlit"}, False),
    ("minecraft:poppy", "Coquelicot", "cross", {"all": "coquelicot"}, False),
    ("minecraft:hanging_roots", "Racines", "cross", {"all": "racines"}, False),
    ("minecraft:pointed_dripstone", "Spéléothème", "cross", {"all": "stalactite"}, False),
    ("minecraft:oak_wall_sign", "Enseigne au nom de l'entreprise", "sign", {"all": "enseigne"}, False),
    ("silooo:tremie_bois", "Trémie en bois", "cube", {"side": "tremie_bois", "top": "tremie_bois_dessus", "bottom": "tremie_bois"}, True),
    ("silooo:engrenage_bois", "Rouet du bocard", "cube", {"side": "engrenage_bois", "top": "chene-noir-dessus", "bottom": "chene-noir-dessus"}, True),
    ("silooo:engrenage_bois[grippe]", "Rouet grippé", "cube", {"side": "engrenage_bois_grippe", "top": "chene-noir-dessus", "bottom": "chene-noir-dessus"}, True),
    ("silooo:tapis_toile", "Tapis en toile", "conveyor", {"top": "tapis_toile", "side": "tapis_toile_cote", "bottom": "sapin"}, True),
    ("silooo:tapis_toile[dechire]", "Tapis déchiré", "conveyor", {"top": "tapis_toile_dechire", "side": "tapis_toile_cote", "bottom": "sapin"}, True),
    ("silooo:coffre_vente", "Coffre de vente", "chest", {"side": "coffre_vente", "pz": "coffre_vente_face", "top": "coffre_vente_dessus", "bottom": "sapin"}, True),
    ("silooo:plaque_emaillee[gauche]", "Plaque CARRIÈRE (gauche)", "cube", {"all": "sapin", "pz": "plaque_emaillee_gauche"}, True),
    ("silooo:plaque_emaillee[milieu]", "Plaque CARRIÈRE (milieu)", "cube", {"all": "sapin", "pz": "plaque_emaillee_milieu"}, True),
    ("silooo:plaque_emaillee[droite]", "Plaque CARRIÈRE (droite)", "cube", {"all": "sapin", "pz": "plaque_emaillee_droite"}, True),
]
P = {p[0]: i for i, p in enumerate(PALETTE)}
OPAQUE = {"cube", "leaves"}


def h(*k):
    v = 2166136261
    for n in k:
        v ^= (int(n) + 0x9E3779B9) & 0xFFFFFFFF
        v = (v * 16777619) & 0xFFFFFFFF
        v ^= v >> 13
        v = (v * 0x5BD1E995) & 0xFFFFFFFF
        v ^= v >> 15
    return v / 2**32


class Grid:
    def __init__(self):
        self.b = {}

    def set(self, x, y, z, bid):
        if bid is None:
            self.b.pop((x, y, z), None)
        else:
            assert bid in P, bid
            self.b[(x, y, z)] = bid

    def get(self, x, y, z):
        return self.b.get((x, y, z))

    def fill(self, x0, y0, z0, x1, y1, z1, bid):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, bid)

    def top(self, x, z):
        for y in range(Y0 + 20, -5, -1):
            bid = self.b.get((x, y, z))
            if bid is not None and PALETTE[P[bid]][2] in OPAQUE | {"path"}:
                return y
        return None


# --- Île -----------------------------------------------------------------------------------------------------
def radius(theta):
    return R0 * (1 + 0.07 * math.sin(3 * theta + 0.7) + 0.05 * math.sin(5 * theta + 2.1) + 0.03 * math.sin(7 * theta + 4.0))


def polar(x, z):
    dx, dz = (x + 0.5 - CX) / 1.06, (z + 0.5 - CZ)
    return math.hypot(dx, dz), math.atan2(dz, dx)


def inside(x, z):
    d, t = polar(x, z)
    return d <= radius(t)


def edge_gap(x, z):
    d, t = polar(x, z)
    return radius(t) - d


CUT_X = (7, 23)


def terrace(z):
    return 9 if z <= 2 else 6 if z <= 4 else 3 if z <= 6 else 0


def hill(x, z):
    return max(0, round(9 - 1.4 * max(0, z - 2)))


def mass_height(x, z):
    hh = terrace(z) if CUT_X[0] <= x <= CUT_X[1] else hill(x, z)
    return max(0, min(hh, round(edge_gap(x, z) * 2.6 + 1)))


def build_island(g):
    for x in range(W):
        for z in range(D):
            if not inside(x, z):
                continue
            gap = edge_gap(x, z)
            d, t = polar(x, z)
            tt = max(0.0, 1 - d / radius(t))
            bottom = Y0 - round(2 + 12 * tt ** 0.8 + h(x, z, 1) * 2.2)
            mh = mass_height(x, z)
            rocheux = mh > 0 and z <= 8  # sous le front de taille, la roche descend dans l'île : pas de terre
            for y in range(bottom, Y0 + 1):
                depth = Y0 - y
                if not rocheux and depth == 0:
                    bid = "minecraft:grass_block"
                elif not rocheux and (depth <= 2 or (depth == 3 and tt > 0.3)):
                    bid = "minecraft:dirt"
                else:
                    r = h(x, y, z, 2)
                    bid = ("minecraft:coal_ore" if r < 0.05 else "minecraft:andesite" if r < 0.2
                           else "minecraft:tuff" if r < 0.28 else "minecraft:gravel" if r < 0.31 else "minecraft:stone")
                g.set(x, y, z, bid)
            if gap < 3 and h(x, z, 3) < 0.25 and Y0 - bottom <= 5 and not rocheux:
                g.set(x, bottom - 1, z, "minecraft:hanging_roots")
            elif tt > 0.55 and h(x, z, 4) < 0.2:
                g.set(x, bottom - 1, z, "minecraft:pointed_dripstone")

            in_cut = CUT_X[0] <= x <= CUT_X[1]
            for k in range(1, mh + 1):
                y = Y0 + k
                if in_cut or (mh - k >= 2 and gap < 2.5):
                    r = h(x, y, z, 5)
                    vein = y in (Y0 + 5, Y0 + 8)
                    bid = ("minecraft:coal_ore" if r < (0.32 if vein else 0.07) else "minecraft:andesite" if r < 0.45
                           else "minecraft:tuff" if r < 0.55 else "minecraft:stone")
                else:
                    bid = "minecraft:grass_block" if k == mh else "minecraft:dirt" if k >= mh - 2 else "minecraft:stone"
                g.set(x, y, z, bid)
            if in_cut and mh > 0 and k == mh and z <= 1:
                g.set(x, Y0 + mh, z, "minecraft:grass_block")
            if in_cut and mh == 0 and z <= 11:
                r = h(x, z, 6)
                g.set(x, Y0, z, "minecraft:gravel" if r < 0.3 else "minecraft:cobblestone" if r < 0.45 else "minecraft:stone")
            elif in_cut and z == 12:
                g.set(x, Y0, z, "minecraft:coarse_dirt" if h(x, z, 7) < 0.6 else "minecraft:grass_block")


# --- Rails et chevalement ------------------------------------------------------------------------------------
RAIL_Z = 8
RAIL_X = (8, 19)


def rail_y(x):
    if x <= 12:
        return Y0 + 1
    if x <= 17:
        return Y0 + 1 + (x - 12)
    return Y0 + 6


def build_rails(g, ruine):
    for x in range(RAIL_X[0], RAIL_X[1] + 1):
        ry = rail_y(x)
        arrache = ruine and 13 <= x <= 15  # un tronçon du chevalement arraché d'un bloc
        if not arrache:
            g.set(x, ry, RAIL_Z, "minecraft:rail")
        if ry > Y0 + 1:
            if not arrache:
                g.set(x, ry - 1, RAIL_Z, "minecraft:spruce_planks")
            if x >= RAIL_X[1]:
                continue
            post = "minecraft:dark_oak_log" if x % 2 == 0 else "minecraft:spruce_fence"
            for y in range(Y0 + 1, ry - 1):
                if arrache and y > Y0 + 1:
                    continue
                g.set(x, y, RAIL_Z, post)
                if x % 2 == 0 and y == ry - 2 and not arrache:
                    g.set(x, y, RAIL_Z - 1, "minecraft:spruce_fence")
                    g.set(x, y, RAIL_Z + 1, "minecraft:spruce_fence")
    if ruine:  # les planches tombées du tronçon arraché
        for (x, z) in [(13, 9), (14, 10), (15, 9)]:
            g.set(x, Y0 + 1, z, "minecraft:spruce_slab")


# --- Bocard ---------------------------------------------------------------------------------------------------
BOC = (19, 7, 23, 11)
PILON_Z = 10


def build_bocard(g, ruine):
    x0, z0, x1, z1 = BOC
    g.fill(x0 - 1, Y0 + 1, z0 - 1, x1 + 1, Y0 + 12, z1 + 1, None)
    g.fill(x0 - 1, Y0, z0 - 1, x1 + 1, Y0, z1 + 1, "minecraft:cobblestone")
    rouet = "silooo:engrenage_bois[grippe]" if ruine else "silooo:engrenage_bois"
    for px in (x0, x1):
        for pz in (z0, z1):
            g.fill(px, Y0 + 1, pz, px, Y0 + 6, pz, "minecraft:dark_oak_log")
    g.fill(x0, Y0 + 4, z0, x1, Y0 + 4, RAIL_Z, "minecraft:spruce_planks")
    g.set(x0, Y0 + 5, RAIL_Z, "minecraft:spruce_planks")
    g.fill(x0 + 1, Y0 + 5, RAIL_Z, x1 - 1, Y0 + 5, RAIL_Z, "silooo:tremie_bois")
    g.fill(x0 + 1, Y0 + 1, PILON_Z, x1 - 1, Y0 + 1, PILON_Z, "minecraft:stone_bricks")
    g.fill(x0 + 1, Y0 + 6, PILON_Z, x1 - 1, Y0 + 6, PILON_Z, "minecraft:dark_oak_log")
    g.set(x0, Y0 + 6, PILON_Z, rouet)
    g.set(x1, Y0 + 6, PILON_Z, rouet)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if ruine and x >= x0 + 2:  # la moitié est du toit s'est effondrée
                continue
            g.set(x, Y0 + 7, z, "minecraft:spruce_slab")
    if ruine:
        for (x, z) in [(x0 + 3, z1), (x1, z1), (x0 + 2, z1)]:
            g.set(x, Y0 + 1, z, "minecraft:spruce_slab")
        g.set(x0, Y0 + 6, z0 + 1, "minecraft:cobweb")
    else:
        g.set(x1 - 1, Y0 + 3, RAIL_Z, "minecraft:lantern")  # suspendue sous le plancher de la trémie
    # transmission vers le moulin
    g.set(x1 + 1, Y0 + 6, PILON_Z, "minecraft:dark_oak_log")
    g.set(x1 + 2, Y0 + 6, PILON_Z, rouet)


# --- Moulin ---------------------------------------------------------------------------------------------------
MOU = (25, 11, 27, 13)
MOYEU = (26.5, Y0 + 7.5, 14.05)
RAYON_AILES = 5


def build_moulin(g, ruine):
    x0, z0, x1, z1 = MOU
    g.fill(x0, Y0 + 1, z0, x1, Y0 + 11, z1, None)
    g.fill(x0 - 1, Y0, z0 - 1, x1 + 1, Y0, z1 + 1, "minecraft:cobblestone")
    g.fill(x0, Y0 + 1, z0, x1, Y0 + 3, z1, "minecraft:cobblestone")
    for y in range(Y0 + 4, Y0 + 9):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                coin = x in (x0, x1) and z in (z0, z1)
                g.set(x, y, z, "minecraft:dark_oak_log" if coin else "minecraft:spruce_planks")
    g.set(x0, Y0 + 1, z0 + 1, None)
    g.set(x0, Y0 + 2, z0 + 1, None)
    g.set(x0, Y0 + 6, z0 + 1, "minecraft:spruce_planks" if ruine else "minecraft:glass_pane")
    for x in range(x0, x1 + 1):
        for z in range(z0 - 1, z1 + 1):
            if ruine and x == x1 and z >= z0:  # un pan du toit ouvert
                continue
            monte = "sud" if z == z0 - 1 else "nord" if z == z1 else "est" if x == x0 else "ouest" if x == x1 else None
            g.set(x, Y0 + 9, z, f"minecraft:spruce_stairs[monte={monte}]" if monte else "minecraft:spruce_planks")
    g.set(x0 + 1, Y0 + 10, z0, "minecraft:spruce_slab")
    g.set(x0 + 1, Y0 + 10, z0 + 1, "minecraft:spruce_slab")
    if ruine:
        for (x, y, z) in [(x0, Y0 + 2, z0), (x1, Y0 + 1, z1), (x1, Y0 + 3, z0)]:
            g.set(x, y, z, "minecraft:mossy_cobblestone")


# --- Tapis et étal de vente -----------------------------------------------------------------------------------
TAPIS_Y = Y0 + 3
ETAL = (13, 15, 17, 17)      # x0, z0, x1, z1 de l'étal
COFFRE = (15, Y0 + 2, 16)    # au centre de l'île, façade au sud : le joueur arrive en face


def tapis_path():
    """Les blocs du tapis dans le sens de la marche : sortie de l'auge (incliné), puis vers le sud, puis vers l'ouest."""
    x0, z0, x1, z1 = BOC
    mx = (x0 + x1) // 2
    path = [(mx, Y0 + 2, z1)]
    path += [(mx, TAPIS_Y, z) for z in range(z1 + 1, 17)]
    path += [(x, TAPIS_Y, 16) for x in range(mx - 1, COFFRE[0], -1)]
    return path


def build_tapis(g, ruine):
    path = tapis_path()
    for i, (x, y, z) in enumerate(path):
        trou = ruine and 3 <= i <= 5  # un tronçon de toile arraché
        if not trou:
            g.set(x, y, z, "silooo:tapis_toile[dechire]" if ruine else "silooo:tapis_toile")
        if i == 0:
            g.set(x, Y0 + 1, z, "minecraft:spruce_planks")
        elif i % 3 == 2 and not (ETAL[0] <= x <= ETAL[2] and ETAL[1] <= z <= ETAL[3]):
            g.fill(x, Y0 + 1, z, x, y - 1, z, "minecraft:spruce_fence")


def build_etal(g, ruine):
    x0, z0, x1, z1 = ETAL
    g.fill(x0, Y0, z0, x1, Y0, z1, "minecraft:stone_bricks")
    cx, cy, cz = COFFRE
    g.set(cx, Y0 + 1, cz, "minecraft:stone_bricks")
    if not ruine:
        g.set(cx, cy, cz, "silooo:coffre_vente")
    for (px, pz) in [(x0, z0), (x1, z0), (x0, z1), (x1, z1)]:
        if ruine and (px, pz) == (x1, z1):
            continue  # un poteau tombé
        g.fill(px, Y0 + 1, pz, px, Y0 + 3, pz, "minecraft:spruce_fence")
    # toile rayée rouge et crème, la seule touche de rouge de l'île
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if ruine and (x == x1 or (x == x1 - 1 and z < z1) or (x, z) == (x0 + 1, z0)):
                continue  # la toile a cédé du côté est ; elle tient encore sous la plaque
            g.set(x, Y0 + 4, z, "minecraft:red_wool" if (x - x0) % 2 == 0 else "minecraft:white_wool")
    # la plaque CARRIÈRE, face au joueur
    g.set(x0 + 1, Y0 + 5, z1, "silooo:plaque_emaillee[gauche]")
    g.set(x0 + 2, Y0 + 5, z1, "silooo:plaque_emaillee[milieu]")
    g.set(x0 + 3, Y0 + 5, z1, "silooo:plaque_emaillee[droite]")
    if not ruine:
        g.set(x0 + 1, Y0 + 3, z1, "minecraft:lantern")
        g.set(x1 - 1, Y0 + 3, z1, "minecraft:lantern")
    else:
        g.set(x1, Y0 + 1, z1 + 1, "minecraft:spruce_slab")  # le poteau, couché au pied de l'étal


# --- Bureau du contremaître -----------------------------------------------------------------------------------
BX0, BZ0, BX1, BZ1 = 3, 13, 9, 19
PORTE_Z = 16


def build_office(g, ruine):
    g.fill(BX0 - 1, Y0 + 1, BZ0 - 1, BX1 + 1, Y0 + 8, BZ1 + 1, None)
    g.fill(BX0, Y0, BZ0, BX1, Y0, BZ1, "minecraft:cobblestone")
    g.fill(BX0 + 1, Y0, BZ0 + 1, BX1 - 1, Y0, BZ1 - 1, "minecraft:spruce_planks")
    for y in range(Y0 + 1, Y0 + 4):
        for x in range(BX0, BX1 + 1):
            for z in range(BZ0, BZ1 + 1):
                ex, ez = x in (BX0, BX1), z in (BZ0, BZ1)
                if ex and ez:
                    g.set(x, y, z, "minecraft:dark_oak_log")
                elif ex or ez:
                    g.set(x, y, z, "minecraft:cobblestone" if y == Y0 + 1 else "minecraft:spruce_planks")
    windows = [(5, BZ1), (7, BZ1), (6, BZ0), (BX1, 14), (BX1, 18), (BX0, 16)]
    for (x, z) in windows:
        # en ruine, les fenêtres sont condamnées par des planches : visible de loin
        g.set(x, Y0 + 2, z, "minecraft:oak_planks" if ruine else "minecraft:glass_pane")
    if not ruine:
        g.set(BX1, Y0 + 1, PORTE_Z, "minecraft:spruce_door")
        g.set(BX1, Y0 + 2, PORTE_Z, "minecraft:spruce_door")
        g.set(BX1 + 1, Y0 + 3, PORTE_Z, "minecraft:oak_wall_sign")
    else:
        g.set(BX1, Y0 + 1, PORTE_Z, None)
        g.set(BX1, Y0 + 2, PORTE_Z, None)
    # toit à deux pans : chaque pas a une marche d'escalier au bord, montant vers le faîtage
    roof = {Y0 + 4: [(BZ0 - 1, "sud"), (BZ0, None), (BZ1, None), (BZ1 + 1, "nord")],
            Y0 + 5: [(BZ0 + 1, "sud"), (BZ0 + 2, None), (BZ1 - 2, None), (BZ1 - 1, "nord")],
            Y0 + 6: [(BZ0 + 3, None)]}
    for y, rangs in roof.items():
        for x in range(BX0 - 1, BX1 + 2):
            for z, monte in rangs:
                if ruine and z >= BZ0 + 3 and BX0 + 2 <= x <= BX1 - 1:  # le pan sud, côté pont, s'est effondré
                    continue
                g.set(x, y, z, f"minecraft:spruce_stairs[monte={monte}]" if monte else "minecraft:spruce_planks")
    for gx in (BX0, BX1):
        g.fill(gx, Y0 + 4, BZ0 + 1, gx, Y0 + 4, BZ1 - 1, "minecraft:spruce_planks")
        g.set(gx, Y0 + 5, BZ0 + 3, "minecraft:spruce_planks")
    # poêle à bois et cheminée en pierre, côté nord : la fumée du bureau
    cx, cz = BX0 + 1, BZ0 + 1
    haut = Y0 + (6 if ruine else 8)
    g.fill(cx, Y0 + 1, cz, cx, haut, cz, "minecraft:cobblestone")
    g.set(BX0 + 1, Y0 + 1, BZ0 + 2, "minecraft:bookshelf")
    g.set(BX0 + 2, Y0 + 1, BZ0 + 1, "minecraft:crafting_table")
    g.set(BX0 + 1, Y0 + 1, BZ1 - 1, "minecraft:barrel")
    if ruine:
        for (x, z) in [(BX0 + 3, BZ1 - 1), (BX0 + 4, BZ1 - 2), (BX0 + 2, BZ1 - 2)]:
            g.set(x, Y0 + 1, z, "minecraft:spruce_slab")  # le toit tombé dans la pièce
        g.set(BX0 + 1, Y0 + 3, BZ1 - 1, "minecraft:cobweb")


# --- Cour, chemins, décor ---------------------------------------------------------------------------------------
ENTREE = (14, 17)  # piliers de l'entrée, là où le pont arrivera


def chemins():
    principal = {(x, z) for z in range(18, 30) for x in (15, 16)}
    bureau = {(x, PORTE_Z) for x in range(BX1 + 1, ETAL[0])}
    moulin = {(x, 18) for x in range(17, 20)} | {(19, z) for z in range(12, 18)} | {(x, 12) for x in range(20, MOU[0])}
    return principal, bureau | moulin


def build_yard(g, ruine):
    principal, secondaires = chemins()
    for (x, z) in principal | secondaires:
        if g.get(x, Y0, z) == "minecraft:grass_block" and inside(x, z):
            # en ruine, l'herbe reprend les chemins secondaires et le bout du chemin principal
            if ruine and ((x, z) in secondaires or z >= 25):
                continue
            g.set(x, Y0, z, "minecraft:dirt_path")
    for px in ENTREE:
        ez = max(z for z in range(D) if inside(px, z)) - 1
        g.fill(px, Y0 + 1, ez, px, Y0 + 2, ez, "minecraft:cobblestone")
        if not ruine:
            g.set(px, Y0 + 3, ez, "minecraft:lantern")
        elif px == ENTREE[1]:
            g.set(px, Y0 + 2, ez, "minecraft:mossy_cobblestone")
    # pierre extraite, prête à partir, à l'est de la cour
    for (x, z, hh) in [(21, 19, 2), (22, 19, 1), (21, 20, 1), (20, 19, 1)]:
        for k in range(hh):
            g.set(x, Y0 + 1 + k, z, "minecraft:cobblestone" if (x + z + k) % 2 else "minecraft:stone")
    g.set(22, Y0 + 1, 20, "minecraft:smooth_stone_slab")
    # tonneaux au pied du bureau
    g.set(BX1 + 1, Y0 + 1, BZ1, "minecraft:barrel")
    if not ruine:
        g.set(BX1 + 1, Y0 + 2, BZ1, "minecraft:barrel")
        g.set(BX1 + 1, Y0 + 1, BZ1 - 1, "minecraft:barrel")
    for (x, z) in [(18, 24), (12, 13)]:
        g.fill(x, Y0 + 1, z, x, Y0 + 2, z, "minecraft:spruce_fence")
        if not ruine:
            g.set(x, Y0 + 3, z, "minecraft:lantern")
    # trois sapins sur la colline ouest et près du bureau
    for (tx, tz, haut) in [(3, 7, 6), (5, 10, 4), (1, 13, 5)]:
        ty = g.top(tx, tz)
        if ty is None:
            continue
        g.fill(tx, ty + 1, tz, tx, ty + haut, tz, "minecraft:spruce_log")
        for k, rr in enumerate([2, 1, 2, 1, 1, 0][-haut:]):
            layer = ty + 2 + k
            for dx in range(-rr, rr + 1):
                for dz in range(-rr, rr + 1):
                    if abs(dx) + abs(dz) <= rr + (1 if rr == 2 else 0) and (dx or dz):
                        if g.get(tx + dx, layer, tz + dz) is None:
                            g.set(tx + dx, layer, tz + dz, "minecraft:spruce_leaves")
        g.set(tx, ty + haut + 1, tz, "minecraft:spruce_leaves")
    # herbes et fleurs
    for x in range(W):
        for z in range(D):
            ty = g.top(x, z)
            if ty is None or g.get(x, ty, z) != "minecraft:grass_block" or g.get(x, ty + 1, z) is not None:
                continue
            r = h(x, z, 51)
            if r < (0.13 if ruine else 0.06):
                g.set(x, ty + 1, z, "minecraft:short_grass")
            elif r < (0.15 if ruine else 0.09):
                g.set(x, ty + 1, z, "minecraft:dandelion" if h(x, z, 53) < 0.5 else "minecraft:poppy")
    if ruine:
        # un éboulis de gravier au pied du front, visible de loin
        for (x, z, hh) in [(9, 7, 3), (10, 7, 2), (9, 8, 2), (11, 8, 1), (8, 8, 1), (10, 9, 1), (12, 7, 1)]:
            for k in range(hh):
                g.set(x, Y0 + 1 + k, z, "minecraft:gravel")
        # la végétation reprend le coin ouest de la carrière
        for (x, z) in [(7, 9), (7, 10), (8, 10), (8, 11), (7, 11)]:
            g.set(x, Y0, z, "minecraft:grass_block")
            g.set(x, Y0 + 1, z, "minecraft:spruce_leaves" if (x + z) % 3 == 0 else "minecraft:short_grass")
        g.set(14, Y0 + 2, RAIL_Z, "minecraft:cobweb")


def build(ruine):
    g = Grid()
    build_island(g)
    build_bocard(g, ruine)
    build_rails(g, ruine)
    build_moulin(g, ruine)
    build_etal(g, ruine)
    build_tapis(g, ruine)
    build_office(g, ruine)
    build_yard(g, ruine)
    return g


# --- Entités ---------------------------------------------------------------------------------------------------
def pierres_trajet():
    """Centres des pierres le long du trajet, en coordonnées du monde : de l'auge au coffre."""
    x0, z0, x1, z1 = BOC
    mx = (x0 + x1) // 2
    pts = [[mx + 0.5, Y0 + 2 + 0.14, PILON_Z + 0.5]]
    for (x, y, z) in tapis_path():
        pts.append([x + 0.5, y + 0.375 + 0.14, z + 0.5])
    pts.append([COFFRE[0] + 0.9, TAPIS_Y + 0.515, COFFRE[2] + 0.5])
    pts.append([COFFRE[0] + 0.5, COFFRE[1] + 0.9, COFFRE[2] + 0.5])
    return pts


def entites(ruine):
    x0, z0, x1, z1 = BOC
    return {
        "ailes": {"moyeu": MOYEU, "rayon": RAYON_AILES, "tourne": not ruine, "angle": 0.5 if ruine else 0.0,
                  "ailes": [1] if ruine else [0, 1, 2, 3], "toile": not ruine,
                  "tombee": {"x": 26.0, "y": Y0 + 1.1, "z": 17.0} if ruine else None},
        "pilons": {"x": [x0 + 1, x0 + 2, x0 + 3], "z": PILON_Z, "bas": Y0 + 2, "arbre": Y0 + 6, "frappe": not ruine,
                   "tombe": {"x": 17, "y": Y0 + 1, "z": 11} if ruine else None},
        "wagonnets": [{"x": 11, "y": Y0 + 1, "z": 10, "renverse": True}] if ruine else
                     [{"x": 9, "y": Y0 + 1, "z": RAIL_Z, "renverse": False}, {"x": 18, "y": Y0 + 6, "z": RAIL_Z, "renverse": False}],
        "charrette": {"x": 21, "y": Y0 + 1, "z": 23, "renverse": ruine},
        "pierres": None if ruine else pierres_trajet(),
        "coffre": None if ruine else list(COFFRE),
        "salaries": [] if ruine else [{"x": 10, "y": Y0 + 1, "z": 7, "geste": "pioche"}, {"x": 18, "y": Y0 + 1, "z": 10, "geste": "pelle"}],
    }


def emetteurs(ruine):
    """Où les particules et les sons partent, en coordonnées de la structure (centre des blocs)."""
    if ruine:
        return {"vent": [CX, Y0 + 6, CZ]}
    x0, z0, x1, z1 = BOC
    return {
        "fumee_poele": [BX0 + 1.5, Y0 + 9.0, BZ0 + 1.5],
        "poussiere_bocard": [[x + 0.5, Y0 + 2.1, PILON_Z + 0.5] for x in (x0 + 1, x0 + 2, x0 + 3)],
        "eclats_pioche": [10.5, Y0 + 1.4, 6.5],
        "vente_coffre": [COFFRE[0] + 0.5, COFFRE[1] + 1.0, COFFRE[2] + 0.5],
        "moulin_moyeu": list(MOYEU),
        "tapis": [[x + 0.5, y + 0.5, z + 0.5] for (x, y, z) in tapis_path()[::3]],
        "vent": [CX, Y0 + 6, CZ],
    }


def surface():
    return Y0 + 1


def frame_to_world(avant, droite, haut):
    return [CX - droite, surface() + haut, CZ + avant]


PLANS = [
    {"duree": 5, "depart": {"avant": 30, "droite": -22, "haut": 22}, "arrivee": {"avant": 19, "droite": -14, "haut": 13},
     "regard": {"avant": -2, "droite": 0, "haut": 3}, "cadre": "L'île entière, vue du pont"},
    {"duree": 5, "depart": {"avant": 4, "droite": 2, "haut": 10}, "arrivee": {"avant": 1, "droite": 1, "haut": 8},
     "regard": {"avant": -10, "droite": 1, "haut": 3}, "cadre": "Le front de taille et l'éboulis"},
    {"duree": 5, "depart": {"avant": 10, "droite": -9, "haut": 5}, "arrivee": {"avant": 6, "droite": -6, "haut": 5},
     "regard": {"avant": -4, "droite": -7, "haut": 4}, "cadre": "Le bocard et le moulin"},
    {"duree": 5, "depart": {"avant": 10, "droite": -3, "haut": 3}, "arrivee": {"avant": 6.5, "droite": -1, "haut": 3},
     "regard": {"avant": 2.5, "droite": 0, "haut": 4.5}, "cadre": "L'étal de vente et la plaque"},
]
APPARITION = {"distance": 9, "oooscar": {"avant": 7, "droite": 2.5}}
TEXTES = {
    "qui": "Oooscar",
    "plans": ["Bienvenue chez toi, patron.", "Tu hérites d'une vieille carrière abandonnée.",
              "Les murs tiennent encore, à peu près.", "Remettons-la en état, je t'accompagne."],
    "passer": "Reste accroupi pour passer.",
}
# L'ordre de la remise en route : le constructeur repose l'usine par lots (addon/src/monde/constructeur.ts).
LOTS = [
    {"nom": "Déblaiement", "boite": None},
    {"nom": "Rails", "boite": [RAIL_X[0] - 1, 6, RAIL_X[1], 11]},
    {"nom": "Bocard", "boite": [BOC[0] - 1, BOC[1] - 1, BOC[2] + 2, BOC[3] + 1]},
    {"nom": "Moulin", "boite": [MOU[0] - 1, MOU[1] - 1, MOU[2] + 1, MOU[3] + 1]},
    {"nom": "Tapis", "boite": None, "prefixe": "silooo:tapis"},
    {"nom": "Étal", "boite": [ETAL[0], ETAL[1], ETAL[2], ETAL[3] + 1]},
    {"nom": "Bureau", "boite": [BX0 - 1, BZ0 - 1, BX1 + 2, BZ1 + 1]},
    {"nom": "Cour", "boite": [0, 0, W - 1, D - 1]},
]


def main():
    textures = json.load(open(sys.argv[1])) if len(sys.argv) > 1 else {"textures": {}, "images": {}}
    ruine, marche = build(True), build(False)
    keys = sorted(set(ruine.b) | set(marche.b))

    def opaque(g, k):
        bid = g.b.get(k)
        return bid is not None and PALETTE[P[bid]][2] in OPAQUE

    flat = []
    for k in keys:
        x, y, z = k
        if y < Y0:
            nb = [(x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)]
            if all(opaque(ruine, n) and opaque(marche, n) for n in nb) and opaque(ruine, k) and opaque(marche, k):
                continue
        flat += [x, y, z, P[ruine.b[k]] if k in ruine.b else -1, P[marche.b[k]] if k in marche.b else -1]

    def counts(g):
        out = {}
        for bid in g.b.values():
            out[bid] = out.get(bid, 0) + 1
        return out

    ys = [k[1] for k in keys]
    sx, sy, sz = frame_to_world(APPARITION["distance"], 0, 0)
    ox, oy, oz = frame_to_world(APPARITION["oooscar"]["avant"], APPARITION["oooscar"]["droite"], 0)
    out = {
        "taille": {"x": W, "y": max(ys) - min(ys) + 1, "z": D},
        "yMin": min(ys), "yMax": max(ys), "sol": Y0, "centre": [CX, CZ],
        "palette": [{"id": p[0], "nom": p[1], "forme": p[2], "faces": p[3], "nouveau": p[4]} for p in PALETTE],
        "textures": textures["textures"], "images": textures["images"],
        "blocs": flat,
        "compte": {"ruine": counts(ruine), "marche": counts(marche)},
        "entites": {"ruine": entites(True), "marche": entites(False)},
        "emetteurs": {"ruine": emetteurs(True), "marche": emetteurs(False)},
        "reperes": [{"id": "apparition", "nom": "Apparition", "x": sx, "y": sy, "z": sz},
                    {"id": "oooscar", "nom": "Oooscar", "x": ox, "y": oy, "z": oz}],
        "lots": LOTS,
        "cinematique": {"plans": [dict(p, cam0=frame_to_world(**p["depart"]), cam1=frame_to_world(**p["arrivee"]),
                                       vise=frame_to_world(**p["regard"])) for p in PLANS],
                        "textes": TEXTES, "apparition": APPARITION,
                        "reglages": {"fondu": 0.25, "noir": 0.15, "retour": 0.35, "avanceSousTitre": 0.6, "sousTitreMin": 3}},
    }
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
