import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ActionFormSpec, FormAnswer } from "../src/ui/formulaires";
import { playRepair, repairMove, repairScreen, repairStepCount } from "../src/ui/reparation";
import { INCIDENT_TEXTES_KINDS, REPARATION_TEXTES, repairSteps } from "../src/ui/textes";
import { bare } from "./ui-helpers";

/** Toutes les sortes du jeu, plus ce que l'add-on ne connaît pas : le service peut en ajouter, ou ne rien dire. */
const SORTES: ReadonlyArray<string | null | undefined> = [...INCIDENT_TEXTES_KINDS, null, undefined, "sorte-du-futur", "constructor"];

/** Le joueur d'un test : une réponse par écran, dans l'ordre. Un écran de trop est une erreur du test. */
type Plan = ReadonlyArray<number | "retour" | "fermer" | "occupe">;

function joueur(plan: Plan) {
  const shown: ActionFormSpec[] = [];
  let i = 0;
  return {
    shown,
    async show(spec: ActionFormSpec): Promise<FormAnswer> {
      shown.push(spec);
      const move = plan[i];
      i += 1;
      if (move === undefined) throw new Error(`écran imprévu : ${spec.body}`);
      if (move === "fermer") return { kind: "closed" };
      if (move === "occupe") return { kind: "busy" };
      if (move === "retour") return { kind: "selected", index: spec.buttons.length - 1 };
      return { kind: "selected", index: move };
    },
  };
}

/** Les bons boutons d'une sorte, étape après étape. */
const bons = (kind: string | null | undefined): number[] => repairSteps(kind).map((step) => step.bonne);

describe("repairMove", () => {
  it("chaque sorte a trois étapes, et une sorte inconnue ou absente joue le mini-jeu général", () => {
    for (const kind of SORTES) {
      assert.ok(repairStepCount(kind) >= 3 && repairStepCount(kind) <= 4, String(kind));
    }
    assert.equal(repairStepCount(null), REPARATION_TEXTES.generique.length);
    assert.equal(repairStepCount("sorte-du-futur"), REPARATION_TEXTES.generique.length);
    assert.equal(repairStepCount("constructor"), REPARATION_TEXTES.generique.length);
  });

  it("le bon bouton passe à l'étape suivante, puis la dernière réussie termine", () => {
    for (const kind of SORTES) {
      const total = repairStepCount(kind);
      const right = bons(kind);
      for (let step = 0; step < total - 1; step += 1) {
        assert.deepEqual(repairMove(kind, step, right[step] ?? -1), { kind: "next", step: step + 1 }, `${kind} ${step}`);
      }
      assert.deepEqual(repairMove(kind, total - 1, right[total - 1] ?? -1), { kind: "done" }, String(kind));
    }
  });

  it("un mauvais bouton rend l'indice de l'étape et ne passe pas à la suite, quel que soit l'indice", () => {
    for (const kind of SORTES) {
      repairSteps(kind).forEach((step, index) => {
        for (const choice of [...step.choix.keys(), -1, step.choix.length, 99, 1.5, Number.NaN]) {
          if (choice === step.bonne) continue;
          assert.deepEqual(repairMove(kind, index, choice), { kind: "wrong", bubble: step.erreur }, `${kind} ${index} ${choice}`);
        }
      });
    }
  });

  it("chaque étape a deux ou trois boutons dont un seul est le bon", () => {
    for (const kind of SORTES) {
      repairSteps(kind).forEach((step, index) => {
        assert.ok(step.choix.length >= 2 && step.choix.length <= 3, step.consigne);
        const justes = step.choix.filter((_, choice) => repairMove(kind, index, choice).kind !== "wrong");
        assert.equal(justes.length, 1, step.consigne);
        assert.equal(justes[0], step.choix[step.bonne], step.consigne);
      });
    }
  });

  it("une étape hors du mini-jeu est une erreur du programme", () => {
    for (const step of [-1, 3, 4, 99, 1.5, Number.NaN]) {
      assert.throws(() => repairMove("incendie", step, 0), RangeError, String(step));
    }
  });

  it("est pure : mêmes entrées, même sortie, et rien ne change dans les textes", () => {
    const avant = JSON.stringify(REPARATION_TEXTES);
    for (let i = 0; i < 3; i += 1) {
      assert.deepEqual(repairMove("panne", 1, 0), repairMove("panne", 1, 0));
    }
    assert.equal(JSON.stringify(REPARATION_TEXTES), avant);
  });
});

describe("repairScreen", () => {
  it("la sorte et l'usine, où l'on en est, la consigne, puis les boutons de l'étape et Retour en dernier", () => {
    const spec = repairScreen("carriere", "incendie", 0);
    const step = repairSteps("incendie")[0];
    assert.equal(spec.title, REPARATION_TEXTES.titre);
    assert.deepEqual(bare(spec.body).split("\n"), [
      "DÉBUT D'INCENDIE",
      "Carrière",
      REPARATION_TEXTES.etape(1, 3),
      "",
      step?.consigne,
    ]);
    assert.deepEqual(spec.buttons, [...(step?.choix ?? []), REPARATION_TEXTES.retour]);
  });

  it("l'étape affichée suit l'étape jouée", () => {
    assert.match(bare(repairScreen("scierie", "panne", 1).body), /Étape 2 sur 3/);
    assert.match(bare(repairScreen("scierie", "courroie", 2).body), /Étape 3 sur 3/);
  });

  it("une mauvaise réponse met l'indice d'Oooscar en tête et garde la même étape", () => {
    const step = repairSteps("panne")[0];
    assert.ok(step);
    const spec = repairScreen("scierie", "panne", 0, { bubble: step.erreur });
    assert.equal(bare(spec.body).split("\n")[0], `Oooscar [oups] ${step.erreur.text}`);
    assert.match(bare(spec.body), /Étape 1 sur 3/);
    assert.deepEqual(spec.buttons, [...step.choix, REPARATION_TEXTES.retour]);
  });

  it("une sorte inconnue ou absente garde le mot Incident et les écrans du mini-jeu général", () => {
    for (const kind of [null, undefined, "sorte-du-futur"]) {
      const spec = repairScreen("carriere", kind, 0);
      assert.equal(bare(spec.body).split("\n")[0], "INCIDENT");
      assert.match(spec.body, new RegExp(REPARATION_TEXTES.generique[0]?.consigne ?? "absent"));
    }
  });

  it("une étape hors du mini-jeu est une erreur du programme", () => {
    for (const step of [-1, 3, 1.5, Number.NaN]) {
      assert.throws(() => repairScreen("carriere", "incendie", step), RangeError, String(step));
    }
  });
});

describe("playRepair", () => {
  it("toutes les bonnes réponses : une fenêtre par étape, puis done", async () => {
    for (const kind of SORTES) {
      const play = joueur(bons(kind));
      assert.equal(await playRepair(play, "carriere", kind), "done", String(kind));
      assert.equal(play.shown.length, repairStepCount(kind));
      assert.deepEqual(
        play.shown.map((spec) => bare(spec.body).split("\n").at(-1)),
        repairSteps(kind).map((step) => step.consigne),
      );
    }
  });

  it("un mauvais choix rejoue la même étape avec l'indice, sans rien perdre", async () => {
    const right = bons("incendie");
    const wrong = (right[0] ?? 0) === 0 ? 1 : 0;
    const play = joueur([wrong, ...right]);
    assert.equal(await playRepair(play, "carriere", "incendie"), "done");
    assert.equal(play.shown.length, 4);
    assert.doesNotMatch(bare(play.shown[0]?.body ?? ""), /Oooscar/);
    assert.match(bare(play.shown[1]?.body ?? ""), /^Oooscar \[oups\] /);
    assert.match(bare(play.shown[1]?.body ?? ""), /Étape 1 sur 3/);
    assert.doesNotMatch(bare(play.shown[2]?.body ?? ""), /Oooscar/);
  });

  it("autant d'erreurs que l'on veut : le jeu ne se termine que par une réussite, Retour ou la fermeture", async () => {
    const right = bons("courroie");
    const wrong = (right[0] ?? 0) === 0 ? 1 : 0;
    const play = joueur([...Array.from({ length: 25 }, () => wrong), ...right]);
    assert.equal(await playRepair(play, "carriere", "courroie"), "done");
    assert.equal(play.shown.length, 25 + 3);
  });

  it("Retour quitte le jeu sans le réussir, à n'importe quelle étape", async () => {
    const right = bons("panne");
    const first = joueur(["retour"]);
    assert.equal(await playRepair(first, "carriere", "panne"), "back");
    assert.equal(first.shown.length, 1);
    const second = joueur([right[0] ?? 0, "retour"]);
    assert.equal(await playRepair(second, "carriere", "panne"), "back");
    assert.equal(second.shown.length, 2);
  });

  it("fermer la fenêtre ou un écran que Bedrock ne montre pas termine sans réussite", async () => {
    assert.equal(await playRepair(joueur(["fermer"]), "carriere", "incendie"), "closed");
    assert.equal(await playRepair(joueur(["occupe"]), "carriere", "incendie"), "closed");
    assert.equal(await playRepair(joueur([bons("incendie")[0] ?? 0, "fermer"]), "carriere", "incendie"), "closed");
  });

  it("une exception du présentateur remonte, le menu la journalise", async () => {
    const play = { show: (): Promise<FormAnswer> => Promise.reject(new Error("Bedrock est tombé")) };
    await assert.rejects(playRepair(play, "carriere", "incendie"), /Bedrock est tombé/);
  });
});
