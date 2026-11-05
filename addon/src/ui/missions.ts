// Les missions du joueur vues par les écrans : la dernière réponse de getMissions, gardée par joueur, et les trois
// moments où on la relit (l'arrivée, après chaque encaissement, une annonce de mission terminée).
//
// Le service fait tout : il compte les actions, termine la mission et verse les Lingots (core/README.md, « Les
// missions »). L'add-on ne dit jamais « mission faite » : il lit ce que le service rend et affiche l'annonce que le
// service publie. Ce fichier ne décide de rien ; les chiffres montrés (`progress`, `target`, `rewardLingots`) sont
// ceux de la réponse.
//
// Comme le ViewStore, l'état d'un joueur tient à sa réponse de `join` : un joueur parti n'a plus rien, un joueur qui
// revient repart de zéro. Les lectures d'un même joueur se font l'une après l'autre : une réponse lente ne
// recouvre jamais une plus récente.

import type { CoreApi } from "../api";
import { describeFailure } from "../http";
import type { GameEvent, JoinResponse, MissionState, Xuid } from "../protocol";
import { describeError, type Logger } from "../runtime";
import type { PlayerStore } from "../state";
import { COLOR, colored } from "./apparence";
import { formatCount } from "./nombres";
import { OBJECTIFS_TEXTES, missionEndBubble, renderBubble } from "./textes";

/** La valeur de `payload.action` d'une annonce de mission terminée (api/silooo-core.openapi.yaml, getMissions). */
export const MISSION_ACTION = "mission";

export interface Missions {
  /** Les missions du joueur d'après la dernière lecture, ou undefined s'il n'est pas là ou si rien n'a pu être lu. */
  get(xuid: Xuid): MissionState | undefined;
  /**
   * À l'arrivée du joueur : lit ses missions et retient celles qu'il avait déjà terminées. Une annonce de l'une
   * d'elles qui revient plus tard (le curseur des événements repart de 0 quand le serveur redémarre) ne dit rien.
   * Ne lève pas.
   */
  arrive(xuid: Xuid): Promise<void>;
  /** Relit les missions du joueur. Ne lève pas ; en cas d'échec, la lecture précédente reste. */
  refresh(xuid: Xuid): Promise<void>;
  /** Un événement `ANNONCE` : si c'est une mission terminée pour un joueur présent, Oooscar le dit, puis on relit. Ne lève pas. */
  onAnnonce(event: GameEvent): Promise<void>;
}

export interface MissionsDeps {
  readonly api: Pick<CoreApi, "getMissions">;
  readonly players: Pick<PlayerStore, "get">;
  /** Envoie un texte au joueur (le tchat). Une exception est journalisée, jamais relancée. */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly log: Logger;
}

interface Entry {
  /** La réponse de `join` d'où cet état est parti. */
  readonly source: JoinResponse;
  state: MissionState | undefined;
  /** Les missions déjà terminées à l'arrivée du joueur, et celles dont l'annonce a été dite. */
  readonly told: Set<string>;
}

/** Ce que porte l'annonce d'une mission terminée, quand le payload est lisible. */
interface Announcement {
  readonly missionId: string;
  /** Absent quand le payload n'a pas de Lingots lisibles : seule la bulle est dite. */
  readonly rewardLingots: number | undefined;
}

function readAnnouncement(event: GameEvent): Announcement | undefined {
  if (event.kind !== "ANNONCE") return undefined;
  if (event.payload["action"] !== MISSION_ACTION) return undefined;
  const missionId = event.payload["missionId"];
  if (typeof missionId !== "string" || missionId === "") return undefined;
  const reward = event.payload["rewardLingots"];
  const rewardLingots = typeof reward === "number" && Number.isFinite(reward) && reward >= 0 ? reward : undefined;
  return { missionId, rewardLingots };
}

/** Le texte envoyé au joueur : la bulle d'Oooscar, puis, si le service a donné la somme, la récompense en Lingots. */
export function announcementText(missionId: string, rewardLingots: number | undefined): string {
  const bubble = renderBubble(missionEndBubble(missionId));
  if (rewardLingots === undefined) return bubble;
  const reward = colored(COLOR.lingots, OBJECTIFS_TEXTES.recompense(formatCount(rewardLingots)));
  return `${bubble}\n${reward}`;
}

export function createMissions(deps: MissionsDeps): Missions {
  const { api, players, log } = deps;
  const entries = new Map<Xuid, Entry>();
  const chains = new Map<Xuid, Promise<unknown>>();

  function entryOf(xuid: Xuid): Entry | undefined {
    const joined = players.get(xuid);
    if (joined === undefined) {
      entries.delete(xuid);
      return undefined;
    }
    const known = entries.get(xuid);
    if (known !== undefined && known.source === joined.join) return known;
    const entry: Entry = { source: joined.join, state: undefined, told: new Set() };
    entries.set(xuid, entry);
    return entry;
  }

  /** Un seul appel à la fois par joueur : les tâches se suivent dans l'ordre où elles arrivent. Une tâche ne lève pas. */
  function enqueue<T>(xuid: Xuid, task: () => Promise<T>): Promise<T> {
    const previous = chains.get(xuid) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(task);
    chains.set(xuid, next);
    return next.finally(() => {
      if (chains.get(xuid) === next) chains.delete(xuid);
    });
  }

  /** Lit les missions et les range dans l'entrée du joueur. Rend l'état lu, ou undefined si la lecture a échoué. */
  async function read(xuid: Xuid): Promise<MissionState | undefined> {
    const entry = entryOf(xuid);
    if (entry === undefined) return undefined;
    try {
      const result = await api.getMissions(xuid);
      if (result.kind !== "ok") {
        log.warn(`missions de ${xuid} : ${describeFailure(result)}`);
        return undefined;
      }
      // Le joueur est parti, ou reparti de zéro, pendant l'appel : cette réponse n'est plus la sienne.
      if (entryOf(xuid) !== entry) return undefined;
      entry.state = result.body;
      return result.body;
    } catch (error) {
      log.warn(`missions de ${xuid} : ${describeError(error)}`);
      return undefined;
    }
  }

  function tell(xuid: Xuid, text: string): void {
    try {
      deps.say(xuid, text);
    } catch (error) {
      log.warn(`annonce de mission à ${xuid} : ${describeError(error)}`);
    }
  }

  return {
    get: (xuid) => entryOf(xuid)?.state,

    arrive: (xuid) =>
      enqueue(xuid, async () => {
        const state = await read(xuid);
        const entry = entryOf(xuid);
        if (state === undefined || entry === undefined) return;
        for (const id of state.completed) entry.told.add(id);
      }),

    refresh: (xuid) =>
      enqueue(xuid, async () => {
        await read(xuid);
      }),

    onAnnonce: (event) => {
      const announcement = readAnnouncement(event);
      const xuid = event.xuid;
      if (announcement === undefined || xuid === null) return Promise.resolve();
      return enqueue(xuid, async () => {
        const entry = entryOf(xuid);
        // Un joueur absent n'a personne à qui parler : à son retour, l'arrivée lit l'état du service.
        if (entry === undefined) return;
        if (!entry.told.has(announcement.missionId)) {
          entry.told.add(announcement.missionId);
          tell(xuid, announcementText(announcement.missionId, announcement.rewardLingots));
        }
        await read(xuid);
      });
    },
  };
}
