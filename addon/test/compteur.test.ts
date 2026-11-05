import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { anchorOf, counterValue, MS_PER_HOUR } from "../src/ui/compteur";
import { companyState } from "./helpers";

describe("counterValue", () => {
  const anchor = { donar: 1000, ratePerHour: 3600, atMs: 10_000 };

  it("part du donar du service à l'instant de la réponse", () => {
    assert.equal(counterValue(anchor, 10_000), 1000);
  });

  it("ajoute le revenu par heure au prorata du temps écoulé", () => {
    assert.equal(counterValue(anchor, 10_000 + 1000), 1001);
    assert.equal(counterValue(anchor, 10_000 + 10_000), 1010);
    assert.equal(counterValue(anchor, 10_000 + MS_PER_HOUR), 4600);
  });

  it("avance d'un pas fin entre deux secondes", () => {
    assert.equal(counterValue(anchor, 10_500), 1000.5);
  });

  it("ne descend jamais sous le donar du service quand l'horloge recule", () => {
    assert.equal(counterValue(anchor, 5000), 1000);
  });

  it("n'avance pas avec un revenu nul, négatif ou illisible", () => {
    for (const ratePerHour of [0, -50, Number.NaN, Number.POSITIVE_INFINITY * -1]) {
      assert.equal(counterValue({ ...anchor, ratePerHour }, 99_999), 1000, String(ratePerHour));
    }
  });

  it("rend le donar de départ si l'instant est illisible", () => {
    assert.equal(counterValue(anchor, Number.NaN), 1000);
    assert.equal(counterValue(anchor, Number.POSITIVE_INFINITY), 1000);
  });

  it("garde la précision sur de grands nombres", () => {
    const big = { donar: 1e30, ratePerHour: 3.6e33, atMs: 0 };
    assert.equal(counterValue(big, 1000), 1e30 + 1e30);
  });
});

describe("anchorOf", () => {
  it("prend donar et ratePerHour de l'état du service", () => {
    const state = companyState({ donar: 42.5, ratePerHour: 7200 });
    assert.deepEqual(anchorOf(state, 123), { donar: 42.5, ratePerHour: 7200, atMs: 123 });
  });

  it("se recale à chaque réponse : le nouvel ancrage remplace l'ancien", () => {
    const first = anchorOf(companyState({ donar: 100, ratePerHour: 3600 }), 0);
    assert.equal(counterValue(first, 10_000), 110);
    // Le service répond à 10 s avec un donar de 500 (un achat est passé par là) : le compteur repart de 500.
    const second = anchorOf(companyState({ donar: 500, ratePerHour: 7200 }), 10_000);
    assert.equal(counterValue(second, 10_000), 500);
    assert.equal(counterValue(second, 11_000), 502);
  });
});
