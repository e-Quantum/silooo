import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { createStatusLog, secondsToTicks } from "../src/runtime";
import { COLOR } from "../src/ui/apparence";
import { anchorOf, counterValue } from "../src/ui/compteur";
import { createViewStore, type CompanyView } from "../src/ui/etat";
import {
  HUD_SUBTITLE_PREFIX,
  HUD_TITLE_PREFIX,
  createHud,
  renderHud,
  startHud,
  type HudPlayerLike,
  type HudTitleOptions,
} from "../src/ui/hud";
import { formatAmount, formatCount, formatRate } from "../src/ui/nombres";
import { HUD_TEXTES } from "../src/ui/textes";
import { balances, companyState, fakeScheduler, recordingLogger } from "./helpers";
import { GAMERTAG, bare, fakeClock, storeWith } from "./ui-helpers";

const state = companyState({ donar: 1_500, ratePerHour: 7_200, threshold: 1_000_000, version: 3 });
const view: CompanyView = {
  state,
  balances: balances({ lingots: 12, gemmes: 3_400 }),
  anchor: anchorOf(state, 0),
};

describe("renderHud, sans canal de titre", () => {
  const frame = renderHud({ view, link: "ok", nowMs: 0, titleChannel: false });

  it("tout passe par la barre d'action : les trois monnaies, puis le gain par seconde", () => {
    assert.equal(frame.title, "");
    assert.equal(frame.subtitle, "");
    const text = bare(frame.actionBar);
    assert.ok(text.includes(formatAmount(1_500)), text);
    assert.ok(text.includes(formatCount(12)), text);
    assert.ok(text.includes(formatCount(3_400)), text);
    assert.ok(text.includes(`+${formatRate(7_200)}`), text);
  });

  it("n'écrit jamais le préfixe du canal de titre", () => {
    assert.doesNotMatch(frame.actionBar, /silooo:/);
  });

  it("chaque monnaie garde sa couleur", () => {
    assert.ok(frame.actionBar.includes(COLOR.donar));
    assert.ok(frame.actionBar.includes(COLOR.lingots));
    assert.ok(frame.actionBar.includes(COLOR.gemmes));
  });
});

describe("renderHud, avec le canal de titre", () => {
  const frame = renderHud({ view, link: "ok", nowMs: 0, titleChannel: true });

  it("le titre porte les monnaies, le sous-titre la consigne, la barre d'action le gain", () => {
    assert.ok(frame.title.startsWith(HUD_TITLE_PREFIX));
    assert.ok(frame.subtitle.startsWith(HUD_SUBTITLE_PREFIX));
    assert.ok(bare(frame.title).includes(formatAmount(1_500)));
    assert.ok(bare(frame.title).includes(formatCount(3_400)));
    assert.ok(bare(frame.subtitle).includes(HUD_TEXTES.consigne(formatAmount(1_000_000))));
    assert.equal(bare(frame.actionBar), `+${formatRate(7_200)}`);
  });

  it("les deux préfixes sont distincts et finissent par une barre verticale que le pack coupe", () => {
    assert.notEqual(HUD_TITLE_PREFIX, HUD_SUBTITLE_PREFIX);
    assert.match(HUD_TITLE_PREFIX, /^silooo:[a-z]+\|$/);
    assert.match(HUD_SUBTITLE_PREFIX, /^silooo:[a-z]+\|$/);
  });
});

describe("le compteur de Donar", () => {
  it("avance avec l'horloge entre deux réponses du service, sans jamais descendre sous le dernier solde", () => {
    const at = (nowMs: number) => bare(renderHud({ view, link: "ok", nowMs, titleChannel: false }).actionBar);
    // 7 200 Donar par heure = 2 par seconde.
    assert.ok(at(0).includes(formatAmount(1_500)));
    assert.ok(at(10_000).includes(formatAmount(counterValue(view.anchor, 10_000))));
    assert.ok(at(10_000).includes(formatAmount(1_520)));
    assert.ok(at(-5_000).includes(formatAmount(1_500)));
  });
});

describe("renderHud, service injoignable", () => {
  it("se réduit à « hors ligne » : ni monnaie, ni gain, ni consigne", () => {
    for (const titleChannel of [false, true]) {
      const frame = renderHud({ view, link: "down", nowMs: 0, titleChannel });
      const all = bare(`${frame.title}${frame.subtitle}${frame.actionBar}`);
      assert.ok(all.includes(HUD_TEXTES.horsLigne), all);
      assert.doesNotMatch(all, /\d/);
      assert.ok(!all.includes("Prestige"));
    }
  });

  it("sans canal de titre, tout tient dans la barre d'action", () => {
    const frame = renderHud({ view, link: "down", nowMs: 0, titleChannel: false });
    assert.deepEqual([frame.title, frame.subtitle], ["", ""]);
    assert.ok(bare(frame.actionBar).includes(HUD_TEXTES.horsLigne));
  });

  it("avec le canal, la barre d'action est vide et les préfixes restent pour que le pack ne montre rien d'autre", () => {
    const frame = renderHud({ view, link: "down", nowMs: 0, titleChannel: true });
    assert.equal(frame.actionBar, "");
    assert.ok(frame.title.startsWith(HUD_TITLE_PREFIX));
    assert.equal(frame.subtitle, HUD_SUBTITLE_PREFIX);
  });

  it("un joueur dont le service n'a rien dit est aussi hors ligne", () => {
    const frame = renderHud({ view: undefined, link: "ok", nowMs: 0, titleChannel: false });
    assert.ok(bare(frame.actionBar).includes(HUD_TEXTES.horsLigne));
  });
});

// ---------------------------------------------------------------- l'enveloppe

interface Sent {
  title?: { text: string; options: HudTitleOptions | undefined };
  actionBars: string[];
}

function fakePlayer(name: string, failing = false): { player: HudPlayerLike; sent: Sent } {
  const sent: Sent = { actionBars: [] };
  return {
    sent,
    player: {
      name,
      onScreenDisplay: {
        setTitle(text, options) {
          if (failing) throw new Error("joueur parti");
          sent.title = { text, options };
        },
        setActionBar(text) {
          if (failing) throw new Error("joueur parti");
          sent.actionBars.push(text);
        },
      },
    },
  };
}

function hudFor(players: () => HudPlayerLike[], titleChannel: boolean, link: "ok" | "down" = "ok") {
  const clock = fakeClock(0);
  const log = recordingLogger();
  const views = createViewStore({ players: storeWith({ donar: 10, ratePerHour: 3_600 }), clock });
  const hud = createHud({
    players,
    views,
    link: { status: () => link },
    clock,
    status: createStatusLog(log, "HUD"),
    titleChannel,
    titleStaySeconds: 4,
  });
  return { hud, log, clock };
}

describe("createHud", () => {
  it("envoie le titre sans fondu et assez long pour ne jamais s'éteindre, puis la barre d'action", () => {
    const { player, sent } = fakePlayer(GAMERTAG);
    const { hud } = hudFor(() => [player], true);
    hud.refresh();
    assert.ok(sent.title?.text.startsWith(HUD_TITLE_PREFIX));
    assert.deepEqual(
      { in: sent.title?.options?.fadeInDuration, out: sent.title?.options?.fadeOutDuration, stay: sent.title?.options?.stayDuration },
      { in: 0, out: 0, stay: secondsToTicks(4) },
    );
    assert.ok(sent.title?.options?.subtitle?.startsWith(HUD_SUBTITLE_PREFIX));
    assert.equal(sent.actionBars.length, 1);
  });

  it("sans canal de titre, il n'appelle jamais setTitle", () => {
    const { player, sent } = fakePlayer(GAMERTAG);
    const { hud } = hudFor(() => [player], false);
    hud.refresh();
    assert.equal(sent.title, undefined);
    assert.equal(sent.actionBars.length, 1);
  });

  it("le Donar affiché suit l'horloge d'un envoi à l'autre", () => {
    const { player, sent } = fakePlayer(GAMERTAG);
    const { hud, clock } = hudFor(() => [player], false);
    hud.refresh();
    clock.advance(100_000);
    hud.refresh();
    assert.ok(bare(sent.actionBars[0] ?? "").includes(formatAmount(10)));
    assert.ok(bare(sent.actionBars[1] ?? "").includes(formatAmount(110)));
  });

  it("un joueur en erreur n'empêche pas les autres, et l'erreur est journalisée une fois seulement", () => {
    const parti = fakePlayer("Parti", true);
    const present = fakePlayer(GAMERTAG);
    const { hud, log } = hudFor(() => [parti.player, present.player], false);
    hud.refresh();
    hud.refresh();
    assert.equal(present.sent.actionBars.length, 2);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /HUD : échec \(Error: joueur parti\)/);
  });

  it("une fois le joueur absent, le HUD se rétablit et le dit", () => {
    const parti = fakePlayer("Parti", true);
    const present = fakePlayer(GAMERTAG);
    let liste: HudPlayerLike[] = [parti.player, present.player];
    const { hud, log } = hudFor(() => liste, false);
    hud.refresh();
    liste = [present.player];
    hud.refresh();
    assert.match(log.infos.join("\n"), /HUD : rétabli/);
  });

  it("service injoignable : seule la ligne « hors ligne » part", () => {
    const { player, sent } = fakePlayer(GAMERTAG);
    const { hud } = hudFor(() => [player], false, "down");
    hud.refresh();
    assert.ok(bare(sent.actionBars[0] ?? "").includes(HUD_TEXTES.horsLigne));
    assert.doesNotMatch(bare(sent.actionBars[0] ?? ""), /\d/);
  });

  it("un joueur arrivé sans réponse de join voit « hors ligne », pas un solde inventé", () => {
    const inconnu = fakePlayer("Inconnu");
    const { hud } = hudFor(() => [inconnu.player], false);
    hud.refresh();
    assert.ok(bare(inconnu.sent.actionBars[0] ?? "").includes(HUD_TEXTES.horsLigne));
  });

  it("personne en ligne : rien à envoyer, pas d'erreur", () => {
    const { hud, log } = hudFor(() => [], true);
    hud.refresh();
    assert.deepEqual(log.all(), []);
  });
});

describe("startHud", () => {
  it("se règle sur config.ui.hud.refreshSeconds, une fois par seconde au plus", () => {
    const scheduler = fakeScheduler();
    let appels = 0;
    startHud(scheduler, () => void (appels += 1));
    assert.equal(scheduler.intervals.length, 1);
    assert.equal(scheduler.intervals[0]?.seconds, config.ui.hud.refreshSeconds);
    assert.ok(config.ui.hud.refreshSeconds >= 1);
    scheduler.intervals[0]?.run();
    assert.equal(appels, 1);
  });
});
