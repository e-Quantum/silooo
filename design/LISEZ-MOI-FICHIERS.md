# Les fichiers du design system

`design/README.md` est la charte, exportée de l'artifact « Silooo Atelier ». Dans `composants/bundle.css`,
les adresses `/_blob/<id>` désignent les textures de `design/textures/` (même nom que dans la charte) :
remplace-les par des chemins relatifs quand tu t'en sers.

- `textures/` : textures 9-slice et HUD, taille native (1 pixel = 3 pixels d'écran).
- `icones/` : icônes 16 × 16.
- `decor/` : tuiles 16 × 16 pour les maquettes (en jeu, ce sont les vrais blocs).
- `mascotte/` : Oooscar, cinq expressions, PNG 512 px et SVG.
- `logo/` : le pictogramme, SVG et PNG 512, 128, 64.
- `glyphes.json` : la planche de glyphes du pack (une case par icône et par visage d'Oooscar), que
  `tools/generer_glyphes.mjs` compose dans `resource-pack/font/glyph_E2.png`.
- Maquettes : https://claude.ai/artifact/7uBWLYneMcH3uwBMWNwXbz (privé, compte de Kr).
