// La cinématique d'arrivée : une chronologie de 20 s en quatre plans, et un lecteur qui masque le HUD, fige les
// déplacements, se laisse passer en restant accroupi une seconde, et rend toujours la main.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import {
  buildTimeline,
  createCinematicRunner,
  type CinematicOutcome,
  type CinematicStage,
  type Step,
  type Timeline,
} from "../src/monde/cinematique";
import { ILES } from "../src/monde/definitions";
import { arrivalOf, surfaceY, type Pos } from "../src/monde/geometrie";
import { CINEMATIQUE_TEXTES } from "../src/ui/textes";
import { recordingLogger } from "./helpers";
import { virtualTime } from "./monde-helpers";

const TEXTES = { speaker: CINEMATIQUE_TEXTES.qui, plans: CINEMATIQUE_TEXTES.plans };
const arrivee = arrivalOf({ x: 0, z: 0 }, ILES);
assert.ok(arrivee !== undefined);
const CHRONOLOGIE = buildTimeline(ILES, arrivee, TEXTES);

const mots = (texte: string): number => texte.split(/\s+/).filter((mot) => mot !== "").length;
const etapes = <K extends Step["kind"]>(timeline: Timeline, kind: K): Array<Extract<Step, { kind: K }>> =>
  timeline.steps.filter((step): step is Extract<Step, { kind: K }> => step.kind === kind);

describe("buildTimeline", () => {
  it("dure vingt secondes, en quatre plans", () => {
    assert.equal(CHRONOLOGIE.totalSeconds, 20);
    assert.equal(etapes(CHRONOLOGIE, "cut").length, 4);
    assert.equal(etapes(CHRONOLOGIE, "move").length, 4);
    assert.equal(etapes(CHRONOLOGIE, "subtitle").length, 4);
  });

  it("est dans l'ordre du temps, et finit avant la fin", () => {
    const instants = CHRONOLOGIE.steps.map((step) => step.at);
    assert.deepEqual(instants, [...instants].sort((a, b) => a - b));
    for (const instant of instants) assert.ok(instant >= 0 && instant < CHRONOLOGIE.totalSeconds, `${instant}`);
  });

  it("ouvre sur un fondu et se ferme sur un fondu, chaque coupe de plan est cachée par un fondu", () => {
    const [premier] = CHRONOLOGIE.steps;
    assert.equal(premier?.kind, "fade");
    assert.equal(premier?.at, 0);
    const fondus = etapes(CHRONOLOGIE, "fade").map((fondu) => fondu.at);
    assert.equal(fondus.length, 5);
    assert.equal(fondus.at(-1), CHRONOLOGIE.totalSeconds - config.cinematic.fadeInSeconds);
    for (const coupe of etapes(CHRONOLOGIE, "cut").slice(1)) {
      assert.ok(fondus.some((f) => Math.abs(f + config.cinematic.fadeInSeconds - coupe.at) < 1e-9), `coupe à ${coupe.at}`);
    }
  });

  it("chaque sous-titre reste au moins trois secondes à l'écran, et dit la phrase d'Oooscar", () => {
    const titres = etapes(CHRONOLOGIE, "subtitle");
    titres.forEach((titre, index) => {
      assert.ok(titre.seconds >= 3, `${titre.text} : ${titre.seconds} s`);
      assert.equal(titre.speaker, "Oooscar");
      assert.equal(titre.text, CINEMATIQUE_TEXTES.plans[index]);
      assert.ok(mots(titre.text) <= 8, titre.text);
    });
  });

  it("un sous-titre ne recouvre pas le suivant, et reste dans le plan qui le porte", () => {
    const titres = etapes(CHRONOLOGIE, "subtitle");
    titres.forEach((titre, index) => {
      const debutPlan = index * 5;
      assert.ok(titre.at >= debutPlan && titre.at + titre.seconds <= debutPlan + 5, `plan ${index + 1}`);
      const suivant = titres[index + 1];
      if (suivant !== undefined) assert.ok(titre.at + titre.seconds < suivant.at);
    });
  });

  it("la caméra est toujours au-dessus du sol de l'île, et le glissé finit juste avant le fondu suivant", () => {
    for (const etape of [...etapes(CHRONOLOGIE, "cut"), ...etapes(CHRONOLOGIE, "move")]) {
      assert.ok(etape.camera.y > surfaceY(ILES), `${etape.at}`);
    }
    for (const glisse of etapes(CHRONOLOGIE, "move")) {
      const fin = glisse.at + glisse.seconds;
      assert.ok(fin <= CHRONOLOGIE.totalSeconds - config.cinematic.fadeInSeconds + 1e-9);
      assert.ok(glisse.seconds >= 3, "un vrai mouvement, pas un saut");
    }
  });

  it("le premier plan est un survol de loin, le dernier se termine près du joueur, devant le bâtiment", () => {
    assert.ok(arrivee !== undefined);
    const distance = (point: Pos): number => Math.hypot(point.x - arrivee.frame.center.x, point.z - arrivee.frame.center.z);
    const coupes = etapes(CHRONOLOGIE, "cut");
    const glissés = etapes(CHRONOLOGIE, "move");
    assert.ok(distance(coupes[0]?.camera ?? arrivee.position) > 50);
    assert.ok(distance(glissés[3]?.camera ?? arrivee.position) < 10);
    assert.ok(distance(coupes[0]?.camera ?? arrivee.position) > distance(coupes[3]?.camera ?? arrivee.position));
  });

  it("ne montre pas de phrase vide : un plan sans texte n'a pas de sous-titre", () => {
    const sans = buildTimeline(ILES, arrivee, { speaker: "Oooscar", plans: ["Une phrase.", "", "Une autre.", ""] });
    assert.equal(etapes(sans, "subtitle").length, 2);
    assert.equal(sans.totalSeconds, 20);
  });
});

// ---------------------------------------------------------------- le lecteur

interface Joueur {
  readonly nom: string;
  accroupi: boolean;
  present: boolean;
}

/** Une scène qui note tout ce qu'on lui fait, dans l'ordre, et qui peut échouer sur commande. */
function scene() {
  const appels: string[] = [];
  const pannes = new Set<string>();
  function note(nom: string, ...details: unknown[]): void {
    appels.push(details.length === 0 ? nom : `${nom} ${details.join(" ")}`);
    if (pannes.has(nom)) throw new Error(`${nom} en panne`);
  }
  const stage: CinematicStage<Joueur> = {
    isValid: (joueur) => joueur.present,
    isSneaking: (joueur) => joueur.accroupi,
    hideHud: () => note("hideHud"),
    showHud: () => note("showHud"),
    lockInput: () => note("lockInput"),
    unlockInput: () => note("unlockInput"),
    setCamera: () => note("setCamera"),
    moveCamera: (_joueur, _camera, _regard, secondes) => note("moveCamera", secondes),
    clearCamera: () => note("clearCamera"),
    fade: (_joueur, entree, tenue, sortie) => note("fade", entree, tenue, sortie),
    subtitle: (_joueur, qui, texte, secondes) => note("subtitle", qui, texte, secondes),
    clearSubtitle: () => note("clearSubtitle"),
    hint: (_joueur, texte) => note("hint", texte),
    clearHint: () => note("clearHint"),
  };
  return { stage, appels, pannes, compte: (nom: string) => appels.filter((a) => a === nom || a.startsWith(`${nom} `)).length };
}

function banc() {
  const temps = virtualTime();
  const s = scene();
  const log = recordingLogger();
  const runner = createCinematicRunner({ stage: s.stage, clock: temps.clock, log, hint: CINEMATIQUE_TEXTES.passer });
  /** Fait avancer le temps par pas de 50 ms, comme le tick réel, en appelant `tick` à chaque pas. */
  async function jouer(secondes: number): Promise<void> {
    const pas = config.cinematic.tickSeconds;
    for (let fait = 0; fait < secondes - 1e-9; fait += pas) {
      await temps.advance(pas);
      runner.tick();
    }
  }
  return { temps, s, log, runner, jouer };
}

const ALEX = (): Joueur => ({ nom: "Alex", accroupi: false, present: true });

function demarre(b: ReturnType<typeof banc>, joueur: Joueur, key = "alex"): { issue: Promise<CinematicOutcome> } {
  return { issue: b.runner.start(key, joueur, CHRONOLOGIE) };
}

describe("le lecteur de cinématique", () => {
  it("masque le HUD et fige les déplacements avant le premier fondu", () => {
    const b = banc();
    demarre(b, ALEX());
    assert.deepEqual(b.s.appels.slice(0, 3), ["hideHud", "lockInput", `fade ${config.cinematic.fadeInSeconds} ${config.cinematic.fadeHoldSeconds} ${config.cinematic.fadeOutSeconds}`]);
    assert.equal(b.runner.isPlaying("alex"), true);
  });

  it("joue les quatre plans en vingt secondes, puis rend tout au joueur, dans cet ordre", async () => {
    const b = banc();
    const { issue } = demarre(b, ALEX());
    await b.jouer(20.2);
    assert.equal(await issue, "finished");
    assert.equal(b.runner.isPlaying("alex"), false);
    assert.equal(b.s.compte("setCamera"), 4);
    assert.equal(b.s.compte("moveCamera"), 4);
    assert.equal(b.s.compte("subtitle"), 4);
    assert.equal(b.s.compte("fade"), 5);
    assert.deepEqual(b.s.appels.slice(-5), ["clearSubtitle", "clearHint", "clearCamera", "unlockInput", "showHud"]);
    assert.equal(b.temps.elapsed() >= 20 && b.temps.elapsed() < 20.3, true);
  });

  it("dit les phrases dans l'ordre, avec le nom de qui parle", async () => {
    const b = banc();
    demarre(b, ALEX());
    await b.jouer(20.2);
    const dites = b.s.appels.filter((a) => a.startsWith("subtitle "));
    assert.equal(dites.length, 4);
    dites.forEach((appel, index) => assert.ok(appel.startsWith(`subtitle Oooscar ${CINEMATIQUE_TEXTES.plans[index]} `), appel));
  });

  it("rappelle la consigne pour passer environ chaque seconde", async () => {
    const b = banc();
    demarre(b, ALEX());
    await b.jouer(10.05);
    const rappels = b.s.compte("hint");
    assert.ok(rappels >= 10 && rappels <= 12, `${rappels}`);
    assert.ok(b.s.appels.includes(`hint ${CINEMATIQUE_TEXTES.passer}`));
  });

  it("ne passe pas avant que le joueur ait tenu accroupi une seconde de suite", async () => {
    const b = banc();
    const joueur = ALEX();
    const { issue } = demarre(b, joueur);
    await b.jouer(3);
    joueur.accroupi = true;
    await b.jouer(0.9);
    assert.equal(b.runner.isPlaying("alex"), true);
    await b.jouer(0.2);
    assert.equal(await issue, "skipped");
    assert.equal(b.runner.isPlaying("alex"), false);
    assert.equal(b.temps.elapsed() < 5, true, "on a passé au bout d'environ quatre secondes");
    assert.deepEqual(b.s.appels.slice(-6), ["fade 0.2 0.2 0.3", "clearSubtitle", "clearHint", "clearCamera", "unlockInput", "showHud"]);
  });

  it("se laisse relâcher : s'accroupir à peine puis se relever remet le compte à zéro", async () => {
    const b = banc();
    const joueur = ALEX();
    demarre(b, joueur);
    for (let i = 0; i < 5; i += 1) {
      joueur.accroupi = true;
      await b.jouer(0.6);
      joueur.accroupi = false;
      await b.jouer(0.3);
    }
    assert.equal(b.runner.isPlaying("alex"), true);
  });

  it("ne rejoue pas ce qui est passé : on ne reprend pas les plans manqués", async () => {
    const b = banc();
    const joueur = ALEX();
    demarre(b, joueur);
    joueur.accroupi = true;
    await b.jouer(1.2);
    assert.equal(b.s.compte("subtitle"), 1, "seule la première phrase a eu le temps de partir");
    assert.equal(b.s.compte("setCamera"), 1);
  });

  it("s'arrête si le joueur part, et remet ce qui peut l'être", async () => {
    const b = banc();
    const joueur = ALEX();
    const { issue } = demarre(b, joueur);
    await b.jouer(2);
    joueur.present = false;
    await b.jouer(0.1);
    assert.equal(await issue, "aborted");
    assert.equal(b.s.compte("unlockInput"), 1);
    assert.equal(b.s.compte("showHud"), 1);
    assert.equal(b.s.compte("fade"), 1, "pas de fondu de sortie quand le joueur est parti");
  });

  it("rend la main même quand la scène tombe en panne, chaque remise en état étant tentée à part", async () => {
    const b = banc();
    const { issue } = demarre(b, ALEX());
    await b.jouer(1);
    for (const nom of ["clearSubtitle", "clearHint", "clearCamera", "unlockInput", "showHud"]) b.s.pannes.add(nom);
    b.runner.cancel("alex");
    assert.equal(await issue, "aborted");
    assert.deepEqual(b.s.appels.slice(-5), ["clearSubtitle", "clearHint", "clearCamera", "unlockInput", "showHud"]);
    assert.equal(b.log.warns.length, 5);
    assert.equal(b.runner.isPlaying("alex"), false);
  });

  it("une panne au milieu d'un plan arrête la cinématique de ce joueur, journalise l'erreur, et rend la main", async () => {
    const b = banc();
    const { issue } = demarre(b, ALEX());
    b.s.pannes.add("moveCamera");
    await b.jouer(1);
    assert.equal(await issue, "aborted");
    assert.equal(b.log.errors.length, 1);
    assert.deepEqual(b.s.appels.slice(-5), ["clearSubtitle", "clearHint", "clearCamera", "unlockInput", "showHud"]);
  });

  it("une panne au tout début (HUD impossible à masquer) n'empêche pas la cinématique ni la remise en état", async () => {
    const b = banc();
    b.s.pannes.add("hideHud");
    const { issue } = demarre(b, ALEX());
    await b.jouer(20.2);
    assert.equal(await issue, "finished");
    assert.ok(b.log.warns.some((ligne) => ligne.includes("HUD masqué impossible")));
    assert.equal(b.s.compte("showHud"), 1);
  });

  it("une seconde cinématique pour le même joueur arrête la première, qui rend la main avant", async () => {
    const b = banc();
    const premier = demarre(b, ALEX());
    await b.jouer(1);
    const second = demarre(b, ALEX());
    assert.equal(await premier.issue, "aborted");
    assert.equal(b.runner.isPlaying("alex"), true);
    await b.jouer(20.2);
    assert.equal(await second.issue, "finished");
    assert.equal(b.s.compte("showHud"), 2);
  });

  it("deux joueurs ne se gênent pas : la sortie de l'un ne touche pas l'autre", async () => {
    const b = banc();
    const alex = ALEX();
    const bob = { nom: "Bob", accroupi: false, present: true };
    const a = demarre(b, alex, "alex");
    const c = demarre(b, bob, "bob");
    await b.jouer(1);
    alex.accroupi = true;
    await b.jouer(1.2);
    assert.equal(await a.issue, "skipped");
    assert.equal(b.runner.isPlaying("bob"), true);
    await b.jouer(20);
    assert.equal(await c.issue, "finished");
  });

  it("cancel dit s'il y avait quelque chose à arrêter", () => {
    const b = banc();
    assert.equal(b.runner.cancel("alex"), false);
    demarre(b, ALEX());
    assert.equal(b.runner.cancel("alex"), true);
    assert.equal(b.runner.cancel("alex"), false);
  });
});
