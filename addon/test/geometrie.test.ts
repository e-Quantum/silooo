import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { IlesDefinitions } from "../src/generated/definitions";
import {
  arrivalAt,
  arrivalOf,
  discCells,
  discRows,
  doorSide,
  footprint,
  frameOf,
  frameOfIsland,
  hash32,
  inFrame,
  islandCenter,
  islandRadius,
  mainCenter,
  surfaceY,
  usineIle,
  viewPoint,
} from "../src/monde/geometrie";
import { ILES } from "../src/monde/definitions";

const ORIGINE = { x: 1200, z: -600 };

function usine(id: string): NonNullable<ReturnType<typeof usineIle>> {
  const trouvee = usineIle(ILES, id);
  assert.ok(trouvee !== undefined, id);
  return trouvee;
}

describe("centres", () => {
  it("le centre de l'île principale est au milieu de la case", () => {
    assert.deepEqual(mainCenter(ORIGINE, ILES), { x: 1500, z: -300 });
  });

  it("la Carrière (angle 0) est à l'est de l'île principale, sur le carré de l'anneau", () => {
    assert.deepEqual(islandCenter(ORIGINE, usine("carriere"), ILES), { x: 1500 + 280, z: -300 });
  });

  it("un angle de 90° donne le sud, 180° l'ouest, 270° le nord", () => {
    const centre = mainCenter(ORIGINE, ILES);
    const sud = islandCenter(ORIGINE, { angle: 90 }, ILES);
    const ouest = islandCenter(ORIGINE, { angle: 180 }, ILES);
    const nord = islandCenter(ORIGINE, { angle: 270 }, ILES);
    assert.deepEqual(sud, { x: centre.x, z: centre.z + 280 });
    assert.deepEqual(ouest, { x: centre.x - 280, z: centre.z });
    assert.deepEqual(nord, { x: centre.x, z: centre.z - 280 });
  });

  it("un angle de 45° tombe sur le coin du carré", () => {
    const centre = mainCenter(ORIGINE, ILES);
    assert.deepEqual(islandCenter(ORIGINE, { angle: 45 }, ILES), { x: centre.x + 280, z: centre.z + 280 });
  });

  it("toutes les mini-îles sont à la distance de l'anneau (au bloc près) et dans la case", () => {
    const centre = mainCenter(ORIGINE, ILES);
    for (const u of ILES.usines) {
      const c = islandCenter(ORIGINE, u, ILES);
      const chebyshev = Math.max(Math.abs(c.x - centre.x), Math.abs(c.z - centre.z));
      assert.ok(Math.abs(chebyshev - ILES.anneau.demiCote) <= 1, `${u.id} : ${chebyshev}`);
      assert.ok(c.x - ORIGINE.x - ILES.plateforme.rayon >= 0 && c.x - ORIGINE.x + ILES.plateforme.rayon < 600, u.id);
      assert.ok(c.z - ORIGINE.z - ILES.plateforme.rayon >= 0 && c.z - ORIGINE.z + ILES.plateforme.rayon < 600, u.id);
    }
  });

  it("changer l'origine déplace tout du même vecteur", () => {
    const a = islandCenter({ x: 0, z: 0 }, usine("scierie"), ILES);
    const b = islandCenter({ x: 600, z: 1200 }, usine("scierie"), ILES);
    assert.deepEqual(b, { x: a.x + 600, z: a.z + 1200 });
  });
});

describe("repère d'une mini-île", () => {
  it("l'avant va vers l'île principale, la droite est à droite quand on regarde l'avant", () => {
    const frame = frameOfIsland(ORIGINE, usine("carriere"), ILES);
    assert.deepEqual([frame.avantX, frame.avantZ], [-1, 0]);
    // On regarde vers l'ouest : la droite est le nord (z décroissant).
    assert.deepEqual([frame.droiteX, frame.droiteZ], [0, -1]);
  });

  it("au sud de l'île principale, l'avant est le nord et la droite l'est", () => {
    const frame = frameOf({ x: 0, z: 100 }, { x: 0, z: 0 });
    assert.deepEqual([frame.avantX, frame.avantZ], [0, -1]);
    assert.deepEqual([frame.droiteX, frame.droiteZ], [1, 0]);
  });

  it("deux îles confondues avec l'île principale ne font pas de division par zéro", () => {
    const frame = frameOf({ x: 5, z: 5 }, { x: 5, z: 5 });
    assert.ok(Number.isFinite(frame.avantX) && Number.isFinite(frame.avantZ));
  });

  it("inFrame compte `avant` vers l'île principale et `droite` à sa droite", () => {
    const frame = frameOf({ x: 100, z: 0 }, { x: 0, z: 0 });
    assert.deepEqual(inFrame(frame, 5, 0), { x: 95, z: 0 });
    assert.deepEqual(inFrame(frame, 0, 3), { x: 100, z: -3 });
    assert.deepEqual(inFrame(frame, -2, -1), { x: 102, z: 1 });
  });

  it("viewPoint vise le centre du bloc et monte de `haut` au-dessus du sol", () => {
    const frame = frameOf({ x: 100, z: 0 }, { x: 0, z: 0 });
    assert.deepEqual(viewPoint(frame, { avant: 10, droite: 0, haut: 4 }, ILES), { x: 90.5, y: surfaceY(ILES) + 4, z: 0.5 });
  });

  it("le sol est au-dessus de la face du dessus", () => {
    assert.equal(surfaceY(ILES), ILES.hauteur + 1);
  });
});

describe("la porte", () => {
  it("est sur la face tournée vers l'île principale", () => {
    assert.deepEqual(doorSide({ avantX: -1, avantZ: 0 }), { axis: "x", sign: -1 });
    assert.deepEqual(doorSide({ avantX: 1, avantZ: 0 }), { axis: "x", sign: 1 });
    assert.deepEqual(doorSide({ avantX: 0, avantZ: -1 }), { axis: "z", sign: -1 });
    assert.deepEqual(doorSide({ avantX: 0, avantZ: 1 }), { axis: "z", sign: 1 });
  });

  it("pour une diagonale, prend l'axe le plus marqué (x en cas d'égalité)", () => {
    assert.deepEqual(doorSide({ avantX: -0.8, avantZ: 0.6 }), { axis: "x", sign: -1 });
    assert.deepEqual(doorSide({ avantX: 0.6, avantZ: -0.8 }), { axis: "z", sign: -1 });
    assert.deepEqual(doorSide({ avantX: -0.7, avantZ: 0.7 }), { axis: "x", sign: -1 });
  });
});

describe("arrivée", () => {
  it("le joueur apparaît sur l'île de la Carrière, du côté de l'île principale, à `distance` blocs du centre", () => {
    const arrivee = arrivalOf(ORIGINE, ILES);
    assert.ok(arrivee !== undefined);
    const centre = islandCenter(ORIGINE, usine("carriere"), ILES);
    assert.equal(arrivee.factoryId, "carriere");
    assert.deepEqual(arrivee.position, { x: centre.x - (usine("carriere").apparition ?? ILES.apparition.distance) + 0.5, y: surfaceY(ILES), z: centre.z + 0.5 });
  });

  it("regarde le bâtiment, à hauteur d'yeux", () => {
    const arrivee = arrivalOf(ORIGINE, ILES);
    assert.ok(arrivee !== undefined);
    const centre = islandCenter(ORIGINE, usine("carriere"), ILES);
    assert.deepEqual(arrivee.facing, { x: centre.x + 0.5, y: surfaceY(ILES) + 1.6, z: centre.z + 0.5 });
  });

  it("Oooscar se tient devant le bâtiment, à côté du joueur", () => {
    const arrivee = arrivalOf(ORIGINE, ILES);
    assert.ok(arrivee !== undefined);
    const centre = islandCenter(ORIGINE, usine("carriere"), ILES);
    // avant 3 vers l'ouest, droite 2 vers le nord.
    assert.deepEqual(arrivee.oooscar, { x: centre.x - ILES.apparition.oooscar.avant + 0.5, y: surfaceY(ILES), z: centre.z - 2 + 0.5 });
    const ecart = Math.hypot(arrivee.oooscar.x - arrivee.position.x, arrivee.oooscar.z - arrivee.position.z);
    assert.ok(ecart > 1 && ecart < 4, `${ecart}`);
  });

  it("le joueur et Oooscar ne sont ni dans les murs, ni sur un poteau de chantier, ni devant la porte", () => {
    const arrivee = arrivalOf(ORIGINE, ILES);
    assert.ok(arrivee !== undefined);
    const batiment = usine("carriere");
    const centre = islandCenter(ORIGINE, batiment, ILES);
    const fp = footprint(centre, batiment);
    const porte = doorSide(arrivee.frame);
    const devantLaPorte = {
      x: porte.axis === "x" ? (porte.sign > 0 ? fp.x1 + 1 : fp.x0 - 1) : centre.x,
      z: porte.axis === "z" ? (porte.sign > 0 ? fp.z1 + 1 : fp.z0 - 1) : centre.z,
    };
    const interdits = new Set<string>([`${devantLaPorte.x},${devantLaPorte.z}`]);
    for (let x = fp.x0; x <= fp.x1; x += 1) for (let z = fp.z0; z <= fp.z1; z += 1) interdits.add(`${x},${z}`);
    for (const x of [fp.x0 - 1, fp.x1 + 1]) for (const z of [fp.z0 - 1, fp.z1 + 1]) interdits.add(`${x},${z}`);
    for (const point of [arrivee.position, arrivee.oooscar]) {
      assert.ok(!interdits.has(`${Math.floor(point.x)},${Math.floor(point.z)}`), `${point.x},${point.z}`);
    }
  });

  it("sans usine de départ dans le fichier, il n'y a pas d'arrivée", () => {
    const sans = { ...ILES, apparition: { ...ILES.apparition, usine: "ailleurs" } } as unknown as IlesDefinitions;
    assert.equal(arrivalOf(ORIGINE, sans), undefined);
  });
});

describe("disque", () => {
  it("est symétrique, plein, et contient son centre", () => {
    for (const rayon of [0, 1, 2, 5, 7]) {
      const lignes = discRows(rayon);
      const cellules = discCells(rayon);
      assert.equal(cellules.length, lignes.reduce((somme, l) => somme + 2 * l.demiLargeur + 1, 0));
      assert.ok(cellules.some((c) => c.dx === 0 && c.dz === 0));
      for (const c of cellules) assert.ok(cellules.some((o) => o.dx === -c.dx && o.dz === c.dz && o.dx !== undefined), `${rayon}`);
    }
  });

  it("le rayon 7 fait une plateforme ronde de 15 blocs de large, et un carré de 3 pour le rayon 1", () => {
    assert.equal(Math.max(...discRows(7).map((l) => 2 * l.demiLargeur + 1)), 15);
    assert.equal(discRows(7).length, 15);
    assert.equal(discCells(1).length, 9);
    assert.equal(discCells(0).length, 1);
  });

  it("les coins du bâtiment et de ses abords tiennent dans la face du dessus", () => {
    const { rayon } = ILES.plateforme;
    const cellules = new Set(discCells(rayon).map((c) => `${c.dx},${c.dz}`));
    for (const u of ILES.usines) {
      const demiX = (u.largeur - 1) / 2 + 1;
      const demiZ = (u.profondeur - 1) / 2 + 1;
      for (const dx of [-demiX, demiX]) {
        for (const dz of [-demiZ, demiZ]) assert.ok(cellules.has(`${dx},${dz}`), `${u.id} ${dx},${dz}`);
      }
    }
  });
});

describe("outils", () => {
  it("l'emprise d'un bâtiment est centrée, coins inclus", () => {
    assert.deepEqual(footprint({ x: 10, z: 20 }, { largeur: 7, profondeur: 5 }), { x0: 7, x1: 13, z0: 18, z1: 22 });
  });

  it("hash32 est le FNV-1a de 32 bits, stable", () => {
    assert.equal(hash32(""), 0x811c9dc5);
    assert.equal(hash32("a"), 0xe40c292c);
    assert.equal(hash32("carriere:mur:1:2:3"), hash32("carriere:mur:1:2:3"));
    assert.notEqual(hash32("carriere:mur:1:2:3"), hash32("carriere:mur:1:2:4"));
  });

  it("l'usine inconnue n'a pas d'île", () => {
    assert.equal(usineIle(ILES, "ailleurs"), undefined);
  });
});

describe("arrivée sur chaque île de la carte", () => {
  it("l'arrivée du départ est celle de l'usine de départ des définitions", () => {
    assert.deepEqual(arrivalOf(ORIGINE, ILES), arrivalAt(ORIGINE, ILES, ILES.apparition.usine));
  });

  it("une usine qui n'a pas d'île dans iles.json n'a pas de point d'arrivée", () => {
    assert.equal(arrivalAt(ORIGINE, ILES, "usine-du-futur"), undefined);
  });

  it("chaque île a un point d'arrivée distinct, sur son propre repère", () => {
    const points = new Set<string>();
    for (const batiment of ILES.usines) {
      const arrivee = arrivalAt(ORIGINE, ILES, batiment.id);
      assert.ok(arrivee !== undefined, batiment.id);
      assert.equal(arrivee.factoryId, batiment.id);
      points.add(`${arrivee.position.x},${arrivee.position.z}`);
    }
    assert.equal(points.size, ILES.usines.length);
  });

  it("le point d'arrivée est sur la plateforme de l'île, hors du bâtiment, sur la face du dessus", () => {
    for (const batiment of ILES.usines) {
      const arrivee = arrivalAt(ORIGINE, ILES, batiment.id);
      assert.ok(arrivee !== undefined, batiment.id);
      const centre = islandCenter(ORIGINE, batiment, ILES);
      const fp = footprint(centre, batiment);
      const x = Math.floor(arrivee.position.x);
      const z = Math.floor(arrivee.position.z);
      const dansLeBatiment = x >= fp.x0 && x <= fp.x1 && z >= fp.z0 && z <= fp.z1;
      assert.ok(!dansLeBatiment, `${batiment.id} : dans le bâtiment`);
      const plateforme = new Set(discCells(islandRadius(ILES, batiment)).map((c) => `${centre.x + c.dx},${centre.z + c.dz}`));
      assert.ok(plateforme.has(`${x},${z}`), `${batiment.id} : hors de la plateforme (${x},${z})`);
      assert.equal(arrivee.position.y, surfaceY(ILES), batiment.id);
    }
  });

  it("le point d'arrivée est sur l'île choisie, pas sur sa voisine : plus près de son centre que de tout autre", () => {
    for (const batiment of ILES.usines) {
      const arrivee = arrivalAt(ORIGINE, ILES, batiment.id);
      assert.ok(arrivee !== undefined, batiment.id);
      const distance = (autre: { readonly angle: number }): number => {
        const centre = islandCenter(ORIGINE, autre, ILES);
        return Math.hypot(arrivee.position.x - centre.x, arrivee.position.z - centre.z);
      };
      const siennes = distance(batiment);
      for (const autre of ILES.usines) {
        if (autre.id !== batiment.id) assert.ok(siennes < distance(autre), `${batiment.id} / ${autre.id}`);
      }
    }
  });

  it("le point d'arrivée suit l'origine de l'archipel : une autre case, la même place dans l'île", () => {
    const decalage = { x: 3000, z: 1500 };
    for (const batiment of ILES.usines) {
      const ici = arrivalAt(ORIGINE, ILES, batiment.id);
      const la = arrivalAt({ x: ORIGINE.x + decalage.x, z: ORIGINE.z + decalage.z }, ILES, batiment.id);
      assert.ok(ici !== undefined && la !== undefined, batiment.id);
      assert.deepEqual(
        { x: la.position.x - ici.position.x, y: la.position.y - ici.position.y, z: la.position.z - ici.position.z },
        { x: decalage.x, y: 0, z: decalage.z },
        batiment.id,
      );
    }
  });
});
