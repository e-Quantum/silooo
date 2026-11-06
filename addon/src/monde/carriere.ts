// La Carrière aménagée : uniquement sa représentation. Aucun prix, gain ni salarié nommé n'est créé ici.
import type { IlesDefinitions } from "../generated/definitions";
import type { FactoryState } from "../protocol";
import { factoryName } from "../ui/textes";
import { doorSide, inFrame, type Frame, type Pos } from "./geometrie";
import type { Op } from "./plan";

export function workshopBlock(frame: Frame, height: number, point: { avant: number; droite: number; haut: number }): Pos {
  const horizontal = inFrame(frame, point.avant, point.droite);
  return { x: Math.round(horizontal.x), y: height + point.haut, z: Math.round(horizontal.z) };
}

export function carriereOps(defs: IlesDefinitions, frame: Frame, state: FactoryState, radius: number): Op[] {
  const { center } = frame;
  const ops: Op[] = [{ kind: "fill", from: { x: center.x - radius, y: defs.hauteur + 1, z: center.z - radius },
    to: { x: center.x + radius, y: defs.hauteur + 9, z: center.z + radius }, block: defs.materiaux.vide }];
  const position = (point: { avant: number; droite: number; haut: number }) => workshopBlock(frame, defs.hauteur, point);
  const addSign = () => {
    const side = doorSide(frame);
    const facing = side.axis === "x" ? (side.sign > 0 ? 5 : 4) : side.sign > 0 ? 3 : 2;
    const sign = position(defs.atelier.enseigne.position);
    ops.push({ kind: "set", at: position(defs.atelier.enseigne.support), block: defs.materiaux.planches });
    ops.push({ kind: "set", at: sign, block: defs.materiaux.enseigne, states: { facing_direction: facing } });
    ops.push({ kind: "text", at: sign, text: factoryName("carriere") });
  };
  // Remettre le sous-sol utilisé par la fosse, y compris après un changement d'état.
  for (const [index, layer] of defs.plateforme.couches.entries()) {
    if (index === 0) continue;
    const r = radius - layer.retrait;
    for (let z = -r; z <= r; z += 1) {
      const half = Math.floor(Math.sqrt(Math.max(0, r * r + r - z * z)));
      ops.push({ kind: "fill", from: { x: center.x - half, y: defs.hauteur - index, z: center.z + z },
        to: { x: center.x + half, y: defs.hauteur - index, z: center.z + z }, block: defs.materiaux[layer.materiau] });
    }
  }
  // Le sol est remis en place après une ruine ; le creux d'extraction est ensuite taillé par les volumes.
  for (let z = -radius; z <= radius; z += 1) {
    const half = Math.floor(Math.sqrt(Math.max(0, radius * radius + radius - z * z)));
    ops.push({ kind: "fill", from: { x: center.x - half, y: defs.hauteur, z: center.z + z },
      to: { x: center.x + half, y: defs.hauteur, z: center.z + z }, block: defs.materiaux.herbe });
  }
  if (state !== "ACTIVE") {
    for (const machine of defs.atelier.machines.filter((entry) => entry.role === "broyeur" || entry.role === "bureau")) {
      ops.push({ kind: "set", at: position(machine.position), block: state === "EN_RUINE" ? defs.materiaux.gravats : defs.materiaux.echafaudage });
    }
    addSign();
    return ops;
  }
  for (const volume of defs.atelier.volumes) {
    const a = position(volume.de), b = position(volume.a);
    ops.push({ kind: "fill", from: { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) },
      to: { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) }, block: volume.bloc });
  }
  for (const machine of defs.atelier.machines) {
    ops.push({ kind: "set", at: position(machine.position), block: machine.bloc,
      ...(machine.role === "broyeur" || machine.role === "convoyeur" ? { states: { "silooo:actif": false } } : {}) });
  }
  addSign();
  return ops;
}
