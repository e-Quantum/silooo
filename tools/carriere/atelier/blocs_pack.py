"""Écrit les fichiers de blocs Silooo des deux packs, à partir des clés de textures.py.

- behavior-pack/blocks/<bloc>.json : un fichier par bloc (identifiant, états, faces, rotation).
- resource-pack/models/blocks/<bloc>.geo.json : les deux blocs qui ne sont pas des cubes pleins (coffre, tapis).

Les noms de texture sont les clés de resource-pack/textures/terrain_texture.json, préfixées « silooo_ ».
Orientation : l'état minecraft:cardinal_direction du trait minecraft:placement_direction. La rotation de chaque valeur
suit l'exemple de Microsoft Learn (north 0°, west 90°, south 180°, east -90°). Rien n'est vérifié dans un vrai client.

Usage : python3 -I blocs_pack.py
"""
import json
from pathlib import Path

ICI = Path(__file__).resolve().parent
BP = ICI / "behavior-pack" / "blocks"
RP = ICI / "resource-pack" / "models" / "blocks"
BP.mkdir(parents=True, exist_ok=True)
RP.mkdir(parents=True, exist_ok=True)
FORMAT_BLOC = "1.21.60"
DIRECTION = "minecraft:cardinal_direction"
ROTATIONS = {"north": 0, "west": 90, "south": 180, "east": -90}


def mat(texture, rendu="opaque"):
    return {"texture": f"silooo_{texture}", "render_method": rendu}


def rotations():
    return [{"condition": f"q.block_state('{DIRECTION}') == '{d}'",
             "components": {"minecraft:transformation": {"rotation": [0, r, 0]}}} for d, r in ROTATIONS.items()]


def bloc(identifiant, composants, etats=None, traits=None, permutations=None):
    description = {"identifier": f"silooo:{identifiant}", "menu_category": {"category": "construction"}}
    if etats:
        description["states"] = etats
    if traits:
        description["traits"] = traits
    corps = {"description": description, "components": {"minecraft:destructible_by_mining": {"seconds_to_destroy": 1.5}, **composants}}
    if permutations:
        corps["permutations"] = permutations
    (BP / f"{identifiant}.json").write_text(json.dumps({"format_version": FORMAT_BLOC, "minecraft:block": corps}, indent=2, ensure_ascii=False) + "\n")


def modele(identifiant, origine, taille, uv):
    geo = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.silooo.{identifiant}", "texture_width": 16, "texture_height": 16},
        "bones": [{"name": "bloc", "pivot": [0, 0, 0], "cubes": [{"origin": origine, "size": taille, "uv": uv}]}],
    }]}
    (RP / f"{identifiant}.geo.json").write_text(json.dumps(geo, indent=2) + "\n")


TOUT = {"uv": [0, 0], "uv_size": [16, 16]}

# Trémie : un cube plein.
bloc("tremie_bois", {"minecraft:material_instances": {"*": mat("tremie_bois"), "up": mat("tremie_bois_dessus")}})

# Rouet : un cube plein, la roue animée sur les quatre côtés, le dessus en bûche de chêne noir. État grippé pour la ruine.
bloc("engrenage_bois",
     {"minecraft:material_instances": {"*": mat("engrenage_bois"), "up": mat("chene_noir_dessus"), "down": mat("chene_noir_dessus")}},
     etats={"silooo:grippe": [False, True]},
     permutations=[{"condition": "q.block_state('silooo:grippe')",
                    "components": {"minecraft:material_instances": {"*": mat("engrenage_bois_grippe"), "up": mat("chene_noir_dessus"),
                                                                    "down": mat("chene_noir_dessus")}}}])

# Tapis : 6 pixels de haut. À l'orientation north (rotation 0), la toile défile vers le nord de la texture, donc vers le
# nord du monde. Le chef pose chaque bloc avec minecraft:cardinal_direction dans le sens des pierres. Le flanc prend les
# lignes 5 à 10 de tapis_toile_cote.
modele("tapis_toile", [-8, 0, -8], [16, 6, 16], {
    "up": TOUT, "down": TOUT,
    **{f: {"uv": [0, 5], "uv_size": [16, 6]} for f in ("north", "south", "east", "west")},
})
TAPIS = {"*": mat("tapis_toile_cote"), "up": mat("tapis_toile"), "down": mat("planches_sapin")}
bloc("tapis_toile",
     {"minecraft:geometry": "geometry.silooo.tapis_toile", "minecraft:material_instances": TAPIS,
      "minecraft:collision_box": {"origin": [-8, 0, -8], "size": [16, 6, 16]},
      "minecraft:selection_box": {"origin": [-8, 0, -8], "size": [16, 6, 16]}, "minecraft:light_dampening": 0},
     etats={"silooo:dechire": [False, True]},
     traits={"minecraft:placement_direction": {"enabled_states": [DIRECTION], "y_rotation_offset": 0}},
     permutations=rotations() + [{"condition": "q.block_state('silooo:dechire')",
                                  "components": {"minecraft:material_instances": {
                                      "*": mat("tapis_toile_cote", "alpha_test"), "up": mat("tapis_toile_dechire", "alpha_test"),
                                      "down": mat("planches_sapin", "alpha_test")}}}])

# Coffre de vente : 14 pixels de côté, comme un coffre ; la face (jeton de Donar) au nord, tournée vers le joueur qui le
# pose (y_rotation_offset 180). La texture entière couvre chaque face, comme dans l'aperçu.
modele("coffre_vente", [-7, 0, -7], [14, 14, 14], {f: TOUT for f in ("north", "south", "east", "west", "up", "down")})
bloc("coffre_vente",
     {"minecraft:geometry": "geometry.silooo.coffre_vente",
      "minecraft:material_instances": {"*": mat("coffre_vente"), "north": mat("coffre_vente_face"), "up": mat("coffre_vente_dessus"),
                                       "down": mat("planches_sapin")},
      "minecraft:collision_box": {"origin": [-7, 0, -7], "size": [14, 14, 14]},
      "minecraft:selection_box": {"origin": [-7, 0, -7], "size": [14, 14, 14]}, "minecraft:light_dampening": 0},
     traits={"minecraft:placement_direction": {"enabled_states": [DIRECTION], "y_rotation_offset": 180}},
     permutations=rotations())

# Plaque CARRIÈRE : trois blocs de planches de sapin, la plaque sur la face nord ; l'état silooo:partie dit quel tiers.
bloc("plaque_emaillee",
     {"minecraft:material_instances": {"*": mat("planches_sapin"), "north": mat("plaque_emaillee_gauche")}},
     etats={"silooo:partie": ["gauche", "milieu", "droite"]},
     traits={"minecraft:placement_direction": {"enabled_states": [DIRECTION], "y_rotation_offset": 180}},
     permutations=rotations() + [{"condition": f"q.block_state('silooo:partie') == '{p}'",
                                  "components": {"minecraft:material_instances": {"*": mat("planches_sapin"),
                                                                                  "north": mat(f"plaque_emaillee_{p}")}}}
                                 for p in ("milieu", "droite")])

# Sons des blocs (pas, pose, casse) : la clé « sound » de blocks.json, dans le pack de ressources. Les blocs du pack de
# comportement n'ont pas de composant de son ; c'est ici que Bedrock la lit.
SONS = {"tremie_bois": "wood", "engrenage_bois": "wood", "coffre_vente": "wood", "tapis_toile": "cloth", "plaque_emaillee": "stone"}
blocks = {"format_version": [1, 1, 0], **{f"silooo:{b}": {"sound": s} for b, s in SONS.items()}}
(ICI / "resource-pack" / "blocks.json").write_text(json.dumps(blocks, indent=2) + "\n")

print(len(list(BP.glob("*.json"))), "blocs,", len(list(RP.glob("*.json"))), "modèles")
