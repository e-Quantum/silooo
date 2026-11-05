import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CompanyState, Member } from "../src/protocol";
import {
  associesDetail,
  associesNotice,
  associesScreen,
  invitationScreen,
  inviterScreen,
  isRejectedRequest,
  membersOf,
  partsForm,
  readShares,
} from "../src/ui/associes";
import type { Invitation } from "../src/ui/invitations";
import { ASSOCIES_TEXTES, BULLES, MENU_TEXTES, USINES_TEXTES } from "../src/ui/textes";
import { mainScreen } from "../src/ui/menu";
import { missionState } from "./helpers";
import { XUID, bare, fakeClock, viewsWith } from "./ui-helpers";

const ADA_XUID = "2535428000000002";
const BEA_XUID = "2535428000000003";

const PDG: Member = { xuid: XUID, name: "Alex", role: "PDG", sharePercent: 60 };
const ADA: Member = { xuid: ADA_XUID, name: "Ada", role: "ASSOCIE", sharePercent: 40 };

function viewOf(state: Partial<CompanyState> = {}) {
  const view = viewsWith({ name: "Les Forges", ...state }, fakeClock(0)).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

const FORGES: Invitation = { companyId: 12, companyName: "Les Forges", invitedBy: BEA_XUID, invitedByName: "Bea", expiresAtMs: 1 };
const MINES: Invitation = { companyId: 13, companyName: "Mines Unies", invitedBy: ADA_XUID, invitedByName: "Cal", expiresAtMs: 1 };

const linesOf = (body: string): string[] => bare(body).split("\n");

describe("membersOf et la tuile du menu", () => {
  it("les membres sont ceux du service, dans son ordre ; absents, la liste est vide", () => {
    assert.deepEqual(membersOf(viewOf({ members: [PDG, ADA] })), [PDG, ADA]);
    assert.deepEqual(membersOf(viewOf()), []);
  });

  it("la ligne d'info dit le nombre d'associés d'après la liste, ou invite à en chercher", () => {
    assert.equal(associesDetail(viewOf()), "Invite des joueurs");
    assert.equal(associesDetail(viewOf({ members: [PDG] })), "Invite des joueurs");
    assert.equal(associesDetail(viewOf({ members: [PDG, ADA] })), "2 associés");
    assert.equal(associesDetail(viewOf({ members: [PDG, ADA, { ...ADA, xuid: "9", name: "Bo" }] })), "3 associés");
  });

  it("la tuile du menu est juste avant « Amis », active, avec sa ligne d'info", () => {
    const screen = mainScreen(viewOf({ members: [PDG, ADA] }), missionState());
    const noms = screen.spec.buttons.map((texte) => bare(texte));
    const index = noms.findIndex((texte) => texte.includes(MENU_TEXTES.associes));
    assert.ok(index > 0, noms.join("|"));
    assert.ok(noms[index]?.endsWith("2 associés"), noms[index]);
    assert.ok(noms[index + 1]?.includes("Amis"), "Associés est juste avant Amis");
    assert.deepEqual(screen.targets[index], { kind: "go", route: { screen: "associes" } });
  });
});

describe("l'écran des associés", () => {
  it("le PDG voit le nom de l'entreprise, chaque membre (rôle, nom, part) et se reconnaît ; Inviter, Parts, Retour", () => {
    const screen = associesScreen(viewOf({ members: [PDG, ADA] }), XUID, []);
    assert.equal(screen.spec.title, "ASSOCIÉS");
    assert.deepEqual(linesOf(screen.spec.body), ["Les Forges", "PDG · Alex · 60 % (toi)", "Associé · Ada · 40 %"]);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Inviter", "Parts", "Retour"]);
    assert.deepEqual(screen.targets, [
      { kind: "go", route: { screen: "inviter", page: 0 } },
      { kind: "parts" },
      { kind: "go", route: { screen: "main" } },
    ]);
  });

  it("un associé a la liste et Retour, rien d'autre ; il se reconnaît aussi", () => {
    const screen = associesScreen(viewOf({ members: [PDG, ADA] }), ADA_XUID, []);
    assert.deepEqual(linesOf(screen.spec.body), ["Les Forges", "PDG · Alex · 60 %", "Associé · Ada · 40 % (toi)"]);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Retour"]);
  });

  it("les membres sont montrés dans l'ordre du service, pas triés par l'add-on", () => {
    const autre: Member = { xuid: "9", name: "Zoé", role: "ASSOCIE", sharePercent: 5 };
    const screen = associesScreen(viewOf({ members: [PDG, autre, ADA] }), XUID, []);
    assert.deepEqual(linesOf(screen.spec.body).slice(1), ["PDG · Alex · 60 % (toi)", "Associé · Zoé · 5 %", "Associé · Ada · 40 %"]);
  });

  it("un joueur seul lit qu'il peut inviter jusqu'à ce que le service refuse, sans aucun chiffre ni maximum", () => {
    const screen = associesScreen(viewOf({ members: [{ ...PDG, sharePercent: 100 }] }), XUID, []);
    const lignes = linesOf(screen.spec.body);
    assert.deepEqual(lignes, ["Les Forges", "PDG · Alex · 100 % (toi)", "", ASSOCIES_TEXTES.seul]);
    assert.doesNotMatch(ASSOCIES_TEXTES.seul, /\d/);
    assert.match(ASSOCIES_TEXTES.seul, /jusqu'à ce que le service refuse/);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Inviter", "Retour"], "rien à répartir : pas de Parts");
  });

  it("sans liste du service, le nom de l'entreprise reste et Inviter est proposé", () => {
    const screen = associesScreen(viewOf(), XUID, []);
    assert.deepEqual(linesOf(screen.spec.body), ["Les Forges"]);
    assert.deepEqual(screen.spec.buttons.map(bare), ["Inviter", "Retour"]);
  });

  it("les parts sont montrées telles que le service les donne, même si elles ne font pas 100 : rien n'est additionné", () => {
    const screen = associesScreen(viewOf({ members: [{ ...PDG, sharePercent: 10 }, { ...ADA, sharePercent: 10 }] }), XUID, []);
    assert.deepEqual(linesOf(screen.spec.body).slice(1), ["PDG · Alex · 10 % (toi)", "Associé · Ada · 10 %"]);
    assert.doesNotMatch(bare(screen.spec.body), /total|somme|100|reste/i);
  });

  it("les invitations reçues passent en premier : « <nom> t'invite dans <entreprise> », chacune vers sa fenêtre", () => {
    const screen = associesScreen(viewOf({ members: [PDG] }), XUID, [FORGES, MINES]);
    assert.deepEqual(screen.spec.buttons.map(bare), [
      "[N] Bea t'invite dans Les Forges",
      "[N] Cal t'invite dans Mines Unies",
      "Inviter",
      "Retour",
    ]);
    assert.deepEqual(screen.targets.slice(0, 2), [
      { kind: "go", route: { screen: "invitation", companyId: 12 } },
      { kind: "go", route: { screen: "invitation", companyId: 13 } },
    ]);
  });

  it("un associé voit aussi ses invitations", () => {
    const screen = associesScreen(viewOf({ members: [PDG, ADA] }), ADA_XUID, [FORGES]);
    assert.deepEqual(screen.spec.buttons.map(bare), ["[N] Bea t'invite dans Les Forges", "Retour"]);
  });

  it("une bulle d'Oooscar passe avant le reste", () => {
    const screen = associesScreen(viewOf({ members: [PDG] }), XUID, [], { bubble: BULLES.bientot });
    assert.ok(bare(screen.spec.body).startsWith("Oooscar ["), screen.spec.body);
    assert.ok(bare(screen.spec.body).includes("Les Forges"));
  });
});

describe("le choix du joueur à inviter", () => {
  const EN_LIGNE = [
    { xuid: XUID, name: "Alex" },
    { xuid: BEA_XUID, name: "Bea" },
    { xuid: ADA_XUID, name: "Ada" },
  ];

  it("liste les joueurs connectés par leur nom, sauf le joueur lui-même, dans l'ordre reçu", () => {
    const screen = inviterScreen(EN_LIGNE, XUID, 0, 8);
    assert.equal(screen.spec.title, "INVITER");
    assert.deepEqual(linesOf(screen.spec.body), ["Choisis un joueur connecté."]);
    assert.deepEqual(screen.spec.buttons, ["Bea", "Ada", "Retour"]);
    assert.deepEqual(screen.targets, [
      { kind: "invite", targetXuid: BEA_XUID },
      { kind: "invite", targetXuid: ADA_XUID },
      { kind: "go", route: { screen: "associes" } },
    ]);
  });

  it("personne d'autre en ligne : une phrase et Retour", () => {
    const screen = inviterScreen([{ xuid: XUID, name: "Alex" }], XUID, 0, 8);
    assert.deepEqual(linesOf(screen.spec.body), ["Aucun autre joueur connecté."]);
    assert.deepEqual(screen.spec.buttons, ["Retour"]);
    assert.deepEqual(inviterScreen([], XUID, 0, 8).spec.buttons, ["Retour"]);
  });

  it("des pages quand il y a trop de joueurs : numéro de page, Suite, Précédent vers la bonne page", () => {
    const beaucoup = [{ xuid: XUID, name: "Alex" }, ...Array.from({ length: 5 }, (_, i) => ({ xuid: `30${i}`, name: `Joueur${i}` }))];
    const premiere = inviterScreen(beaucoup, XUID, 0, 2);
    assert.deepEqual(premiere.spec.buttons, ["Joueur0", "Joueur1", USINES_TEXTES.suite, "Retour"]);
    assert.ok(linesOf(premiere.spec.body).includes(USINES_TEXTES.page(1, 3)), premiere.spec.body);
    assert.deepEqual(premiere.targets[2], { kind: "go", route: { screen: "inviter", page: 1 } });
    const derniere = inviterScreen(beaucoup, XUID, 9, 2);
    assert.deepEqual(derniere.spec.buttons, ["Joueur4", USINES_TEXTES.precedent, "Retour"]);
    assert.deepEqual(derniere.targets[1], { kind: "go", route: { screen: "inviter", page: 1 } });
  });

  it("une bulle d'Oooscar passe avant le reste", () => {
    const screen = inviterScreen(EN_LIGNE, XUID, 0, 8, { bubble: BULLES.bientot });
    assert.ok(bare(screen.spec.body).startsWith("Oooscar ["), screen.spec.body);
  });
});

describe("la fenêtre d'une invitation", () => {
  it("dit qui invite dans quelle entreprise et ce que le joueur quitte ; Accepter vise l'entreprise qui invite, Plus tard revient", () => {
    const screen = invitationScreen([FORGES, MINES], 13);
    assert.ok(screen);
    assert.equal(screen.spec.title, "INVITATION");
    assert.deepEqual(linesOf(screen.spec.body), ["Cal t'invite dans Mines Unies", "", ASSOCIES_TEXTES.invitation.consequence]);
    assert.deepEqual(screen.spec.buttons, ["Accepter", "Plus tard"]);
    assert.deepEqual(screen.targets, [
      { kind: "accept", companyId: 13 },
      { kind: "go", route: { screen: "associes" } },
    ]);
  });

  it("une invitation qui a disparu n'a pas de fenêtre", () => {
    assert.equal(invitationScreen([FORGES], 99), undefined);
    assert.equal(invitationScreen([], 12), undefined);
  });
});

describe("le formulaire des parts", () => {
  const MEMBRES = [PDG, ADA];

  it("un champ par membre, dans l'ordre du service, rempli avec la part que le service a donnée", () => {
    const form = partsForm(MEMBRES);
    assert.equal(form.title, "PARTS");
    assert.equal(form.submit, "Enregistrer");
    assert.deepEqual(form.fields, [
      { label: "Part de Alex, en %", placeholder: "Un nombre entier", initial: "60" },
      { label: "Part de Ada, en %", placeholder: "Un nombre entier", initial: "40" },
    ]);
  });

  it("readShares : un entier de 0 à 100 par champ, rendu par XUID dans l'ordre de la liste", () => {
    assert.deepEqual(readShares(MEMBRES, ["70", "30"]), [
      { xuid: XUID, percent: 70 },
      { xuid: ADA_XUID, percent: 30 },
    ]);
    assert.deepEqual(readShares(MEMBRES, [" 0 ", "100"]), [
      { xuid: XUID, percent: 0 },
      { xuid: ADA_XUID, percent: 100 },
    ]);
  });

  it("n'additionne rien : des parts qui ne font pas 100 sont rendues telles quelles, c'est le service qui juge", () => {
    assert.deepEqual(readShares(MEMBRES, ["90", "90"]), [
      { xuid: XUID, percent: 90 },
      { xuid: ADA_XUID, percent: 90 },
    ]);
    assert.deepEqual(readShares(MEMBRES, ["0", "0"]), [
      { xuid: XUID, percent: 0 },
      { xuid: ADA_XUID, percent: 0 },
    ]);
  });

  it("un champ qui n'est pas un entier de 0 à 100 donne undefined : rien ne part", () => {
    for (const mauvais of ["", "  ", "abc", "12.5", "12,5", "-1", "101", "1e2", "+5", "0x10", "1 000", "٣"]) {
      assert.equal(readShares(MEMBRES, ["50", mauvais]), undefined, `« ${mauvais} »`);
      assert.equal(readShares(MEMBRES, [mauvais, "50"]), undefined, `« ${mauvais} »`);
    }
  });

  it("moins de valeurs que de membres : undefined", () => {
    assert.equal(readShares(MEMBRES, ["100"]), undefined);
    assert.equal(readShares(MEMBRES, []), undefined);
  });
});

describe("les bulles propres à l'entreprise partagée", () => {
  const refus = (reason: string) => ({ kind: "refused", reason }) as const;
  const quatreCents = { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" } as const;

  it("inviter : DEJA_POSSEDE et ENTREPRISE_EN_COURS ont les mots de l'invitation", () => {
    assert.deepEqual(associesNotice(refus("DEJA_POSSEDE"), "invite"), { bubble: ASSOCIES_TEXTES.bulles.dejaAssocie });
    assert.deepEqual(associesNotice(refus("ENTREPRISE_EN_COURS"), "invite"), { bubble: ASSOCIES_TEXTES.bulles.ilALance });
  });

  it("accepter : DEJA_POSSEDE et ENTREPRISE_PLEINE ont les mots de l'acceptation", () => {
    assert.deepEqual(associesNotice(refus("DEJA_POSSEDE"), "acceptInvite"), { bubble: ASSOCIES_TEXTES.bulles.tuEsDejaAssocie });
    assert.deepEqual(associesNotice(refus("ENTREPRISE_PLEINE"), "acceptInvite"), { bubble: ASSOCIES_TEXTES.bulles.entreprisePleine });
  });

  it("les autres refus gardent la bulle habituelle : le menu la prend (undefined ici)", () => {
    assert.equal(associesNotice(refus("ENTREPRISE_PLEINE"), "invite"), undefined);
    assert.equal(associesNotice(refus("ENTREPRISE_EN_COURS"), "acceptInvite"), undefined);
    assert.equal(associesNotice(refus("PAS_LE_PDG"), "invite"), undefined);
    assert.equal(associesNotice(refus("PAS_LE_PDG"), "setShares"), undefined);
    assert.equal(associesNotice(refus("CONFLIT_REESSAYER"), "acceptInvite"), undefined);
    assert.equal(associesNotice(refus("DEJA_POSSEDE"), "setShares"), undefined);
  });

  it("un 400 du service : le joueur est inconnu, les parts ne font pas le total, l'invitation a expiré", () => {
    assert.equal(isRejectedRequest(quatreCents), true);
    assert.deepEqual(associesNotice(quatreCents, "invite"), { bubble: ASSOCIES_TEXTES.bulles.joueurInconnu });
    assert.deepEqual(associesNotice(quatreCents, "setShares"), { bubble: ASSOCIES_TEXTES.bulles.partsTotal });
    assert.deepEqual(associesNotice(quatreCents, "acceptInvite"), { bubble: ASSOCIES_TEXTES.bulles.invitationExpiree });
  });

  it("une autre erreur du service, une panne ou une action réussie ne sont pas un 400", () => {
    for (const autre of [
      { kind: "failed", failure: "error", reason: "ERREUR_INTERNE" },
      { kind: "failed", failure: "network", reason: "RESEAU" },
      { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" },
      { kind: "invited" },
      { kind: "busy" },
    ] as const) {
      assert.equal(isRejectedRequest(autre), false, autre.kind);
      assert.equal(associesNotice(autre, "setShares"), undefined, autre.kind);
    }
  });

  it("la bulle des parts dit le total que le contrat attend, et aucune bulle ne dépasse six mots", () => {
    assert.match(ASSOCIES_TEXTES.bulles.partsTotal.text, /100/);
    for (const bulle of Object.values(ASSOCIES_TEXTES.bulles)) assert.ok(bulle.text.split(/\s+/).length <= 6, bulle.text);
  });
});
