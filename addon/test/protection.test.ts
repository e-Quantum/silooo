// La protection de la Carrière : l'île entière, sauf le front de taille ; rien hors des îles connues.
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { workshopBlock } from "../src/monde/carriere";
import { ILES } from "../src/monde/definitions";
import { frameOfIsland, islandCenter, usineIle, type Xz } from "../src/monde/geometrie";
import { carriereProtection } from "../src/monde/protection";

const ORIGINE: Xz = { x: 0, z: 0 };
const usine = usineIle(ILES, ILES.apparition.usine)!;
const frame = frameOfIsland(ORIGINE, usine, ILES);
const point = (avant: number, droite: number, haut: number) => workshopBlock(frame, ILES.hauteur, { avant, droite, haut });

describe("protection de la Carrière", () => {
  it("laisse casser le front de taille, et seulement lui", () => {
    const zone = ILES.atelier.libre[0]!;
    const milieu = point((zone.de.avant + zone.a.avant) / 2, (zone.de.droite + zone.a.droite) / 2, zone.de.haut);
    assert.equal(carriereProtection(ILES, milieu, [ORIGINE]), "libre");
  });

  it("protège tout le reste de l'île : machines, coffre de vente, bureau, sol, dessous de l'île", () => {
    for (const machine of ILES.atelier.machines) assert.equal(carriereProtection(ILES, point(machine.position.avant, machine.position.droite, machine.position.haut), [ORIGINE]), "protege", machine.role);
    const centre = islandCenter(ORIGINE, usine, ILES);
    assert.equal(carriereProtection(ILES, { x: centre.x, y: ILES.hauteur, z: centre.z }, [ORIGINE]), "protege", "le sol");
    assert.equal(carriereProtection(ILES, { x: centre.x, y: ILES.hauteur - ILES.atelier.solStructure, z: centre.z }, [ORIGINE]), "protege", "la pointe de l'île");
  });

  it("ne protège rien hors des îles connues, ni avant que le relevé ait vu l'archipel", () => {
    const centre = islandCenter(ORIGINE, usine, ILES);
    assert.equal(carriereProtection(ILES, { x: centre.x, y: ILES.hauteur, z: centre.z }, []), "hors");
    assert.equal(carriereProtection(ILES, { x: centre.x + 40, y: ILES.hauteur, z: centre.z }, [ORIGINE]), "hors");
    assert.equal(carriereProtection(ILES, { x: centre.x, y: ILES.hauteur + ILES.atelier.hauteurNettoyage + 1, z: centre.z }, [ORIGINE]), "hors");
  });

  it("protège la Carrière de chaque archipel connu", () => {
    const autre: Xz = { x: 600, z: 0 };
    const centre = islandCenter(autre, usine, ILES);
    assert.equal(carriereProtection(ILES, { x: centre.x, y: ILES.hauteur, z: centre.z }, [ORIGINE, autre]), "protege");
  });
});
