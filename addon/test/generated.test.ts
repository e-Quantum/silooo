// Les types générés suivent definitions/. La vérification de fond est celle du générateur
// (node tools/generer_types.mjs --verifier, lancée par tools/verifier.sh) ; ici on contrôle que
// le fichier a bien la forme que le reste de l'add-on attend, et que tsc le tient (npm run check).

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import type { FactoryId, UsinesDefinitions } from "../src/generated/definitions";

// npm lance les tests depuis addon/ ; tester.mjs fixe le dossier courant.
const usines = JSON.parse(readFileSync(resolve("..", "definitions", "usines.json"), "utf8")) as UsinesDefinitions;
const source = readFileSync(resolve("src", "generated", "definitions.ts"), "utf8");

function accepter(id: FactoryId): FactoryId {
  return id;
}

describe("types générés", () => {
  it("chaque usine de definitions/usines.json a son identifiant dans FactoryId", () => {
    assert.ok(usines.usines.length > 0);
    for (const usine of usines.usines) {
      assert.ok(source.includes(`| ${JSON.stringify(usine.id)}`), `${usine.id} absent de FactoryId`);
      assert.equal(accepter(usine.id), usine.id);
    }
    // @ts-expect-error seul un identifiant de definitions/usines.json est un FactoryId
    accepter("usine-qui-n-existe-pas");
  });

  it("le fichier se déclare généré et n'exporte que des types", () => {
    assert.match(source, /généré par tools\/generer_types\.mjs.*ne pas éditer/);
    assert.doesNotMatch(source, /^export (?!type )/m);
  });
});
