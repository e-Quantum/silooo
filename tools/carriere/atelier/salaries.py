"""Les salariés de la Carrière : le modèle, les trois skins, l'entité cliente et son contrôleur de rendu, d'un seul coup,
pour que chaque cube trouve ses pixels (comme depot/tools/generer_oooscar.mjs).

- resource-pack/models/entity/salarie.geo.json : geometry.silooo.salarie (pioche) et geometry.silooo.salarie.pelle
  (mêmes os, mêmes cubes, sauf l'outil). Os du contrat : root > corps > (tete > chapeau), (bras_g), (bras_d > outil),
  (jambe_g), (jambe_d). Pivot de chaque os à son articulation. 1 bloc = 16. L'avant est la face north (-z).
- design/skins/salarie_<rareté>.png (sources) et resource-pack/textures/entity/salarie_<rareté>.png (copies), 64 × 64.
  Corps, bras et jambes rangés comme une peau de joueur (tête en 0,0, corps en 16,16, bras droit en 40,16, jambe
  droite en 0,16, bras gauche en 32,48, jambe gauche en 16,48), pour qu'on puisse les reprendre dans Blockbench.
- resource-pack/entity/salarie.entity.json et resource-pack/render_controllers/salarie.render_controllers.json.
  Os `charge` (fils de corps, vide) pour porter ; locator `pointe_outil` dans `outil`. Au repos, le manche part vers
  l'avant depuis la main droite, comme un objet tenu en vanilla.

Tenue de carrier de l'époque 1 : casquette de drap, foulard, chemise écrue aux manches retroussées, tablier de cuir,
pantalon de toile sombre, brodequins. La rareté ne change que deux touches : le foulard et le ruban de la casquette,
gris (commun), bleu (rare), or (légendaire). Couleurs : jetons de depot/design/tokens.css, par leur nom.

Usage : python3 -I salaries.py
"""
import json
from pathlib import Path

from PIL import Image

ICI = Path(__file__).resolve().parent
JETONS = {
    "fonte-900": "#1e1c1a", "fonte-700": "#3a3633", "fonte-500": "#5b5853", "fonte-300": "#8c8780", "acier-100": "#bab5ac",
    "email-creme": "#efe3c6", "papier": "#fff6de", "papier-ombre": "#c9ba98",
    "email-bleu": "#2d4e7a", "email-bleu-clair": "#5c84b8", "laiton": "#c8962e", "laiton-clair": "#f0cf72", "or-lingot": "#f2c440",
    "email-rouge-sombre": "#7a2019",
    "bois-clair": "#9a6c43", "bois": "#6b4a2e", "bois-sombre": "#46301e",
}
LEG = {
    "0": "fonte-900", "1": "fonte-700", "2": "fonte-500", "3": "fonte-300", "4": "acier-100",
    "c": "email-creme", "p": "papier", "o": "papier-ombre",
    "w": "bois-sombre", "b": "bois", "B": "bois-clair", "s": "email-rouge-sombre",
}
RARETES = {  # X : ton principal de la touche, x : son ombre
    "commun": {"X": "acier-100", "x": "fonte-300"},
    "rare": {"X": "email-bleu-clair", "x": "email-bleu"},
    "legendaire": {"X": "or-lingot", "x": "laiton"},
}
# La peau : bois clair, ombre en bois, lumière en papier ombré. Une seule carnation pour les trois raretés
# (à valider par Kr : d'autres carnations seraient des skins en plus, pas des raretés).
PEAU, PEAU_OMBRE, PEAU_LUMIERE = "B", "b", "o"

# ------------------------------------------------------------------ les os et les cubes
OS = [
    {"nom": "root", "pivot": [0, 0, 0]},
    {"nom": "corps", "parent": "root", "pivot": [0, 12, 0]},
    {"nom": "tete", "parent": "corps", "pivot": [0, 24, 0]},
    {"nom": "chapeau", "parent": "tete", "pivot": [0, 30, 0]},
    {"nom": "bras_g", "parent": "corps", "pivot": [5, 22, 0]},
    {"nom": "bras_d", "parent": "corps", "pivot": [-5, 22, 0]},
    {"nom": "outil", "parent": "bras_d", "pivot": [-6, 13, -1]},
    {"nom": "charge", "parent": "corps", "pivot": [0, 17, -4]},  # devant le ventre, vide : sert à porter
    {"nom": "jambe_g", "parent": "root", "pivot": [2, 12, 0]},
    {"nom": "jambe_d", "parent": "root", "pivot": [-2, 12, 0]},
]
# id, os, origine, taille, coin du gabarit sur la planche (ou faces à la main), peintre
CUBES = [
    ("tete", "tete", [-4, 24, -4], [8, 8, 8], (0, 0), "tete"),
    ("casquette", "chapeau", [-4.5, 30, -4.5], [9, 3, 9], (0, 32), "casquette"),
    ("visiere", "chapeau", [-4.5, 30, -7.5], [9, 1, 3], (36, 32), "visiere"),
    ("corps", "corps", [-4, 12, -2], [8, 12, 4], (16, 16), "corps"),
    ("bras_d", "bras_d", [-8, 12, -2], [4, 12, 4], (40, 16), "bras"),
    ("bras_g", "bras_g", [4, 12, -2], [4, 12, 4], (32, 48), "bras"),
    ("jambe_d", "jambe_d", [-4, 0, -2], [4, 12, 4], (0, 16), "jambe"),
    ("jambe_g", "jambe_g", [0, 0, -2], [4, 12, 4], (16, 48), "jambe"),
]
MANCHE = ("manche", "outil", [-6.5, 12.5, -13], [1, 1, 12],
          {"east": (0, 44, 12, 1), "west": (0, 45, 12, 1), "up": (56, 16, 1, 12), "down": (57, 16, 1, 12),
           "north": (58, 16, 1, 1), "south": (58, 17, 1, 1)}, "manche")
PIOCHE = [
    ("fer_pioche", "outil", [-7, 9, -14], [2, 8, 2], (0, 48), "fer"),
    ("pointe_haut", "outil", [-6.5, 17, -13.5], [1, 1, 1], {f: (8, 48, 1, 1) for f in ("north", "south", "east", "west", "up", "down")}, "pointe"),
    ("pointe_bas", "outil", [-6.5, 8, -13.5], [1, 1, 1], {f: (8, 48, 1, 1) for f in ("north", "south", "east", "west", "up", "down")}, "pointe"),
]
PELLE = [("lame", "outil", [-8, 12.5, -19], [4, 1, 6], (36, 36), "lame")]
FACES = ["north", "south", "east", "west", "up", "down"]


def gabarit(coin, taille):
    """Les six rectangles d'un cube, rangés comme l'UV en boîte de Bedrock (même ordre que generer_oooscar.mjs)."""
    x, y = coin
    sx, sy, sz = taille
    return {"up": (x + sz, y, sx, sz), "down": (x + sz + sx, y, sx, sz), "east": (x, y + sz, sz, sy),
            "north": (x + sz, y + sz, sx, sy), "west": (x + sz + sx, y + sz, sz, sy), "south": (x + 2 * sz + sx, y + sz, sx, sy)}


def faces_de(cube):
    _, _, _, taille, place, _ = cube
    return place if isinstance(place, dict) else gabarit(place, taille)


# ------------------------------------------------------------------ les peintres : une grille de lettres par face
def g(*lignes):
    return list(lignes)


def plein(w, h, ch):
    return [ch * w for _ in range(h)]


def tete(face, w, h):
    if face == "north":
        return g("wwwwwwww",
                 "wwwwwwww",
                 "wBBBBBBw",
                 "wp0BB0pw",
                 "BoBbbBBb",
                 "BBwwwwBb",
                 "BBBBBBBb",
                 "bbbbbbbb")
    if face in ("east", "west"):
        return g("wwwwwwww", "wwwwwwww", "wwwwwwww", "wBBBBBww", "wBBbbBBw", "wBBbbBBw", "BBBBBBBb", "bbbbbbbb")
    if face == "south":
        return g("wwwwwwww", "wwwwwwww", "wwbwwwww", "wwwwwbww", "wwwwwwww", "wbwwwwww", "wwwwwwww", "bbbbbbbb")
    if face == "up":
        return plein(8, 8, "w")
    return plein(8, 8, "b")


def casquette(face, w, h):
    if face == "up":
        lignes = []
        for y in range(h):
            lignes.append("".join("3" if x + y < 4 else "1" if (x == 4 and y == 4) else "1" if x + y > 13 else "2" for x in range(w)))
        return lignes
    if face == "down":
        return plein(w, h, "1")
    return ["3" * w, "2" * w, "X" * w]  # côtés : lumière, drap, ruban de rareté


def visiere(face, w, h):
    if face == "up":
        return ["2" * w] + ["1" * w] * (h - 1)
    return plein(w, h, "0" if face == "down" else "1")


def corps(face, w, h):
    if face == "north":
        return g("XXXXXXXX",
                 "cXXxxXXc",
                 "cwbXxbwc",
                 "cwBbbbwc",
                 "cwBbbbwc",
                 "cwBbbbwc",
                 "cwBbbbwc",
                 "cwBwwbwc",
                 "0wBbbbw0",
                 "1wBbbbw1",
                 "1wBbbbw1",
                 "1wwwwww1")
    if face == "south":
        return g("XXXXXXXX",
                 "wXXXXXXw",
                 "cwxXXxwc",
                 "ccwxxwcc",
                 "cccwwccc",
                 "ccwccwcc",
                 "cwccccwc",
                 "wcccccow",
                 "00wbbw00",
                 "111bb111",
                 "11111111",
                 "11111111")
    if face in ("east", "west"):
        return g("XXXX", "xxxx", "cccc", "cccc", "cccc", "cocc", "cccc", "ccco", "0000", "1111", "1111", "1111")
    if face == "up":
        return g("ccXXXXcc", "cXXXXXXc", "cXXXXXXc", "ccXXXXcc")
    return plein(w, h, "1")


def bras(face, w, h):
    if face == "up":
        return plein(w, h, "c")
    if face == "down":
        return plein(w, h, PEAU_OMBRE)
    manche = "ccco" if face in ("north", "south") else "cccc"
    return [manche] * 6 + ["pppp", "oooo"] + [PEAU_LUMIERE + PEAU * 3] + [PEAU * 4] * 2 + [PEAU_OMBRE * 4]


def jambe(face, w, h):
    if face == "north":
        return g("bbbb", "Bbbb", "Bbbb", "Bbbw", "wwww", "1112", "2111", "1111", "1111", "wwww", "wbww", "0000")
    if face == "up":
        return plein(w, h, "1")
    if face == "down":
        return plein(w, h, "0")
    return g("1111", "1112", "1111", "1111", "2111", "1111", "1111", "1121", "1111", "wwww", "wwww", "0000")


def manche(face, w, h):
    return plein(w, h, {"up": "B", "east": "B", "west": "b", "down": "b"}.get(face, "w"))


def fer(face, w, h):
    if face == "up":
        return plein(w, h, "3")
    if face == "down":
        return plein(w, h, "1")
    return ["3" * w] + ["2" * w] * (h - 2) + ["1" * w]


def pointe(face, w, h):
    return plein(w, h, "3")


def lame(face, w, h):
    if face == "up":
        return ["3" * w] + ["2" * w] * (h - 2) + ["1" * w]
    return plein(w, h, "1")


PEINTRES = {"tete": tete, "casquette": casquette, "visiere": visiere, "corps": corps, "bras": bras, "jambe": jambe,
            "manche": manche, "fer": fer, "pointe": pointe, "lame": lame}
TOUS = CUBES + [MANCHE] + PIOCHE + PELLE


def couleur(nom):
    v = JETONS[nom]
    return (int(v[1:3], 16), int(v[3:5], 16), int(v[5:7], 16), 255)


def peindre(rarete):
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    p = im.load()
    leg = {**LEG, **RARETES[rarete]}
    deja = {}
    for cube in TOUS:
        for face, (fx, fy, w, h) in faces_de(cube).items():
            grille = PEINTRES[cube[5]](face, w, h)
            assert len(grille) == h and all(len(l) == w for l in grille), (cube[0], face, w, h, grille)
            for y in range(h):
                for x in range(w):
                    pos = (fx + x, fy + y)
                    col = couleur(leg[grille[y][x]])
                    assert deja.get(pos, col) == col, f"{cube[0]} {face} recouvre un autre cube en {pos}"
                    deja[pos] = col
                    p[pos] = col
    return im


# ------------------------------------------------------------------ le modèle
def nombre(v):
    return int(v) if float(v).is_integer() else v


# Le bout qui frappe : la pointe basse du fer de pioche (au repos, manche vers l'avant, elle regarde le sol ; quand le
# bras s'abat, c'est elle qui mène), le bord avant de la lame de pelle.
POINTES = {"pioche": [-6, 8, -13], "pelle": [-6, 13, -19]}


def geometrie(identifiant, outil, pointe):
    cubes = CUBES + [MANCHE] + outil
    bones = []
    for os_ in OS:
        bone = {"name": os_["nom"]}
        if "parent" in os_:
            bone["parent"] = os_["parent"]
        bone["pivot"] = os_["pivot"]
        liste = [{"origin": [nombre(v) for v in c[2]], "size": c[3],
                  "uv": {f: {"uv": [r[0], r[1]], "uv_size": [r[2], r[3]]} for f, r in faces_de(c).items()}}
                 for c in cubes if c[1] == os_["nom"]]
        if liste:
            bone["cubes"] = liste
        if os_["nom"] == "outil":
            bone["locators"] = {"pointe_outil": pointe}
        bones.append(bone)
    return {"description": {"identifier": identifiant, "texture_width": 64, "texture_height": 64,
                            "visible_bounds_width": 3, "visible_bounds_height": 3, "visible_bounds_offset": [0, 1, 0]},
            "bones": bones}


ANIMATIONS = ["inactif", "marche", "piocher", "pelleter", "porter", "saluer"]

if __name__ == "__main__":
    rp = ICI / "resource-pack"
    for d in ("models/entity", "textures/entity", "entity", "render_controllers"):
        (rp / d).mkdir(parents=True, exist_ok=True)
    (ICI / "design" / "skins").mkdir(parents=True, exist_ok=True)
    geo = {"format_version": "1.12.0", "minecraft:geometry": [geometrie("geometry.silooo.salarie", PIOCHE, POINTES["pioche"]),
                                                               geometrie("geometry.silooo.salarie.pelle", PELLE, POINTES["pelle"])]}
    (rp / "models/entity/salarie.geo.json").write_text(json.dumps(geo, indent=2) + "\n")
    for r in RARETES:
        im = peindre(r)
        im.save(ICI / "design" / "skins" / f"salarie_{r}.png")
        im.save(rp / "textures/entity" / f"salarie_{r}.png")
    entite = {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
        "identifier": "silooo:salarie",
        "materials": {"default": "entity_alphatest"},
        "textures": {r: f"textures/entity/salarie_{r}" for r in RARETES},
        "geometry": {"default": "geometry.silooo.salarie", "pelle": "geometry.silooo.salarie.pelle"},
        # noms de la session animations (animations/propositions/salarie.entity.json) : le contrôleur suit la
        # propriété silooo:geste
        "animations": {**{n: f"animation.silooo_salarie.{n}" for n in ANIMATIONS}, "geste": "controller.animation.silooo_salarie.geste"},
        # noms courts que les animations déclenchent ; événements de sons/resource-pack/sounds/sound_definitions.json
        "sound_effects": {"pioche": "silooo.carriere.pioche", "pelle": "silooo.carriere.pelle"},
        "scripts": {"animate": ["geste"]},
        "particle_effects": {"eclats": "silooo:eclats_pierre"},
        "render_controllers": ["controller.render.silooo_salarie"],
    }}}
    (rp / "entity/salarie.entity.json").write_text(json.dumps(entite, indent=2, ensure_ascii=False) + "\n")
    controleur = {"format_version": "1.8.0", "render_controllers": {"controller.render.silooo_salarie": {
        "arrays": {"textures": {"Array.raretes": [f"Texture.{r}" for r in RARETES]}},
        # la pelle pour pelleter, la pioche pour tout le reste ; la rareté par minecraft:variant (0, 1, 2)
        "geometry": "query.property('silooo:geste') == 'pelleter' ? Geometry.pelle : Geometry.default",
        "materials": [{"*": "Material.default"}],
        "textures": ["Array.raretes[query.variant]"],
    }}}
    (rp / "render_controllers/salarie.render_controllers.json").write_text(json.dumps(controleur, indent=2) + "\n")
    print("salariés :", len(TOUS), "cubes, 3 skins 64 × 64")
