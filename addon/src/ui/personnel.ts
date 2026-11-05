// Les écrans du personnel : l'équipe usine par usine, la fiche d'un membre, l'affectation, le renvoi, et l'agence qui
// propose les candidats du jour.
//
//   PERSONNEL ─┬─ une équipe par usine (puis « Sans usine ») ─ un membre ─┬─ Affecter à une usine
//              │                                                           └─ Renvoyer ─ confirmation
//              └─ AGENCE ─ un candidat ─ Embaucher ─ le choix de l'usine (ou aucune)
//
// Des fonctions pures, comme celles de menu.ts : une vue de l'entreprise (ce que le service a dit) et, pour l'agence,
// la dernière lecture des candidats entrent ; un écran sort. Rien n'y est calculé : le bonus, le salaire, le prix
// d'embauche et la rareté sont des champs du service, mis en forme par nombres.ts. L'add-on ne sait pas quels postes
// une usine a, ni qui a le droit de renvoyer : il demande, et le service répond par un refus que la bulle d'Oooscar dit.
// Un écran rend `undefined` quand ce qu'il montre n'existe plus (membre renvoyé ailleurs, candidat disparu) : le parcours
// remonte alors au parent.

import type { Candidate, Staff, StaffMember } from "../protocol";
import type { CandidatesOutcome } from "./actions";
import { COLOR, colored, ICON, RARITY_COLOR } from "./apparence";
import { MAIN, STATUS_MARK, bodyOf, button, go, pageOf, screenOf, type Entry, type Notice, type Screen } from "./ecran";
import { associesDetail } from "./associes";
import type { CompanyView } from "./etat";
import { formatAmount } from "./nombres";
import { AGENCE_TEXTES, BULLES, FICHE_TEXTES, MENU_TEXTES, PERSONNEL_TEXTES, USINES_TEXTES, factoryName, failureBubble } from "./textes";
import { factoryStatus } from "./usines";

// ---------------------------------------------------------------- ce que le service dit du personnel

/** Le personnel embauché. Le contrat ne marque pas la liste obligatoire : absente, elle est vide. */
function membersOf(view: CompanyView): readonly StaffMember[] {
  return view.state.staff ?? [];
}

/** L'usine d'un membre, ou null tant qu'il n'en a pas. */
function factoryOf(member: Staff): string | null {
  return member.factoryId ?? null;
}

function teamOf(view: CompanyView, factoryId: string | null): StaffMember[] {
  return membersOf(view).filter((member) => factoryOf(member) === factoryId);
}

/** Une équipe : les membres d'une usine, ou (`factoryId` nul) ceux qui n'ont pas d'usine. */
interface Team {
  readonly factoryId: string | null;
  readonly members: StaffMember[];
}

/**
 * Les équipes qui ont du monde : d'abord les usines de l'entreprise dans l'ordre du service, puis une usine que
 * l'entreprise ne montre pas (au cas où), puis les membres sans usine.
 */
function teamsOf(view: CompanyView): Team[] {
  const members = membersOf(view);
  const ids: string[] = [];
  for (const id of [...view.state.factories.map((factory) => factory.factoryId), ...members.map(factoryOf)]) {
    if (id !== null && !ids.includes(id)) ids.push(id);
  }
  const teams: Team[] = [];
  for (const id of [...ids, null]) {
    const team = teamOf(view, id);
    if (team.length > 0) teams.push({ factoryId: id, members: team });
  }
  return teams;
}

function teamName(factoryId: string | null): string {
  return factoryId === null ? PERSONNEL_TEXTES.sansUsine : factoryName(factoryId);
}

/** Le repère d'une équipe : celui de son usine, ou celui du personnel pour les membres sans usine. */
function teamMark(view: CompanyView, factoryId: string | null): string {
  if (factoryId === null) return ICON.personnel;
  const factory = view.state.factories.find((candidate) => candidate.factoryId === factoryId);
  return factory === undefined ? ICON.usine : STATUS_MARK[factoryStatus(factory)];
}

/** Le nom d'un membre ou d'un candidat, tel que le service le donne, de la couleur de sa rareté. */
function nameOf(person: Staff): string {
  return colored(RARITY_COLOR[person.rarity], person.name);
}

function sorteOf(person: Staff): string {
  return PERSONNEL_TEXTES.sorte(PERSONNEL_TEXTES.raretes[person.rarity], PERSONNEL_TEXTES.roles[person.role]);
}

/** La ligne d'info d'un membre ou d'un candidat dans une liste : rareté, rôle, bonus. */
function lineOf(person: Staff): string {
  return PERSONNEL_TEXTES.ligne(PERSONNEL_TEXTES.raretes[person.rarity], PERSONNEL_TEXTES.roles[person.role], formatAmount(person.bonusPercent));
}

/** Les lignes que la fiche d'un membre et celle d'un candidat ont en commun : rareté et rôle, nom, bonus. */
function personLines(person: Staff): string[] {
  return [
    colored(RARITY_COLOR[person.rarity], sorteOf(person)),
    `${COLOR.bold}${person.name}${COLOR.reset}`,
    "",
    PERSONNEL_TEXTES.membre.bonus(formatAmount(person.bonusPercent)),
  ];
}

function skinLine(person: Staff): string {
  return colored(COLOR.muted, PERSONNEL_TEXTES.membre.apparence(person.skinId));
}

// ---------------------------------------------------------------- la ligne d'équipe de la fiche d'usine

/**
 * La ligne d'équipe de la fiche d'une usine : combien de membres y travaillent, et s'il y a un chef (un membre qui
 * n'est pas un simple salarié). Rien quand personne n'y travaille.
 */
export function teamLine(view: CompanyView, factoryId: string): string | undefined {
  const team = teamOf(view, factoryId);
  if (team.length === 0) return undefined;
  return FICHE_TEXTES.equipe(MENU_TEXTES.nombreMembres(team.length), team.some((member) => member.role !== "SALARIE"));
}

// ---------------------------------------------------------------- le personnel

const PERSONNEL_PAGE_ZERO = { screen: "personnel", page: 0 } as const;

/**
 * L'écran Personnel : la porte de l'agence et celle des associés, puis une tuile par équipe (une par usine qui a du monde,
 * puis les membres sans usine). Les deux portes restent sur chaque page.
 */
export function personnelScreen(view: CompanyView, page: number, tilesPerScreen: number, notice?: Notice): Screen {
  const teams = teamsOf(view);
  const rows: Entry[] = teams.map((team) => ({
    text: button(`${teamMark(view, team.factoryId)} ${teamName(team.factoryId)}`, MENU_TEXTES.nombreMembres(team.members.length)),
    target: go({ screen: "equipe", factoryId: team.factoryId, page: 0 }),
  }));
  const agence: Entry = {
    text: button(`${ICON.agence} ${PERSONNEL_TEXTES.agence}`, PERSONNEL_TEXTES.agenceDetail),
    target: go({ screen: "agence" }),
  };
  const associes: Entry = {
    text: button(`${ICON.associes} ${MENU_TEXTES.associes}`, associesDetail(view)),
    target: go({ screen: "associes" }),
  };
  const paged = pageOf(rows, page, tilesPerScreen - 2, (target) => ({ screen: "personnel", page: target }));
  const entries: Entry[] = [agence, associes, ...paged.entries, { text: PERSONNEL_TEXTES.retour, target: go(MAIN) }];

  const lines: string[] = [PERSONNEL_TEXTES.phrase];
  if (teams.length === 0) lines.push(PERSONNEL_TEXTES.vide);
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(PERSONNEL_TEXTES.titre, bodyOf(notice, lines), entries);
}

function memberTile(member: StaffMember): Entry {
  return { text: button(nameOf(member), lineOf(member)), target: go({ screen: "membre", staffId: member.staffId }) };
}

/** L'équipe d'une usine (`factoryId` nul : les membres sans usine), un membre par tuile. Rend undefined si elle est vide. */
export function equipeScreen(view: CompanyView, factoryId: string | null, page: number, tilesPerScreen: number, notice?: Notice): Screen | undefined {
  const members = teamOf(view, factoryId);
  if (members.length === 0) return undefined;
  const paged = pageOf(members.map(memberTile), page, tilesPerScreen, (target) => ({ screen: "equipe", factoryId, page: target }));
  const entries: Entry[] = [...paged.entries, { text: PERSONNEL_TEXTES.retour, target: go(PERSONNEL_PAGE_ZERO) }];

  const lines = [`${COLOR.bold}${teamName(factoryId)}${COLOR.reset}`, colored(COLOR.muted, MENU_TEXTES.nombreMembres(members.length))];
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(PERSONNEL_TEXTES.equipe.titre, bodyOf(notice, lines), entries);
}

function findMember(view: CompanyView, staffId: number): StaffMember | undefined {
  return membersOf(view).find((member) => member.staffId === staffId);
}

/**
 * La fiche d'un membre : rareté et rôle, nom, bonus, salaire par heure, usine, apparence (tout tel que le service le
 * donne), puis affecter et renvoyer. Rend undefined si le membre n'est plus dans le personnel.
 */
export function membreScreen(view: CompanyView, staffId: number, notice?: Notice): Screen | undefined {
  const member = findMember(view, staffId);
  if (member === undefined) return undefined;
  const texts = PERSONNEL_TEXTES.membre;
  const lines = personLines(member);
  if (member.salaryPerHour !== undefined) lines.push(texts.salaire(formatAmount(member.salaryPerHour)));
  const factoryId = factoryOf(member);
  lines.push(factoryId === null ? texts.sansUsine : texts.usine(factoryName(factoryId)), skinLine(member));

  const entries: Entry[] = [
    { text: texts.affecter, target: go({ screen: "affecter", staffId, page: 0 }) },
    { text: texts.renvoyer, target: go({ screen: "renvoi", staffId }) },
    { text: texts.retour, target: go({ screen: "equipe", factoryId, page: 0 }) },
  ];
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/** Une usine de l'entreprise comme tuile de choix : son repère, son nom, le nombre de membres qui y travaillent. */
function factoryChoice(view: CompanyView, factoryId: string, target: Entry["target"]): Entry {
  const factory = view.state.factories.find((candidate) => candidate.factoryId === factoryId);
  const mark = factory === undefined ? ICON.usine : STATUS_MARK[factoryStatus(factory)];
  return { text: button(`${mark} ${factoryName(factoryId)}`, MENU_TEXTES.nombreMembres(teamOf(view, factoryId).length)), target };
}

/** Le choix de l'usine d'un membre : toutes les usines de l'entreprise sauf la sienne. Le service dit s'il reste un poste. */
export function affecterScreen(view: CompanyView, staffId: number, page: number, tilesPerScreen: number, notice?: Notice): Screen | undefined {
  const member = findMember(view, staffId);
  if (member === undefined) return undefined;
  const texts = PERSONNEL_TEXTES.affecter;
  const rows = view.state.factories
    .filter((factory) => factory.factoryId !== factoryOf(member))
    .map((factory) => factoryChoice(view, factory.factoryId, { kind: "assign", staffId, factoryId: factory.factoryId }));
  const paged = pageOf(rows, page, tilesPerScreen, (target) => ({ screen: "affecter", staffId, page: target }));
  const entries: Entry[] = [...paged.entries, { text: texts.retour, target: go({ screen: "membre", staffId }) }];

  const lines = [nameOf(member), texts.phrase];
  if (rows.length === 0) lines.push(texts.aucune);
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/** La confirmation d'un renvoi : le bouton « Renvoyer » n'existe que sur cet écran. Rend undefined si le membre est parti. */
export function renvoiScreen(view: CompanyView, staffId: number, notice?: Notice): Screen | undefined {
  const member = findMember(view, staffId);
  if (member === undefined) return undefined;
  const texts = PERSONNEL_TEXTES.renvoi;
  const entries: Entry[] = [
    { text: texts.renvoyer, target: { kind: "fire", staffId } },
    { text: texts.garder, target: go({ screen: "membre", staffId }) },
  ];
  return screenOf(texts.titre, bodyOf(notice, [nameOf(member), texts.phrase]), entries);
}

// ---------------------------------------------------------------- l'agence

/** Un candidat déjà embauché porte son `staffId` : c'est le service qui le dit. */
function isHired(candidate: Candidate): boolean {
  return candidate.staffId !== undefined;
}

/**
 * L'agence : les candidats du jour tels que le service les donne, un par tuile, avec la marque « déjà embauché ».
 * Sans lecture (pas faite ou échouée), Oooscar le dit et il ne reste que « Retour » : rien n'est inventé.
 */
export function agenceScreen(lookup: CandidatesOutcome | undefined, notice?: Notice): Screen {
  const back: Entry = { text: AGENCE_TEXTES.retour, target: go(PERSONNEL_PAGE_ZERO) };
  if (lookup?.kind !== "candidates") {
    const bubble = lookup === undefined ? BULLES.serviceInjoignable : failureBubble(lookup.failure, lookup.reason);
    return screenOf(AGENCE_TEXTES.titre, bodyOf(notice ?? { bubble }, []), [back]);
  }
  const rows: Entry[] = lookup.candidates.map((candidate) => ({
    text: button(nameOf(candidate), isHired(candidate) ? AGENCE_TEXTES.dejaEmbauche : lineOf(candidate)),
    target: go({ screen: "candidat", slot: candidate.slot }),
  }));
  const lines = [lookup.candidates.length === 0 ? AGENCE_TEXTES.vide : AGENCE_TEXTES.phrase];
  return screenOf(AGENCE_TEXTES.titre, bodyOf(notice, lines), [...rows, back]);
}

function findCandidate(lookup: CandidatesOutcome | undefined, slot: number): Candidate | undefined {
  return lookup?.kind === "candidates" ? lookup.candidates.find((candidate) => candidate.slot === slot) : undefined;
}

/**
 * La fiche d'un candidat : rareté et rôle, nom, bonus, salaire en pourcent du revenu de l'usine, prix d'embauche et
 * apparence, tels que le service les donne. Un candidat déjà embauché n'a pas de bouton « Embaucher ». Rend undefined si
 * la place n'existe plus.
 */
export function candidatScreen(lookup: CandidatesOutcome | undefined, slot: number, notice?: Notice): Screen | undefined {
  const candidate = findCandidate(lookup, slot);
  if (candidate === undefined) return undefined;
  const texts = AGENCE_TEXTES.candidat;
  const lines = personLines(candidate);
  if (candidate.salaryPercent !== undefined) lines.push(texts.salaire(formatAmount(candidate.salaryPercent)));
  if (candidate.hireCost !== undefined) lines.push(texts.cout(formatAmount(candidate.hireCost)));
  lines.push(skinLine(candidate));

  const entries: Entry[] = [];
  if (isHired(candidate)) lines.push(colored(COLOR.success, AGENCE_TEXTES.dejaEmbauche));
  else entries.push({ text: texts.embaucher, target: go({ screen: "embauche", slot, page: 0 }) });
  entries.push({ text: texts.retour, target: go({ screen: "agence" }) });
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/**
 * Le choix de l'usine d'un candidat qu'on embauche : « Sans usine » d'abord (sur chaque page), puis les usines de
 * l'entreprise. Le service fixe le prix et refuse s'il n'y a plus de poste. Rend undefined si le candidat n'existe plus
 * ou est déjà embauché.
 */
export function embaucheScreen(
  view: CompanyView,
  lookup: CandidatesOutcome | undefined,
  slot: number,
  page: number,
  tilesPerScreen: number,
  notice?: Notice,
): Screen | undefined {
  const candidate = findCandidate(lookup, slot);
  if (candidate === undefined || isHired(candidate)) return undefined;
  const texts = AGENCE_TEXTES.embauche;
  const none: Entry = {
    text: button(`${ICON.personnel} ${texts.sansUsine}`, texts.sansUsineDetail),
    target: { kind: "hire", slot },
  };
  const rows = view.state.factories.map((factory) => factoryChoice(view, factory.factoryId, { kind: "hire", slot, factoryId: factory.factoryId }));
  const paged = pageOf(rows, page, tilesPerScreen - 1, (target) => ({ screen: "embauche", slot, page: target }));
  const entries: Entry[] = [none, ...paged.entries, { text: texts.retour, target: go({ screen: "candidat", slot }) }];

  const lines = [nameOf(candidate), texts.phrase];
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}
