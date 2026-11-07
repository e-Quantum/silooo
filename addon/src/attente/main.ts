// Le point d'entrée du pack de la salle d'attente (attente/scripts/main.js après esbuild). Un monde vide, avant
// l'ouverture : chaque joueur apparaît sur un bloc, le suivant est à 1 à 4 blocs, et ainsi de suite ; une chute et il
// repart de zéro. Le meilleur score de chacun est dans le classement de la barre latérale. Une boussole ouvre l'écran
// « Devenir Pionnier » (le code de liaison Discord de silooo-core). Les testeurs de la liste blanche partent au monde de test.
//
// Rien ici ne touche au jeu Silooo : ni entreprise, ni monnaie. Le score du saut ne vaut rien ailleurs que dans ce classement.

import {
  DisplaySlotId,
  GameMode,
  ItemLockMode,
  ItemStack,
  ObjectiveSortOrder,
  system,
  world,
  type Dimension,
  type Player,
  type ScoreboardObjective,
} from "@minecraft/server";
import { createCoreApi, type CoreApi } from "../api";
import { createBetaTransport, estTesteur, lireTransfert, readSettings, transferer } from "../beta";
import { config } from "../config";
import { createCoreClient } from "../http";
import { playerXuid } from "../identity";
import { consoleLogger, createRequestIdGenerator, describeError } from "../runtime";
import { createFormPresenter } from "../ui/formulaires";
import { couloirLibre, depart, estTombe, prochainBloc, surLeBloc, type Bloc, type Pas } from "./parcours";
import { ecranPionnier } from "./pionnier";
import { ATTENTE_TEXTES } from "./textes";

const OBJECTIF = "silooo_sauts";
const BOUSSOLE = "minecraft:compass";
const BLOC_DEPART = "minecraft:quartz_block";
/** Les blocs du parcours, tour à tour : les couleurs claires de la charte (ciel, crème, émail). */
const BLOCS = ["minecraft:light_blue_concrete", "minecraft:white_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete"];
/** Le bloc d'apparition du monde, sous le point d'apparition de level.dat (0, 101, 0). */
const APPARITION: Bloc = { x: 0, y: 100, z: 0 };

interface Course {
  readonly couloir: number;
  /** Le bloc où se tient le joueur (le dernier atteint) et le suivant à atteindre. */
  actuel: Pas;
  suivant: Pas;
  score: number;
  /** Le bloc de départ n'est pas encore posé (son tronçon de monde n'était pas chargé). */
  enAttente: boolean;
}

const log = consoleLogger("[silooo-attente]");
const courses = new Map<string, Course>();
const nouvelleIntention = createRequestIdGenerator();
const ecrans = createFormPresenter();
let api: Pick<CoreApi, "issueLobbyLinkCode"> | undefined;

function poser(dimension: Dimension, bloc: Bloc, type: string): boolean {
  try {
    dimension.setBlockType(bloc, type);
    return true;
  } catch {
    return false; // tronçon pas encore chargé : on réessaie au tour suivant
  }
}

function retirer(dimension: Dimension, bloc: Bloc): void {
  poser(dimension, bloc, "minecraft:air");
}

let classement: ScoreboardObjective | undefined;

/** L'objectif du classement, créé une fois et affiché dans la barre latérale, du meilleur au moins bon. */
function objectif(): ScoreboardObjective {
  if (classement?.isValid) return classement;
  const obj = world.scoreboard.getObjective(OBJECTIF) ?? world.scoreboard.addObjective(OBJECTIF, ATTENTE_TEXTES.classement);
  world.scoreboard.setObjectiveAtDisplaySlot(DisplaySlotId.Sidebar, { objective: obj, sortOrder: ObjectiveSortOrder.Descending });
  classement = obj;
  return obj;
}

/** Le record d'un joueur. Il est rangé sous son gamertag (un faux joueur du tableau) : le classement garde son nom hors ligne. */
function record(player: Player): number {
  try {
    return objectif().getScore(player.name) ?? 0;
  } catch {
    return 0;
  }
}

function blocDe(score: number): string {
  return BLOCS[score % BLOCS.length] ?? BLOCS[0]!;
}

function auDessus(bloc: Bloc): { x: number; y: number; z: number } {
  return { x: bloc.x + 0.5, y: bloc.y + 1, z: bloc.z + 0.5 };
}

/** Un nouveau départ : couloir du joueur, bloc de départ, premier bloc à atteindre. */
function demarrer(player: Player, couloir: number): void {
  const actuel = depart(couloir);
  const course: Course = { couloir, actuel, suivant: prochainBloc(actuel, Math.random), score: 0, enAttente: true };
  courses.set(player.id, course);
  player.teleport(auDessus(actuel.bloc), { dimension: world.getDimension("overworld"), rotation: { x: 0, y: 0 } });
}

function nettoyer(dimension: Dimension, course: Course): void {
  retirer(dimension, course.actuel.bloc);
  retirer(dimension, course.suivant.bloc);
}

function rejoindre(player: Player): void {
  try {
    player.setGameMode(GameMode.Adventure);
  } catch (error) {
    log.warn(`mode de jeu non réglé pour un joueur : ${describeError(error)}`);
  }
  donnerBoussole(player);
  player.onScreenDisplay.setTitle(ATTENTE_TEXTES.titre, { subtitle: ATTENTE_TEXTES.sousTitre, fadeInDuration: 10, stayDuration: 60, fadeOutDuration: 20 });
  player.sendMessage(ATTENTE_TEXTES.bienvenue(player.name));
  player.sendMessage(ATTENTE_TEXTES.invitation);
  const pris = [...courses.values()].map((c) => c.couloir);
  demarrer(player, couloirLibre(pris));

  const cible = lireTransfert();
  if (cible !== undefined && estTesteur(player)) {
    player.sendMessage(ATTENTE_TEXTES.transfert);
    system.runTimeout(() => {
      try {
        if (player.isValid) transferer(player, cible);
      } catch (error) {
        log.warn(`transfert vers le monde de test impossible : ${describeError(error)}`);
      }
    }, 60);
  }
}

function donnerBoussole(player: Player): void {
  const inventaire = player.getComponent("minecraft:inventory")?.container;
  if (inventaire === undefined) return;
  for (let i = 0; i < inventaire.size; i += 1) {
    if (inventaire.getItem(i)?.typeId === BOUSSOLE) return;
  }
  const boussole = new ItemStack(BOUSSOLE, 1);
  boussole.nameTag = ATTENTE_TEXTES.boussole;
  boussole.keepOnDeath = true;
  boussole.lockMode = ItemLockMode.slot;
  inventaire.setItem(4, boussole);
}

/** Un tour du parcours pour un joueur : poser le départ s'il attend, compter un saut réussi, ou constater la chute. */
function tour(player: Player, course: Course): void {
  const dimension = player.dimension;
  if (course.enAttente) {
    if (poser(dimension, course.actuel.bloc, BLOC_DEPART) && poser(dimension, course.suivant.bloc, blocDe(1))) {
      course.enAttente = false;
    } else {
      player.teleport(auDessus(course.actuel.bloc)); // il plane au-dessus du départ tant que le tronçon charge
      return;
    }
  }
  const pieds = player.location;
  if (surLeBloc(pieds, course.suivant.bloc, player.isOnGround)) {
    retirer(dimension, course.actuel.bloc);
    course.actuel = course.suivant;
    course.score += 1;
    course.suivant = prochainBloc(course.actuel, Math.random);
    poser(dimension, course.suivant.bloc, blocDe(course.score + 1));
    player.playSound("random.orb", { pitch: Math.min(2, 0.8 + course.score * 0.02), volume: 0.6 });
  } else if (estTombe(pieds, course.actuel.bloc)) {
    const ancien = record(player);
    if (course.score > ancien) {
      try {
        objectif().setScore(player.name, course.score);
      } catch (error) {
        log.warn(`record non enregistré : ${describeError(error)}`);
      }
      if (ancien > 0) player.sendMessage(ATTENTE_TEXTES.nouveauRecord(course.score));
    }
    if (course.score > 0) player.onScreenDisplay.setTitle(" ", { subtitle: ATTENTE_TEXTES.chute(course.score), fadeInDuration: 0, stayDuration: 30, fadeOutDuration: 10 });
    nettoyer(dimension, course);
    demarrer(player, course.couloir);
    return;
  }
  player.onScreenDisplay.setActionBar(ATTENTE_TEXTES.barre(course.score, Math.max(record(player), course.score)));
}

function brancherService(): void {
  const reglages = readSettings();
  if (!reglages.ok) {
    log.warn(`codes Pionnier indisponibles : ${reglages.problems.join(" ; ")}`);
    return;
  }
  const { coreUrl, apiKey } = reglages.settings;
  api = createCoreApi(
    createCoreClient({ transport: createBetaTransport(), coreUrl, apiKey, timeoutSeconds: config.requestTimeoutSeconds, readRetries: config.readRetries }),
  );
}

function main(): void {
  brancherService();

  world.afterEvents.worldLoad.subscribe(() => {
    poser(world.getDimension("overworld"), APPARITION, BLOC_DEPART);
    objectif();
    log.info("salle d'attente prête");
  });

  world.afterEvents.playerSpawn.subscribe((event) => {
    if (!event.initialSpawn) return;
    try {
      rejoindre(event.player);
    } catch (error) {
      log.error(`arrivée d'un joueur : ${describeError(error)}`);
    }
  });

  world.beforeEvents.playerLeave.subscribe((event) => {
    const course = courses.get(event.player.id);
    if (course === undefined) return;
    courses.delete(event.player.id);
    const dimension = event.player.dimension;
    system.run(() => nettoyer(dimension, course));
  });

  world.afterEvents.itemUse.subscribe((event) => {
    if (event.itemStack.typeId !== BOUSSOLE) return;
    const player = event.source;
    void ecranPionnier({
      actor: playerXuid(player),
      gamertag: player.name,
      api,
      newRequestId: nouvelleIntention,
      show: (spec) => ecrans.show(player, spec),
    }).catch((error: unknown) => log.error(`écran Pionnier : ${describeError(error)}`));
  });

  system.runInterval(() => {
    for (const player of world.getAllPlayers()) {
      const course = courses.get(player.id);
      if (course === undefined) continue;
      try {
        tour(player, course);
      } catch (error) {
        log.error(`parcours : ${describeError(error)}`);
      }
    }
  }, 2);
}

try {
  main();
} catch (error) {
  log.error(`salle d'attente inactive : ${describeError(error)}`);
}
