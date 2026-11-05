// Le monde réel : tout ce que la partie « monde » de l'add-on touche de @minecraft/server passe par ce fichier, et
// seul main.ts l'importe. Chaque enveloppe est mince : elle traduit un port du code testable (BlockWriter, MarkStore,
// OooscarWorld, CinematicStage, ArrivalWorld) en appels de la Script API, sans rien décider. Ce fichier ne se teste
// pas hors d'un vrai serveur Bedrock ; la liste de ce qu'il faut y vérifier est dans addon/README.md.

import {
  BlockPermutation,
  BlockVolume,
  EasingType,
  HudElement,
  HudVisibility,
  InputPermissionCategory,
  world,
  type Dimension,
  type Entity,
  type Player,
  type Vector3,
} from "@minecraft/server";
import { config } from "../config";
import { secondsToTicks } from "../runtime";
import type { ArrivalWorld } from "./arrivee";
import type { CinematicStage } from "./cinematique";
import type { BlockWriter, MarkStore } from "./constructeur";
import type { OooscarHandle, OooscarWorld } from "./oooscar";

/** La dimension où vit l'archipel : le monde de Silooo n'en a qu'une. */
function overworld(): Dimension {
  return world.getDimension("overworld");
}

// ---------------------------------------------------------------- blocs

export function createBlockWriter(): BlockWriter {
  let counter = 0;
  return {
    async withLoadedArea(box, run) {
      const manager = world.tickingAreaManager;
      const options = { dimension: overworld(), from: box.from, to: box.to };
      if (!manager.hasCapacity(options)) throw new Error("plus de place pour une zone chargée de plus");
      counter += 1;
      const id = `${config.world.areaPrefix}${counter}`;
      await manager.createTickingArea(id, options);
      try {
        return await run();
      } finally {
        try {
          manager.removeTickingArea(id);
        } catch {
          // La zone n'existe plus : rien à relâcher.
        }
      }
    },

    fill(from, to, block) {
      overworld().fillBlocks(new BlockVolume(from, to), block);
    },

    set(at, block, states) {
      const target = overworld().getBlock(at);
      if (target === undefined) throw new Error(`bloc en ${at.x} ${at.y} ${at.z} : zone non chargée`);
      target.setPermutation(BlockPermutation.resolve(block, states === undefined ? undefined : { ...states }));
    },

    setText(at, text) {
      const sign = overworld().getBlock(at)?.getComponent("minecraft:sign");
      if (sign === undefined) throw new Error(`enseigne en ${at.x} ${at.y} ${at.z} : introuvable`);
      sign.setText(text);
    },
  };
}

/** Retire les zones chargées laissées par un script rechargé en cours de pose : toutes portent le préfixe de config.world.areaPrefix. */
export function releaseStaleAreas(): number {
  const manager = world.tickingAreaManager;
  let released = 0;
  for (const area of manager.getAllTickingAreas()) {
    if (area.identifier.startsWith(config.world.areaPrefix)) {
      manager.removeTickingArea(area.identifier);
      released += 1;
    }
  }
  return released;
}

// ---------------------------------------------------------------- marques des îles posées

const MARK_PREFIX = "silooo:iles:";

export function createMarkStore(): MarkStore {
  return {
    read(companyId) {
      const raw = world.getDynamicProperty(`${MARK_PREFIX}${companyId}`);
      if (typeof raw !== "string") return {};
      try {
        const parsed: unknown = JSON.parse(raw);
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
        const marks: Record<string, string> = {};
        for (const [factoryId, mark] of Object.entries(parsed)) if (typeof mark === "string") marks[factoryId] = mark;
        return marks;
      } catch {
        return {};
      }
    },
    write(companyId, marks) {
      world.setDynamicProperty(`${MARK_PREFIX}${companyId}`, JSON.stringify(marks));
    },
  };
}

// ---------------------------------------------------------------- Oooscar

function handleOf(entity: Entity): OooscarHandle {
  return { id: entity.id };
}

export function createOooscarWorld(): OooscarWorld {
  const type = config.world.oooscarType;
  return {
    spawn(at, facing) {
      const entity = overworld().spawnEntity(type, at);
      entity.teleport(at, { facingLocation: facing });
      return handleOf(entity);
    },
    remove(handle) {
      const entity = world.getEntity(handle.id);
      if (entity?.isValid === true) entity.remove();
    },
    exists: (handle) => world.getEntity(handle.id)?.isValid === true,
    all: () => overworld().getEntities({ type }).map(handleOf),
    near: (center, radius) => overworld().getEntities({ type, location: center, maxDistance: radius }).map(handleOf),
  };
}

// ---------------------------------------------------------------- arrivée

export function createArrivalWorld(): ArrivalWorld<Player> {
  return {
    isOnline: (player) => player.isValid,
    teleport(player, at, facing) {
      player.teleport(at, { dimension: overworld(), facingLocation: facing, keepVelocity: false });
    },
  };
}

// ---------------------------------------------------------------- cinématique

/** Tout le HUD de Bedrock sauf les commandes tactiles : sur mobile, on s'accroupit avec un bouton, il doit rester. */
const HIDDEN_HUD: HudElement[] = [
  HudElement.PaperDoll,
  HudElement.Armor,
  HudElement.ToolTips,
  HudElement.Crosshair,
  HudElement.Hotbar,
  HudElement.Health,
  HudElement.ProgressBar,
  HudElement.Hunger,
  HudElement.AirBubbles,
  HudElement.HorseHealth,
  HudElement.StatusEffects,
  HudElement.ItemText,
];

/** Les déplacements qu'on fige pendant la cinématique. S'accroupir reste libre : c'est le geste pour passer. */
const LOCKED_INPUT: InputPermissionCategory[] = [
  InputPermissionCategory.Movement,
  InputPermissionCategory.LateralMovement,
  InputPermissionCategory.Jump,
];

const FREE_CAMERA = "minecraft:free";

function vector(point: Vector3): Vector3 {
  return { x: point.x, y: point.y, z: point.z };
}

export function createCinematicStage(): CinematicStage<Player> {
  return {
    isValid: (player) => player.isValid,
    isSneaking: (player) => player.isSneaking,
    hideHud: (player) => player.onScreenDisplay.setHudVisibility(HudVisibility.Hide, HIDDEN_HUD),
    showHud: (player) => player.onScreenDisplay.resetHudElementsVisibility(),
    lockInput(player) {
      for (const category of LOCKED_INPUT) player.inputPermissions.setPermissionCategory(category, false);
    },
    unlockInput(player) {
      for (const category of LOCKED_INPUT) player.inputPermissions.setPermissionCategory(category, true);
    },
    setCamera: (player, camera, facing) => player.camera.setCamera(FREE_CAMERA, { location: vector(camera), facingLocation: vector(facing) }),
    moveCamera: (player, camera, facing, seconds) =>
      player.camera.setCamera(FREE_CAMERA, {
        location: vector(camera),
        facingLocation: vector(facing),
        easeOptions: { easeTime: seconds, easeType: EasingType.InOutSine },
      }),
    clearCamera: (player) => player.camera.clear(),
    fade: (player, inSeconds, holdSeconds, outSeconds) =>
      player.camera.fade({
        fadeColor: { red: 0, green: 0, blue: 0 },
        fadeTime: { fadeInTime: inSeconds, holdTime: holdSeconds, fadeOutTime: outSeconds },
      }),
    // Le sous-titre passe par le canal de titre : le nom de qui parle en titre, la phrase en sous-titre.
    subtitle: (player, speaker, text, seconds) =>
      player.onScreenDisplay.setTitle(speaker, {
        subtitle: text,
        fadeInDuration: secondsToTicks(0.25),
        stayDuration: secondsToTicks(Math.max(0.5, seconds - 0.75)),
        fadeOutDuration: secondsToTicks(0.5),
      }),
    clearSubtitle: (player) => void player.runCommand("title @s clear"),
    hint: (player, text) => player.onScreenDisplay.setActionBar(text),
    clearHint: (player) => player.onScreenDisplay.setActionBar(""),
  };
}
