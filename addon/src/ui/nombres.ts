// Le format français des nombres du jeu : virgule décimale, espace entre milliers, suffixes k, M, Md, puis
// aa, ab, ac… (deux lettres, un cran tous les ×1000, aa = 1e12). Des fonctions pures : elles mettent en forme
// une valeur que le service a calculée, elles n'en calculent aucune. Aucun chiffre du jeu ici.
//
// Notation : docs/glossaire.md (Notation), docs/game-design.md (Notation et technique), design/README.md (Les mots).

/**
 * Le séparateur de milliers. Le design demande une espace fine (U+202F). On met l'espace insécable (U+00A0) tant
 * qu'on n'a pas vu sur un vrai BDS que la police du jeu dessine U+202F : un glyphe absent s'afficherait en carré.
 */
export const GROUP_SPACE = " ";

const DECIMAL_SEPARATOR = ",";

/** Chiffres significatifs d'un montant compact : « 84,3 Md », « 512 k », « 1,2 aa » (design/composants/Compteur.md). */
const SIGNIFICANT_DIGITS = 3;

/** En dessous, un montant s'écrit « 0 » : une fraction de Donar ne se lit pas. */
const SMALLEST_SHOWN = 0.01;

const SHORT_SUFFIXES = ["", "k", "M", "Md"] as const;

/** Rang du premier suffixe à deux lettres : aa = 1000^4. */
const FIRST_LETTERED_RANK = SHORT_SUFFIXES.length;

const LETTERS = "abcdefghijklmnopqrstuvwxyz";

/** De aa à zz : 676 suffixes, bien au-delà du plus grand double (suffixe « du » vers 1e306). */
const MAX_RANK = FIRST_LETTERED_RANK + LETTERS.length * LETTERS.length - 1;

const SECONDS_PER_HOUR = 3600;
const MINUTES_PER_HOUR = 60;

/** Le suffixe du rang demandé (rang = puissance de 1000) : 0 → rien, 1 → k, 2 → M, 3 → Md, 4 → aa, 5 → ab… */
export function suffixOf(rank: number): string {
  if (!Number.isInteger(rank) || rank < 0) return "";
  const short = SHORT_SUFFIXES[rank];
  if (short !== undefined) return short;
  const index = Math.min(rank, MAX_RANK) - FIRST_LETTERED_RANK;
  return `${LETTERS[Math.floor(index / LETTERS.length)] ?? ""}${LETTERS[index % LETTERS.length] ?? ""}`;
}

function trimZeros(text: string): string {
  return text.includes(".") ? text.replace(/0+$/, "").replace(/\.$/, "") : text;
}

function frenchDecimal(text: string): string {
  return text.replace(".", DECIMAL_SEPARATOR);
}

function significant(value: number): number {
  return Number(value.toPrecision(SIGNIFICANT_DIGITS));
}

/**
 * Un montant en notation compacte à trois chiffres significatifs, sans zéro inutile :
 * 12 → « 12 », 0,67 → « 0,67 », 1 234 → « 1,23 k », 240 000 → « 240 k », 8,43e10 → « 84,3 Md ».
 * Une valeur illisible (NaN, infini) s'écrit « 0 ».
 */
export function formatAmount(value: number): string {
  if (!Number.isFinite(value)) return "0";
  const sign = value < 0 ? "-" : "";
  const magnitude = Math.abs(value);
  if (magnitude < SMALLEST_SHOWN) return "0";

  let rank = 0;
  while (rank < MAX_RANK && magnitude >= 1000 ** (rank + 1)) rank += 1;
  let mantissa = significant(magnitude / 1000 ** rank);
  if (mantissa >= 1000 && rank < MAX_RANK) {
    // 999,6 k arrondi à trois chiffres vaut 1 000 k : c'est 1 M.
    rank += 1;
    mantissa = 1;
  }
  const text = mantissa >= 1000 ? String(Math.round(mantissa)) : frenchDecimal(trimZeros(mantissa.toPrecision(SIGNIFICANT_DIGITS)));
  const suffix = suffixOf(rank);
  return suffix === "" ? `${sign}${text}` : `${sign}${text}${GROUP_SPACE}${suffix}`;
}

/** Un entier avec une espace entre les milliers : 12400 → « 12 400 ». Pour les Lingots, les Gemmes et les niveaux. */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) return "0";
  const whole = Math.trunc(Math.abs(value));
  if (whole >= 1e21) return formatAmount(value);
  const grouped = String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, GROUP_SPACE);
  return value < 0 && whole > 0 ? `-${grouped}` : grouped;
}

/** Un revenu par heure du service, écrit par seconde comme dans le HUD : 14 400 → « 4/s ». Changement d'unité, pas un calcul de jeu. */
export function formatRate(ratePerHour: number): string {
  return `${formatAmount(ratePerHour / SECONDS_PER_HOUR)}/s`;
}

/** Une durée en heures (les heures hors ligne du service) : 8 → « 8 h », 2,5 → « 2 h 30 », 0,25 → « 15 min ». */
export function formatDuration(hours: number): string {
  if (!Number.isFinite(hours) || hours <= 0) return "0 min";
  const totalMinutes = Math.round(hours * MINUTES_PER_HOUR);
  if (totalMinutes <= 0) return "0 min";
  const wholeHours = Math.floor(totalMinutes / MINUTES_PER_HOUR);
  const minutes = totalMinutes % MINUTES_PER_HOUR;
  if (wholeHours === 0) return `${minutes} min`;
  if (minutes === 0) return `${wholeHours} h`;
  return `${wholeHours} h ${String(minutes).padStart(2, "0")}`;
}

/** Un multiplicateur du service : 2 → « ×2 », 1,5 → « ×1,5 », 1 500 → « ×1,5 k ». */
export function formatMultiplier(multiplier: number): string {
  return `×${formatAmount(multiplier)}`;
}
