// Le HUD, en trois étages (design/composants/Hud.md) :
//
// - en haut : les monnaies (Donar, Lingots, Gemmes), puis une consigne. Deux textes du canal de titre de Bedrock :
//   le titre porte les monnaies, le sous-titre la consigne. Chacun commence par un préfixe que le pack de ressources
//   masque et lit pour placer le texte (hud_screen.json, étape suivante). La consigne est celle de la mission en
//   cours, avec son avancement tel que le service le donne ; sans mission en cours (toutes terminées, ou pas encore
//   lues, ou inconnues de cet add-on), c'est le seuil de prestige ;
// - juste au-dessus de la barre d'inventaire : le gain par seconde, par la barre d'action (`setActionBar`) ;
// - en dessous : rien pour l'instant (île, membres connectés et ping n'ont pas de champ dans le contrat).
//
// Quand le service ne répond pas, le HUD se réduit à « hors ligne ». Le rendu est une fonction pure ; l'enveloppe
// qui parle aux joueurs reste mince. Sans le pack, le canal de titre afficherait le préfixe au milieu de l'écran :
// config.ui.hud.titleChannel le coupe et tout passe alors par la barre d'action.

import { config } from "../config";
import { describeError, secondsToTicks, type Clock, type Scheduler, type StatusLog } from "../runtime";
import { playerXuid } from "../identity";
import { COLOR, colored, ICON } from "./apparence";
import { counterValue } from "./compteur";
import type { MissionState } from "../protocol";
import type { CompanyView, LinkState, LinkStatus, ViewStore } from "./etat";
import type { Missions } from "./missions";
import { formatAmount, formatCount, formatRate } from "./nombres";
import { HUD_TEXTES, missionTexts } from "./textes";

/** Début du titre : les monnaies. Le pack masque ce préfixe et lit ce qui suit. */
export const HUD_TITLE_PREFIX = "silooo:hud|";

/** Début du sous-titre : la consigne. */
export const HUD_SUBTITLE_PREFIX = "silooo:consigne|";

/** Ce que le HUD envoie à un joueur à un instant. Les trois textes peuvent être vides. */
export interface HudFrame {
  readonly title: string;
  readonly subtitle: string;
  readonly actionBar: string;
}

export interface HudInput {
  /** La vue du joueur, ou undefined si le service n'a encore rien dit de lui. */
  readonly view: CompanyView | undefined;
  /** Les missions du joueur d'après la dernière lecture du service, ou undefined si rien n'a pu être lu. */
  readonly missions?: MissionState | undefined;
  readonly link: LinkStatus;
  readonly nowMs: number;
  /** Faux : pas de canal de titre, tout passe par la barre d'action. */
  readonly titleChannel: boolean;
}

const SEPARATOR = "   ";

/** La ligne des monnaies. Le compteur de Donar défile ; les Lingots et les Gemmes viennent du dernier solde du service. */
function currencyLine(view: CompanyView, nowMs: number): string {
  const donar = `${colored(COLOR.donar, ICON.donar)} ${COLOR.paper}${formatAmount(counterValue(view.anchor, nowMs))}${COLOR.reset}`;
  const lingots = `${colored(COLOR.lingots, ICON.lingots)} ${COLOR.lingots}${formatCount(view.balances.lingots)}${COLOR.reset}`;
  const gemmes = `${colored(COLOR.gemmes, ICON.gemmes)} ${COLOR.paper}${formatCount(view.balances.gemmes)}${COLOR.reset}`;
  return [donar, lingots, gemmes].join(SEPARATOR);
}

/** Le gain par seconde, d'après le revenu par heure du service : un chiffre, pas de mot. */
function gainLine(view: CompanyView): string {
  return colored(COLOR.paper, `+${formatRate(view.state.ratePerHour)}`);
}

/**
 * La consigne : la mission en cours (sa consigne, puis `progress`/`target` du service), sinon le seuil de prestige.
 * Une mission dont cet add-on n'a pas les textes laisse la place au prestige : il n'invente pas de mots pour elle.
 */
function consigneLine(view: CompanyView, missions: MissionState | undefined): string {
  const current = missions?.current ?? null;
  const texts = current === null ? undefined : missionTexts(current.id);
  if (current !== null && texts !== undefined) {
    return HUD_TEXTES.mission(texts.consigne, formatCount(current.progress), formatCount(current.target));
  }
  return HUD_TEXTES.consigne(formatAmount(view.state.threshold));
}

function offlineLine(): string {
  return colored(COLOR.blocked, `${ICON.flamme} ${HUD_TEXTES.horsLigne}`);
}

/** Compose le HUD d'un joueur. Pure : mêmes entrées, mêmes textes. */
export function renderHud(input: HudInput): HudFrame {
  const { view, missions, link, nowMs, titleChannel } = input;
  const offline = link === "down" || view === undefined;

  if (!titleChannel) {
    if (offline) return { title: "", subtitle: "", actionBar: offlineLine() };
    return { title: "", subtitle: "", actionBar: `${currencyLine(view, nowMs)}${SEPARATOR}${gainLine(view)}` };
  }
  if (offline) {
    return { title: `${HUD_TITLE_PREFIX}${offlineLine()}`, subtitle: HUD_SUBTITLE_PREFIX, actionBar: "" };
  }
  return {
    title: `${HUD_TITLE_PREFIX}${currencyLine(view, nowMs)}`,
    subtitle: `${HUD_SUBTITLE_PREFIX}${colored(COLOR.muted, consigneLine(view, missions))}`,
    actionBar: gainLine(view),
  };
}

// ---------------------------------------------------------------- l'enveloppe qui parle aux joueurs

/** Les options du titre, telles que `TitleDisplayOptions` de @minecraft/server les attend. */
export interface HudTitleOptions {
  readonly fadeInDuration: number;
  readonly fadeOutDuration: number;
  readonly stayDuration: number;
  readonly subtitle?: string;
}

/** La partie de `player.onScreenDisplay` dont le HUD a besoin. */
export interface HudScreenLike {
  setTitle(title: string, options?: HudTitleOptions): void;
  setActionBar(text: string): void;
}

export interface HudPlayerLike {
  readonly name: string;
  readonly onScreenDisplay: HudScreenLike;
}

export interface HudDeps<P extends HudPlayerLike = HudPlayerLike> {
  readonly players: () => readonly P[];
  readonly views: Pick<ViewStore, "get">;
  /** Les missions des joueurs. Sans lui, la consigne est toujours le seuil de prestige. */
  readonly missions?: Pick<Missions, "get">;
  readonly link: Pick<LinkState, "status">;
  readonly clock: Clock;
  /** Un échec d'envoi à un joueur (il vient de partir) est noté une fois, pas toutes les secondes. */
  readonly status: StatusLog;
  readonly titleChannel?: boolean;
  readonly titleStaySeconds?: number;
  /** Vrai pour un joueur à qui le HUD ne doit rien envoyer : pendant son arrivée et la cinématique, le HUD se tait. */
  readonly silenced?: (player: P) => boolean;
}

/**
 * Un rafraîchissement du HUD pour tous les joueurs présents. Un joueur qui échoue (parti entre-temps) n'empêche
 * pas les autres, et aucune exception ne sort.
 */
export function createHud<P extends HudPlayerLike = HudPlayerLike>(deps: HudDeps<P>): { refresh(): void } {
  const titleChannel = deps.titleChannel ?? config.ui.hud.titleChannel;
  const stayTicks = secondsToTicks(deps.titleStaySeconds ?? config.ui.hud.titleStaySeconds);
  return {
    refresh() {
      let failed: string | undefined;
      for (const player of deps.players()) {
        try {
          if (deps.silenced?.(player) === true) continue;
          const xuid = playerXuid(player);
          const frame = renderHud({
            view: deps.views.get(xuid),
            missions: deps.missions?.get(xuid),
            link: deps.link.status(),
            nowMs: deps.clock.now(),
            titleChannel,
          });
          if (titleChannel) {
            // Pas de fondu et un affichage plus long que l'intervalle : le texte ne clignote jamais entre deux envois.
            player.onScreenDisplay.setTitle(frame.title, {
              fadeInDuration: 0,
              fadeOutDuration: 0,
              stayDuration: stayTicks,
              subtitle: frame.subtitle,
            });
          }
          player.onScreenDisplay.setActionBar(frame.actionBar);
        } catch (error) {
          failed = describeError(error);
        }
      }
      if (failed === undefined) deps.status.ok();
      else deps.status.failed(failed);
    },
  };
}

/** Lance le rafraîchissement du HUD à la cadence de config.ui.hud.refreshSeconds. */
export function startHud(scheduler: Pick<Scheduler, "every">, refresh: () => void, seconds: number = config.ui.hud.refreshSeconds): void {
  scheduler.every(seconds, refresh);
}
