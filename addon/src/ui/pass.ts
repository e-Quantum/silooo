// Les écrans du pass de saison : la saison et ses points, les paliers des deux pistes, un palier, et l'achat de la piste premium.
//
//   BOUTIQUE ─ PASS ─┬─ Paliers ─ un palier ─ Réclamer le gratuit / Réclamer le premium
//                    └─ Passer premium (la piste premium s'ouvre par l'achat de l'article `pass-premium`)
//
// Des fonctions pures, comme celles de menu.ts : la dernière lecture du pass (GET getPass) et celle du catalogue entrent,
// un écran sort. Le service compte les points, juge les paliers et verse les récompenses ; l'add-on les montre. Le
// classement d'un palier (réclamé, à réclamer, pas atteint) range deux champs du service l'un contre l'autre (les points du
// joueur, le seuil du palier) pour choisir une icône et un bouton : le service refuse quand même ce qu'il juge invalide,
// et la bulle d'Oooscar dit son refus. Les récompenses sont celles de la lecture : des Lingots sur la piste gratuite, un
// cosmétique (dont le nom est celui de definitions/boutique.json) sur la piste premium. Le prix de la piste premium est
// celui du catalogue du service.

import { BOUTIQUE } from "../monde/definitions";
import { PASS_PREMIUM_ITEM_ID, PASS_TRACKS, passClaimKey, type CatalogItem, type PassState, type PassTier, type PassTrack } from "../protocol";
import type { ActionOutcome, CatalogOutcome, PassOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import { bodyOf, button, go, pageOf, screenOf, type Entry, type Notice, type Screen } from "./ecran";
import { formatCount } from "./nombres";
import { BOUTIQUE_TEXTES, PASS_TEXTES, USINES_TEXTES } from "./textes";

const BOUTIQUE_ROUTE = { screen: "boutique" } as const;
const PASS_ROUTE = { screen: "pass" } as const;

// ---------------------------------------------------------------- ce que le service dit du pass

/** La lecture du pass, ou undefined si elle manque ou a échoué. */
export function passOf(outcome: PassOutcome | undefined): PassState | undefined {
  return outcome?.kind === "pass" ? outcome.pass : undefined;
}

/** Où en est une récompense d'un palier. */
export type RewardState = "aucune" | "reclame" | "aReclamer" | "pasAtteint" | "premiumRequis";

function rewardOf(tier: PassTier, track: PassTrack): { readonly lingots: number } | { readonly cosmeticId: string } | null {
  return track === "GRATUIT" ? tier.free : tier.premium;
}

/**
 * L'état de la récompense d'un palier sur une piste : rien sur cette piste, déjà réclamée (la liste du service),
 * seuil de points pas atteint, piste premium pas ouverte, ou à réclamer.
 */
export function rewardState(pass: PassState, tier: PassTier, track: PassTrack): RewardState {
  if (rewardOf(tier, track) === null) return "aucune";
  if (pass.claimed.includes(passClaimKey(tier.tier, track))) return "reclame";
  if (pass.points < tier.points) return "pasAtteint";
  if (track === "PREMIUM" && !pass.premium) return "premiumRequis";
  return "aReclamer";
}

/** Combien de récompenses attendent d'être réclamées, toutes pistes et tous paliers. */
export function claimableCount(pass: PassState): number {
  let count = 0;
  for (const tier of pass.tiers) {
    for (const track of PASS_TRACKS) if (rewardState(pass, tier, track) === "aReclamer") count += 1;
  }
  return count;
}

/** Le nom d'un cosmétique : celui de definitions/boutique.json, ou son identifiant s'il n'y est pas. */
export function cosmeticName(cosmeticId: string): string {
  return BOUTIQUE.cosmetiques.find((cosmetic) => cosmetic.id === cosmeticId)?.nom ?? cosmeticId;
}

/** Une récompense écrite : « 2 Lingots » ou le nom du cosmétique. */
function rewardText(reward: { readonly lingots: number } | { readonly cosmeticId: string }): string {
  return "lingots" in reward ? PASS_TEXTES.tuile.lingots(formatCount(reward.lingots)) : cosmeticName(reward.cosmeticId);
}

/** « 2026-11-05 » écrit « 05/11/2026 ». Un jour qui n'a pas cette forme est rendu tel que le service l'a donné. */
function dayText(day: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  return match === null ? day : `${match[3]}/${match[2]}/${match[1]}`;
}

function premiumItem(catalog: CatalogOutcome | undefined): CatalogItem | undefined {
  return catalog?.kind === "catalog" ? catalog.items.find((item) => item.itemId === PASS_PREMIUM_ITEM_ID) : undefined;
}

// ---------------------------------------------------------------- la porte du pass, dans la boutique

/** La tuile du pass dans la boutique : combien de récompenses attendent, d'après la dernière lecture du service. */
export function passTile(outcome: PassOutcome | undefined): Entry {
  const pass = passOf(outcome);
  return {
    text: button(`${ICON.pass} ${BOUTIQUE_TEXTES.rayons.pass}`, pass === undefined ? undefined : PASS_TEXTES.aReclamer(claimableCount(pass))),
    target: go(PASS_ROUTE),
  };
}

// ---------------------------------------------------------------- le pass

/**
 * L'écran du pass : le nom de la saison, ses dates, les points du joueur, l'état de la piste premium, puis les paliers et,
 * si la piste premium est à vendre, « Passer premium » avec le prix du catalogue. Rend undefined si la lecture manque.
 */
export function passScreen(outcome: PassOutcome | undefined, catalog: CatalogOutcome | undefined, notice?: Notice): Screen | undefined {
  const pass = passOf(outcome);
  if (pass === undefined) return undefined;
  const lines = [
    `${COLOR.bold}${pass.name}${COLOR.reset}`,
    PASS_TEXTES.dates(dayText(pass.startsOn), dayText(pass.endsOn)),
  ];
  if (!pass.active) lines.push(colored(COLOR.blocked, `${ICON.cadenas} ${PASS_TEXTES.pasEnCours}`));
  lines.push(
    "",
    PASS_TEXTES.points(formatCount(pass.points)),
    pass.premium ? colored(COLOR.success, `${ICON.possede} ${PASS_TEXTES.premium.actif}`) : colored(COLOR.muted, PASS_TEXTES.premium.inactif),
  );

  const entries: Entry[] = [
    {
      text: button(`${ICON.pass} ${PASS_TEXTES.paliers.page}`, PASS_TEXTES.aReclamer(claimableCount(pass))),
      target: go({ screen: "paliers", page: 0 }),
    },
  ];
  const premium = premiumItem(catalog);
  if (!pass.premium && premium !== undefined && premium.available && !premium.owned) {
    entries.push({
      text: button(`${ICON.gemmes} ${PASS_TEXTES.passerPremium}`, PASS_TEXTES.prixPremium(formatCount(premium.gemmes))),
      target: { kind: "buyItem", itemId: PASS_PREMIUM_ITEM_ID },
    });
  }
  entries.push({ text: PASS_TEXTES.retour, target: go(BOUTIQUE_ROUTE) });
  return screenOf(PASS_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- les paliers

/** Le repère et la ligne d'info d'un palier dans la liste, d'après l'état de ses deux récompenses. */
function tierTile(pass: PassState, tier: PassTier, page: number): Entry {
  const states = PASS_TRACKS.map((track) => rewardState(pass, tier, track));
  const name = PASS_TEXTES.tuile.palier(formatCount(tier.tier));
  const target = go({ screen: "palier", tier: tier.tier, page });
  if (states.includes("aReclamer")) {
    return { text: button(`${colored(COLOR.success, ICON.palierAReclamer)} ${name}`, PASS_TEXTES.etats.aReclamer), target };
  }
  const rewards = states.filter((state) => state !== "aucune");
  if (rewards.length > 0 && rewards.every((state) => state === "reclame")) {
    return { text: button(`${colored(COLOR.muted, ICON.palierFait)} ${name}`, PASS_TEXTES.etats.reclame), target };
  }
  if (states.includes("premiumRequis") && !states.includes("pasAtteint")) {
    return { text: button(`${colored(COLOR.locked, ICON.cadenas)} ${name}`, PASS_TEXTES.etats.premiumRequis), target };
  }
  return { text: button(`${colored(COLOR.locked, ICON.cadenas)} ${name}`, PASS_TEXTES.tuile.des(formatCount(tier.points))), target };
}

/** La liste des paliers, une page à la fois, dans l'ordre du service. Rend undefined si la lecture du pass manque. */
export function paliersScreen(outcome: PassOutcome | undefined, page: number, tilesPerScreen: number, notice?: Notice): Screen | undefined {
  const pass = passOf(outcome);
  if (pass === undefined) return undefined;
  // Chaque tuile sait sur quelle page elle tombe : le retour d'un palier revient à la bonne page.
  const perPage = Math.max(1, Math.floor(tilesPerScreen));
  const rows = pass.tiers.map((tier, index) => tierTile(pass, tier, Math.floor(index / perPage)));
  const paged = pageOf(rows, page, tilesPerScreen, (target) => ({ screen: "paliers", page: target }));
  const entries: Entry[] = [...paged.entries, { text: PASS_TEXTES.retour, target: go(PASS_ROUTE) }];
  const lines = [PASS_TEXTES.points(formatCount(pass.points))];
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(PASS_TEXTES.paliers.titre, bodyOf(notice, lines), entries);
}

/** La ligne d'état d'une récompense sur la fiche d'un palier. */
function stateLine(state: RewardState): string {
  const etats = PASS_TEXTES.etats;
  switch (state) {
    case "reclame":
      return colored(COLOR.muted, `${ICON.palierFait} ${etats.reclame}`);
    case "aReclamer":
      return colored(COLOR.success, `${ICON.palierAReclamer} ${etats.aReclamer}`);
    case "pasAtteint":
      return colored(COLOR.locked, `${ICON.cadenas} ${etats.pasAtteint}`);
    case "premiumRequis":
      return colored(COLOR.locked, `${ICON.cadenas} ${etats.premiumRequis}`);
    case "aucune":
      return colored(COLOR.muted, etats.aucune);
  }
}

/**
 * La fiche d'un palier : son seuil de points, puis les deux pistes avec leur récompense et leur état, et un bouton pour chaque
 * récompense à réclamer. Rend undefined si le palier n'existe plus ou si la lecture du pass manque.
 */
export function palierScreen(outcome: PassOutcome | undefined, tierNumber: number, page: number, notice?: Notice): Screen | undefined {
  const pass = passOf(outcome);
  const tier = pass?.tiers.find((candidate) => candidate.tier === tierNumber);
  if (pass === undefined || tier === undefined) return undefined;
  const lines = [`${COLOR.bold}${PASS_TEXTES.tuile.palier(formatCount(tier.tier))}${COLOR.reset}`, PASS_TEXTES.seuil(formatCount(tier.points)), ""];
  const entries: Entry[] = [];
  for (const track of PASS_TRACKS) {
    const reward = rewardOf(tier, track);
    const state = rewardState(pass, tier, track);
    lines.push(PASS_TEXTES.ligne(PASS_TEXTES.pistes[track], reward === null ? PASS_TEXTES.etats.aucune : rewardText(reward)), stateLine(state));
    if (state === "aReclamer") entries.push({ text: PASS_TEXTES.reclamer[track], target: { kind: "claim", tier: tier.tier, track } });
  }
  entries.push({ text: PASS_TEXTES.retour, target: go({ screen: "paliers", page }) });
  return screenOf(PASS_TEXTES.palier.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- ce que dit Oooscar après une action du pass

/**
 * La bulle d'un palier réclamé, avec ce que le service a versé (des Lingots ou un cosmétique, tels qu'il les a dits). Rend
 * undefined pour tout autre résultat : le menu prend alors la bulle de `noticeOf`.
 */
export function passNotice(outcome: ActionOutcome): Notice | undefined {
  if (outcome.kind !== "passClaimed") return undefined;
  const { rewardLingots, cosmeticId } = outcome.claimed;
  const received: string[] = [];
  if (rewardLingots > 0) received.push(PASS_TEXTES.recu(PASS_TEXTES.tuile.lingots(formatCount(rewardLingots))));
  if (cosmeticId !== null) received.push(PASS_TEXTES.recu(cosmeticName(cosmeticId)));
  return { bubble: PASS_TEXTES.bulles.palierReclame, lines: received };
}
