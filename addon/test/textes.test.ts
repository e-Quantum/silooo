import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import { MEMBER_ROLES, PASS_TRACKS, REFUSAL_REASONS, STAFF_RARITIES, STAFF_ROLES } from "../src/protocol";
import { COLOR, EXPRESSIONS, EXPRESSION_MARK, GLYPH, ICON, RARITY_COLOR, colored } from "../src/ui/apparence";
import { formatAmount } from "../src/ui/nombres";
import {
  AGENCE_TEXTES,
  ANNONCES_TEXTES,
  ASSOCIES_TEXTES,
  BATIMENT_NOMS,
  BOUSSOLE_TEXTES,
  BOUTIQUE_TEXTES,
  BULLES,
  CARTE_TEXTES,
  CINEMATIQUE_TEXTES,
  FACTORY_IDS,
  FACTORY_NAMES,
  FICHE_TEXTES,
  HUD_TEXTES,
  INCIDENT_TEXTES,
  INCIDENT_TEXTES_KINDS,
  MENU_TEXTES,
  MISSION_TEXTES,
  MISSION_TEXTES_IDS,
  OBJECTIFS_TEXTES,
  PASS_TEXTES,
  PERSONNEL_TEXTES,
  PRESTIGE_TEXTES,
  REFUSAL_BUBBLE_REASONS,
  REPARATION_TEXTES,
  SILO_TEXTES,
  USINES_TEXTES,
  VILLE_TEXTES,
  VOTE_TEXTES,
  dureeEnMots,
  eraName,
  factoryName,
  failureBubble,
  incidentTexts,
  missionEndBubble,
  missionTexts,
  missionTitle,
  refusalBubble,
  renderBubble,
  repairSteps,
  staffRefusalBubble,
  type Bubble,
} from "../src/ui/textes";

const mots = (texte: string): string[] => texte.split(/\s+/).filter((mot) => mot !== "");

/** Toutes les bulles qu'Oooscar peut dire : les fixes, un refus par code (avec et sans somme), un échec par sorte. */
function toutesLesBulles(): Array<{ nom: string; bulle: Bubble }> {
  const liste: Array<{ nom: string; bulle: Bubble }> = Object.entries(BULLES).map(([nom, bulle]) => ({ nom, bulle }));
  for (const code of REFUSAL_BUBBLE_REASONS) {
    for (const somme of [undefined, 0, 1, 120, 1_500, 123_456_789_012, 3e29, 5e120]) {
      liste.push({ nom: `${code} ${somme ?? "sans somme"}`, bulle: refusalBubble(code, somme) });
    }
  }
  // Dans le contexte du personnel, deux refus ont d'autres mots : chaque code passe aussi par staffRefusalBubble.
  for (const code of REFUSAL_BUBBLE_REASONS) {
    for (const somme of [undefined, 0, 120, 123_456_789_012]) {
      liste.push({ nom: `personnel ${code} ${somme ?? "sans somme"}`, bulle: staffRefusalBubble(code, somme) });
    }
  }
  for (const failure of ["network", "error", "unavailable"] as const) {
    for (const reason of ["RESEAU", "PAS_FAIT", "ERREUR_INTERNE"]) {
      liste.push({ nom: `${failure} ${reason}`, bulle: failureBubble(failure, reason) });
    }
  }
  for (const [id, textes] of Object.entries(MISSION_TEXTES)) liste.push({ nom: `fin de ${id}`, bulle: textes.fin });
  for (const [cle, bulle] of Object.entries(ASSOCIES_TEXTES.bulles)) liste.push({ nom: `associés ${cle}`, bulle });
  for (const [cle, bulle] of Object.entries(VILLE_TEXTES.bulles)) liste.push({ nom: `ville ${cle}`, bulle });
  for (const [cle, bulle] of Object.entries(BOUTIQUE_TEXTES.bulles)) liste.push({ nom: `boutique ${cle}`, bulle });
  for (const [cle, bulle] of Object.entries(PASS_TEXTES.bulles)) liste.push({ nom: `pass ${cle}`, bulle });
  liste.push(
    { nom: "vote rappel", bulle: VOTE_TEXTES.rappel.bulle },
    { nom: "vote reçu", bulle: VOTE_TEXTES.vote.bulle },
    { nom: "vote party", bulle: VOTE_TEXTES.voteParty.bulle },
    { nom: "annonce gemmes", bulle: ANNONCES_TEXTES.gemmes.bulle },
    { nom: "annonce pionnier", bulle: ANNONCES_TEXTES.pionnier },
    { nom: "annonce sans courant", bulle: ANNONCES_TEXTES.sansCourant.bulle },
  );
  // L'indice d'Oooscar à chaque étape du mini-jeu de réparation, pour chaque sorte et pour le mini-jeu général.
  for (const kind of [...INCIDENT_TEXTES_KINDS, null]) {
    repairSteps(kind).forEach((step, index) => liste.push({ nom: `réparation ${kind ?? "générale"} étape ${index + 1}`, bulle: step.erreur }));
  }
  return liste;
}

describe("bulles d'Oooscar", () => {
  it("six mots au plus, une des cinq expressions, pas de saut de ligne", () => {
    for (const { nom, bulle } of toutesLesBulles()) {
      assert.ok(mots(bulle.text).length <= 6, `${nom} : « ${bulle.text} » a plus de six mots`);
      assert.ok(EXPRESSIONS.includes(bulle.expression), `${nom} : expression ${bulle.expression}`);
      assert.doesNotMatch(bulle.text, /\n/);
    }
  });

  it("chaque code `reason` de l'OpenAPI a sa bulle, et rien d'autre", () => {
    const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");
    const debut = yaml.indexOf("    Refused:");
    assert.notEqual(debut, -1);
    const ouvre = yaml.indexOf("[", yaml.indexOf("enum:", debut));
    const ferme = yaml.indexOf("]", ouvre);
    const contrat = yaml
      .slice(ouvre + 1, ferme)
      .split(",")
      .map((code) => code.trim())
      .filter((code) => code !== "");
    assert.deepEqual([...REFUSAL_BUBBLE_REASONS].sort(), [...contrat].sort());
    assert.deepEqual([...REFUSAL_BUBBLE_REASONS].sort(), [...REFUSAL_REASONS].sort());
  });

  it("un refus qui demande une somme la dit, sans somme il garde une phrase complète", () => {
    assert.equal(refusalBubble("PAS_ASSEZ_DE_DONAR", 120).text, "Il te manque 120 Donar.");
    assert.equal(refusalBubble("PAS_ASSEZ_DE_LINGOTS", 3).text, "Il te manque 3 Lingots.");
    assert.equal(refusalBubble("PAS_ASSEZ_DE_GEMMES", 2).text, "Il te manque 2 Gemmes.");
    assert.equal(refusalBubble("PAS_ASSEZ_DE_DONAR").text, "Il te manque du Donar.");
  });

  it("un code que l'add-on ne connaît pas donne la phrase de repli, jamais une exception", () => {
    assert.equal(refusalBubble("CODE_DU_FUTUR"), BULLES.refusInconnu);
    assert.equal(refusalBubble("constructor"), BULLES.refusInconnu);
    assert.equal(refusalBubble("toString", 5), BULLES.refusInconnu);
  });

  it("DEJA_POSSEDE est la seule bulle fière : on n'est pas fier d'un refus", () => {
    const fieres = REFUSAL_BUBBLE_REASONS.filter((code) => refusalBubble(code).expression === "fier");
    assert.deepEqual(fieres, ["DEJA_POSSEDE"]);
  });

  it("les situations de panique sont celles où le service ne répond pas", () => {
    assert.equal(failureBubble("network", "RESEAU").expression, "panique");
    assert.equal(failureBubble("unavailable", "X").expression, "panique");
    assert.equal(failureBubble("error", "ERREUR_INTERNE").expression, "panique");
    assert.equal(failureBubble("error", "PAS_FAIT").expression, "oups");
  });

  it("la bulle s'écrit sur une ligne : nom, visage de l'expression, phrase", () => {
    assert.equal(renderBubble(BULLES.bientot).replaceAll(/§./g, ""), `Oooscar ${EXPRESSION_MARK.oups} Pas encore disponible.`);
    assert.ok(renderBubble(BULLES.montee).startsWith(COLOR.muted));
    assert.ok(renderBubble(BULLES.montee).includes(`${COLOR.paper}${EXPRESSION_MARK.joie}`), "le visage n'est pas teinté du gris du nom");
  });

  it("les cinq expressions sont celles des images de design/mascotte, ni plus ni moins", () => {
    const fichiers = readdirSync(resolve("..", "design", "mascotte"))
      .filter((nom) => nom.endsWith(".svg"))
      .map((nom) => nom.replace(/^oooscar-/, "").replace(/\.svg$/, ""))
      .sort();
    assert.deepEqual([...EXPRESSIONS].sort(), fichiers);
    assert.deepEqual(Object.keys(EXPRESSION_MARK).sort(), fichiers);
  });
});

describe("noms des usines", () => {
  it("recopient definitions/usines.json : mêmes identifiants, mêmes noms", () => {
    const donnees = JSON.parse(readFileSync(resolve("..", "definitions", "usines.json"), "utf8")) as {
      usines: Array<{ id: string; nom: string }>;
    };
    assert.deepEqual(
      Object.entries(FACTORY_NAMES).sort(([a], [b]) => a.localeCompare(b)),
      donnees.usines.map((u) => [u.id, u.nom] as [string, string]).sort(([a], [b]) => a.localeCompare(b)),
    );
  });

  it("un identifiant que l'add-on ne connaît pas encore devient un nom lisible", () => {
    assert.equal(factoryName("carriere"), "Carrière");
    assert.equal(factoryName("usine-neuve"), "Usine neuve");
    assert.equal(factoryName("tour"), "Tour");
    assert.equal(factoryName("constructor"), "Constructor");
    assert.equal(factoryName("-"), "-");
    assert.equal(factoryName(""), "");
  });
});

// ---------------------------------------------------------------- règles d'écriture

/** Tous les textes fixes des écrans, avec leur chemin, pour les contrôles de style. Les fonctions sont appelées avec des mots d'essai. */
function feuilles(valeur: unknown, chemin: string): Array<{ chemin: string; texte: string }> {
  if (typeof valeur === "string") return [{ chemin, texte: valeur }];
  if (typeof valeur === "function") {
    const appel = valeur as (...args: unknown[]) => unknown;
    const nombres = [appel(0, 0, 0), appel(1, 2, "x"), appel(2, 4, "x")].filter((t): t is string => typeof t === "string");
    const textes = [appel("a", "b", "c")].filter((t): t is string => typeof t === "string");
    return [...nombres, ...textes].map((texte) => ({ chemin: `${chemin}()`, texte }));
  }
  if (Array.isArray(valeur)) return valeur.flatMap((v, i) => feuilles(v, `${chemin}[${i}]`));
  if (typeof valeur === "object" && valeur !== null) {
    return Object.entries(valeur).flatMap(([cle, v]) => feuilles(v, `${chemin}.${cle}`));
  }
  return [];
}

const TOUS = [
  ...feuilles(HUD_TEXTES, "HUD_TEXTES"),
  ...feuilles(BOUSSOLE_TEXTES, "BOUSSOLE_TEXTES"),
  ...feuilles(MENU_TEXTES, "MENU_TEXTES"),
  ...feuilles(USINES_TEXTES, "USINES_TEXTES"),
  ...feuilles(FICHE_TEXTES, "FICHE_TEXTES"),
  ...feuilles(SILO_TEXTES, "SILO_TEXTES"),
  ...feuilles(OBJECTIFS_TEXTES, "OBJECTIFS_TEXTES"),
  ...feuilles(PRESTIGE_TEXTES, "PRESTIGE_TEXTES"),
  ...feuilles(CARTE_TEXTES, "CARTE_TEXTES"),
  ...feuilles(PERSONNEL_TEXTES, "PERSONNEL_TEXTES"),
  ...feuilles(AGENCE_TEXTES, "AGENCE_TEXTES"),
  ...feuilles(ASSOCIES_TEXTES, "ASSOCIES_TEXTES"),
  ...feuilles(VILLE_TEXTES, "VILLE_TEXTES"),
  ...feuilles(BOUTIQUE_TEXTES, "BOUTIQUE_TEXTES"),
  ...feuilles(PASS_TEXTES, "PASS_TEXTES"),
  ...feuilles(VOTE_TEXTES, "VOTE_TEXTES"),
  ...feuilles(ANNONCES_TEXTES, "ANNONCES_TEXTES"),
  ...feuilles(BATIMENT_NOMS, "BATIMENT_NOMS"),
  ...feuilles(INCIDENT_TEXTES, "INCIDENT_TEXTES"),
  ...feuilles(REPARATION_TEXTES, "REPARATION_TEXTES"),
  ...Object.entries(MISSION_TEXTES).flatMap(([id, t]) => [
    { chemin: `MISSION_TEXTES.${id}.titre`, texte: t.titre },
    { chemin: `MISSION_TEXTES.${id}.consigne`, texte: t.consigne },
  ]),
  ...feuilles(CINEMATIQUE_TEXTES, "CINEMATIQUE_TEXTES"),
  ...toutesLesBulles().map(({ nom, bulle }) => ({ chemin: `bulle ${nom}`, texte: bulle.text })),
];

/** Le chemin du seul texte qui porte des points d'interrogation : le nom caché d'une usine que le joueur n'a pas encore. */
const QUESTIONS_PERMISES = "CARTE_TEXTES.inconnue";

describe("règles d'écriture (design/README.md, « Écrire sans slop »)", () => {
  it("le contrôle trouve les textes", () => {
    assert.ok(TOUS.length > 100);
  });

  it("ni emoji, ni point de suspension, ni « !! », ni tiret cadratin, ni question", () => {
    for (const { chemin, texte } of TOUS) {
      assert.doesNotMatch(texte, /\p{Extended_Pictographic}/u, chemin);
      assert.doesNotMatch(texte, /\.\.\.|…|!!|—/, `${chemin} : ${texte}`);
      if (chemin !== QUESTIONS_PERMISES) assert.doesNotMatch(texte, /\?/, `${chemin} : ${texte}`);
    }
  });

  it("le seul point d'interrogation est le « ????? » de la carte : une usine cachée, pas une question", () => {
    const avec = TOUS.filter(({ texte }) => texte.includes("?"));
    assert.deepEqual(avec, [{ chemin: QUESTIONS_PERMISES, texte: "?????" }]);
  });

  it("tutoiement : jamais « vous », « votre » ni « vos »", () => {
    for (const { chemin, texte } of TOUS) assert.doesNotMatch(texte, /\b(vous|votre|vos)\b/i, chemin);
  });

  it("les titres de fenêtre sont en capitales, d'un ou deux mots", () => {
    for (const titre of [
      MENU_TEXTES.titre,
      USINES_TEXTES.titre,
      FICHE_TEXTES.titre,
      SILO_TEXTES.titre,
      PRESTIGE_TEXTES.titre,
      CARTE_TEXTES.titre,
      ASSOCIES_TEXTES.titre,
      ASSOCIES_TEXTES.invitation.titre,
      ASSOCIES_TEXTES.choix.titre,
      ASSOCIES_TEXTES.formulaire.titre,
      VILLE_TEXTES.titre,
      VILLE_TEXTES.construire.titre,
      VILLE_TEXTES.poser.titre,
      BOUTIQUE_TEXTES.titre,
      BOUTIQUE_TEXTES.rayon.titre,
      BOUTIQUE_TEXTES.article.titre,
      BOUTIQUE_TEXTES.offrir.titre,
      PASS_TEXTES.titre,
      PASS_TEXTES.paliers.titre,
      PASS_TEXTES.palier.titre,
    ]) {
      assert.match(titre, /^[A-ZÉ]+( [A-ZÉ]+)?$/);
    }
  });

  it("la phrase d'une fiche tient en dix mots, sur une ligne", () => {
    for (const phrase of Object.values(FICHE_TEXTES.phrase)) {
      assert.ok(mots(phrase).length <= 10, phrase);
      assert.doesNotMatch(phrase, /\n/);
    }
  });

  it("les boutons sont courts et sans ponctuation finale ; ceux d'action tiennent en un seul mot", () => {
    const boutons = [
      SILO_TEXTES.encaisser,
      ...Object.values(FICHE_TEXTES.monter),
      ...FICHE_TEXTES.etapes.map((etape) => etape.bouton),
      MENU_TEXTES.retour,
      USINES_TEXTES.precedent,
      USINES_TEXTES.suite,
      PRESTIGE_TEXTES.avant.retour,
      PRESTIGE_TEXTES.apres.usines,
      PRESTIGE_TEXTES.apres.retour,
      CARTE_TEXTES.retour,
      BOUTIQUE_TEXTES.retour,
      BOUTIQUE_TEXTES.article.acheter,
      BOUTIQUE_TEXTES.article.prolonger,
      BOUTIQUE_TEXTES.article.offrir,
      BOUTIQUE_TEXTES.article.retour,
      BOUTIQUE_TEXTES.offrir.retour,
      ...Object.values(BOUTIQUE_TEXTES.rayons),
      ...Object.values(BOUTIQUE_TEXTES.categories),
      PASS_TEXTES.retour,
      PASS_TEXTES.paliers.page,
      PASS_TEXTES.passerPremium,
      ...Object.values(PASS_TEXTES.reclamer),
    ];
    for (const bouton of boutons) {
      assert.ok(mots(bouton).length >= 1 && mots(bouton).length <= 4, bouton);
      assert.doesNotMatch(bouton, /[.!?]$/, bouton);
    }
    for (const bouton of [SILO_TEXTES.encaisser, ...FICHE_TEXTES.etapes.map((etape) => etape.bouton)]) {
      assert.equal(mots(bouton).length, 1, bouton);
    }
  });

  it("la cinématique : Oooscar parle, une phrase de huit mots au plus par plan, sur une ligne", () => {
    assert.equal(CINEMATIQUE_TEXTES.qui, "Oooscar");
    assert.equal(CINEMATIQUE_TEXTES.plans.length, 4);
    for (const phrase of CINEMATIQUE_TEXTES.plans) {
      assert.ok(mots(phrase).length >= 1 && mots(phrase).length <= 8, phrase);
      assert.doesNotMatch(phrase, /\n/);
      assert.match(phrase, /[.]$/, "une phrase finit par un point");
    }
    assert.ok(mots(CINEMATIQUE_TEXTES.passer).length <= 8);
  });

  it("aucun texte ne dépasse deux lignes", () => {
    for (const { chemin, texte } of TOUS) {
      assert.ok(texte.split("\n").length <= 2, chemin);
    }
  });

  it("l'habillage n'ajoute que des codes de couleur, des repères entre crochets ou des glyphes de la planche, et un nom", () => {
    assert.equal(colored(COLOR.donar, "x"), `${COLOR.donar}x${COLOR.reset}`);
    const glyphes = new Set<string>(Object.values(GLYPH));
    for (const repere of Object.values(ICON)) assert.ok(/^\[[A-Z!]\]$/.test(repere) || glyphes.has(repere), repere);
    for (const repere of Object.values(EXPRESSION_MARK)) assert.ok(glyphes.has(repere), repere);
    for (const code of Object.values(COLOR)) assert.match(code, /^§[0-9a-v]$/);
  });

  it("chaque glyphe est le caractère de sa case dans design/glyphes.json, et sa case a une image", () => {
    const plan = JSON.parse(readFileSync(resolve(process.cwd(), "../design/glyphes.json"), "utf8")) as {
      page: string;
      glyphes: Array<{ nom: string; source: string; case: number }>;
    };
    for (const [nom, glyphe] of Object.entries(GLYPH)) {
      const entree = plan.glyphes.find((g) => g.nom === nom);
      assert.ok(entree, `${nom} est dans design/glyphes.json`);
      const attendu = String.fromCodePoint(parseInt(`${plan.page}${entree.case.toString(16).padStart(2, "0")}`, 16));
      assert.equal(glyphe, attendu, `${nom} : U+${plan.page}${entree.case.toString(16).padStart(2, "0").toUpperCase()}`);
    }
    for (const expression of EXPRESSIONS) {
      assert.equal(EXPRESSION_MARK[expression], GLYPH[`oooscar-${expression}`], `le visage ${expression} est l'image oooscar-${expression}`);
    }
  });
});

// ---------------------------------------------------------------- tout est dans le README, « à valider par Kr »

describe("addon/README.md, section « Textes à valider »", () => {
  const readme = readFileSync(resolve("README.md"), "utf8");
  const debut = readme.indexOf("## Textes à valider");
  const fin = readme.indexOf("\n## ", debut + 1);
  const section = debut < 0 ? "" : readme.slice(debut, fin < 0 ? undefined : fin);

  it("existe", () => {
    assert.ok(debut >= 0, "la section « Textes à valider » manque");
    assert.match(section, /à valider par Kr/);
  });

  it("donne l'avant (rien : ces écrans sont nouveaux) et l'après", () => {
    assert.match(section, /\| Où \| Avant \| Après \|/);
    assert.match(section, /rien/);
  });

  it("contient chaque morceau de texte de ui/textes.ts, sans exception", () => {
    const source = readFileSync(resolve("src", "ui", "textes.ts"), "utf8");
    // On enlève les imports, les commentaires et la table des noms d'usines (elle recopie definitions/usines.json).
    const sansNoms = source.replace(/export const FACTORY_NAMES[\s\S]*?\n};/, "");
    const lignes = sansNoms
      .split("\n")
      .filter((ligne) => !/^\s*(import |\/\/|\*|\/\*)/.test(ligne))
      .join("\n");
    const morceaux = new Set<string>();
    for (const m of lignes.matchAll(/"((?:[^"\\\n]|\\.)*)"(?!\s*:)/g)) morceaux.add(m[1] ?? "");
    for (const m of lignes.matchAll(/`((?:[^`\\]|\\.)*)`/g)) {
      for (const part of (m[1] ?? "").split(/\$\{[^}]*\}/)) morceaux.add(part);
    }
    const cherches = [...morceaux].map((morceau) => morceau.trim()).filter((morceau) => morceau.length >= 3);
    assert.ok(cherches.length > 60, `${cherches.length} morceaux trouvés : le contrôle est cassé`);
    const manquants = cherches.filter((morceau) => !section.includes(morceau));
    assert.deepEqual(manquants, [], "ces textes ne sont pas dans la section « Textes à valider » du README");
  });

  it("dit où sont les noms d'usines et les repères provisoires, qui ne sont pas des textes de ui/textes.ts", () => {
    assert.match(section, /definitions\/usines\.json/);
    assert.match(section, /ui\/apparence\.ts/);
  });
});

// ---------------------------------------------------------------- les missions

describe("textes des missions", () => {
  const donnees = JSON.parse(readFileSync(resolve("..", "definitions", "missions.json"), "utf8")) as {
    missions: Array<{ id: string }>;
  };
  const idsDuJeu = donnees.missions.map((mission) => mission.id);

  it("le contrôle trouve les missions du jeu", () => {
    assert.ok(idsDuJeu.length >= 1);
    assert.ok(idsDuJeu.includes("bienvenue-mairie"));
  });

  it("chaque identifiant de definitions/missions.json a ses textes : titre, consigne et bulle de fin", () => {
    for (const id of idsDuJeu) {
      const textes = missionTexts(id);
      assert.notEqual(textes, undefined, `la mission « ${id} » n'a pas de textes dans ui/textes.ts`);
      assert.ok((textes?.titre ?? "") !== "", `${id} : titre vide`);
      assert.ok((textes?.consigne ?? "") !== "", `${id} : consigne vide`);
      assert.ok((textes?.fin.text ?? "") !== "", `${id} : bulle de fin vide`);
    }
  });

  it("aucun texte ne vise un identifiant que definitions/missions.json ne connaît pas", () => {
    const inconnus = MISSION_TEXTES_IDS.filter((id) => !idsDuJeu.includes(id));
    assert.deepEqual(inconnus, [], "ces textes de mission n'ont pas de mission dans definitions/missions.json");
    assert.deepEqual([...MISSION_TEXTES_IDS].sort(), [...idsDuJeu].sort());
  });

  it("bienvenue-mairie : le joueur doit encaisser une fois", () => {
    const textes = missionTexts("bienvenue-mairie");
    assert.equal(textes?.titre, "Bienvenue à la mairie");
    assert.equal(textes?.consigne, "Encaisse au Silo");
    assert.equal(textes?.fin.expression, "joie");
    assert.match(textes?.fin.text ?? "", /encaissement/);
  });

  it("le titre tient en cinq mots, la consigne en six, sans point final : ce sont des étiquettes", () => {
    for (const [id, textes] of Object.entries(MISSION_TEXTES)) {
      assert.ok(mots(textes.titre).length >= 1 && mots(textes.titre).length <= 5, `${id} : ${textes.titre}`);
      assert.ok(mots(textes.consigne).length >= 1 && mots(textes.consigne).length <= 6, `${id} : ${textes.consigne}`);
      assert.doesNotMatch(textes.titre, /[.!?]$/, id);
      assert.doesNotMatch(textes.consigne, /[.!?]$/, id);
      assert.doesNotMatch(`${textes.titre}${textes.consigne}`, /\n/, id);
    }
  });

  it("la bulle de fin est dite par Oooscar : six mots au plus, une des cinq expressions", () => {
    for (const [id, textes] of Object.entries(MISSION_TEXTES)) {
      assert.ok(mots(textes.fin.text).length <= 6, `${id} : ${textes.fin.text}`);
      assert.ok(EXPRESSIONS.includes(textes.fin.expression), id);
    }
    assert.ok(mots(BULLES.missionInconnue.text).length <= 6);
  });

  it("une mission que l'add-on ne connaît pas : pas de textes inventés, un titre lisible, une bulle de repli", () => {
    assert.equal(missionTexts("mission-neuve"), undefined);
    assert.equal(missionTexts("constructor"), undefined);
    assert.equal(missionTexts("toString"), undefined);
    assert.equal(missionTitle("mission-neuve"), "Mission neuve");
    assert.equal(missionTitle("bienvenue-mairie"), "Bienvenue à la mairie");
    assert.equal(missionTitle(""), "");
    assert.equal(missionTitle("-"), "-");
    assert.deepEqual(missionEndBubble("mission-neuve"), BULLES.missionInconnue);
    assert.deepEqual(missionEndBubble("bienvenue-mairie"), MISSION_TEXTES["bienvenue-mairie"].fin);
  });

  it("la consigne du HUD joint la consigne et l'avancement du service, tels quels", () => {
    assert.equal(HUD_TEXTES.mission("Encaisse au Silo", "0", "1"), "Encaisse au Silo : 0/1");
    assert.equal(HUD_TEXTES.mission("Encaisse au Silo", "12", "20"), "Encaisse au Silo : 12/20");
  });

  it("les textes de l'écran des objectifs : titre en capitales, boutons sans ponctuation finale", () => {
    assert.match(OBJECTIFS_TEXTES.titre, /^[A-ZÉ]+( [A-ZÉ]+)?$/);
    assert.match(OBJECTIFS_TEXTES.enCours, /^[A-ZÉ]+( [A-ZÉ]+)?$/);
    assert.match(OBJECTIFS_TEXTES.terminees, /^[A-ZÉ]+( [A-ZÉ]+)?$/);
    assert.doesNotMatch(OBJECTIFS_TEXTES.retour, /[.!?]$/);
    assert.equal(OBJECTIFS_TEXTES.avancement("2", "5"), "Avancement 2/5");
    assert.equal(OBJECTIFS_TEXTES.recompense("10"), "Récompense 10 Lingots");
    assert.equal(OBJECTIFS_TEXTES.tuile.enCours("2", "5"), "Mission 2/5");
  });
});

// ---------------------------------------------------------------- le prestige et la carte

describe("textes du prestige et de la carte", () => {
  const lire = (...morceaux: string[]): string => readFileSync(resolve("..", ...morceaux), "utf8");

  it("l'ordre des usines de la carte est celui de definitions/usines.json et de definitions/iles.json", () => {
    const usines = JSON.parse(lire("definitions", "usines.json")) as { usines: Array<{ id: string }> };
    const iles = JSON.parse(lire("definitions", "iles.json")) as { usines: Array<{ id: string }> };
    assert.deepEqual([...FACTORY_IDS], usines.usines.map((u) => u.id));
    assert.deepEqual([...FACTORY_IDS], iles.usines.map((u) => u.id));
  });

  it("les noms d'époque sont ceux de docs/game-design.md, et un numéro inconnu n'a pas de nom inventé", () => {
    const table = [...lire("docs", "game-design.md").matchAll(/^\| ([1-4]) · ([^|]+?) \|/gm)];
    const noms = new Map(table.map((m) => [Number(m[1]), m[2] ?? ""]));
    assert.equal(noms.size, 4, "la table des époques de game-design.md est introuvable");
    for (const [numero, nom] of noms) assert.equal(eraName(numero), nom);
    for (const era of [0, 5, -1, 1.5, Number.NaN]) assert.equal(eraName(era), "");
  });

  it("les gabarits mettent en forme ce que le service a dit, sans rien ajouter", () => {
    assert.equal(PRESTIGE_TEXTES.tuile("4"), "Course 4");
    assert.equal(PRESTIGE_TEXTES.epoque("2", "Vapeur"), "Époque 2 : Vapeur");
    assert.equal(PRESTIGE_TEXTES.epoque("9", ""), "Époque 9");
    assert.equal(PRESTIGE_TEXTES.course("3", "Époque 1 : Artisanale"), "Course 3 · Époque 1 : Artisanale");
    assert.equal(PRESTIGE_TEXTES.suivante.epoque("2", "Vapeur"), "Course suivante : époque 2, Vapeur");
    assert.equal(PRESTIGE_TEXTES.suivante.epoque("9", ""), "Course suivante : époque 9");
    assert.equal(PRESTIGE_TEXTES.avant.seuil("2,5 M"), "Seuil 2,5 M Donar");
    assert.equal(PRESTIGE_TEXTES.avant.total("1 200"), "Lingots en tout 1 200");
    assert.equal(PRESTIGE_TEXTES.avant.taPart("700"), "Ta part 700 Lingots");
    assert.equal(PRESTIGE_TEXTES.avant.unAssocie("500"), "Un associé 500 Lingots");
    assert.equal(PRESTIGE_TEXTES.avant.doubler("1 700"), "Avec deux fois plus de Donar : 1 700 Lingots");
    assert.equal(PRESTIGE_TEXTES.apres.etoiles("2"), "Étoiles 2");
    assert.equal(PRESTIGE_TEXTES.apres.lingots("1 200"), "Tu as 1 200 Lingots");
    assert.equal(CARTE_TEXTES.etat.entiere("12"), "Entière · niv. 12");
    assert.equal(CARTE_TEXTES.etat.enTravaux(2, 4), "En travaux 2/4");
  });

  it("les phrases fixes n'ont aucun chiffre : tous les nombres viennent du service", () => {
    const fixes = [
      PRESTIGE_TEXTES.suivante.etoile,
      PRESTIGE_TEXTES.avant.principe,
      PRESTIGE_TEXTES.avant.pret,
      PRESTIGE_TEXTES.avant.pasEncore,
      ...PRESTIGE_TEXTES.avant.repart,
      PRESTIGE_TEXTES.avant.reste,
      PRESTIGE_TEXTES.apres.suite,
      CARTE_TEXTES.phrase,
      CARTE_TEXTES.etat.enRuine,
    ];
    for (const texte of fixes) assert.doesNotMatch(texte, /[0-9]/, texte);
  });

  it("« Prestige ! » est le bouton de confirmation du design system, et le seul à finir par un point d'exclamation", () => {
    assert.equal(PRESTIGE_TEXTES.avant.confirmer, "Prestige !");
    assert.ok(lire("design", "README.md").includes("« Prestige ! »"));
    const boutons = [PRESTIGE_TEXTES.avant.retour, PRESTIGE_TEXTES.apres.usines, PRESTIGE_TEXTES.apres.retour, CARTE_TEXTES.retour];
    for (const bouton of boutons) assert.doesNotMatch(bouton, /[.!?]$/, bouton);
  });

  it("l'écran d'avant dit ce qui repart et ce qui reste : le Donar et les usines repartent, les Lingots restent", () => {
    assert.match(PRESTIGE_TEXTES.avant.repart.join(" "), /Donar/);
    assert.match(PRESTIGE_TEXTES.avant.repart.join(" "), /usines/);
    assert.match(PRESTIGE_TEXTES.avant.reste, /Lingots/);
    assert.match(PRESTIGE_TEXTES.avant.reste, /Gemmes/);
  });

  it("la bulle du prestige réussi est fière, le refus du seuil dit les Donar qui manquent", () => {
    assert.equal(BULLES.prestige.expression, "fier");
    assert.equal(refusalBubble("SEUIL_NON_ATTEINT", 4_000).text, `Il te manque ${formatAmount(4_000)} Donar.`);
    assert.equal(refusalBubble("SEUIL_NON_ATTEINT").text, "Le seuil n'est pas atteint.");
    assert.equal(refusalBubble("PAS_LE_PDG").text, "Seul le PDG peut le faire.");
  });

  it("les bulles de la carte sont celles d'Oooscar qui s'excuse", () => {
    for (const bulle of [BULLES.usinePasEncore, BULLES.ileIntrouvable, BULLES.ilePasPrete]) {
      assert.equal(bulle.expression, "oups");
    }
  });

  it("le repère du prestige est dans l'habillage provisoire, pas dans les textes", () => {
    assert.equal(ICON.prestige, "[P]");
    assert.equal(ICON.carte, "[C]");
  });
});

// ---------------------------------------------------------------- le personnel

describe("textes du personnel", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function enumDuContrat(champ: string): string[] {
    const debut = yaml.indexOf("    Staff:\n");
    assert.notEqual(debut, -1);
    const ligne = yaml.slice(yaml.indexOf(`${champ}:`, debut), yaml.indexOf("\n", yaml.indexOf(`${champ}:`, debut)));
    return ligne
      .slice(ligne.indexOf("enum: [") + "enum: [".length, ligne.indexOf("]"))
      .split(",")
      .map((mot) => mot.trim());
  }

  it("chaque rôle et chaque rareté du contrat a son nom, et rien d'autre", () => {
    assert.deepEqual(Object.keys(PERSONNEL_TEXTES.roles).sort(), [...enumDuContrat("role")].sort());
    assert.deepEqual(Object.keys(PERSONNEL_TEXTES.raretes).sort(), [...enumDuContrat("rarity")].sort());
    assert.deepEqual(Object.keys(PERSONNEL_TEXTES.roles).sort(), [...STAFF_ROLES].sort());
    assert.deepEqual(Object.keys(PERSONNEL_TEXTES.raretes).sort(), [...STAFF_RARITIES].sort());
  });

  it("les noms de rôle et de rareté sont un seul mot, distincts, avec une majuscule", () => {
    for (const noms of [Object.values(PERSONNEL_TEXTES.roles), Object.values(PERSONNEL_TEXTES.raretes)]) {
      assert.equal(new Set(noms).size, noms.length);
      for (const nom of noms) {
        assert.equal(mots(nom).length, 1, nom);
        assert.match(nom, /^\p{Lu}\p{Ll}+$/u, nom);
      }
    }
  });

  it("les titres de fenêtre sont en capitales, d'un ou deux mots", () => {
    for (const titre of [
      MENU_TEXTES.titre,
      PERSONNEL_TEXTES.titre,
      PERSONNEL_TEXTES.equipe.titre,
      PERSONNEL_TEXTES.membre.titre,
      PERSONNEL_TEXTES.affecter.titre,
      PERSONNEL_TEXTES.renvoi.titre,
      AGENCE_TEXTES.titre,
      AGENCE_TEXTES.candidat.titre,
      AGENCE_TEXTES.embauche.titre,
    ]) {
      assert.match(titre, /^[A-ZÉ]+( [A-ZÉ]+)?$/);
    }
  });

  it("les boutons sont courts et sans ponctuation finale", () => {
    const boutons = [
      MENU_TEXTES.personnel,
      PERSONNEL_TEXTES.agence,
      PERSONNEL_TEXTES.sansUsine,
      PERSONNEL_TEXTES.retour,
      PERSONNEL_TEXTES.membre.affecter,
      PERSONNEL_TEXTES.membre.renvoyer,
      PERSONNEL_TEXTES.membre.retour,
      PERSONNEL_TEXTES.affecter.retour,
      PERSONNEL_TEXTES.renvoi.renvoyer,
      PERSONNEL_TEXTES.renvoi.garder,
      AGENCE_TEXTES.retour,
      AGENCE_TEXTES.candidat.embaucher,
      AGENCE_TEXTES.candidat.retour,
      AGENCE_TEXTES.embauche.sansUsine,
      AGENCE_TEXTES.embauche.retour,
    ];
    for (const bouton of boutons) {
      assert.ok(mots(bouton).length >= 1 && mots(bouton).length <= 4, bouton);
      assert.doesNotMatch(bouton, /[.!?]$/, bouton);
    }
  });

  it("l'agence ne parle jamais d'une nouvelle liste, d'un tirage ni d'un renouvellement payant", () => {
    for (const { chemin, texte } of feuilles(AGENCE_TEXTES, "AGENCE_TEXTES")) {
      assert.doesNotMatch(texte, /nouvelle liste|tirage|tirer|renouvel|relanc|rafra/i, chemin);
    }
  });

  it("les chiffres arrivent mis en forme : les textes les posent tels quels, sans rien calculer", () => {
    assert.equal(PERSONNEL_TEXTES.ligne("Rare", "Chef", "25"), "Rare · Chef · +25 %");
    assert.equal(PERSONNEL_TEXTES.sorte("Rare", "Chef"), "Rare · Chef");
    assert.equal(PERSONNEL_TEXTES.membre.bonus("25"), "Bonus +25 % de production");
    assert.equal(PERSONNEL_TEXTES.membre.salaire("360"), "Salaire 360 Donar/h");
    assert.equal(AGENCE_TEXTES.candidat.cout("4,8 k"), "Embauche 4,8 k Donar");
    assert.equal(AGENCE_TEXTES.candidat.salaire("2,5"), "Salaire 2,5 % du revenu de l'usine");
  });

  it("le nombre de membres : aucun, un, plusieurs", () => {
    assert.equal(MENU_TEXTES.nombreMembres(0), "Aucun membre");
    assert.equal(MENU_TEXTES.nombreMembres(1), "1 membre");
    assert.equal(MENU_TEXTES.nombreMembres(4), "4 membres");
  });

  it("la ligne d'équipe de la fiche d'usine : avec ou sans chef", () => {
    assert.equal(FICHE_TEXTES.equipe("2 membres", true), "Équipe : 2 membres, avec chef");
    assert.equal(FICHE_TEXTES.equipe("1 membre", false), "Équipe : 1 membre, sans chef");
  });

  it("deux refus ont les mots du personnel, les autres gardent ceux de refusalBubble", () => {
    assert.equal(staffRefusalBubble("EMPLACEMENT_OCCUPE"), BULLES.postesPleins);
    assert.equal(staffRefusalBubble("DEJA_POSSEDE"), BULLES.dejaEmbauche);
    for (const code of REFUSAL_BUBBLE_REASONS.filter((c) => c !== "EMPLACEMENT_OCCUPE" && c !== "DEJA_POSSEDE")) {
      assert.deepEqual(staffRefusalBubble(code, 120), refusalBubble(code, 120), code);
    }
    assert.deepEqual(staffRefusalBubble("CODE_DU_FUTUR"), BULLES.refusInconnu);
  });

  it("la couleur d'une rareté ne se confond avec aucune monnaie, et une rareté a toujours son nom écrit", () => {
    const monnaies: string[] = [COLOR.donar, COLOR.lingots, COLOR.gemmes];
    for (const rarete of STAFF_RARITIES) {
      assert.ok(!monnaies.includes(RARITY_COLOR[rarete]), rarete);
      assert.match(RARITY_COLOR[rarete], /^§[0-9a-v]$/);
      assert.ok(PERSONNEL_TEXTES.raretes[rarete] !== "", rarete);
    }
    assert.equal(new Set(STAFF_RARITIES.map((rarete) => RARITY_COLOR[rarete])).size, STAFF_RARITIES.length);
  });
});

// ---------------------------------------------------------------- les incidents et leur réparation

describe("textes des incidents", () => {
  const donnees = JSON.parse(readFileSync(resolve("..", "definitions", "incidents.json"), "utf8")) as {
    sortes: Array<{ id: string }>;
  };
  const idsDuJeu = donnees.sortes.map((sorte) => sorte.id);

  it("le contrôle trouve les sortes du jeu", () => {
    assert.ok(idsDuJeu.length >= 3);
    assert.ok(idsDuJeu.includes("incendie"));
  });

  it("chaque sorte de definitions/incidents.json a ses textes (nom, phrase, mini-jeu), et rien d'autre", () => {
    assert.deepEqual([...INCIDENT_TEXTES_KINDS].sort(), [...idsDuJeu].sort());
    assert.deepEqual(Object.keys(REPARATION_TEXTES.sortes).sort(), [...idsDuJeu].sort());
  });

  it("un nom court sans ponctuation finale, une phrase de dix mots au plus sur une ligne, aucun chiffre du jeu", () => {
    const noms = new Set<string>();
    for (const kind of idsDuJeu) {
      const { nom, phrase } = incidentTexts(kind);
      assert.ok(mots(nom).length >= 1 && mots(nom).length <= 3, nom);
      assert.doesNotMatch(nom, /[.!?]$/, nom);
      assert.ok(mots(phrase).length <= 10, phrase);
      assert.doesNotMatch(phrase, /\n/);
      // Le facteur de ralentissement et la récompense sont au service (definitions/incidents.json) : jamais écrits ici.
      assert.doesNotMatch(`${nom} ${phrase}`, /\d/, kind);
      noms.add(nom);
    }
    assert.equal(noms.size, idsDuJeu.length, "deux sortes portent le même nom");
  });

  it("une sorte inconnue, absente ou qui ressemble à un champ d'objet prend le mot général", () => {
    for (const kind of ["sorte-du-futur", "", null, undefined, "constructor", "toString", "__proto__"]) {
      assert.equal(incidentTexts(kind), INCIDENT_TEXTES.inconnue, String(kind));
    }
    assert.equal(INCIDENT_TEXTES.inconnue.nom, "Incident");
  });

  it("les noms des sortes sont ceux du design : incendie, panne, courroie", () => {
    assert.equal(incidentTexts("incendie").nom, "Début d'incendie");
    assert.equal(incidentTexts("panne").nom, "Machine en panne");
    assert.equal(incidentTexts("courroie").nom, "Courroie cassée");
  });

  it("la récompense se dit avec la somme du service et sans chiffre écrit en dur", () => {
    assert.equal(INCIDENT_TEXTES.reparation.recompense("1,5 k"), "Récompense 1,5 k Donar");
    for (const texte of [INCIDENT_TEXTES.reparation.aucune, INCIDENT_TEXTES.reparation.dejaFaite, INCIDENT_TEXTES.ouverture.conseil]) {
      assert.doesNotMatch(texte, /\d/, texte);
    }
  });

  it("les bulles d'incident : panique quand il s'ouvre, joie quand l'usine repart", () => {
    assert.equal(BULLES.incident.expression, "panique");
    for (const bulle of [BULLES.usineRepart, BULLES.repareeParToi, BULLES.repareeParUnAssocie]) {
      assert.equal(bulle.expression, "joie", bulle.text);
    }
  });
});

describe("textes du mini-jeu de réparation", () => {
  const sortes = [...INCIDENT_TEXTES_KINDS, null];

  it("titre en un mot de capitales, ligne d'étape et Retour", () => {
    assert.match(REPARATION_TEXTES.titre, /^[A-ZÉ]+$/);
    assert.equal(REPARATION_TEXTES.etape(2, 3), "Étape 2 sur 3");
    assert.equal(REPARATION_TEXTES.retour, MENU_TEXTES.retour);
  });

  it("trois étapes au moins, quatre au plus, par sorte et pour le mini-jeu général", () => {
    for (const kind of sortes) {
      const etapes = repairSteps(kind);
      assert.ok(etapes.length >= 3 && etapes.length <= 4, String(kind));
    }
  });

  it("une consigne d'une ligne et de dix mots au plus ; deux ou trois boutons courts, sans ponctuation finale, tous différents", () => {
    for (const kind of sortes) {
      for (const etape of repairSteps(kind)) {
        assert.ok(mots(etape.consigne).length >= 1 && mots(etape.consigne).length <= 10, etape.consigne);
        assert.doesNotMatch(etape.consigne, /\n/);
        assert.ok(etape.choix.length >= 2 && etape.choix.length <= 3, etape.consigne);
        assert.equal(new Set(etape.choix).size, etape.choix.length, etape.consigne);
        for (const choix of etape.choix) {
          assert.ok(mots(choix).length >= 1 && mots(choix).length <= 5, choix);
          assert.doesNotMatch(choix, /[.!?]$/, choix);
        }
        assert.ok(Number.isInteger(etape.bonne) && etape.bonne >= 0 && etape.bonne < etape.choix.length, etape.consigne);
      }
    }
  });

  it("l'indice d'une erreur est une bulle « oups » qui ne donne pas la réponse mot pour mot", () => {
    for (const kind of sortes) {
      for (const etape of repairSteps(kind)) {
        assert.equal(etape.erreur.expression, "oups", etape.consigne);
        assert.notEqual(etape.erreur.text, etape.choix[etape.bonne], etape.consigne);
      }
    }
  });

  it("la bonne réponse ne tombe pas toujours au même rang, dans chaque sorte", () => {
    for (const kind of sortes) {
      assert.ok(new Set(repairSteps(kind).map((etape) => etape.bonne)).size >= 2, String(kind));
    }
  });

  it("aucun chiffre du jeu dans les consignes et les boutons", () => {
    for (const kind of sortes) {
      for (const etape of repairSteps(kind)) assert.doesNotMatch([etape.consigne, ...etape.choix].join(" "), /\d/, etape.consigne);
    }
  });
});

// ---------------------------------------------------------------- l'entreprise partagée

describe("textes de l'entreprise partagée", () => {
  it("un nom de rôle par rôle du contrat, et rien d'autre", () => {
    assert.deepEqual(Object.keys(ASSOCIES_TEXTES.roles).sort(), [...MEMBER_ROLES].sort());
    const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");
    const debut = yaml.indexOf("    Member:\n");
    assert.notEqual(debut, -1);
    const ligne = yaml.slice(yaml.indexOf("role:", debut), yaml.indexOf("\n", yaml.indexOf("role:", debut)));
    const contrat = ligne
      .slice(ligne.indexOf("enum: [") + "enum: [".length, ligne.indexOf("]"))
      .split(",")
      .map((mot) => mot.trim());
    assert.deepEqual(Object.keys(ASSOCIES_TEXTES.roles).sort(), [...contrat].sort());
  });

  it("une ligne de membre pose le rôle, le nom et la part tels que le service les donne", () => {
    assert.equal(ASSOCIES_TEXTES.ligne("PDG", "Alex", "60"), "PDG · Alex · 60 %");
    assert.equal(ASSOCIES_TEXTES.toi("PDG · Alex · 60 %"), "PDG · Alex · 60 % (toi)");
  });

  it("la tuile du menu : une phrase pour le joueur seul, un nombre sinon", () => {
    assert.equal(ASSOCIES_TEXTES.tuile.seul, "Invite des joueurs");
    assert.equal(ASSOCIES_TEXTES.tuile.nombre(2), "2 associés");
    assert.equal(ASSOCIES_TEXTES.tuile.nombre(7), "7 associés");
  });

  it("l'add-on n'affiche aucun maximum d'associés : c'est le service qui refuse", () => {
    for (const { chemin, texte } of feuilles(ASSOCIES_TEXTES, "ASSOCIES_TEXTES")) {
      assert.doesNotMatch(texte, /maximum|au plus|limite|jusqu'à \d|\d+ associés? (au|maximum)/i, `${chemin} : ${texte}`);
    }
    assert.doesNotMatch(ASSOCIES_TEXTES.seul, /\d/);
    assert.doesNotMatch(ASSOCIES_TEXTES.tuile.seul, /\d/);
  });

  it("l'invitation dit qui invite dans quelle entreprise, et ce que le joueur quitte", () => {
    assert.equal(ASSOCIES_TEXTES.invitation.texte("Bea", "Les Forges"), "Bea t'invite dans Les Forges");
    assert.match(ASSOCIES_TEXTES.invitation.consequence, /quittes/);
  });

  it("le formulaire des parts : un champ par membre, par son nom, et un indice qui demande un entier", () => {
    assert.equal(ASSOCIES_TEXTES.formulaire.champ("Ada"), "Part de Ada, en %");
    assert.match(ASSOCIES_TEXTES.formulaire.indice, /entier/);
  });

  it("les boutons sont courts et sans ponctuation finale", () => {
    const boutons = [
      MENU_TEXTES.associes,
      ASSOCIES_TEXTES.inviter,
      ASSOCIES_TEXTES.parts,
      ASSOCIES_TEXTES.retour,
      ASSOCIES_TEXTES.invitation.accepter,
      ASSOCIES_TEXTES.invitation.plusTard,
      ASSOCIES_TEXTES.choix.retour,
      ASSOCIES_TEXTES.formulaire.envoyer,
    ];
    for (const bouton of boutons) {
      assert.ok(mots(bouton).length >= 1 && mots(bouton).length <= 3, bouton);
      assert.doesNotMatch(bouton, /[.!?]$/, bouton);
    }
  });

  it("les bulles du refus d'un total à cent et d'une invitation périmée sont des « oups », celles qui réussissent des « joie »", () => {
    const { bulles } = ASSOCIES_TEXTES;
    for (const bulle of [bulles.partsInvalides, bulles.partsTotal, bulles.invitationExpiree, bulles.joueurInconnu, bulles.dejaAssocie, bulles.tuEsDejaAssocie, bulles.ilALance, bulles.entreprisePleine]) {
      assert.equal(bulle.expression, "oups", bulle.text);
    }
    for (const bulle of [bulles.invitationEnvoyee, bulles.partsEnregistrees, bulles.bienvenue, bulles.invitationRecue, bulles.partsChangees]) {
      assert.equal(bulle.expression, "joie", bulle.text);
    }
  });

  it("le refus d'une entreprise pleine a sa bulle de refus ; l'autre refus (déjà associé) est dit par les associés", () => {
    assert.equal(refusalBubble("ENTREPRISE_PLEINE").text, "Ton entreprise est pleine.");
    assert.ok(REFUSAL_BUBBLE_REASONS.includes("DEJA_POSSEDE"));
    assert.equal(ASSOCIES_TEXTES.bulles.dejaAssocie.text, "Il est déjà associé.");
  });
});

// ---------------------------------------------------------------- la boutique, le pass, les votes, les annonces

describe("textes de la boutique, du pass, des votes et des annonces", () => {
  const lire = (fichier: string): unknown => JSON.parse(readFileSync(resolve("..", "definitions", fichier), "utf8"));

  it("le nom de chaque rareté du personnel est celui de definitions/personnel.json, et la liste des raretés est la même", () => {
    const raretes = (lire("personnel.json") as { raretes: Record<string, { nom: string }> }).raretes;
    assert.deepEqual(Object.keys(raretes).sort(), [...STAFF_RARITIES].sort());
    for (const rarete of STAFF_RARITIES) {
      assert.equal(PERSONNEL_TEXTES.raretes[rarete], raretes[rarete]?.nom, rarete);
    }
  });

  it("chaque catégorie de cosmétique de definitions/boutique.json a son rayon, et rien d'autre", () => {
    const cosmetiques = (lire("boutique.json") as { cosmetiques: Array<{ categorie: string }> }).cosmetiques;
    const categories = [...new Set(cosmetiques.map((cosmetique) => cosmetique.categorie))].sort();
    assert.deepEqual(Object.keys(BOUTIQUE_TEXTES.categories).sort(), categories);
  });

  it("les deux pistes du pass sont celles du contrat, avec un nom et un bouton chacune", () => {
    assert.deepEqual(Object.keys(PASS_TEXTES.pistes).sort(), [...PASS_TRACKS].sort());
    assert.deepEqual(Object.keys(PASS_TEXTES.reclamer).sort(), [...PASS_TRACKS].sort());
  });

  it("l'endroit où l'on achète des Gemmes est dit sans adresse", () => {
    assert.doesNotMatch(BOUTIQUE_TEXTES.gemmesManquent, /https?:|www\.|\.(com|fr|net|org)\b/i);
    assert.ok(BOUTIQUE_TEXTES.gemmesManquent.includes("site"));
  });

  it("les bulles qui réussissent sont des « joie » ou des « fier », celles qui refusent des « oups »", () => {
    const reussies = [
      BOUTIQUE_TEXTES.bulles.achete,
      BOUTIQUE_TEXTES.bulles.prolonge,
      BOUTIQUE_TEXTES.bulles.boostLance,
      BOUTIQUE_TEXTES.bulles.offert,
      PASS_TEXTES.bulles.palierReclame,
      PASS_TEXTES.bulles.premiumActif,
      VOTE_TEXTES.rappel.bulle,
      VOTE_TEXTES.vote.bulle,
      VOTE_TEXTES.voteParty.bulle,
      ANNONCES_TEXTES.gemmes.bulle,
      ANNONCES_TEXTES.pionnier,
    ];
    for (const bulle of reussies) assert.ok(["joie", "fier"].includes(bulle.expression), bulle.text);
    const refus = [
      BOUTIQUE_TEXTES.bulles.dejaChezLui,
      BOUTIQUE_TEXTES.bulles.gradePrecedentPourLui,
      BOUTIQUE_TEXTES.bulles.plusEnVente,
      ANNONCES_TEXTES.sansCourant.bulle,
    ];
    for (const bulle of refus) assert.equal(bulle.expression, "oups", bulle.text);
  });

  it("une ligne de tchat tient sur une seule ligne, avec ou sans pseudo", () => {
    const lignes = [
      ANNONCES_TEXTES.boost.avecNom("Bea", "25", "une heure"),
      ANNONCES_TEXTES.boost.sansNom("25", "une heure"),
      ANNONCES_TEXTES.gemmes.ligne("400", "900"),
      ANNONCES_TEXTES.sansCourant.ligne("Carrière"),
      VOTE_TEXTES.rappel.ligne("Hubyy"),
      VOTE_TEXTES.vote.recompense("3"),
      VOTE_TEXTES.voteParty.ligne("2"),
    ];
    for (const ligne of lignes) {
      assert.doesNotMatch(ligne, /\n/, ligne);
      assert.match(ligne, /[.]$|Lingots?$|[0-9]$/, ligne);
    }
  });

  it("la durée d'un boost se dit en mots : une heure, des heures rondes, sinon des minutes", () => {
    assert.equal(dureeEnMots(60), "une heure");
    assert.equal(dureeEnMots(120), "2 heures");
    assert.equal(dureeEnMots(90), "90 minutes");
    assert.equal(dureeEnMots(1), "1 minute");
    assert.equal(dureeEnMots(30), "30 minutes");
  });

  it("Lingot et Gemmes gardent leur nom du jeu et leur pluriel", () => {
    assert.equal(VOTE_TEXTES.vote.recompense("1"), "+1 Lingot");
    assert.equal(VOTE_TEXTES.vote.recompense("12"), "+12 Lingots");
    assert.equal(PASS_TEXTES.tuile.lingots("1"), "1 Lingot");
    assert.equal(PASS_TEXTES.tuile.lingots("2"), "2 Lingots");
    assert.equal(PASS_TEXTES.aReclamer(0), "Rien à réclamer");
    assert.equal(PASS_TEXTES.aReclamer(1), "1 récompense à réclamer");
    assert.equal(PASS_TEXTES.aReclamer(4), "4 récompenses à réclamer");
    assert.equal(BOUTIQUE_TEXTES.details.articles(1), "1 article");
    assert.equal(BOUTIQUE_TEXTES.details.articles(3), "3 articles");
  });
});
