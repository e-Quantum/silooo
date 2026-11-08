#!/usr/bin/env python3
# Fabrique tous les sons de Silooo par synthèse (numpy, scipy), puis les encode en Ogg Vorbis mono 44,1 kHz (ffmpeg).
# Aucun échantillon extérieur : chaque son sort des fonctions ci-dessous, avec une graine fixe, donc le script est la
# source et la preuve du droit d'usage. Relancer le script redonne les mêmes fichiers.
#
#   python3 -I sons/outils/fabriquer.py            écrit sons/resource-pack/sounds/** et sons/catalogue.json
#   python3 -I sons/outils/fabriquer.py --mp3 DIR  écrit aussi une copie MP3 de chaque son dans DIR (pour l'aperçu)
#   python3 -I sons/outils/fabriquer.py --verifier refait les signaux sans rien écrire et compare : empreinte des
#                                                  échantillons avant encodage (catalogue.json), sound_definitions.json,
#                                                  fichiers .ogg présents et cités. Sortie 1 au moindre écart. Sans ffmpeg.
#   --pack DIR et --catalogue FICHIER changent les chemins (dans le dépôt : tools/generer_sons.py, voir sons/README.md).
#
# Direction : atelier artisanal, bois, pierre, mécanique simple, pièces en laiton. Interface douce et courte. Les
# fréquences utiles restent au-dessus de 300 Hz pour passer sur un haut-parleur de téléphone.
import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np
from scipy import signal

SR = 44100
RACINE = Path(__file__).resolve().parent.parent  # sons/
PACK = RACINE / "resource-pack"
CRETE = 10 ** (-3 / 20)  # crête des sons courts : -3 dBFS, de la marge pour l'encodeur
QUALITE_VORBIS = "2"  # environ 64 kbit/s en mono : léger, sans artefact audible sur ces sons


# ------------------------------------------------------------------ briques de base

def silence(duree):
    return np.zeros(int(round(duree * SR)))


def temps(n):
    return np.arange(n) / SR


def amorti(n, tau):
    return np.exp(-temps(n) / tau)


def bruit(rng, n):
    return rng.standard_normal(n)


def passe_bande(x, bas, haut, ordre=2):
    sos = signal.butter(ordre, [bas, haut], btype="band", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def passe_bas(x, coupure, ordre=2):
    sos = signal.butter(ordre, coupure, btype="low", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def passe_haut(x, coupure, ordre=2):
    sos = signal.butter(ordre, coupure, btype="high", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def modes(f0, rapports, amplitudes, taus, duree, rng, desaccord=0.0):
    """Somme de sinus amortis : le modèle d'un objet frappé (bois, laiton, pierre)."""
    n = int(round(duree * SR))
    t = temps(n)
    x = np.zeros(n)
    for r, a, tau in zip(rapports, amplitudes, taus):
        f = f0 * r
        if f > 15000:
            continue
        phase = rng.uniform(0, 2 * np.pi)
        x += a * np.sin(2 * np.pi * f * t + phase) * np.exp(-t / tau)
        if desaccord:
            x += a * 0.5 * np.sin(2 * np.pi * f * (1 + desaccord) * t + phase) * np.exp(-t / tau)
    return x


def attaque(x, ms=1.5):
    n = max(1, int(ms * SR / 1000))
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    return x


def poser(buf, grain, t, gain=1.0, boucle=False):
    """Ajoute un grain dans le tampon à l'instant t. En boucle, ce qui dépasse revient au début."""
    i = int(round(t * SR))
    if boucle:
        idx = (np.arange(len(grain)) + i) % len(buf)
        np.add.at(buf, idx, grain * gain)
    else:
        fin = min(len(buf), i + len(grain))
        if fin > i:
            buf[i:fin] += grain[: fin - i] * gain
    return buf


def bruit_periodique(rng, duree, bas, haut, pente=0.0):
    """Bruit dont le spectre est taillé dans le domaine de Fourier : il boucle sans raccord."""
    n = int(round(duree * SR))
    spectre = rng.standard_normal(n // 2 + 1) + 1j * rng.standard_normal(n // 2 + 1)
    f = np.fft.rfftfreq(n, 1 / SR)
    masque = 1 / (1 + ((f - (bas + haut) / 2) / ((haut - bas) / 2)) ** 4)  # bande douce
    if pente:
        masque *= np.maximum(f, 20) ** (-pente)
    x = np.fft.irfft(spectre * masque, n)
    return x / (np.max(np.abs(x)) + 1e-12)


def lfo_periodique(duree, rng, harmoniques=(1, 2, 3), profondeur=0.5):
    """Variation lente qui fait un nombre entier de cycles sur la durée : la boucle reste continue."""
    n = int(round(duree * SR))
    t = temps(n)
    v = np.zeros(n)
    for k in harmoniques:
        v += rng.uniform(0.3, 1.0) / k * np.sin(2 * np.pi * k * t / duree + rng.uniform(0, 2 * np.pi))
    v /= np.max(np.abs(v)) + 1e-12
    return 1 + profondeur * v


# ------------------------------------------------------------------ objets frappés

def bois(rng, f0=1100, duree=0.09, sec=1.0):
    """Petit bloc de bois frappé."""
    x = modes(f0, [1, 2.57, 4.6], [1, 0.45, 0.18], [0.035 * sec, 0.018 * sec, 0.009 * sec], duree, rng)
    n = int(0.004 * SR)
    clic = passe_bande(bruit(rng, n), 2000, 7000) * np.linspace(1, 0, n) * 0.25
    x[:n] += clic
    return attaque(x)


def marimba(rng, f0, duree=0.6, eclat=1.0):
    """Lame de bois accordée (marimba) : le fil musical des retours de progression."""
    k = (440 / f0) ** 0.5
    x = modes(f0, [1, 3.93, 9.24], [1, 0.32 * eclat, 0.08 * eclat], [0.42 * k, 0.1 * k, 0.03 * k], duree, rng)
    n = int(0.003 * SR)
    x[:n] += passe_bas(bruit(rng, n), 3000) * np.linspace(1, 0, n) * 0.15
    return attaque(x, 2)


def cloche(rng, f0, duree=1.2, tau=0.9):
    """Clochette à main en laiton : fondamentale, douzième, et quelques partiels inharmoniques discrets."""
    x = modes(f0, [1, 2.4, 3.0, 4.6, 6.1], [1, 0.18, 0.42, 0.12, 0.05],
              [tau, tau * 0.35, tau * 0.6, tau * 0.25, tau * 0.12], duree, rng, desaccord=0.0015)
    return attaque(x, 1)


def piece(rng, f0=1650, duree=0.45, rebond=True):
    """Pièce de laiton qui tinte sur le bois du coffre, avec un petit rebond."""
    x = modes(f0, [1, 1.68, 2.48, 3.39, 4.4], [1, 0.75, 0.5, 0.32, 0.18],
              [0.30, 0.22, 0.15, 0.10, 0.07], duree, rng, desaccord=0.004)
    x = attaque(x, 0.8)
    if rebond:
        r = modes(f0 * 1.01, [1, 1.68, 2.48], [1, 0.7, 0.4], [0.12, 0.09, 0.06], duree, rng)
        poser(x, attaque(r, 0.8), 0.065, 0.35)
    return x


def choc_sourd(rng, f0=110, duree=0.3, tau=0.09, craquant=0.6, bas=400, haut=2500):
    """Choc lourd (pilon, couvercle) : une basse qui chute, et un craquement dans les médiums pour le téléphone."""
    n = int(round(duree * SR))
    t = temps(n)
    f = f0 * (1 + 0.7 * np.exp(-t / 0.015))
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / tau)
    c = passe_bande(bruit(rng, n), bas, haut) * np.exp(-t / 0.035)
    c /= np.max(np.abs(c)) + 1e-12
    return attaque(x + craquant * c, 0.5)


def gravier(rng, duree=0.3, densite=60, bas=900, haut=4500, tau=0.004):
    """Une poignée de petits cailloux : des clics brefs semés au hasard."""
    buf = silence(duree)
    for _ in range(int(densite * duree)):
        t0 = rng.uniform(0, duree * 0.85)
        g = attaque(modes(rng.uniform(bas, haut), [1, 1.7], [1, 0.4], [tau, tau * 0.6], 0.03, rng), 0.4)
        poser(buf, g, t0, rng.uniform(0.2, 1.0))
    return buf


def craquement(rng, duree, debut, fin, buf=None, t0=0.0, gain=1.0, boucle=False):
    """Bois qui grince : une suite de micro-chocs dont le rythme glisse (frottement collé-glissé)."""
    if buf is None:
        buf = silence(duree)
    t = 0.0
    while t < duree:
        p = t / duree
        rythme = debut + (fin - debut) * p
        env = np.sin(np.pi * p) ** 1.5
        g = attaque(modes(rng.uniform(560, 640), [1, 2.2, 3.7], [1, 0.5, 0.25], [0.006, 0.004, 0.003], 0.02, rng), 0.8)
        poser(buf, g, t0 + t, gain * env * rng.uniform(0.7, 1.0), boucle)
        t += 1 / rythme * rng.uniform(0.85, 1.15)
    return buf


def souffle(rng, duree, bas=300, haut=1400, courbe=None):
    """Souffle filtré (vent, fondu) dont l'enveloppe est donnée par courbe (0 à 1)."""
    n = int(round(duree * SR))
    p = np.linspace(0, 1, n)
    env = np.sin(np.pi * p) ** 2 if courbe is None else courbe(p)
    x = passe_bande(bruit(rng, n), bas, haut, ordre=2)
    x /= np.max(np.abs(x)) + 1e-12
    return x * env


# ------------------------------------------------------------------ les sons de Silooo

def s_clic(rng):
    return bois(rng, 1250, 0.07, sec=0.9)


def s_ouvrir(rng):
    buf = silence(0.26)
    poser(buf, bois(rng, 760, 0.12), 0.0, 0.8)
    poser(buf, bois(rng, 1010, 0.12), 0.07, 1.0)
    poser(buf, souffle(rng, 0.14, 1800, 5000) * 0.08, 0.02)
    return buf


def s_fermer(rng):
    buf = silence(0.22)
    poser(buf, bois(rng, 1010, 0.1), 0.0, 0.8)
    poser(buf, bois(rng, 700, 0.12), 0.06, 0.9)
    return buf


def s_refus(rng):
    # Deux « toc » de bois graves qui descendent un peu : non, sans buzzer.
    buf = silence(0.4)
    poser(buf, marimba(rng, 392, 0.3, eclat=1.6) * 0.9, 0.0)
    poser(buf, marimba(rng, 349.2, 0.3, eclat=1.6), 0.12)
    return buf


def s_jeton(rng):
    return piece(rng, 1650, 0.45)


def s_coffre(rng):
    # Le couvercle du coffre retombe, puis les pierres glissent dedans : le coffre « avale ».
    buf = silence(0.55)
    poser(buf, choc_sourd(rng, 170, 0.25, tau=0.05, craquant=1.0, bas=300, haut=1600), 0.0, 0.9)
    poser(buf, bois(rng, 420, 0.15, sec=2.0), 0.0, 0.9)
    poser(buf, gravier(rng, 0.35, densite=45, bas=700, haut=3000, tau=0.006), 0.07, 0.5)
    return buf


def s_encaisse(rng):
    # Une pluie de pièces qui s'accélère, la dernière plus franche.
    buf = silence(1.0)
    instants = [0.0, 0.12, 0.22, 0.30, 0.36, 0.41, 0.45]
    for i, t0 in enumerate(instants):
        poser(buf, piece(rng, 1650 * rng.uniform(0.92, 1.12), 0.45, rebond=False), t0, 0.45 + 0.05 * i)
    poser(buf, piece(rng, 1720, 0.5), 0.52, 0.9)
    return buf


def s_embauche(rng):
    buf = silence(0.7)
    poser(buf, marimba(rng, 587.3, 0.5), 0.0, 0.8)
    poser(buf, marimba(rng, 880.0, 0.55), 0.12, 1.0)
    return buf


def s_amelioration(rng):
    # Trois crans d'engrenage en bois, puis trois notes qui montent.
    buf = silence(0.8)
    for i in range(3):
        poser(buf, bois(rng, 900 + 60 * i, 0.05, sec=0.7), 0.035 * i, 0.5)
    for i, f in enumerate([523.3, 659.3, 784.0]):
        poser(buf, marimba(rng, f, 0.55), 0.13 + 0.07 * i, 0.75 + 0.1 * i)
    return buf


def s_palier(rng):
    # Le palier ×2 : l'arpège complet, et la clochette de laiton sur la note du haut.
    buf = silence(1.4)
    for i, f in enumerate([523.3, 659.3, 784.0, 1046.5]):
        poser(buf, marimba(rng, f, 0.6), 0.075 * i, 0.7 + 0.08 * i)
    poser(buf, cloche(rng, 1046.5, 1.1, tau=0.6), 0.225, 0.55)
    return buf


def s_prestige(rng):
    # Le prestige : un souffle qui monte, deux octaves d'arpège, deux cloches et quelques pièces.
    buf = silence(2.9)
    poser(buf, souffle(rng, 1.1, 300, 1500, courbe=lambda p: p ** 2 * (1 - p) ** 0.3) * 0.18, 0.0)
    for i, f in enumerate([523.3, 659.3, 784.0, 1046.5, 1318.5, 1568.0]):
        poser(buf, marimba(rng, f, 0.7), 0.75 + 0.07 * i, 0.55 + 0.06 * i)
    poser(buf, cloche(rng, 1046.5, 1.9, tau=1.1), 1.17, 0.6)
    poser(buf, cloche(rng, 1568.0, 1.7, tau=0.9), 1.19, 0.4)
    for i in range(5):
        poser(buf, piece(rng, 1650 * rng.uniform(0.95, 1.15), 0.4, rebond=False), 1.3 + 0.09 * i + rng.uniform(0, 0.03), 0.22)
    return buf


def s_etape(rng):
    # Une étape de mission ou de remise en état : toc toc au maillet, puis une note.
    buf = silence(0.75)
    poser(buf, bois(rng, 480, 0.12, sec=1.8), 0.0, 0.8)
    poser(buf, bois(rng, 480, 0.12, sec=1.8), 0.11, 0.9)
    poser(buf, marimba(rng, 880.0, 0.5), 0.24, 0.9)
    return buf


def s_pose(rng):
    # Une zone achetée ou un bâtiment posé : trois coups de maillet sur une poutre.
    buf = silence(0.55)
    for i in range(3):
        poser(buf, bois(rng, 380 + 15 * i, 0.15, sec=2.4), 0.13 * i, 0.75 + 0.1 * i)
    poser(buf, gravier(rng, 0.2, densite=25, bas=800, haut=2500), 0.27, 0.25)
    return buf


def s_alerte(rng):
    # Incident : la crécelle de bois de l'atelier, deux tours. Elle appelle sans agresser.
    buf = silence(0.95)
    t = 0.0
    while t < 0.85:
        p = t / 0.85
        env = np.sin(np.pi * p) ** 0.7 * (0.75 + 0.25 * np.sin(2 * np.pi * 2 * p) ** 2)
        poser(buf, bois(rng, rng.uniform(980, 1150), 0.04, sec=0.6), t, env * rng.uniform(0.75, 1.0))
        t += 1 / 24
    return buf


def s_repare(rng):
    buf = silence(0.8)
    poser(buf, bois(rng, 430, 0.12, sec=2.0), 0.0, 0.7)
    poser(buf, bois(rng, 450, 0.12, sec=2.0), 0.13, 0.8)
    poser(buf, marimba(rng, 659.3, 0.5), 0.27, 0.8)
    poser(buf, marimba(rng, 987.8, 0.55), 0.37, 1.0)
    return buf


def s_grince(rng):
    # L'engrenage grippé : un grincement de bois court, à rejouer de temps en temps pendant l'incident.
    return craquement(rng, 0.6, 38, 70, gain=1.0)


def s_cloche_annonce(rng):
    return cloche(rng, 1174.7, 1.1, tau=0.55)


def s_vote(rng):
    buf = silence(1.1)
    poser(buf, cloche(rng, 880.0, 1.0, tau=0.6), 0.0, 0.8)
    poser(buf, piece(rng, 1700, 0.45), 0.18, 0.6)
    return buf


def s_vote_party(rng):
    buf = silence(2.3)
    for i, f in enumerate([1046.5, 1318.5, 1568.0, 2093.0]):
        poser(buf, cloche(rng, f, 1.5, tau=0.7), 0.16 * i, 0.55 - 0.06 * i)
    for i, f in enumerate([784.0, 1046.5, 784.0, 1046.5, 1318.5]):
        poser(buf, marimba(rng, f, 0.4), 0.7 + 0.065 * i, 0.5)
    for i in range(6):
        poser(buf, piece(rng, 1650 * rng.uniform(0.92, 1.15), 0.4, rebond=False), 0.95 + 0.08 * i + rng.uniform(0, 0.03), 0.3)
    return buf


def s_fondu(rng):
    n = int(0.9 * SR)
    p = np.linspace(0, 1, n)
    x = bruit(rng, n)
    # Le souffle s'ouvre puis se referme : deux bandes croisées pour un passage d'air doux.
    a = passe_bande(x, 250, 700)
    b = passe_bande(x, 700, 1800)
    a /= np.max(np.abs(a)) + 1e-12
    b /= np.max(np.abs(b)) + 1e-12
    env = np.sin(np.pi * p) ** 2
    return attaque((a * (1 - p) + b * p * 0.7) * env, 5)


def s_fin_cinematique(rng):
    buf = silence(1.5)
    poser(buf, cloche(rng, 784.0, 1.3, tau=0.8), 0.0, 0.7)
    poser(buf, cloche(rng, 1174.7, 1.2, tau=0.7), 0.2, 0.55)
    return buf


def s_vent(rng):
    # Boucle de 8 s : deux bandes de bruit périodique, des rafales lentes en nombre entier de cycles.
    d = 8.0
    grave = bruit_periodique(rng, d, 120, 520) * lfo_periodique(d, rng, (1, 2), 0.45)
    aigu = bruit_periodique(rng, d, 500, 1500) * lfo_periodique(d, rng, (1, 3), 0.8) * 0.35
    return grave + aigu


def s_moulin(rng):
    # Boucle de 4 s, un tour d'ailes : quatre passages d'aile, l'arbre qui grince deux fois, les dents qui cliquent.
    d = 4.0
    n = int(d * SR)
    t = temps(n)
    passage = np.cos(np.pi * ((t * 4 / d) % 1 - 0.5)) ** 6  # quatre bosses par tour
    x = bruit_periodique(rng, d, 220, 900) * (0.15 + passage) * 0.55
    for t0 in (0.55, 2.55):
        craquement(rng, 0.55, 30, 52, buf=x, t0=t0, gain=0.55, boucle=True)
    for i in range(24):  # engrenage de bois, six dents par seconde
        poser(x, bois(rng, 700 + rng.uniform(-30, 30), 0.05, sec=0.8), i * d / 24, 0.22, boucle=True)
    x += bruit_periodique(rng, d, 60, 220) * 0.1  # grondement de l'arbre, discret
    return x


def s_pilon(rng):
    # Le pilon du bocard tombe sur la pierre : choc, éclats, poussière de gravier.
    buf = silence(0.4)
    poser(buf, choc_sourd(rng, 110, 0.35, tau=0.06, craquant=1.6, bas=450, haut=2800), 0.0, 1.0)
    poser(buf, gravier(rng, 0.25, densite=70, bas=1200, haut=5000, tau=0.003), 0.012, 0.55)
    return buf


def s_tapis(rng):
    # Boucle de 2 s : la toile frotte, les rouleaux de bois tapent doucement quatre fois.
    d = 2.0
    x = bruit_periodique(rng, d, 1200, 4500) * lfo_periodique(d, rng, (2, 4), 0.35) * 0.3
    for i in range(4):
        poser(x, bois(rng, 520 + rng.uniform(-20, 20), 0.08, sec=1.6), i * d / 4 + 0.01, 0.5, boucle=True)
    x += bruit_periodique(rng, d, 300, 800) * 0.12
    return x


def s_pioche(rng):
    buf = silence(0.3)
    pierre = modes(1900, [1, 1.53, 2.27, 3.1], [1, 0.7, 0.45, 0.3], [0.05, 0.035, 0.025, 0.015], 0.25, rng)
    poser(buf, attaque(pierre, 0.3), 0.0, 0.8)
    poser(buf, choc_sourd(rng, 180, 0.15, tau=0.03, craquant=0.9, bas=800, haut=4000), 0.0, 0.6)
    poser(buf, gravier(rng, 0.2, densite=50, bas=1500, haut=6000, tau=0.003), 0.02, 0.3)
    return buf


def s_pelle(rng):
    # La pelle entre dans le gravier : un grain serré qui monte puis retombe.
    n = int(0.32 * SR)
    p = np.linspace(0, 1, n)
    grains = passe_bande(bruit(rng, n), 600, 3200) * (rng.random(n) < 0.08)
    grains = passe_bande(grains, 500, 3500)
    grains /= np.max(np.abs(grains)) + 1e-12
    env = np.minimum(1, p / 0.15) * np.exp(-np.maximum(0, p - 0.15) / 0.25)
    buf = grains * env
    poser(buf, gravier(rng, 0.3, densite=60, bas=800, haut=3500), 0.0, 0.5)
    return buf


# ------------------------------------------------------------------ le catalogue
# Chaque entrée : événement, fichier (sous sounds/silooo/), fonction, boucle, réglages de sound_definitions, moment du jeu.
# Les hauteurs listées deviennent des variantes tirées au hasard par le jeu (un seul fichier, plusieurs « pitch »).

UI = dict(categorie="ui", min_distance=None, max_distance=None)

CATALOGUE = [
    # interface
    dict(evenement="silooo.ui.clic", fichier="ui/clic", f=s_clic, volume=0.5, hauteurs=[0.96, 1.0, 1.04], **UI,
         moment="Un bouton touché dans un formulaire, la boussole utilisée"),
    dict(evenement="silooo.ui.ouvrir", fichier="ui/ouvrir", f=s_ouvrir, volume=0.55, hauteurs=[1.0], **UI,
         moment="Un écran du menu s'ouvre"),
    dict(evenement="silooo.ui.fermer", fichier="ui/fermer", f=s_fermer, volume=0.45, hauteurs=[1.0], **UI,
         moment="Le menu se ferme ou le joueur revient en arrière"),
    dict(evenement="silooo.ui.refus", fichier="ui/refus", f=s_refus, volume=0.6, hauteurs=[1.0], **UI,
         moment="Le service refuse (pas assez de Donar, pas le PDG, usine verrouillée)"),
    # vente
    dict(evenement="silooo.vente.coffre", fichier="vente/coffre", f=s_coffre, volume=0.8, hauteurs=[0.95, 1.0, 1.05],
         categorie="block", min_distance=2.0, max_distance=12.0,
         moment="Le coffre de vente avale les marchandises (au coffre, pour ceux qui sont autour)"),
    dict(evenement="silooo.vente.jeton", fichier="vente/jeton", f=s_jeton, volume=0.7, hauteurs=[0.94, 1.0, 1.06],
         categorie="block", min_distance=2.0, max_distance=12.0,
         moment="Le jeton de Donar sort du coffre (avec la particule silooo:jeton_donar), un achat en boutique"),
    dict(evenement="silooo.vente.encaisse", fichier="vente/encaisse", f=s_encaisse, volume=0.7, hauteurs=[1.0], **UI,
         moment="Le joueur encaisse (collect), gains hors ligne compris, un palier du pass réclamé"),
    # progression
    dict(evenement="silooo.progression.embauche", fichier="progression/embauche", f=s_embauche, volume=0.7, hauteurs=[1.0], **UI,
         moment="Un salarié est embauché"),
    dict(evenement="silooo.progression.amelioration", fichier="progression/amelioration", f=s_amelioration, volume=0.7,
         hauteurs=[1.0], **UI, moment="Des niveaux d'usine achetés, un bâtiment de la ville amélioré"),
    dict(evenement="silooo.progression.palier", fichier="progression/palier", f=s_palier, volume=0.75, hauteurs=[1.0], **UI,
         moment="Une usine passe un palier ×2"),
    dict(evenement="silooo.progression.prestige", fichier="progression/prestige", f=s_prestige, volume=0.8, hauteurs=[1.0], **UI,
         moment="Le prestige est fait (écran avant / après)"),
    dict(evenement="silooo.progression.etape", fichier="progression/etape", f=s_etape, volume=0.7, hauteurs=[1.0], **UI,
         moment="Une étape de remise en état ou une mission terminée"),
    dict(evenement="silooo.ville.pose", fichier="ville/pose", f=s_pose, volume=0.7, hauteurs=[1.0], **UI,
         moment="Une zone achetée, un bâtiment posé dans la ville"),
    # incident
    dict(evenement="silooo.incident.alerte", fichier="incident/alerte", f=s_alerte, volume=0.6, hauteurs=[1.0],
         categorie="block", min_distance=3.0, max_distance=20.0,
         moment="Un incident s'ouvre sur l'usine (joué à la machine, s'entend sur l'île)"),
    dict(evenement="silooo.incident.repare", fichier="incident/repare", f=s_repare, volume=0.7, hauteurs=[1.0], **UI,
         moment="Le mini-jeu réussi, l'usine repart"),
    dict(evenement="silooo.carriere.grince", fichier="carriere/grince", f=s_grince, volume=0.5, hauteurs=[0.92, 1.0, 1.08],
         categorie="block", min_distance=1.0, max_distance=8.0,
         moment="Engrenage grippé pendant un incident (toutes les 3 à 5 s, près de l'engrenage)"),
    # annonces et votes
    dict(evenement="silooo.annonce.cloche", fichier="annonce/cloche", f=s_cloche_annonce, volume=0.5, hauteurs=[1.0], **UI,
         moment="Une annonce dans le tchat : invitation, gemmes reçues, commande terminée, boost collectif"),
    dict(evenement="silooo.vote.recu", fichier="vote/recu", f=s_vote, volume=0.6, hauteurs=[1.0], **UI,
         moment="Le vote du joueur est reçu et récompensé"),
    dict(evenement="silooo.vote.party", fichier="vote/party", f=s_vote_party, volume=0.7, hauteurs=[1.0], **UI,
         moment="Vote party : joué à chaque joueur présent"),
    # cinématique
    dict(evenement="silooo.cinematique.fondu", fichier="cinematique/fondu", f=s_fondu, volume=0.45, hauteurs=[1.0], **UI,
         moment="Chaque fondu au noir de la cinématique d'arrivée, et le voyage entre les îles"),
    dict(evenement="silooo.cinematique.fin", fichier="cinematique/fin", f=s_fin_cinematique, volume=0.6, hauteurs=[1.0], **UI,
         moment="Fin de la cinématique, le joueur reçoit la boussole"),
    # ambiance
    dict(evenement="silooo.ambiance.vent", fichier="ambiance/vent", f=s_vent, volume=0.25, hauteurs=[1.0], boucle=True,
         categorie="ambient", min_distance=None, max_distance=None,
         moment="Ambiance de la Carrière (et de toute île du ciel) : boucle de 8 s jouée au joueur"),
    # machines de la Carrière
    dict(evenement="silooo.carriere.moulin", fichier="carriere/moulin", f=s_moulin, volume=0.6, hauteurs=[1.0], boucle=True,
         categorie="block", min_distance=2.0, max_distance=10.0,
         moment="Le moulin à vent tourne : boucle de 4 s, un tour d'ailes, au moyeu"),
    dict(evenement="silooo.carriere.pilon", fichier="carriere/pilon", f=s_pilon, volume=0.7, hauteurs=[0.92, 1.0, 1.08],
         categorie="block", min_distance=2.0, max_distance=10.0,
         moment="Un pilon du bocard frappe (un son par coup)"),
    dict(evenement="silooo.carriere.tapis", fichier="carriere/tapis", f=s_tapis, volume=0.45, hauteurs=[1.0], boucle=True,
         categorie="block", min_distance=1.0, max_distance=6.0,
         moment="Le tapis en toile avance : boucle de 2 s, le long du tapis"),
    dict(evenement="silooo.carriere.pioche", fichier="carriere/pioche", f=s_pioche, volume=0.6, hauteurs=[0.93, 1.0, 1.07],
         categorie="block", min_distance=1.0, max_distance=8.0,
         moment="Un salarié pioche la pierre (avec silooo:eclats_pierre)"),
    dict(evenement="silooo.carriere.pelle", fichier="carriere/pelle", f=s_pelle, volume=0.5, hauteurs=[0.95, 1.0, 1.05],
         categorie="block", min_distance=1.0, max_distance=6.0,
         moment="Un salarié pellette le gravier"),
]


# ------------------------------------------------------------------ écriture

def finir(x, boucle):
    x = np.asarray(x, dtype=np.float64)
    if not boucle:
        # Coupe le silence de fin (sous -60 dB de la crête), puis un fondu de 10 ms.
        seuil = np.max(np.abs(x)) * 10 ** (-60 / 20)
        utile = np.nonzero(np.abs(x) > seuil)[0]
        x = x[: utile[-1] + 1] if len(utile) else x
        f = min(len(x), int(0.01 * SR))
        x[-f:] *= np.linspace(1, 0, f)
        x = x - np.mean(x)
        return x * CRETE / (np.max(np.abs(x)) + 1e-12)
    # Une boucle : niveau moyen fixé (-20 dBFS RMS), crête bornée à -3 dBFS.
    x = x - np.mean(x)
    rms = np.sqrt(np.mean(x ** 2))
    x = x * (10 ** (-20 / 20)) / (rms + 1e-12)
    crete = np.max(np.abs(x))
    if crete > CRETE:
        x *= CRETE / crete
    return x


def ecrire_wav(chemin, x):
    with wave.open(str(chemin), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm(x).tobytes())


def encoder(wav, sortie, codec):
    sortie.parent.mkdir(parents=True, exist_ok=True)
    args = ["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-map_metadata", "-1", "-ac", "1", "-ar", str(SR)]
    if codec == "ogg":
        args += ["-c:a", "libvorbis", "-q:a", QUALITE_VORBIS]
    else:
        args += ["-c:a", "libmp3lame", "-q:a", "5"]
    subprocess.run(args + [str(sortie)], check=True)


def decoder(chemin):
    brut = subprocess.run(["ffmpeg", "-v", "error", "-i", str(chemin), "-f", "f32le", "-ac", "1", "-"],
                          check=True, capture_output=True).stdout
    return np.frombuffer(brut, dtype="<f4")


def db(v):
    return round(20 * np.log10(max(v, 1e-9)), 1)


def pcm(x):
    """Les échantillons tels qu'ils partent à l'encodeur : entiers 16 bits."""
    return np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")


def empreinte(x):
    # Empreinte des échantillons avant encodage : elle ne dépend pas de la version de ffmpeg ni de libvorbis.
    return hashlib.sha256(pcm(x).tobytes()).hexdigest()


def signaux():
    """Refait chaque son du catalogue, dans l'ordre, avec sa graine fixe."""
    for i, s in enumerate(CATALOGUE):
        rng = np.random.default_rng(20261107 + i)
        yield i, s, finir(s["f"](rng), s.get("boucle", False))


def definition(s):
    entree = {"category": s["categorie"]}
    if s["min_distance"] is not None:
        entree["min_distance"] = s["min_distance"]
        entree["max_distance"] = s["max_distance"]
    entree["sounds"] = [
        {"name": f"sounds/silooo/{s['fichier']}", "volume": s["volume"], "pitch": h, "load_on_low_memory": True}
        for h in s["hauteurs"]
    ]
    return entree


def texte_definitions():
    doc = {"format_version": "1.20.20", "sound_definitions": {s["evenement"]: definition(s) for s in CATALOGUE}}
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def verifier(pack, catalogue):
    """Refait les signaux sans rien écrire et les compare au pack et au catalogue. Rend le nombre d'écarts."""
    ecarts = []
    try:
        notes = {l["evenement"]: l for l in json.loads(catalogue.read_text(encoding="utf-8"))["sons"]}
    except (OSError, ValueError, KeyError) as e:
        print(f"fabriquer.py --verifier : catalogue illisible ({catalogue}) : {e}", file=sys.stderr)
        return 1
    defs = pack / "sounds" / "sound_definitions.json"
    if not defs.is_file() or defs.read_text(encoding="utf-8") != texte_definitions():
        ecarts.append(f"{defs} : absent ou différent de ce que le script écrit")
    attendus = set()
    for _, s, x in signaux():
        ev = s["evenement"]
        ogg = pack / "sounds" / "silooo" / f"{s['fichier']}.ogg"
        attendus.add(ogg.resolve())
        if not ogg.is_file():
            ecarts.append(f"{ev} : fichier absent ({ogg})")
        note = notes.pop(ev, None)
        if note is None:
            ecarts.append(f"{ev} : absent du catalogue")
        elif note.get("empreinte_pcm") != empreinte(x):
            ecarts.append(f"{ev} : les échantillons refaits ne correspondent plus à l'empreinte du catalogue")
    for ev in notes:
        ecarts.append(f"{ev} : dans le catalogue, plus dans le script")
    dossier = pack / "sounds" / "silooo"
    if dossier.is_dir():
        for f in sorted(dossier.rglob("*")):
            if f.is_file() and f.resolve() not in attendus:
                ecarts.append(f"{f} : fichier qu'aucun événement ne cite")
    for e in ecarts:
        print(f"fabriquer.py --verifier : {e}", file=sys.stderr)
    if not ecarts:
        print(f"fabriquer.py --verifier : {len(CATALOGUE)} sons conformes")
    return len(ecarts)


def arguments():
    p = argparse.ArgumentParser(description="Fabrique ou vérifie les sons de Silooo.")
    p.add_argument("--verifier", action="store_true",
                   help="refait les signaux sans rien écrire et les compare au pack et au catalogue (sortie 1 en cas d'écart)")
    p.add_argument("--pack", type=Path, default=PACK, help="le dossier resource-pack (défaut : %(default)s)")
    p.add_argument("--catalogue", type=Path, default=RACINE / "catalogue.json", help="le catalogue (défaut : %(default)s)")
    p.add_argument("--mp3", type=Path, help="écrit aussi une copie MP3 de chaque son dans ce dossier (pour l'aperçu)")
    return p.parse_args()


def main():
    args = arguments()
    if args.verifier:
        sys.exit(1 if verifier(args.pack, args.catalogue) else 0)
    pack, mp3 = args.pack, args.mp3
    lignes = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, s, x in signaux():
            boucle = s.get("boucle", False)
            wav = Path(tmp) / f"{i}.wav"
            ecrire_wav(wav, x)
            ogg = pack / "sounds" / "silooo" / f"{s['fichier']}.ogg"
            encoder(wav, ogg, "ogg")
            if mp3:
                mp3.mkdir(parents=True, exist_ok=True)
                encoder(wav, mp3 / f"{s['evenement']}.mp3", "mp3")
            relu = decoder(ogg)
            lignes.append({
                "evenement": s["evenement"],
                "fichier": f"sounds/silooo/{s['fichier']}.ogg",
                "moment": s["moment"],
                "boucle": boucle,
                "categorie": s["categorie"],
                "volume": s["volume"],
                "min_distance": s["min_distance"],
                "max_distance": s["max_distance"],
                "hauteurs": s["hauteurs"],
                "duree_s": round(len(x) / SR, 3),
                "octets": ogg.stat().st_size,
                "crete_dbfs": db(float(np.max(np.abs(relu)))),
                "rms_dbfs": db(float(np.sqrt(np.mean(relu.astype(np.float64) ** 2)))),
                "empreinte_pcm": empreinte(x),
            })
    (pack / "sounds").mkdir(parents=True, exist_ok=True)
    (pack / "sounds" / "sound_definitions.json").write_text(texte_definitions(), encoding="utf-8")
    total = sum(l["octets"] for l in lignes)
    args.catalogue.write_text(
        json.dumps({"total_octets": total, "sons": lignes}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for l in lignes:
        print(f"{l['evenement']:34s} {l['duree_s']:6.2f} s {l['octets']:7d} o  crête {l['crete_dbfs']:6.1f}  rms {l['rms_dbfs']:6.1f}")
    print(f"total : {len(lignes)} sons, {total} octets ({total / 1024:.0f} Ko)")


if __name__ == "__main__":
    main()
