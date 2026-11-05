import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CandidatesOutcome } from "../src/ui/actions";
import type { Candidate, CompanyState, CompanyUpdated, StaffMember } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { COLOR, ICON, RARITY_COLOR } from "../src/ui/apparence";
import type { Screen } from "../src/ui/ecran";
import { createViewStore, type CompanyView } from "../src/ui/etat";
import { createMenu, ficheScreen, mainScreen, noticeOf } from "../src/ui/menu";
import { createMissions } from "../src/ui/missions";
import { formatAmount } from "../src/ui/nombres";
import {
  affecterScreen,
  agenceScreen,
  candidatScreen,
  embaucheScreen,
  equipeScreen,
  membreScreen,
  personnelScreen,
  renvoiScreen,
  teamLine,
} from "../src/ui/personnel";
import {
  AGENCE_TEXTES,
  BULLES,
  FACTORY_NAMES,
  FICHE_TEXTES,
  MENU_TEXTES,
  PERSONNEL_TEXTES,
  refusalBubble,
  renderBubble,
  staffRefusalBubble,
} from "../src/ui/textes";
import { balances, candidate, companyState, factory, missionState, ok, recordingLogger, staffMember } from "./helpers";
import {
  PLAYER,
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
  viewsWith,
  type Reply,
  type Script,
} from "./ui-helpers";

const IDS = Object.keys(FACTORY_NAMES);

const CARRIERE = factory({ factoryId: "carriere", level: 3 });
const SCIERIE = factory({ factoryId: "scierie", level: 2 });

const ADELE = staffMember();
const BRUNO = staffMember({ staffId: 12, name: "Bruno", role: "CHEF", rarity: "RARE", bonusPercent: 25, salaryPerHour: 360, skinId: "chef-casque" });
const CHLOE = staffMember({ staffId: 13, name: "Chloé", rarity: "EPIQUE", bonusPercent: 40, salaryPerHour: 0, factoryId: null });

function viewOf(state: Partial<CompanyState> = {}): CompanyView {
  const view = viewsWith(state, fakeClock(0)).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

function lookupOf(...candidates: Candidate[]): CandidatesOutcome {
  return { kind: "candidates", candidates };
}

/** Les types de cible de chaque bouton d'un écran, dans l'ordre. */
function kindsOf(screen: Screen): string[] {
  return screen.targets.map((target) => (target.kind === "go" ? `go:${target.route.screen}` : target.kind));
}

const labelsOf = (screen: Screen): string[] => screen.spec.buttons.map(bare);

function must<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("écran absent");
  return value;
}

const TEAM = { factories: [CARRIERE, SCIERIE], staff: [ADELE, BRUNO, CHLOE] };

// ---------------------------------------------------------------- la ligne d'équipe de la fiche d'usine

describe("la ligne d'équipe de la fiche d'usine", () => {
  it("rien quand personne ne travaille dans l'usine", () => {
    assert.equal(teamLine(viewOf(TEAM), "scierie"), undefined);
    assert.equal(teamLine(viewOf({ factories: [CARRIERE] }), "carriere"), undefined);
  });

  it("le nombre de membres de l'usine, tel que le service les donne, et s'il y a un chef", () => {
    const view = viewOf(TEAM);
    assert.equal(teamLine(view, "carriere"), FICHE_TEXTES.equipe(MENU_TEXTES.nombreMembres(2), true));
    assert.equal(bare(teamLine(view, "carriere") ?? ""), "Équipe : 2 membres, avec chef");
  });

  it("un seul simple salarié : un membre, sans chef", () => {
    const view = viewOf({ factories: [CARRIERE], staff: [ADELE] });
    assert.equal(teamLine(view, "carriere"), "Équipe : 1 membre, sans chef");
  });

  it("un directeur compte comme un chef : tout ce qui n'est pas un simple salarié", () => {
    const view = viewOf({ factories: [CARRIERE], staff: [staffMember({ role: "DIRECTEUR" })] });
    assert.equal(teamLine(view, "carriere"), "Équipe : 1 membre, avec chef");
  });

  it("les membres sans usine et ceux des autres usines ne comptent pas", () => {
    const view = viewOf({ factories: [CARRIERE, SCIERIE], staff: [ADELE, CHLOE, staffMember({ staffId: 14, factoryId: "scierie" })] });
    assert.equal(teamLine(view, "carriere"), "Équipe : 1 membre, sans chef");
  });

  it("sans liste du personnel (le contrat ne l'exige pas), il n'y a pas de ligne", () => {
    assert.equal(teamLine(viewOf({ factories: [CARRIERE] }), "carriere"), undefined);
  });

  it("la fiche d'une usine montre la ligne après les infos, et seulement si elle existe", () => {
    const avec = must(ficheScreen(viewOf(TEAM), "carriere", 0));
    assert.ok(bare(avec.spec.body).split("\n").includes("Équipe : 2 membres, avec chef"), avec.spec.body);
    const sans = must(ficheScreen(viewOf(TEAM), "scierie", 0));
    assert.ok(!bare(sans.spec.body).includes("Équipe"), sans.spec.body);
    assert.equal(avec.spec.buttons.length, sans.spec.buttons.length, "la ligne d'équipe ne change pas les boutons");
  });
});

// ---------------------------------------------------------------- la tuile du menu

describe("menu principal, tuile Personnel", () => {
  it("dit combien de membres le service a donnés, après Prestige", () => {
    const screen = mainScreen(viewOf(TEAM), undefined);
    const text = bare(screen.spec.buttons[5] ?? "");
    assert.equal(text, `${ICON.personnel} Personnel\n3 membres`);
    assert.deepEqual(screen.targets[5], { kind: "go", route: { screen: "personnel", page: 0 } });
  });

  it("sans liste du personnel : aucun membre", () => {
    const screen = mainScreen(viewOf({ factories: [CARRIERE] }), undefined);
    assert.equal(bare(screen.spec.buttons[5] ?? ""), `${ICON.personnel} Personnel\nAucun membre`);
  });
});

// ---------------------------------------------------------------- l'écran Personnel

describe("écran Personnel", () => {
  it("l'Agence et les associés d'abord, puis une tuile par équipe qui a du monde (usines dans l'ordre du service, puis sans usine), puis Retour", () => {
    const screen = personnelScreen(viewOf(TEAM), 0, 8);
    assert.equal(screen.spec.title, PERSONNEL_TEXTES.titre);
    assert.deepEqual(labelsOf(screen), [
      `${ICON.agence} Agence\nLes candidats du jour`,
      `${ICON.associes} Associés\nInvite des joueurs`,
      `${ICON.usine} Carrière\n2 membres`,
      `${ICON.personnel} Sans usine\n1 membre`,
      "Retour",
    ]);
    assert.deepEqual(kindsOf(screen), ["go:agence", "go:associes", "go:equipe", "go:equipe", "go:main"]);
    assert.deepEqual(screen.targets[2], { kind: "go", route: { screen: "equipe", factoryId: "carriere", page: 0 } });
    assert.deepEqual(screen.targets[3], { kind: "go", route: { screen: "equipe", factoryId: null, page: 0 } });
  });

  it("une usine sans personne n'a pas de tuile", () => {
    const screen = personnelScreen(viewOf(TEAM), 0, 8);
    assert.ok(!labelsOf(screen).some((label) => label.includes("Scierie")));
  });

  it("une usine que l'entreprise ne montre pas mais où le service a mis quelqu'un a sa tuile, avant « Sans usine »", () => {
    const view = viewOf({ factories: [CARRIERE], staff: [CHLOE, staffMember({ staffId: 15, factoryId: "usine-neuve" })] });
    const screen = personnelScreen(view, 0, 8);
    assert.deepEqual(labelsOf(screen).slice(2, 4), [`${ICON.usine} Usine neuve\n1 membre`, `${ICON.personnel} Sans usine\n1 membre`]);
  });

  it("l'état de l'usine donne le repère de sa tuile : une usine en ruine garde son cadenas", () => {
    const view = viewOf({ factories: [factory({ factoryId: "carriere", state: "EN_RUINE", level: 0 })], staff: [ADELE] });
    assert.ok(bare(personnelScreen(view, 0, 8).spec.buttons[2] ?? "").startsWith(`${ICON.cadenas} Carrière`));
  });

  it("personne dans l'équipe : la phrase, le mot « personne », l'Agence, les associés et Retour", () => {
    const screen = personnelScreen(viewOf({ factories: [CARRIERE] }), 0, 8);
    assert.deepEqual(kindsOf(screen), ["go:agence", "go:associes", "go:main"]);
    assert.ok(bare(screen.spec.body).includes(PERSONNEL_TEXTES.vide), screen.spec.body);
  });

  it("la bulle d'Oooscar passe avant la phrase", () => {
    const screen = personnelScreen(viewOf(TEAM), 0, 8, { bubble: BULLES.renvoi });
    assert.ok(bare(screen.spec.body).startsWith("Oooscar [oups] Membre renvoyé.\n\nTon équipe"), screen.spec.body);
  });

  it("des pages : l'Agence et les associés restent sur chacune, la navigation vient après les équipes", () => {
    const staff = IDS.slice(0, 5).map((factoryId, i) => staffMember({ staffId: 20 + i, factoryId }));
    const view = viewOf({ factories: IDS.slice(0, 5).map((factoryId) => factory({ factoryId })), staff });
    const premiere = personnelScreen(view, 0, 5);
    assert.deepEqual(kindsOf(premiere), ["go:agence", "go:associes", "go:equipe", "go:equipe", "go:equipe", "go:personnel", "go:main"]);
    assert.deepEqual(labelsOf(premiere).slice(5), ["Suite", "Retour"]);
    assert.ok(bare(premiere.spec.body).includes("Page 1 sur 2"));
    const seconde = personnelScreen(view, 1, 5);
    assert.deepEqual(kindsOf(seconde), ["go:agence", "go:associes", "go:equipe", "go:equipe", "go:personnel", "go:main"]);
    assert.deepEqual(labelsOf(seconde).slice(4), ["Précédent", "Retour"]);
    assert.deepEqual(seconde.targets[4], { kind: "go", route: { screen: "personnel", page: 0 } });
  });

  it("une page hors limites est ramenée dans les limites", () => {
    assert.deepEqual(kindsOf(personnelScreen(viewOf(TEAM), 99, 8)), ["go:agence", "go:associes", "go:equipe", "go:equipe", "go:main"]);
  });
});

// ---------------------------------------------------------------- une équipe

describe("écran d'une équipe", () => {
  it("un membre par tuile : son nom tel que le service le donne, sa rareté, son rôle, son bonus", () => {
    const screen = must(equipeScreen(viewOf(TEAM), "carriere", 0, 8));
    assert.equal(screen.spec.title, PERSONNEL_TEXTES.equipe.titre);
    assert.deepEqual(labelsOf(screen), ["Adèle\nOrdinaire · Salarié · +10 %", "Bruno\nRare · Chef · +25 %", "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "go", route: { screen: "membre", staffId: 11 } });
    assert.deepEqual(screen.targets[1], { kind: "go", route: { screen: "membre", staffId: 12 } });
    assert.deepEqual(screen.targets[2], { kind: "go", route: { screen: "personnel", page: 0 } });
  });

  it("le nom a la couleur de sa rareté, et la rareté est écrite à côté", () => {
    const screen = must(equipeScreen(viewOf(TEAM), "carriere", 0, 8));
    assert.ok((screen.spec.buttons[0] ?? "").startsWith(`${RARITY_COLOR.COMMUN}Adèle`));
    assert.ok((screen.spec.buttons[1] ?? "").startsWith(`${RARITY_COLOR.RARE}Bruno`));
    const sansUsine = must(equipeScreen(viewOf(TEAM), null, 0, 8));
    assert.ok((sansUsine.spec.buttons[0] ?? "").startsWith(`${RARITY_COLOR.EPIQUE}Chloé`));
    assert.ok(bare(sansUsine.spec.buttons[0] ?? "").includes("Exceptionnel"));
  });

  it("le titre de la fiche dit le nom de l'usine, ou « Sans usine »", () => {
    assert.ok(bare(must(equipeScreen(viewOf(TEAM), "carriere", 0, 8)).spec.body).startsWith("Carrière\n2 membres"));
    assert.ok(bare(must(equipeScreen(viewOf(TEAM), null, 0, 8)).spec.body).startsWith("Sans usine\n1 membre"));
  });

  it("une équipe vide n'existe pas : l'écran rend undefined", () => {
    assert.equal(equipeScreen(viewOf(TEAM), "scierie", 0, 8), undefined);
    assert.equal(equipeScreen(viewOf({ factories: [CARRIERE] }), null, 0, 8), undefined);
  });

  it("des pages : Suite, Précédent et Retour", () => {
    const staff = [1, 2, 3, 4, 5].map((i) => staffMember({ staffId: 30 + i, name: `Membre ${i}` }));
    const view = viewOf({ factories: [CARRIERE], staff });
    const premiere = must(equipeScreen(view, "carriere", 0, 3));
    assert.deepEqual(kindsOf(premiere), ["go:membre", "go:membre", "go:membre", "go:equipe", "go:personnel"]);
    assert.deepEqual(premiere.targets[3], { kind: "go", route: { screen: "equipe", factoryId: "carriere", page: 1 } });
    const seconde = must(equipeScreen(view, "carriere", 1, 3));
    assert.deepEqual(kindsOf(seconde), ["go:membre", "go:membre", "go:equipe", "go:personnel"]);
    assert.ok(bare(seconde.spec.body).includes("Page 2 sur 2"));
  });
});

// ---------------------------------------------------------------- un membre

describe("fiche d'un membre", () => {
  it("rareté et rôle, nom, bonus, salaire, usine et apparence, tels que le service les donne", () => {
    const screen = must(membreScreen(viewOf(TEAM), 12));
    assert.equal(screen.spec.title, PERSONNEL_TEXTES.membre.titre);
    assert.deepEqual(bare(screen.spec.body).split("\n"), [
      "Rare · Chef",
      "Bruno",
      "",
      "Bonus +25 % de production",
      "Salaire 360 Donar/h",
      "Usine Carrière",
      "Apparence chef-casque",
    ]);
  });

  it("la rareté prend sa couleur, le nom est en gras", () => {
    const body = must(membreScreen(viewOf(TEAM), 12)).spec.body;
    assert.ok(body.startsWith(`${RARITY_COLOR.RARE}Rare · Chef`), body);
    assert.ok(body.includes(`${COLOR.bold}Bruno${COLOR.reset}`));
  });

  it("le salaire est montré tel que le service le donne, même nul", () => {
    const body = bare(must(membreScreen(viewOf(TEAM), 13)).spec.body);
    assert.ok(body.includes("Salaire 0 Donar/h"), body);
  });

  it("un salaire que le service ne donne pas n'est pas inventé", () => {
    const sans = staffMember({ staffId: 40 });
    delete (sans as { salaryPerHour?: number }).salaryPerHour;
    const body = bare(must(membreScreen(viewOf({ factories: [CARRIERE], staff: [sans] }), 40)).spec.body);
    assert.ok(!body.includes("Salaire"), body);
  });

  it("sans usine, le dit en toutes lettres", () => {
    assert.ok(bare(must(membreScreen(viewOf(TEAM), 13)).spec.body).includes(PERSONNEL_TEXTES.membre.sansUsine));
  });

  it("Affecter, Renvoyer, Retour : aucun bouton ne renvoie directement, et Retour revient à son équipe", () => {
    const screen = must(membreScreen(viewOf(TEAM), 11));
    assert.deepEqual(labelsOf(screen), ["Affecter à une usine", "Renvoyer", "Retour"]);
    assert.deepEqual(kindsOf(screen), ["go:affecter", "go:renvoi", "go:equipe"]);
    assert.deepEqual(screen.targets[2], { kind: "go", route: { screen: "equipe", factoryId: "carriere", page: 0 } });
    assert.deepEqual(must(membreScreen(viewOf(TEAM), 13)).targets[2], {
      kind: "go",
      route: { screen: "equipe", factoryId: null, page: 0 },
    });
  });

  it("un membre qui n'est plus dans la liste du service : l'écran rend undefined", () => {
    assert.equal(membreScreen(viewOf(TEAM), 999), undefined);
  });
});

// ---------------------------------------------------------------- l'affectation

describe("choix de l'usine d'un membre", () => {
  const view = viewOf({ factories: [CARRIERE, SCIERIE, factory({ factoryId: "ferme" })], staff: [ADELE, BRUNO] });

  it("toutes les usines de l'entreprise sauf la sienne, avec le nombre de membres qui y travaillent", () => {
    const screen = must(affecterScreen(view, 11, 0, 8));
    assert.equal(screen.spec.title, PERSONNEL_TEXTES.affecter.titre);
    assert.deepEqual(labelsOf(screen), [`${ICON.usine} Scierie\nAucun membre`, `${ICON.usine} Ferme\nAucun membre`, "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "assign", staffId: 11, factoryId: "scierie" });
    assert.deepEqual(screen.targets[1], { kind: "assign", staffId: 11, factoryId: "ferme" });
    assert.deepEqual(screen.targets[2], { kind: "go", route: { screen: "membre", staffId: 11 } });
  });

  it("le nombre de membres vient de la liste du service, pas d'un calcul de postes", () => {
    const vue = viewOf({ factories: [CARRIERE, SCIERIE], staff: [ADELE, staffMember({ staffId: 16, factoryId: "scierie" })] });
    const screen = must(affecterScreen(vue, 11, 0, 8));
    assert.equal(bare(screen.spec.buttons[0] ?? ""), `${ICON.usine} Scierie\n1 membre`);
  });

  it("un membre sans usine peut aller dans toutes les usines", () => {
    const screen = must(affecterScreen(viewOf(TEAM), 13, 0, 8));
    assert.deepEqual(kindsOf(screen), ["assign", "assign", "go:membre"]);
  });

  it("une seule usine, et c'est la sienne : la phrase le dit, il ne reste que Retour", () => {
    const screen = must(affecterScreen(viewOf({ factories: [CARRIERE], staff: [ADELE] }), 11, 0, 8));
    assert.deepEqual(kindsOf(screen), ["go:membre"]);
    assert.ok(bare(screen.spec.body).includes(PERSONNEL_TEXTES.affecter.aucune));
  });

  it("des pages", () => {
    const vue = viewOf({ factories: IDS.slice(0, 6).map((factoryId) => factory({ factoryId })), staff: [ADELE] });
    const premiere = must(affecterScreen(vue, 11, 0, 3));
    assert.deepEqual(kindsOf(premiere), ["assign", "assign", "assign", "go:affecter", "go:membre"]);
    assert.deepEqual(premiere.targets[3], { kind: "go", route: { screen: "affecter", staffId: 11, page: 1 } });
    assert.ok(bare(premiere.spec.body).includes("Page 1 sur 2"));
  });

  it("un membre qui n'est plus dans la liste : l'écran rend undefined", () => {
    assert.equal(affecterScreen(view, 999, 0, 8), undefined);
  });
});

// ---------------------------------------------------------------- le renvoi

describe("confirmation d'un renvoi", () => {
  it("un bouton qui renvoie, un bouton qui garde", () => {
    const screen = must(renvoiScreen(viewOf(TEAM), 12));
    assert.equal(screen.spec.title, PERSONNEL_TEXTES.renvoi.titre);
    assert.deepEqual(labelsOf(screen), ["Renvoyer", "Garder"]);
    assert.deepEqual(screen.targets[0], { kind: "fire", staffId: 12 });
    assert.deepEqual(screen.targets[1], { kind: "go", route: { screen: "membre", staffId: 12 } });
    assert.deepEqual(bare(screen.spec.body).split("\n"), ["Bruno", PERSONNEL_TEXTES.renvoi.phrase]);
  });

  it("un membre qui n'est plus dans la liste : l'écran rend undefined", () => {
    assert.equal(renvoiScreen(viewOf(TEAM), 999), undefined);
  });

  it("aucun autre écran du personnel n'a de bouton qui renvoie : un renvoi demande deux appuis", () => {
    const view = viewOf(TEAM);
    const lookup = lookupOf(candidate(), candidate({ slot: 2, staffId: 11 }));
    const ecrans: Screen[] = [
      personnelScreen(view, 0, 8),
      must(equipeScreen(view, "carriere", 0, 8)),
      must(membreScreen(view, 11)),
      must(affecterScreen(view, 11, 0, 8)),
      agenceScreen(lookup),
      must(candidatScreen(lookup, 1)),
      must(embaucheScreen(view, lookup, 1, 0, 8)),
    ];
    for (const ecran of ecrans) {
      assert.ok(!ecran.targets.some((cible) => cible.kind === "fire"), ecran.spec.title);
    }
  });
});

// ---------------------------------------------------------------- l'agence

describe("écran de l'agence", () => {
  const liste = lookupOf(
    candidate({ slot: 1, name: "Basile" }),
    candidate({ slot: 2, name: "Camille", role: "CHEF", rarity: "RARE", bonusPercent: 25 }),
    candidate({ slot: 3, name: "Dorian", rarity: "EPIQUE", bonusPercent: 40, staffId: 11, factoryId: "carriere" }),
  );

  it("un candidat par tuile, dans l'ordre du service, puis Retour", () => {
    const screen = agenceScreen(liste);
    assert.equal(screen.spec.title, AGENCE_TEXTES.titre);
    assert.deepEqual(labelsOf(screen), [
      "Basile\nOrdinaire · Salarié · +10 %",
      "Camille\nRare · Chef · +25 %",
      "Dorian\nDéjà embauché",
      "Retour",
    ]);
    assert.deepEqual(screen.targets.slice(0, 3), [
      { kind: "go", route: { screen: "candidat", slot: 1 } },
      { kind: "go", route: { screen: "candidat", slot: 2 } },
      { kind: "go", route: { screen: "candidat", slot: 3 } },
    ]);
    assert.deepEqual(screen.targets[3], { kind: "go", route: { screen: "personnel", page: 0 } });
  });

  it("le nom prend la couleur de sa rareté", () => {
    const screen = agenceScreen(liste);
    assert.ok((screen.spec.buttons[0] ?? "").startsWith(`${RARITY_COLOR.COMMUN}Basile`));
    assert.ok((screen.spec.buttons[1] ?? "").startsWith(`${RARITY_COLOR.RARE}Camille`));
    assert.ok((screen.spec.buttons[2] ?? "").startsWith(`${RARITY_COLOR.EPIQUE}Dorian`));
  });

  it("aucun bouton pour une nouvelle liste : pas de hasard payant", () => {
    const ecrans = [agenceScreen(liste), agenceScreen(lookupOf()), agenceScreen(undefined), ...[1, 2, 3].map((slot) => must(candidatScreen(liste, slot)))];
    for (const ecran of ecrans) {
      for (const texte of labelsOf(ecran)) {
        assert.doesNotMatch(texte, /nouvelle|renouvel|relanc|tirer|tirage|rafra/i, texte);
      }
    }
  });

  it("la liste du jour peut être vide : le mot le dit, il reste Retour", () => {
    const screen = agenceScreen(lookupOf());
    assert.deepEqual(kindsOf(screen), ["go:personnel"]);
    assert.ok(bare(screen.spec.body).includes(AGENCE_TEXTES.vide));
  });

  it("sans lecture : Oooscar dit que le service ne répond pas, rien n'est inventé", () => {
    const screen = agenceScreen(undefined);
    assert.deepEqual(kindsOf(screen), ["go:personnel"]);
    assert.equal(screen.spec.body, renderBubble(BULLES.serviceInjoignable));
  });

  it("lecture échouée : la bulle de l'échec, réseau ou service", () => {
    assert.equal(agenceScreen({ kind: "failed", failure: "network", reason: "RESEAU" }).spec.body, renderBubble(BULLES.serviceInjoignable));
    assert.equal(agenceScreen({ kind: "failed", failure: "error", reason: "ERREUR_INTERNE" }).spec.body, renderBubble(BULLES.problemeService));
    assert.equal(agenceScreen({ kind: "failed", failure: "error", reason: "PAS_FAIT" }).spec.body, renderBubble(BULLES.bientot));
  });

  it("une bulle donnée passe avant la phrase", () => {
    const screen = agenceScreen(liste, { bubble: BULLES.recrue });
    assert.ok(bare(screen.spec.body).startsWith("Oooscar [joie] Nouvelle recrue embauchée !\n\nChoisis"), screen.spec.body);
  });
});

// ---------------------------------------------------------------- un candidat

describe("fiche d'un candidat", () => {
  const liste = lookupOf(
    candidate({ slot: 1, name: "Basile", hireCost: 123_456, salaryPercent: 3.5, bonusPercent: 12.5, skinId: "ouvrier-casque" }),
    candidate({ slot: 2, name: "Camille", staffId: 11, factoryId: "carriere", hireCost: 4_800 }),
  );

  it("rareté et rôle, nom, bonus, salaire en pourcent, prix d'embauche et apparence : tels que le service les donne", () => {
    const screen = must(candidatScreen(liste, 1));
    assert.equal(screen.spec.title, AGENCE_TEXTES.candidat.titre);
    assert.deepEqual(bare(screen.spec.body).split("\n"), [
      "Ordinaire · Salarié",
      "Basile",
      "",
      `Bonus +${formatAmount(12.5)} % de production`,
      `Salaire ${formatAmount(3.5)} % du revenu de l'usine`,
      `Embauche ${formatAmount(123_456)} Donar`,
      "Apparence ouvrier-casque",
    ]);
  });

  it("Embaucher mène au choix de l'usine ; Retour revient à l'agence", () => {
    const screen = must(candidatScreen(liste, 1));
    assert.deepEqual(labelsOf(screen), ["Embaucher", "Retour"]);
    assert.deepEqual(screen.targets[0], { kind: "go", route: { screen: "embauche", slot: 1, page: 0 } });
    assert.deepEqual(screen.targets[1], { kind: "go", route: { screen: "agence" } });
  });

  it("un candidat déjà embauché : la marque, et pas de bouton Embaucher", () => {
    const screen = must(candidatScreen(liste, 2));
    assert.deepEqual(labelsOf(screen), ["Retour"]);
    assert.ok(bare(screen.spec.body).split("\n").includes(AGENCE_TEXTES.dejaEmbauche), screen.spec.body);
  });

  it("un prix ou un salaire que le service ne donne pas n'est pas inventé", () => {
    const sans = candidate({ slot: 4 });
    delete (sans as { hireCost?: number }).hireCost;
    delete (sans as { salaryPercent?: number }).salaryPercent;
    const body = bare(must(candidatScreen(lookupOf(sans), 4)).spec.body);
    assert.ok(!body.includes("Embauche"), body);
    assert.ok(!body.includes("Salaire"), body);
  });

  it("une place qui n'existe pas, ou pas de lecture : l'écran rend undefined", () => {
    assert.equal(candidatScreen(liste, 5), undefined);
    assert.equal(candidatScreen(undefined, 1), undefined);
    assert.equal(candidatScreen({ kind: "failed", failure: "network", reason: "RESEAU" }, 1), undefined);
  });
});

// ---------------------------------------------------------------- l'embauche

describe("choix de l'usine d'un candidat", () => {
  const view = viewOf({ factories: [CARRIERE, SCIERIE], staff: [ADELE] });
  const liste = lookupOf(candidate({ slot: 2 }), candidate({ slot: 3, staffId: 12 }));

  it("« Sans usine » d'abord, puis chaque usine de l'entreprise avec ses membres, puis Retour", () => {
    const screen = must(embaucheScreen(view, liste, 2, 0, 8));
    assert.equal(screen.spec.title, AGENCE_TEXTES.embauche.titre);
    assert.deepEqual(labelsOf(screen), [
      `${ICON.personnel} Sans usine\nRejoint l'équipe sans poste`,
      `${ICON.usine} Carrière\n1 membre`,
      `${ICON.usine} Scierie\nAucun membre`,
      "Retour",
    ]);
  });

  it("sans usine, la cible n'a pas de factoryId ; avec une usine, elle le porte", () => {
    const screen = must(embaucheScreen(view, liste, 2, 0, 8));
    assert.deepEqual(screen.targets[0], { kind: "hire", slot: 2 });
    assert.equal("factoryId" in (screen.targets[0] ?? {}), false);
    assert.deepEqual(screen.targets[1], { kind: "hire", slot: 2, factoryId: "carriere" });
    assert.deepEqual(screen.targets[2], { kind: "hire", slot: 2, factoryId: "scierie" });
    assert.deepEqual(screen.targets[3], { kind: "go", route: { screen: "candidat", slot: 2 } });
  });

  it("le prix ne figure pas ici : il est sur la fiche du candidat", () => {
    const body = bare(must(embaucheScreen(view, liste, 2, 0, 8)).spec.body);
    assert.ok(!body.includes("Donar"), body);
  });

  it("des pages : « Sans usine » reste sur chacune", () => {
    const vue = viewOf({ factories: IDS.slice(0, 6).map((factoryId) => factory({ factoryId })) });
    const seconde = must(embaucheScreen(vue, liste, 2, 1, 4));
    assert.deepEqual(kindsOf(seconde), ["hire", "hire", "hire", "hire", "go:embauche", "go:candidat"]);
    assert.deepEqual(seconde.targets[0], { kind: "hire", slot: 2 });
    assert.deepEqual(seconde.targets[4], { kind: "go", route: { screen: "embauche", slot: 2, page: 0 } });
    assert.ok(bare(seconde.spec.body).includes("Page 2 sur 2"));
  });

  it("un candidat déjà embauché, une place qui n'existe pas ou pas de lecture : l'écran rend undefined", () => {
    assert.equal(embaucheScreen(view, liste, 3, 0, 8), undefined);
    assert.equal(embaucheScreen(view, liste, 5, 0, 8), undefined);
    assert.equal(embaucheScreen(view, undefined, 2, 0, 8), undefined);
  });

  it("une entreprise sans usine : « Sans usine » reste possible", () => {
    const screen = must(embaucheScreen(viewOf({ factories: [] }), liste, 2, 0, 8));
    assert.deepEqual(kindsOf(screen), ["hire", "go:candidat"]);
  });
});

// ---------------------------------------------------------------- ce que dit Oooscar

describe("noticeOf : le personnel", () => {
  it("embauche, affectation et renvoi ont leur bulle", () => {
    assert.deepEqual(noticeOf({ kind: "hired" }), { bubble: BULLES.recrue });
    assert.deepEqual(noticeOf({ kind: "assigned" }), { bubble: BULLES.affectation });
    assert.deepEqual(noticeOf({ kind: "fired" }), { bubble: BULLES.renvoi });
  });

  it("dans le contexte du personnel, EMPLACEMENT_OCCUPE et DEJA_POSSEDE ont les mots du personnel", () => {
    assert.deepEqual(noticeOf({ kind: "refused", reason: "EMPLACEMENT_OCCUPE" }, true), { bubble: BULLES.postesPleins });
    assert.deepEqual(noticeOf({ kind: "refused", reason: "DEJA_POSSEDE" }, true), { bubble: BULLES.dejaEmbauche });
  });

  it("hors du personnel, ces codes gardent leur bulle d'avant", () => {
    assert.deepEqual(noticeOf({ kind: "refused", reason: "EMPLACEMENT_OCCUPE" }), { bubble: refusalBubble("EMPLACEMENT_OCCUPE") });
    assert.deepEqual(noticeOf({ kind: "refused", reason: "DEJA_POSSEDE" }), { bubble: refusalBubble("DEJA_POSSEDE") });
  });

  it("les autres refus gardent leur bulle : Donar qui manque, usine verrouillée, conflit, pas le PDG", () => {
    assert.deepEqual(noticeOf({ kind: "refused", reason: "PAS_ASSEZ_DE_DONAR", missing: 1_200 }, true), {
      bubble: refusalBubble("PAS_ASSEZ_DE_DONAR", 1_200),
    });
    for (const reason of ["USINE_VERROUILLEE", "CONFLIT_REESSAYER", "PAS_LE_PDG", "CODE_DU_FUTUR"]) {
      assert.deepEqual(staffRefusalBubble(reason), refusalBubble(reason), reason);
    }
  });

  it("les bulles du personnel tiennent en six mots au plus", () => {
    for (const bulle of [BULLES.recrue, BULLES.affectation, BULLES.renvoi, BULLES.postesPleins, BULLES.dejaEmbauche]) {
      assert.ok(bulle.text.split(/\s+/).length <= 6, bulle.text);
    }
  });
});

// ---------------------------------------------------------------- les parcours

const STATE: Partial<CompanyState> = { factories: [CARRIERE, SCIERIE], staff: [ADELE, BRUNO, CHLOE], donar: 50_000 };

function setup(replies: readonly Reply[], script: Script = {}, state: Partial<CompanyState> = STATE, tilesPerScreen = 8) {
  const clock = fakeClock(0);
  const store = storeWith(state);
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const missions = createMissions({ api: fakeMissionsApi([ok(missionState())]), players: store, say: () => undefined, log });
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log, afterCollect: missions.refresh });
  const presenter = fakePresenter(replies);
  const menu = createMenu({
    views,
    missions,
    actions,
    presenter,
    clock,
    wait: async () => undefined,
    log,
    tilesPerScreen,
    busyRetries: 3,
    busyRetrySeconds: 0.5,
  });
  return { menu, presenter, api, views, log };
}

const titleOf = (spec: { title: string }) => spec.title;

function updated(version: number, staff: StaffMember[], donar = 40_000): CompanyUpdated {
  return { state: companyState({ ...STATE, version, donar, staff }), balances: balances() };
}

const LISTE_DU_JOUR = [
  candidate({ slot: 1, name: "Basile", hireCost: 4_800 }),
  candidate({ slot: 2, name: "Camille", role: "CHEF", rarity: "RARE", bonusPercent: 25, hireCost: 9_600 }),
  candidate({ slot: 3, name: "Dorian" }),
];

const refusedPdg = { kind: "refused", status: 403, reason: "PAS_LE_PDG" } as const;

describe("parcours : l'équipe", () => {
  it("Personnel, une équipe, un membre, et on revient pas à pas", async () => {
    const { menu, presenter, api } = setup([
      press("Personnel"),
      press("Carrière"),
      press("Bruno"),
      press("Retour"),
      press("Retour"),
      press("Retour"),
      close,
    ]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE", "ÉQUIPE", "PERSONNEL", "MENU"]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").startsWith("Rare · Chef\nBruno"));
    assert.deepEqual(api.calls, [], "regarder l'équipe n'appelle pas le service");
  });

  it("l'équipe sans usine a sa tuile et sa fiche", async () => {
    const { menu, presenter } = setup([press("Personnel"), press("Sans usine"), press("Chloé"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE"]);
    assert.ok(bare(presenter.shown[3]?.body ?? "").includes(PERSONNEL_TEXTES.membre.sansUsine));
  });

  it("la fiche d'une usine montre la ligne d'équipe dans le parcours", async () => {
    const { menu, presenter } = setup([press("Usines"), press("Carrière"), close]);
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[2]?.body ?? "").includes("Équipe : 2 membres, avec chef"), presenter.shown[2]?.body);
  });
});

describe("parcours : affecter", () => {
  it("le service rend l'état : la fiche du membre revient avec la bulle et sa nouvelle usine", async () => {
    const aprés = updated(2, [staffMember({ factoryId: "scierie" }), BRUNO, CHLOE]);
    const { menu, presenter, api, views } = setup(
      [press("Personnel"), press("Carrière"), press("Adèle"), press("Affecter"), press("Scierie"), close],
      { assignStaff: [ok(aprés)] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE", "AFFECTATION", "MEMBRE"]);
    assert.deepEqual(api.calls, [
      { op: "assignStaff", companyId: 7, staffId: 11, action: { requestId: "id-1", actor: XUID }, factoryId: "scierie" },
    ]);
    const body = bare(presenter.shown[5]?.body ?? "");
    assert.ok(body.startsWith("Oooscar [joie] Affectation faite."), body);
    assert.ok(body.includes("Usine Scierie"), body);
    assert.equal(views.get(XUID)?.state.version, 2);
  });

  it("plus de poste libre : le service refuse, le joueur reste sur le choix avec la bulle, l'état ne bouge pas", async () => {
    const { menu, presenter, views } = setup(
      [press("Personnel"), press("Carrière"), press("Adèle"), press("Affecter"), press("Scierie"), close],
      { assignStaff: [refused("EMPLACEMENT_OCCUPE")] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["AFFECTATION", "AFFECTATION"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [oups] Plus de poste libre ici."));
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("usine verrouillée : la bulle du refus habituel", async () => {
    const { menu, presenter } = setup(
      [press("Personnel"), press("Carrière"), press("Adèle"), press("Affecter"), press("Scierie"), close],
      { assignStaff: [refused("USINE_VERROUILLEE")] },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").includes(bare(renderBubble(refusalBubble("USINE_VERROUILLEE")))));
  });

  it("service injoignable : la panique d'Oooscar, rien n'est présenté comme fait", async () => {
    const { menu, presenter, views } = setup(
      [press("Personnel"), press("Carrière"), press("Adèle"), press("Affecter"), press("Scierie"), close],
      { assignStaff: [unreachable()] },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."));
    assert.equal(views.get(XUID)?.state.version, 1);
  });
});

describe("parcours : renvoyer", () => {
  it("deux appuis : Renvoyer sur la fiche ne renvoie pas, la confirmation renvoie", async () => {
    const { menu, presenter, api } = setup(
      [press("Personnel"), press("Carrière"), press("Bruno"), press("Renvoyer"), close],
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE", "RENVOI"]);
    assert.deepEqual(api.calls, [], "rien n'est parti au service avant la confirmation");
  });

  it("Garder revient à la fiche du membre, sans appel", async () => {
    const { menu, presenter, api } = setup(
      [press("Personnel"), press("Carrière"), press("Bruno"), press("Renvoyer"), press("Garder"), close],
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["RENVOI", "MEMBRE"]);
    assert.deepEqual(api.calls, []);
  });

  it("confirmé : le membre part de la liste, le joueur revient au Personnel avec la bulle", async () => {
    const { menu, presenter, api, views } = setup(
      [press("Personnel"), press("Carrière"), press("Bruno"), press("Renvoyer"), press("Renvoyer"), close],
      { fireStaff: [ok(updated(2, [ADELE, CHLOE]))] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE", "RENVOI", "PERSONNEL"]);
    assert.deepEqual(api.calls, [{ op: "fireStaff", companyId: 7, staffId: 12, action: { requestId: "id-1", actor: XUID } }]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [oups] Membre renvoyé."));
    assert.equal(views.get(XUID)?.state.staff?.length, 2);
    assert.ok(bare(presenter.shown[5]?.buttons[2] ?? "").endsWith("1 membre"), "l'équipe de la Carrière n'a plus que 1 membre");
  });

  it("un associé reçoit 403 PAS_LE_PDG : la bulle d'Oooscar, le membre reste, rien n'est calculé côté add-on", async () => {
    const { menu, presenter, views } = setup(
      [press("Personnel"), press("Carrière"), press("Bruno"), press("Renvoyer"), press("Renvoyer"), close],
      { fireStaff: [refusedPdg] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["RENVOI", "RENVOI"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [oups] Seul le PDG peut le faire."));
    assert.equal(views.get(XUID)?.state.staff?.length, 3);
  });

  it("l'add-on propose le renvoi à tout le monde : le rôle du joueur n'est jamais lu", async () => {
    const { menu, presenter } = setup([press("Personnel"), press("Carrière"), press("Bruno"), close]);
    await menu.open(PLAYER);
    assert.ok(presenter.shown[3]?.buttons.map(bare).includes("Renvoyer"));
  });
});

describe("parcours : un membre disparu", () => {
  it("renvoyé ailleurs pendant que l'écran est ouvert : le parcours remonte au Personnel, sans erreur", async () => {
    let views: ReturnType<typeof setup>["views"] | undefined;
    const retirer: Reply = (spec) => {
      views?.apply(XUID, { state: companyState({ ...STATE, version: 2, staff: [ADELE, CHLOE] }) });
      return press("Affecter")(spec);
    };
    const harness = setup([press("Personnel"), press("Carrière"), press("Bruno"), retirer, close]);
    views = harness.views;
    await harness.menu.open(PLAYER);
    assert.deepEqual(harness.presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "ÉQUIPE", "MEMBRE", "PERSONNEL"]);
    assert.equal(harness.log.warns.length, 0);
  });
});

describe("parcours : l'agence", () => {
  it("l'agence relit les candidats à chaque ouverture", async () => {
    const { menu, presenter, api } = setup(
      [press("Personnel"), press("Agence"), press("Retour"), press("Agence"), close],
      { getCandidates: [ok([candidate({ name: "Basile" })]), ok([candidate({ name: "Denis" })])] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls.map((call) => call.op), ["getCandidates", "getCandidates"]);
    assert.ok(bare(presenter.shown[2]?.buttons[0] ?? "").startsWith("Basile"));
    assert.ok(bare(presenter.shown[4]?.buttons[0] ?? "").startsWith("Denis"));
  });

  it("une fiche de candidat n'appelle pas le service : elle montre la dernière lecture", async () => {
    const { menu, presenter, api } = setup(
      [press("Personnel"), press("Agence"), press("Camille"), close],
      { getCandidates: [ok(LISTE_DU_JOUR)] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "AGENCE", "CANDIDAT"]);
    assert.equal(api.calls.length, 1);
    assert.ok(bare(presenter.shown[3]?.body ?? "").includes(`Embauche ${formatAmount(9_600)} Donar`));
  });

  it("revenir de la fiche d'un candidat à l'agence la relit aussi", async () => {
    const { menu, api } = setup(
      [press("Personnel"), press("Agence"), press("Camille"), press("Retour"), close],
      { getCandidates: [ok(LISTE_DU_JOUR), ok(LISTE_DU_JOUR)] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls.map((call) => call.op), ["getCandidates", "getCandidates"]);
  });

  it("lecture impossible : la bulle d'Oooscar et Retour, jamais une liste inventée", async () => {
    const { menu, presenter } = setup(
      [press("Personnel"), press("Agence"), press("Retour"), close],
      { getCandidates: [unreachable()] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown[2]?.buttons.map(bare), ["Retour"]);
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."));
  });

  it("route pas encore écrite (501) : « Pas encore disponible »", async () => {
    const { menu, presenter } = setup([press("Personnel"), press("Agence"), close], { getCandidates: [failure(501, "PAS_FAIT")] });
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[2]?.body ?? "").startsWith("Oooscar [oups] Pas encore disponible."));
  });
});

describe("parcours : embaucher", () => {
  const VERS_LE_CHOIX = [press("Personnel"), press("Agence"), press("Camille"), press("Embaucher")];

  it("dans une usine : la place et l'usine partent au service, l'agence revient relue avec la marque « déjà embauché »", async () => {
    const recrue = staffMember({ staffId: 50, name: "Camille", role: "CHEF", rarity: "RARE", factoryId: "scierie" });
    const { menu, presenter, api, views } = setup(
      [...VERS_LE_CHOIX, press("Scierie"), close],
      {
        getCandidates: [ok(LISTE_DU_JOUR), ok([LISTE_DU_JOUR[0] as Candidate, { ...(LISTE_DU_JOUR[1] as Candidate), staffId: 50, factoryId: "scierie" }, LISTE_DU_JOUR[2] as Candidate])],
        hire: [ok(updated(2, [ADELE, BRUNO, CHLOE, recrue], 30_400))],
      },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PERSONNEL", "AGENCE", "CANDIDAT", "EMBAUCHE", "AGENCE"]);
    assert.deepEqual(api.calls, [
      { op: "getCandidates", companyId: 7 },
      { op: "hire", companyId: 7, slot: 2, action: { requestId: "id-1", actor: XUID }, factoryId: "scierie" },
      { op: "getCandidates", companyId: 7 },
    ]);
    const agence = presenter.shown[5];
    assert.ok(bare(agence?.body ?? "").startsWith("Oooscar [joie] Nouvelle recrue embauchée !"));
    assert.equal(bare(agence?.buttons[1] ?? ""), "Camille\nDéjà embauché");
    assert.equal(views.get(XUID)?.state.donar, 30_400);
    assert.equal(views.get(XUID)?.state.staff?.length, 4);
  });

  it("sans usine : l'appel part sans factoryId", async () => {
    const { menu, api } = setup(
      [...VERS_LE_CHOIX, press("Sans usine"), close],
      { getCandidates: [ok(LISTE_DU_JOUR), ok(LISTE_DU_JOUR)], hire: [ok(updated(2, [ADELE, BRUNO, CHLOE]))] },
    );
    await menu.open(PLAYER);
    const embauche = api.calls.find((call) => call.op === "hire");
    assert.deepEqual(embauche, { op: "hire", companyId: 7, slot: 2, action: { requestId: "id-1", actor: XUID }, factoryId: undefined });
  });

  it("PAS_ASSEZ_DE_DONAR : les Donar qui manquent, le joueur reste sur le choix, l'état ne bouge pas", async () => {
    const { menu, presenter, views } = setup(
      [...VERS_LE_CHOIX, press("Carrière"), close],
      { getCandidates: [ok(LISTE_DU_JOUR)], hire: [refused("PAS_ASSEZ_DE_DONAR", 1_200)] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["EMBAUCHE", "EMBAUCHE"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith(`Oooscar [oups] Il te manque ${formatAmount(1_200)} Donar.`), presenter.shown[5]?.body);
    assert.equal(views.get(XUID)?.state.version, 1);
    assert.equal(views.get(XUID)?.state.donar, 50_000);
  });

  it("plus de poste libre dans l'usine : la bulle du personnel, on peut choisir une autre usine", async () => {
    const { menu, presenter, api } = setup(
      [...VERS_LE_CHOIX, press("Carrière"), press("Sans usine"), close],
      {
        getCandidates: [ok(LISTE_DU_JOUR), ok(LISTE_DU_JOUR)],
        hire: [refused("EMPLACEMENT_OCCUPE"), ok(updated(2, [ADELE, BRUNO, CHLOE, staffMember({ staffId: 51, factoryId: null })]))],
      },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [oups] Plus de poste libre ici."));
    assert.deepEqual(api.calls.filter((call) => call.op === "hire").map((call) => (call.op === "hire" ? call.factoryId : "")), ["carriere", undefined]);
  });

  it("déjà embauché ailleurs : la bulle « Déjà embauché », et revenir à l'agence la relit à jour", async () => {
    const { menu, presenter, api } = setup(
      [...VERS_LE_CHOIX, press("Sans usine"), press("Retour"), press("Retour"), close],
      { getCandidates: [ok(LISTE_DU_JOUR), ok(LISTE_DU_JOUR)], hire: [refused("DEJA_POSSEDE")] },
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [fier] Déjà embauché."));
    assert.deepEqual(api.calls.map((call) => call.op), ["getCandidates", "hire", "getCandidates"]);
  });

  it("CONFLIT_REESSAYER : l'état est rechargé, l'embauche rejouée une fois avec le même requestId, le joueur ne voit que le succès", async () => {
    const { menu, presenter, api } = setup(
      [...VERS_LE_CHOIX, press("Scierie"), close],
      {
        getCandidates: [ok(LISTE_DU_JOUR), ok(LISTE_DU_JOUR)],
        hire: [refused("CONFLIT_REESSAYER"), ok(updated(6, [ADELE, BRUNO, CHLOE]))],
        getCompany: [ok(companyState({ ...STATE, version: 5 }))],
      },
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls.map((call) => call.op), ["getCandidates", "hire", "getCompany", "hire", "getCandidates"]);
    const ids = api.calls.flatMap((call) => (call.op === "hire" ? [call.action.requestId] : []));
    assert.equal(ids.length, 2);
    assert.equal(ids[0], ids[1]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [joie] Nouvelle recrue embauchée !"));
  });

  it("service injoignable : la panique d'Oooscar, le joueur reste sur le choix", async () => {
    const { menu, presenter } = setup(
      [...VERS_LE_CHOIX, press("Scierie"), close],
      { getCandidates: [ok(LISTE_DU_JOUR)], hire: [unreachable()] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf).slice(4), ["EMBAUCHE", "EMBAUCHE"]);
    assert.ok(bare(presenter.shown[5]?.body ?? "").startsWith("Oooscar [panique] Le service ne répond pas."));
  });

  it("un candidat déjà embauché n'a pas de bouton Embaucher dans le parcours", async () => {
    const { menu, presenter } = setup(
      [press("Personnel"), press("Agence"), press("Dorian"), close],
      { getCandidates: [ok([...LISTE_DU_JOUR.slice(0, 2), candidate({ slot: 3, name: "Dorian", staffId: 52, factoryId: "carriere" })])] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown[3]?.buttons.map(bare), ["Retour"]);
  });
});
