import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { GameEvent, Xuid } from "../src/protocol";
import { boostLine, boostMinutes, createAnnouncements, gemmesText, sansCourantText } from "../src/ui/annonces";
import { createViewStore } from "../src/ui/etat";
import { ANNONCES_TEXTES, FACTORY_NAMES, renderBubble } from "../src/ui/textes";
import { recordingLogger } from "./helpers";
import { T0, XUID, bare, fakeClock, storeWith } from "./ui-helpers";

const MINUTE = 60_000;
const iso = (ms: number): string => new Date(ms).toISOString();

function event(kind: GameEvent["kind"], xuid: Xuid | null, payload: Record<string, unknown>): GameEvent {
  return { id: 1, xuid, kind, payload };
}

describe("gemmesText", () => {
  it("la bulle d'Oooscar, puis ce qui est arrivé et le solde, tels que l'événement les dit", () => {
    const lines = bare(gemmesText({ orderId: "abc", gemmes: 275, gemmesTotal: 1_300 })).split("\n");
    assert.equal(lines[0], bare(renderBubble(ANNONCES_TEXTES.gemmes.bulle)));
    assert.deepEqual(lines.slice(1), [ANNONCES_TEXTES.gemmes.ligne("275", "1 300")]);
  });

  it("une valeur absente ou illisible : la bulle seule, rien d'inventé", () => {
    const bulle = renderBubble(ANNONCES_TEXTES.gemmes.bulle);
    assert.equal(gemmesText({}), bulle);
    assert.equal(gemmesText({ gemmes: 275 }), bulle);
    assert.equal(gemmesText({ gemmes: "275", gemmesTotal: -1 }), bulle);
    assert.equal(gemmesText({ gemmes: 1.5, gemmesTotal: 10 }), bulle);
  });
});

describe("boostMinutes et boostLine", () => {
  it("la durée est le temps qui reste de maintenant à endsAt, arrondi à la minute", () => {
    assert.equal(boostMinutes({ endsAt: iso(T0 + 60 * MINUTE) }, T0), 60);
    assert.equal(boostMinutes({ endsAt: iso(T0 + 59.6 * MINUTE) }, T0), 60);
    assert.equal(boostMinutes({ endsAt: iso(T0 + 59.4 * MINUTE) }, T0), 59);
    assert.equal(boostMinutes({ endsAt: iso(T0 + 120 * MINUTE) }, T0), 120);
  });

  it("un boost qui attend le précédent garde sa durée propre : on compte depuis son début", () => {
    assert.equal(boostMinutes({ startsAt: iso(T0 + 30 * MINUTE), endsAt: iso(T0 + 90 * MINUTE) }, T0), 60);
    // Déjà commencé : on compte depuis maintenant.
    assert.equal(boostMinutes({ startsAt: iso(T0 - 20 * MINUTE), endsAt: iso(T0 + 40 * MINUTE) }, T0), 40);
  });

  it("un boost fini rend zéro ou moins : il ne se dit pas", () => {
    assert.ok((boostMinutes({ endsAt: iso(T0 - 5 * MINUTE) }, T0) ?? 1) <= 0);
    assert.equal(boostLine({ percent: 25, endsAt: iso(T0 - 5 * MINUTE) }, T0), undefined);
    assert.equal(boostLine({ percent: 25, startsAt: iso(T0 - 90 * MINUTE), endsAt: iso(T0 - 30 * MINUTE) }, T0), undefined);
  });

  it("sans endsAt lisible, la durée `minutes` de l'événement ; sans rien de lisible, undefined", () => {
    assert.equal(boostMinutes({ minutes: 60 }, T0), 60);
    assert.equal(boostMinutes({ minutes: 60, endsAt: "demain" }, T0), 60);
    assert.equal(boostMinutes({}, T0), undefined);
    assert.equal(boostMinutes({ minutes: -3 }, T0), undefined);
    assert.equal(boostMinutes({ endsAt: 5 }, T0), undefined);
  });

  it("la ligne de tchat avec le pseudo de l'acheteur : le bonus et la durée en mots", () => {
    const line = boostLine({ percent: 25, minutes: 60, buyerName: "Kr", startsAt: iso(T0), endsAt: iso(T0 + 60 * MINUTE) }, T0);
    assert.equal(bare(line ?? ""), ANNONCES_TEXTES.boost.avecNom("Kr", "25", "une heure"));
  });

  it("sans pseudo, la phrase ne nomme personne", () => {
    for (const buyerName of [undefined, "", 7]) {
      const line = boostLine({ percent: 25, endsAt: iso(T0 + 60 * MINUTE), buyerName }, T0);
      assert.equal(bare(line ?? ""), ANNONCES_TEXTES.boost.sansNom("25", "une heure"));
    }
  });

  it("la durée est dite en heures quand elle tombe juste, en minutes sinon", () => {
    assert.ok(bare(boostLine({ percent: 25, endsAt: iso(T0 + 120 * MINUTE) }, T0) ?? "").endsWith("pendant 2 heures."));
    assert.ok(bare(boostLine({ percent: 25, endsAt: iso(T0 + 45 * MINUTE) }, T0) ?? "").endsWith("pendant 45 minutes."));
    assert.ok(bare(boostLine({ percent: 25, endsAt: iso(T0 + 61 * MINUTE) }, T0) ?? "").endsWith("pendant 61 minutes."));
  });

  it("un pourcentage décimal est mis en forme à la française", () => {
    assert.ok(bare(boostLine({ percent: 12.5, minutes: 60 }, T0) ?? "").includes("+12,5 %"));
  });

  it("un payload illisible ne donne pas de ligne", () => {
    assert.equal(boostLine({}, T0), undefined);
    assert.equal(boostLine({ percent: 0, minutes: 60 }, T0), undefined);
    assert.equal(boostLine({ percent: -5, minutes: 60 }, T0), undefined);
    assert.equal(boostLine({ percent: "25", minutes: 60 }, T0), undefined);
    assert.equal(boostLine({ percent: 25 }, T0), undefined);
    assert.equal(boostLine({ percent: 25, minutes: 0 }, T0), undefined);
  });
});

describe("sansCourantText", () => {
  it("la bulle d'Oooscar, puis l'usine arrêtée, avec son nom du jeu", () => {
    const [id, name] = Object.entries(FACTORY_NAMES)[0] ?? [];
    const lines = bare(sansCourantText({ action: "sansCourant", factoryId: id, factoryName: "autre", playerName: "Kr" })).split("\n");
    assert.equal(lines[0], bare(renderBubble(ANNONCES_TEXTES.sansCourant.bulle)));
    assert.deepEqual(lines.slice(1), [ANNONCES_TEXTES.sansCourant.ligne(name ?? "")]);
  });

  it("sans usine lisible : la bulle seule", () => {
    const bulle = renderBubble(ANNONCES_TEXTES.sansCourant.bulle);
    assert.equal(sansCourantText({}), bulle);
    assert.equal(sansCourantText({ factoryId: "" }), bulle);
    assert.equal(sansCourantText({ factoryId: 4 }), bulle);
  });
});

describe("createAnnouncements", () => {
  function setup(opts: { online?: readonly Xuid[]; nowMs?: number; withView?: boolean; sayFails?: readonly Xuid[] } = {}) {
    const { online = [XUID], nowMs = T0, withView = true, sayFails = [] } = opts;
    const clock = fakeClock(nowMs);
    const players = withView ? storeWith({ version: 1 }, { gemmes: 5, lingots: 8 }) : storeWith();
    const views = createViewStore({ players, clock });
    if (!withView) players.remove(XUID);
    const sent: Array<{ xuid: Xuid; text: string }> = [];
    const log = recordingLogger();
    const announcements = createAnnouncements({
      views,
      online: () => online,
      say: (xuid, text) => {
        if (sayFails.includes(xuid)) throw new Error("joueur parti");
        sent.push({ xuid, text });
      },
      clock,
      log,
    });
    return { announcements, views, sent, log, clock };
  }

  describe("GEMMES_RECUES", () => {
    it("une bulle par livraison, puis le solde affiché est celui du service", () => {
      const { announcements, views, sent } = setup();
      const payload = { orderId: "a", gemmes: 275, gemmesTotal: 1_300 };
      announcements.onGemmes(event("GEMMES_RECUES", XUID, payload));
      assert.deepEqual(sent, [{ xuid: XUID, text: gemmesText(payload) }]);
      assert.equal(views.get(XUID)?.balances.gemmes, 1_300);
      // Les autres soldes et l'état ne bougent pas.
      assert.equal(views.get(XUID)?.balances.lingots, 8);
      assert.equal(views.get(XUID)?.state.version, 1);
    });

    it("deux livraisons : deux bulles, le dernier solde l'emporte", () => {
      const { announcements, views, sent } = setup();
      announcements.onGemmes(event("GEMMES_RECUES", XUID, { gemmes: 50, gemmesTotal: 55 }));
      announcements.onGemmes(event("GEMMES_RECUES", XUID, { gemmes: 275, gemmesTotal: 330 }));
      assert.equal(sent.length, 2);
      assert.equal(views.get(XUID)?.balances.gemmes, 330);
    });

    it("sans solde dans l'événement, la bulle est dite et le solde de la vue ne change pas", () => {
      const { announcements, views, sent } = setup();
      announcements.onGemmes(event("GEMMES_RECUES", XUID, { gemmes: 50 }));
      assert.equal(sent.length, 1);
      assert.equal(views.get(XUID)?.balances.gemmes, 5);
    });

    it("un joueur sans vue (absent) n'a personne à qui parler ; un autre événement ou sans joueur est ignoré", () => {
      const absent = setup({ withView: false });
      absent.announcements.onGemmes(event("GEMMES_RECUES", XUID, { gemmes: 50, gemmesTotal: 55 }));
      assert.deepEqual(absent.sent, []);
      const { announcements, sent, views } = setup();
      announcements.onGemmes(event("GEMMES_RECUES", null, { gemmes: 50, gemmesTotal: 55 }));
      announcements.onGemmes(event("ANNONCE", XUID, { gemmes: 50, gemmesTotal: 55 }));
      assert.deepEqual(sent, []);
      assert.equal(views.get(XUID)?.balances.gemmes, 5);
    });

    it("un tchat qui lève est journalisé et ne fait pas lever l'événement, le solde est quand même rangé", () => {
      const { announcements, views, log } = setup({ sayFails: [XUID] });
      announcements.onGemmes(event("GEMMES_RECUES", XUID, { gemmes: 50, gemmesTotal: 55 }));
      assert.match(log.warns.join("\n"), /GEMMES_RECUES, message à .*joueur parti/);
      assert.equal(views.get(XUID)?.balances.gemmes, 55);
    });
  });

  describe("BOOST_COLLECTIF", () => {
    const boost = (startsAt: number, endsAt: number, extra: Record<string, unknown> = {}): GameEvent =>
      event("BOOST_COLLECTIF", null, { percent: 25, minutes: 60, startsAt: iso(startsAt), endsAt: iso(endsAt), buyerName: "Kr", ...extra });

    it("une seule ligne de tchat, la même pour tous les joueurs présents", () => {
      const { announcements, sent } = setup({ online: ["1", "2", "3"] });
      announcements.onBoost(boost(T0, T0 + 60 * MINUTE));
      assert.deepEqual(
        sent.map((message) => message.xuid),
        ["1", "2", "3"],
      );
      assert.equal(new Set(sent.map((message) => message.text)).size, 1);
      assert.equal(bare(sent[0]?.text ?? ""), ANNONCES_TEXTES.boost.avecNom("Kr", "25", "une heure"));
    });

    it("la durée dite est celle qui reste, arrondie : un boost lancé il y a deux minutes se dit 58 minutes", () => {
      const { announcements, sent } = setup({ nowMs: T0 + 2 * MINUTE });
      announcements.onBoost(boost(T0, T0 + 60 * MINUTE));
      assert.ok(bare(sent[0]?.text ?? "").endsWith("pendant 58 minutes."));
    });

    it("un boost qui attend le précédent se dit avec sa durée propre", () => {
      const { announcements, sent } = setup();
      announcements.onBoost(boost(T0 + 30 * MINUTE, T0 + 90 * MINUTE));
      assert.ok(bare(sent[0]?.text ?? "").endsWith("pendant une heure."));
    });

    it("un boost fini, relu au redémarrage, ne se dit pas", () => {
      const { announcements, sent } = setup({ nowMs: T0 + 3 * 60 * MINUTE });
      announcements.onBoost(boost(T0, T0 + 60 * MINUTE));
      assert.deepEqual(sent, []);
    });

    it("personne en ligne, un autre événement ou un payload illisible : rien", () => {
      const vide = setup({ online: [] });
      vide.announcements.onBoost(boost(T0, T0 + 60 * MINUTE));
      assert.deepEqual(vide.sent, []);
      const { announcements, sent } = setup();
      announcements.onBoost(event("GEMMES_RECUES", null, { percent: 25, minutes: 60 }));
      announcements.onBoost(event("BOOST_COLLECTIF", null, {}));
      assert.deepEqual(sent, []);
    });

    it("un tchat qui lève pour un joueur n'empêche pas de le dire aux autres", () => {
      const { announcements, sent, log } = setup({ online: ["1", "2"], sayFails: ["1"] });
      announcements.onBoost(boost(T0, T0 + 60 * MINUTE));
      assert.deepEqual(
        sent.map((message) => message.xuid),
        ["2"],
      );
      assert.match(log.warns.join("\n"), /BOOST_COLLECTIF, message à 1/);
    });
  });

  describe("ANNONCE pionnier", () => {
    const pionnier = (xuid: Xuid | null = XUID): GameEvent => event("ANNONCE", xuid, { action: "pionnier", cosmeticId: "pionnier" });

    it("une bulle de bienvenue, une seule fois par joueur", () => {
      const { announcements, sent } = setup();
      announcements.onAnnonce(pionnier());
      announcements.onAnnonce(pionnier());
      assert.deepEqual(sent, [{ xuid: XUID, text: renderBubble(ANNONCES_TEXTES.pionnier) }]);
    });

    it("le titre est retenu même si le joueur n'est pas là : le menu l'affichera à son retour", () => {
      const { announcements, sent } = setup({ online: [] });
      announcements.onAnnonce(pionnier());
      assert.deepEqual(sent, []);
      assert.deepEqual(announcements.cosmeticsOf(XUID, []), ["pionnier"]);
    });

    it("cosmeticsOf : ceux de join, plus le titre appris par l'annonce, sans doublon", () => {
      const { announcements } = setup();
      assert.deepEqual(announcements.cosmeticsOf(XUID, undefined), []);
      assert.deepEqual(announcements.cosmeticsOf(XUID, ["s1-fanion"]), ["s1-fanion"]);
      announcements.onAnnonce(pionnier());
      assert.deepEqual(announcements.cosmeticsOf(XUID, undefined), ["pionnier"]);
      assert.deepEqual(announcements.cosmeticsOf(XUID, ["s1-fanion"]), ["s1-fanion", "pionnier"]);
      assert.deepEqual(announcements.cosmeticsOf(XUID, ["pionnier", "s1-fanion"]), ["pionnier", "s1-fanion"]);
      // Un autre joueur n'a pas le titre.
      assert.deepEqual(announcements.cosmeticsOf("2", []), []);
    });

    it("sans joueur, ou avec une autre action, rien n'est dit ni retenu", () => {
      const { announcements, sent } = setup();
      announcements.onAnnonce(pionnier(null));
      announcements.onAnnonce(event("ANNONCE", XUID, { action: "mission" }));
      announcements.onAnnonce(event("BOOST_COLLECTIF", XUID, { action: "pionnier" }));
      assert.deepEqual(sent, []);
      assert.deepEqual(announcements.cosmeticsOf(XUID, []), []);
    });
  });

  describe("ANNONCE sansCourant", () => {
    const [factoryId] = Object.keys(FACTORY_NAMES);
    const sansCourant = (xuid: Xuid | null = XUID): GameEvent =>
      event("ANNONCE", xuid, { action: "sansCourant", companyId: 7, factoryId, factoryName: "Carrière", playerName: "Alex" });

    it("une bulle d'Oooscar au joueur concerné, avec le nom de l'usine", () => {
      const { announcements, sent } = setup();
      announcements.onAnnonce(sansCourant());
      assert.deepEqual(sent, [{ xuid: XUID, text: sansCourantText(sansCourant().payload) }]);
    });

    it("à chaque fois : l'alerte n'est pas dite une seule fois", () => {
      const { announcements, sent } = setup();
      announcements.onAnnonce(sansCourant());
      announcements.onAnnonce(sansCourant());
      assert.equal(sent.length, 2);
    });

    it("un joueur absent, ou sans joueur : rien", () => {
      const absent = setup({ online: [] });
      absent.announcements.onAnnonce(sansCourant());
      assert.deepEqual(absent.sent, []);
      const { announcements, sent } = setup();
      announcements.onAnnonce(sansCourant(null));
      assert.deepEqual(sent, []);
    });

    it("un tchat qui lève est journalisé", () => {
      const { announcements, log } = setup({ sayFails: [XUID] });
      announcements.onAnnonce(sansCourant());
      assert.match(log.warns.join("\n"), /annonce-sansCourant, message à .*joueur parti/);
    });
  });
});
