// Les écrans de l'entreprise partagée : la liste des associés, le choix du joueur à inviter, une invitation reçue, et le
// formulaire des parts.
//
//   ASSOCIÉS ─┬─ une invitation reçue ─ Accepter / Plus tard
//             ├─ Inviter ─ un joueur connecté                (le PDG)
//             └─ Parts ─ un formulaire, un champ par membre  (le PDG)
//
// Des fonctions pures, comme celles de menu.ts : l'entreprise du joueur (ce que le service a dit, `members` compris), les
// invitations en attente (ui/invitations.ts) et les joueurs connectés entrent ; un écran sort. Rien n'y est calculé : les
// rôles, les noms et les parts sont ceux de la liste du service, dans son ordre (le PDG d'abord), et l'add-on ne connaît
// aucun maximum d'associés ni aucune règle sur les parts. Il n'additionne rien : le total des parts est jugé par le
// service, qui répond 400 quand il n'est pas bon. Seules les formes sont vérifiées ici (un entier de 0 à 100 par champ).
//
// Qui voit quoi : « Inviter » et « Parts » sont montrés à tout le monde sauf à un associé (le rôle vient de la liste du
// service). Le service refuse quand même (PAS_LE_PDG) : ces boutons ne sont qu'un confort.

import { isSharePercent, type Member, type MemberRole, type ShareInput, type Xuid } from "../protocol";
import type { ActionOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import { MAIN, bodyOf, button, go, pageOf, screenOf, type Entry, type Notice, type Screen } from "./ecran";
import type { CompanyView } from "./etat";
import type { ModalFormSpec } from "./formulaires";
import type { Invitation } from "./invitations";
import { formatCount } from "./nombres";
import { ASSOCIES_TEXTES, USINES_TEXTES } from "./textes";

/** Un joueur connecté que le PDG peut inviter. */
export interface OnlinePlayer {
  readonly xuid: Xuid;
  readonly name: string;
}

const ASSOCIES_ROUTE = { screen: "associes" } as const;

/** Les membres tels que le service les donne. Le contrat ne marque pas la liste obligatoire : absente, elle est vide. */
export function membersOf(view: CompanyView): readonly Member[] {
  return view.state.members ?? [];
}

function roleOf(view: CompanyView, xuid: Xuid): MemberRole | undefined {
  return membersOf(view).find((member) => member.xuid === xuid)?.role;
}

/** Le joueur peut-il inviter ? Oui tant que le service ne l'a pas dit simple associé. */
function canInvite(view: CompanyView, xuid: Xuid): boolean {
  return roleOf(view, xuid) !== "ASSOCIE";
}

/** Le joueur peut-il fixer les parts ? Le PDG, quand il y a des parts à fixer entre plusieurs membres. */
function canSetShares(view: CompanyView, xuid: Xuid): boolean {
  return roleOf(view, xuid) === "PDG" && membersOf(view).length >= 2;
}

/** La ligne d'info de la tuile du menu : le nombre d'associés d'après la liste du service, ou l'invitation à en chercher. */
export function associesDetail(view: CompanyView): string {
  const count = membersOf(view).length;
  return count >= 2 ? ASSOCIES_TEXTES.tuile.nombre(count) : ASSOCIES_TEXTES.tuile.seul;
}

function memberLine(member: Member, xuid: Xuid): string {
  const line = ASSOCIES_TEXTES.ligne(ASSOCIES_TEXTES.roles[member.role], member.name, formatCount(member.sharePercent));
  return member.xuid === xuid ? ASSOCIES_TEXTES.toi(line) : line;
}

/**
 * L'écran des associés : le nom de l'entreprise, un membre par ligne (rôle, nom, part, dans l'ordre du service), puis les
 * invitations reçues, « Inviter » et « Parts » selon le rôle, et « Retour ». Un joueur seul lit qu'il peut inviter jusqu'à
 * ce que le service refuse.
 */
export function associesScreen(view: CompanyView, xuid: Xuid, pending: readonly Invitation[], notice?: Notice): Screen {
  const texts = ASSOCIES_TEXTES;
  const members = membersOf(view);
  const lines = [`${COLOR.bold}${view.state.name}${COLOR.reset}`, ...members.map((member) => memberLine(member, xuid))];
  if (members.length === 1) lines.push("", texts.seul);

  const entries: Entry[] = pending.map((invitation) => ({
    text: button(`${ICON.associes} ${texts.invitation.texte(invitation.invitedByName, invitation.companyName)}`),
    target: go({ screen: "invitation", companyId: invitation.companyId }),
  }));
  if (canInvite(view, xuid)) entries.push({ text: texts.inviter, target: go({ screen: "inviter", page: 0 }) });
  if (canSetShares(view, xuid)) entries.push({ text: texts.parts, target: { kind: "parts" } });
  entries.push({ text: texts.retour, target: go(MAIN) });
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/** Le choix du joueur à inviter : les joueurs connectés que le service connaît, sauf le joueur lui-même, par leur nom. */
export function inviterScreen(online: readonly OnlinePlayer[], self: Xuid, page: number, tilesPerScreen: number, notice?: Notice): Screen {
  const texts = ASSOCIES_TEXTES.choix;
  const others = online.filter((player) => player.xuid !== self);
  const rows: Entry[] = others.map((player) => ({ text: player.name, target: { kind: "invite", targetXuid: player.xuid } }));
  const paged = pageOf(rows, page, tilesPerScreen, (target) => ({ screen: "inviter", page: target }));
  const entries: Entry[] = [...paged.entries, { text: texts.retour, target: go(ASSOCIES_ROUTE) }];

  const lines: string[] = [others.length === 0 ? texts.aucun : texts.phrase];
  if (paged.pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(paged.current + 1, paged.pages)));
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

/** Une invitation reçue : qui invite dans quelle entreprise, ce que le joueur quitte, Accepter ou Plus tard. Undefined si elle a disparu. */
export function invitationScreen(pending: readonly Invitation[], companyId: number, notice?: Notice): Screen | undefined {
  const invitation = pending.find((candidate) => candidate.companyId === companyId);
  if (invitation === undefined) return undefined;
  const texts = ASSOCIES_TEXTES.invitation;
  const lines = [
    `${COLOR.bold}${texts.texte(invitation.invitedByName, invitation.companyName)}${COLOR.reset}`,
    "",
    texts.consequence,
  ];
  const entries: Entry[] = [
    { text: texts.accepter, target: { kind: "accept", companyId } },
    { text: texts.plusTard, target: go(ASSOCIES_ROUTE) },
  ];
  return screenOf(texts.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- le formulaire des parts

/** Le formulaire des parts : un champ par membre, dans l'ordre du service, avec la part que le service a donnée. */
export function partsForm(members: readonly Member[]): ModalFormSpec {
  const texts = ASSOCIES_TEXTES.formulaire;
  return {
    title: texts.titre,
    submit: texts.envoyer,
    fields: members.map((member) => ({
      label: texts.champ(member.name),
      placeholder: texts.indice,
      initial: String(member.sharePercent),
    })),
  };
}

/**
 * Lit les champs du formulaire : un entier de 0 à 100 par membre, dans l'ordre de la liste. Rend undefined si un champ n'a
 * pas cette forme (rien n'est envoyé). Le total n'est pas vérifié : le service le juge.
 */
export function readShares(members: readonly Member[], values: readonly string[]): ShareInput[] | undefined {
  const shares: ShareInput[] = [];
  for (const [index, member] of members.entries()) {
    const text = (values[index] ?? "").trim();
    const percent = /^[0-9]{1,3}$/.test(text) ? Number(text) : Number.NaN;
    if (!isSharePercent(percent)) return undefined;
    shares.push({ xuid: member.xuid, percent });
  }
  return shares;
}

// ---------------------------------------------------------------- les refus qui ont des mots à eux

/** Les actions de l'entreprise partagée. */
export type AssociesAction = "invite" | "setShares" | "acceptInvite";

/** Le service a répondu 400 : il ne reconnaît pas la demande (parts, joueur, invitation). Un refus du jeu est autre chose. */
export function isRejectedRequest(outcome: ActionOutcome): boolean {
  return outcome.kind === "failed" && outcome.failure === "error" && outcome.reason === "REQUETE_INVALIDE";
}

/**
 * La bulle d'une action de l'entreprise partagée quand ses mots changent selon l'action, ou undefined : le menu prend
 * alors celle de `noticeOf`. Deux refus (DEJA_POSSEDE, ENTREPRISE_EN_COURS, ENTREPRISE_PLEINE) ne veulent pas dire la même
 * chose quand on invite et quand on accepte, et un 400 (REQUETE_INVALIDE) dit une chose différente par action : des parts
 * dont le total n'est pas bon, une invitation expirée, un joueur inconnu.
 */
export function associesNotice(outcome: ActionOutcome, action: AssociesAction): Notice | undefined {
  const bulles = ASSOCIES_TEXTES.bulles;
  if (outcome.kind === "refused") {
    if (action === "invite") {
      if (outcome.reason === "DEJA_POSSEDE") return { bubble: bulles.dejaAssocie };
      if (outcome.reason === "ENTREPRISE_EN_COURS") return { bubble: bulles.ilALance };
    }
    if (action === "acceptInvite") {
      if (outcome.reason === "DEJA_POSSEDE") return { bubble: bulles.tuEsDejaAssocie };
      if (outcome.reason === "ENTREPRISE_PLEINE") return { bubble: bulles.entreprisePleine };
    }
    return undefined;
  }
  if (isRejectedRequest(outcome)) {
    if (action === "invite") return { bubble: bulles.joueurInconnu };
    if (action === "setShares") return { bubble: bulles.partsTotal };
    return { bubble: bulles.invitationExpiree };
  }
  return undefined;
}
