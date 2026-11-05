import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import { REFUSAL_REASONS } from "../src/protocol";
import { COLOR, EXPRESSIONS, EXPRESSION_MARK, ICON, colored } from "../src/ui/apparence";
import { formatAmount } from "../src/ui/nombres";
import {
  BOUSSOLE_TEXTES,
  BULLES,
  CARTE_TEXTES,
  CINEMATIQUE_TEXTES,
  FACTORY_IDS,
  FACTORY_NAMES,
  FICHE_TEXTES,
  HUD_TEXTES,
  MENU_TEXTES,
  MISSION_TEXTES,
  MISSION_TEXTES_IDS,
  OBJECTIFS_TEXTES,
  PRESTIGE_TEXTES,
  REFUSAL_BUBBLE_REASONS,
  SILO_TEXTES,
  USINES_TEXTES,
  eraName,
  factoryName,
  failureBubble,
  missionEndBubble,
  missionTexts,
  missionTitle,
  refusalBubble,
  renderBubble,
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
  for (const failure of ["network", "error", "unavailable"] as const) {
    for (const reason of ["RESEAU", "PAS_FAIT", "ERREUR_INTERNE"]) {
      liste.push({ nom: `${failure} ${reason}`, bulle: failureBubble(failure, reason) });
    }
  }
  for (const [id, textes] of Object.entries(MISSION_TEXTES)) liste.push({ nom: `fin de ${id}`, bulle: textes.fin });
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

  it("la bulle s'écrit sur une ligne : nom, repère de l'expression, phrase", () => {
    assert.equal(renderBubble(BULLES.bientot).replaceAll(/§./g, ""), "Oooscar [oups] Pas encore disponible.");
    assert.ok(renderBubble(BULLES.montee).startsWith(COLOR.muted));
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

  it("l'habillage n'ajoute que des codes de couleur, des repères entre crochets et un nom", () => {
    assert.equal(colored(COLOR.donar, "x"), `${COLOR.donar}x${COLOR.reset}`);
    for (const repere of Object.values(ICON)) assert.match(repere, /^\[[A-Z!]\]$/);
    for (const repere of Object.values(EXPRESSION_MARK)) assert.match(repere, /^\[[a-z]+\]$/);
    for (const code of Object.values(COLOR)) assert.match(code, /^§[0-9a-v]$/);
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
