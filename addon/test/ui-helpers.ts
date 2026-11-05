// Faux partagés par les tests des écrans : une horloge que le test fait avancer, une API de l'entreprise qui
// rend des réponses prévues et note ses appels, un présentateur de formulaires qui répond à la place du joueur.
// Rien ici n'importe @minecraft/*.

import type { CoreApi } from "../src/api";
import type { ApiResult } from "../src/http";
import { provisionalXuid } from "../src/identity";
import type {
  ActionBase,
  Balances,
  BuyAmount,
  Candidate,
  CollectResponse,
  CompanyState,
  CompanyUpdated,
  JoinResponse,
  MissionState,
  PrestigePreview,
  ShareInput,
  Xuid,
} from "../src/protocol";
import type { Clock } from "../src/runtime";
import { createPlayerStore, type PlayerStore } from "../src/state";
import { createViewStore, type ViewStore } from "../src/ui/etat";
import type { ActionFormSpec, FormAnswer, FormPresenter, ModalAnswer, ModalFormSpec } from "../src/ui/formulaires";
import { balances, companyState, joinResponse, missionState, ok } from "./helpers";

export interface FakeClock extends Clock {
  advance(ms: number): void;
}

export function fakeClock(startMs = 1_000_000): FakeClock {
  let now = startMs;
  return {
    now: () => now,
    advance(ms) {
      now += ms;
    },
  };
}

export const GAMERTAG = "Alex";
export const XUID: Xuid = provisionalXuid(GAMERTAG);
/** Ce que le menu et le HUD lisent d'un joueur : son nom suffit, l'identifiant s'en déduit. */
export const PLAYER = { name: GAMERTAG };

/** Un magasin de joueurs où Alex est arrivé avec cet état d'entreprise. */
export function storeWith(state: Partial<CompanyState> = {}, soldes: Partial<Balances> = {}): PlayerStore {
  const store = createPlayerStore();
  store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse({ balances: balances(soldes), state: companyState(state) }) });
  return store;
}

export function viewsWith(
  state: Partial<CompanyState> = {},
  clock: Clock = fakeClock(),
  soldes: Partial<Balances> = {},
): { views: ViewStore; store: PlayerStore } {
  const store = storeWith(state, soldes);
  return { views: createViewStore({ players: store, clock }), store };
}

export function joinOf(store: PlayerStore): JoinResponse {
  const joined = store.get(XUID);
  if (joined === undefined) throw new Error("joueur absent du magasin");
  return joined.join;
}

// ---------------------------------------------------------------- l'API de l'entreprise

export type Company = Pick<
  CoreApi,
  | "getCompany"
  | "collect"
  | "buyLevels"
  | "restoreStep"
  | "prestige"
  | "prestigePreview"
  | "getCandidates"
  | "hire"
  | "assignStaff"
  | "fireStaff"
  | "invite"
  | "setShares"
  | "acceptInvite"
>;

export type Call =
  | { readonly op: "getCompany"; readonly companyId: number }
  | { readonly op: "collect"; readonly companyId: number; readonly action: ActionBase }
  | { readonly op: "buyLevels"; readonly companyId: number; readonly factoryId: string; readonly action: ActionBase; readonly amount: BuyAmount }
  | { readonly op: "restoreStep"; readonly companyId: number; readonly factoryId: string; readonly action: ActionBase; readonly step: number }
  | { readonly op: "prestige"; readonly companyId: number; readonly action: ActionBase }
  | { readonly op: "prestigePreview"; readonly companyId: number }
  | { readonly op: "getCandidates"; readonly companyId: number }
  | { readonly op: "hire"; readonly companyId: number; readonly slot: number; readonly action: ActionBase; readonly factoryId: string | undefined }
  | { readonly op: "assignStaff"; readonly companyId: number; readonly staffId: number; readonly action: ActionBase; readonly factoryId: string }
  | { readonly op: "fireStaff"; readonly companyId: number; readonly staffId: number; readonly action: ActionBase }
  | { readonly op: "invite"; readonly companyId: number; readonly action: ActionBase; readonly targetXuid: Xuid }
  | { readonly op: "setShares"; readonly companyId: number; readonly action: ActionBase; readonly shares: readonly ShareInput[] }
  | { readonly op: "acceptInvite"; readonly companyId: number; readonly action: ActionBase };

export interface Script {
  getCompany?: Array<ApiResult<CompanyState>>;
  collect?: Array<ApiResult<CollectResponse>>;
  buyLevels?: Array<ApiResult<CompanyUpdated>>;
  restoreStep?: Array<ApiResult<CompanyUpdated>>;
  prestige?: Array<ApiResult<CompanyUpdated>>;
  prestigePreview?: Array<ApiResult<PrestigePreview>>;
  getCandidates?: Array<ApiResult<Candidate[]>>;
  hire?: Array<ApiResult<CompanyUpdated>>;
  assignStaff?: Array<ApiResult<CompanyUpdated>>;
  fireStaff?: Array<ApiResult<CompanyUpdated>>;
  invite?: Array<ApiResult<CompanyUpdated>>;
  setShares?: Array<ApiResult<CompanyUpdated>>;
  acceptInvite?: Array<ApiResult<CompanyUpdated>>;
}

export interface FakeCompany extends Company {
  readonly calls: Call[];
}

/** Rend les réponses prévues dans l'ordre pour chaque route ; un appel de trop est une erreur du test. */
export function fakeCompany(script: Script = {}): FakeCompany {
  const calls: Call[] = [];
  function next<T>(op: keyof Script, queue: Array<ApiResult<T>> | undefined): Promise<ApiResult<T>> {
    const answer = queue?.shift();
    if (answer === undefined) return Promise.reject(new Error(`appel imprévu : ${op}`));
    return Promise.resolve(answer);
  }
  return {
    calls,
    getCompany(companyId) {
      calls.push({ op: "getCompany", companyId });
      return next("getCompany", script.getCompany);
    },
    collect(companyId, action) {
      calls.push({ op: "collect", companyId, action });
      return next("collect", script.collect);
    },
    buyLevels(companyId, factoryId, action, amount) {
      calls.push({ op: "buyLevels", companyId, factoryId, action, amount });
      return next("buyLevels", script.buyLevels);
    },
    restoreStep(companyId, factoryId, action, step) {
      calls.push({ op: "restoreStep", companyId, factoryId, action, step });
      return next("restoreStep", script.restoreStep);
    },
    prestige(companyId, action) {
      calls.push({ op: "prestige", companyId, action });
      return next("prestige", script.prestige);
    },
    prestigePreview(companyId) {
      calls.push({ op: "prestigePreview", companyId });
      return next("prestigePreview", script.prestigePreview);
    },
    getCandidates(companyId) {
      calls.push({ op: "getCandidates", companyId });
      return next("getCandidates", script.getCandidates);
    },
    hire(companyId, slot, action, factoryId) {
      calls.push({ op: "hire", companyId, slot, action, factoryId });
      return next("hire", script.hire);
    },
    assignStaff(companyId, staffId, action, factoryId) {
      calls.push({ op: "assignStaff", companyId, staffId, action, factoryId });
      return next("assignStaff", script.assignStaff);
    },
    fireStaff(companyId, staffId, action) {
      calls.push({ op: "fireStaff", companyId, staffId, action });
      return next("fireStaff", script.fireStaff);
    },
    invite(companyId, action, targetXuid) {
      calls.push({ op: "invite", companyId, action, targetXuid });
      return next("invite", script.invite);
    },
    setShares(companyId, action, shares) {
      calls.push({ op: "setShares", companyId, action, shares });
      return next("setShares", script.setShares);
    },
    acceptInvite(companyId, action) {
      calls.push({ op: "acceptInvite", companyId, action });
      return next("acceptInvite", script.acceptInvite);
    },
  };
}

// ---------------------------------------------------------------- l'API des missions

export interface FakeMissionsApi extends Pick<CoreApi, "getMissions"> {
  /** Le joueur de chaque lecture, dans l'ordre. */
  readonly calls: Xuid[];
}

/** Rend les réponses dans l'ordre ; quand elles sont épuisées, la dernière sert encore. Par défaut : la mission de bienvenue, à zéro. */
export function fakeMissionsApi(script: ReadonlyArray<ApiResult<MissionState>> = [ok(missionState())]): FakeMissionsApi {
  const calls: Xuid[] = [];
  return {
    calls,
    getMissions(xuid) {
      calls.push(xuid);
      const answer = script[Math.min(calls.length - 1, script.length - 1)];
      if (answer === undefined) return Promise.reject(new Error("script des missions vide"));
      return Promise.resolve(answer);
    },
  };
}

export function refused<T = never>(reason: string, missing?: number): ApiResult<T> {
  return missing === undefined ? { kind: "refused", status: 409, reason } : { kind: "refused", status: 409, reason, missing };
}

export function failure<T = never>(status: number, reason: string): ApiResult<T> {
  return { kind: "error", status, reason };
}

export function unreachable<T = never>(): ApiResult<T> {
  return { kind: "network", message: "délai dépassé" };
}

/** Des identifiants de requête prévisibles : « id-1 », « id-2 »… */
export function sequentialIds(): () => string {
  let n = 0;
  return () => {
    n += 1;
    return `id-${n}`;
  };
}

// ---------------------------------------------------------------- le présentateur de formulaires

export type Reply = (spec: ActionFormSpec) => FormAnswer | Promise<FormAnswer>;

/** Répond en appuyant sur le premier bouton dont le texte contient `text`. Échoue si aucun ne convient. */
export function press(text: string): Reply {
  return (spec) => {
    const index = spec.buttons.findIndex((candidate) => candidate.includes(text));
    if (index < 0) throw new Error(`aucun bouton « ${text} » dans ${JSON.stringify(spec.buttons)}`);
    return { kind: "selected", index };
  };
}

export const close: Reply = () => ({ kind: "closed" });
export const busy: Reply = () => ({ kind: "busy" });

/** La réponse du joueur à un formulaire de champs de texte. */
export type FormReply = (spec: ModalFormSpec) => ModalAnswer | Promise<ModalAnswer>;

/** Envoie le formulaire avec ces valeurs, une par champ. */
export function submit(...values: string[]): FormReply {
  return () => ({ kind: "submitted", values });
}

export const closeForm: FormReply = () => ({ kind: "closed" });
export const busyForm: FormReply = () => ({ kind: "busy" });

export interface FakePresenter extends FormPresenter<{ readonly name: string }> {
  /** Chaque écran montré, dans l'ordre. */
  readonly shown: ActionFormSpec[];
  /** Chaque formulaire de champs montré, dans l'ordre. */
  readonly asked: ModalFormSpec[];
}

/** Répond aux écrans dans l'ordre prévu, et aux formulaires dans l'ordre de `forms` ; un écran de trop est une erreur du test. */
export function fakePresenter(replies: readonly Reply[], forms: readonly FormReply[] = []): FakePresenter {
  const shown: ActionFormSpec[] = [];
  const asked: ModalFormSpec[] = [];
  let index = 0;
  let formIndex = 0;
  return {
    shown,
    asked,
    async show(_player, spec) {
      shown.push(spec);
      const reply = replies[index];
      index += 1;
      if (reply === undefined) throw new Error(`écran imprévu : ${spec.title}`);
      return reply(spec);
    },
    async ask(_player, spec) {
      asked.push(spec);
      const reply = forms[formIndex];
      formIndex += 1;
      if (reply === undefined) throw new Error(`formulaire imprévu : ${spec.title}`);
      return reply(spec);
    },
  };
}

/** Le texte brut d'un écran : titre, corps et boutons, sans les codes de couleur. */
export function plain(spec: ActionFormSpec): string {
  return [spec.title, spec.body, ...spec.buttons].join("\n").replaceAll(/§./g, "");
}

/** Le texte brut d'une chaîne, sans les codes de couleur. */
export function bare(text: string): string {
  return text.replaceAll(/§./g, "");
}
