import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import { VOTE } from "../src/monde/definitions";
import type { GameEvent, VotesToday, Xuid } from "../src/protocol";
import type { VotesOutcome } from "../src/ui/actions";
import { createVotes, hasNotVoted, reminderLine, reminderText, votePartyText, voteSites, voteText } from "../src/ui/votes";
import { VOTE_TEXTES, renderBubble } from "../src/ui/textes";
import { ok, recordingLogger, votesToday } from "./helpers";
import { T0, bare, failure, fakeClock, unreachable } from "./ui-helpers";

const votesOf = (mine: { votes: number; streak: number }): VotesOutcome => ({ kind: "votes", votes: votesToday({ mine }) });
const FAILED: VotesOutcome = { kind: "failed", failure: "network", reason: "RESEAU" };

function annonce(xuid: Xuid | null, payload: Record<string, unknown>): GameEvent {
  return { id: 1, xuid, kind: "ANNONCE", payload };
}

describe("voteSites, hasNotVoted, reminderLine et reminderText", () => {
  it("voteSites donne le nom des sites de definitions/vote.json, séparés par une virgule, jamais un lien", () => {
    assert.equal(voteSites(), VOTE.sites.map((site) => site.nom).join(", "));
    assert.equal(voteSites([{ nom: "Hubyy" }, { nom: "Autre" }]), "Hubyy, Autre");
    assert.equal(voteSites([]), "");
    assert.doesNotMatch(voteSites(), /https?:|www\.|\./);
  });

  it("hasNotVoted : seulement quand le service dit zéro vote aujourd'hui", () => {
    assert.equal(hasNotVoted(votesOf({ votes: 0, streak: 0 })), true);
    assert.equal(hasNotVoted(votesOf({ votes: 0, streak: 4 })), true);
    assert.equal(hasNotVoted(votesOf({ votes: 1, streak: 1 })), false);
    assert.equal(hasNotVoted(FAILED), false);
    assert.equal(hasNotVoted(undefined), false);
  });

  it("reminderLine : une ligne sobre avec les sites, rien sans preuve ou sans site", () => {
    const ligne = reminderLine(votesOf({ votes: 0, streak: 0 }), "Hubyy, Autre");
    assert.equal(bare(ligne ?? ""), VOTE_TEXTES.rappel.boutique("Hubyy, Autre"));
    assert.equal(reminderLine(votesOf({ votes: 2, streak: 1 }), "Hubyy"), undefined);
    assert.equal(reminderLine(FAILED, "Hubyy"), undefined);
    assert.equal(reminderLine(undefined, "Hubyy"), undefined);
    assert.equal(reminderLine(votesOf({ votes: 0, streak: 0 }), ""), undefined);
    assert.ok(bare(reminderLine(votesOf({ votes: 0, streak: 0 })) ?? "").includes(voteSites()));
  });

  it("reminderText : la bulle d'Oooscar puis les sites ; sans site, la bulle seule", () => {
    assert.equal(bare(reminderText("Hubyy")), `${bare(renderBubble(VOTE_TEXTES.rappel.bulle))}\n${VOTE_TEXTES.rappel.ligne("Hubyy")}`);
    assert.equal(reminderText(""), renderBubble(VOTE_TEXTES.rappel.bulle));
    assert.ok(bare(reminderText()).includes(voteSites()));
  });
});

describe("voteText et votePartyText", () => {
  it("un vote : la bulle, les Lingots versés (ceux de l'événement), la série et le bonus de série", () => {
    const text = bare(voteText({ action: "vote", site: "hubyy", rewardLingots: 3, streak: 7, streakBonusLingots: 2 }));
    assert.deepEqual(text.split("\n").slice(1), [VOTE_TEXTES.vote.recompense("3"), VOTE_TEXTES.vote.serie("7"), VOTE_TEXTES.vote.bonus("2")]);
    assert.equal(text.split("\n")[0], bare(renderBubble(VOTE_TEXTES.vote.bulle)));
  });

  it("le singulier de la série et des Lingots", () => {
    const lines = bare(voteText({ rewardLingots: 1, streak: 1 })).split("\n");
    assert.deepEqual(lines.slice(1), ["+1 Lingot", "Série : 1 jour"]);
  });

  it("une valeur absente, nulle ou illisible n'est pas dite", () => {
    const bulle = renderBubble(VOTE_TEXTES.vote.bulle);
    assert.equal(voteText({ action: "vote" }), bulle);
    assert.equal(voteText({ rewardLingots: 0, streak: 0, streakBonusLingots: 0 }), bulle);
    assert.equal(voteText({ rewardLingots: "3", streak: -1, streakBonusLingots: Number.NaN }), bulle);
    assert.equal(voteText({ rewardLingots: 1.5, streak: null, streakBonusLingots: undefined }), bulle);
  });

  it("la Vote party : la bulle, puis ce que chaque joueur présent a reçu", () => {
    const lines = bare(votePartyText({ action: "voteParty", day: "2026-11-05", votes: 10, rewardLingots: 2, players: 4 })).split("\n");
    assert.equal(lines[0], bare(renderBubble(VOTE_TEXTES.voteParty.bulle)));
    assert.deepEqual(lines.slice(1), [VOTE_TEXTES.voteParty.ligne("2")]);
    assert.equal(votePartyText({ action: "voteParty" }), renderBubble(VOTE_TEXTES.voteParty.bulle));
    assert.equal(votePartyText({ rewardLingots: 0 }), renderBubble(VOTE_TEXTES.voteParty.bulle));
  });
});

describe("createVotes : l'arrivée du joueur", () => {
  function setup(reply: ApiResult<VotesToday> | Error, online: readonly Xuid[] = ["1"]) {
    const sent: Array<{ xuid: Xuid; text: string }> = [];
    const reads: Array<Xuid | undefined> = [];
    const log = recordingLogger();
    const votes = createVotes({
      api: {
        todayVotes: (xuid) => {
          reads.push(xuid);
          return reply instanceof Error ? Promise.reject(reply) : Promise.resolve(reply);
        },
      },
      online: () => online,
      say: (xuid, text) => void sent.push({ xuid, text }),
      clock: fakeClock(T0),
      log,
    });
    return { votes, sent, reads, log };
  }

  it("le joueur n'a pas voté : Oooscar le lui rappelle, avec le nom des sites", async () => {
    const { votes, sent, reads } = setup(ok(votesToday({ mine: { votes: 0, streak: 0 } })));
    await votes.arrive("1");
    assert.deepEqual(reads, ["1"]);
    assert.deepEqual(sent, [{ xuid: "1", text: reminderText() }]);
  });

  it("le joueur a déjà voté : rien", async () => {
    const { votes, sent } = setup(ok(votesToday({ mine: { votes: 1, streak: 2 } })));
    await votes.arrive("1");
    assert.deepEqual(sent, []);
  });

  it("une lecture qui échoue ne dit rien et se journalise ; une exception aussi", async () => {
    for (const reply of [unreachable<VotesToday>(), failure(500, "ERREUR_INTERNE"), new Error("coupure")]) {
      const { votes, sent, log } = setup(reply);
      await votes.arrive("1");
      assert.deepEqual(sent, []);
      assert.match(log.warns.join("\n"), /rappel du vote de 1/);
    }
  });

  it("un tchat qui lève ne fait pas lever l'arrivée", async () => {
    const log = recordingLogger();
    const votes = createVotes({
      api: { todayVotes: () => Promise.resolve(ok(votesToday())) },
      online: () => ["1"],
      say: () => {
        throw new Error("joueur parti");
      },
      clock: fakeClock(T0),
      log,
    });
    await votes.arrive("1");
    assert.match(log.warns.join("\n"), /vote, message à 1 : .*joueur parti/);
  });
});

describe("createVotes : les annonces du service", () => {
  function setup(online: readonly Xuid[], nowMs = T0) {
    const sent: Array<{ xuid: Xuid; text: string }> = [];
    const log = recordingLogger();
    const votes = createVotes({
      api: { todayVotes: () => Promise.reject(new Error("inutile")) },
      online: () => online,
      say: (xuid, text) => void sent.push({ xuid, text }),
      clock: fakeClock(nowMs),
      log,
    });
    return { votes, sent, log };
  }

  it("un vote reçu est dit au joueur qui l'a fait, s'il est présent", async () => {
    const { votes, sent } = setup(["1", "2"]);
    const payload = { action: "vote", site: "hubyy", rewardLingots: 3, streak: 7, streakBonusLingots: 2 };
    await votes.onAnnonce(annonce("1", payload));
    assert.deepEqual(sent, [{ xuid: "1", text: voteText(payload) }]);
  });

  it("un vote d'un joueur absent, ou sans joueur, ne dit rien", async () => {
    const { votes, sent } = setup(["2"]);
    await votes.onAnnonce(annonce("1", { action: "vote", rewardLingots: 1 }));
    await votes.onAnnonce(annonce(null, { action: "vote", rewardLingots: 1 }));
    assert.deepEqual(sent, []);
  });

  it("la Vote party d'aujourd'hui est dite à tous les joueurs présents", async () => {
    const { votes, sent } = setup(["1", "2", "3"]);
    const payload = { action: "voteParty", day: "2026-11-05", votes: 10, rewardLingots: 1, players: 3 };
    await votes.onAnnonce(annonce(null, payload));
    assert.deepEqual(
      sent.map((message) => message.xuid),
      ["1", "2", "3"],
    );
    assert.ok(sent.every((message) => message.text === votePartyText(payload)));
  });

  it("une Vote party d'un autre jour (relue au redémarrage du serveur) ou sans jour n'est pas une nouvelle", async () => {
    const { votes, sent } = setup(["1"]);
    await votes.onAnnonce(annonce(null, { action: "voteParty", day: "2026-11-04", rewardLingots: 1 }));
    await votes.onAnnonce(annonce(null, { action: "voteParty", rewardLingots: 1 }));
    await votes.onAnnonce(annonce(null, { action: "voteParty", day: 20261105, rewardLingots: 1 }));
    assert.deepEqual(sent, []);
  });

  it("le jour est le jour UTC : une minute avant minuit, le jour est encore le même", async () => {
    const { votes, sent } = setup(["1"], Date.parse("2026-11-05T23:59:00Z"));
    await votes.onAnnonce(annonce(null, { action: "voteParty", day: "2026-11-05", rewardLingots: 1 }));
    assert.equal(sent.length, 1);
    const apres = setup(["1"], Date.parse("2026-11-06T00:01:00Z"));
    await apres.votes.onAnnonce(annonce(null, { action: "voteParty", day: "2026-11-05", rewardLingots: 1 }));
    assert.deepEqual(apres.sent, []);
  });

  it("les autres annonces et les autres événements ne sont pas pour ce module", async () => {
    const { votes, sent } = setup(["1"]);
    await votes.onAnnonce(annonce("1", { action: "mission", rewardLingots: 1 }));
    await votes.onAnnonce(annonce("1", { action: "pionnier" }));
    await votes.onAnnonce(annonce("1", {}));
    await votes.onAnnonce({ id: 2, xuid: "1", kind: "GEMMES_RECUES", payload: { action: "vote" } });
    assert.deepEqual(sent, []);
  });

  it("un tchat qui lève pour un joueur n'empêche pas de le dire aux autres", async () => {
    const sent: Xuid[] = [];
    const log = recordingLogger();
    const votes = createVotes({
      api: { todayVotes: () => Promise.reject(new Error("inutile")) },
      online: () => ["1", "2"],
      say: (xuid) => {
        if (xuid === "1") throw new Error("joueur parti");
        sent.push(xuid);
      },
      clock: fakeClock(T0),
      log,
    });
    await votes.onAnnonce(annonce(null, { action: "voteParty", day: "2026-11-05", rewardLingots: 1 }));
    assert.deepEqual(sent, ["2"]);
    assert.match(log.warns.join("\n"), /vote, message à 1/);
  });
});
