// Le point d'entrée du pack de la salle d'attente (attente/scripts/main.js après esbuild). Un monde vide, avant
// l'ouverture : chaque joueur apparaît sur un bloc, le suivant est à 1 à 4 blocs, et ainsi de suite ; une chute et il
// repart de zéro. silooo-core garde les records (une ligne par XUID) ; la barre latérale et les hologrammes affichent ce
// qu'il rend. À chaque entrée, et à chaque ouverture de la boussole, l'accueil (sur le modèle de Hubyy) propose de devenir
// Pionnier, de sauter, et aux testeurs de la liste blanche de rejoindre le monde de test.
//
// Rien ici ne touche au jeu Silooo : ni entreprise, ni monnaie. Le score du saut ne vaut rien ailleurs que dans ce classement.

import {
  BlockVolume,
  DisplaySlotId,
  GameMode,
  ItemStack,
  ObjectiveSortOrder,
  system,
  world,
  type Dimension,
  type Entity,
  type Player,
} from "@minecraft/server";
import { createCoreApi, type CoreApi } from "../api";
import { createBetaTransport, estTesteur, lireTransfert, readSettings, transferer } from "../beta";
import { config } from "../config";
import { createCoreClient } from "../http";
import { playerXuid } from "../identity";
import type { LobbyBoard, LobbyPlayer, Xuid } from "../protocol";
import { consoleLogger, createRequestIdGenerator, describeError } from "../runtime";
import { createFormPresenter } from "../ui/formulaires";
import { construireAccueil, montrerAccueil } from "./accueil";
import { ajouter, cleBloc, dansZone, ecrireFile, lireFile, sansLesOccupes, zoneAutour, zonesDeBalayage, type Zone } from "./menage";
import { couloirLibre, depart, estTombe, prochainBloc, surLeBloc, type Bloc, type Pas } from "./parcours";
import { ecranPionnier } from "./pionnier";
import { ATTENTE, nouveauPalier } from "./regles";
import { ATTENTE_TEXTES } from "./textes";
import { creerRappels, HOLOGRAMMES, lignesClassement, positionHologramme, texteHologramme, type Hologramme, type Rappels } from "./vitrine";

const OBJECTIF = "silooo_sauts";
const BOUSSOLE = "minecraft:compass";
const CASE_BOUSSOLE = 4;
const HOLOGRAMME = "silooo:hologramme";
const ETIQUETTE = "silooo_hologramme";
/** Le joueur est déjà venu : propriété dynamique gardée par le monde, l'accueil change de ton. */
const DEJA_VENU = "silooo:deja_venu";
const BLOC_DEPART = "minecraft:quartz_block";
/** Les blocs du parcours, tour à tour : les couleurs claires de la charte (ciel, crème, émail). */
const BLOCS = ["minecraft:light_blue_concrete", "minecraft:white_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete"];
/** Le bloc d'apparition du monde, sous le point d'apparition de level.dat (0, 101, 0). */
const APPARITION: Bloc = { x: 0, y: 100, z: 0 };
const TICKS = 20;

type Api = Pick<CoreApi, "issueLobbyLinkCode" | "recordLobbyRun" | "lobbyBoard" | "lobbyPlayer">;

interface Course {
  readonly couloir: number;
  /** Le bloc où se tient le joueur (le dernier atteint) et le suivant à atteindre. */
  actuel: Pas;
  suivant: Pas;
  score: number;
  /** Le départ de la course (Date.now()), pour sa durée envoyée au service. */
  debut: number;
  /** Le bloc de départ n'est pas encore posé (son tronçon de monde n'était pas chargé). */
  enAttente: boolean;
}

interface Visiteur {
  readonly xuid: Xuid;
  /** Ce que le service sait du joueur ; absent tant qu'il n'a pas répondu. */
  joueur?: LobbyPlayer;
  readonly rappels: Rappels;
  /** Un écran de la salle d'attente est ouvert (accueil ou Pionnier) : la boussole n'en ouvre pas un second. */
  ecranOuvert: boolean;
}

const log = consoleLogger("[silooo-attente]");
const courses = new Map<string, Course>();
const visiteurs = new Map<string, Visiteur>();
const nouvelleIntention = createRequestIdGenerator();
const ecrans = createFormPresenter();
let api: Api | undefined;
let tableau: LobbyBoard | undefined;

// ---------------------------------------------------------------- les blocs

function poser(dimension: Dimension, bloc: Bloc, type: string): boolean {
  try {
    dimension.setBlockType(bloc, type);
    return true;
  } catch {
    return false; // tronçon pas encore chargé : on réessaie au tour suivant
  }
}

function retirer(dimension: Dimension, bloc: Bloc): boolean {
  return poser(dimension, bloc, "minecraft:air");
}

function blocDe(score: number): string {
  return BLOCS[score % BLOCS.length] ?? BLOCS[0]!;
}

function auDessus(bloc: Bloc): { x: number; y: number; z: number } {
  return { x: bloc.x + 0.5, y: bloc.y + 1, z: bloc.z + 0.5 };
}

// ---------------------------------------------------------------- le ménage des blocs laissés

/** Les blocs que le parcours n'a pas pu retirer (tronçon déchargé) : gardés par le monde, retirés dès que possible. */
const A_RETIRER = "silooo:attente_a_retirer";
/** Le balayage des premiers couloirs a eu lieu : les blocs laissés avant la file sont partis. */
const BALAYE = "silooo:attente_balaye";
const ZONE_MENAGE = "silooo_menage";
const COULOIRS_BALAYES = 4;
const MENAGE_SECONDES = 1;
const CHARGEMENT_SECONDES = 10;
const ESSAIS_MAX = 3;

let file: Bloc[] = [];
let balayage: Zone[] = [];
let menagePret = false;
let menageEnCours = false;
const essais = new Map<string, number>();

function garderFile(): void {
  try {
    world.setDynamicProperty(A_RETIRER, file.length === 0 ? undefined : ecrireFile(file));
  } catch (error) {
    log.warn(`file du ménage non gardée : ${describeError(error)}`);
  }
}

/** Retire les blocs ; ceux dont le tronçon n'est pas chargé vont dans la file du ménage. */
function retirerOuPlusTard(dimension: Dimension, blocs: readonly Bloc[]): void {
  const restants = blocs.filter((b) => !retirer(dimension, b));
  if (restants.length === 0) return;
  file = ajouter(file, restants);
  garderFile();
}

/** Les blocs à ne jamais retirer : l'apparition, et tout ce qu'une course en cours utilise. */
function occupes(): Bloc[] {
  const blocs: Bloc[] = [APPARITION];
  for (const c of courses.values()) blocs.push(c.actuel.bloc, c.suivant.bloc, depart(c.couloir).bloc);
  return blocs;
}

function preparerMenage(): void {
  try {
    world.tickingAreaManager.removeAllTickingAreas(); // une zone d'une session précédente
  } catch {
    // aucune zone à retirer
  }
  file = lireFile(world.getDynamicProperty(A_RETIRER));
  balayage = world.getDynamicProperty(BALAYE) === true ? [] : zonesDeBalayage(COULOIRS_BALAYES);
  menagePret = true;
  if (file.length > 0 || balayage.length > 0) log.info(`ménage du parcours : ${file.length} bloc(s) en file, ${balayage.length} zone(s) à balayer`);
}

/** Charge la zone le temps de [action] : une zone de ticking du pack, retirée après. */
async function dansZoneChargee(dimension: Dimension, zone: Zone, action: () => void): Promise<boolean> {
  const zones = world.tickingAreaManager;
  const options = { dimension, from: zone.from, to: zone.to };
  if (zones.hasTickingArea(ZONE_MENAGE)) zones.removeTickingArea(ZONE_MENAGE);
  if (!zones.hasCapacity(options)) {
    log.warn(`ménage du parcours : pas de place pour charger ${cleBloc(zone.from)} → ${cleBloc(zone.to)}`);
    return false;
  }
  try {
    const charge = await Promise.race([
      zones.createTickingArea(ZONE_MENAGE, options).then(() => true),
      attendre(CHARGEMENT_SECONDES).then(() => false),
    ]);
    if (charge) action();
    return charge;
  } finally {
    if (zones.hasTickingArea(ZONE_MENAGE)) zones.removeTickingArea(ZONE_MENAGE);
  }
}

/** Les hologrammes chargés qu'aucun couloir occupé ne tient plus. */
function retirerHologrammesOrphelins(dimension: Dimension): void {
  const tenus = new Set<string>();
  for (const parRole of hologrammes.values()) for (const e of parRole.values()) tenus.add(e.id);
  for (const entite of dimension.getEntities({ type: HOLOGRAMME })) {
    if (!tenus.has(entite.id)) entite.remove();
  }
}

/** Un pas du ménage : vider la file, tronçon par tronçon, puis balayer une zone des premiers couloirs. */
async function menage(): Promise<void> {
  const dimension = world.getDimension("overworld");
  file = sansLesOccupes(file, occupes());
  const premier = file[0];
  if (premier !== undefined) {
    const zone = zoneAutour(premier);
    await dansZoneChargee(dimension, zone, () => {
      const restants = sansLesOccupes(file, occupes()).filter((b) => !(dansZone(b, zone) && retirer(dimension, b)));
      log.info(`ménage du parcours : ${file.length - restants.length} bloc(s) retiré(s)`);
      file = restants;
      retirerHologrammesOrphelins(dimension);
    });
    const cle = cleBloc(premier);
    if (file.some((b) => cleBloc(b) === cle)) {
      // il ne part pas : il passe en fin de file, et il est oublié au bout de quelques essais
      const n = (essais.get(cle) ?? 0) + 1;
      essais.set(cle, n);
      file = file.filter((b) => cleBloc(b) !== cle);
      if (n < ESSAIS_MAX) file.push(premier);
      else log.warn(`ménage du parcours : ${cle} abandonné`);
    } else {
      essais.delete(cle);
    }
    garderFile();
    return;
  }
  const zone = balayage[0];
  if (zone === undefined) return;
  await dansZoneChargee(dimension, zone, () => {
    const garde = new Set(occupes().map(cleBloc));
    const trouves = dimension.getBlocks(new BlockVolume(zone.from, zone.to), { includeTypes: [BLOC_DEPART, ...BLOCS] });
    let n = 0;
    for (const p of trouves.getBlockLocationIterator()) {
      const bloc = { x: p.x, y: p.y, z: p.z };
      if (!garde.has(cleBloc(bloc)) && retirer(dimension, bloc)) n += 1;
    }
    if (n > 0) log.info(`balayage du parcours : ${n} bloc(s) laissé(s) retiré(s) vers ${cleBloc(zone.from)}`);
    retirerHologrammesOrphelins(dimension);
  });
  balayage.shift(); // une zone qui ne charge pas n'est pas retentée : la file reste là pour les nouveaux départs
  if (balayage.length === 0) {
    world.setDynamicProperty(BALAYE, true);
    log.info("balayage du parcours terminé");
  }
}

// ---------------------------------------------------------------- le classement de la barre latérale

/**
 * La barre latérale, refaite à chaque tableau du service : l'objectif est recréé, puis rempli des lignes rendues par
 * silooo-core (une par XUID). Aucun score n'est rangé par joueur dans le monde : un joueur n'y figure jamais deux fois.
 */
function afficherClassement(board: LobbyBoard): void {
  try {
    if (world.scoreboard.getObjective(OBJECTIF) !== undefined) world.scoreboard.removeObjective(OBJECTIF);
    const objectif = world.scoreboard.addObjective(OBJECTIF, ATTENTE_TEXTES.classement);
    for (const ligne of lignesClassement(board)) objectif.setScore(ligne.nom, ligne.score);
    world.scoreboard.setObjectiveAtDisplaySlot(DisplaySlotId.Sidebar, { objective: objectif, sortOrder: ObjectiveSortOrder.Descending });
  } catch (error) {
    log.warn(`classement non affiché : ${describeError(error)}`);
  }
}

// ---------------------------------------------------------------- les hologrammes

/** Les hologrammes de chaque couloir occupé, par rôle. */
const hologrammes = new Map<number, Map<Hologramme["role"], Entity>>();

function poserHologrammes(dimension: Dimension, couloir: number): boolean {
  let parRole = hologrammes.get(couloir);
  if (parRole === undefined) {
    parRole = new Map();
    hologrammes.set(couloir, parRole);
  }
  const debut = depart(couloir).bloc;
  for (const h of HOLOGRAMMES) {
    const present = parRole.get(h.role);
    if (present?.isValid) continue;
    try {
      const entite = dimension.spawnEntity(HOLOGRAMME, positionHologramme(debut, h));
      entite.addTag(ETIQUETTE);
      entite.nameTag = texteHologramme(h.role, tableau);
      parRole.set(h.role, entite);
    } catch {
      return false; // tronçon pas encore chargé
    }
  }
  return true;
}

function retirerHologrammes(couloir: number): void {
  for (const entite of hologrammes.get(couloir)?.values() ?? []) {
    try {
      if (entite.isValid) entite.remove();
    } catch {
      // déjà parti avec son tronçon : le ménage du prochain démarrage le retirera
    }
  }
  hologrammes.delete(couloir);
}

function rafraichirHologrammes(): void {
  for (const parRole of hologrammes.values()) {
    for (const [role, entite] of parRole) {
      if (entite.isValid) entite.nameTag = texteHologramme(role, tableau);
    }
  }
}

/** Au démarrage : les hologrammes d'une session précédente, restés dans le monde, sont retirés. */
function menageHologrammes(): void {
  let n = 0;
  for (const entite of world.getDimension("overworld").getEntities({ type: HOLOGRAMME })) {
    entite.remove();
    n += 1;
  }
  if (n > 0) log.info(`ménage de départ : ${n} hologramme(s) retiré(s)`);
}

// ---------------------------------------------------------------- le service

function recevoirTableau(board: LobbyBoard): void {
  tableau = board;
  afficherClassement(board);
  rafraichirHologrammes();
}

async function chargerTableau(): Promise<void> {
  if (api === undefined) return;
  const reponse = await api.lobbyBoard();
  if (reponse.kind === "ok") recevoirTableau(reponse.body);
}

/** Ce que le service sait du joueur ; à la première preuve Discord vue, le joueur l'apprend en jeu. */
async function chargerJoueur(player: Player, visiteur: Visiteur): Promise<void> {
  if (api === undefined) return;
  const reponse = await api.lobbyPlayer(visiteur.xuid);
  if (reponse.kind !== "ok" || !player.isValid) return;
  const avant = visiteur.joueur;
  visiteur.joueur = reponse.body;
  if (!reponse.body.linked) return;
  player.nameTag = ATTENTE_TEXTES.nomPionnier(player.name);
  if (avant !== undefined && !avant.linked) {
    player.onScreenDisplay.setTitle(ATTENTE_TEXTES.bienvenuePionnier, { subtitle: ATTENTE_TEXTES.compteRelie, fadeInDuration: 10, stayDuration: 60, fadeOutDuration: 20 });
    player.playSound("random.levelup", { volume: 0.8 });
  }
}

/** Une course terminée part au service ; la réponse donne le record, et le nouveau tableau pour tous. */
async function envoyerCourse(player: Player, visiteur: Visiteur, score: number, secondes: number): Promise<void> {
  if (api === undefined || score <= 0) return;
  const reponse = await api.recordLobbyRun({ requestId: nouvelleIntention(), actor: visiteur.xuid }, player.name, score, secondes);
  if (reponse.kind !== "ok") {
    if (reponse.kind !== "network") log.warn(`course refusée par le service (${reponse.kind})`);
    return;
  }
  const r = reponse.body;
  if (visiteur.joueur !== undefined) visiteur.joueur = { ...visiteur.joueur, best: r.best, weekBest: r.weekBest };
  recevoirTableau(r.board);
  if (!player.isValid || !r.newRecord) return;
  player.sendMessage(ATTENTE_TEXTES.nouveauRecord(r.best));
  if (visiteur.rappels.auRecord(Date.now(), visiteur.joueur?.linked === true)) player.sendMessage(ATTENTE_TEXTES.recordDiscord);
}

// ---------------------------------------------------------------- l'accueil et la boussole

function attendre(secondes: number): Promise<void> {
  return new Promise((resolve) => system.runTimeout(resolve, Math.max(1, Math.round(secondes * TICKS))));
}

async function ouvrirAccueil(player: Player, nouveau: boolean): Promise<void> {
  const visiteur = visiteurs.get(player.id);
  if (visiteur === undefined || visiteur.ecranOuvert) return;
  visiteur.ecranOuvert = true;
  try {
    const cible = lireTransfert();
    const accueil = construireAccueil({
      pseudo: player.name,
      nouveau,
      testeur: cible !== undefined && estTesteur(player),
      ...(visiteur.joueur === undefined ? {} : { joueur: visiteur.joueur }),
      ...(tableau === undefined ? {} : { tableau }),
    });
    const choix = await montrerAccueil(accueil, {
      show: (spec) => ecrans.show(player, spec),
      attendre,
      essais: ATTENTE.rythmes.accueilEssais,
    });
    if (!player.isValid) return;
    if (choix === "pionnier") {
      await ecranPionnier({ actor: visiteur.xuid, gamertag: player.name, api, newRequestId: nouvelleIntention, show: (spec) => ecrans.show(player, spec) });
    } else if (choix === "rejoindre" && cible !== undefined) {
      try {
        transferer(player, cible);
      } catch (error) {
        log.warn(`transfert vers le monde de test impossible : ${describeError(error)}`);
        player.sendMessage(ATTENTE_TEXTES.transfertImpossible);
      }
    }
  } finally {
    visiteur.ecranOuvert = false;
  }
}

function nouvelleBoussole(): ItemStack {
  // Pas de lockMode : son long texte d'objet verrouillé n'apprend rien au joueur. Une boussole jetée lui revient.
  const boussole = new ItemStack(BOUSSOLE, 1);
  boussole.nameTag = ATTENTE_TEXTES.boussole;
  boussole.keepOnDeath = true;
  return boussole;
}

/** Rend la boussole au joueur s'il ne l'a plus (jetée, rangée hors de l'inventaire). */
function verifierBoussole(player: Player): void {
  const inventaire = player.getComponent("minecraft:inventory")?.container;
  if (inventaire === undefined) return;
  for (let i = 0; i < inventaire.size; i += 1) {
    if (inventaire.getItem(i)?.typeId === BOUSSOLE) return;
  }
  if (inventaire.getItem(CASE_BOUSSOLE) === undefined) inventaire.setItem(CASE_BOUSSOLE, nouvelleBoussole());
  else inventaire.addItem(nouvelleBoussole());
}

// ---------------------------------------------------------------- le parcours

/** Un nouveau départ : couloir du joueur, bloc de départ, premier bloc à atteindre. */
function demarrer(player: Player, couloir: number): void {
  const actuel = depart(couloir);
  const course: Course = { couloir, actuel, suivant: prochainBloc(actuel, Math.random, 0), score: 0, debut: Date.now(), enAttente: true };
  courses.set(player.id, course);
  player.teleport(auDessus(actuel.bloc), { dimension: world.getDimension("overworld"), rotation: { x: 0, y: 0 } });
}

function rejoindre(player: Player): void {
  try {
    player.setGameMode(GameMode.Adventure);
  } catch (error) {
    log.warn(`mode de jeu non réglé pour un joueur : ${describeError(error)}`);
  }
  const visiteur: Visiteur = { xuid: playerXuid(player), rappels: creerRappels(), ecranOuvert: false };
  visiteurs.set(player.id, visiteur);
  verifierBoussole(player);
  const nouveau = player.getDynamicProperty(DEJA_VENU) !== true;
  player.setDynamicProperty(DEJA_VENU, true);
  player.onScreenDisplay.setTitle(ATTENTE_TEXTES.titre, { subtitle: ATTENTE_TEXTES.sousTitre, fadeInDuration: 10, stayDuration: 50, fadeOutDuration: 20 });
  player.sendMessage(ATTENTE_TEXTES.bienvenue(player.name));
  player.sendMessage(ATTENTE_TEXTES.invitation);
  const pris = [...courses.values()].map((c) => c.couloir);
  demarrer(player, couloirLibre(pris));

  void chargerJoueur(player, visiteur)
    .catch((error: unknown) => log.warn(`état du visiteur : ${describeError(error)}`))
    .then(() => attendre(ATTENTE.rythmes.accueilSecondes))
    .then(() => (player.isValid ? ouvrirAccueil(player, nouveau) : undefined))
    .catch((error: unknown) => log.error(`accueil : ${describeError(error)}`));
}

/** Un tour du parcours pour un joueur : poser le départ s'il attend, compter un saut réussi, ou constater la chute. */
function tour(player: Player, course: Course, visiteur: Visiteur): void {
  const dimension = player.dimension;
  if (course.enAttente) {
    if (poser(dimension, course.actuel.bloc, BLOC_DEPART) && poser(dimension, course.suivant.bloc, blocDe(1)) && poserHologrammes(dimension, course.couloir)) {
      course.enAttente = false;
    } else {
      player.teleport(auDessus(course.actuel.bloc)); // il plane au-dessus du départ tant que le tronçon charge
      return;
    }
  }
  const pieds = player.location;
  const relie = visiteur.joueur?.linked === true;
  if (surLeBloc(pieds, course.suivant.bloc, player.isOnGround)) {
    if (course.score > 0) retirer(dimension, course.actuel.bloc); // le bloc de départ reste : les hologrammes l'entourent
    const avant = course.score;
    course.actuel = course.suivant;
    course.score += 1;
    course.suivant = prochainBloc(course.actuel, Math.random, course.score);
    poser(dimension, course.suivant.bloc, blocDe(course.score + 1));
    player.playSound("random.orb", { pitch: Math.min(2, 0.8 + course.score * 0.02), volume: 0.6 });
    if (nouveauPalier(avant, course.score)) {
      player.onScreenDisplay.setTitle(" ", { subtitle: ATTENTE_TEXTES.palier, fadeInDuration: 0, stayDuration: 30, fadeOutDuration: 10 });
      player.playSound("random.levelup", { volume: 0.5 });
      try {
        dimension.spawnParticle("minecraft:villager_happy", { x: pieds.x, y: pieds.y + 1, z: pieds.z });
      } catch {
        // une particule manquée ne change rien au parcours
      }
    }
    if (visiteur.rappels.auScore(course.score, Date.now(), relie)) player.sendMessage(ATTENTE_TEXTES.rappel(course.score));
  } else if (estTombe(pieds, course.actuel.bloc)) {
    const score = course.score;
    const secondes = Math.round((Date.now() - course.debut) / 1000);
    if (score > 0) player.onScreenDisplay.setTitle(" ", { subtitle: ATTENTE_TEXTES.chute(score), fadeInDuration: 0, stayDuration: 30, fadeOutDuration: 10 });
    // le bloc de départ reste
    retirerOuPlusTard(dimension, course.score > 0 ? [course.actuel.bloc, course.suivant.bloc] : [course.suivant.bloc]);
    demarrer(player, course.couloir);
    void envoyerCourse(player, visiteur, score, secondes).catch((error: unknown) => log.warn(`course : ${describeError(error)}`));
    return;
  }
  const record = Math.max(visiteur.joueur?.best ?? 0, course.score);
  player.onScreenDisplay.setActionBar(ATTENTE_TEXTES.barre(course.score, record));
}

// ---------------------------------------------------------------- le branchement

function brancherService(): void {
  const reglages = readSettings();
  if (!reglages.ok) {
    log.warn(`service indisponible (codes Pionnier, records) : ${reglages.problems.join(" ; ")}`);
    return;
  }
  const { coreUrl, apiKey } = reglages.settings;
  api = createCoreApi(
    createCoreClient({ transport: createBetaTransport(), coreUrl, apiKey, timeoutSeconds: config.requestTimeoutSeconds, readRetries: config.readRetries }),
  );
}

function enBoucle(secondes: number, action: () => void): void {
  system.runInterval(() => {
    try {
      action();
    } catch (error) {
      log.error(`tâche périodique : ${describeError(error)}`);
    }
  }, Math.max(1, Math.round(secondes * TICKS)));
}

function main(): void {
  brancherService();

  world.afterEvents.worldLoad.subscribe(() => {
    poser(world.getDimension("overworld"), APPARITION, BLOC_DEPART);
    menageHologrammes();
    preparerMenage();
    if (world.scoreboard.getObjective(OBJECTIF) !== undefined) world.scoreboard.removeObjective(OBJECTIF); // les scores d'avant le service
    void chargerTableau().catch((error: unknown) => log.warn(`tableau : ${describeError(error)}`));
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
    const id = event.player.id;
    visiteurs.delete(id);
    const course = courses.get(id);
    if (course === undefined) return;
    courses.delete(id);
    const dimension = event.player.dimension;
    // Son tronçon part souvent avec lui : ce qui ne se retire pas tout de suite va dans la file du ménage.
    system.run(() => {
      retirerOuPlusTard(dimension, [course.actuel.bloc, course.suivant.bloc, depart(course.couloir).bloc]);
      retirerHologrammes(course.couloir);
    });
  });

  world.afterEvents.itemUse.subscribe((event) => {
    if (event.itemStack.typeId !== BOUSSOLE) return;
    void ouvrirAccueil(event.source, false).catch((error: unknown) => log.error(`menu : ${describeError(error)}`));
  });

  // Une boussole jetée ne reste pas au sol : elle revient au joueur au contrôle suivant.
  world.afterEvents.entitySpawn.subscribe((event) => {
    const entite = event.entity;
    if (!entite.isValid || entite.typeId !== "minecraft:item") return;
    if (entite.getComponent("minecraft:item")?.itemStack.typeId === BOUSSOLE) entite.remove();
  });

  system.runInterval(() => {
    for (const player of world.getAllPlayers()) {
      const course = courses.get(player.id);
      const visiteur = visiteurs.get(player.id);
      if (course === undefined || visiteur === undefined) continue;
      try {
        tour(player, course, visiteur);
      } catch (error) {
        log.error(`parcours : ${describeError(error)}`);
      }
    }
  }, 2);

  enBoucle(MENAGE_SECONDES, () => {
    if (!menagePret || menageEnCours) return;
    menageEnCours = true;
    void menage()
      .catch((error: unknown) => log.warn(`ménage du parcours : ${describeError(error)}`))
      .finally(() => {
        menageEnCours = false;
      });
  });
  enBoucle(ATTENTE.rythmes.boussoleSecondes, () => {
    for (const player of world.getAllPlayers()) verifierBoussole(player);
  });
  enBoucle(ATTENTE.rythmes.tableauSecondes, () => {
    void chargerTableau().catch((error: unknown) => log.warn(`tableau : ${describeError(error)}`));
  });
  // Les visiteurs pas encore reliés : le /lier fait sur Discord se voit en jeu au contrôle suivant.
  enBoucle(ATTENTE.rythmes.lienSecondes, () => {
    for (const player of world.getAllPlayers()) {
      const visiteur = visiteurs.get(player.id);
      if (visiteur === undefined || visiteur.joueur?.linked === true) continue;
      void chargerJoueur(player, visiteur).catch((error: unknown) => log.warn(`état du visiteur : ${describeError(error)}`));
    }
  });
}

try {
  main();
} catch (error) {
  log.error(`salle d'attente inactive : ${describeError(error)}`);
}
