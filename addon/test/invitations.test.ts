import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { GameEvent, Xuid } from "../src/protocol";
import { createInvitations, invitationText } from "../src/ui/invitations";
import { ASSOCIES_TEXTES } from "../src/ui/textes";
import { joinResponse, recordingLogger } from "./helpers";
import { GAMERTAG, T0, XUID, bare, fakeClock, invitationEvent, storeWith } from "./ui-helpers";

const HORS_LIGNE: Xuid = "777";

function setup() {
  const clock = fakeClock(T0);
  const store = storeWith();
  const said: Array<{ xuid: Xuid; text: string }> = [];
  const log = recordingLogger();
  const invitations = createInvitations({ clock, players: store, say: (xuid, text) => void said.push({ xuid, text }), log });
  return { invitations, clock, store, said, log };
}

const annonce = (id: number, xuid: Xuid | null, action: string): GameEvent => ({
  id,
  xuid,
  kind: "ANNONCE",
  payload: { action, companyId: 7 },
});

describe("une invitation reçue", () => {
  it("est gardée telle que le service l'a envoyée, et dite au joueur dans le tchat", () => {
    const { invitations, said } = setup();
    invitations.onInvitation(invitationEvent(1, XUID));
    assert.deepEqual(invitations.list(XUID), [
      {
        companyId: 12,
        companyName: "Les Forges",
        invitedBy: "2535428000000002",
        invitedByName: "Bea",
        expiresAtMs: Date.parse("2026-11-06T12:00:00Z"),
      },
    ]);
    assert.equal(said.length, 1);
    assert.equal(said[0]?.xuid, XUID);
    assert.equal(bare(said[0]?.text ?? ""), "Oooscar [joie] Tu as une invitation.\nBea t'invite dans Les Forges");
  });

  it("find retrouve l'invitation d'une entreprise, et seulement celle-là", () => {
    const { invitations } = setup();
    invitations.onInvitation(invitationEvent(1, XUID));
    assert.equal(invitations.find(XUID, 12)?.companyName, "Les Forges");
    assert.equal(invitations.find(XUID, 13), undefined);
    assert.equal(invitations.find("999", 12), undefined);
  });

  it("une seule invitation par entreprise : la nouvelle remplace l'ancienne et passe en dernier", () => {
    const { invitations } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12, companyName: "Les Forges" }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13, companyName: "Mines Unies", invitedByName: "Cal" }));
    invitations.onInvitation(invitationEvent(3, XUID, { companyId: 12, companyName: "Forges et Fils", expiresAt: "2026-11-07T00:00:00Z" }));
    assert.deepEqual(
      invitations.list(XUID).map((invitation) => [invitation.companyId, invitation.companyName]),
      [
        [13, "Mines Unies"],
        [12, "Forges et Fils"],
      ],
    );
    assert.equal(invitations.find(XUID, 12)?.expiresAtMs, Date.parse("2026-11-07T00:00:00Z"));
  });

  it("deux entreprises qui invitent : les deux attendent, de la plus ancienne à la plus récente", () => {
    const { invitations, said } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12 }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13, companyName: "Mines Unies", invitedByName: "Cal" }));
    assert.deepEqual(
      invitations.list(XUID).map((invitation) => invitation.companyId),
      [12, 13],
    );
    assert.equal(said.length, 2);
  });

  it("ne vaut que pour l'invité : celle d'un autre joueur n'apparaît pas", () => {
    const { invitations } = setup();
    invitations.onInvitation(invitationEvent(1, HORS_LIGNE));
    assert.deepEqual(invitations.list(XUID), []);
    assert.equal(invitations.list(HORS_LIGNE).length, 1);
  });

  it("expire à expiresAt : l'horloge la retire de la liste et de find", () => {
    const { invitations, clock } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { expiresAt: "2026-11-05T13:00:00Z" }));
    clock.advance(59 * 60_000);
    assert.equal(invitations.list(XUID).length, 1);
    clock.advance(60_000);
    assert.deepEqual(invitations.list(XUID), []);
    assert.equal(invitations.find(XUID, 12), undefined);
  });

  it("une expirée ne retire pas celle qui dure encore", () => {
    const { invitations, clock } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12, expiresAt: "2026-11-05T13:00:00Z" }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13, expiresAt: "2026-11-06T13:00:00Z" }));
    clock.advance(2 * 3_600_000);
    assert.deepEqual(
      invitations.list(XUID).map((invitation) => invitation.companyId),
      [13],
    );
  });

  it("déjà expirée à l'arrivée de l'événement (rejeu après un redémarrage) : ni gardée ni dite", () => {
    const { invitations, said } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { expiresAt: "2026-11-05T11:00:00Z" }));
    invitations.onInvitation(invitationEvent(2, XUID, { expiresAt: "2026-11-05T12:00:00Z" }));
    assert.deepEqual(invitations.list(XUID), []);
    assert.deepEqual(said, []);
  });

  it("remove : le joueur a répondu, ou le service ne la connaît plus", () => {
    const { invitations } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12 }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13 }));
    invitations.remove(XUID, 12);
    assert.deepEqual(
      invitations.list(XUID).map((invitation) => invitation.companyId),
      [13],
    );
    invitations.remove(XUID, 13);
    invitations.remove(XUID, 13);
    invitations.remove("999", 1);
    assert.deepEqual(invitations.list(XUID), []);
  });

  it("clear : entrer dans une entreprise périme toutes les autres invitations du joueur", () => {
    const { invitations } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12 }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13 }));
    invitations.onInvitation(invitationEvent(3, HORS_LIGNE, { companyId: 12 }));
    invitations.clear(XUID);
    assert.deepEqual(invitations.list(XUID), []);
    assert.equal(invitations.list(HORS_LIGNE).length, 1, "celles des autres joueurs restent");
  });
});

describe("une invitation illisible ou qui n'est pas pour un joueur", () => {
  it("un payload auquel il manque une clé ou qui a un mauvais type est ignoré et journalisé", () => {
    const { invitations, said, log } = setup();
    const abimes: Array<Record<string, unknown>> = [
      { companyId: undefined },
      { companyId: "12" },
      { companyId: 0 },
      { companyId: 1.5 },
      { companyName: "" },
      { companyName: 7 },
      { invitedBy: undefined },
      { invitedByName: "" },
      { invitedByName: undefined },
      { expiresAt: undefined },
      { expiresAt: 12 },
      { expiresAt: "bientôt" },
    ];
    abimes.forEach((payload, index) => invitations.onInvitation(invitationEvent(index + 1, XUID, payload)));
    assert.deepEqual(invitations.list(XUID), []);
    assert.deepEqual(said, []);
    assert.equal(log.warns.length, abimes.length);
    assert.match(log.warns[0] ?? "", /illisible/);
  });

  it("un événement sans destinataire ou d'un autre genre est ignoré, sans bruit", () => {
    const { invitations, said, log } = setup();
    invitations.onInvitation(invitationEvent(1, null));
    invitations.onInvitation({ ...invitationEvent(2, XUID), kind: "ANNONCE" });
    assert.deepEqual(invitations.list(XUID), []);
    assert.deepEqual(said, []);
    assert.deepEqual(log.all(), []);
  });

  it("un tchat qui échoue est journalisé, l'invitation est quand même gardée", () => {
    const clock = fakeClock(T0);
    const log = recordingLogger();
    const invitations = createInvitations({
      clock,
      players: storeWith(),
      say: () => {
        throw new Error("tchat fermé");
      },
      log,
    });
    invitations.onInvitation(invitationEvent(1, XUID));
    assert.equal(invitations.list(XUID).length, 1);
    assert.match(log.warns.join(" "), /tchat fermé/);
  });
});

describe("un invité hors ligne", () => {
  it("garde l'invitation sans rien dire ; à son arrivée, elle lui est dite une seule fois", () => {
    const { invitations, store, said } = setup();
    invitations.onInvitation(invitationEvent(1, HORS_LIGNE));
    assert.equal(said.length, 0);
    assert.equal(invitations.list(HORS_LIGNE).length, 1);

    store.set({ xuid: HORS_LIGNE, gamertag: "Cal", join: joinResponse() });
    invitations.arrive(HORS_LIGNE);
    assert.equal(said.length, 1);
    assert.equal(said[0]?.xuid, HORS_LIGNE);
    assert.match(bare(said[0]?.text ?? ""), /Tu as une invitation\.\nBea t'invite dans Les Forges$/);

    invitations.arrive(HORS_LIGNE);
    assert.equal(said.length, 1, "pas deux fois");
  });

  it("à l'arrivée, une invitation déjà dite en ligne n'est pas répétée, une expirée n'est pas dite", () => {
    const { invitations, clock, said } = setup();
    invitations.onInvitation(invitationEvent(1, XUID, { companyId: 12 }));
    invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13, expiresAt: "2026-11-05T13:00:00Z" }));
    assert.equal(said.length, 2);
    clock.advance(2 * 3_600_000);
    invitations.arrive(XUID);
    assert.equal(said.length, 2);
  });

  it("arrive ne fait rien pour un joueur sans invitation", () => {
    const { invitations, said } = setup();
    invitations.arrive(XUID);
    assert.deepEqual(said, []);
  });
});

describe("l'annonce d'un changement de parts", () => {
  it("dit « Les parts ont changé » à celui qui a agi", () => {
    const { invitations, said } = setup();
    invitations.onAnnonce(annonce(1, XUID, "setShares"));
    assert.equal(said.length, 1);
    assert.equal(said[0]?.xuid, XUID);
    assert.equal(bare(said[0]?.text ?? ""), "Oooscar [joie] Les parts ont changé.");
  });

  it("ne le répète pas tant que l'écran des associés n'a pas été rouvert (rejeu des événements)", () => {
    const { invitations, said } = setup();
    invitations.onAnnonce(annonce(1, XUID, "setShares"));
    invitations.onAnnonce(annonce(2, XUID, "setShares"));
    invitations.onAnnonce(annonce(3, XUID, "setShares"));
    assert.equal(said.length, 1);
    invitations.opened(XUID);
    invitations.onAnnonce(annonce(4, XUID, "setShares"));
    assert.equal(said.length, 2);
  });

  it("ne reprend que setShares : une mission, une entrée dans une entreprise ou une action inconnue sont laissées aux autres", () => {
    const { invitations, said } = setup();
    invitations.onAnnonce(annonce(1, XUID, "mission"));
    invitations.onAnnonce(annonce(2, XUID, "acceptInvite"));
    invitations.onAnnonce({ id: 3, xuid: XUID, kind: "ANNONCE", payload: {} });
    invitations.onAnnonce({ id: 4, xuid: XUID, kind: "INVITATION", payload: { action: "setShares" } });
    assert.deepEqual(said, []);
  });

  it("n'a personne à qui parler : sans destinataire ou joueur hors ligne, rien n'est dit", () => {
    const { invitations, said } = setup();
    invitations.onAnnonce(annonce(1, null, "setShares"));
    invitations.onAnnonce(annonce(2, HORS_LIGNE, "setShares"));
    assert.deepEqual(said, []);
  });
});

describe("invitationText", () => {
  it("la bulle d'Oooscar, puis qui invite dans quelle entreprise, tels que le service les a donnés", () => {
    const text = invitationText({ companyId: 3, companyName: "Atelier", invitedBy: "5", invitedByName: GAMERTAG, expiresAtMs: 0 });
    assert.equal(bare(text), `Oooscar [joie] ${ASSOCIES_TEXTES.bulles.invitationRecue.text}\nAlex t'invite dans Atelier`);
  });
});
