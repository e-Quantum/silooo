"""Écrit les .mcstructure de la Carrière (ruine, marche) depuis generer.py, et les vérifie.

    python3 -I structure.py <dossier de sortie> [--blocs mojang-blocks.json]
    python3 -I structure.py --verifier <dossier> [--blocs mojang-blocks.json]

Format Bedrock : NBT petit-boutiste, indices z le plus rapide, puis y, puis x. Les cases vides sont de l'air, pas du
vide de structure : charger « marche » par-dessus « ruine » efface les gravats. Les identifiants et les états sont ceux
de Bedrock 1.26.60 (bedrock-samples, metadata/vanilladata_modules/mojang-blocks.json) ; les blocs Silooo sont ceux de
textures-skins/behavior-pack/blocks/.
"""
import argparse
import importlib.util
import json
import struct
import sys
from pathlib import Path

ICI = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("generer", ICI / "generer.py")
G = importlib.util.module_from_spec(spec)
sys.argv, _argv = [sys.argv[0]], sys.argv
spec.loader.exec_module(G)
sys.argv = _argv

VERSION = (1 << 24) | (26 << 16) | (60 << 8)  # version des états de blocs : 1.26.60
NOMS = {"minecraft:dirt_path": "minecraft:grass_path", "minecraft:cobweb": "minecraft:web", "minecraft:oak_wall_sign": "minecraft:wall_sign"}
PLEINS = {"cube"}  # formes pleines : une barrière ou une vitre s'y relie
MONTE = {"est": 0, "ouest": 1, "sud": 2, "nord": 3}  # weirdo_direction : le côté haut de l'escalier


def etat(g, x, y, z, bid):
    """Le nom Bedrock et les états d'un bloc de la grille, d'après ses voisins quand il le faut."""
    forme = G.PALETTE[G.P[bid]][2]
    base, _, variante = bid.partition("[")
    variante = variante.rstrip("]")
    nom = NOMS.get(base, base)
    voisin = lambda dx, dy, dz: g.get(x + dx, y + dy, z + dz)
    plein = lambda b: b is not None and G.PALETTE[G.P[b]][2] in PLEINS

    if base == "minecraft:spruce_stairs":
        return nom, {"weirdo_direction": ("int", MONTE[variante.split("=")[1]]), "upside_down_bit": ("byte", 0), "minecraft:corner": ("string", "none")}
    if base in ("minecraft:spruce_slab", "minecraft:smooth_stone_slab"):
        return nom, {"minecraft:vertical_half": ("string", "bottom")}
    if base in ("minecraft:dark_oak_log", "minecraft:spruce_log"):
        bois = lambda b: b is not None and (b.endswith("_log") or b.startswith("silooo:engrenage"))
        if bois(voisin(0, 1, 0)) or bois(voisin(0, -1, 0)):
            axe = "y"
        elif bois(voisin(1, 0, 0)) or bois(voisin(-1, 0, 0)):
            axe = "x"
        elif bois(voisin(0, 0, 1)) or bois(voisin(0, 0, -1)):
            axe = "z"
        else:
            axe = "y"
        return nom, {"pillar_axis": ("string", axe)}
    if base in ("minecraft:spruce_fence", "minecraft:glass_pane"):
        lie = lambda b: b == base or plein(b)
        return nom, {f"minecraft:connection_{c}": ("byte", int(lie(voisin(*d)))) for c, d in
                     (("north", (0, 0, -1)), ("south", (0, 0, 1)), ("east", (1, 0, 0)), ("west", (-1, 0, 0)))}
    if base == "minecraft:spruce_leaves":
        return nom, {"persistent_bit": ("byte", 1), "update_bit": ("byte", 0)}
    if base == "minecraft:spruce_door":
        haut = voisin(0, -1, 0) == base
        return nom, {"door_hinge_bit": ("byte", 0), "minecraft:cardinal_direction": ("string", "east"), "open_bit": ("byte", 0), "upper_block_bit": ("byte", int(haut))}
    if base == "minecraft:lantern":
        dessous = voisin(0, -1, 0)
        posee = dessous is not None and G.PALETTE[G.P[dessous]][2] in PLEINS | {"post"}
        return nom, {"hanging": ("byte", int(not posee))}
    if base == "minecraft:barrel":
        return nom, {"facing_direction": ("int", 1), "open_bit": ("byte", 0)}
    if base == "minecraft:rail":
        if voisin(1, 1, 0) == base:
            sens = 2  # monte vers l'est
        elif voisin(-1, 1, 0) == base:
            sens = 3
        else:
            sens = 1  # est-ouest
        return nom, {"rail_direction": ("int", sens)}
    if base == "minecraft:pointed_dripstone":
        return nom, {"dripstone_thickness": ("string", "tip"), "hanging": ("byte", 1)}
    if base == "minecraft:oak_wall_sign":
        for (dx, dz), face in (((-1, 0), 5), ((1, 0), 4), ((0, -1), 3), ((0, 1), 2)):
            if plein(voisin(dx, 0, dz)):
                return nom, {"facing_direction": ("int", face)}
        return nom, {"facing_direction": ("int", 5)}
    # blocs Silooo
    if base == "silooo:engrenage_bois":
        return nom, {"silooo:grippe": ("byte", int(variante == "grippe"))}
    if base == "silooo:tapis_toile":
        return nom, {"silooo:dechire": ("byte", int(variante == "dechire")), "minecraft:cardinal_direction": ("string", sens_tapis(x, y, z))}
    if base == "silooo:plaque_emaillee":
        return nom, {"silooo:partie": ("string", variante), "minecraft:cardinal_direction": ("string", "south")}
    if base == "silooo:coffre_vente":
        return nom, {"minecraft:cardinal_direction": ("string", "south")}
    return nom, {}


def sens_tapis(x, y, z):
    """Le sens où avance la toile : vers le bloc suivant du trajet (textures-skins : north = vers le nord)."""
    chemin = G.tapis_path()
    i = chemin.index((x, y, z))
    nx, ny, nz = chemin[i + 1] if i + 1 < len(chemin) else (G.COFFRE[0], 0, G.COFFRE[2])
    dx, dz = nx - x, nz - z
    return "east" if dx > 0 else "west" if dx < 0 else "south" if dz > 0 else "north"


# --- NBT petit-boutiste ---------------------------------------------------------------------------------------
def s_nom(s):
    b = s.encode("utf-8")
    return struct.pack("<H", len(b)) + b


def tag(t, nom, charge):
    return bytes([t]) + s_nom(nom) + charge


def compound(items):
    return b"".join(items) + b"\x00"


def valeur(typ, v):
    return {"byte": (1, lambda v: struct.pack("<b", v)), "int": (3, lambda v: struct.pack("<i", v)),
            "string": (8, s_nom)}[typ][0], {"byte": lambda v: struct.pack("<b", v), "int": lambda v: struct.pack("<i", v), "string": s_nom}[typ](v)


def liste_int(valeurs):
    return bytes([3]) + struct.pack("<i", len(valeurs)) + struct.pack(f"<{len(valeurs)}i", *valeurs)


def ecrire(g, chemin):
    keys = list(g.b)
    xs, ys, zs = [k[0] for k in keys], [k[1] for k in keys], [k[2] for k in keys]
    x0, y0, z0 = 0, min(ys), 0
    sx, sy, sz = G.W, max(ys) - y0 + 1, G.D
    palette, index = [("minecraft:air", {})], {("minecraft:air", ()): 0}
    indices = []
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                bid = g.get(x, y + y0, z)
                if bid is None:
                    indices.append(0)
                    continue
                nom, st = etat(g, x, y + y0, z, bid)
                cle = (nom, tuple(sorted((k, v) for k, v in st.items())))
                if cle not in index:
                    index[cle] = len(palette)
                    palette.append((nom, st))
                indices.append(index[cle])
    blocs = []
    for nom, st in palette:
        etats = []
        for k, (typ, v) in sorted(st.items()):
            t, charge = valeur(typ, v)
            etats.append(tag(t, k, charge))
        blocs.append(compound([tag(8, "name", s_nom(nom)), tag(10, "states", compound(etats)), tag(3, "version", struct.pack("<i", VERSION))]))
    block_palette = bytes([10]) + struct.pack("<i", len(blocs)) + b"".join(blocs)
    racine = compound([
        tag(3, "format_version", struct.pack("<i", 1)),
        tag(9, "size", liste_int([sx, sy, sz])),
        tag(10, "structure", compound([
            tag(9, "block_indices", bytes([9]) + struct.pack("<i", 2) + liste_int(indices) + liste_int([-1] * len(indices))),
            tag(9, "entities", bytes([10]) + struct.pack("<i", 0)),
            tag(10, "palette", compound([tag(10, "default", compound([
                tag(9, "block_palette", block_palette),
                tag(10, "block_position_data", compound([])),
            ]))])),
        ])),
        tag(9, "structure_world_origin", liste_int([0, 0, 0])),
    ])
    Path(chemin).write_bytes(bytes([10]) + s_nom("") + racine)
    return (sx, sy, sz), len(palette), y0


# --- Relecture -------------------------------------------------------------------------------------------------
class Lecteur:
    def __init__(self, data):
        self.d, self.i = data, 0

    def lit(self, fmt):
        v = struct.unpack_from("<" + fmt, self.d, self.i)
        self.i += struct.calcsize("<" + fmt)
        return v[0] if len(v) == 1 else v

    def chaine(self):
        n = self.lit("H")
        s = self.d[self.i:self.i + n].decode("utf-8")
        self.i += n
        return s

    def charge(self, t):
        if t == 1:
            return self.lit("b")
        if t == 3:
            return self.lit("i")
        if t == 8:
            return self.chaine()
        if t == 9:
            et, n = self.lit("B"), self.lit("i")
            return [self.charge(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = self.lit("B")
                if tt == 0:
                    return out
                nom = self.chaine()  # le nom avant la valeur : Python évalue la droite d'une affectation en premier
                out[nom] = self.charge(tt)
        raise ValueError(f"type NBT inattendu {t}")

    def racine(self):
        t = self.lit("B")
        self.chaine()
        r = self.charge(t)
        if self.i != len(self.d):
            raise ValueError("octets en trop après la racine")
        return r


def verifier(chemin, blocs_mojang, blocs_silooo):
    try:
        r = Lecteur(Path(chemin).read_bytes()).racine()
    except (ValueError, struct.error, KeyError, UnicodeDecodeError, FileNotFoundError) as e:
        return None, 0, [f"illisible : {e}"]
    sx, sy, sz = r["size"]
    s = r["structure"]
    pal = s["palette"]["default"]["block_palette"]
    idx = s["block_indices"]
    erreurs = []
    if len(idx) != 2 or len(idx[0]) != sx * sy * sz or len(idx[1]) != sx * sy * sz:
        erreurs.append("taille des indices")
    if max(idx[0]) >= len(pal) or min(idx[0]) < 0:
        erreurs.append("indice hors palette")
    for b in pal:
        nom, st = b["name"], b["states"]
        attendu = blocs_mojang.get(nom) if nom.startswith("minecraft:") else blocs_silooo.get(nom)
        if attendu is None:
            erreurs.append(f"bloc inconnu {nom}")
            continue
        if set(st) != set(attendu):
            erreurs.append(f"{nom} : états {sorted(st)} au lieu de {sorted(attendu)}")
    return (sx, sy, sz), len(pal), erreurs


def charger_references(chemin_mojang):
    d = json.loads(Path(chemin_mojang).read_text())
    mojang = {b["name"]: [p["name"] for p in b.get("properties", [])] for b in d["data_items"]}
    silooo = {}
    for f in sorted(DOSSIER_SILOOO.glob("*.json")):
        desc = json.loads(f.read_text())["minecraft:block"]["description"]
        etats = list((desc.get("states") or {}).keys())
        for tr in (desc.get("traits") or {}).values():
            etats += tr.get("enabled_states", [])
        silooo[desc["identifier"]] = etats
    return mojang, silooo


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dossier")
    ap.add_argument("--verifier", action="store_true")
    ap.add_argument("--blocs", default=str(ICI / "blocs-bedrock-1.26.60.json"))
    ap.add_argument("--blocs-silooo", default=None, help="dossier des blocs Silooo (défaut : addon/pack/blocks du dépôt, ou textures-skins/ de l'atelier)")
    a = ap.parse_args()
    out = Path(a.dossier)
    out.mkdir(parents=True, exist_ok=True)
    global DOSSIER_SILOOO
    candidats = [Path(a.blocs_silooo)] if a.blocs_silooo else [ICI.parents[1] / "addon" / "pack" / "blocks", ICI.parent / "textures-skins" / "behavior-pack" / "blocks"]
    DOSSIER_SILOOO = next(c for c in candidats if c.is_dir())
    mojang, silooo = charger_references(a.blocs)
    code = 0
    for nom, ruine in (("carriere_ruine", True), ("carriere_marche", False)):
        f = out / f"{nom}.mcstructure"
        if not a.verifier:
            taille, n, y0 = ecrire(G.build(ruine), f)
            print(f"{f.name} : {taille[0]} × {taille[1]} × {taille[2]}, {n} états de blocs, décalage y {y0}, {f.stat().st_size} octets")
        else:
            import tempfile
            with tempfile.TemporaryDirectory() as tmp:
                attendu = Path(tmp) / f.name
                ecrire(G.build(ruine), attendu)
                if not f.exists() or attendu.read_bytes() != f.read_bytes():
                    print(f"{f.name} : diffère de ce qu'écrit generer.py ; relance « python3 -I structure.py {a.dossier} »")
                    code = 1
        taille, n, err = verifier(f, mojang, silooo)
        print(f"  relu : {taille}, {n} états, {'aucune erreur' if not err else ' ; '.join(err)}")
        code |= bool(err)
    sys.exit(code)


if __name__ == "__main__":
    main()
