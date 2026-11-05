import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  consoleLogger,
  createStatusLog,
  createSystemScheduler,
  describeError,
  guard,
  prefixLogger,
  secondsToTicks,
} from "../src/runtime";
import { recordingLogger } from "./helpers";

describe("secondsToTicks", () => {
  it("20 ticks par seconde, arrondi, au moins un tick", () => {
    assert.equal(secondsToTicks(10), 200);
    assert.equal(secondsToTicks(0.25), 5);
    assert.equal(secondsToTicks(0), 1);
    assert.equal(secondsToTicks(-3), 1);
    assert.equal(secondsToTicks(Number.NaN), 1);
    assert.equal(secondsToTicks(1, 4), 4);
  });
});

describe("createSystemScheduler", () => {
  it("convertit les secondes en ticks pour runInterval et runTimeout", () => {
    const appels: string[] = [];
    const scheduler = createSystemScheduler({
      runInterval: (_cb, ticks) => (appels.push(`interval ${ticks}`), 1),
      runTimeout: (_cb, ticks) => (appels.push(`timeout ${ticks}`), 2),
    });
    scheduler.every(10, () => undefined);
    scheduler.after(1.5, () => undefined);
    assert.deepEqual(appels, ["interval 200", "timeout 30"]);
  });
});

describe("createStatusLog", () => {
  it("journalise le premier échec, un changement de cause, puis le retour", () => {
    const log = recordingLogger();
    const status = createStatusLog(log, "tâche");
    status.ok();
    status.failed("A");
    status.failed("A");
    status.failed("B");
    status.ok();
    status.ok();
    status.failed("A");
    assert.deepEqual(log.warns, ["tâche : échec (A)", "tâche : échec (B)", "tâche : échec (A)"]);
    assert.deepEqual(log.infos, ["tâche : rétabli après 3 échec(s)"]);
  });
});

describe("guard", () => {
  it("attrape une exception synchrone", () => {
    const log = recordingLogger();
    guard(log, "x", () => {
      throw new Error("a");
    })();
    assert.deepEqual(log.errors, ["x : Error: a"]);
  });

  it("attrape le rejet d'une promesse", async () => {
    const log = recordingLogger();
    guard(log, "x", async () => {
      throw new Error("b");
    })();
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(log.errors, ["x : Error: b"]);
  });

  it("transmet les arguments", () => {
    const vus: number[] = [];
    guard(recordingLogger(), "x", (n: number) => void vus.push(n))(5);
    assert.deepEqual(vus, [5]);
  });
});

describe("journal", () => {
  it("prefixLogger préfixe les trois niveaux", () => {
    const lignes: string[] = [];
    const log = prefixLogger(
      { info: (m) => void lignes.push(`i ${m}`), warn: (m) => void lignes.push(`w ${m}`), error: (m) => void lignes.push(`e ${m}`) },
      "[p]",
    );
    log.info("a");
    log.warn("b");
    log.error("c");
    assert.deepEqual(lignes, ["i [p] a", "w [p] b", "e [p] c"]);
  });

  it("consoleLogger écrit dans console avec le préfixe de config", () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, "console");
    const lignes: string[] = [];
    Object.defineProperty(globalThis, "console", {
      configurable: true,
      value: { log: (m: string) => lignes.push(`log ${m}`), warn: (m: string) => lignes.push(`warn ${m}`), error: (m: string) => lignes.push(`error ${m}`) },
    });
    try {
      const log = consoleLogger();
      log.info("a");
      log.warn("b");
      log.error("c");
    } finally {
      if (original) Object.defineProperty(globalThis, "console", original);
    }
    assert.deepEqual(lignes, ["log [silooo] a", "warn [silooo] b", "error [silooo] c"]);
  });

  it("describeError donne une ligne", () => {
    assert.equal(describeError(new TypeError("mauvais")), "TypeError: mauvais");
    assert.equal(describeError("texte"), "texte");
  });
});
