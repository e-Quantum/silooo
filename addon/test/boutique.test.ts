import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { BOUTIQUE } from "../src/monde/definitions";
import type { CatalogItem } from "../src/protocol";
import type { ActionOutcome, CatalogOutcome, VotesOutcome } from "../src/ui/actions";
import { ICON } from "../src/ui/apparence";
import {
  affectsCompany,
  articleOf,
  articleParent,
  articleScreen,
  articlesOf,
  boutiqueNotice,
  boutiqueScreen,
  boutiqueTile,
  offrirScreen,
  pioneerLine,
  purchaseContext,
  rayonScreen,
  shelfParent,
} from "../src/ui/boutique";
import type { Screen } from "../src/ui/ecran";
import type { CompanyView } from "../src/ui/etat";
import { cosmeticName } from "../src/ui/pass";
import { ASSOCIES_TEXTES, BOUTIQUE_TEXTES, PASS_TEXTES, USINES_TEXTES, VOTE_TEXTES, dureeEnMots, refusalBubble } from "../src/ui/textes";
import { voteSites } from "../src/ui/votes";
import { catalogItem, shopCatalog, votesToday } from "./helpers";
import { XUID, bare, fakeClock, viewsWith } from "./ui-helpers";

function viewOf(soldes: { gemmes?: number } = {}): CompanyView {
  const view = viewsWith({}, fakeClock(0), soldes).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

function catalogOf(changes: Readonly<Record<string, Partial<CatalogItem>>> = {}): CatalogOutcome {
  return { kind: "catalog", items: shopCatalog(changes) };
}

const FAILED: CatalogOutcome = { kind: "failed", failure: "network", reason: "RESEAU" };

const labelsOf = (screen: Screen): string[] => screen.spec.buttons.map(bare);
const kindsOf = (screen: Screen): string[] => screen.targets.map((target) => (target.kind === "go" ? `go:${target.route.screen}` : target.kind));

const GRADES = BOUTIQUE.grades;
const PILIER = BOUTIQUE.abonnements.find((abonnement) => abonnement.id === "pilier");
const BOOST = BOUTIQUE.boostCollectif;
const VENDUS = BOUTIQUE.cosmetiques.filter((cosmetic) => cosmetic.achetable);

function must<T>(value: T | undefined): T {
  if (value === undefined) throw new Error("valeur absente");
  return value;
}

describe("articleOf et articlesOf : le catalogue du service rapproché de definitions/boutique.json", () => {
  it("un grade a le nom et le bonus du JSON, et rien d'autre", () => {
    const grade = must(GRADES[0]);
    const article = articleOf(catalogItem(grade.id));
    assert.deepEqual(article && { kind: article.kind, nom: article.nom }, { kind: "grade", nom: grade.nom });
    assert.equal(article?.kind === "grade" && article.bonus, grade.bonusDonarPourcent);
  });

  it("le Pilier est un abonnement avec ses jours, son bonus et les Gemmes rendues du JSON", () => {
    const pilier = must(PILIER);
    const article = articleOf(catalogItem("pilier"));
    assert.equal(article?.kind, "abonnement");
    if (article?.kind === "abonnement") {
      assert.equal(article.nom, pilier.nom);
      assert.equal(article.jours, pilier.jours);
      assert.equal(article.bonus, pilier.bonusDonarPourcent);
      assert.equal(article.rendues, pilier.gemmesRendues);
    }
  });

  it("un cosmétique en vente a sa catégorie ; le boost collectif a son bonus et sa durée du JSON", () => {
    const cosmetic = must(VENDUS[0]);
    const article = articleOf(catalogItem(cosmetic.id));
    assert.equal(article?.kind === "cosmetique" && article.categorie, cosmetic.categorie);
    const boost = articleOf(catalogItem("boost-collectif"));
    assert.equal(boost?.kind, "boost");
    if (boost?.kind === "boost") {
      assert.equal(boost.pourcent, BOOST.bonusPourcent);
      assert.equal(boost.minutes, BOOST.dureeMinutes);
      assert.equal(boost.nom, BOUTIQUE_TEXTES.rayons.boost);
    }
  });

  it("le préfixe perso, le pass premium, un cosmétique que la boutique ne vend pas et un inconnu ne sont pas montrés", () => {
    for (const id of ["perso", "pass-premium", "pionnier", "s1-fanion", "inconnu", ""]) {
      assert.equal(articleOf(catalogItem(id)), undefined, id);
    }
  });

  it("articlesOf garde l'ordre du service et saute ce qui n'est pas montré ; sans lecture, il n'y a rien", () => {
    const ids = articlesOf(catalogOf()).map((article) => article.item.itemId);
    assert.deepEqual(ids.slice(0, GRADES.length), GRADES.map((grade) => grade.id));
    assert.equal(ids[GRADES.length], "pilier");
    assert.ok(!ids.includes("perso") && !ids.includes("pass-premium"));
    assert.equal(ids[ids.length - 1], "boost-collectif");
    assert.deepEqual(articlesOf(undefined), []);
    assert.deepEqual(articlesOf(FAILED), []);
  });

  it("affectsCompany : un grade, le Pilier et le boost changent le revenu ; un cosmétique et le pass non", () => {
    assert.equal(affectsCompany(must(GRADES[0]).id), true);
    assert.equal(affectsCompany("pilier"), true);
    assert.equal(affectsCompany("boost-collectif"), true);
    assert.equal(affectsCompany(must(VENDUS[0]).id), false);
    assert.equal(affectsCompany("pass-premium"), false);
    assert.equal(affectsCompany("inconnu"), false);
  });
});

describe("la tuile du menu", () => {
  it("porte le nom, l'icône et les Gemmes du service, et mène à la boutique", () => {
    const tile = boutiqueTile(viewOf({ gemmes: 1_250 }));
    const text = bare(tile.text);
    assert.ok(text.startsWith(`${ICON.boutique} Boutique`), text);
    assert.ok(text.endsWith(BOUTIQUE_TEXTES.tuile("1 250")), text);
    assert.deepEqual(tile.target, { kind: "go", route: { screen: "boutique" } });
  });
});

describe("boutiqueScreen", () => {
  it("les portes dans l'ordre : grades, Pilier, cosmétiques, boost collectif, pass, Retour", () => {
    const screen = boutiqueScreen(viewOf({ gemmes: 90 }), catalogOf(), undefined, undefined);
    assert.equal(screen.spec.title, BOUTIQUE_TEXTES.titre);
    assert.deepEqual(kindsOf(screen), ["go:rayon", "go:article", "go:rayon", "go:article", "go:pass", "go:main"]);
    const labels = labelsOf(screen);
    assert.equal(labels[0], `${ICON.boutique} Grades\n${BOUTIQUE_TEXTES.details.prochainGrade(must(GRADES[0]).nom)}`);
    assert.ok(labels[1]?.includes(must(PILIER).nom), labels[1]);
    assert.equal(labels[2], `${ICON.boutique} Cosmétiques`);
    assert.ok(labels[3]?.includes(BOUTIQUE_TEXTES.rayons.boost), labels[3]);
    assert.ok(labels[4]?.includes(BOUTIQUE_TEXTES.rayons.pass), labels[4]);
    assert.equal(labels[5], "Retour");
  });

  it("le prix du Pilier et du boost sur leur tuile est celui du catalogue du service", () => {
    const screen = boutiqueScreen(viewOf(), catalogOf({ pilier: { gemmes: 333 }, "boost-collectif": { gemmes: 77 } }), undefined, undefined);
    const labels = labelsOf(screen);
    assert.ok(labels[1]?.endsWith(BOUTIQUE_TEXTES.details.prix("333")), labels[1]);
    assert.ok(labels[3]?.endsWith(BOUTIQUE_TEXTES.details.prix("77")), labels[3]);
  });

  it("le solde de Gemmes est celui de la vue", () => {
    const screen = boutiqueScreen(viewOf({ gemmes: 90 }), catalogOf(), undefined, undefined);
    assert.ok(bare(screen.spec.body).includes(BOUTIQUE_TEXTES.solde("90")), screen.spec.body);
  });

  it("sans Gemme, l'écran dit où elles s'achètent, sans adresse ; avec des Gemmes, il ne le dit pas", () => {
    const vide = bare(boutiqueScreen(viewOf({ gemmes: 0 }), catalogOf(), undefined, undefined).spec.body);
    assert.ok(vide.includes(BOUTIQUE_TEXTES.gemmesManquent), vide);
    assert.doesNotMatch(vide, /https?:|www\.|\.com|\.fr/);
    const riche = bare(boutiqueScreen(viewOf({ gemmes: 1 }), catalogOf(), undefined, undefined).spec.body);
    assert.ok(!riche.includes(BOUTIQUE_TEXTES.gemmesManquent), riche);
  });

  it("tous les grades possédés : la porte le dit ; un Pilier en cours aussi", () => {
    const changes = Object.fromEntries(GRADES.map((grade) => [grade.id, { owned: true, available: false }]));
    const screen = boutiqueScreen(viewOf(), catalogOf({ ...changes, pilier: { owned: true } }), undefined, undefined);
    const labels = labelsOf(screen);
    assert.equal(labels[0], `${ICON.boutique} Grades\n${BOUTIQUE_TEXTES.details.tousLesGrades}`);
    assert.ok(labels[1]?.endsWith(BOUTIQUE_TEXTES.details.enCours), labels[1]);
  });

  it("sans lecture du catalogue, il reste les rayons et le pass ; le Pilier et le boost, qui sont des articles du catalogue, ne sont pas inventés", () => {
    for (const catalog of [undefined, FAILED]) {
      const screen = boutiqueScreen(viewOf(), catalog, undefined, undefined);
      assert.deepEqual(kindsOf(screen), ["go:rayon", "go:rayon", "go:pass", "go:main"]);
      assert.equal(labelsOf(screen)[0], `${ICON.boutique} Grades`);
    }
  });

  it("le rappel du vote est dit quand le service dit que le joueur n'a pas voté, avec le nom des sites et sans lien", () => {
    const pasVote: VotesOutcome = { kind: "votes", votes: votesToday({ mine: { votes: 0, streak: 0 } }) };
    const body = bare(boutiqueScreen(viewOf(), catalogOf(), undefined, pasVote).spec.body);
    assert.ok(body.includes(VOTE_TEXTES.rappel.boutique(voteSites())), body);
    assert.doesNotMatch(body, /https?:|www\./);
  });

  it("pas de rappel quand le joueur a voté, quand on ne sait pas, ou quand la lecture a échoué", () => {
    const aVote: VotesOutcome = { kind: "votes", votes: votesToday({ mine: { votes: 1, streak: 1 } }) };
    const echec: VotesOutcome = { kind: "failed", failure: "network", reason: "RESEAU" };
    for (const votes of [aVote, echec, undefined]) {
      const body = bare(boutiqueScreen(viewOf(), catalogOf(), undefined, votes).spec.body);
      assert.ok(!body.includes(voteSites()), body);
    }
  });

  it("la tuile du pass dit combien de récompenses attendent quand le pass est lu", () => {
    const sans = labelsOf(boutiqueScreen(viewOf(), catalogOf(), undefined, undefined))[4] ?? "";
    assert.ok(!sans.includes("\n"), sans);
    const avec = labelsOf(boutiqueScreen(viewOf(), catalogOf(), { kind: "pass", pass: passOutcomeState() }, undefined))[4] ?? "";
    assert.ok(avec.endsWith(PASS_TEXTES.aReclamer(2)), avec);
  });

  it("la bulle de l'action vient en tête du corps", () => {
    const screen = boutiqueScreen(viewOf(), catalogOf(), undefined, undefined, { bubble: BOUTIQUE_TEXTES.bulles.achete });
    assert.ok(bare(screen.spec.body).includes(BOUTIQUE_TEXTES.bulles.achete.text));
    assert.ok(screen.spec.body.indexOf(BOUTIQUE_TEXTES.bulles.achete.text) < screen.spec.body.indexOf("Gemmes :"));
  });
});

function passOutcomeState() {
  // 120 points : le palier 2 est à réclamer sur la piste gratuite (le palier 1 l'est déjà) ; ici deux récompenses attendent.
  return {
    seasonId: "saison-1",
    name: "Saison 1",
    startsOn: "2026-11-05",
    endsOn: "2027-01-31",
    active: true,
    points: 120,
    premium: false,
    claimed: ["1-GRATUIT"] as string[],
    tiers: [
      { tier: 1, points: 40, free: { lingots: 1 }, premium: null },
      { tier: 2, points: 100, free: { lingots: 1 }, premium: null },
      { tier: 3, points: 110, free: { lingots: 2 }, premium: null },
    ],
  };
}

describe("rayonScreen", () => {
  it("rend undefined quand le catalogue n'a pas été lu", () => {
    assert.equal(rayonScreen(undefined, "grades", 0, 8), undefined);
    assert.equal(rayonScreen(FAILED, "grades", 0, 8), undefined);
  });

  it("les grades dans l'ordre du service : le premier à acheter, les autres sous cadenas, un grade possédé marqué", () => {
    const screen = must(rayonScreen(catalogOf({ [must(GRADES[0]).id]: { owned: true, available: false }, [must(GRADES[1]).id]: { available: true } }), "grades", 0, 8));
    assert.equal(screen.spec.title, BOUTIQUE_TEXTES.rayon.titre);
    const labels = labelsOf(screen);
    assert.equal(labels.length, GRADES.length + 1);
    assert.equal(labels[0], `${ICON.possede} ${must(GRADES[0]).nom}\n${BOUTIQUE_TEXTES.details.possede}`);
    assert.ok(labels[1]?.startsWith(`${bare(ICON.gemmes)} ${must(GRADES[1]).nom}`), labels[1]);
    assert.ok(labels[2]?.startsWith(`${ICON.cadenas} ${must(GRADES[2]).nom}`), labels[2]);
    assert.ok(labels[2]?.endsWith(BOUTIQUE_TEXTES.details.bloque), labels[2]);
    assert.equal(labels[GRADES.length], "Retour");
    assert.deepEqual(screen.targets[0], { kind: "go", route: { screen: "article", itemId: must(GRADES[0]).id, shelf: "grades", page: 0 } });
    assert.deepEqual(screen.targets[GRADES.length], { kind: "go", route: { screen: "boutique" } });
  });

  it("le prix d'un grade sur sa tuile est celui du catalogue du service", () => {
    const screen = must(rayonScreen(catalogOf({ [must(GRADES[0]).id]: { gemmes: 4_321 } }), "grades", 0, 8));
    assert.ok(labelsOf(screen)[0]?.endsWith(BOUTIQUE_TEXTES.details.prix("4 321")));
  });

  it("des pages : chaque tuile sait sur quelle page elle tombe, la navigation vient après, une page hors limites est ramenée", () => {
    const premiere = must(rayonScreen(catalogOf(), "grades", 0, 4));
    assert.deepEqual(kindsOf(premiere), ["go:article", "go:article", "go:article", "go:article", "go:rayon", "go:boutique"]);
    assert.deepEqual(labelsOf(premiere).slice(4), ["Suite", "Retour"]);
    assert.ok(bare(premiere.spec.body).includes(USINES_TEXTES.page(1, 2)), premiere.spec.body);
    const seconde = must(rayonScreen(catalogOf(), "grades", 99, 4));
    assert.deepEqual(labelsOf(seconde).slice(2), ["Précédent", "Retour"]);
    assert.deepEqual(seconde.targets[0], {
      kind: "go",
      route: { screen: "article", itemId: must(GRADES[4]).id, shelf: "grades", page: 1 },
    });
    assert.deepEqual(seconde.targets[2], { kind: "go", route: { screen: "rayon", rayon: "grades", page: 0 } });
  });

  it("la liste des cosmétiques n'a que les catégories qui ont un article en vente, avec leur nombre", () => {
    const screen = must(rayonScreen(catalogOf(), "cosmetiques", 0, 8));
    const categories = [...new Set(VENDUS.map((cosmetic) => cosmetic.categorie))];
    assert.equal(screen.targets.length, categories.length + 1);
    categories.forEach((categorie, index) => {
      const count = VENDUS.filter((cosmetic) => cosmetic.categorie === categorie).length;
      assert.equal(
        labelsOf(screen)[index],
        `${ICON.boutique} ${BOUTIQUE_TEXTES.categories[categorie]}\n${BOUTIQUE_TEXTES.details.articles(count)}`,
      );
      assert.deepEqual(screen.targets[index], { kind: "go", route: { screen: "rayon", rayon: categorie, page: 0 } });
    });
    assert.deepEqual(screen.targets[categories.length], { kind: "go", route: { screen: "boutique" } });
  });

  it("une catégorie montre ses articles, et Retour remonte à la liste des catégories", () => {
    const cosmetic = must(VENDUS[0]);
    const screen = must(rayonScreen(catalogOf(), cosmetic.categorie, 0, 8));
    const attendus = VENDUS.filter((candidate) => candidate.categorie === cosmetic.categorie);
    assert.equal(screen.targets.length, attendus.length + 1);
    assert.deepEqual(screen.targets[0], {
      kind: "go",
      route: { screen: "article", itemId: cosmetic.id, shelf: cosmetic.categorie, page: 0 },
    });
    assert.deepEqual(screen.targets[attendus.length], { kind: "go", route: { screen: "rayon", rayon: "cosmetiques", page: 0 } });
    assert.ok(bare(screen.spec.body).includes(BOUTIQUE_TEXTES.categories[cosmetic.categorie]));
  });

  it("un cosmétique possédé est marqué, et un rayon sans article le dit", () => {
    const cosmetic = must(VENDUS[0]);
    const screen = must(rayonScreen(catalogOf({ [cosmetic.id]: { owned: true } }), cosmetic.categorie, 0, 8));
    assert.equal(labelsOf(screen)[0], `${ICON.possede} ${cosmetic.nom}\n${BOUTIQUE_TEXTES.details.possede}`);
    const vide = must(rayonScreen({ kind: "catalog", items: [catalogItem(must(GRADES[0]).id)] }, "pet", 0, 8));
    assert.ok(bare(vide.spec.body).includes(BOUTIQUE_TEXTES.details.rayonVide));
    assert.deepEqual(kindsOf(vide), ["go:rayon"]);
  });

  it("shelfParent et articleParent disent où mène Retour", () => {
    assert.deepEqual(shelfParent("grades"), { screen: "boutique" });
    assert.deepEqual(shelfParent("cosmetiques"), { screen: "boutique" });
    assert.deepEqual(shelfParent("pet"), { screen: "rayon", rayon: "cosmetiques", page: 0 });
    assert.deepEqual(articleParent("direct", 3), { screen: "boutique" });
    assert.deepEqual(articleParent("grades", 2), { screen: "rayon", rayon: "grades", page: 2 });
  });
});

describe("articleScreen", () => {
  it("un grade à acheter : son nom, le prix du service, son bonus, les Gemmes du joueur, Acheter, Offrir, Retour", () => {
    const grade = must(GRADES[0]);
    const screen = must(articleScreen(viewOf({ gemmes: 12 }), catalogOf({ [grade.id]: { gemmes: 777 } }), grade.id, "grades", 0));
    assert.equal(screen.spec.title, BOUTIQUE_TEXTES.article.titre);
    const body = bare(screen.spec.body);
    assert.ok(body.includes(grade.nom), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.prix("777")), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.grade(String(grade.bonusDonarPourcent))), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.gradeOrdre), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.solde("12")), body);
    assert.deepEqual(labelsOf(screen), [
      `${bare(ICON.gemmes)} Acheter\n${BOUTIQUE_TEXTES.details.prix("777")}`,
      `${ICON.cadeau} Offrir\n${BOUTIQUE_TEXTES.details.prix("777")}`,
      "Retour",
    ]);
    assert.deepEqual(screen.targets, [
      { kind: "buyItem", itemId: grade.id },
      { kind: "go", route: { screen: "offrir", itemId: grade.id, shelf: "grades", page: 0, choix: 0 } },
      { kind: "go", route: { screen: "rayon", rayon: "grades", page: 0 } },
    ]);
  });

  it("un grade pas encore débloqué : le cadenas et ce qu'il faut, pas d'Acheter, mais on peut l'offrir", () => {
    const grade = must(GRADES[2]);
    const screen = must(articleScreen(viewOf(), catalogOf({ [grade.id]: { available: false } }), grade.id, "grades", 0));
    const body = bare(screen.spec.body);
    assert.ok(body.includes(`${ICON.cadenas} ${BOUTIQUE_TEXTES.article.bloque}`), body);
    assert.deepEqual(kindsOf(screen), ["go:offrir", "go:rayon"]);
  });

  it("un grade possédé : marqué, pas d'Acheter", () => {
    const grade = must(GRADES[0]);
    const screen = must(articleScreen(viewOf(), catalogOf({ [grade.id]: { owned: true, available: false } }), grade.id, "grades", 0));
    assert.ok(bare(screen.spec.body).includes(`${ICON.possede} ${BOUTIQUE_TEXTES.article.possede}`));
    assert.ok(!kindsOf(screen).includes("buyItem"));
  });

  it("le Pilier en cours se prolonge : bouton Prolonger, ses jours, son bonus et les Gemmes rendues du JSON", () => {
    const pilier = must(PILIER);
    const screen = must(articleScreen(viewOf(), catalogOf({ pilier: { owned: true } }), "pilier", "direct", 0));
    const body = bare(screen.spec.body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.enCours), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.abonnement(String(pilier.jours))), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.abonnementBonus(String(pilier.bonusDonarPourcent))), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.abonnementRendu(String(pilier.gemmesRendues))), body);
    assert.ok(labelsOf(screen)[0]?.startsWith(`${bare(ICON.gemmes)} ${BOUTIQUE_TEXTES.article.prolonger}`), labelsOf(screen)[0]);
    assert.deepEqual(screen.targets[0], { kind: "buyItem", itemId: "pilier" });
    assert.deepEqual(screen.targets[screen.targets.length - 1], { kind: "go", route: { screen: "boutique" } });
  });

  it("le boost collectif : Acheter sans Offrir, la durée dite en mots, le pseudo annoncé", () => {
    const screen = must(articleScreen(viewOf(), catalogOf(), "boost-collectif", "direct", 0));
    const body = bare(screen.spec.body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.boostPseudo), body);
    assert.ok(body.includes(BOUTIQUE_TEXTES.article.boostSuite), body);
    assert.ok(body.includes(`+${BOOST.bonusPourcent} %`), body);
    assert.ok(body.includes(dureeEnMots(BOOST.dureeMinutes)), body);
    assert.deepEqual(kindsOf(screen), ["buyItem", "go:boutique"]);
  });

  it("un cosmétique : sa catégorie ; possédé, plus d'Acheter ; Retour remonte à sa page de catégorie", () => {
    const cosmetic = must(VENDUS[0]);
    const libre = must(articleScreen(viewOf(), catalogOf(), cosmetic.id, cosmetic.categorie, 1));
    assert.ok(bare(libre.spec.body).includes(BOUTIQUE_TEXTES.article.cosmetique(BOUTIQUE_TEXTES.categories[cosmetic.categorie])));
    assert.deepEqual(libre.targets[libre.targets.length - 1], {
      kind: "go",
      route: { screen: "rayon", rayon: cosmetic.categorie, page: 1 },
    });
    const possede = must(articleScreen(viewOf(), catalogOf({ [cosmetic.id]: { owned: true } }), cosmetic.id, cosmetic.categorie, 0));
    assert.ok(!kindsOf(possede).includes("buyItem"));
    assert.ok(bare(possede.spec.body).includes(BOUTIQUE_TEXTES.article.possede));
  });

  it("un article que le catalogue n'a plus, ou un catalogue pas lu : undefined", () => {
    assert.equal(articleScreen(viewOf(), catalogOf(), "inconnu", "grades", 0), undefined);
    assert.equal(articleScreen(viewOf(), undefined, must(GRADES[0]).id, "grades", 0), undefined);
    assert.equal(articleScreen(viewOf(), FAILED, must(GRADES[0]).id, "grades", 0), undefined);
  });
});

describe("offrirScreen", () => {
  const AUTRES = [
    { xuid: XUID, name: "Alex" },
    { xuid: "2", name: "Bea" },
    { xuid: "3", name: "Cleo" },
  ];
  const cosmetic = must(VENDUS[0]);

  it("liste les joueurs connectés sauf soi-même ; chacun mène à l'offre, Retour revient à l'article", () => {
    const screen = must(offrirScreen(catalogOf(), AUTRES, XUID, cosmetic.id, cosmetic.categorie, 0, 0, 8));
    assert.equal(screen.spec.title, BOUTIQUE_TEXTES.offrir.titre);
    assert.deepEqual(labelsOf(screen), ["Bea", "Cleo", "Retour"]);
    assert.deepEqual(screen.targets, [
      { kind: "giftItem", itemId: cosmetic.id, forXuid: "2" },
      { kind: "giftItem", itemId: cosmetic.id, forXuid: "3" },
      { kind: "go", route: { screen: "article", itemId: cosmetic.id, shelf: cosmetic.categorie, page: 0 } },
    ]);
    assert.ok(bare(screen.spec.body).includes(BOUTIQUE_TEXTES.offrir.phrase(cosmetic.nom, String(shopCatalog().find((item) => item.itemId === cosmetic.id)?.gemmes))));
  });

  it("personne d'autre : la phrase le dit et il ne reste que Retour", () => {
    const screen = must(offrirScreen(catalogOf(), [{ xuid: XUID, name: "Alex" }], XUID, cosmetic.id, cosmetic.categorie, 0, 0, 8));
    assert.ok(bare(screen.spec.body).includes(BOUTIQUE_TEXTES.offrir.aucun));
    assert.deepEqual(kindsOf(screen), ["go:article"]);
  });

  it("des pages de joueurs : la navigation garde l'article et la page d'où l'on vient", () => {
    const foule = Array.from({ length: 5 }, (_, i) => ({ xuid: String(10 + i), name: `Joueur${i}` }));
    const screen = must(offrirScreen(catalogOf(), foule, XUID, cosmetic.id, cosmetic.categorie, 2, 0, 2));
    assert.deepEqual(labelsOf(screen), ["Joueur0", "Joueur1", "Suite", "Retour"]);
    assert.deepEqual(screen.targets[2], {
      kind: "go",
      route: { screen: "offrir", itemId: cosmetic.id, shelf: cosmetic.categorie, page: 2, choix: 1 },
    });
  });

  it("le boost ne s'offre pas, et un article disparu ou un catalogue pas lu rendent undefined", () => {
    assert.equal(offrirScreen(catalogOf(), AUTRES, XUID, "boost-collectif", "direct", 0, 0, 8), undefined);
    assert.equal(offrirScreen(catalogOf(), AUTRES, XUID, "inconnu", "direct", 0, 0, 8), undefined);
    assert.equal(offrirScreen(undefined, AUTRES, XUID, cosmetic.id, cosmetic.categorie, 0, 0, 8), undefined);
  });
});

describe("purchaseContext, pioneerLine et boutiqueNotice", () => {
  const bought = (itemId: string, forXuid?: string): ActionOutcome =>
    forXuid === undefined ? { kind: "itemBought", itemId } : { kind: "itemBought", itemId, forXuid };
  const noContext = { gift: false, extended: false };

  it("purchaseContext : un cadeau n'est jamais une prolongation ; le Pilier possédé se prolonge", () => {
    assert.deepEqual(purchaseContext(catalogOf(), must(GRADES[0]).id, false), { gift: false, extended: false });
    assert.deepEqual(purchaseContext(catalogOf({ pilier: { owned: true } }), "pilier", false), { gift: false, extended: true });
    assert.deepEqual(purchaseContext(catalogOf({ pilier: { owned: true } }), "pilier", true), { gift: true, extended: false });
    assert.deepEqual(purchaseContext(catalogOf(), "pilier", false), { gift: false, extended: false });
    assert.deepEqual(purchaseContext(undefined, "pilier", false), { gift: false, extended: false });
  });

  it("pioneerLine : le titre du joueur sous son nom quand join dit qu'il l'a, rien sinon", () => {
    const line = pioneerLine(["s1-fanion", "pionnier"]);
    assert.equal(bare(line ?? ""), BOUTIQUE_TEXTES.titreDuJoueur(cosmeticName("pionnier")));
    assert.equal(pioneerLine([]), undefined);
    assert.equal(pioneerLine(["s1-fanion"]), undefined);
  });

  it("un achat réussi : la bulle selon l'article (achat, prolongation, boost, pass premium, cadeau)", () => {
    const bulles = BOUTIQUE_TEXTES.bulles;
    assert.deepEqual(boutiqueNotice(bought("vip"), noContext), { bubble: bulles.achete });
    assert.deepEqual(boutiqueNotice(bought("pilier"), { gift: false, extended: true }), { bubble: bulles.prolonge });
    assert.deepEqual(boutiqueNotice(bought("boost-collectif"), noContext), { bubble: bulles.boostLance });
    assert.deepEqual(boutiqueNotice(bought("pass-premium"), noContext), { bubble: PASS_TEXTES.bulles.premiumActif });
    assert.deepEqual(boutiqueNotice(bought("chat-atelier", "2"), { gift: true, extended: false }), { bubble: bulles.offert });
  });

  it("PAS_ASSEZ_DE_GEMMES : la bulle de refus avec ce qui manque, puis où acheter des Gemmes", () => {
    const notice = boutiqueNotice({ kind: "refused", reason: "PAS_ASSEZ_DE_GEMMES", missing: 40 }, noContext);
    assert.deepEqual(notice, { bubble: refusalBubble("PAS_ASSEZ_DE_GEMMES", 40), lines: [BOUTIQUE_TEXTES.gemmesManquent] });
  });

  it("un cadeau refusé dit des mots à lui ; un achat pour soi laisse la bulle du menu", () => {
    const cadeau = { gift: true, extended: false };
    assert.deepEqual(boutiqueNotice({ kind: "refused", reason: "DEJA_POSSEDE" }, cadeau), { bubble: BOUTIQUE_TEXTES.bulles.dejaChezLui });
    assert.deepEqual(boutiqueNotice({ kind: "refused", reason: "GRADE_PRECEDENT_REQUIS" }, cadeau), {
      bubble: BOUTIQUE_TEXTES.bulles.gradePrecedentPourLui,
    });
    assert.equal(boutiqueNotice({ kind: "refused", reason: "DEJA_POSSEDE" }, noContext), undefined);
    assert.equal(boutiqueNotice({ kind: "refused", reason: "GRADE_PRECEDENT_REQUIS" }, noContext), undefined);
    assert.equal(boutiqueNotice({ kind: "refused", reason: "SAISON_INACTIVE" }, noContext), undefined);
  });

  it("un 400 : l'article n'est plus en vente, ou le destinataire est inconnu pour un cadeau", () => {
    const rejete: ActionOutcome = { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" };
    assert.deepEqual(boutiqueNotice(rejete, noContext), { bubble: BOUTIQUE_TEXTES.bulles.plusEnVente });
    assert.deepEqual(boutiqueNotice(rejete, { gift: true, extended: false }), { bubble: ASSOCIES_TEXTES.bulles.joueurInconnu });
  });

  it("les autres résultats laissent la bulle au menu", () => {
    assert.equal(boutiqueNotice({ kind: "busy" }, noContext), undefined);
    assert.equal(boutiqueNotice({ kind: "failed", failure: "network", reason: "RESEAU" }, noContext), undefined);
    assert.equal(boutiqueNotice({ kind: "collected", collected: 1, offlineHours: 0 }, noContext), undefined);
  });
});
