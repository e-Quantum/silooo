// Les textes de la ville : noms des bâtiments, lignes de bonus, singulier et pluriel, repères de rotation, bulles.
// Les règles d'écriture communes (ni emoji, ni question, tutoiement, titres en capitales, six mots par bulle) et la présence
// de chaque texte dans le README sont dans textes.test.ts, qui lit aussi ces textes.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { BUILDING_ROTATIONS, CITY_BONUS_TYPES } from "../src/protocol";
import { BATIMENT_IDS, BATIMENT_NOMS, VILLE_TEXTES, batimentName, bonusLine } from "../src/ui/textes";

const mots = (texte: string): string[] => texte.split(/\s+/).filter((mot) => mot !== "");

describe("noms des bâtiments", () => {
  it("un nom par identifiant du catalogue, d'un mot, en capitale initiale, tous différents", () => {
    assert.deepEqual(Object.keys(BATIMENT_NOMS), [...BATIMENT_IDS]);
    for (const nom of Object.values(BATIMENT_NOMS)) assert.match(nom, /^[A-ZÉ][a-zéèêàç]+$/, nom);
    assert.equal(new Set(Object.values(BATIMENT_NOMS)).size, BATIMENT_IDS.length);
  });

  it("un type que l'add-on ne connaît pas encore devient un nom lisible", () => {
    assert.equal(batimentName("maison"), "Maison");
    assert.equal(batimentName("entrepot"), "Entrepot");
    assert.equal(batimentName("grand-hotel"), "Grand hotel");
    assert.equal(batimentName("constructor"), "Constructor");
    assert.equal(batimentName("toString"), "ToString");
    assert.equal(batimentName(""), "");
  });
});

describe("la ligne de bonus", () => {
  it("met en forme la valeur du service, sans rien calculer", () => {
    assert.equal(bonusLine("LOGEMENTS", 2), "Logements 2");
    assert.equal(bonusLine("LOGEMENTS", 1_200), "Logements 1 200");
    assert.equal(bonusLine("PRODUCTION_DONAR", 0.5), "Donar +0,5 %");
    assert.equal(bonusLine("PRODUCTION_DONAR", 2.5), "Donar +2,5 %");
    assert.equal(bonusLine("LIVRAISON_VITESSE", 10), "Livraison +10 %");
  });

  it("n'écrit rien pour la mairie et la route, dont le bonus est nul ou absent", () => {
    assert.equal(bonusLine(null, null), undefined);
    assert.equal(bonusLine(undefined, undefined), undefined);
    assert.equal(bonusLine("LOGEMENTS", null), undefined);
    assert.equal(bonusLine(null, 3), undefined);
  });

  it("a une ligne par sorte de bonus du contrat, et rien d'autre", () => {
    assert.deepEqual(Object.keys(VILLE_TEXTES.bonus.types).sort(), [...CITY_BONUS_TYPES].sort());
  });
});

describe("singulier et pluriel", () => {
  it("un bâtiment, des bâtiments", () => {
    assert.equal(VILLE_TEXTES.batiments.nombre("1"), "1 bâtiment");
    assert.equal(VILLE_TEXTES.batiments.nombre("2"), "2 bâtiments");
    assert.equal(VILLE_TEXTES.batiments.nombre("0"), "0 bâtiments");
  });

  it("une usine active, des usines actives, sur la tuile et sur la zone", () => {
    assert.equal(VILLE_TEXTES.tuile.verrouillee("1"), "Il faut 1 usine active.");
    assert.equal(VILLE_TEXTES.tuile.verrouillee("2"), "Il faut 2 usines actives.");
    assert.equal(VILLE_TEXTES.tuile.premiere("1"), "Première zone : 1 usine active");
    assert.equal(VILLE_TEXTES.tuile.premiere("2"), "Première zone : 2 usines actives");
    assert.equal(VILLE_TEXTES.zone.usinesRequises("1"), "1 usine active requise");
    assert.equal(VILLE_TEXTES.zone.usinesRequises("3"), "3 usines actives requises");
  });
});

describe("les repères de pose", () => {
  it("la porte et le sens de la route ont un texte pour chaque rotation du contrat", () => {
    assert.deepEqual(Object.keys(VILLE_TEXTES.poser.porte).map(Number).sort((a, b) => a - b), [...BUILDING_ROTATIONS]);
    assert.deepEqual(Object.keys(VILLE_TEXTES.poser.sens).map(Number).sort((a, b) => a - b), [...BUILDING_ROTATIONS]);
  });

  it("une rotation et son opposé disent le même sens de route, et deux portes opposées", () => {
    assert.equal(VILLE_TEXTES.poser.sens[0], VILLE_TEXTES.poser.sens[180]);
    assert.equal(VILLE_TEXTES.poser.sens[90], VILLE_TEXTES.poser.sens[270]);
    assert.notEqual(VILLE_TEXTES.poser.sens[0], VILLE_TEXTES.poser.sens[90]);
    assert.equal(new Set(Object.values(VILLE_TEXTES.poser.porte)).size, 4);
  });

  it("la parcelle se lit « Parcelle x, z » avec les coordonnées du service", () => {
    assert.equal(VILLE_TEXTES.poser.parcelle("192", "208"), "Parcelle 192, 208");
  });
});

describe("les mots de la ville", () => {
  it("les boutons sont courts et sans ponctuation finale", () => {
    const boutons = [
      VILLE_TEXTES.retour,
      VILLE_TEXTES.batiments.bouton,
      VILLE_TEXTES.fiche.ameliorer,
      VILLE_TEXTES.construire.bouton,
      VILLE_TEXTES.poser.poser,
      VILLE_TEXTES.poser.tourner,
      VILLE_TEXTES.zone.acheter("2"),
    ];
    for (const bouton of boutons) {
      assert.ok(mots(bouton).length >= 1 && mots(bouton).length <= 4, bouton);
      assert.doesNotMatch(bouton, /[.!?]$/, bouton);
    }
  });

  it("les bulles de réussite sont des « joie », les refus des « oups » ou des « fier » (déjà acquis)", () => {
    const { bulles } = VILLE_TEXTES;
    for (const bulle of [bulles.zoneAchetee, bulles.batimentPose, bulles.batimentAmeliore]) assert.equal(bulle.expression, "joie", bulle.text);
    for (const bulle of [bulles.pasSurLIle, bulles.positionInconnue, bulles.premiereZone, bulles.mairieBasse, bulles.parcelleFermee, bulles.emplacementOccupe, bulles.maximumAtteint]) {
      assert.equal(bulle.expression, "oups", bulle.text);
    }
    assert.equal(bulles.zoneDejaPossedee.expression, "fier");
    assert.equal(bulles.niveauMax.expression, "fier");
  });

  it("une bulle de refus dit ce qui bloque, pas le code du service", () => {
    for (const bulle of Object.values(VILLE_TEXTES.bulles)) {
      assert.doesNotMatch(bulle.text, /[A-Z]{3,}_[A-Z_]+/, bulle.text);
    }
  });
});
