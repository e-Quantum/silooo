import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import type { GameEvent, Xuid } from "../src/protocol";
import { COLOR } from "../src/ui/apparence";
import { createViewStore } from "../src/ui/etat";
import { createIncidents, incidentText, readIncidentEvent } from "../src/ui/incidents";
import { carteScreen, ficheScreen, usinesScreen } from "../src/ui/menu";
import { formatAmount } from "../src/ui/nombres";
import { BULLES, CARTE_TEXTES, FICHE_TEXTES, INCIDENT_TEXTES, USINES_TEXTES, renderBubble } from "../src/ui/textes";
import { companyState, factory, ok, recordingLogger } from "./helpers";
import { XUID, bare, fakeClock, fakeCompany, storeWith, unreachable, viewsWith } from "./ui-helpers";

const AUTRE: Xuid = "2535400000000001";

function event(payload: Record<string, unknown>, xuid: Xuid | null = XUID, kind = "INCIDENT"): GameEvent {
  return { id: 1, xuid, kind, payload };
}

const OUVERT = { action: "opened", companyId: 7, incidentId: 31, factoryId: "carriere", kind: "incendie", slowdown: 0.5 };
const REPARE = { action: "resolved", companyId: 7, incidentId: 31, factoryId: "carriere", kind: "incendie", reward: 600, resolvedBy: XUID };

describe("readIncidentEvent", () => {
  it("lit l'ouverture : l'incident, l'usine et la sorte, sans le ralentissement", () => {
    assert.deepEqual(readIncidentEvent(event(OUVERT)), { action: "opened", incidentId: 31, factoryId: "carriere", kind: "incendie" });
  });

  it("lit la réparation : la récompense et qui a réparé, tels que le service les donne", () => {
    assert.deepEqual(readIncidentEvent(event(REPARE)), {
      action: "resolved",
      incidentId: 31,
      factoryId: "carriere",
      kind: "incendie",
      reward: 600,
      resolvedBy: XUID,
    });
  });

  it("une récompense ou un auteur illisible reste absent : rien n'est inventé", () => {
    for (const reward of [undefined, "600", -5, Number.NaN, Number.POSITIVE_INFINITY, null]) {
      const read = readIncidentEvent(event({ ...REPARE, reward }));
      assert.ok(read !== undefined && read.action === "resolved");
      assert.equal(read.reward, undefined, String(reward));
    }
    for (const resolvedBy of [undefined, 42, "", null]) {
      const read = readIncidentEvent(event({ ...REPARE, resolvedBy }));
      assert.ok(read !== undefined && read.action === "resolved");
      assert.equal(read.resolvedBy, undefined, String(resolvedBy));
    }
    assert.equal((readIncidentEvent(event({ ...REPARE, reward: 0 })) as { reward?: number }).reward, 0);
  });

  it("la sorte manquante n'empêche pas de lire : le mot général suffit", () => {
    const read = readIncidentEvent(event({ ...OUVERT, kind: undefined }));
    assert.deepEqual(read, { action: "opened", incidentId: 31, factoryId: "carriere", kind: "" });
  });

  it("un autre type d'événement, une action inconnue ou un payload illisible ne donnent rien", () => {
    assert.equal(readIncidentEvent(event(OUVERT, XUID, "ANNONCE")), undefined);
    assert.equal(readIncidentEvent(event({ ...OUVERT, action: "closed" })), undefined);
    assert.equal(readIncidentEvent(event({ ...OUVERT, action: undefined })), undefined);
    for (const incidentId of [undefined, "31", 1.5, Number.NaN, 2 ** 60, null]) {
      assert.equal(readIncidentEvent(event({ ...OUVERT, incidentId })), undefined, String(incidentId));
    }
    for (const factoryId of [undefined, "", 7, null]) {
      assert.equal(readIncidentEvent(event({ ...OUVERT, factoryId })), undefined, String(factoryId));
    }
  });
});

describe("incidentText", () => {
  const lignes = (texte: string): string[] => bare(texte).split("\n");

  it("une ouverture : la bulle de panique, l'usine et la sorte, puis où réparer", () => {
    const read = readIncidentEvent(event(OUVERT));
    assert.ok(read);
    const texte = incidentText(read, XUID);
    assert.deepEqual(lignes(texte), [
      bare(renderBubble(BULLES.incident)),
      "Carrière : Début d'incendie",
      INCIDENT_TEXTES.ouverture.conseil,
    ]);
    assert.ok(texte.includes(`${COLOR.incident}Carrière : Début d'incendie${COLOR.reset}`), texte);
  });

  it("une sorte inconnue ou absente garde le mot Incident", () => {
    for (const kind of ["sorte-du-futur", undefined, ""]) {
      const read = readIncidentEvent(event({ ...OUVERT, kind }));
      assert.ok(read);
      assert.equal(lignes(incidentText(read, XUID))[1], "Carrière : Incident", String(kind));
    }
  });

  it("réparé par le joueur : la bulle de joie, l'usine, puis la récompense du service mise en forme", () => {
    const read = readIncidentEvent(event({ ...REPARE, reward: 12_400 }));
    assert.ok(read);
    const texte = incidentText(read, XUID);
    assert.deepEqual(lignes(texte), [
      bare(renderBubble(BULLES.repareeParToi)),
      "Carrière : réparation faite",
      `Récompense ${formatAmount(12_400)} Donar`,
    ]);
    assert.ok(texte.endsWith(`${COLOR.donar}Récompense ${formatAmount(12_400)} Donar${COLOR.reset}`), texte);
  });

  it("réparé par un associé : la bulle le dit, la récompense reste celle du service", () => {
    const read = readIncidentEvent(event({ ...REPARE, resolvedBy: AUTRE, reward: 600 }));
    assert.ok(read);
    assert.deepEqual(lignes(incidentText(read, XUID)), [
      bare(renderBubble(BULLES.repareeParUnAssocie)),
      "Carrière : réparation faite",
      `Récompense ${formatAmount(600)} Donar`,
    ]);
  });

  it("une récompense de 0 le dit : rien versé pour le joueur, ou un associé avait déjà réparé", () => {
    const parToi = readIncidentEvent(event({ ...REPARE, reward: 0 }));
    const parAutre = readIncidentEvent(event({ ...REPARE, reward: 0, resolvedBy: AUTRE }));
    assert.ok(parToi && parAutre);
    assert.equal(lignes(incidentText(parToi, XUID)).at(-1), INCIDENT_TEXTES.reparation.aucune);
    assert.equal(lignes(incidentText(parAutre, XUID)).at(-1), INCIDENT_TEXTES.reparation.dejaFaite);
  });

  it("sans récompense dans le payload, aucune somme n'est inventée", () => {
    const read = readIncidentEvent(event({ ...REPARE, reward: undefined }));
    assert.ok(read);
    assert.equal(lignes(incidentText(read, XUID)).length, 2);
    assert.doesNotMatch(incidentText(read, XUID), /Donar/);
  });
});

// ---------------------------------------------------------------- l'écoute des événements

/** Laisse passer les promesses en attente. */
const settle = (): Promise<void> => new Promise((resolve) => setImmediate(resolve));

function setup(script: Parameters<typeof fakeCompany>[0] = {}) {
  const store = storeWith({ factories: [factory({ factoryId: "carriere", incidentId: null })] });
  const views = createViewStore({ players: store, clock: fakeClock(0) });
  const api = fakeCompany(script);
  const said: Array<{ xuid: Xuid; text: string }> = [];
  const log = recordingLogger();
  const incidents = createIncidents({ api, views, say: (xuid, text) => void said.push({ xuid, text }), log });
  return { incidents, api, views, said, log };
}

const EN_INCIDENT = companyState({ version: 9, factories: [factory({ factoryId: "carriere", incidentId: 31, incidentKind: "incendie" })] });

describe("createIncidents", () => {
  it("une ouverture : le message au joueur, puis l'état relu pour que la fiche montre Réparer", async () => {
    const { incidents, api, views, said } = setup({ getCompany: [ok(EN_INCIDENT)] });
    await incidents.onIncident(event(OUVERT));
    assert.equal(said.length, 1);
    assert.equal(said[0]?.xuid, XUID);
    assert.match(bare(said[0]?.text ?? ""), /Carrière : Début d'incendie/);
    assert.deepEqual(api.calls, [{ op: "getCompany", companyId: 7 }]);
    assert.equal(views.get(XUID)?.state.version, 9);
    assert.equal(views.get(XUID)?.state.factories[0]?.incidentKind, "incendie");
  });

  it("une réparation : le message avec la récompense, l'état relu sans incident", async () => {
    const clos = companyState({ version: 10, factories: [factory({ factoryId: "carriere", incidentId: null, incidentKind: null })] });
    const { incidents, views, said } = setup({ getCompany: [ok(clos)] });
    await incidents.onIncident(event(REPARE));
    assert.match(bare(said[0]?.text ?? ""), /Récompense 600 Donar/);
    assert.equal(views.get(XUID)?.state.factories[0]?.incidentId, null);
  });

  it("un joueur qui n'est pas en ligne : ni message, ni lecture", async () => {
    const { incidents, api, said } = setup();
    await incidents.onIncident(event(OUVERT, AUTRE));
    await incidents.onIncident(event(OUVERT, null));
    assert.deepEqual(said, []);
    assert.deepEqual(api.calls, []);
  });

  it("un événement illisible ou d'un autre type n'a aucun effet", async () => {
    const { incidents, api, said } = setup();
    await incidents.onIncident(event({ ...OUVERT, incidentId: "x" }));
    await incidents.onIncident(event(OUVERT, XUID, "ANNONCE"));
    assert.deepEqual(said, []);
    assert.deepEqual(api.calls, []);
  });

  it("le service ne répond pas à la relecture : le message est dit quand même, l'état ne bouge pas, le journal le note", async () => {
    const { incidents, views, said, log } = setup({ getCompany: [unreachable()] });
    const avant = views.get(XUID)?.state.version;
    await incidents.onIncident(event(OUVERT));
    assert.equal(said.length, 1);
    assert.equal(views.get(XUID)?.state.version, avant);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /incident/);
  });

  it("une exception du tchat ou de la relecture est journalisée, jamais relancée", async () => {
    const store = storeWith();
    const views = createViewStore({ players: store, clock: fakeClock(0) });
    const log = recordingLogger();
    const api = { getCompany: () => Promise.reject(new Error("coupure")) };
    const incidents = createIncidents({
      api,
      views,
      say: () => {
        throw new Error("joueur parti");
      },
      log,
    });
    await incidents.onIncident(event(OUVERT));
    await settle();
    assert.equal(log.warns.length, 2);
    assert.match(log.warns.join("\n"), /joueur parti/);
    assert.match(log.warns.join("\n"), /coupure/);
  });
});

describe("branchement dans main.ts", () => {
  it("l'événement INCIDENT est écouté, et l'écoute passe par ui/incidents.ts", () => {
    const main = readFileSync(resolve("src", "main.ts"), "utf8");
    assert.match(main, /createIncidents\(/);
    assert.match(main, /listeners\.on\("INCIDENT",\s*incidents\.onIncident\)/);
  });
});

// ---------------------------------------------------------------- les écrans

function viewOf(...factories: ReturnType<typeof factory>[]) {
  const view = viewsWith({ factories }).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

const SORTES = [
  { kind: "incendie", nom: "Début d'incendie", phrase: "Le feu a pris, la production ralentit." },
  { kind: "panne", nom: "Machine en panne", phrase: "Une machine est en panne, la production ralentit." },
  { kind: "courroie", nom: "Courroie cassée", phrase: "Une courroie a cassé, la production ralentit." },
];

describe("la fiche d'une usine en incident", () => {
  for (const { kind, nom, phrase } of SORTES) {
    it(`${kind} : la sorte nommée en capitales, sa phrase, et Réparer en premier bouton`, () => {
      const fiche = ficheScreen(viewOf(factory({ factoryId: "carriere", incidentId: 31, incidentKind: kind })), "carriere", 2);
      assert.ok(fiche);
      assert.deepEqual(bare(fiche.spec.body).split("\n").slice(0, 3), [nom.toUpperCase(), "Carrière", phrase]);
      assert.equal(bare(fiche.spec.buttons[0] ?? ""), INCIDENT_TEXTES.reparer);
      assert.deepEqual(fiche.targets[0], { kind: "repair", factoryId: "carriere", incidentId: 31, incidentKind: kind });
      assert.deepEqual(fiche.targets.at(-1), { kind: "go", route: { screen: "usines", page: 2 } });
    });
  }

  it("la montée de niveau reste possible (canBuyLevels) : quatre boutons d'achat après Réparer", () => {
    const fiche = ficheScreen(viewOf(factory({ incidentId: 31, incidentKind: "panne" })), "carriere", 0);
    assert.ok(fiche);
    assert.deepEqual(
      fiche.targets.map((target) => (target.kind === "buy" ? target.amount : target.kind)),
      ["repair", "1", "10", "palier", "max", "go", "go"],
    );
  });

  it("une sorte inconnue, nulle ou absente garde le mot Incident, la phrase du général et le bouton Réparer", () => {
    for (const incidentKind of ["sorte-du-futur", null, undefined]) {
      const fiche = ficheScreen(viewOf(factory({ incidentId: 31, ...(incidentKind === undefined ? {} : { incidentKind }) })), "carriere", 0);
      assert.ok(fiche);
      assert.deepEqual(bare(fiche.spec.body).split("\n").slice(0, 3), [FICHE_TEXTES.sorte.INCIDENT, "Carrière", FICHE_TEXTES.phrase.INCIDENT]);
      assert.equal(fiche.targets[0]?.kind, "repair");
    }
  });

  it("l'incident numéro 0 se répare comme un autre", () => {
    const fiche = ficheScreen(viewOf(factory({ incidentId: 0, incidentKind: "incendie" })), "carriere", 0);
    assert.deepEqual(fiche?.targets[0], { kind: "repair", factoryId: "carriere", incidentId: 0, incidentKind: "incendie" });
  });

  it("une usine sans incident n'a pas de bouton Réparer", () => {
    for (const unite of [factory({ incidentId: null }), factory({})]) {
      const fiche = ficheScreen(viewOf(unite), "carriere", 0);
      assert.ok(fiche);
      assert.ok(!fiche.targets.some((target) => target.kind === "repair"));
      assert.ok(!bare(fiche.spec.body).includes(INCIDENT_TEXTES.sansCourant));
    }
  });
});

describe("la fiche d'une usine arrêtée faute de courant", () => {
  const fiche = ficheScreen(viewOf(factory({ stoppedNoPower: true })), "carriere", 0);

  it("dit qu'il faut une Centrale en marche en dernière ligne, en couleur de blocage", () => {
    assert.ok(fiche);
    assert.equal(bare(fiche.spec.body).split("\n").at(-1), INCIDENT_TEXTES.sansCourant);
    assert.ok(fiche.spec.body.endsWith(`${COLOR.blocked}${INCIDENT_TEXTES.sansCourant}${COLOR.reset}`));
    assert.match(bare(fiche.spec.body), new RegExp(`^${FICHE_TEXTES.sorte.SANS_COURANT}\nCarrière\n${FICHE_TEXTES.phrase.SANS_COURANT}`));
  });

  it("n'a aucun bouton Réparer, mais garde la montée de niveau", () => {
    assert.ok(fiche);
    assert.ok(!fiche.targets.some((target) => target.kind === "repair"));
    assert.deepEqual(
      fiche.targets.map((target) => (target.kind === "buy" ? target.amount : target.kind)),
      ["1", "10", "palier", "max", "go", "go"],
    );
    assert.ok(!fiche.spec.buttons.some((button) => bare(button).includes(INCIDENT_TEXTES.reparer)));
  });

  it("un incident sur une usine sans courant garde Réparer et dit aussi qu'il faut du courant", () => {
    const double = ficheScreen(viewOf(factory({ incidentId: 5, incidentKind: "courroie", stoppedNoPower: true })), "carriere", 0);
    assert.ok(double);
    assert.equal(double.targets[0]?.kind, "repair");
    assert.equal(bare(double.spec.body).split("\n").at(-1), INCIDENT_TEXTES.sansCourant);
  });
});

describe("la tuile et la carte nomment la sorte de l'incident", () => {
  it("la tuile : repère d'incident, couleur d'incident et nom de la sorte ; sans courant et sorte inconnue gardent leurs mots", () => {
    const view = viewOf(
      factory({ factoryId: "carriere", incidentId: 1, incidentKind: "incendie" }),
      factory({ factoryId: "scierie", incidentId: 2, incidentKind: "panne" }),
      factory({ factoryId: "ferme", incidentId: 3, incidentKind: "courroie" }),
      factory({ factoryId: "fonderie", incidentId: 4, incidentKind: null }),
      factory({ factoryId: "centrale", stoppedNoPower: true }),
    );
    const screen = usinesScreen(view, 0, 8);
    assert.deepEqual(screen.spec.buttons.slice(0, 5).map(bare), [
      "[!] Carrière\nDébut d'incendie",
      "[!] Scierie\nMachine en panne",
      "[!] Ferme\nCourroie cassée",
      `[!] Fonderie\n${USINES_TEXTES.tuile.incident}`,
      `[E] Centrale\n${USINES_TEXTES.tuile.sansCourant}`,
    ]);
    for (const index of [0, 1, 2, 3]) assert.ok(screen.spec.buttons[index]?.includes(COLOR.incident), String(index));
  });

  it("la carte : la sorte puis le niveau, ou « Sans courant » puis le niveau, avec le repère de l'état", () => {
    const view = viewOf(
      factory({ factoryId: "carriere", level: 12, incidentId: 1, incidentKind: "incendie" }),
      factory({ factoryId: "scierie", level: 7, incidentId: 2, incidentKind: null }),
      factory({ factoryId: "ferme", level: 4, stoppedNoPower: true }),
    );
    const screen = carteScreen(view, 0, 20);
    assert.equal(bare(screen.spec.buttons[1] ?? ""), `[!] Carrière\n${CARTE_TEXTES.etat.incident("Début d'incendie", "12")}`);
    assert.equal(bare(screen.spec.buttons[2] ?? ""), `[!] Scierie\n${CARTE_TEXTES.etat.incident(USINES_TEXTES.tuile.incident, "7")}`);
    assert.equal(bare(screen.spec.buttons[3] ?? ""), `[E] Ferme\n${CARTE_TEXTES.etat.sansCourant("4")}`);
    assert.equal(CARTE_TEXTES.etat.sansCourant("4"), "Sans courant · niv. 4");
    assert.ok(screen.spec.buttons[1]?.includes(COLOR.incident));
  });
});
