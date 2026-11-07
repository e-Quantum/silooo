#!/usr/bin/env python3
"""Écrit tous les fichiers de la session « animations » de l'atelier Carrière.

Lancer depuis n'importe où :  python3 -I animations/outils/generer.py
Sorties (dans animations/) :
  resource-pack/animations/*.animation.json            salariés, Oooscar, effets des pilons
  resource-pack/animation_controllers/*.json            contrôleur des gestes des salariés
  resource-pack/particles/*.particle.json               fumée, poussière, éclats, jeton, miettes, sciure
  resource-pack/textures/particle/silooo_particules.png planche 64 x 64 des particules
  design/particules_x8.png                              la même planche agrandie 8 fois, pour la relecture
  propositions/*.json                                   ce que l'entité cliente et l'add-on devraient déclarer

Règles suivies (CONTRAT.md, docs/design.md) : images clés en nombres, sans Molang, dans les animations ;
déplacements en pixels entiers (clés « pre / post » en marche d'escalier) ; particules sans dégradé de
taille ni de couleur : leur dessin change par images successives, leur position avance d'un pixel (1/16 bloc)
à la fois ; couleurs prises dans design/tokens.css et dans les tuiles de design/decor/.
"""
import json
import os
import struct
import zlib

ICI = os.path.dirname(os.path.abspath(__file__))
RACINE = os.path.dirname(ICI)
RP = os.path.join(RACINE, "resource-pack")


def ecrire(chemin, donnees):
    chemin = os.path.join(RACINE, chemin)
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    with open(chemin, "w", encoding="utf-8") as f:
        json.dump(donnees, f, ensure_ascii=False, indent=2)
        f.write("\n")


def t(x):
    """Clé de temps Bedrock : une chaîne courte, « 0.6 » plutôt que « 0.6000 »."""
    s = f"{x:.3f}".rstrip("0").rstrip(".")
    return s if "." in s else s + ".0"


def courbe(*cles):
    """Images clés interpolées : courbe((0, [0,0,0]), (0.5, [10,0,0]), ...)."""
    return {t(k): list(v) for k, v in cles}


def escalier(*cles):
    """Images clés en marche d'escalier (déplacement entier) : la valeur saute à chaque clé et tient jusqu'à la suivante."""
    out, prec = {}, None
    for k, v in cles:
        v = list(v)
        out[t(k)] = v if prec is None or prec == v else {"pre": prec, "post": v}
        prec = v
    return out


def contre(stance, corps, temps):
    """Rotation des jambes qui annule la flexion du corps (les jambes sont filles de `corps`) : les pieds restent au sol."""
    return courbe(*[(k, [stance[0] - c[0], stance[1] - c[1], 0]) for k, c in zip(temps, corps)])


# ---------------------------------------------------------------------------------------------------------------
# 1. Salariés : os du contrat, root > corps > (tete > chapeau), bras_g, (bras_d > outil), jambe_g, jambe_d
#    Convention Bedrock : x négatif = bras ou jambe vers l'avant, x positif = corps ou tête penchés en avant.
#    bras_g est du côté +x du modèle : z négatif l'écarte du corps ; bras_d l'inverse.
# ---------------------------------------------------------------------------------------------------------------
S = "animation.silooo_salarie."

inactif = {
    "loop": True,
    "animation_length": 4.0,
    "bones": {
        "tete": {"rotation": courbe((0, [0, 0, 0]), (1.2, [0, 0, 0]), (1.5, [0, 25, 0]), (2.2, [0, 25, 0]),
                                    (2.5, [6, -15, 0]), (3.5, [6, -15, 0]), (3.8, [0, 0, 0]), (4.0, [0, 0, 0]))},
        # il s'essuie le front de la main gauche entre 2,6 et 3,5 s
        "bras_g": {"rotation": courbe((0, [0, 0, -3]), (1.3, [0, 0, -5]), (2.6, [0, 0, -3]), (2.85, [-125, 0, 25]),
                                      (3.05, [-125, 0, 10]), (3.25, [-125, 0, 25]), (3.5, [0, 0, -3]), (4.0, [0, 0, -3]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 3]), (2.0, [0, 0, 5]), (4.0, [0, 0, 3]))},
    },
}

marche = {
    "loop": True,
    "animation_length": 1.0,
    "bones": {
        "corps": {"position": escalier((0, [0, 0, 0]), (0.125, [0, 1, 0]), (0.375, [0, 0, 0]), (0.625, [0, 1, 0]),
                                       (0.875, [0, 0, 0]), (1.0, [0, 0, 0]))},
        "jambe_g": {"rotation": courbe((0, [-30, 0, 0]), (0.25, [0, 0, 0]), (0.5, [30, 0, 0]), (0.75, [0, 0, 0]), (1.0, [-30, 0, 0]))},
        "jambe_d": {"rotation": courbe((0, [30, 0, 0]), (0.25, [0, 0, 0]), (0.5, [-30, 0, 0]), (0.75, [0, 0, 0]), (1.0, [30, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [25, 0, -3]), (0.5, [-25, 0, -3]), (1.0, [25, 0, -3]))},
        # le bras de l'outil balance moins
        "bras_d": {"rotation": courbe((0, [-30, 0, 3]), (0.5, [-5, 0, 3]), (1.0, [-30, 0, 3]))},
    },
}

T_PIOCHE = [0, 0.45, 0.6, 0.8, 1.2]
C_PIOCHE = [[0, 0, 0], [-8, 0, 0], [20, 0, 0], [18, 0, 0], [0, 0, 0]]
piocher = {
    "loop": True,
    "animation_length": 1.2,
    "bones": {
        "corps": {"rotation": courbe(*zip(T_PIOCHE, C_PIOCHE))},
        "bras_d": {"rotation": courbe((0, [-20, 0, 0]), (0.45, [-170, 0, 5]), (0.6, [-50, 0, 0]), (0.8, [-45, 0, 0]), (1.2, [-20, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [-20, 0, 0]), (0.45, [-160, 0, -5]), (0.6, [-55, 0, 10]), (0.8, [-50, 0, 10]), (1.2, [-20, 0, 0]))},
        "tete": {"rotation": courbe((0, [0, 0, 0]), (0.45, [-10, 0, 0]), (0.6, [10, 0, 0]), (1.2, [0, 0, 0]))},
        # l'outil, tenu manche vers l'avant au repos, pivote dans la main pour que le fer touche la pierre au coup
        "outil": {"rotation": courbe((0, [30, 0, 0]), (0.45, [0, 0, 0]), (0.6, [80, 0, 0]), (0.8, [80, 0, 0]), (1.2, [30, 0, 0]))},
        "jambe_g": {"rotation": contre([-12, 0], C_PIOCHE, T_PIOCHE)},
        "jambe_d": {"rotation": contre([10, 0], C_PIOCHE, T_PIOCHE)},
    },
    # le coup porte à 0,6 s : éclats à la pointe de l'outil et son de pioche (nom du contrat, côté « sons »)
    "particle_effects": {"0.6": {"effect": "eclats", "locator": "pointe_outil"}},
    "sound_effects": {"0.6": {"effect": "pioche"}},
}

T_PELLE = [0, 0.4, 0.7, 1.0, 1.15, 1.6]
C_PELLE = [[0, 0, 0], [30, 0, 0], [30, 0, 0], [8, -20, 0], [0, -25, 0], [0, 0, 0]]
pelleter = {
    "loop": True,
    "animation_length": 1.6,
    "bones": {
        "corps": {"rotation": courbe(*zip(T_PELLE, C_PELLE))},
        "bras_d": {"rotation": courbe((0, [-30, 0, 0]), (0.4, [-75, 0, 0]), (0.7, [-70, 0, 0]), (1.0, [-95, 0, 0]),
                                      (1.15, [-115, 0, 0]), (1.6, [-30, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [-40, 0, 10]), (0.4, [-80, 0, 15]), (0.7, [-75, 0, 15]), (1.0, [-85, 0, 5]),
                                      (1.15, [-100, 0, 0]), (1.6, [-40, 0, 10]))},
        # la lame suit le geste : au sol à 0,4 s, vers l'avant au jet
        "outil": {"rotation": courbe((0, [80, 0, 0]), (0.4, [120, 0, 0]), (0.7, [110, 0, 0]), (1.0, [75, 0, 0]),
                                     (1.15, [85, 0, 0]), (1.6, [80, 0, 0]))},
        "jambe_g": {"rotation": contre([-10, 0], C_PELLE, T_PELLE)},
        "jambe_d": {"rotation": contre([12, 0], C_PELLE, T_PELLE)},
    },
    # la pelle mord dans le gravier à 0,4 s : éclats et son court « pelle » (silooo.carriere.pelle)
    "particle_effects": {"0.4": {"effect": "eclats", "locator": "pointe_outil"}},
    "sound_effects": {"0.4": {"effect": "pelle"}},
}

porter = {
    "loop": True,
    "animation_length": 1.0,
    "bones": {
        "corps": {"rotation": courbe((0, [-4, 0, 0]), (1.0, [-4, 0, 0])),
                  "position": escalier((0, [0, 0, 0]), (0.125, [0, 1, 0]), (0.375, [0, 0, 0]), (0.625, [0, 1, 0]),
                                       (0.875, [0, 0, 0]), (1.0, [0, 0, 0]))},
        # les deux bras entourent la charge (os `charge`, devant le ventre), l'outil est rangé (échelle 0, sans transition)
        "bras_g": {"rotation": courbe((0, [-50, 0, 12]), (0.5, [-46, 0, 12]), (1.0, [-50, 0, 12]))},
        "bras_d": {"rotation": courbe((0, [-46, 0, -12]), (0.5, [-50, 0, -12]), (1.0, [-46, 0, -12]))},
        "outil": {"scale": 0},
        # la charge est portée un pixel plus haut et tressaute à contretemps du corps
        "charge": {"position": escalier((0, [0, 1, 0]), (0.125, [0, 0, 0]), (0.375, [0, 1, 0]), (0.625, [0, 0, 0]),
                                        (0.875, [0, 1, 0]), (1.0, [0, 1, 0]))},
        # pas plus courts qu'à vide
        "jambe_g": {"rotation": courbe((0, [-20, 0, 0]), (0.25, [4, 0, 0]), (0.5, [24, 0, 0]), (0.75, [4, 0, 0]), (1.0, [-20, 0, 0]))},
        "jambe_d": {"rotation": courbe((0, [24, 0, 0]), (0.25, [4, 0, 0]), (0.5, [-20, 0, 0]), (0.75, [4, 0, 0]), (1.0, [24, 0, 0]))},
    },
}

saluer = {
    "loop": False,
    "animation_length": 1.6,
    "bones": {
        "bras_g": {"rotation": courbe((0, [0, 0, 0]), (0.25, [-170, 0, -15]), (0.45, [-170, 0, -40]), (0.65, [-170, 0, -15]),
                                      (0.85, [-170, 0, -40]), (1.05, [-170, 0, -15]), (1.4, [0, 0, 0]), (1.6, [0, 0, 0]))},
        "tete": {"rotation": courbe((0, [0, 0, 0]), (0.25, [0, 0, -8]), (1.2, [0, 0, -8]), (1.5, [0, 0, 0]))},
        # le chapeau saute de deux pixels puis retombe, un pixel à la fois
        "chapeau": {"position": escalier((0, [0, 0, 0]), (0.35, [0, 2, 0]), (0.6, [0, 1, 0]), (0.7, [0, 0, 0]), (1.6, [0, 0, 0]))},
        "root": {"position": escalier((0, [0, 0, 0]), (0.2, [0, 1, 0]), (0.35, [0, 0, 0]), (1.6, [0, 0, 0]))},
    },
}

SALARIE = {"inactif": inactif, "marche": marche, "piocher": piocher, "pelleter": pelleter, "porter": porter, "saluer": saluer}
ecrire("resource-pack/animations/salarie.animation.json",
       {"format_version": "1.8.0", "animations": {S + k: v for k, v in SALARIE.items()}})

# Contrôleur : l'état suit la propriété d'entité `silooo:geste` (enum) que l'add-on règle. Seules les conditions sont en Molang.
GESTES = list(SALARIE)
P = "query.property('silooo:geste')"
etats = {}
for g in GESTES:
    if g == "saluer":
        # geste ponctuel : il se joue en entier, puis l'état suit de nouveau la propriété
        trans = [{h: f"query.all_animations_finished && {P} == '{h}'"} for h in GESTES if h != g]
    else:
        trans = [{h: f"{P} == '{h}'"} for h in GESTES if h != g]
    # porter range l'outil (échelle 0) : pas de fondu, pour que l'outil ne rétrécisse pas progressivement
    etats[g] = {"animations": [g], "transitions": trans, "blend_transition": 0.0 if g == "porter" else 0.2}
ecrire("resource-pack/animation_controllers/salarie.animation_controllers.json", {
    "format_version": "1.10.0",
    "animation_controllers": {"controller.animation.silooo_salarie.geste": {"initial_state": "inactif", "states": etats}},
})

# ---------------------------------------------------------------------------------------------------------------
# 2. Oooscar : os root, pied_g, pied_d, corps (pivot 0,3,0), echelle, toit (0,16,0), bras_g (+x), bras_d (-x).
#    Chaque réaction se joue par-dessus `balancement`, qui ne s'arrête jamais.
# ---------------------------------------------------------------------------------------------------------------
O = "animation.silooo_oooscar."


def alterne(debut, fin, pas, a, b):
    out, k, i = [], debut, 0
    while k < fin - 1e-9:
        out.append((k, a if i % 2 == 0 else b))
        k, i = round(k + pas, 3), i + 1
    return out


joie = {  # une vente : deux bonds, les bras en l'air, le toit qui saute comme un couvercle
    "loop": False,
    "animation_length": 1.0,
    "bones": {
        "root": {"position": courbe((0, [0, 0, 0]), (0.12, [0, 3, 0]), (0.3, [0, 0, 0]), (0.42, [0, 2, 0]), (0.6, [0, 0, 0]), (1.0, [0, 0, 0]))},
        "toit": {"position": courbe((0, [0, 0, 0]), (0.08, [0, 0, 0]), (0.16, [0, 2, 0]), (0.3, [0, 0, 0]), (0.46, [0, 1, 0]), (0.6, [0, 0, 0]))},
        "corps": {"rotation": courbe((0, [0, 0, 0]), (0.6, [0, 0, 0]), (0.72, [0, 0, 4]), (0.84, [0, 0, -4]), (1.0, [0, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [0, 0, 0]), (0.12, [-160, 0, -20]), (0.3, [-150, 0, -35]), (0.45, [-160, 0, -20]),
                                      (0.6, [-150, 0, -35]), (0.8, [-120, 0, -20]), (1.0, [0, 0, 0]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 0]), (0.12, [-160, 0, 20]), (0.3, [-150, 0, 35]), (0.45, [-160, 0, 20]),
                                      (0.6, [-150, 0, 35]), (0.8, [-120, 0, 20]), (1.0, [0, 0, 0]))},
    },
}

oups = {  # pas assez de Donar : il recule, le toit se soulève, il se gratte le toit et se tasse d'un pixel
    "loop": False,
    "animation_length": 1.4,
    "bones": {
        "corps": {"rotation": courbe((0, [0, 0, 0]), (0.15, [-8, 0, 0]), (0.35, [0, 0, 0]), (0.6, [0, 0, -5]), (1.15, [0, 0, -5]), (1.4, [0, 0, 0])),
                  "position": escalier((0, [0, 0, 0]), (0.65, [0, -1, 0]), (1.25, [0, 0, 0]), (1.4, [0, 0, 0]))},
        "toit": {"position": escalier((0, [0, 0, 0]), (0.1, [0, 1, 0]), (0.25, [0, 0, 0]), (1.4, [0, 0, 0])),
                 "rotation": courbe((0, [0, 0, 0]), (0.15, [0, 0, 0]), (0.25, [0, 0, 10]), (0.4, [0, 0, -6]), (0.55, [0, 0, 0]), (1.4, [0, 0, 0]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 0]), (0.4, [0, 0, 0]), (0.6, [-150, 0, 25]), (0.75, [-140, 0, 15]), (0.9, [-150, 0, 25]),
                                      (1.05, [-140, 0, 15]), (1.3, [0, 0, 0]), (1.4, [0, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [0, 0, 0]), (0.5, [-30, 0, -40]), (1.1, [-30, 0, -40]), (1.3, [0, 0, 0]), (1.4, [0, 0, 0]))},
    },
}

pan = alterne(0, 2.0, 0.1, [0, 0, 6], [0, 0, -6])
panique = {  # incident : il tremble, agite les bras, court sur place et regarde à gauche, à droite
    "loop": False,
    "animation_length": 2.0,
    "bones": {
        "corps": {"rotation": courbe((0, [0, 0, 0]), *pan[1:], (2.0, [0, 0, 0]))},
        "toit": {"position": escalier(*alterne(0, 2.0, 0.1, [0, 0, 0], [0, 1, 0]), (2.0, [0, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [0, 0, 0]), *alterne(0.1, 1.9, 0.1, [-170, 0, -30], [-110, 0, -60]), (2.0, [0, 0, 0]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 0]), *alterne(0.1, 1.9, 0.1, [-110, 0, 60], [-170, 0, 30]), (2.0, [0, 0, 0]))},
        "pied_g": {"position": escalier(*alterne(0, 2.0, 0.1, [0, 0, 0], [0, 1, 0]), (2.0, [0, 0, 0]))},
        "pied_d": {"position": escalier(*alterne(0, 2.0, 0.1, [0, 1, 0], [0, 0, 0]), (2.0, [0, 0, 0]))},
        "root": {"rotation": courbe((0, [0, 0, 0]), (0.4, [0, 25, 0]), (0.8, [0, -25, 0]), (1.2, [0, 25, 0]), (1.6, [0, -25, 0]), (2.0, [0, 0, 0]))},
    },
}

fier = {  # prestige : il bombe le torse, grandit d'un pixel, toit de travers, poings sur les hanches, tape sa poitrine
    "loop": False,
    "animation_length": 2.0,
    "bones": {
        "corps": {"rotation": courbe((0, [0, 0, 0]), (0.3, [-8, 0, 0]), (1.6, [-8, 0, 0]), (2.0, [0, 0, 0])),
                  "position": escalier((0, [0, 0, 0]), (0.3, [0, 1, 0]), (1.8, [0, 0, 0]), (2.0, [0, 0, 0]))},
        "toit": {"rotation": courbe((0, [0, 0, 0]), (0.3, [0, 0, -8]), (0.9, [0, 0, -8]), (1.05, [-6, 0, -8]), (1.2, [0, 0, -8]),
                                    (1.6, [0, 0, -8]), (2.0, [0, 0, 0])),
                 "position": escalier((0, [0, 0, 0]), (0.25, [0, 1, 0]), (1.8, [0, 0, 0]), (2.0, [0, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [0, 0, 0]), (0.3, [-15, 0, -40]), (1.7, [-15, 0, -40]), (2.0, [0, 0, 0]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 0]), (0.3, [-15, 0, 40]), (0.9, [-15, 0, 40]), (1.05, [-100, 0, -10]), (1.15, [-100, 0, -20]),
                                      (1.25, [-100, 0, -10]), (1.45, [-15, 0, 40]), (1.7, [-15, 0, 40]), (2.0, [0, 0, 0]))},
    },
}

dodo = {  # cinquième expression de la charte, en bonus : il pique du nez, le toit glisse d'un pixel
    "loop": True,
    "animation_length": 4.0,
    "bones": {
        "corps": {"rotation": courbe((0, [4, 0, 0]), (2.0, [8, 0, 3]), (4.0, [4, 0, 0]))},
        "toit": {"rotation": courbe((0, [6, 0, 0]), (2.0, [10, 0, 0]), (4.0, [6, 0, 0])),
                 "position": escalier((0, [0, 0, 0]), (1.5, [0, -1, 0]), (3.0, [0, 0, 0]), (4.0, [0, 0, 0]))},
        "bras_g": {"rotation": courbe((0, [0, 0, -2]), (2.0, [0, 0, 0]), (4.0, [0, 0, -2]))},
        "bras_d": {"rotation": courbe((0, [0, 0, 2]), (2.0, [0, 0, 0]), (4.0, [0, 0, 2]))},
    },
}

OOOSCAR = {"joie": joie, "oups": oups, "panique": panique, "fier": fier, "dodo": dodo}
ecrire("resource-pack/animations/oooscar_reactions.animation.json",
       {"format_version": "1.8.0", "animations": {O + k: v for k, v in OOOSCAR.items()}})

# ---------------------------------------------------------------------------------------------------------------
# 3. Machines (CONTRAT.md, « Machines ») : animations. Modèles, texture et entités : section 7.
#    Pilons : os pilon_1, pilon_2, pilon_3. Cycle de 1,1 s : montée de 8 pixels en 0,8 s, un pixel toutes les 0,1 s,
#    chute en 0,1 s (deux pas de 4 pixels), 0,2 s posé au fond de l'auge. Chaque pilon a un tiers de cycle de retard
#    sur le précédent. Au coup (0,9 s de son cycle) : poussière au locator pilon_N (bas de la tête) et son « pilon ».
# ---------------------------------------------------------------------------------------------------------------
CYCLE = 1.1
MONTEE = [(round(0.1 * i, 3), i) for i in range(1, 9)] + [(0.85, 4), (0.9, 0)]  # (phase, hauteur en pixels)
COUP = 0.9  # phase du coup : la tête touche l'auge
def hauteur(phase):
    h = 0
    for p, v in MONTEE:
        if p <= phase + 1e-9:
            h = v
    return h


frappe_os, coups = {}, {}
for k in range(3):
    decal = k * CYCLE / 3
    depart = hauteur((CYCLE - decal) % CYCLE)  # hauteur du pilon à t = 0
    evts = sorted(((round((p + decal) % CYCLE, 3), h) for p, h in MONTEE), key=lambda e: e[0])
    frappe_os[f"pilon_{k + 1}"] = {"position": escalier((0, [0, depart, 0]), *[(tt, [0, h, 0]) for tt, h in evts if tt > 0],
                                                        (CYCLE, [0, depart, 0]))}
    coups[t(round((COUP + decal) % CYCLE, 3))] = k + 1
frappe = {
    "loop": True,
    "animation_length": CYCLE,
    "bones": frappe_os,
    "particle_effects": {k: {"effect": "poussiere", "locator": f"pilon_{n}"} for k, n in sorted(coups.items(), key=lambda kv: float(kv[0]))},
    "sound_effects": {k: {"effect": "pilon"} for k in sorted(coups, key=float)},
}
# Sciure des rouets : un brin par rouet, l'un après l'autre, sur trois cycles de pilons (3,3 s).
sciure = {t(0.3): {"effect": "sciure", "locator": "rouet_g"}, t(CYCLE + 0.7): {"effect": "sciure", "locator": "rouet_d"},
          t(2 * CYCLE + 0.3): {"effect": "sciure", "locator": "rouet_moulin"}}
ecrire("resource-pack/animations/pilons.animation.json", {"format_version": "1.8.0", "animations": {
    "animation.silooo_pilons.frappe": frappe,
    "animation.silooo_pilons.effets": {"loop": True, "animation_length": round(3 * CYCLE, 3), "particle_effects": sciure},
}})
ancien = os.path.join(RP, "animations", "pilons_effets.animation.json")  # remplacé par pilons.animation.json
if os.path.exists(ancien):
    os.remove(ancien)

# Ailes du moulin : os moyeu, un tour complet en 4 s exactement, en boucle, autour de l'axe du moyeu (z du modèle).
# Une clé par quart de tour pour que l'interpolation ne prenne pas de raccourci. Sens : celui de l'aperçu du chef,
# en supposant qu'une entité tournée vers le sud affiche le modèle avec z inversé (voir README) ; un signe à changer.
SENS = 1
# Ruine : arrêtée, à 0,5 rad (28,6°), comme `entites.ruine.ailes.angle` de carriere.json.
ANGLE_RUINE = -28.6 * SENS
ecrire("resource-pack/animations/ailes_moulin.animation.json", {"format_version": "1.8.0", "animations": {
    "animation.silooo_ailes_moulin.tourne": {
        "loop": True,
        "animation_length": 4.0,
        "bones": {"moyeu": {"rotation": courbe(*[(s, [0, 0, 90 * s * SENS]) for s in range(5)])}},
    },
    "animation.silooo_ailes_moulin.ruine": {
        "loop": True,
        "animation_length": 1.0,
        "bones": {"moyeu": {"rotation": [0, 0, ANGLE_RUINE]}},
    }}})

# ---------------------------------------------------------------------------------------------------------------
# 4. Planche des particules, 64 x 64, une image = 8 x 8 pixels.
#    Lignes : 0 fumée (8 images), 1 poussière (4), 2 éclats (4 variantes), 3 jeton de Donar (4), 4 sciure (2).
# ---------------------------------------------------------------------------------------------------------------
TOK = {  # design/tokens.css, et les tuiles design/decor/planches.png
    "K": "#1e1c1a",  # fonte-900
    "D": "#5b5853",  # fonte-500
    "F": "#8c8780",  # fonte-300
    "A": "#bab5ac",  # acier-100
    "O": "#c9ba98",  # papier-ombre
    "L": "#f0cf72",  # laiton-clair
    "M": "#c8962e",  # laiton
    "S": "#8a6420",  # laiton-sombre
    "B": "#9a6c43",  # planches, clair
    "b": "#6b4a2e",  # planches, sombre
}

FUMEE = [
    ["........", "........", "........", "...AF...", "...FD...", "........", "........", "........"],
    ["........", "........", "...AA...", "..AFFD..", "..FFDD..", "...DD...", "........", "........"],
    ["........", "...AA...", "..AAFF..", "..AFFFD.", "..FFFDD.", "...FDD..", "........", "........"],
    ["...AA...", "..AAFF..", ".AAFFFD.", ".AFFFFD.", ".FFFFDD.", "..FFDD..", "...DD...", "........"],
    ["..AA....", ".AAFF.A.", ".AFFFAFD", ".FFFDFFD", "..FDD.D.", "..FD....", "........", "........"],
    ["..A.....", ".AFF..A.", ".FFD.AFD", "..D..FD.", "........", "..F.....", "........", "........"],
    ["........", ".AF.....", ".FD...A.", "......F.", "........", "........", "........", "........"],
    ["........", "........", ".F......", "........", "......F.", "........", "........", "........"],
]
POUSSIERE = [
    ["........", "........", "........", "........", "...AO...", "..AOOF..", "........", "........"],
    ["........", "........", "........", "..A.O...", ".AOOOOF.", "AOOFOOFF", "........", "........"],
    ["........", "........", "........", ".A....O.", "AO.OO.OF", ".F.O..F.", "........", "........"],
    ["........", "........", "........", "........", "O.....A.", "...F....", "......O.", "........"],
]
ECLATS = [
    ["........", "........", "...A....", "...FD...", "........", "........", "........", "........"],
    ["........", "........", "..AF....", "..FFD...", "...D....", "........", "........", "........"],
    ["........", "........", "....A...", "...AF...", "...FD...", "........", "........", "........"],
    ["........", "........", "...AF...", "...D....", "........", "........", "........", "........"],
]
JETON = [  # jeton de laiton percé (design/icones/donar.png), qui tourne : face, trois quarts, tranche, trois quarts
    ["..KKKK..", ".KLLMMK.", "KLLMMMSK", "KLM..MSK", "KLM..MSK", "KMMMMSSK", ".KMSSSK.", "..KKKK.."],
    ["...KK...", "..KLMK..", ".KLMMSK.", ".KL.MSK.", ".KL.MSK.", ".KMMSSK.", "..KMSK..", "...KK..."],
    ["...KK...", "...LK...", "...LK...", "...MK...", "...MK...", "...SK...", "...SK...", "...KK..."],
    ["...KK...", "..KLMK..", ".KLMMSK.", ".KLM.SK.", ".KLM.SK.", ".KMMSSK.", "..KMSK..", "...KK..."],
]
SCIURE = [
    ["........", "........", "...B....", "...bB...", "........", "........", "........", "........"],
    ["........", "........", "...Bb...", "....b...", "........", "........", "........", "........"],
]
LIGNES = [FUMEE, POUSSIERE, ECLATS, JETON, SCIURE]


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def png(chemin, w, h, px):
    """PNG RGBA sans bibliothèque : px[y][x] = (r, g, b, a)."""
    brut = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in ligne) for ligne in px)

    def bloc(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    with open(chemin, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + bloc(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + bloc(b"IDAT", zlib.compress(brut, 9)) + bloc(b"IEND", b""))


W = H = 64
planche = [[(0, 0, 0, 0)] * W for _ in range(H)]
for r, images in enumerate(LIGNES):
    for c, img in enumerate(images):
        assert len(img) == 8 and all(len(l) == 8 for l in img), (r, c)
        for y, ligne in enumerate(img):
            for x, ch in enumerate(ligne):
                if ch != ".":
                    planche[r * 8 + y][c * 8 + x] = hexrgb(TOK[ch]) + (255,)
png(os.path.join(RP, "textures/particle/silooo_particules.png"), W, H, planche)
# agrandie 8 fois sur fond papier, pour la relecture
Z, FOND = 8, hexrgb("#fff6de") + (255,)
grand = [[planche[y // Z][x // Z] if planche[y // Z][x // Z][3] else FOND for x in range(W * Z)] for y in range(40 * Z)]
png(os.path.join(RACINE, "design/particules_x8.png"), W * Z, 40 * Z, grand)

# ---------------------------------------------------------------------------------------------------------------
# 5. Particules. Mouvement paramétrique : la position est recalculée à chaque image et arrondie au pixel
#    (math.floor(x * 16) / 16), donc elle avance d'un pixel entier à la fois. Taille fixe, pas de teinte.
#    Hypothèse à confirmer en jeu : `size` est une demi-largeur, 0.25 = un quad d'un demi-bloc = 8 pixels de texture.
# ---------------------------------------------------------------------------------------------------------------
TEX = "textures/particle/silooo_particules"


def px(expr):
    return f"math.floor(({expr}) * 16) / 16"


def particule(ident, composants):
    return {"format_version": "1.10.0", "particle_effect": {
        "description": {"identifier": ident, "basic_render_parameters": {"material": "particles_alpha", "texture": TEX}},
        "components": composants}}


def billboard(uv, taille="0.25"):
    return {"size": [taille, taille], "facing_camera_mode": "lookat_xyz", "uv": dict({"texture_width": 64, "texture_height": 64}, **uv)}


def flip(ligne, images, fps=None, boucle=False):
    f = {"base_UV": [0, ligne * 8], "size_UV": [8, 8], "step_UV": [8, 0], "max_frame": images, "loop": boucle}
    if fps is None:
        f["stretch_to_lifetime"] = True
    else:
        f["frames_per_second"] = fps
    return {"flipbook": f}


A_, R1, R2, R3, R4 = "variable.particle_age", "variable.particle_random_1", "variable.particle_random_2", "variable.particle_random_3", "variable.particle_random_4"
ANGLE = f"{R1} * 360"

PARTICULES = {
    # cheminée du bureau : une bouffée toutes les 0,8 s, qui monte de 0,6 bloc/s et dérive avec le vent (+x, +z)
    "fumee_poele": particule("silooo:fumee_poele", {
        "minecraft:emitter_rate_steady": {"spawn_rate": 1.25, "max_particles": 5},
        "minecraft:emitter_lifetime_once": {"active_time": 8},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": 3.2},
        "minecraft:particle_motion_parametric": {"relative_position": [
            px(f"{A_} * 0.15 + ({R1} - 0.5) * 0.12"), px(f"{A_} * 0.6"), px(f"{A_} * 0.05 + ({R2} - 0.5) * 0.12")]},
        "minecraft:particle_appearance_billboard": billboard(flip(0, 8)),
    }),
    # un pilon frappe l'auge : trois petits nuages qui s'écartent au ras de l'auge
    "poussiere_bocard": particule("silooo:poussiere_bocard", {
        "minecraft:emitter_rate_instant": {"num_particles": 3},
        "minecraft:emitter_lifetime_once": {"active_time": 0.1},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": 0.5},
        "minecraft:particle_motion_parametric": {"relative_position": [
            px(f"math.cos({ANGLE}) * math.min({A_}, 0.3) * 1.4"), px(f"{A_} * 0.35"), px(f"math.sin({ANGLE}) * math.min({A_}, 0.3) * 1.4")]},
        "minecraft:particle_appearance_billboard": billboard(flip(1, 4)),
    }),
    # le joueur frappe (4 éclats) ou casse (8 éclats, variable.gros = 1) une pierre ; un salarié pioche
    "eclats_pierre": particule("silooo:eclats_pierre", {
        "minecraft:emitter_rate_instant": {"num_particles": "variable.gros > 0 ? 8 : 4"},
        "minecraft:emitter_lifetime_once": {"active_time": 0.1},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": f"0.45 + {R3} * 0.15"},
        "minecraft:particle_motion_parametric": {"relative_position": [
            px(f"math.cos({ANGLE}) * {A_} * (1.2 + {R2})"), px(f"{A_} * (2.6 + {R2}) - 6 * {A_} * {A_}"), px(f"math.sin({ANGLE}) * {A_} * (1.2 + {R2})")]},
        "minecraft:particle_appearance_billboard": billboard({"uv": [f"math.floor({R4} * 4) * 8", 16], "uv_size": [8, 8]}),
    }),
    # vente : le jeton de Donar saute d'un bloc en tournant, attend, clignote et disparaît (le compteur du HUD prend le relais)
    "jeton_donar": particule("silooo:jeton_donar", {
        "minecraft:emitter_rate_instant": {"num_particles": 1},
        "minecraft:emitter_lifetime_once": {"active_time": 0.1},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": 1.0},
        "minecraft:particle_motion_parametric": {"relative_position": [
            0, px(f"math.min(math.max({A_} - 0.2, 0), 0.4) * 5 - math.pow(math.min(math.max({A_} - 0.2, 0), 0.4), 2) * 6.25"), 0]},
        # invisible pendant que le coffre avale (0,2 s), puis clignote de 0,8 à 1 s : la taille saute, elle ne glisse jamais
        "minecraft:particle_appearance_billboard": billboard(flip(3, 4, fps=10, boucle=True),
                                                             taille=f"({A_} < 0.2 || ({A_} > 0.8 && math.mod(math.floor({A_} * 16), 2) == 0)) ? 0 : 0.25"),
    }),
    # le coffre avale : cinq miettes tirées vers la fente, vers le centre et vers le bas
    "miettes_coffre": particule("silooo:miettes_coffre", {
        "minecraft:emitter_rate_instant": {"num_particles": 5},
        "minecraft:emitter_lifetime_once": {"active_time": 0.1},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": 0.3},
        "minecraft:particle_motion_parametric": {"relative_position": [
            px(f"math.cos({ANGLE}) * 0.45 * (1 - {A_} / 0.3)"), px(f"0.4 - {A_} * 1.4"), px(f"math.sin({ANGLE}) * 0.45 * (1 - {A_} / 0.3)")]},
        "minecraft:particle_appearance_billboard": billboard({"uv": [f"math.floor({R4} * 4) * 8", 16], "uv_size": [8, 8]}),
    }),
    # un rouet tourne : de temps en temps un brin de sciure tombe de l'axe en tournoyant
    "sciure_rouet": particule("silooo:sciure_rouet", {
        "minecraft:emitter_rate_instant": {"num_particles": 1},
        "minecraft:emitter_lifetime_once": {"active_time": 0.1},
        "minecraft:emitter_shape_point": {"offset": [0, 0, 0]},
        "minecraft:particle_lifetime_expression": {"max_lifetime": 0.9},
        "minecraft:particle_motion_parametric": {"relative_position": [
            px(f"({R1} - 0.5) * 0.4"), px(f"-{A_} * 1.1"), f"math.mod(math.floor({A_} * 6), 2) * 0.0625"]},
        "minecraft:particle_appearance_billboard": billboard(flip(4, 2, fps=6, boucle=True)),
    }),
}
for nom, donnees in PARTICULES.items():
    ecrire(f"resource-pack/particles/{nom}.particle.json", donnees)

# ---------------------------------------------------------------------------------------------------------------
# 6. Propositions pour l'intégration (le chef les fusionne ; on ne touche ni à depot/ ni aux dossiers des autres)
# ---------------------------------------------------------------------------------------------------------------
ecrire("propositions/salarie.entity.json", {
    "format_version": "1.10.0",
    "minecraft:client_entity": {"description": {
        "identifier": "silooo:salarie",
        "materials": {"default": "entity_alphatest"},
        "textures": {"commun": "textures/entity/salarie_commun", "rare": "textures/entity/salarie_rare",
                     "legendaire": "textures/entity/salarie_legendaire"},
        "geometry": {"default": "geometry.silooo.salarie"},
        "animations": dict({k: S + k for k in GESTES}, geste="controller.animation.silooo_salarie.geste"),
        "scripts": {"animate": ["geste"]},
        "particle_effects": {"eclats": "silooo:eclats_pierre"},
        "sound_effects": {"pioche": "silooo.carriere.pioche", "pelle": "silooo.carriere.pelle"},
        "render_controllers": ["controller.render.silooo_salarie"],
    }}})

ecrire("propositions/salarie.comportement.extrait.json", {
    "_note": "Extrait pour addon/pack/entities/salarie.json : la propriété que lit le contrôleur. client_sync la rend lisible par le pack.",
    "minecraft:entity": {"description": {"identifier": "silooo:salarie", "properties": {
        "silooo:geste": {"type": "enum", "values": GESTES, "default": "inactif", "client_sync": True}}}}})

with open(os.path.join(RACINE, "..", "depot", "resource-pack", "entity", "oooscar.entity.json"), encoding="utf-8") as f:
    oooscar = json.load(f)
desc = oooscar["minecraft:client_entity"]["description"]
desc["animations"].update({k: O + k for k in OOOSCAR})
ecrire("propositions/oooscar.entity.json", oooscar)

# Le salarié n'affiche l'os `charge` qu'en portant : règle de visibilité pour le contrôleur de rendu de textures-skins.
ecrire("propositions/salarie.render_controllers.extrait.json", {
    "_note": "À ajouter au contrôleur de rendu des salariés (textures-skins) : `charge` est vide au repos, visible seulement pendant `porter`.",
    "part_visibility": [{"*": True}, {"charge": "query.property('silooo:geste') == 'porter'"}]})

for vieux in ("pilons.entity.extrait.json", "ailes_moulin.entity.extrait.json"):  # remplacés par les vraies entités
    if os.path.exists(os.path.join(RACINE, "propositions", vieux)):
        os.remove(os.path.join(RACINE, "propositions", vieux))

# ---------------------------------------------------------------------------------------------------------------
# 7. Machines : modèles, texture, entités clientes et contrôleurs de rendu (CONTRAT.md, « Machines »).
#    Repère : une entité tournée vers le sud (yaw 0) affiche x du modèle = x du monde et z du modèle = -z du monde
#    (la tête d'un joueur, face nord du modèle, regarde le sud). Si c'est l'inverse en jeu : faire apparaître
#    l'entité tournée de 180°, et passer SENS à -1.
#    Ailes : entité au moyeu, entites.marche.ailes.moyeu = (26.5, 21.5, 14.05). Pilons : entité à (21.5, 16, 10.5),
#    pied du pilon du milieu (pilons.x = 20, 21, 22, z = 10, bas = 16). Unité : 1 bloc = 16.
#    Une seule propriété pilote la ruine : silooo:ruine (booléen, client_sync), lue par les entités et leurs rendus.
# ---------------------------------------------------------------------------------------------------------------
from PIL import Image  # noqa: E402  (seule dépendance : lire les tuiles du dépôt)

DECOR = os.path.join(RACINE, "..", "depot", "design", "decor")
pierre = Image.open(os.path.join(DECOR, "pierre.png")).convert("RGBA")
planches = Image.open(os.path.join(DECOR, "planches.png")).convert("RGBA")
TW, TH = 128, 128
machines = Image.new("RGBA", (TW, TH), (0, 0, 0, 0))
for i in range(8):
    machines.paste(pierre, (i * 16, 0))      # P : pierre, bande horizontale
    machines.paste(planches, (i * 16, 16))   # H : planches, bande horizontale
for j in range(6):
    machines.paste(planches, (0, 32 + j * 16))  # V : planches, bande verticale (poutres longues)
machines.paste(Image.new("RGBA", (32, 96), hexrgb(TOK["O"]) + (255,)), (32, 32))  # T : toile, papier-ombre uni
os.makedirs(os.path.join(RP, "textures/entity"), exist_ok=True)
machines.save(os.path.join(RP, "textures/entity/machines_carriere.png"))
machines.resize((TW * 4, TH * 4), Image.NEAREST).save(os.path.join(RACINE, "design/machines_carriere_x4.png"))

REGIONS = {"pierre": [(0, 0, 128, 16)], "bois": [(0, 16, 128, 16), (0, 32, 16, 96)], "toile": [(32, 32, 32, 96)]}


def faces(taille, matiere):
    """UV par face, un pixel de texture par unité : la face prend la première région assez grande (jamais étirée)."""
    sx, sy, sz = taille
    dims = {"north": (sx, sy), "south": (sx, sy), "east": (sz, sy), "west": (sz, sy), "up": (sx, sz), "down": (sx, sz)}
    out = {}
    for f, (w, h) in dims.items():
        u, v, W_, H_ = next(r for r in REGIONS[matiere] if w <= r[2] and h <= r[3])
        out[f] = {"uv": [u, v], "uv_size": [w, h]}
    return out


def cb(origine, taille, matiere):
    return {"origin": [round(x, 2) for x in origine], "size": taille, "uv": faces(taille, matiere)}


def geo(ident, vb, os_):
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": ident, "texture_width": TW, "texture_height": TH,
                        "visible_bounds_width": vb[0], "visible_bounds_height": vb[1], "visible_bounds_offset": vb[2]},
        "bones": os_}]}


# Ailes : moyeu, quatre ailes de 5 blocs (bras, cinq lattes, toile), une aile tombée au sol pour la ruine.
os_ailes = [{"name": "root", "pivot": [0, 0, 0]},
            {"name": "moyeu", "parent": "root", "pivot": [0, 0, 0], "cubes": [cb([-5, -5, -4], [10, 10, 8], "bois")]}]
for k in range(4):
    os_ailes.append({"name": f"aile_{k}", "parent": "moyeu", "pivot": [0, 0, 0], "rotation": [0, 0, -90 * k * SENS],
                     "cubes": [cb([-2, 0, -3], [4, 80, 3], "bois")] + [cb([-2, y, -4], [18, 1, 1], "bois") for y in (24, 36, 48, 60, 72)]})
    os_ailes.append({"name": f"toile_{k}", "parent": f"aile_{k}", "pivot": [0, 0, 0], "cubes": [cb([1, 21, -2], [15, 59, 1], "toile")]})
P_T = [-8, -102, -47]  # entites.ruine.ailes.tombee (26.0, 15.1, 17.0) moins le moyeu
os_ailes.append({"name": "aile_tombee", "parent": "root", "pivot": P_T, "rotation": [0, -20, 0],
                 "cubes": [cb([P_T[0] - 40, P_T[1] - 1.5, P_T[2] - 2], [80, 3, 4], "bois")]
                 + [cb([P_T[0] + s * 16 - 0.5, P_T[1] + 0.5, P_T[2] - 15], [1, 1, 16], "bois") for s in (-2, -1.25, -0.5, 0.25, 1.0, 1.75)]})
ecrire("resource-pack/models/entity/ailes_moulin.geo.json", geo("geometry.silooo.ailes_moulin", [12, 14, [0, -1, 0]], os_ailes))

# Pilons : tête de pierre 9 x 8 x 9, fût de bois 3 x 46, collier ; locators du contrat.
os_pilons = [{"name": "root", "pivot": [0, 0, 0], "locators": {
    "rouet_g": [-32, 63, 0], "rouet_d": [32, 63, 0], "rouet_moulin": [64, 63, 0]}}]


def pilon(x, y=0, z=0):
    return [cb([x - 4.5, y, z - 4.5], [9, 8, 9], "pierre"), cb([x - 1.5, y + 8, z - 1.5], [3, 46, 3], "bois"),
            cb([x - 2.5, y + 40, z - 2.5], [5, 3, 5], "bois")]


for k in (1, 2, 3):
    x = (k - 2) * 16
    os_pilons.append({"name": f"pilon_{k}", "parent": "root", "pivot": [x, 0, 0], "cubes": pilon(x),
                      "locators": {f"pilon_{k}": [x, 1.6, 0]}})
P_P = [-64, -11.5, -16]  # entites.ruine.pilons.tombe (17, 15, 11) moins l'origine, en ruine seulement
os_pilons.append({"name": "pilon_tombe", "parent": "root", "pivot": P_P, "rotation": [83, -29, 0], "cubes": pilon(*P_P)})
ecrire("resource-pack/models/entity/pilons.geo.json", geo("geometry.silooo.pilons", [10, 6, [0, 2, 0]], os_pilons))

RUINE, MARCHE = "query.property('silooo:ruine')", "!query.property('silooo:ruine')"
ecrire("resource-pack/render_controllers/machines.render_controllers.json", {"format_version": "1.8.0", "render_controllers": {
    "controller.render.silooo_ailes_moulin": {
        "geometry": "Geometry.default", "materials": [{"*": "Material.default"}], "textures": ["Texture.default"],
        # ruine : une seule aile (aile_1), sans toile, plus l'aile tombée au sol
        "part_visibility": [{"*": True}] + [{f"aile_{k}": MARCHE} for k in (0, 2, 3)] + [{f"toile_{k}": MARCHE} for k in range(4)]
                           + [{"aile_tombee": RUINE}]},
    "controller.render.silooo_pilons": {
        "geometry": "Geometry.default", "materials": [{"*": "Material.default"}], "textures": ["Texture.default"],
        # ruine : deux pilons debout, le troisième au sol
        "part_visibility": [{"*": True}, {"pilon_3": MARCHE}, {"pilon_tombe": RUINE}]},
}})


def entite(ident, geom, anims, animate, rc, extra=None):
    d = {"identifier": ident, "materials": {"default": "entity_alphatest"},
         "textures": {"default": "textures/entity/machines_carriere"}, "geometry": {"default": geom},
         "animations": anims, "scripts": {"animate": animate}, "render_controllers": [rc]}
    d.update(extra or {})
    return {"format_version": "1.10.0", "minecraft:client_entity": {"description": d}}


ecrire("resource-pack/entity/ailes_moulin.entity.json", entite(
    "silooo:ailes_moulin", "geometry.silooo.ailes_moulin",
    {"tourne": "animation.silooo_ailes_moulin.tourne", "ruine": "animation.silooo_ailes_moulin.ruine"},
    [{"tourne": MARCHE}, {"ruine": RUINE}], "controller.render.silooo_ailes_moulin"))
ecrire("resource-pack/entity/pilons.entity.json", entite(
    "silooo:pilons", "geometry.silooo.pilons",
    {"frappe": "animation.silooo_pilons.frappe", "effets": "animation.silooo_pilons.effets"},
    [{"frappe": MARCHE}, {"effets": MARCHE}], "controller.render.silooo_pilons",
    {"particle_effects": {"poussiere": "silooo:poussiere_bocard", "sciure": "silooo:sciure_rouet"},
     "sound_effects": {"pilon": "silooo.carriere.pilon"}}))

ecrire("propositions/machines.comportement.extrait.json", {
    "_note": "Pour addon/pack/entities/ : les deux entités de comportement, inertes comme Oooscar, avec la propriété que lisent les "
             "entités clientes. L'add-on les fait apparaître tournées vers le sud (yaw 0) : ailes au moyeu (26.5, 21.5, 14.05), "
             "pilons à (21.5, 16, 10.5), positions relatives à l'origine de la Carrière (carriere.json), et règle "
             "silooo:ruine à true sur l'île en ruine, false après la remise en route.",
    "properties": {"silooo:ruine": {"type": "bool", "default": False, "client_sync": True}},
    "identifiants": ["silooo:ailes_moulin", "silooo:pilons"]})

print("écrit :", ", ".join(sorted(SALARIE)), "|", ", ".join(OOOSCAR), "|", ", ".join(PARTICULES))
