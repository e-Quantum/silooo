#!/usr/bin/env python3
"""Compose la bannière de la pop-up Discord de la salle d'attente (320 × 80 px, le quart du rapport de l'affiche).

Ciel `ciel`, nuages `ciel-clair`, une bande d'herbe et de terre (design/decor/), le logo à gauche, Oooscar à droite.
Tout vient de design/ : aucun dessin nouveau. Les nuages sont des rectangles à coins coupés, sans dégradé.

Usage : python3 tools/generer_banniere_discord.py [sortie.png]
        (par défaut resource-pack-attente/textures/ui/silooo_discord/banniere.png)
Demande Pillow. Relancer le script redonne le même fichier.
"""
import sys
from pathlib import Path

from PIL import Image

RACINE = Path(__file__).resolve().parent.parent
SORTIE = Path(sys.argv[1]) if len(sys.argv) > 1 else RACINE / "resource-pack-attente/textures/ui/silooo_discord/banniere.png"

CIEL = (0x8F, 0xB8, 0xD6, 255)
CIEL_CLAIR = (0xBE, 0xDA, 0xEC, 255)
LARGEUR, HAUTEUR = 320, 80
BANDE = 16  # la hauteur d'une tuile de décor


def nuage(image: Image.Image, x: int, y: int, largeur: int) -> None:
    """Un nuage : un corps, une bosse dessus, les coins du corps coupés d'un pixel."""
    corps = Image.new("RGBA", (largeur, 6), CIEL_CLAIR)
    for coin in ((0, 0), (largeur - 1, 0), (0, 5), (largeur - 1, 5)):
        corps.putpixel(coin, (0, 0, 0, 0))
    bosse = Image.new("RGBA", (largeur // 2, 4), CIEL_CLAIR)
    image.alpha_composite(corps, (x, y + 4))
    image.alpha_composite(bosse, (x + largeur // 4, y))


def main() -> None:
    image = Image.new("RGBA", (LARGEUR, HAUTEUR), CIEL)
    for x, y, largeur in ((150, 8, 36), (232, 24, 28), (22, 30, 24), (270, 4, 20)):
        nuage(image, x, y, largeur)

    herbe = Image.open(RACINE / "design/decor/terre-herbe.png").convert("RGBA")
    for x in range(0, LARGEUR, herbe.size[0]):
        image.alpha_composite(herbe, (x, HAUTEUR - BANDE))

    logo = Image.open(RACINE / "design/logo/silooo-logo-128.png").convert("RGBA")
    hauteur_logo = 56
    logo = logo.resize((round(logo.size[0] * hauteur_logo / logo.size[1]), hauteur_logo), Image.LANCZOS)
    image.alpha_composite(logo, (150 - logo.size[0] // 2, HAUTEUR - BANDE - hauteur_logo + 6))

    oooscar = Image.open(RACINE / "design/mascotte/oooscar-joie.png").convert("RGBA")
    hauteur_oooscar = 62
    oooscar = oooscar.resize((round(oooscar.size[0] * hauteur_oooscar / oooscar.size[1]), hauteur_oooscar), Image.LANCZOS)
    image.alpha_composite(oooscar, (258, HAUTEUR - BANDE - hauteur_oooscar + 8))

    SORTIE.parent.mkdir(parents=True, exist_ok=True)
    image.save(SORTIE, optimize=True)
    print(f"{SORTIE} : {LARGEUR} × {HAUTEUR} px")


if __name__ == "__main__":
    main()
