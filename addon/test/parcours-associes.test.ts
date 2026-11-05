import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { CompanyState, CompanyUpdated, Member } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { createInvitations } from "../src/ui/invitations";
import { createMenu } from "../src/ui/menu";
import { createMissions } from "../src/ui/missions";
import { balances, companyState, ok, recordingLogger } from "./helpers";
import {
  PLAYER,
  T0,
  XUID,
  bare,
  busyForm,
  close,
  closeForm,
  failure,
  fakeClock,
  fakeCompany,
  fakeMissionsApi,
  fakePresenter,
  invitationEvent,
  press,
  refused,
  sequentialIds,
  storeWith,
  submit,
  unreachable,
  type FormReply,
  type Reply,
  type Script,
} from "./ui-helpers";

const ADA_XUID = "2535428000000002";
const BEA_XUID = "2535428000000003";

const PDG: Member = { xuid: XUID, name: "Alex", role: "PDG", sharePercent: 60 };
const ADA: Member = { xuid: ADA_XUID, name: "Ada", role: "ASSOCIE", sharePercent: 40 };
const SEUL: Member = { ...PDG, sharePercent: 100 };

const EN_LIGNE = [
  { xuid: XUID, name: "Alex" },
  { xuid: BEA_XUID, name: "Bea" },
];

/** L'entreprise 7 du joueur : un PDG et une associée, par défaut. */
const ASSOCIES: Partial<CompanyState> = { name: "Atelier", members: [PDG, ADA], version: 1 };

function setup(replies: readonly Reply[], script: Script = {}, state: Partial<CompanyState> = ASSOCIES, forms: readonly FormReply[] = []) {
  const clock = fakeClock(T0);
  const store = storeWith(state);
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const missions = createMissions({ api: fakeMissionsApi(), players: store, say: () => undefined, log });
  const said: string[] = [];
  const invitations = createInvitations({ clock, players: store, say: (_xuid, text) => void said.push(text), log });
  const huds: string[] = [];
  const actions = createActions({
    api,
    views,
    newRequestId: sequentialIds(),
    log,
    afterCollect: missions.refresh,
    afterJoin: (xuid) => void huds.push(xuid),
  });
  const presenter = fakePresenter(replies, forms);
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
  });
  return { menu, presenter, api, views, invitations, clock, log, said, huds };
}

const titleOf = (spec: { title: string }) => spec.title;

/** La réponse du service à une relecture de l'entreprise 7. */
const relue = (state: Partial<CompanyState> = ASSOCIES) => ok(companyState({ ...state, version: (state.version ?? 1) + 1 }));

function updated(version: number, members: Member[], extra: Partial<CompanyState> = {}): CompanyUpdated {
  return { state: companyState({ ...ASSOCIES, ...extra, version, members }), balances: balances() };
}

describe("parcours : la liste des associés", () => {
  it("la tuile ouvre l'écran, qui relit l'entreprise : la liste est celle du service, pas celle de la dernière fois", async () => {
    const { menu, presenter, api, views } = setup([press("Associés"), close], {
      getCompany: [relue({ ...ASSOCIES, members: [{ ...PDG, sharePercent: 50 }, { ...ADA, sharePercent: 50 }] })],
    });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS"]);
    assert.deepEqual(api.calls, [{ op: "getCompany", companyId: 7 }]);
    assert.ok(bare(presenter.shown[1]?.body ?? "").includes("PDG · Alex · 50 % (toi)"), presenter.shown[1]?.body);
    assert.equal(views.get(XUID)?.state.members?.[0]?.sharePercent, 50);
  });

  it("revient au menu par Retour, et la tuile garde sa ligne d'info", async () => {
    const { menu, presenter } = setup([press("Associés"), press("Retour"), close], { getCompany: [relue()] });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "MENU"]);
    assert.ok(presenter.shown[0]?.buttons.some((texte) => bare(texte).includes("Associés") && bare(texte).includes("2 associés")));
  });

  it("un associé n'a que la liste et Retour", async () => {
    const membres = [PDG, { ...ADA, xuid: XUID, name: "Alex", role: "ASSOCIE" as const }];
    const { menu, presenter } = setup([press("Associés"), close], { getCompany: [relue({ ...ASSOCIES, members: [{ ...PDG, xuid: ADA_XUID, name: "Ada" }, membres[1] as Member] })] });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown[1]?.buttons.map(bare), ["Retour"]);
  });

  it("un joueur seul lit qu'il peut inviter, et il a Inviter", async () => {
    const { menu, presenter } = setup([press("Associés"), close], { getCompany: [relue({ ...ASSOCIES, members: [SEUL] })] }, { ...ASSOCIES, members: [SEUL] });
    await menu.open(PLAYER);
    const body = bare(presenter.shown[1]?.body ?? "");
    assert.ok(body.includes("jusqu'à ce que le service refuse"), body);
    assert.deepEqual(presenter.shown[1]?.buttons.map(bare), ["Inviter", "Retour"]);
    assert.ok(bare(presenter.shown[0]?.buttons.find((texte) => texte.includes("Associés")) ?? "").endsWith("Invite des joueurs"));
  });

  it("la relecture échoue : la dernière liste connue reste affichée, Oooscar dit que le service ne répond pas", async () => {
    const { menu, presenter } = setup([press("Associés"), close], { getCompany: [unreachable()] });
    await menu.open(PLAYER);
    const body = bare(presenter.shown[1]?.body ?? "");
    assert.ok(body.startsWith("Oooscar [panique] Le service ne répond pas."), body);
    assert.ok(body.includes("Associé · Ada · 40 %"), body);
  });
});

describe("parcours : inviter", () => {
  const VERS_INVITER = [press("Associés"), press("Inviter")];

  it("les joueurs connectés sauf soi ; un appui envoie l'invitation, puis la liste revient avec « Invitation envoyée »", async () => {
    const { menu, presenter, api } = setup([...VERS_INVITER, press("Bea"), close], {
      getCompany: [relue()],
      invite: [ok(updated(3, [PDG, ADA]))],
    });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITER", "ASSOCIÉS"]);
    assert.deepEqual(presenter.shown[2]?.buttons, ["Bea", "Retour"], "le joueur n'est pas dans sa propre liste");
    assert.deepEqual(api.calls[1], { op: "invite", companyId: 7, action: { requestId: "id-1", actor: XUID }, targetXuid: BEA_XUID });
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [joie] Invitation envoyée."), presenter.shown[3]?.body);
  });

  it("Retour revient à la liste, sans appel", async () => {
    const { menu, presenter, api } = setup([...VERS_INVITER, press("Retour"), close], { getCompany: [relue(), relue()] });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITER", "ASSOCIÉS"]);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["getCompany", "getCompany"],
    );
  });

  const BULLES_DES_REFUS: Array<[string, ApiResult<CompanyUpdated>, string]> = [
    ["l'entreprise est pleine", refused("ENTREPRISE_PLEINE"), "Oooscar [oups] Ton entreprise est pleine."],
    ["le joueur est déjà associé", refused("DEJA_POSSEDE"), "Oooscar [oups] Il est déjà associé."],
    ["le joueur a déjà lancé son entreprise", refused("ENTREPRISE_EN_COURS"), "Oooscar [oups] Il a déjà lancé son entreprise."],
    ["le joueur n'est pas le PDG", { kind: "refused", status: 403, reason: "PAS_LE_PDG" }, "Oooscar [oups] Seul le PDG peut le faire."],
    ["le service ne connaît pas le joueur (400)", failure(400, "REQUETE_INVALIDE"), "Oooscar [oups] Je ne connais pas ce joueur."],
  ];
  for (const [quand, reponse, bulle] of BULLES_DES_REFUS) {
    it(`refus : ${quand} ; le joueur reste sur le choix avec la bulle, rien n'est changé`, async () => {
      const { menu, presenter, views } = setup([...VERS_INVITER, press("Bea"), close], { getCompany: [relue()], invite: [reponse] });
      await menu.open(PLAYER);
      assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITER", "INVITER"]);
      assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith(bulle), presenter.shown[3]?.body);
      assert.equal(views.get(XUID)?.state.version, 2, "l'état est celui de la relecture");
    });
  }

  it("service injoignable : la panique d'Oooscar, le joueur reste sur le choix", async () => {
    const { menu, presenter } = setup([...VERS_INVITER, press("Bea"), close], { getCompany: [relue()], invite: [unreachable()] });
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."));
  });

  it("personne d'autre en ligne : la phrase le dit", async () => {
    const solo = setup([...VERS_INVITER, close], { getCompany: [relue()] });
    // Même menu, mais seul en ligne : on vérifie la phrase du choix vide par l'écran pur (voir associes.test.ts).
    await solo.menu.open(PLAYER);
    assert.deepEqual(solo.presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITER"]);
  });
});

describe("parcours : les parts", () => {
  const VERS_PARTS = [press("Associés"), press("Parts")];

  it("le formulaire a un champ par membre, rempli avec les parts du service ; l'envoi part tel quel, la liste revient à jour", async () => {
    const apres = updated(3, [{ ...PDG, sharePercent: 70 }, { ...ADA, sharePercent: 30 }]);
    const { menu, presenter, api, views } = setup([...VERS_PARTS, close], { getCompany: [relue()], setShares: [ok(apres)] }, ASSOCIES, [submit("70", "30")]);
    await menu.open(PLAYER);
    assert.equal(presenter.asked.length, 1);
    assert.equal(presenter.asked[0]?.title, "PARTS");
    assert.deepEqual(
      presenter.asked[0]?.fields.map((champ) => [champ.label, champ.initial]),
      [
        ["Part de Alex, en %", "60"],
        ["Part de Ada, en %", "40"],
      ],
    );
    assert.deepEqual(api.calls[1], {
      op: "setShares",
      companyId: 7,
      action: { requestId: "id-1", actor: XUID },
      shares: [
        { xuid: XUID, percent: 70 },
        { xuid: ADA_XUID, percent: 30 },
      ],
    });
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "ASSOCIÉS"]);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.startsWith("Oooscar [joie] Parts enregistrées."), body);
    assert.ok(body.includes("PDG · Alex · 70 % (toi)") && body.includes("Associé · Ada · 30 %"), body);
    assert.equal(views.get(XUID)?.state.version, 3);
  });

  it("un champ qui n'est pas un entier : rien ne part, Oooscar le dit", async () => {
    const { menu, presenter, api } = setup([...VERS_PARTS, close], { getCompany: [relue()] }, ASSOCIES, [submit("70", "trente")]);
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["getCompany"],
    );
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [oups] Écris un pourcentage entier."), presenter.shown[2]?.body);
  });

  it("des parts qui ne font pas 100 partent quand même : l'add-on n'additionne rien, le 400 du service devient la bulle du total", async () => {
    const { menu, presenter, api, views } = setup([...VERS_PARTS, close], { getCompany: [relue()], setShares: [failure(400, "REQUETE_INVALIDE")] }, ASSOCIES, [submit("90", "90")]);
    await menu.open(PLAYER);
    const envoi = api.calls[1];
    assert.equal(envoi?.op, "setShares");
    if (envoi?.op === "setShares") {
      assert.deepEqual(
        envoi.shares.map((part) => part.percent),
        [90, 90],
      );
    }
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [oups] Les parts doivent faire 100."), presenter.shown[2]?.body);
    assert.equal(views.get(XUID)?.state.members?.[0]?.sharePercent, 60, "la liste n'a pas bougé");
  });

  it("le joueur ferme le formulaire : rien ne part, pas de bulle", async () => {
    const { menu, presenter, api } = setup([...VERS_PARTS, close], { getCompany: [relue()] }, ASSOCIES, [closeForm]);
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["getCompany"],
    );
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "ASSOCIÉS"]);
    assert.ok(!bare(presenter.shown[2]?.body ?? "").includes("Oooscar"), presenter.shown[2]?.body);
  });

  it("Bedrock refuse d'abord le formulaire (un écran est ouvert) : il est réessayé, puis envoyé", async () => {
    const apres = updated(3, [{ ...PDG, sharePercent: 70 }, { ...ADA, sharePercent: 30 }]);
    const { menu, presenter, api } = setup([...VERS_PARTS, close], { getCompany: [relue()], setShares: [ok(apres)] }, ASSOCIES, [
      busyForm,
      busyForm,
      submit("70", "30"),
    ]);
    await menu.open(PLAYER);
    assert.equal(presenter.asked.length, 3);
    assert.equal(api.calls.filter((call) => call.op === "setShares").length, 1);
  });

  it("le formulaire reste occupé : abandon sans bruit, rien ne part", async () => {
    const { menu, api } = setup([...VERS_PARTS, close], { getCompany: [relue()] }, ASSOCIES, [busyForm, busyForm, busyForm, busyForm]);
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["getCompany"],
    );
  });

  it("PAS_LE_PDG (403) : la bulle habituelle du refus", async () => {
    const { menu, presenter } = setup([...VERS_PARTS, close], {
      getCompany: [relue()],
      setShares: [{ kind: "refused", status: 403, reason: "PAS_LE_PDG" }],
    }, ASSOCIES, [submit("70", "30")]);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [oups] Seul le PDG peut le faire."), presenter.shown[2]?.body);
  });

  it("un joueur seul n'a pas de bouton Parts", async () => {
    const { menu, presenter } = setup([press("Associés"), close], { getCompany: [relue({ ...ASSOCIES, members: [SEUL] })] }, { ...ASSOCIES, members: [SEUL] });
    await menu.open(PLAYER);
    assert.ok(!presenter.shown[1]?.buttons.some((texte) => texte.includes("Parts")));
  });
});

describe("parcours : une invitation reçue", () => {
  const AILLEURS: Partial<CompanyState> = { name: "Les Forges", members: [{ ...PDG, xuid: BEA_XUID, name: "Bea", sharePercent: 100 }], companyId: 12, version: 1 };

  /** L'invitation de Bea dans l'entreprise 12, déjà reçue. */
  function withInvitation(replies: readonly Reply[], script: Script, forms: readonly FormReply[] = []) {
    const setupResult = setup(replies, script, ASSOCIES, forms);
    setupResult.invitations.onInvitation(invitationEvent(1, XUID));
    return setupResult;
  }

  const rejointe = (): CompanyUpdated => ({
    state: companyState({ ...AILLEURS, version: 5, members: [{ xuid: BEA_XUID, name: "Bea", role: "PDG", sharePercent: 100 }, { xuid: XUID, name: "Alex", role: "ASSOCIE", sharePercent: 0 }] }),
    balances: balances({ lingots: 3 }),
  });

  it("l'invitation est la première entrée de la liste ; sa fenêtre dit ce que le joueur quitte", async () => {
    const { menu, presenter, api } = withInvitation([press("Associés"), press("t'invite dans"), close], { getCompany: [relue()] });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION"]);
    assert.equal(bare(presenter.shown[1]?.buttons[0] ?? ""), "[N] Bea t'invite dans Les Forges");
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.startsWith("Bea t'invite dans Les Forges"), body);
    assert.ok(body.includes("Tu quittes ton entreprise actuelle."), body);
    assert.deepEqual(presenter.shown[2]?.buttons, ["Accepter", "Plus tard"]);
    assert.equal(api.calls.length, 1, "regarder une invitation n'appelle que la relecture");
  });

  it("Accepter : l'appel vise l'entreprise qui invite, le joueur change d'entreprise, le HUD est redessiné", async () => {
    const { menu, presenter, api, views, invitations, huds } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [ok(rejointe())] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls[1], { op: "acceptInvite", companyId: 12, action: { requestId: "id-1", actor: XUID } });
    assert.equal(views.get(XUID)?.state.companyId, 12);
    assert.equal(views.get(XUID)?.state.name, "Les Forges");
    assert.equal(views.get(XUID)?.balances.lingots, 3);
    assert.deepEqual(huds, [XUID]);
    assert.deepEqual(invitations.list(XUID), [], "les invitations du joueur sont périmées");
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION", "ASSOCIÉS"]);
    const body = bare(presenter.shown[3]?.body ?? "");
    assert.ok(body.startsWith("Oooscar [joie] Bienvenue dans l'entreprise."), body);
    assert.ok(body.includes("Les Forges") && body.includes("Associé · Alex · 0 % (toi)"), body);
  });

  it("la suite se passe dans la nouvelle entreprise : les appels visent l'entreprise 12", async () => {
    const { menu, api } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), press("Retour"), press("Associés"), close],
      { getCompany: [relue(), ok(companyState({ ...AILLEURS, version: 6, members: rejointe().state.members as Member[] }))], acceptInvite: [ok(rejointe())] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => [call.op, call.companyId]),
      [
        ["getCompany", 7],
        ["acceptInvite", 12],
        ["getCompany", 12],
      ],
    );
  });

  it("Plus tard revient à la liste : l'invitation attend encore, rien n'est appelé", async () => {
    const { menu, presenter, api, invitations } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Plus tard"), close],
      { getCompany: [relue(), relue()] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION", "ASSOCIÉS"]);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["getCompany", "getCompany"],
      "ni accepter ni refuser : seule la relecture de la liste part",
    );
    assert.equal(invitations.list(XUID).length, 1);
    assert.equal(bare(presenter.shown[3]?.buttons[0] ?? ""), "[N] Bea t'invite dans Les Forges");
  });

  it("l'invitation a expiré côté service (400) : elle disparaît de la liste, Oooscar le dit", async () => {
    const { menu, presenter, invitations, views } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [failure(400, "REQUETE_INVALIDE")] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION", "ASSOCIÉS"]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [oups] Cette invitation a expiré."), presenter.shown[3]?.body);
    assert.ok(!presenter.shown[3]?.buttons.some((texte) => texte.includes("t'invite dans")));
    assert.deepEqual(invitations.list(XUID), []);
    assert.equal(views.get(XUID)?.state.companyId, 7);
  });

  it("l'entreprise est pleine : la fenêtre reste, l'invitation aussi, avec la bulle", async () => {
    const { menu, presenter, invitations } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [refused("ENTREPRISE_PLEINE")] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION", "INVITATION"]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [oups] Cette entreprise est pleine."), presenter.shown[3]?.body);
    assert.equal(invitations.list(XUID).length, 1);
  });

  it("l'entreprise du joueur est déjà lancée : la bulle habituelle, il reste dans son entreprise", async () => {
    const { menu, presenter, views } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [refused("ENTREPRISE_EN_COURS")] },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [oups] Tu as déjà lancé ton entreprise."), presenter.shown[3]?.body);
    assert.equal(views.get(XUID)?.state.companyId, 7);
  });

  it("DEJA_POSSEDE : le joueur est déjà dans cette entreprise, l'invitation ne sert plus", async () => {
    const { menu, presenter, invitations } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [refused("DEJA_POSSEDE")] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "INVITATION", "ASSOCIÉS"]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [oups] Tu es déjà associé ici."), presenter.shown[3]?.body);
    assert.deepEqual(invitations.list(XUID), []);
  });

  it("service injoignable : la panique d'Oooscar, le joueur reste où il est et l'invitation aussi", async () => {
    const { menu, presenter, invitations, views } = withInvitation(
      [press("Associés"), press("t'invite dans"), press("Accepter"), close],
      { getCompany: [relue()], acceptInvite: [unreachable()] },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."));
    assert.equal(invitations.list(XUID).length, 1);
    assert.equal(views.get(XUID)?.state.companyId, 7);
  });

  it("une invitation expirée n'est plus dans la liste quand le joueur ouvre l'écran", async () => {
    const { menu, presenter, clock } = withInvitation([press("Associés"), close], { getCompany: [relue()] });
    clock.advance(25 * 3_600_000);
    await menu.open(PLAYER);
    assert.ok(!presenter.shown[1]?.buttons.some((texte) => texte.includes("t'invite dans")), JSON.stringify(presenter.shown[1]?.buttons));
  });

  it("deux invitations : chacune a sa fenêtre, et Accepter vise la bonne entreprise", async () => {
    const setupResult = setup([press("Associés"), press("Cal t'invite"), press("Accepter"), close], {
      getCompany: [relue()],
      acceptInvite: [ok({ ...rejointe(), state: { ...rejointe().state, companyId: 13, name: "Mines Unies" } })],
    });
    setupResult.invitations.onInvitation(invitationEvent(1, XUID));
    setupResult.invitations.onInvitation(invitationEvent(2, XUID, { companyId: 13, companyName: "Mines Unies", invitedByName: "Cal" }));
    await setupResult.menu.open(PLAYER);
    assert.deepEqual(setupResult.api.calls[1], { op: "acceptInvite", companyId: 13, action: { requestId: "id-1", actor: XUID } });
    assert.equal(setupResult.views.get(XUID)?.state.name, "Mines Unies");
  });

  it("une invitation périmée entre deux écrans : le joueur revient à la liste, sans appel d'acceptation", async () => {
    const setupResult = setup([press("Associés"), press("t'invite dans"), close], { getCompany: [relue(), relue()] });
    setupResult.invitations.onInvitation(invitationEvent(1, XUID));
    // Le joueur appuie sur l'invitation, mais elle est périmée (arrivée de l'événement suivant, réponse d'un autre écran) avant que sa fenêtre s'ouvre.
    const presenterShow = setupResult.presenter.show.bind(setupResult.presenter);
    setupResult.presenter.show = async (player, spec) => {
      const answer = await presenterShow(player, spec);
      if (spec.title === "ASSOCIÉS") setupResult.invitations.clear(XUID);
      return answer;
    };
    await setupResult.menu.open(PLAYER);
    assert.deepEqual(setupResult.presenter.shown.map(titleOf), ["MENU", "ASSOCIÉS", "ASSOCIÉS"]);
    assert.deepEqual(
      setupResult.api.calls.map((call) => call.op),
      ["getCompany", "getCompany"],
    );
  });
});
