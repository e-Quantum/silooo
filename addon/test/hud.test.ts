import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import type { MissionState } from "../src/protocol";
import { createStatusLog, secondsToTicks } from "../src/runtime";
import { COLOR } from "../src/ui/apparence";
import { anchorOf, counterValue } from "../src/ui/compteur";
import { createViewStore, type CompanyView } from "../src/ui/etat";
import {
  BAR_MAX_SEGMENTS,
  BAR_SEGMENT,
  HUD_SUBTITLE_PREFIX,
  HUD_TITLE_PREFIX,
  createHud,
  progressBar,
  renderHud,
  startHud,
  type HudPlayerLike,
  type HudTitleOptions,
} from "../src/ui/hud";
import { formatAmount, formatCount, formatRate } from "../src/ui/nombres";
import { HUD_TEXTES } from "../src/ui/textes";
import { balances, companyState, fakeScheduler, mission, missionState, recordingLogger } from "./helpers";
import { GAMERTAG, XUID, bare, fakeClock, storeWith } from "./ui-helpers";

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

function hudFor(
  players: () => HudPlayerLike[],
  titleChannel: boolean,
  link: "ok" | "down" = "ok",
  silenced?: (player: HudPlayerLike) => boolean,
) {
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
    ...(silenced === undefined ? {} : { silenced }),
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

describe("createHud, joueur silencieux (arrivée et cinématique)", () => {
  it("n'envoie rien à un joueur silencieux, ni titre ni barre d'action, et continue pour les autres", () => {
    const occupe = fakePlayer("Occupe");
    const libre = fakePlayer(GAMERTAG);
    const { hud, log } = hudFor(() => [occupe.player, libre.player], true, "ok", (player) => player.name === "Occupe");
    hud.refresh();
    assert.equal(occupe.sent.title, undefined);
    assert.deepEqual(occupe.sent.actionBars, []);
    assert.equal(libre.sent.actionBars.length, 1);
    assert.ok(libre.sent.title !== undefined);
    assert.deepEqual(log.all(), []);
  });

  it("reprend dès que le joueur n'est plus silencieux", () => {
    const joueur = fakePlayer(GAMERTAG);
    let occupe = true;
    const { hud } = hudFor(() => [joueur.player], false, "ok", () => occupe);
    hud.refresh();
    hud.refresh();
    assert.equal(joueur.sent.actionBars.length, 0);
    occupe = false;
    hud.refresh();
    assert.equal(joueur.sent.actionBars.length, 1);
  });

  it("un joueur silencieux qui ne répondrait plus n'est pas compté comme un échec", () => {
    const parti = fakePlayer("Parti", true);
    const { hud, log } = hudFor(() => [parti.player], false, "ok", () => true);
    hud.refresh();
    assert.deepEqual(log.all(), []);
  });

  it("sans règle de silence, tout le monde reçoit le HUD", () => {
    const joueur = fakePlayer(GAMERTAG);
    const { hud } = hudFor(() => [joueur.player], false);
    hud.refresh();
    assert.equal(joueur.sent.actionBars.length, 1);
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

// ---------------------------------------------------------------- la consigne de la mission

describe("renderHud, la consigne de la mission", () => {
  const render = (missions: MissionState | undefined, titleChannel = true, link: "ok" | "down" = "ok") =>
    renderHud({ view, missions, link, nowMs: 0, titleChannel });
  const consigne = (frame: { subtitle: string }): string => bare(frame.subtitle);

  it("montre la consigne de la mission en cours et son avancement tel que le service le donne", () => {
    assert.equal(
      consigne(render(missionState())),
      `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.mission("Encaisse au Silo", "0", "1")} ${bare(progressBar(0, 1))}`,
    );
    assert.equal(consigne(render(missionState())), `${HUD_SUBTITLE_PREFIX}Encaisse au Silo : 0/1 ■`);
  });

  it("met en forme les nombres du service, sans rien calculer : un avancement qui dépasse la cible reste tel quel", () => {
    assert.equal(
      consigne(render(missionState({ current: mission({ progress: 1_250, target: 20_000 }) }))),
      `${HUD_SUBTITLE_PREFIX}Encaisse au Silo : ${formatCount(1_250)}/${formatCount(20_000)} ■■■■■■■■■■`,
    );
    assert.equal(
      consigne(render(missionState({ current: mission({ progress: 5, target: 3 }) }))),
      `${HUD_SUBTITLE_PREFIX}Encaisse au Silo : 5/3 ■■■`,
    );
  });

  it("la consigne est en gris, comme celle du prestige", () => {
    assert.ok(render(missionState()).subtitle.includes(`${COLOR.muted}Encaisse au Silo : 0/1`));
  });

  it("sans lecture des missions, ou sans mission en cours, la consigne reste le seuil de prestige", () => {
    const prestige = `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.consigne(formatAmount(1_000_000))}`;
    assert.equal(consigne(render(undefined)), prestige);
    assert.equal(consigne(render({ current: null, completed: ["bienvenue-mairie"] })), prestige);
    assert.equal(consigne(renderHud({ view, link: "ok", nowMs: 0, titleChannel: true })), prestige);
  });

  it("une mission que cet add-on ne connaît pas laisse la place au prestige : pas de mots inventés", () => {
    const frame = render(missionState({ current: mission({ id: "mission-du-futur", progress: 2, target: 9 }) }));
    assert.equal(consigne(frame), `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.consigne(formatAmount(1_000_000))}`);
    assert.doesNotMatch(frame.subtitle, /2\/9/);
  });

  it("les monnaies et le gain ne changent pas", () => {
    const sans = render(undefined);
    const avec = render(missionState());
    assert.equal(avec.title, sans.title);
    assert.equal(avec.actionBar, sans.actionBar);
  });

  it("sans canal de titre, la mission en cours passe au-dessus des monnaies, dans la barre d'action", () => {
    const sans = render(undefined, false);
    const avec = render(missionState(), false);
    assert.equal(avec.title, "");
    assert.equal(avec.subtitle, "");
    assert.equal(bare(avec.actionBar), `Encaisse au Silo : 0/1 ■\n${bare(sans.actionBar)}`);
    // Sans mission que cet add-on sait dire, la barre d'action ne change pas : le prestige n'y monte pas.
    assert.deepEqual(render({ current: null, completed: ["bienvenue-mairie"] }, false), sans);
  });

  it("service injoignable : « hors ligne » seulement, aucune consigne", () => {
    const frame = render(missionState(), true, "down");
    assert.doesNotMatch(bare(frame.subtitle), /Encaisse|\d\/\d/);
    assert.doesNotMatch(bare(frame.title), /Encaisse|\d\/\d/);
  });
});

describe("progressBar, l'avancement d'une mission", () => {
  const segments = (bar: string) => ({ faits: bare(bar.split(COLOR.locked)[0] ?? ""), restants: bare(bar.split(COLOR.locked)[1] ?? "") });

  it("une petite cible : un segment par pas, les faits en vert, les autres en gris", () => {
    assert.deepEqual(segments(progressBar(2, 3)), { faits: BAR_SEGMENT.repeat(2), restants: BAR_SEGMENT });
    assert.ok(progressBar(2, 3).startsWith(COLOR.success));
    assert.deepEqual(segments(progressBar(3, 3)), { faits: BAR_SEGMENT.repeat(3), restants: "" });
  });

  it("une grande cible : dix segments au plus, jamais plus que la barre", () => {
    assert.equal(bare(progressBar(1_250, 20_000)), BAR_SEGMENT.repeat(BAR_MAX_SEGMENTS));
    assert.deepEqual(segments(progressBar(10_000, 20_000)), { faits: BAR_SEGMENT.repeat(5), restants: BAR_SEGMENT.repeat(5) });
    assert.deepEqual(segments(progressBar(9, 3)), { faits: BAR_SEGMENT.repeat(3), restants: "" });
  });

  it("une cible nulle ou absurde : pas de barre", () => {
    assert.equal(progressBar(0, 0), "");
    assert.equal(progressBar(1, Number.NaN), "");
  });
});

describe("createHud, les missions", () => {
  function hudWithMissions(missions: (xuid: string) => MissionState | undefined, withDep = true) {
    const clock = fakeClock(0);
    const views = createViewStore({ players: storeWith({ donar: 10, ratePerHour: 3_600, threshold: 1_000_000 }), clock });
    const asked: string[] = [];
    const { player, sent } = fakePlayer(GAMERTAG);
    const hud = createHud({
      players: () => [player],
      views,
      ...(withDep
        ? {
            missions: {
              get: (xuid: string) => {
                asked.push(xuid);
                return missions(xuid);
              },
            },
          }
        : {}),
      link: { status: () => "ok" as const },
      clock,
      status: createStatusLog(recordingLogger(), "HUD"),
      titleChannel: true,
      titleStaySeconds: 4,
    });
    return { hud, sent, asked };
  }

  it("lit les missions du joueur à chaque envoi : le sous-titre suit la lecture du service", () => {
    let courant: MissionState | undefined = undefined;
    const { hud, sent, asked } = hudWithMissions(() => courant);
    hud.refresh();
    assert.equal(
      bare(sent.title?.options?.subtitle ?? ""),
      `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.consigne(formatAmount(1_000_000))}`,
    );
    courant = missionState({ current: mission({ progress: 0, target: 1 }) });
    hud.refresh();
    assert.equal(bare(sent.title?.options?.subtitle ?? ""), `${HUD_SUBTITLE_PREFIX}Encaisse au Silo : 0/1 ■`);
    courant = { current: null, completed: ["bienvenue-mairie"] };
    hud.refresh();
    assert.equal(
      bare(sent.title?.options?.subtitle ?? ""),
      `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.consigne(formatAmount(1_000_000))}`,
    );
    assert.deepEqual(asked, [XUID, XUID, XUID]);
  });

  it("sans lecteur de missions, la consigne est le seuil de prestige", () => {
    const { hud, sent, asked } = hudWithMissions(() => missionState(), false);
    hud.refresh();
    assert.equal(
      bare(sent.title?.options?.subtitle ?? ""),
      `${HUD_SUBTITLE_PREFIX}${HUD_TEXTES.consigne(formatAmount(1_000_000))}`,
    );
    assert.deepEqual(asked, []);
  });
});
