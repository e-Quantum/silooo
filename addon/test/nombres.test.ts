import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  formatAmount,
  formatCount,
  formatDuration,
  formatMultiplier,
  formatRate,
  GROUP_SPACE,
  suffixOf,
} from "../src/ui/nombres";

/** Écrit les attendus avec une espace simple ; la fonction met l'espace insécable. */
function nb(text: string): string {
  return text.replaceAll(" ", GROUP_SPACE);
}

describe("suffixOf", () => {
  it("k, M, Md, puis deux lettres tous les ×1000", () => {
    assert.deepEqual([0, 1, 2, 3, 4, 5].map(suffixOf), ["", "k", "M", "Md", "aa", "ab"]);
  });

  it("passe de az à ba, et finit à zz", () => {
    assert.equal(suffixOf(29), "az");
    assert.equal(suffixOf(30), "ba");
    assert.equal(suffixOf(679), "zz");
    assert.equal(suffixOf(5000), "zz");
  });

  it("arrive à « du » vers 1e306, la limite du double annoncée par economie.json", () => {
    assert.equal(suffixOf(102), "du");
    assert.equal(formatAmount(1e306), nb("1 du"));
  });

  it("un rang illisible n'a pas de suffixe", () => {
    assert.equal(suffixOf(-1), "");
    assert.equal(suffixOf(1.5), "");
    assert.equal(suffixOf(Number.NaN), "");
  });
});

describe("formatAmount", () => {
  it("écrit les exemples du design system", () => {
    assert.equal(formatAmount(84.3e9), nb("84,3 Md"));
    assert.equal(formatAmount(1.2e12), nb("1,2 aa"));
    assert.equal(formatAmount(512_000), nb("512 k"));
    assert.equal(formatAmount(240_000), nb("240 k"));
  });

  it("garde trois chiffres significatifs et retire les zéros inutiles", () => {
    assert.equal(formatAmount(1234), nb("1,23 k"));
    assert.equal(formatAmount(1200), nb("1,2 k"));
    assert.equal(formatAmount(1000), nb("1 k"));
    assert.equal(formatAmount(12_345), nb("12,3 k"));
    assert.equal(formatAmount(123_456), nb("123 k"));
  });

  it("n'ajoute pas de suffixe sous 1 000", () => {
    assert.equal(formatAmount(0), "0");
    assert.equal(formatAmount(5), "5");
    assert.equal(formatAmount(12), "12");
    assert.equal(formatAmount(100), "100");
    assert.equal(formatAmount(999), "999");
    assert.equal(formatAmount(12.5), "12,5");
    assert.equal(formatAmount(0.67), "0,67");
  });

  it("passe au cran suivant quand l'arrondi atteint 1 000", () => {
    assert.equal(formatAmount(999.5), nb("1 k"));
    assert.equal(formatAmount(999_499), nb("999 k"));
    assert.equal(formatAmount(999_500), nb("1 M"));
    assert.equal(formatAmount(999.9e12), nb("1 ab"));
  });

  it("change de lettre à chaque ×1000", () => {
    assert.equal(formatAmount(1e6), nb("1 M"));
    assert.equal(formatAmount(1e9), nb("1 Md"));
    assert.equal(formatAmount(1e12), nb("1 aa"));
    assert.equal(formatAmount(1e15), nb("1 ab"));
    assert.equal(formatAmount(3e29), nb("300 af"));
  });

  it("écrit 0 pour une fraction illisible ou une valeur sans sens", () => {
    assert.equal(formatAmount(0.004), "0");
    assert.equal(formatAmount(Number.NaN), "0");
    assert.equal(formatAmount(Number.POSITIVE_INFINITY), "0");
    assert.equal(formatAmount(Number.NEGATIVE_INFINITY), "0");
  });

  it("garde le signe d'un montant négatif", () => {
    assert.equal(formatAmount(-1234), nb("-1,23 k"));
    assert.equal(formatAmount(-5), "-5");
  });

  it("n'utilise jamais la notation scientifique", () => {
    for (const value of [1e-2, 0.5, 7, 1e3, 1e21, 1e100, 1.7e308]) {
      assert.doesNotMatch(formatAmount(value), /e[+-]?\d/i, String(value));
    }
  });
});

describe("formatCount", () => {
  it("sépare les milliers", () => {
    assert.equal(formatCount(12_400), nb("12 400"));
    assert.equal(formatCount(1_000_000), nb("1 000 000"));
    assert.equal(formatCount(999), "999");
    assert.equal(formatCount(0), "0");
  });

  it("tronque les décimales et garde le signe", () => {
    assert.equal(formatCount(12.9), "12");
    assert.equal(formatCount(-5), "-5");
    assert.equal(formatCount(-0.4), "0");
  });

  it("passe en notation compacte au-delà de ce qu'un entier écrit proprement", () => {
    assert.equal(formatCount(1e21), nb("1 ad"));
  });

  it("une valeur illisible s'écrit 0", () => {
    assert.equal(formatCount(Number.NaN), "0");
    assert.equal(formatCount(Number.POSITIVE_INFINITY), "0");
  });
});

describe("formatRate", () => {
  it("passe du revenu par heure du service au revenu par seconde", () => {
    assert.equal(formatRate(3600), "1/s");
    assert.equal(formatRate(14_400), "4/s");
    assert.equal(formatRate(2400), "0,667/s");
    assert.equal(formatRate(0), "0/s");
  });

  it("utilise les suffixes pour les grands revenus", () => {
    assert.equal(formatRate(3600 * 4200), nb("4,2 k/s"));
  });
});

describe("formatDuration", () => {
  it("écrit heures et minutes", () => {
    assert.equal(formatDuration(8), "8 h");
    assert.equal(formatDuration(2.5), "2 h 30");
    assert.equal(formatDuration(0.25), "15 min");
    assert.equal(formatDuration(1 + 1 / 60), "1 h 01");
  });

  it("une durée nulle, négative ou illisible s'écrit 0 min", () => {
    for (const hours of [0, -3, Number.NaN, Number.POSITIVE_INFINITY, 0.001]) {
      assert.equal(formatDuration(hours), "0 min", String(hours));
    }
  });
});

describe("formatMultiplier", () => {
  it("écrit ×2, ×1,5 et les grands multiplicateurs", () => {
    assert.equal(formatMultiplier(2), "×2");
    assert.equal(formatMultiplier(1.5), "×1,5");
    assert.equal(formatMultiplier(300), "×300");
    assert.equal(formatMultiplier(1500), `×${nb("1,5 k")}`);
  });
});
