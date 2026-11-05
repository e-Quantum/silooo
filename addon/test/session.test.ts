import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CoreApi } from "../src/api";
import type { ApiResult } from "../src/http";
import type { JoinResponse } from "../src/protocol";
import { combineHooks, createSessionHandlers, registerSessionEvents, type LeaveEventLike, type SessionHooks, type SpawnEventLike } from "../src/session";
import { createPlayerStore } from "../src/state";
import { provisionalXuid as X } from "../src/identity";
import { joinResponse, ok, recordingLogger } from "./helpers";

type Api = Pick<CoreApi, "join" | "leave">;

function setup(api: Partial<Api> = {}) {
  const calls: string[] = [];
  const full: Api = {
    join: api.join ?? (async (xuid, gamertag) => {
      calls.push(`join ${xuid} ${gamertag}`);
      return ok(joinResponse({ xuid }));
    }),
    leave: api.leave ?? (async (xuid) => {
      calls.push(`leave ${xuid}`);
      return ok(null, 204);
    }),
  };
  const store = createPlayerStore();
  const log = recordingLogger();
  return { handlers: createSessionHandlers({ api: full, store, log }), store, log, calls };
}

const spawn = (id: string, name: string, initialSpawn = true): SpawnEventLike => ({ initialSpawn, player: { id, name } });
const leave = (id: string, name: string): LeaveEventLike => ({ playerId: id, playerName: name });

describe("arrivée", () => {
  it("join au premier passage, état gardé par XUID", async () => {
    const { handlers, store, calls, log } = setup();
    await handlers.onSpawn(spawn("42", "Alex"));
    assert.deepEqual(calls, [`join ${X("Alex")} Alex`]);
    assert.equal(store.get(X("Alex"))?.gamertag, "Alex");
    assert.equal(store.get(X("Alex"))?.join.companyId, 7);
    assert.match(log.infos.join("\n"), /join de Alex : premier passage, entreprise 7, 0 usine/);
  });

  it("une réapparition (mort, retour d'une dimension) ne rappelle pas join", async () => {
    const { handlers, calls, store } = setup();
    await handlers.onSpawn(spawn("42", "Alex", false));
    assert.deepEqual(calls, []);
    assert.equal(store.size, 0);
  });

  it("un retour est journalisé comme tel", async () => {
    const { handlers, log } = setup({ join: async (xuid) => ok(joinResponse({ xuid, firstJoin: false })) });
    await handlers.onSpawn(spawn("42", "Alex"));
    assert.match(log.infos.join("\n"), /retour/);
  });

  it("un refus est journalisé une fois, rien n'est gardé, pas de nouvel essai", async () => {
    let appels = 0;
    const { handlers, store, log } = setup({
      join: async () => {
        appels += 1;
        return { kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" } satisfies ApiResult<JoinResponse>;
      },
    });
    await handlers.onSpawn(spawn("42", "Alex"));
    assert.equal(appels, 1);
    assert.equal(store.size, 0);
    assert.deepEqual(log.warns, ["join de Alex : refusé CONFLIT_REESSAYER"]);
  });

  it("une erreur du service ou du réseau est journalisée sans lever", async () => {
    const { handlers, log } = setup({
      join: async () => ({ kind: "error", status: 401, reason: "CLE_INVALIDE" }),
    });
    await handlers.onSpawn(spawn("1", "A"));
    const reseau = setup({ join: async () => ({ kind: "network", message: "coupé" }) });
    await reseau.handlers.onSpawn(spawn("2", "B"));
    assert.deepEqual(log.warns, ["join de A : erreur 401 CLE_INVALIDE"]);
    assert.deepEqual(reseau.log.warns, ["join de B : réseau : coupé"]);
  });
});

describe("départ", () => {
  it("leave efface l'état et appelle le service", async () => {
    const { handlers, store, calls } = setup();
    await handlers.onSpawn(spawn("42", "Alex"));
    await handlers.onLeave(leave("42", "Alex"));
    assert.deepEqual(calls, [`join ${X("Alex")} Alex`, `leave ${X("Alex")}`]);
    assert.equal(store.size, 0);
  });

  it("un échec de leave est journalisé une fois", async () => {
    const { handlers, log } = setup({ leave: async () => ({ kind: "error", status: 500, reason: "ERREUR_INTERNE" }) });
    await handlers.onLeave(leave("42", "Alex"));
    assert.deepEqual(log.warns, ["leave de Alex : erreur 500 ERREUR_INTERNE"]);
  });

  it("un joueur parti pendant son join n'est pas gardé, et leave passe après join", async () => {
    const ordre: string[] = [];
    let libererJoin: () => void = () => undefined;
    const attente = new Promise<void>((resolve) => {
      libererJoin = resolve;
    });
    const { handlers, store } = setup({
      join: async (xuid) => {
        ordre.push("join début");
        await attente;
        ordre.push("join fin");
        return ok(joinResponse({ xuid }));
      },
      leave: async () => {
        ordre.push("leave");
        return ok(null, 204);
      },
    });
    const arrivee = handlers.onSpawn(spawn("42", "Alex"));
    const depart = handlers.onLeave(leave("42", "Alex"));
    libererJoin();
    await Promise.all([arrivee, depart]);
    assert.deepEqual(ordre, ["join début", "join fin", "leave"]);
    assert.equal(store.size, 0);
  });

  it("deux joueurs ne se font pas attendre l'un l'autre", async () => {
    const ordre: string[] = [];
    let libererA: () => void = () => undefined;
    const attenteA = new Promise<void>((resolve) => {
      libererA = resolve;
    });
    const { handlers } = setup({
      join: async (xuid) => {
        if (xuid === X("A")) await attenteA;
        ordre.push(`join ${xuid}`);
        return ok(joinResponse({ xuid }));
      },
    });
    const a = handlers.onSpawn(spawn("A", "A"));
    await handlers.onSpawn(spawn("B", "B"));
    libererA();
    await a;
    assert.deepEqual(ordre, [`join ${X("B")}`, `join ${X("A")}`]);
  });
});

describe("registerSessionEvents", () => {
  it("abonne playerSpawn et playerLeave, et une exception du gestionnaire ne sort pas", async () => {
    const abonnes: { spawn?: (e: SpawnEventLike) => void; leave?: (e: LeaveEventLike) => void } = {};
    const world = {
      afterEvents: {
        playerSpawn: { subscribe: (cb: (e: SpawnEventLike) => void) => void (abonnes.spawn = cb) },
        playerLeave: { subscribe: (cb: (e: LeaveEventLike) => void) => void (abonnes.leave = cb) },
      },
    };
    const log = recordingLogger();
    registerSessionEvents(
      world,
      {
        onSpawn: async () => {
          throw new Error("raté");
        },
        onLeave: () => {
          throw new Error("raté aussi");
        },
      },
      log,
    );
    assert.ok(abonnes.spawn && abonnes.leave);
    abonnes.spawn(spawn("1", "A"));
    abonnes.leave(leave("1", "A"));
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual([...log.errors].sort(), ["playerLeave : Error: raté aussi", "playerSpawn : Error: raté"]);
  });
});

describe("crochets de la partie monde", () => {
  interface Joueur {
    readonly id: string;
    readonly name: string;
  }
  const joueur = (id: string, name: string): SpawnEventLike<Joueur> => ({ initialSpawn: true, player: { id, name } });

  function avecCrochets(hooks: SessionHooks<Joueur>, api: Partial<Api> = {}) {
    const store = createPlayerStore();
    const log = recordingLogger();
    const complet: Api = {
      join: api.join ?? (async (xuid) => ok(joinResponse({ xuid }))),
      leave: api.leave ?? (async () => ok(null, 204)),
    };
    const handlers = createSessionHandlers<Joueur>({ api: complet, store, log, hooks });
    return { handlers, store, log };
  }

  it("arriving passe avant la réponse de join, joined après le rangement de l'état", async () => {
    const ordre: string[] = [];
    const { handlers, store } = avecCrochets(
      {
        arriving: () => void ordre.push("arriving"),
        joined: (_joueur, join) => {
          ordre.push(`joined ${join.companyId} ${store.size}`);
        },
      },
      {
        join: async (xuid) => {
          ordre.push("join");
          return ok(joinResponse({ xuid }));
        },
      },
    );
    await handlers.onSpawn(joueur("42", "Alex"));
    assert.deepEqual(ordre, ["arriving", "join", "joined 7 1"]);
  });

  it("joined n'est pas attendu : le leave du joueur ne patiente pas derrière une arrivée de vingt secondes", async () => {
    let finirArrivee: () => void = () => undefined;
    const ordre: string[] = [];
    const { handlers } = avecCrochets(
      {
        joined: () =>
          new Promise<void>((resolve) => {
            ordre.push("joined début");
            finirArrivee = () => {
              ordre.push("joined fin");
              resolve();
            };
          }),
      },
      {
        leave: async () => {
          ordre.push("leave");
          return ok(null, 204);
        },
      },
    );
    await handlers.onSpawn(joueur("42", "Alex"));
    await handlers.onLeave(leave("42", "Alex"));
    assert.deepEqual(ordre, ["joined début", "leave"]);
    finirArrivee();
  });

  it("joinFailed : refus du service, erreur réseau, ou joueur parti avant la réponse", async () => {
    const echecs: string[] = [];
    const crochets: SessionHooks<Joueur> = {
      joinFailed: (p) => void echecs.push(p.name),
      joined: () => void echecs.push("joined à tort"),
    };
    const refus = avecCrochets(crochets, { join: async () => ({ kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" }) });
    await refus.handlers.onSpawn(joueur("1", "A"));
    const reseau = avecCrochets(crochets, { join: async () => ({ kind: "network", message: "coupé" }) });
    await reseau.handlers.onSpawn(joueur("2", "B"));

    let liberer: () => void = () => undefined;
    const attente = new Promise<void>((resolve) => {
      liberer = resolve;
    });
    const parti = avecCrochets(crochets, {
      join: async (xuid) => {
        await attente;
        return ok(joinResponse({ xuid }));
      },
    });
    const arrivee = parti.handlers.onSpawn(joueur("3", "C"));
    const depart = parti.handlers.onLeave(leave("3", "C"));
    liberer();
    await Promise.all([arrivee, depart]);

    assert.deepEqual(echecs, ["A", "B", "C"]);
  });

  it("left reçoit l'événement de départ, avant l'appel à leave", async () => {
    const ordre: string[] = [];
    const { handlers } = avecCrochets(
      { left: (event) => void ordre.push(`left ${event.playerName}`) },
      {
        leave: async () => {
          ordre.push("leave");
          return ok(null, 204);
        },
      },
    );
    await handlers.onLeave(leave("42", "Alex"));
    assert.deepEqual(ordre, ["left Alex", "leave"]);
  });

  it("une réapparition (initialSpawn faux) n'appelle aucun crochet", async () => {
    const appels: string[] = [];
    const { handlers } = avecCrochets({
      arriving: () => void appels.push("arriving"),
      joined: () => void appels.push("joined"),
      joinFailed: () => void appels.push("joinFailed"),
    });
    await handlers.onSpawn({ initialSpawn: false, player: { id: "42", name: "Alex" } });
    assert.deepEqual(appels, []);
  });

  it("une exception d'un crochet, synchrone ou d'une promesse, est journalisée et ne change rien au reste", async () => {
    const { handlers, store, log } = avecCrochets({
      arriving: () => {
        throw new Error("arriving raté");
      },
      joined: async () => {
        throw new Error("joined raté");
      },
      left: () => {
        throw new Error("left raté");
      },
    });
    await handlers.onSpawn(joueur("42", "Alex"));
    assert.equal(store.size, 1, "l'état est rangé malgré les crochets");
    await handlers.onLeave(leave("42", "Alex"));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(store.size, 0);
    assert.equal(log.errors.length, 3);
    assert.ok(log.errors.some((ligne) => ligne.includes("arriving raté")));
    assert.ok(log.errors.some((ligne) => ligne.includes("joined raté")));
    assert.ok(log.errors.some((ligne) => ligne.includes("left raté")));
    assert.ok(log.infos.some((ligne) => ligne.includes("départ de Alex")), "leave a bien été appelé");
  });

  it("sans crochets, rien ne change", async () => {
    const { handlers, store } = avecCrochets({});
    await handlers.onSpawn(joueur("42", "Alex"));
    assert.equal(store.size, 1);
  });
});

describe("combineHooks", () => {
  interface Alex {
    id: string;
    name: string;
  }
  const alex: Alex = { id: "42", name: "Alex" };

  it("appelle chaque jeu de crochets, dans l'ordre, pour les quatre moments", () => {
    const seen: string[] = [];
    const part = (nom: string): SessionHooks<Alex> => ({
      arriving: (p) => void seen.push(`${nom} arriving ${p.name}`),
      joined: (p, join) => void seen.push(`${nom} joined ${p.name} ${join.companyId}`),
      joinFailed: (p) => void seen.push(`${nom} joinFailed ${p.name}`),
      left: (e) => void seen.push(`${nom} left ${e.playerName}`),
    });
    const hooks = combineHooks(recordingLogger(), part("a"), part("b"));
    hooks.arriving?.(alex);
    void hooks.joined?.(alex, joinResponse());
    hooks.joinFailed?.(alex);
    hooks.left?.(leave("42", "Alex"));
    assert.deepEqual(seen, [
      "a arriving Alex",
      "b arriving Alex",
      "a joined Alex 7",
      "b joined Alex 7",
      "a joinFailed Alex",
      "b joinFailed Alex",
      "a left Alex",
      "b left Alex",
    ]);
  });

  it("un jeu de crochets qui n'a pas un des moments est simplement sauté", async () => {
    const seen: string[] = [];
    const hooks = combineHooks<Alex>(recordingLogger(), {}, { joined: () => void seen.push("joined") }, { left: () => void seen.push("left") });
    hooks.arriving?.(alex);
    await hooks.joined?.(alex, joinResponse());
    hooks.joinFailed?.(alex);
    hooks.left?.(leave("42", "Alex"));
    assert.deepEqual(seen, ["joined", "left"]);
  });

  it("joined se résout quand tous les crochets ont fini, et ils tournent ensemble", async () => {
    const order: string[] = [];
    let finishFirst: () => void = () => undefined;
    const first: SessionHooks<Alex> = {
      joined: () =>
        new Promise<void>((resolve) => {
          order.push("first start");
          finishFirst = () => {
            order.push("first end");
            resolve();
          };
        }),
    };
    const second: SessionHooks<Alex> = {
      joined: async () => {
        order.push("second start");
        order.push("second end");
      },
    };
    const hooks = combineHooks(recordingLogger(), first, second);
    let done = false;
    const joined = hooks.joined?.(alex, joinResponse());
    void Promise.resolve(joined).then(() => {
      done = true;
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(order, ["first start", "second start", "second end"]);
    assert.equal(done, false);
    finishFirst();
    await joined;
    assert.equal(done, true);
  });

  it("l'exception d'un crochet est journalisée et n'empêche pas les suivants", async () => {
    const log = recordingLogger();
    const seen: string[] = [];
    const bad: SessionHooks<Alex> = {
      arriving: () => {
        throw new Error("arriving cassé");
      },
      joined: () => {
        throw new Error("joined cassé");
      },
      joinFailed: () => {
        throw new Error("joinFailed cassé");
      },
      left: () => {
        throw new Error("left cassé");
      },
    };
    const good: SessionHooks<Alex> = {
      arriving: () => void seen.push("arriving"),
      joined: () => void seen.push("joined"),
      joinFailed: () => void seen.push("joinFailed"),
      left: () => void seen.push("left"),
    };
    const hooks = combineHooks(log, bad, good);
    hooks.arriving?.(alex);
    await hooks.joined?.(alex, joinResponse());
    hooks.joinFailed?.(alex);
    hooks.left?.(leave("42", "Alex"));
    assert.deepEqual(seen, ["arriving", "joined", "joinFailed", "left"]);
    assert.equal(log.errors.length, 4);
    assert.match(log.errors[0] ?? "", /^crochet arriving : .*arriving cassé/);
    assert.match(log.errors[1] ?? "", /^crochet joined : .*joined cassé/);
    assert.match(log.errors[2] ?? "", /^crochet joinFailed : .*joinFailed cassé/);
    assert.match(log.errors[3] ?? "", /^crochet left : .*left cassé/);
  });

  it("une promesse rejetée est journalisée aussi, et joined ne rejette jamais", async () => {
    const log = recordingLogger();
    const seen: string[] = [];
    const hooks = combineHooks<Alex>(
      log,
      { joined: () => Promise.reject(new Error("cinématique cassée")) },
      { joined: async () => void seen.push("missions lues") },
    );
    await hooks.joined?.(alex, joinResponse());
    assert.deepEqual(seen, ["missions lues"]);
    assert.equal(log.errors.length, 1);
    assert.match(log.errors[0] ?? "", /^crochet joined : .*cinématique cassée/);
  });

  it("branché sur la session : l'arrivée appelle les crochets de chaque partie, le joueur est déjà rangé", async () => {
    const calls: string[] = [];
    const log = recordingLogger();
    const store = createPlayerStore();
    const seenInStore: Array<boolean> = [];
    const hooks = combineHooks<{ id: string; name: string }>(
      log,
      { joined: () => void calls.push("monde") },
      { joined: (p) => void seenInStore.push(store.get(X(p.name)) !== undefined) },
    );
    const handlers = createSessionHandlers({
      api: { join: async (xuid) => ok(joinResponse({ xuid })), leave: async () => ok(null, 204) },
      store,
      log,
      hooks,
    });
    await handlers.onSpawn(spawn("42", "Alex"));
    assert.deepEqual(calls, ["monde"]);
    assert.deepEqual(seenInStore, [true]);
  });
});
