// Le monde réel : tout ce que la partie « monde » de l'add-on touche de @minecraft/server passe par ce fichier, et
// seul main.ts l'importe. Chaque enveloppe est mince : elle traduit un port du code testable (BlockWriter, MarkStore,
// OooscarWorld, CinematicStage, ArrivalWorld, CursorStore) en appels de la Script API, sans rien décider. Ce fichier ne
// se teste pas hors d'un vrai serveur Bedrock ; la liste de ce qu'il faut y vérifier est dans addon/README.md.

import {
  BlockPermutation,
  BlockTypes,
  BlockVolume,
  Difficulty,
  Direction,
  EasingType,
  GameMode,
  HudElement,
  HudVisibility,
  InputPermissionCategory,
  TimeOfDay,
  world,
  type Dimension,
  type Entity,
  type Player,
  type Vector3,
} from "@minecraft/server";
import { config } from "../config";
import { ILES } from "./definitions";
import { currentWorkshopMarks, transportReach, type WorkshopWorld } from "./atelier";
import { carriereProtection } from "./protection";
import type { Xz } from "./geometrie";
import { ATELIER_TEXTES } from "../ui/textes";
import type { CursorStore } from "../events";
import { secondsToTicks } from "../runtime";
import type { ArrivalWorld } from "./arrivee";
import { CINEMATIC_TITLE_PREFIX, CINEMATIC_SUBTITLE_PREFIX, cinematicBounds, type CinematicStage, type Timeline } from "./cinematique";
import type { BlockWriter, MarkStore } from "./constructeur";
import type { OooscarHandle, OooscarWorld } from "./oooscar";

/** La dimension où vit l'archipel : le monde de Silooo n'en a qu'une. */
function overworld(): Dimension {
  return world.getDimension("overworld");
}

/**
 * Les règles du monde de Silooo, posées à chaque démarrage : un tycoon dans le ciel, sans combat ni nuit. Paisible
 * retire les monstres déjà là, l'apparition coupée garde les îles vides, et l'heure reste figée en plein jour (pas de
 * nuit, donc pas de fantômes ni de lit à chercher). La météo ne tourne pas non plus : la pluie assombrit les îles.
 */
export function applyWorldRules(): void {
  world.setDifficulty(Difficulty.Peaceful);
  world.gameRules.doMobSpawning = false;
  world.gameRules.doDayLightCycle = false;
  world.gameRules.doWeatherCycle = false;
  world.gameRules.doInsomnia = false;
  world.setTimeOfDay(TimeOfDay.Noon);
}

/** Échec explicite au démarrage si un matériau ne porte pas son identifiant Bedrock. */
export function validateWorldAssets(): void {
  for (const block of Object.values(ILES.materiaux)) BlockPermutation.resolve(block);
  for (const facing of [2, 3, 4, 5]) BlockPermutation.resolve(ILES.materiaux.enseigne, { facing_direction: facing });
  for (const volume of ILES.atelier.volumes) BlockPermutation.resolve(volume.bloc);
  for (const machine of ILES.atelier.machines) BlockPermutation.resolve(machine.bloc);
  const structures = world.structureManager.getPackStructureIds();
  for (const id of [ILES.atelier.structure, ILES.atelier.structureRuine]) if (!structures.includes(id)) throw new Error(`structure ${id} absente du pack`);
}

/**
 * La Carrière est protégée (protection.ts) : seul le front de taille se casse, rien ne se pose sur l'île. Le mode créatif
 * passe, pour les constructeurs. Un clic sur une machine de l'atelier ouvre toujours son menu. L'API stable n'a pas
 * d'événement « avant pose » : la pose est refusée au clic, quand le joueur tient un bloc et vise une case de l'île.
 */
export function protectCarriere(origins: () => readonly Xz[]): void {
  const enCreatif = (player: Player) => player.getGameMode() === GameMode.Creative;
  world.beforeEvents.playerBreakBlock.subscribe((event) => {
    if (!enCreatif(event.player) && carriereProtection(ILES, event.block.location, origins()) === "protege") event.cancel = true;
  });
  const decalage: Record<string, Vector3> = {
    [Direction.Up]: { x: 0, y: 1, z: 0 }, [Direction.Down]: { x: 0, y: -1, z: 0 }, [Direction.North]: { x: 0, y: 0, z: -1 },
    [Direction.South]: { x: 0, y: 0, z: 1 }, [Direction.East]: { x: 1, y: 0, z: 0 }, [Direction.West]: { x: -1, y: 0, z: 0 },
  };
  world.beforeEvents.playerInteractWithBlock.subscribe((event) => {
    const item = event.itemStack;
    if (item === undefined || BlockTypes.get(item.typeId) === undefined || enCreatif(event.player)) return;
    if (ILES.atelier.machines.some((machine) => machine.bloc === event.block.typeId)) return;
    const d = decalage[event.blockFace] ?? { x: 0, y: 0, z: 0 };
    const cible = { x: event.block.location.x + d.x, y: event.block.location.y + d.y, z: event.block.location.z + d.z };
    if (carriereProtection(ILES, cible, origins()) !== "hors") event.cancel = true;
  });
}

export function createWorkshopWorld(): WorkshopWorld {
  const type = "silooo:ouvrier";
  return {
    machine(at, expected, working) {
      if (!overworld().isChunkLoaded(at)) return false;
      const block = overworld().getBlock(at);
      if (block?.typeId !== expected) return false;
      if (expected === "silooo:broyeur" || expected === "silooo:convoyeur") {
        const states = block.permutation.getAllStates();
        if (states["silooo:actif"] !== working) block.setPermutation(BlockPermutation.resolve(expected, { ...states, "silooo:actif": working }));
      }
      return true;
    },
    worker(previous, companyId, at, facing, working) {
      let entity = previous === undefined ? undefined : world.getEntity(previous);
      if (entity?.isValid !== true) {
        for (const stale of overworld().getEntities({ type, location: at, maxDistance: 4 })) stale.remove();
        entity = overworld().spawnEntity(type, at);
        entity.nameTag = ATELIER_TEXTES.ouvrier;
        entity.setDynamicProperty("silooo:company-id", companyId);
        entity.teleport(at, { facingLocation: facing });
      }
      entity.setProperty("silooo:travaille", working);
      return entity.id;
    },
    fixture(previous, companyId, type, at, yaw, properties) {
      let entity = previous === undefined ? undefined : world.getEntity(previous);
      if (entity?.isValid !== true) {
        for (const stale of overworld().getEntities({ type, location: at, maxDistance: 2 })) stale.remove();
        entity = overworld().spawnEntity(type, at);
        entity.setDynamicProperty("silooo:company-id", companyId);
      }
      entity.teleport(at, { rotation: { x: 0, y: yaw } });
      for (const [name, value] of Object.entries(properties)) if (entity.getProperty(name) !== value) entity.setProperty(name, value);
      return entity.id;
    },
    effect(kind, id, at) {
      if (kind === "son") overworld().playSound(id, at);
      else overworld().spawnParticle(id, at);
    },
    loaded: (at) => overworld().isChunkLoaded(at),
    cargo(previous, companyId, at, working, reset) {
      if (reset === true) {
        for (const stale of overworld().getEntities({ type: "silooo:production", location: at, maxDistance: transportReach(ILES) })) {
          if (stale.getDynamicProperty("silooo:company-id") === companyId) stale.remove();
        }
      }
      let entity = previous === undefined ? undefined : world.getEntity(previous);
      if (entity?.isValid !== true) {
        entity = overworld().spawnEntity("silooo:production", at);
        entity.setDynamicProperty("silooo:company-id", companyId);
      }
      entity.teleport(at);
      if (entity.getProperty("silooo:actif") !== working) entity.setProperty("silooo:actif", working);
      return entity.id;
    },
    remove(id) {
      const entity = world.getEntity(id);
      if (entity?.isValid === true) entity.remove();
    },
  };
}

// ---------------------------------------------------------------- blocs

let areaCounter = 0;
export function createBlockWriter(): BlockWriter {
  return {
    async withLoadedArea(box, run) {
      const manager = world.tickingAreaManager;
      const options = { dimension: overworld(), from: box.from, to: box.to };
      if (!manager.hasCapacity(options)) throw new Error("plus de place pour une zone chargée de plus");
      areaCounter += 1;
      const id = `${config.world.areaPrefix}${areaCounter}`;
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

    structure(id, at) {
      world.structureManager.place(id, overworld(), at, { includeBlocks: true, includeEntities: false, integrity: 1 });
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
/** Les marques de la ville (clés « ville: ») ont leur propre propriété : une propriété dynamique est limitée à 32 767 caractères. */
const CITY_MARK_PREFIX = "silooo:ville:";
const CITY_KEY_PREFIX = "ville:";

function readMarks(property: string): Record<string, string> {
  const raw = world.getDynamicProperty(property);
  if (typeof raw !== "string") return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
    const marks: Record<string, string> = {};
    for (const [key, mark] of Object.entries(parsed)) if (typeof mark === "string") marks[key] = mark;
    return marks;
  } catch {
    return {};
  }
}

export function createMarkStore(): MarkStore {
  return {
    read(companyId) {
      return { ...currentWorkshopMarks(readMarks(`${MARK_PREFIX}${companyId}`), world.getDynamicProperty(`silooo:atelier-version:${companyId}`), ILES), ...readMarks(`${CITY_MARK_PREFIX}${companyId}`) };
    },
    write(companyId, marks) {
      const islands: Record<string, string> = {};
      const city: Record<string, string> = {};
      for (const [key, mark] of Object.entries(marks)) (key.startsWith(CITY_KEY_PREFIX) ? city : islands)[key] = mark;
      world.setDynamicProperty(`${MARK_PREFIX}${companyId}`, JSON.stringify(islands));
      world.setDynamicProperty(`${CITY_MARK_PREFIX}${companyId}`, JSON.stringify(city));
      if (islands[ILES.apparition.usine] !== undefined) world.setDynamicProperty(`silooo:atelier-version:${companyId}`, ILES.atelier.revision);
    },
  };
}

// ---------------------------------------------------------------- curseur des événements

/** Le curseur de la lecture des événements : un nombre, dans une propriété dynamique du monde, pour survivre au redémarrage. */
const EVENTS_CURSOR_PROPERTY = "silooo:events-cursor";

export function createEventsCursorStore(): CursorStore {
  return {
    load() {
      const raw = world.getDynamicProperty(EVENTS_CURSOR_PROPERTY);
      return typeof raw === "number" ? raw : 0;
    },
    save(cursor) {
      world.setDynamicProperty(EVENTS_CURSOR_PROPERTY, cursor);
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
    locate(player) {
      if (!player.isValid || player.dimension.id !== overworld().id) return undefined;
      const { x, y, z } = player.location;
      return { x, y, z };
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

/**
 * Les déplacements qu'on fige pendant la cinématique. S'accroupir reste libre : c'est le geste pour passer. Pas de
 * `Movement` ici : la catégorie englobe aussi l'accroupissement, et la figer rendait la cinématique impossible à passer.
 * On fige donc ses parties une à une, sauf `Sneak`.
 */
const LOCKED_INPUT: InputPermissionCategory[] = [
  InputPermissionCategory.LateralMovement,
  InputPermissionCategory.Jump,
  InputPermissionCategory.Mount,
  InputPermissionCategory.Dismount,
];

const FREE_CAMERA = "minecraft:free";

function vector(point: Vector3): Vector3 {
  return { x: point.x, y: point.y, z: point.z };
}

export function createCinematicStage(): CinematicStage<Player> {
  return {
    isValid: (player) => player.isValid,
    isSneaking: (player) => player.isSneaking,
    hideHud(player) {
      player.onScreenDisplay.setHudVisibility(HudVisibility.Hide, HIDDEN_HUD);
      player.onScreenDisplay.setActionBar("");
      player.onScreenDisplay.setTitle(CINEMATIC_TITLE_PREFIX, { fadeInDuration: 0, stayDuration: secondsToTicks(ILES.cinematique.plans.reduce((sum, plan) => sum + plan.duree, 0)), fadeOutDuration: 0, subtitle: "" });
    },
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
      player.onScreenDisplay.setTitle(CINEMATIC_TITLE_PREFIX, {
        subtitle: `${CINEMATIC_SUBTITLE_PREFIX}${speaker}\n${text}`,
        fadeInDuration: secondsToTicks(0.25),
        stayDuration: secondsToTicks(Math.max(0.5, seconds - 0.75)),
        fadeOutDuration: secondsToTicks(0.5),
      }),
    clearSubtitle: (player) => player.onScreenDisplay.setTitle(" ", { subtitle: "", fadeInDuration: 0, stayDuration: 1, fadeOutDuration: 0 }),
    // La consigne est dans le pack, sous le récit : la barre d’action native le recouvrirait.
    hint: () => {},
    clearHint: (player) => player.onScreenDisplay.setActionBar(""),
  };
}

export function withCinematicArea<T>(timeline: Timeline, run: () => Promise<T>): Promise<T> {
  return createBlockWriter().withLoadedArea(cinematicBounds(timeline), run);
}
