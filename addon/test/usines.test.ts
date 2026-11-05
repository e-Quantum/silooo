import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { canBuyLevels, factoryStatus, nextRestoreStep } from "../src/ui/usines";
import { factory } from "./helpers";

describe("factoryStatus", () => {
  it("une ruine et une remise en état passent avant tout le reste", () => {
    assert.equal(factoryStatus(factory({ state: "EN_RUINE", incidentId: 3, stoppedNoPower: true })), "EN_RUINE");
    assert.equal(factoryStatus(factory({ state: "REMISE_EN_ETAT", incidentId: 3 })), "REMISE_EN_ETAT");
  });

  it("une usine active avec un incident, ou sans courant, ou rien", () => {
    assert.equal(factoryStatus(factory({ incidentId: 12 })), "INCIDENT");
    assert.equal(factoryStatus(factory({ incidentId: 12, stoppedNoPower: true })), "INCIDENT");
    assert.equal(factoryStatus(factory({ stoppedNoPower: true })), "SANS_COURANT");
    assert.equal(factoryStatus(factory({ incidentId: null, stoppedNoPower: false })), "ACTIVE");
    assert.equal(factoryStatus(factory()), "ACTIVE");
  });

  it("un incident numéro 0 est quand même un incident", () => {
    assert.equal(factoryStatus(factory({ incidentId: 0 })), "INCIDENT");
  });
});

describe("canBuyLevels", () => {
  it("seule une usine active se monte", () => {
    assert.equal(canBuyLevels(factory()), true);
    assert.equal(canBuyLevels(factory({ incidentId: 1 })), true);
    assert.equal(canBuyLevels(factory({ state: "EN_RUINE" })), false);
    assert.equal(canBuyLevels(factory({ state: "REMISE_EN_ETAT" })), false);
  });
});

describe("nextRestoreStep", () => {
  it("une ruine commence à l'étape 1", () => {
    assert.equal(nextRestoreStep(factory({ state: "EN_RUINE" })), 1);
    assert.equal(nextRestoreStep(factory({ state: "EN_RUINE", restoreStep: 3 })), 1);
  });

  it("une remise en état demande l'étape qui suit la dernière faite", () => {
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT", restoreStep: 1 })), 2);
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT", restoreStep: 3 })), 4);
  });

  it("sans numéro d'étape connu, on repart de la première ; un numéro absurde ne sort jamais de 1..4", () => {
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT" })), 1);
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT", restoreStep: 1.5 })), 1);
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT", restoreStep: -4 })), 1);
  });

  it("quatre étapes faites ou usine active : rien à demander", () => {
    assert.equal(nextRestoreStep(factory({ state: "REMISE_EN_ETAT", restoreStep: 4 })), undefined);
    assert.equal(nextRestoreStep(factory({ state: "ACTIVE" })), undefined);
  });
});
