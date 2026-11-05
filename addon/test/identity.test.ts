import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { leavingXuid, playerXuid, provisionalXuid } from "../src/identity";

// Le schéma Xuid du contrat (api/silooo-core.openapi.yaml) et la colonne players.xuid (BIGINT UNSIGNED).
const XUID = /^[0-9]{1,20}$/;
const MAX_UNSIGNED_64 = (1n << 64n) - 1n;

describe("identité provisoire", () => {
  it("passe le schéma Xuid du contrat et tient dans un BIGINT UNSIGNED", () => {
    for (const gamertag of ["Alex", "Steve", "Kr", "Un Gamertag Long 1234", "ñandú", "名前"]) {
      const xuid = provisionalXuid(gamertag);
      assert.match(xuid, XUID);
      assert.ok(BigInt(xuid) <= MAX_UNSIGNED_64);
    }
  });

  it("est stable, insensible à la casse et aux espaces autour", () => {
    assert.equal(provisionalXuid("Alex"), provisionalXuid("Alex"));
    assert.equal(provisionalXuid("Alex"), provisionalXuid(" alex "));
    assert.equal(provisionalXuid("ALEX"), provisionalXuid("alex"));
  });

  it("distingue deux gamertags, même proches", () => {
    assert.notEqual(provisionalXuid("Alex"), provisionalXuid("Alex1"));
    assert.notEqual(provisionalXuid("Alex"), provisionalXuid("Alexa"));
    assert.notEqual(provisionalXuid("ab"), provisionalXuid("ba"));
  });

  it("le gamertag vide donne la base de FNV-1a 64 bits", () => {
    assert.equal(provisionalXuid(""), "14695981039346656037");
  });

  it("le joueur et l'événement de départ donnent le même identifiant", () => {
    assert.equal(playerXuid({ name: "Alex" }), leavingXuid({ playerName: "Alex" }));
  });
});
