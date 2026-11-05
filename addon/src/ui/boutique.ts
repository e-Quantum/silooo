// Les écrans de la boutique en Gemmes : la porte de chaque rayon, un rayon, la fiche d'un article, le choix du joueur à qui
// on l'offre, et ce que dit Oooscar après un achat.
//
//   BOUTIQUE ─┬─ Grades ──────────── un article ─┬─ Acheter
//             ├─ Pilier ─────────────────────────┤  (Prolonger quand l'abonnement court)
//             ├─ Cosmétiques ─ une catégorie ─ un article ─ Offrir ─ un joueur connecté
//             ├─ Boost collectif ─── Acheter (pas de cadeau : un boost est pour tout le serveur)
//             └─ Pass de saison (ui/pass.ts)
//
// Des fonctions pures, comme celles de menu.ts : la vue du joueur (ses Gemmes, telles que le service les a dites), la dernière
// lecture du catalogue (GET catalog) et celle du pass entrent ; un écran sort. Le service fixe les prix, la possession et
// l'ordre des grades (`CatalogItem.gemmes`, `owned`, `available`) ; l'add-on y rapproche, par l'identifiant, le nom, la
// catégorie et le bonus de definitions/boutique.json (types générés). Un article que le catalogue donne et que ce fichier ne
// connaît pas n'est pas montré, plutôt que sous un nom inventé. Aucun prix n'est écrit ici, aucun solde n'est calculé.

import { BOUTIQUE, type CosmeticCategory } from "../monde/definitions";
import { BOOST_ITEM_ID, PASS_PREMIUM_ITEM_ID, PIONEER_COSMETIC_ID, type CatalogItem, type Xuid } from "../protocol";
import type { ActionOutcome, CatalogOutcome, PassOutcome, VotesOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import { isRejectedRequest, type OnlinePlayer } from "./associes";
import {
  MAIN,
  bodyOf,
  button,
  go,
  pageOf,
  screenOf,
  type ArticleShelf,
  type Entry,
  type Notice,
  type Route,
  type Screen,
  type ShopShelf,
} from "./ecran";
import type { CompanyView } from "./etat";
import { formatAmount, formatCount } from "./nombres";
import { cosmeticName, passTile } from "./pass";
import {
  ASSOCIES_TEXTES,
  BOUTIQUE_TEXTES,
  MENU_TEXTES,
  PASS_TEXTES,
  USINES_TEXTES,
  dureeEnMots,
  refusalBubble,
} from "./textes";
import { reminderLine } from "./votes";

const BOUTIQUE_ROUTE = { screen: "boutique" } as const;

/**
 * Les abonnements que la boutique ne montre pas : le préfixe perso (réservé au grade Ultime) a besoin d'un mécanisme de
 * tchat que cet add-on n'a pas. Le service le vend quand même ; on n'affiche pas ce que l'on ne sait pas donner.
 */
const HORS_BOUTIQUE: readonly string[] = ["perso"];

// ---------------------------------------------------------------- les articles

/** Un article du catalogue du service, avec ce que definitions/boutique.json en dit. */
export type Article =
  | { readonly kind: "grade"; readonly item: CatalogItem; readonly nom: string; readonly bonus: number }
  | {
      readonly kind: "abonnement";
      readonly item: CatalogItem;
      readonly nom: string;
      readonly jours: number;
      readonly bonus: number | undefined;
      readonly rendues: number | undefined;
    }
  | { readonly kind: "cosmetique"; readonly item: CatalogItem; readonly nom: string; readonly categorie: CosmeticCategory }
  | { readonly kind: "boost"; readonly item: CatalogItem; readonly nom: string; readonly pourcent: number; readonly minutes: number };

/** Rapproche un article du catalogue de definitions/boutique.json. Undefined pour un article que la boutique ne montre pas (le pass premium a son écran). */
export function articleOf(item: CatalogItem): Article | undefined {
  if (item.itemId === BOOST_ITEM_ID) {
    const boost = BOUTIQUE.boostCollectif;
    return { kind: "boost", item, nom: BOUTIQUE_TEXTES.rayons.boost, pourcent: boost.bonusPourcent, minutes: boost.dureeMinutes };
  }
  const grade = BOUTIQUE.grades.find((candidate) => candidate.id === item.itemId);
  if (grade !== undefined) return { kind: "grade", item, nom: grade.nom, bonus: grade.bonusDonarPourcent };
  const abonnement = BOUTIQUE.abonnements.find((candidate) => candidate.id === item.itemId);
  if (abonnement !== undefined) {
    if (HORS_BOUTIQUE.includes(abonnement.id)) return undefined;
    return {
      kind: "abonnement",
      item,
      nom: abonnement.nom,
      jours: abonnement.jours,
      bonus: abonnement.bonusDonarPourcent,
      rendues: abonnement.gemmesRendues,
    };
  }
  const cosmetique = BOUTIQUE.cosmetiques.find((candidate) => candidate.id === item.itemId);
  if (cosmetique !== undefined && cosmetique.achetable) {
    return { kind: "cosmetique", item, nom: cosmetique.nom, categorie: cosmetique.categorie };
  }
  return undefined;
}

/**
 * L'achat de cet article change-t-il le revenu de l'entreprise ? Un grade, le Pilier et le boost collectif multiplient la
 * production : après leur achat, la vue relit l'entreprise pour que le HUD parte du revenu du service. Un cosmétique ou le pass non.
 */
export function affectsCompany(itemId: string): boolean {
  const article = articleOf({ itemId, gemmes: 0, owned: false, available: true });
  return article !== undefined && article.kind !== "cosmetique";
}

/** La lecture du catalogue, ou undefined si elle manque ou a échoué. */
function itemsOf(catalog: CatalogOutcome | undefined): readonly CatalogItem[] | undefined {
  return catalog?.kind === "catalog" ? catalog.items : undefined;
}

/** Les articles que la boutique montre, dans l'ordre du service. */
export function articlesOf(catalog: CatalogOutcome | undefined): readonly Article[] {
  return (itemsOf(catalog) ?? []).flatMap((item) => {
    const article = articleOf(item);
    return article === undefined ? [] : [article];
  });
}

function findArticle(catalog: CatalogOutcome | undefined, itemId: string): Article | undefined {
  return articlesOf(catalog).find((article) => article.item.itemId === itemId);
}

/** Les articles d'un rayon (« direct » : le Pilier et le boost collectif, qui n'ont pas de rayon). */
function inShelf(articles: readonly Article[], shelf: ArticleShelf): Article[] {
  return articles.filter((article) => {
    if (shelf === "grades") return article.kind === "grade";
    if (shelf === "direct") return article.kind === "abonnement" || article.kind === "boost";
    return article.kind === "cosmetique" && article.categorie === shelf;
  });
}

/** Où mène « Retour » depuis un article venu de ce rayon, à cette page. */
export function articleParent(shelf: ArticleShelf, page: number): Route {
  return shelf === "direct" ? BOUTIQUE_ROUTE : { screen: "rayon", rayon: shelf, page };
}

/** Le rayon parent d'un rayon : les catégories de cosmétiques remontent à la liste des catégories, les autres à la boutique. */
export function shelfParent(shelf: ShopShelf): Route {
  return shelf === "grades" || shelf === "cosmetiques" ? BOUTIQUE_ROUTE : { screen: "rayon", rayon: "cosmetiques", page: 0 };
}

/** Le nom d'un rayon tel que le joueur le lit. */
function shelfName(shelf: ShopShelf): string {
  if (shelf === "grades") return BOUTIQUE_TEXTES.rayons.grades;
  if (shelf === "cosmetiques") return BOUTIQUE_TEXTES.rayons.cosmetiques;
  return BOUTIQUE_TEXTES.categories[shelf];
}

// ---------------------------------------------------------------- les tuiles

/** La tuile du menu principal : les Gemmes du joueur, d'après le service. */
export function boutiqueTile(view: CompanyView): Entry {
  return {
    text: button(`${ICON.boutique} ${MENU_TEXTES.boutique}`, BOUTIQUE_TEXTES.tuile(formatCount(view.balances.gemmes))),
    target: go(BOUTIQUE_ROUTE),
  };
}

/** Une tuile d'article : possédé (l'abonnement : en cours), bloqué (cadenas, et ce qu'il faut), ou son prix. Elle ouvre toujours la fiche. */
function articleTile(article: Article, shelf: ArticleShelf, page: number): Entry {
  const { item } = article;
  const details = BOUTIQUE_TEXTES.details;
  const target = go({ screen: "article", itemId: item.itemId, shelf, page });
  if (item.owned) {
    return {
      text: button(`${colored(COLOR.success, ICON.possede)} ${article.nom}`, article.kind === "abonnement" ? details.enCours : details.possede),
      target,
    };
  }
  if (!item.available) {
    const why = article.kind === "grade" ? details.bloque : details.indisponible;
    return { text: `${COLOR.locked}${ICON.cadenas} ${article.nom}\n${why}${COLOR.reset}`, target };
  }
  return { text: button(`${ICON.gemmes} ${article.nom}`, details.prix(formatCount(item.gemmes))), target };
}

// ---------------------------------------------------------------- la boutique

/**
 * L'écran de la boutique : les Gemmes du joueur (celles du service), le rappel du vote du jour quand il n'a pas voté, puis les
 * portes : grades, Pilier, cosmétiques, boost collectif, pass de saison. Sans lecture du catalogue, seules les portes des
 * rayons et celle du pass restent : le Pilier et le boost sont des articles du catalogue, donc de son prix.
 */
export function boutiqueScreen(
  view: CompanyView,
  catalog: CatalogOutcome | undefined,
  pass: PassOutcome | undefined,
  votes: VotesOutcome | undefined,
  notice?: Notice,
): Screen {
  const texts = BOUTIQUE_TEXTES;
  const articles = articlesOf(catalog);
  const known = itemsOf(catalog) !== undefined;

  const entries: Entry[] = [];
  const grades = inShelf(articles, "grades");
  const next = grades.find((article) => !article.item.owned);
  entries.push({
    text: button(`${ICON.boutique} ${texts.rayons.grades}`, !known ? undefined : next === undefined ? texts.details.tousLesGrades : texts.details.prochainGrade(next.nom)),
    target: go({ screen: "rayon", rayon: "grades", page: 0 }),
  });
  for (const article of articles.filter((candidate) => candidate.kind === "abonnement")) entries.push(articleTile(article, "direct", 0));
  entries.push({
    text: button(`${ICON.boutique} ${texts.rayons.cosmetiques}`),
    target: go({ screen: "rayon", rayon: "cosmetiques", page: 0 }),
  });
  for (const article of articles.filter((candidate) => candidate.kind === "boost")) entries.push(articleTile(article, "direct", 0));
  entries.push(passTile(pass), { text: texts.retour, target: go(MAIN) });

  const lines = [colored(COLOR.gemmes, texts.solde(formatCount(view.balances.gemmes)))];
  if (view.balances.gemmes <= 0) lines.push(colored(COLOR.muted, texts.gemmesManquent));
  const reminder = reminderLine(votes);
  if (reminder !== undefined) lines.push(reminder);
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- un rayon

/**
 * Un rayon : les grades dans l'ordre du service, la liste des catégories de cosmétiques, ou les cosmétiques d'une catégorie.
 * Rend undefined si le catalogue n'a pas été lu : la boutique répond alors par la bulle de l'échec.
 */
export function rayonScreen(
  catalog: CatalogOutcome | undefined,
  shelf: ShopShelf,
  page: number,
  tilesPerScreen: number,
  notice?: Notice,
): Screen | undefined {
  if (itemsOf(catalog) === undefined) return undefined;
  const texts = BOUTIQUE_TEXTES;
  const articles = articlesOf(catalog);
  const back: Entry = { text: texts.retour, target: go(shelfParent(shelf)) };
  const title = `${COLOR.bold}${shelfName(shelf)}${COLOR.reset}`;

  if (shelf === "cosmetiques") {
    const rows: Entry[] = [];
    for (const category of Object.keys(texts.categories) as CosmeticCategory[]) {
      const count = inShelf(articles, category).length;
      if (count === 0) continue;
      rows.push({
        text: button(`${ICON.boutique} ${texts.categories[category]}`, texts.details.articles(count)),
        target: go({ screen: "rayon", rayon: category, page: 0 }),
      });
    }
    const lines = [title, ...(rows.length === 0 ? [texts.details.rayonVide] : [])];
    return screenOf(texts.rayon.titre, bodyOf(notice, lines), [...rows, back]);
  }

  const perPage = Math.max(1, Math.floor(tilesPerScreen));
  const rows = inShelf(articles, shelf).map((article, index) => articleTile(article, shelf, Math.floor(index / perPage)));
  const paged = pageOf(rows, page, tilesPerScreen, (target) => ({ screen: "rayon", rayon: shelf, page: target }));
  const lines = [title];
  if (rows.length === 0) lines.push(texts.details.rayonVide);
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.rayon.titre, bodyOf(notice, lines), [...paged.entries, back]);
}

// ---------------------------------------------------------------- la fiche d'un article

/** Ce que l'article apporte, en lignes courtes : les chiffres de definitions/boutique.json, mis en forme. */
function infoLines(article: Article): string[] {
  const texts = BOUTIQUE_TEXTES.article;
  switch (article.kind) {
    case "grade":
      return [texts.grade(formatAmount(article.bonus)), texts.gradeOrdre];
    case "abonnement": {
      const lines = [texts.abonnement(formatCount(article.jours))];
      if (article.bonus !== undefined) lines.push(texts.abonnementBonus(formatAmount(article.bonus)));
      if (article.rendues !== undefined && article.rendues > 0) lines.push(texts.abonnementRendu(formatCount(article.rendues)));
      return lines;
    }
    case "cosmetique":
      return [texts.cosmetique(BOUTIQUE_TEXTES.categories[article.categorie])];
    case "boost":
      return [texts.boost(formatAmount(article.pourcent), dureeEnMots(article.minutes)), texts.boostPseudo, texts.boostSuite];
  }
}

/** Peut-on l'acheter pour soi ? Pas un grade ou un cosmétique déjà possédé ; un abonnement se prolonge. Le service juge quand même. */
function canBuy(article: Article): boolean {
  return article.item.available && (!article.item.owned || article.kind === "abonnement");
}

/**
 * La fiche d'un article : son nom, son prix en Gemmes (celui du service), ce qu'il apporte, s'il est possédé ou bloqué, puis
 * Acheter (ou Prolonger) et Offrir. Un boost ne s'offre pas. Rend undefined si le catalogue n'a plus cet article.
 */
export function articleScreen(
  view: CompanyView,
  catalog: CatalogOutcome | undefined,
  itemId: string,
  shelf: ArticleShelf,
  page: number,
  notice?: Notice,
): Screen | undefined {
  const article = findArticle(catalog, itemId);
  if (article === undefined) return undefined;
  const texts = BOUTIQUE_TEXTES.article;
  const { item } = article;
  const price = formatCount(item.gemmes);

  const lines = [`${COLOR.bold}${article.nom}${COLOR.reset}`, ...infoLines(article), "", colored(COLOR.gemmes, texts.prix(price))];
  if (item.owned) {
    lines.push(colored(COLOR.success, `${ICON.possede} ${article.kind === "abonnement" ? texts.enCours : texts.possede}`));
  } else if (!item.available) {
    lines.push(colored(COLOR.locked, `${ICON.cadenas} ${article.kind === "grade" ? texts.bloque : BOUTIQUE_TEXTES.details.indisponible}`));
  }
  lines.push(colored(COLOR.gemmes, BOUTIQUE_TEXTES.solde(formatCount(view.balances.gemmes))));

  const entries: Entry[] = [];
  if (canBuy(article)) {
    const label = article.kind === "abonnement" && item.owned ? texts.prolonger : texts.acheter;
    entries.push({
      text: button(`${ICON.gemmes} ${label}`, BOUTIQUE_TEXTES.details.prix(price)),
      target: { kind: "buyItem", itemId },
    });
  }
  if (article.kind !== "boost") {
    entries.push({
      text: button(`${ICON.cadeau} ${texts.offrir}`, BOUTIQUE_TEXTES.details.prix(price)),
      target: go({ screen: "offrir", itemId, shelf, page, choix: 0 }),
    });
  }
  entries.push({ text: texts.retour, target: go(articleParent(shelf, page)) });
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- offrir

/**
 * Le choix du joueur à qui l'on offre l'article : les joueurs connectés que le service connaît, sauf le joueur lui-même
 * (le même choix que pour inviter un associé). C'est encore le joueur qui paie. Rend undefined si le catalogue n'a plus l'article.
 */
export function offrirScreen(
  catalog: CatalogOutcome | undefined,
  online: readonly OnlinePlayer[],
  self: Xuid,
  itemId: string,
  shelf: ArticleShelf,
  page: number,
  choix: number,
  tilesPerScreen: number,
  notice?: Notice,
): Screen | undefined {
  const article = findArticle(catalog, itemId);
  if (article === undefined || article.kind === "boost") return undefined;
  const texts = BOUTIQUE_TEXTES.offrir;
  const others = online.filter((player) => player.xuid !== self);
  const rows: Entry[] = others.map((player) => ({ text: player.name, target: { kind: "giftItem", itemId, forXuid: player.xuid } }));
  const paged = pageOf(rows, choix, tilesPerScreen, (target) => ({ screen: "offrir", itemId, shelf, page, choix: target }));
  const back: Entry = { text: texts.retour, target: go({ screen: "article", itemId, shelf, page }) };

  const lines = [
    others.length === 0 ? texts.aucun : texts.phrase(article.nom, formatCount(article.item.gemmes)),
  ];
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.titre, bodyOf(notice, lines), [...paged.entries, back]);
}

// ---------------------------------------------------------------- ce que dit Oooscar après un achat

/** Comment l'achat va être demandé, d'après le catalogue lu : en cadeau, ou en prolongeant un abonnement qui court déjà. */
export function purchaseContext(catalog: CatalogOutcome | undefined, itemId: string, gift: boolean): PurchaseContext {
  const article = findArticle(catalog, itemId);
  return { gift, extended: !gift && article?.kind === "abonnement" && article.item.owned };
}

/** Le titre Pionnier sous le nom de l'entreprise, quand `join` dit que le joueur le possède. Undefined sinon. */
export function pioneerLine(cosmetics: readonly string[]): string | undefined {
  if (!cosmetics.includes(PIONEER_COSMETIC_ID)) return undefined;
  return colored(COLOR.muted, BOUTIQUE_TEXTES.titreDuJoueur(cosmeticName(PIONEER_COSMETIC_ID)));
}

/** Comment l'achat a été demandé : en cadeau, ou en prolongeant un abonnement qui courait déjà. */
export interface PurchaseContext {
  readonly gift: boolean;
  readonly extended: boolean;
}

/**
 * La bulle d'un achat : réussi (l'article, le cadeau, le boost, la piste premium), refusé faute de Gemmes (avec où en acheter),
 * ou refusé pour un cadeau dont les mots changent (il l'a déjà, il lui faut le grade précédent). Rend undefined pour les autres
 * résultats : le menu prend alors la bulle de `noticeOf`.
 */
export function boutiqueNotice(outcome: ActionOutcome, context: PurchaseContext): Notice | undefined {
  const bulles = BOUTIQUE_TEXTES.bulles;
  if (outcome.kind === "itemBought") {
    if (context.gift) return { bubble: bulles.offert };
    if (outcome.itemId === BOOST_ITEM_ID) return { bubble: bulles.boostLance };
    if (outcome.itemId === PASS_PREMIUM_ITEM_ID) return { bubble: PASS_TEXTES.bulles.premiumActif };
    return { bubble: context.extended ? bulles.prolonge : bulles.achete };
  }
  if (outcome.kind === "refused") {
    if (outcome.reason === "PAS_ASSEZ_DE_GEMMES") {
      return { bubble: refusalBubble(outcome.reason, outcome.missing), lines: [BOUTIQUE_TEXTES.gemmesManquent] };
    }
    if (context.gift && outcome.reason === "DEJA_POSSEDE") return { bubble: bulles.dejaChezLui };
    if (context.gift && outcome.reason === "GRADE_PRECEDENT_REQUIS") return { bubble: bulles.gradePrecedentPourLui };
    return undefined;
  }
  // Le service répond 400 : l'article n'existe plus, ou, pour un cadeau, il ne connaît pas le destinataire.
  if (isRejectedRequest(outcome)) return { bubble: context.gift ? ASSOCIES_TEXTES.bulles.joueurInconnu : bulles.plusEnVente };
  return undefined;
}
