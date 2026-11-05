import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { Balances } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { createInvitations } from "../src/ui/invitations";
import { createMenu } from "../src/ui/menu";
import { createMissions } from "../src/ui/missions";
import { ASSOCIES_TEXTES, BOUTIQUE_TEXTES, MENU_TEXTES, PASS_TEXTES, VOTE_TEXTES, refusalBubble, renderBubble } from "../src/ui/textes";
import { balances, companyState, ok, passClaimed, passState, recordingLogger, shopCatalog, votesToday } from "./helpers";
import {
  PLAYER,
  T0,
  XUID,
  bare,
  close,
  failure,
  fakeClock,
  fakeCompany,
  fakeMissionsApi,
  fakePresenter,
  press,
  refused,
  sequentialIds,
  storeWith,
  unreachable,
  type Reply,
  type Script,
} from "./ui-helpers";

const BEA_XUID = "2535428000000003";

const EN_LIGNE = [
  { xuid: XUID, name: "Alex" },
  { xuid: BEA_XUID, name: "Bea" },
];

/** Les trois lectures que la boutique fait à l'ouverture : le catalogue, le pass et les votes du jour. */
function lectures(catalog = shopCatalog(), pass = passState(), votes = votesToday()): Script {
  return { catalog: [ok(catalog)], getPass: [ok(pass)], todayVotes: [ok(votes)] };
}

/** Un script où chaque route a ses réponses dans l'ordre, celles de `more` après celles de `base`. */
function scripts(base: Script, more: Script): Script {
  const merged: Script = {};
  for (const key of new Set([...Object.keys(base), ...Object.keys(more)]) as Set<keyof Script>) {
    (merged as Record<string, unknown[]>)[key] = [...(base[key] ?? []), ...(more[key] ?? [])];
  }
  return merged;
}

function setup(
  replies: readonly Reply[],
  script: Script = lectures(),
  soldes: Partial<Balances> = { gemmes: 500 },
  cosmetics: readonly string[] = [],
) {
  const clock = fakeClock(T0);
  const store = storeWith({ name: "Atelier" }, soldes);
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const missions = createMissions({ api: fakeMissionsApi(), players: store, say: () => undefined, log });
  const invitations = createInvitations({ clock, players: store, say: () => undefined, log });
  const actions = createActions({
    api,
    views,
    newRequestId: sequentialIds(),
    log,
    afterCollect: missions.refresh,
    afterJoin: () => undefined,
  });
  const presenter = fakePresenter(replies);
  const menu = createMenu({
    views,
    missions,
    actions,
    invitations,
    online: () => EN_LIGNE,
    presenter,
    clock,
    wait: async () => undefined,
    log,
    tilesPerScreen: 8,
    busyRetries: 3,
    busyRetrySeconds: 0.5,
    cosmetics: () => cosmetics,
  });
  return { menu, presenter, api, views, log };
}

const titleOf = (spec: { title: string }) => spec.title;

/** Le nom d'un bouton : sa première ligne, sans le repère d'icône ni les couleurs. */
const nameOf = (button: string) => bare(button).split("\n")[0]?.replace(/^\[[A-Z!]\] /, "") ?? "";

const ops = (calls: ReadonlyArray<{ op: string }>) => calls.map((call) => call.op);

const gemmesDe = (views: ReturnType<typeof setup>["views"]) => views.get(XUID)?.balances.gemmes;

describe("parcours : la tuile Boutique et l'écran de la boutique", () => {
  it("la tuile du menu porte les Gemmes du service ; elle prend la place d'Associés, qui est sous Personnel", async () => {
    const { menu, presenter } = setup([close]);
    await menu.open(PLAYER);
    const tuiles = presenter.shown[0]?.buttons.map(nameOf);
    assert.ok(tuiles?.includes(MENU_TEXTES.boutique), String(tuiles));
    assert.ok(!tuiles?.includes("Associés"), String(tuiles));
    const tuile = presenter.shown[0]?.buttons.find((texte) => nameOf(texte) === MENU_TEXTES.boutique) ?? "";
    assert.ok(bare(tuile).endsWith(BOUTIQUE_TEXTES.tuile("500")), bare(tuile));
  });

  it("la tuile ouvre l'écran, qui lit ensemble le catalogue, le pass et les votes du jour", async () => {
    const { menu, presenter, api } = setup([press(MENU_TEXTES.boutique), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE"]);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes"]);
    assert.deepEqual(api.calls[0], { op: "catalog", actor: XUID });
    assert.deepEqual(api.calls[1], { op: "getPass", actor: XUID });
    assert.deepEqual(api.calls[2], { op: "todayVotes", xuid: XUID });
  });

  it("les portes, dans l'ordre : grades, Pilier, cosmétiques, boost collectif, pass ; le préfixe perso n'y est pas", async () => {
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close]);
    await menu.open(PLAYER);
    const noms = presenter.shown[1]?.buttons.map(nameOf);
    assert.deepEqual(noms, ["Grades", "Pilier", BOUTIQUE_TEXTES.rayons.cosmetiques, BOUTIQUE_TEXTES.rayons.boost, BOUTIQUE_TEXTES.rayons.pass, BOUTIQUE_TEXTES.retour]);
  });

  it("les Gemmes et le prochain grade sont ceux du service, le pass dit combien de récompenses attendent", async () => {
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close]);
    await menu.open(PLAYER);
    const screen = presenter.shown[1];
    assert.ok(bare(screen?.body ?? "").includes(BOUTIQUE_TEXTES.solde("500")), screen?.body);
    const texts = screen?.buttons.map(bare) ?? [];
    assert.ok(texts[0]?.endsWith(BOUTIQUE_TEXTES.details.prochainGrade("MVIP")), texts[0]);
    assert.ok(texts[1]?.endsWith(BOUTIQUE_TEXTES.details.prix("400")), texts[1]);
    assert.ok(texts[3]?.endsWith(BOUTIQUE_TEXTES.details.prix("150")), texts[3]);
    assert.ok(texts[4]?.endsWith(PASS_TEXTES.aReclamer(1)), texts[4]);
  });

  it("le rappel du vote du jour porte le nom des sites, jamais un lien", async () => {
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close]);
    await menu.open(PLAYER);
    const body = bare(presenter.shown[1]?.body ?? "");
    assert.ok(body.includes(VOTE_TEXTES.rappel.boutique("Hubyy")), body);
    assert.doesNotMatch(body, /https?:|www\./);
  });

  it("le joueur a déjà voté : pas de rappel", async () => {
    const script = lectures(shopCatalog(), passState(), votesToday({ mine: { votes: 1, streak: 2 } }));
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close], script);
    await menu.open(PLAYER);
    assert.ok(!bare(presenter.shown[1]?.body ?? "").includes("Vote du jour"), presenter.shown[1]?.body);
  });

  it("la lecture des votes qui échoue ne coûte à l'écran que sa ligne", async () => {
    const script: Script = { catalog: [ok(shopCatalog())], getPass: [ok(passState())], todayVotes: [unreachable()] };
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close], script);
    await menu.open(PLAYER);
    const body = bare(presenter.shown[1]?.body ?? "");
    assert.ok(!body.includes("Vote du jour"), body);
    assert.ok(!body.includes("Oooscar"), body);
  });

  it("sans Gemmes, l'écran dit où l'on en achète, sans adresse", async () => {
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close], lectures(), { gemmes: 0 });
    await menu.open(PLAYER);
    const body = bare(presenter.shown[1]?.body ?? "");
    assert.ok(body.includes(BOUTIQUE_TEXTES.gemmesManquent), body);
    assert.doesNotMatch(body, /https?:|www\.|\.com/);
  });

  it("le catalogue qui ne répond pas : les portes des rayons et du pass restent, Oooscar dit pourquoi", async () => {
    const script: Script = { catalog: [unreachable()], getPass: [ok(passState())], todayVotes: [ok(votesToday())] };
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE"]);
    assert.ok(bare(presenter.shown[1]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."), presenter.shown[1]?.body);
    assert.deepEqual(presenter.shown[1]?.buttons.map(nameOf), ["Grades", BOUTIQUE_TEXTES.rayons.cosmetiques, BOUTIQUE_TEXTES.rayons.pass, BOUTIQUE_TEXTES.retour]);
  });

  it("un rayon sans catalogue renvoie à la boutique, qui relit : relue, elle montre les grades", async () => {
    const script: Script = {
      catalog: [unreachable(), ok(shopCatalog())],
      getPass: [ok(passState()), ok(passState())],
      todayVotes: [ok(votesToday()), ok(votesToday())],
    };
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press("Grades"), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "BOUTIQUE"]);
    assert.ok(bare(presenter.shown[2]?.buttons[0] ?? "").endsWith(BOUTIQUE_TEXTES.details.prochainGrade("MVIP")), presenter.shown[2]?.buttons[0]);
  });

  it("si la relecture échoue encore, Oooscar le redit au lieu de se taire", async () => {
    const script: Script = {
      catalog: [unreachable(), unreachable()],
      getPass: [ok(passState()), ok(passState())],
      todayVotes: [ok(votesToday()), ok(votesToday())],
    };
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press("Grades"), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "BOUTIQUE"]);
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."), presenter.shown[2]?.body);
  });

  it("Retour ramène au menu, sans nouvelle lecture", async () => {
    const { menu, presenter, api } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.retour), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "MENU"]);
    assert.equal(api.calls.length, 3);
  });

  it("le titre Pionnier est sous le nom de l'entreprise, quand join le donne", async () => {
    const avec = setup([close], lectures(), { gemmes: 500 }, ["pionnier"]);
    await avec.menu.open(PLAYER);
    assert.ok(bare(avec.presenter.shown[0]?.body ?? "").includes(BOUTIQUE_TEXTES.titreDuJoueur("Pionnier")), avec.presenter.shown[0]?.body);
    const sans = setup([close], lectures(), { gemmes: 500 }, ["chat-atelier"]);
    await sans.menu.open(PLAYER);
    assert.ok(!bare(sans.presenter.shown[0]?.body ?? "").includes("Titre :"), sans.presenter.shown[0]?.body);
  });
});

describe("parcours : un grade, du rayon à l'achat", () => {
  const VERS_MVIP: Reply[] = [press(MENU_TEXTES.boutique), press("Grades"), press("MVIP")];

  it("le rayon liste les grades dans l'ordre du service, la fiche donne le prix et le bonus", async () => {
    const { menu, presenter } = setup([...VERS_MVIP, close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE"]);
    assert.deepEqual(presenter.shown[2]?.buttons.map(nameOf), ["MVIP", "VIP", "VIP+", "Héros", "Légende", "Ultime", BOUTIQUE_TEXTES.retour]);
    const fiche = bare(presenter.shown[3]?.body ?? "");
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.article.prix("100")), fiche);
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.solde("500")), fiche);
    assert.deepEqual(presenter.shown[3]?.buttons.map(nameOf), [BOUTIQUE_TEXTES.article.acheter, BOUTIQUE_TEXTES.article.offrir, BOUTIQUE_TEXTES.article.retour]);
  });

  it("un grade pas encore disponible porte son cadenas et la raison, et ne s'achète pas", async () => {
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press("Grades"), press("Légende"), close]);
    await menu.open(PLAYER);
    const tuile = bare(presenter.shown[2]?.buttons[4] ?? "");
    assert.ok(tuile.includes(BOUTIQUE_TEXTES.details.bloque), tuile);
    const fiche = presenter.shown[3];
    assert.ok(bare(fiche?.body ?? "").includes(BOUTIQUE_TEXTES.article.bloque), fiche?.body);
    assert.ok(!fiche?.buttons.map(nameOf).includes(BOUTIQUE_TEXTES.article.acheter), String(fiche?.buttons));
  });

  it("l'achat : le service débite, l'écran montre ses Gemmes, le catalogue relu dit que le grade est possédé", async () => {
    const script = scripts(lectures(), {
      buyItem: [ok(balances({ gemmes: 400 }))],
      catalog: [ok(shopCatalog({ mvip: { owned: true }, vip: { available: true } }))],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    const { menu, presenter, api, views } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE", "ARTICLE"]);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "buyItem", "catalog", "getCompany"]);
    assert.deepEqual(api.calls[3], { op: "buyItem", action: { requestId: "id-1", actor: XUID }, itemId: "mvip", forXuid: undefined });
    assert.equal(gemmesDe(views), 400);
    const fiche = bare(presenter.shown[4]?.body ?? "");
    assert.ok(fiche.startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.achete))), fiche);
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.article.possede), fiche);
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.solde("400")), fiche);
    assert.ok(!presenter.shown[4]?.buttons.map(nameOf).includes(BOUTIQUE_TEXTES.article.acheter), String(presenter.shown[4]?.buttons));
  });

  it("le prochain grade se débloque à la relecture : le rayon montre le suivant avec son prix", async () => {
    const script = scripts(lectures(), {
      buyItem: [ok(balances({ gemmes: 400 }))],
      catalog: [ok(shopCatalog({ mvip: { owned: true }, vip: { available: true } }))],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    const { menu, presenter } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), press(BOUTIQUE_TEXTES.article.retour), close], script);
    await menu.open(PLAYER);
    const rayon = presenter.shown[5];
    assert.equal(rayon?.title, "RAYON");
    assert.ok(bare(rayon?.buttons[0] ?? "").endsWith(BOUTIQUE_TEXTES.details.possede), rayon?.buttons[0]);
    assert.ok(bare(rayon?.buttons[1] ?? "").endsWith(BOUTIQUE_TEXTES.details.prix("500")), rayon?.buttons[1]);
  });

  it("une relecture du catalogue qui échoue après l'achat ne ferme pas l'écran : la bulle dit l'achat", async () => {
    const script = scripts(lectures(), {
      buyItem: [ok(balances({ gemmes: 400 }))],
      catalog: [unreachable()],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    const { menu, presenter } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.equal(presenter.shown[4]?.title, "ARTICLE");
    assert.ok(bare(presenter.shown[4]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.achete))), presenter.shown[4]?.body);
  });

  it("il manque des Gemmes : le solde reste celui du service, et Oooscar dit où en acheter", async () => {
    const script = scripts(lectures(), { buyItem: [refused("PAS_ASSEZ_DE_GEMMES", 60)] });
    const { menu, presenter, api, views } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script, { gemmes: 40 });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE", "ARTICLE"]);
    assert.equal(gemmesDe(views), 40);
    const fiche = bare(presenter.shown[4]?.body ?? "");
    assert.ok(fiche.startsWith(bare(renderBubble(refusalBubble("PAS_ASSEZ_DE_GEMMES", 60)))), fiche);
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.gemmesManquent), fiche);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "buyItem"]);
  });

  it("un autre refus du service garde sa bulle habituelle, sans la ligne des Gemmes", async () => {
    const script = scripts(lectures(), { buyItem: [refused("GRADE_PRECEDENT_REQUIS")] });
    const { menu, presenter } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    const fiche = bare(presenter.shown[4]?.body ?? "");
    assert.ok(fiche.startsWith("Oooscar ["), fiche);
    assert.ok(!fiche.includes(BOUTIQUE_TEXTES.gemmesManquent), fiche);
  });

  it("l'article n'est plus en vente (400) : Oooscar le dit, la fiche reste", async () => {
    const script = scripts(lectures(), { buyItem: [failure(400, "REQUETE_INVALIDE")] });
    const { menu, presenter } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.equal(presenter.shown[4]?.title, "ARTICLE");
    assert.ok(bare(presenter.shown[4]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.plusEnVente))), presenter.shown[4]?.body);
  });

  it("le service qui ne répond pas : la fiche reste, la bulle le dit, rien n'est débité", async () => {
    const script = scripts(lectures(), { buyItem: [unreachable()] });
    const { menu, presenter, views } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[4]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."), presenter.shown[4]?.body);
    assert.equal(gemmesDe(views), 500);
  });

  it("Retour remonte la fiche, le rayon, puis la boutique, qui relit", async () => {
    const script = scripts(lectures(), lectures());
    const { menu, presenter, api } = setup([...VERS_MVIP, press(BOUTIQUE_TEXTES.article.retour), press(BOUTIQUE_TEXTES.retour), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE", "RAYON", "BOUTIQUE"]);
    assert.equal(api.calls.length, 6);
  });
});

describe("parcours : les cosmétiques, par catégorie", () => {
  it("la liste des catégories compte les articles, la catégorie liste les siens, Retour remonte étage par étage", async () => {
    const script = scripts(lectures(), lectures());
    const replies = [
      press(MENU_TEXTES.boutique),
      press(BOUTIQUE_TEXTES.rayons.cosmetiques),
      press(BOUTIQUE_TEXTES.categories.decor),
      press("Lampadaire"),
      press(BOUTIQUE_TEXTES.article.retour),
      press(BOUTIQUE_TEXTES.retour),
      press(BOUTIQUE_TEXTES.retour),
      close,
    ];
    const { menu, presenter } = setup(replies, script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "RAYON", "ARTICLE", "RAYON", "RAYON", "BOUTIQUE"]);
    const categories = presenter.shown[2]?.buttons.map(nameOf);
    assert.ok(categories?.includes(BOUTIQUE_TEXTES.categories.decor), String(categories));
    assert.ok(bare(presenter.shown[2]?.buttons.find((texte) => nameOf(texte) === BOUTIQUE_TEXTES.categories.decor) ?? "").endsWith(BOUTIQUE_TEXTES.details.articles(3)));
    assert.deepEqual(presenter.shown[3]?.buttons.map(nameOf), ["Lampadaire d'usine", "Bac à fleurs", "Fontaine de cour", BOUTIQUE_TEXTES.retour]);
  });

  it("un cosmétique déjà possédé le dit, et ne s'achète plus", async () => {
    const script = lectures(shopCatalog({ lampadaire: { owned: true } }));
    const replies = [press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.cosmetiques), press(BOUTIQUE_TEXTES.categories.decor), press("Lampadaire"), close];
    const { menu, presenter } = setup(replies, script);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[3]?.buttons[0] ?? "").endsWith(BOUTIQUE_TEXTES.details.possede), presenter.shown[3]?.buttons[0]);
    assert.ok(bare(presenter.shown[4]?.body ?? "").includes(BOUTIQUE_TEXTES.article.possede), presenter.shown[4]?.body);
    assert.ok(!presenter.shown[4]?.buttons.map(nameOf).includes(BOUTIQUE_TEXTES.article.acheter), String(presenter.shown[4]?.buttons));
  });

  it("acheter un cosmétique ne relit pas l'entreprise : il ne change pas le revenu", async () => {
    const script = scripts(lectures(), { buyItem: [ok(balances({ gemmes: 450 }))], catalog: [ok(shopCatalog({ lampadaire: { owned: true } }))] });
    const replies = [
      press(MENU_TEXTES.boutique),
      press(BOUTIQUE_TEXTES.rayons.cosmetiques),
      press(BOUTIQUE_TEXTES.categories.decor),
      press("Lampadaire"),
      press(BOUTIQUE_TEXTES.article.acheter),
      close,
    ];
    const { menu, api, views } = setup(replies, script);
    await menu.open(PLAYER);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "buyItem", "catalog"]);
    assert.equal(api.calls[3] !== undefined && "itemId" in api.calls[3] ? api.calls[3].itemId : undefined, "lampadaire");
    assert.equal(gemmesDe(views), 450);
  });
});

describe("parcours : offrir à un joueur connecté", () => {
  const VERS_OFFRIR: Reply[] = [press(MENU_TEXTES.boutique), press("Grades"), press("MVIP"), press(BOUTIQUE_TEXTES.article.offrir)];

  it("le choix liste les autres joueurs connectés, pas le joueur lui-même", async () => {
    const { menu, presenter } = setup([...VERS_OFFRIR, close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE", "OFFRIR"]);
    assert.deepEqual(presenter.shown[4]?.buttons.map(nameOf), ["Bea", BOUTIQUE_TEXTES.offrir.retour]);
    assert.ok(bare(presenter.shown[4]?.body ?? "").includes(BOUTIQUE_TEXTES.offrir.phrase("MVIP", "100")), presenter.shown[4]?.body);
  });

  it("offrir : le service reçoit le destinataire, le joueur revient à la fiche avec ses Gemmes d'après le service", async () => {
    const script = scripts(lectures(), { buyItem: [ok(balances({ gemmes: 400 }))] });
    const { menu, presenter, api, views } = setup([...VERS_OFFRIR, press("Bea"), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "RAYON", "ARTICLE", "OFFRIR", "ARTICLE"]);
    assert.deepEqual(api.calls[3], { op: "buyItem", action: { requestId: "id-1", actor: XUID }, itemId: "mvip", forXuid: BEA_XUID });
    assert.equal(gemmesDe(views), 400);
    const fiche = bare(presenter.shown[5]?.body ?? "");
    assert.ok(fiche.startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.offert))), fiche);
    assert.ok(fiche.includes(BOUTIQUE_TEXTES.solde("400")), fiche);
    // Offrir ne change ni le catalogue du joueur ni son revenu : aucune relecture.
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "buyItem"]);
  });

  it("il l'a déjà : Oooscar le dit, le joueur reste sur le choix et paie rien", async () => {
    const script = scripts(lectures(), { buyItem: [refused("DEJA_POSSEDE")] });
    const { menu, presenter, views } = setup([...VERS_OFFRIR, press("Bea"), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["OFFRIR", "OFFRIR"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.dejaChezLui))), presenter.shown[5]?.body);
    assert.equal(gemmesDe(views), 500);
  });

  it("le grade précédent lui manque : la bulle parle de lui, pas du joueur", async () => {
    const script = scripts(lectures(), { buyItem: [refused("GRADE_PRECEDENT_REQUIS")] });
    const { menu, presenter } = setup([...VERS_OFFRIR, press("Bea"), close], script);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.gradePrecedentPourLui))), presenter.shown[5]?.body);
  });

  it("le service ne connaît pas le destinataire (400) : la même bulle que pour inviter", async () => {
    const script = scripts(lectures(), { buyItem: [failure(400, "REQUETE_INVALIDE")] });
    const { menu, presenter } = setup([...VERS_OFFRIR, press("Bea"), close], script);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith(bare(renderBubble(ASSOCIES_TEXTES.bulles.joueurInconnu))), presenter.shown[5]?.body);
  });

  it("il manque des Gemmes pour le cadeau : la ligne des Gemmes est dite aussi", async () => {
    const script = scripts(lectures(), { buyItem: [refused("PAS_ASSEZ_DE_GEMMES", 60)] });
    const { menu, presenter } = setup([...VERS_OFFRIR, press("Bea"), close], script, { gemmes: 40 });
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").includes(BOUTIQUE_TEXTES.gemmesManquent), presenter.shown[5]?.body);
  });

  it("Retour ramène à la fiche de l'article", async () => {
    const { menu, presenter } = setup([...VERS_OFFRIR, press(BOUTIQUE_TEXTES.offrir.retour), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(3), ["ARTICLE", "OFFRIR", "ARTICLE"]);
  });
});

describe("parcours : le Pilier et le boost collectif", () => {
  it("le Pilier se prolonge : l'abonnement en cours le dit, le bouton est Prolonger, la bulle aussi", async () => {
    const script = scripts(lectures(shopCatalog({ pilier: { owned: true } })), {
      buyItem: [ok(balances({ gemmes: 100 }))],
      catalog: [ok(shopCatalog({ pilier: { owned: true } }))],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    const { menu, presenter, api } = setup([press(MENU_TEXTES.boutique), press("Pilier"), press(BOUTIQUE_TEXTES.article.prolonger), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "ARTICLE", "ARTICLE"]);
    assert.ok(bare(presenter.shown[1]?.buttons[1] ?? "").endsWith(BOUTIQUE_TEXTES.details.enCours), presenter.shown[1]?.buttons[1]);
    assert.deepEqual(presenter.shown[2]?.buttons.map(nameOf), [BOUTIQUE_TEXTES.article.prolonger, BOUTIQUE_TEXTES.article.offrir, BOUTIQUE_TEXTES.article.retour]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.prolonge))), presenter.shown[3]?.body);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "buyItem", "catalog", "getCompany"]);
  });

  it("le Pilier pas encore pris s'achète : la bulle dit l'achat, la fiche montre ensuite l'abonnement en cours", async () => {
    const script = scripts(lectures(), {
      buyItem: [ok(balances({ gemmes: 100 }))],
      catalog: [ok(shopCatalog({ pilier: { owned: true } }))],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press("Pilier"), press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.achete))), presenter.shown[3]?.body);
    assert.ok(bare(presenter.shown[3]?.body ?? "").includes(BOUTIQUE_TEXTES.article.enCours), presenter.shown[3]?.body);
  });

  it("le boost collectif : un achat, pas de cadeau, la bulle dit qu'il est lancé pour le serveur", async () => {
    const script = scripts(lectures(), { buyItem: [ok(balances({ gemmes: 350 }))], catalog: [ok(shopCatalog())], getCompany: [ok(companyState({ version: 2 }))] });
    const { menu, presenter, api } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.boost), press(BOUTIQUE_TEXTES.article.acheter), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown[2]?.buttons.map(nameOf), [BOUTIQUE_TEXTES.article.acheter, BOUTIQUE_TEXTES.article.retour]);
    assert.ok(bare(presenter.shown[2]?.body ?? "").includes(BOUTIQUE_TEXTES.article.boostPseudo), presenter.shown[2]?.body);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith(bare(renderBubble(BOUTIQUE_TEXTES.bulles.boostLance))), presenter.shown[3]?.body);
    assert.equal(api.calls[3] !== undefined && "itemId" in api.calls[3] ? api.calls[3].itemId : undefined, "boost-collectif");
  });
});

describe("parcours : le pass de saison", () => {
  const VERS_PALIER_2: Reply[] = [press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), press(PASS_TEXTES.paliers.page), press("Palier 2")];

  it("la porte du pass relit le pass : la saison, les dates, les points, la piste premium", async () => {
    const script = scripts(lectures(), { getPass: [ok(passState({ points: 150 }))] });
    const { menu, presenter, api } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS"]);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "getPass"]);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.includes("Saison 1"), body);
    assert.ok(body.includes(PASS_TEXTES.dates("05/11/2026", "31/01/2027")), body);
    assert.ok(body.includes(PASS_TEXTES.points("150")), body);
    assert.ok(body.includes(PASS_TEXTES.premium.inactif), body);
    assert.deepEqual(presenter.shown[2]?.buttons.map(nameOf), [PASS_TEXTES.paliers.page, PASS_TEXTES.passerPremium, PASS_TEXTES.retour]);
    assert.ok(bare(presenter.shown[2]?.buttons[1] ?? "").endsWith(PASS_TEXTES.prixPremium("250")), presenter.shown[2]?.buttons[1]);
  });

  it("les paliers disent l'état de chacun, la fiche d'un palier montre les deux pistes", async () => {
    const script = scripts(lectures(), { getPass: [ok(passState())] });
    const { menu, presenter } = setup([...VERS_PALIER_2, close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS", "PALIERS", "PALIER"]);
    const paliers = presenter.shown[3]?.buttons.map(bare) ?? [];
    // Le gratuit du palier 1 est réclamé, son cosmétique premium attend la piste premium.
    assert.ok(paliers[0]?.endsWith(PASS_TEXTES.etats.premiumRequis), paliers[0]);
    assert.ok(paliers[1]?.endsWith(PASS_TEXTES.etats.aReclamer), paliers[1]);
    assert.ok(paliers[2]?.endsWith(PASS_TEXTES.tuile.des("180")), paliers[2]);
    const fiche = bare(presenter.shown[4]?.body ?? "");
    assert.ok(fiche.includes(PASS_TEXTES.ligne("Gratuit", "1 Lingot")), fiche);
    assert.ok(fiche.includes(PASS_TEXTES.ligne("Premium", PASS_TEXTES.etats.aucune)), fiche);
    assert.deepEqual(presenter.shown[4]?.buttons.map(nameOf), [PASS_TEXTES.reclamer.GRATUIT, PASS_TEXTES.retour]);
  });

  it("réclamer : le service verse, le joueur lit ce qu'il a reçu, le pass relu montre le palier réclamé", async () => {
    const reclame = passState({ claimed: ["1-GRATUIT", "2-GRATUIT"] });
    const script = scripts(lectures(), {
      getPass: [ok(passState()), ok(reclame)],
      claimPass: [ok(passClaimed({ tier: 2, track: "GRATUIT", rewardLingots: 1, balances: { lingots: 9, gemmes: 500, jetons: 0, cles: 0 } }))],
    });
    const { menu, presenter, api, views } = setup([...VERS_PALIER_2, press(PASS_TEXTES.reclamer.GRATUIT), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS", "PALIERS", "PALIER", "PALIER"]);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "getPass", "claimPass", "getPass"]);
    assert.deepEqual(api.calls[4], { op: "claimPass", action: { requestId: "id-1", actor: XUID }, tier: 2, track: "GRATUIT" });
    assert.equal(views.get(XUID)?.balances.lingots, 9);
    const fiche = bare(presenter.shown[5]?.body ?? "");
    assert.ok(fiche.startsWith(bare(renderBubble(PASS_TEXTES.bulles.palierReclame))), fiche);
    assert.ok(fiche.includes(PASS_TEXTES.recu("1 Lingot")), fiche);
    assert.ok(fiche.includes(PASS_TEXTES.etats.reclame), fiche);
    assert.deepEqual(presenter.shown[5]?.buttons.map(nameOf), [PASS_TEXTES.retour]);
  });

  it("la lecture était périmée et le service refuse : le pass est relu, la fiche dit ce que le service sait", async () => {
    const script = scripts(lectures(), {
      getPass: [ok(passState()), ok(passState({ claimed: ["1-GRATUIT", "2-GRATUIT"] }))],
      claimPass: [refused("DEJA_RECLAME")],
    });
    const { menu, presenter, api } = setup([...VERS_PALIER_2, press(PASS_TEXTES.reclamer.GRATUIT), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "getPass", "claimPass", "getPass"]);
    const fiche = bare(presenter.shown[5]?.body ?? "");
    assert.ok(fiche.startsWith("Oooscar ["), fiche);
    assert.ok(fiche.includes(PASS_TEXTES.etats.reclame), fiche);
    assert.deepEqual(presenter.shown[5]?.buttons.map(nameOf), [PASS_TEXTES.retour]);
  });

  it("le service qui ne répond pas à la réclamation : la fiche reste, rien n'est relu", async () => {
    const script = scripts(lectures(), { getPass: [ok(passState())], claimPass: [unreachable()] });
    const { menu, presenter, api } = setup([...VERS_PALIER_2, press(PASS_TEXTES.reclamer.GRATUIT), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "getPass", "claimPass"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."), presenter.shown[5]?.body);
    assert.ok(presenter.shown[5]?.buttons.map(nameOf).includes(PASS_TEXTES.reclamer.GRATUIT), String(presenter.shown[5]?.buttons));
  });

  it("la piste premium d'un joueur premium se réclame, et le cosmétique reçu est nommé", async () => {
    const premium = passState({ premium: true, points: 200, claimed: [] });
    const script = scripts(lectures(), {
      getPass: [ok(premium), ok(passState({ premium: true, points: 200, claimed: ["3-PREMIUM"] }))],
      claimPass: [ok(passClaimed({ tier: 3, track: "PREMIUM", rewardLingots: 0, cosmeticId: "s1-lanterne" }))],
    });
    const replies = [
      press(MENU_TEXTES.boutique),
      press(BOUTIQUE_TEXTES.rayons.pass),
      press(PASS_TEXTES.paliers.page),
      press("Palier 3"),
      press(PASS_TEXTES.reclamer.PREMIUM),
      close,
    ];
    const { menu, presenter, api } = setup(replies, script);
    await menu.open(PLAYER);
    assert.deepEqual(api.calls[4], { op: "claimPass", action: { requestId: "id-1", actor: XUID }, tier: 3, track: "PREMIUM" });
    assert.ok(bare(presenter.shown[5]?.body ?? "").includes(PASS_TEXTES.recu("Lanterne de la saison 1")), presenter.shown[5]?.body);
  });

  it("passer premium : l'achat de pass-premium, puis le catalogue et le pass sont relus", async () => {
    const script = scripts(lectures(), {
      getPass: [ok(passState()), ok(passState({ premium: true }))],
      buyItem: [ok(balances({ gemmes: 250 }))],
      catalog: [ok(shopCatalog({ "pass-premium": { owned: true } }))],
    });
    const { menu, presenter, api, views } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), press(PASS_TEXTES.passerPremium), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS", "PASS"]);
    assert.deepEqual(ops(api.calls), ["catalog", "getPass", "todayVotes", "getPass", "buyItem", "catalog", "getPass"]);
    assert.deepEqual(api.calls[4], { op: "buyItem", action: { requestId: "id-1", actor: XUID }, itemId: "pass-premium", forXuid: undefined });
    assert.equal(gemmesDe(views), 250);
    const pass = bare(presenter.shown[3]?.body ?? "");
    assert.ok(pass.startsWith(bare(renderBubble(PASS_TEXTES.bulles.premiumActif))), pass);
    assert.ok(pass.includes(PASS_TEXTES.premium.actif), pass);
    assert.deepEqual(presenter.shown[3]?.buttons.map(nameOf), [PASS_TEXTES.paliers.page, PASS_TEXTES.retour]);
  });

  it("passer premium sans assez de Gemmes : la ligne des Gemmes, et le pass reste tel quel", async () => {
    const script = scripts(lectures(), { getPass: [ok(passState())], buyItem: [refused("PAS_ASSEZ_DE_GEMMES", 210)] });
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), press(PASS_TEXTES.passerPremium), close], script, { gemmes: 40 });
    await menu.open(PLAYER);
    const pass = bare(presenter.shown[3]?.body ?? "");
    assert.ok(pass.includes(BOUTIQUE_TEXTES.gemmesManquent), pass);
    assert.ok(pass.includes(PASS_TEXTES.premium.inactif), pass);
  });

  it("Retour remonte la fiche du palier, la liste, le pass, puis la boutique", async () => {
    const script = scripts(lectures(), { getPass: [ok(passState()), ok(passState())] });
    const replies = [...VERS_PALIER_2, press(PASS_TEXTES.retour), press(PASS_TEXTES.retour), press(PASS_TEXTES.retour), close];
    const { menu, presenter, api } = setup(replies, scripts(script, lectures()));
    await menu.open(PLAYER);
    // Chaque retour sur le pass et sur la boutique relit : la lecture de la boutique, du pass, du pass encore, de la boutique.
    assert.deepEqual(ops(api.calls).filter((op) => op === "getPass").length, 4);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS", "PALIERS", "PALIER", "PALIERS", "PASS", "BOUTIQUE"]);
  });

  it("le pass qui ne répond pas à l'ouverture : la boutique reste, la bulle le dit", async () => {
    // Le pass échoue à l'ouverture de la boutique, puis à celle de son écran : le joueur est renvoyé à la boutique, qui relit encore.
    const script: Script = {
      catalog: [ok(shopCatalog()), ok(shopCatalog())],
      getPass: [unreachable(), unreachable(), unreachable()],
      todayVotes: [ok(votesToday()), ok(votesToday())],
    };
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "BOUTIQUE"]);
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."), presenter.shown[2]?.body);
  });

  it("une relecture du pass qui échoue garde le dernier pass connu", async () => {
    const script = scripts(lectures(), { getPass: [unreachable()] });
    const { menu, presenter } = setup([press(MENU_TEXTES.boutique), press(BOUTIQUE_TEXTES.rayons.pass), close], script);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "BOUTIQUE", "PASS"]);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.startsWith("Oooscar [panique] Le service ne répond pas."), body);
    assert.ok(body.includes(PASS_TEXTES.points("120")), body);
  });
});
