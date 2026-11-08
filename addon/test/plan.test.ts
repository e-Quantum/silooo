// Le plan de blocs d'une mini-île, rejoué sur un faux monde : ce qu'on y voit après la pose, état par état.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ILES } from "../src/monde/definitions";
import { applyOp } from "../src/monde/constructeur";
import { discCells, doorSide, footprint, frameOfIsland, islandCenter, usineIle, type Xz } from "../src/monde/geometrie";
import { workshopBlock } from "../src/monde/carriere";
import { planIsland, signText, type IslandPlan } from "../src/monde/plan";
import type { FactoryState } from "../src/protocol";
import { FACTORY_NAMES, factoryName } from "../src/ui/textes";
import { fakeWorld, type FakeWorld } from "./monde-helpers";

const ORIGINE: Xz = { x: 1200, z: -600 };
const ETATS: FactoryState[] = ["EN_RUINE", "REMISE_EN_ETAT", "ACTIVE"];
const M = ILES.materiaux;
// Le préfabriqué générique reste couvert pour les quatorze autres usines ; atelier.test.ts couvre la Carrière.
const GENERIQUES = ILES.usines.filter((u) => u.id !== ILES.apparition.usine);

function usine(id: string): NonNullable<ReturnType<typeof usineIle>> {
  const trouvee = usineIle(ILES, id);
  assert.ok(trouvee !== undefined, id);
  return trouvee;
}

/** Pose la plateforme (une fois) et l'état dans le monde, comme le constructeur le fait. */
async function pose(monde: FakeWorld, id: string, etat: FactoryState, options: { origine?: Xz; avecBase?: boolean } = {}): Promise<IslandPlan> {
  const plan = planIsland(ILES, usine(id), options.origine ?? ORIGINE, etat);
  const ops = options.avecBase === false ? plan.state : [...plan.base, ...plan.state];
  await monde.withLoadedArea(plan.box, async () => {
    for (const op of ops) applyOp(monde, op);
  });
  return plan;
}

/** Les mesures d'un bâtiment, relues sans passer par le plan. */
function mesures(id: string, origine: Xz = ORIGINE) {
  const u = usine(id);
  const centre = islandCenter(origine, u, ILES);
  const fp = footprint(centre, u);
  const sol = ILES.hauteur + 1;
  const sommet = ILES.hauteur + u.hauteur;
  const porte = doorSide(frameOfIsland(origine, u, ILES));
  const porteX = porte.axis === "x" ? (porte.sign > 0 ? fp.x1 : fp.x0) : centre.x;
  const porteZ = porte.axis === "z" ? (porte.sign > 0 ? fp.z1 : fp.z0) : centre.z;
  const murs: Array<{ x: number; y: number; z: number }> = [];
  for (let y = sol; y <= sommet; y += 1) {
    for (let x = fp.x0; x <= fp.x1; x += 1) {
      for (let z = fp.z0; z <= fp.z1; z += 1) {
        if (x === fp.x0 || x === fp.x1 || z === fp.z0 || z === fp.z1) murs.push({ x, y, z });
      }
    }
  }
  const estPorte = (c: { x: number; y: number; z: number }): boolean => c.x === porteX && c.z === porteZ && c.y <= sol + 1;
  return { u, centre, fp, sol, sommet, porte, porteX, porteZ, murs, estPorte, muraille: M[u.murs] };
}

describe("signText", () => {
  it("garde un nom court sur une ligne", () => {
    assert.equal(signText("Carrière"), "Carrière");
    assert.equal(signText("Fonderie"), "Fonderie");
  });

  it("coupe un nom long au dernier espace qui tient sur la ligne", () => {
    assert.equal(signText("Mine profonde"), "Mine\nprofonde");
    assert.equal(signText("Atelier d'outils", 12), "Atelier\nd'outils");
  });

  it("ne coupe pas un nom sans espace", () => {
    assert.equal(signText("Cristallerie-Géante-Extra"), "Cristallerie-Géante-Extra");
  });

  it("tous les noms d'usines tiennent sur deux lignes d'enseigne", () => {
    for (const nom of Object.values(FACTORY_NAMES)) {
      const lignes = signText(nom).split("\n");
      assert.ok(lignes.length <= 2, nom);
      for (const ligne of lignes) assert.ok(ligne.length <= 15, `${nom} : « ${ligne} »`);
    }
  });
});

describe("la plateforme", () => {
  it("est ronde, de herbe sur le dessus puis de couches de plus en plus étroites", async () => {
    const monde = fakeWorld();
    const plan = await pose(monde, "ferme", "ACTIVE");
    ILES.plateforme.couches.forEach((couche, index) => {
      const rayon = ILES.plateforme.rayon - couche.retrait;
      const y = ILES.hauteur - index;
      const attendu = M[couche.materiau];
      for (const { dx, dz } of discCells(rayon)) {
        assert.equal(monde.blockAt(plan.center.x + dx, y, plan.center.z + dz), attendu, `couche ${index} ${dx},${dz}`);
      }
      const posés = [...monde.blocks.keys()].filter((k) => k.split(",")[1] === String(y)).length;
      assert.equal(posés, discCells(rayon).length, `couche ${index} : rien au-delà du disque`);
    });
  });

  it("ne pose rien sous elle", async () => {
    const monde = fakeWorld();
    await pose(monde, "ferme", "ACTIVE");
    const plusBas = ILES.hauteur - ILES.plateforme.couches.length + 1;
    for (const k of monde.blocks.keys()) assert.ok(Number(k.split(",")[1]) >= plusBas, k);
  });

  it("tient dans la case de l'entreprise, pour chaque usine", () => {
    for (const u of ILES.usines) {
      const { box } = planIsland(ILES, u, ORIGINE, "ACTIVE");
      assert.ok(box.from.x >= ORIGINE.x && box.to.x < ORIGINE.x + 600, u.id);
      assert.ok(box.from.z >= ORIGINE.z && box.to.z < ORIGINE.z + 600, u.id);
    }
  });

  it("la base ne change pas avec l'état de l'usine", () => {
    for (const u of ILES.usines) {
      const [a, b, c] = ETATS.map((etat) => planIsland(ILES, u, ORIGINE, etat).base);
      assert.deepEqual(a, b, u.id);
      assert.deepEqual(b, c, u.id);
    }
  });

  it("l'état ne touche rien sous la face du dessus, la base ne touche rien au-dessus", () => {
    const plan = planIsland(ILES, usine("usine-robots"), ORIGINE, "EN_RUINE");
    for (const op of plan.state) {
      const bas = op.kind === "fill" ? Math.min(op.from.y, op.to.y) : op.at.y;
      assert.ok(bas >= ILES.hauteur, JSON.stringify(op));
    }
    for (const op of plan.base) {
      const haut = op.kind === "fill" ? Math.max(op.from.y, op.to.y) : op.at.y;
      assert.ok(haut <= ILES.hauteur, JSON.stringify(op));
    }
  });
});

describe("un bâtiment actif", () => {
  it("a ses quatre murs pleins, sa porte, un toit de dalles et une lanterne", async () => {
    for (const u of GENERIQUES) {
      const monde = fakeWorld();
      await pose(monde, u.id, "ACTIVE");
      const m = mesures(u.id);
      for (const cellule of m.murs) {
        const attendu = m.estPorte(cellule) ? undefined : m.muraille;
        assert.equal(monde.blockAt(cellule.x, cellule.y, cellule.z), attendu, `${u.id} ${cellule.x},${cellule.y},${cellule.z}`);
      }
      assert.equal(monde.count(M.toit), u.largeur * u.profondeur, `${u.id} : le toit couvre toute l'emprise`);
      assert.equal(monde.blockAt(m.centre.x, m.sommet + 1, m.centre.z), M.toit);
      assert.equal(monde.count(M.lumiere), 1);
      assert.equal(monde.blockAt(m.centre.x, m.sommet + 2, m.centre.z), M.lumiere);
      assert.equal(monde.count(M.echafaudage), 0);
      assert.equal(monde.count(M.mousse), 0, `${u.id} : pas de mousse`);
      assert.equal(monde.count(M.gravats), 0, `${u.id} : pas de gravats`);
    }
  });

  it("repose sur la plateforme : sous chaque pied de mur, de l'herbe", async () => {
    for (const u of GENERIQUES) {
      const monde = fakeWorld();
      await pose(monde, u.id, "ACTIVE");
      const m = mesures(u.id);
      for (const cellule of m.murs.filter((c) => c.y === m.sol)) {
        assert.equal(monde.blockAt(cellule.x, ILES.hauteur, cellule.z), M.herbe, `${u.id} ${cellule.x},${cellule.z}`);
      }
    }
  });
});

describe("une ruine", () => {
  it("n'a ni toit, ni lanterne, ni échafaudage", async () => {
    const monde = fakeWorld();
    await pose(monde, "scierie", "EN_RUINE");
    assert.equal(monde.count(M.toit), 0);
    assert.equal(monde.count(M.lumiere), 0);
    assert.equal(monde.count(M.echafaudage), 0);
  });

  it("a des murs abîmés, mais pas tous : entre 10 % et 60 % des blocs", async () => {
    for (const id of ["usine-robots", "elevage", "mine-profonde"]) {
      const monde = fakeWorld();
      await pose(monde, id, "EN_RUINE");
      const m = mesures(id);
      const murs = m.murs.filter((c) => !m.estPorte(c));
      const abimes = murs.filter((c) => monde.blockAt(c.x, c.y, c.z) !== m.muraille).length;
      assert.ok(abimes > murs.length * 0.1 && abimes < murs.length * 0.6, `${id} : ${abimes} sur ${murs.length}`);
    }
  });

  it("garde sa porte ouverte, les pieds de ses coins et le bloc derrière l'enseigne", async () => {
    for (const u of GENERIQUES) {
      const monde = fakeWorld();
      await pose(monde, u.id, "EN_RUINE");
      const m = mesures(u.id);
      assert.equal(monde.blockAt(m.porteX, m.sol, m.porteZ), undefined, `${u.id} : porte`);
      assert.equal(monde.blockAt(m.porteX, m.sol + 1, m.porteZ), undefined);
      for (const [x, z] of [[m.fp.x0, m.fp.z0], [m.fp.x0, m.fp.z1], [m.fp.x1, m.fp.z0], [m.fp.x1, m.fp.z1]] as const) {
        assert.equal(monde.blockAt(x, m.sol, z), m.muraille, `${u.id} : pied de coin ${x},${z}`);
      }
    }
  });

  it("n'a plus que des gravats ou du vide dans ses murs abîmés, jamais autre chose", async () => {
    const monde = fakeWorld();
    await pose(monde, "fonderie", "EN_RUINE");
    const m = mesures("fonderie");
    const permis = new Set<string | undefined>([m.muraille, undefined, M.gravats, M.mousse]);
    for (const c of m.murs) assert.ok(permis.has(monde.blockAt(c.x, c.y, c.z)), `${c.x},${c.y},${c.z}`);
  });

  it("a le sol arraché par plaques de mousse, dans l'herbe", async () => {
    const monde = fakeWorld();
    const plan = await pose(monde, "ferme", "EN_RUINE");
    const plaques = monde.count(M.mousse);
    const total = discCells(ILES.plateforme.rayon).length;
    assert.ok(plaques > total * 0.1 && plaques < total * 0.7, `${plaques} plaques sur ${total}`);
    for (const k of monde.blocks.keys()) {
      const [x, y, z] = k.split(",").map(Number);
      if (monde.blockAt(x ?? 0, y ?? 0, z ?? 0) === M.mousse && y === ILES.hauteur) {
        assert.ok(Math.abs((x ?? 0) - plan.center.x) <= ILES.plateforme.rayon);
      }
    }
  });

  it("est abîmée de la même façon à chaque pose, mais pas de la même façon d'une usine à l'autre", async () => {
    const a = fakeWorld();
    const b = fakeWorld();
    await pose(a, "scierie", "EN_RUINE");
    await pose(b, "scierie", "EN_RUINE");
    assert.deepEqual(a.snapshot(), b.snapshot());
    assert.deepEqual(planIsland(ILES, usine("scierie"), ORIGINE, "EN_RUINE"), planIsland(ILES, usine("scierie"), ORIGINE, "EN_RUINE"));
    const autre = fakeWorld();
    await pose(autre, "menuiserie", "EN_RUINE");
    const motif = (monde: FakeWorld, id: string): string[] => {
      const m = mesures(id);
      return m.murs.map((c) => (monde.blockAt(c.x, c.y, c.z) === m.muraille ? "." : "x"));
    };
    assert.notDeepEqual(motif(a, "scierie"), motif(autre, "menuiserie"));
  });
});

describe("un chantier", () => {
  it("a un poteau à chacun de ses quatre coins, et ni toit ni lanterne", async () => {
    const monde = fakeWorld();
    await pose(monde, "briqueterie", "REMISE_EN_ETAT");
    const m = mesures("briqueterie");
    assert.equal(monde.count(M.echafaudage), 4 * (m.u.hauteur + 1));
    for (const x of [m.fp.x0 - 1, m.fp.x1 + 1]) {
      for (const z of [m.fp.z0 - 1, m.fp.z1 + 1]) {
        assert.equal(monde.blockAt(x, m.sol, z), M.echafaudage);
        assert.equal(monde.blockAt(x, m.sommet + 1, z), M.echafaudage);
      }
    }
    assert.equal(monde.count(M.toit), 0);
    assert.equal(monde.count(M.lumiere), 0);
  });

  it("garde des murs entiers : la remise en état a commencé", async () => {
    const monde = fakeWorld();
    await pose(monde, "centrale", "REMISE_EN_ETAT");
    const m = mesures("centrale");
    for (const c of m.murs.filter((cc) => !m.estPorte(cc))) assert.equal(monde.blockAt(c.x, c.y, c.z), m.muraille);
  });
});

describe("la porte", () => {
  it("est sur la face tournée vers l'île principale : à l'ouest pour la Carrière, au nord pour la Fonderie", async () => {
    const carriere = fakeWorld();
    await pose(carriere, "carriere", "ACTIVE");
    const a = mesures("carriere");
    assert.equal(a.porteX, a.fp.x0);
    const frame = frameOfIsland(ORIGINE, usine("carriere"), ILES);
    // La Carrière n'a pas de porte : son entrée regarde l'île principale, un chemin dégagé jusqu'à l'étal.
    const passage = workshopBlock(frame, ILES.hauteur, { avant: 10, droite: 0, haut: 1 });
    const chemin = workshopBlock(frame, ILES.hauteur, { avant: 10, droite: 0, haut: 0 });
    assert.equal(carriere.blockAt(passage.x, passage.y, passage.z), undefined);
    assert.equal(carriere.blockAt(chemin.x, chemin.y, chemin.z), "minecraft:grass_path");

    const fonderie = fakeWorld();
    await pose(fonderie, "fonderie", "ACTIVE");
    const b = mesures("fonderie");
    assert.equal(b.porteZ, b.fp.z0);
    assert.equal(fonderie.blockAt(b.centre.x, b.sol, b.fp.z0), undefined);
    assert.equal(fonderie.blockAt(b.centre.x + 1, b.sol, b.fp.z0), b.muraille);
  });

  it("est ouverte sur deux blocs de haut dans tous les états", async () => {
    for (const u of ILES.usines) {
      for (const etat of ETATS) {
        const monde = fakeWorld();
        await pose(monde, u.id, etat);
        const m = mesures(u.id);
        assert.equal(monde.blockAt(m.porteX, m.sol, m.porteZ), undefined, `${u.id} ${etat}`);
        assert.equal(monde.blockAt(m.porteX, m.sol + 1, m.porteZ), undefined, `${u.id} ${etat}`);
      }
    }
  });
});

describe("l'enseigne", () => {
  const FACE = { x: { 1: 5, [-1]: 4 }, z: { 1: 3, [-1]: 2 } } as const;

  it("porte le nom de l'usine, au-dessus de la porte, côté dehors, dans tous les états", async () => {
    for (const u of ILES.usines) {
      for (const etat of ETATS) {
        const monde = fakeWorld();
        await pose(monde, u.id, etat);
        const m = mesures(u.id);
        const special = u.id === ILES.apparition.usine ? workshopBlock(frameOfIsland(ORIGINE, u, ILES), ILES.hauteur, ILES.atelier.enseigne.position) : undefined;
        const x = special?.x ?? m.porteX + (m.porte.axis === "x" ? m.porte.sign : 0);
        const z = special?.z ?? m.porteZ + (m.porte.axis === "z" ? m.porte.sign : 0);
        const y = special?.y ?? m.sol + 2;
        const bloc = monde.blocks.get(`${x},${y},${z}`);
        assert.equal(bloc?.block, M.enseigne, `${u.id} ${etat}`);
        assert.equal(bloc?.states?.facing_direction, FACE[m.porte.axis][m.porte.sign], `${u.id} : sens de l'enseigne`);
        assert.equal(monde.texts.get(`${x},${y},${z}`), signText(factoryName(u.id)), `${u.id} ${etat}`);
      }
    }
  });

  it("est accrochée au mur : le bloc derrière elle est le mur, au-dessus de la porte", async () => {
    const monde = fakeWorld();
    await pose(monde, "elevage", "ACTIVE");
    const m = mesures("elevage");
    assert.equal(monde.blockAt(m.porteX, m.sol + 2, m.porteZ), m.muraille);
  });

  it("est la seule enseigne de l'île", async () => {
    const monde = fakeWorld();
    await pose(monde, "verrerie", "ACTIVE");
    assert.equal(monde.count(M.enseigne), 1);
    assert.equal(monde.texts.size, 1);
  });
});

describe("changer d'état", () => {
  const CHANGEMENTS: Array<[string, FactoryState, FactoryState]> = [
    ["carriere", "EN_RUINE", "REMISE_EN_ETAT"],
    ["carriere", "REMISE_EN_ETAT", "ACTIVE"],
    ["usine-robots", "EN_RUINE", "ACTIVE"],
    ["usine-robots", "ACTIVE", "EN_RUINE"],
    ["elevage", "ACTIVE", "REMISE_EN_ETAT"],
    ["fonderie", "ACTIVE", "EN_RUINE"],
    ["conserverie", "REMISE_EN_ETAT", "EN_RUINE"],
  ];

  it("ne redessine que le bâtiment, et laisse exactement ce qu'une première pose aurait donné", async () => {
    for (const [id, avant, apres] of CHANGEMENTS) {
      const change = fakeWorld();
      await pose(change, id, avant);
      await pose(change, id, apres, { avecBase: false });
      const neuf = fakeWorld();
      await pose(neuf, id, apres);
      assert.deepEqual(change.snapshot(), neuf.snapshot(), `${id} : ${avant} puis ${apres}`);
      assert.deepEqual([...change.texts.entries()], [...neuf.texts.entries()], `${id} : enseigne`);
    }
  });

  it("un autre emplacement d'archipel donne la même île, décalée", async () => {
    const decalage = { x: 600, z: 1200 };
    const a = fakeWorld();
    const b = fakeWorld();
    await pose(a, "mine-profonde", "EN_RUINE");
    await pose(b, "mine-profonde", "EN_RUINE", { origine: { x: ORIGINE.x + decalage.x, z: ORIGINE.z + decalage.z } });
    const decale = a.snapshot().map(([k, v]) => {
      const [x, y, z] = k.split(",").map(Number);
      return [`${(x ?? 0) + decalage.x},${y},${(z ?? 0) + decalage.z}`, v] as [string, string];
    });
    assert.deepEqual(decale.sort(([p], [q]) => p.localeCompare(q)), b.snapshot());
  });
});
