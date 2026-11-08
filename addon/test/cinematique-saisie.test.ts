import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";

// bedrock.ts tire @minecraft/server, que node ne charge pas : on lit la liste dans la source.
const source = readFileSync(join(process.cwd(), "src/monde/bedrock.ts"), "utf8");
const liste = /const LOCKED_INPUT: InputPermissionCategory\[\] = \[([^\]]*)\]/.exec(source)?.[1] ?? "";
const categories = [...liste.matchAll(/InputPermissionCategory\.(\w+)/g)].map((m) => m[1]);

describe("saisie figée pendant la cinématique", () => {
  it("laisse l'accroupissement libre, le geste pour passer", () => {
    assert.ok(categories.length > 0, "LOCKED_INPUT se lit dans bedrock.ts");
    assert.ok(!categories.includes("Sneak"), "Sneak n'est pas figé");
    assert.ok(!categories.includes("Movement"), "Movement englobe Sneak : le figer empêche de passer");
  });
  it("fige quand même la marche et le saut", () => {
    for (const attendue of ["LateralMovement", "Jump"]) assert.ok(categories.includes(attendue), `${attendue} est figé`);
  });
});
