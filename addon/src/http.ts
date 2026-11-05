// Le client HTTP de silooo-core, au-dessus d'un Transport. Il ne connaît ni @minecraft/server-net ni
// les routes : il envoie du JSON, ajoute les deux en-têtes du contrat et range la réponse dans l'un
// des quatre cas de ApiResult. Le Transport réel est dans beta.ts, un faux dans les tests.

import { describeError } from "./runtime";

// ---------------------------------------------------------------- le Transport

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

declare const secretBrand: unique symbol;

/**
 * Une valeur d'en-tête secrète (la clé X-Silooo-Key). Le code de l'add-on ne peut ni la lire ni
 * l'afficher : il la passe telle quelle au Transport, qui la remet au moteur de scripts. beta.ts
 * est le seul fichier qui sait de quoi elle est faite.
 */
export interface OpaqueSecret {
  readonly [secretBrand]: true;
}

export interface Header {
  readonly name: string;
  readonly value: string | OpaqueSecret;
}

export interface TransportResponse {
  readonly status: number;
  readonly body: string;
}

/**
 * Un aller-retour HTTP. La promesse est rejetée quand le réseau, le délai ou la configuration de BDS
 * empêchent d'obtenir une réponse ; une réponse d'un code d'erreur HTTP est rendue comme les autres
 * (à vérifier sur un vrai BDS : docs/bedrock.md, « Reste à relever »).
 */
export interface Transport {
  send(
    method: HttpMethod,
    url: string,
    headers: readonly Header[],
    body: string | undefined,
    timeoutSeconds: number,
  ): Promise<TransportResponse>;
}

// ---------------------------------------------------------------- le résultat typé

/**
 * - ok : code 2xx ; `body` est le JSON lu, ou undefined pour une réponse vide (204).
 * - refused : 409 ou 403 au corps `Refused` ; `reason` est le code stable du contrat.
 * - error : 400, 401, 500, 501 au corps `Erreur`, et tout autre code d'erreur ; traités d'un bloc.
 * - network : le transport a échoué (réseau coupé, délai dépassé).
 */
export type ApiResult<T> =
  | { readonly kind: "ok"; readonly status: number; readonly body: T }
  | { readonly kind: "refused"; readonly status: number; readonly reason: string; readonly missing?: number }
  | { readonly kind: "error"; readonly status: number; readonly reason: string; readonly detail?: string }
  | { readonly kind: "network"; readonly message: string };

/** Une ligne lisible pour le journal : jamais d'en-tête, jamais de corps. */
export function describeFailure(result: Exclude<ApiResult<unknown>, { kind: "ok" }>): string {
  switch (result.kind) {
    case "refused":
      return result.missing === undefined ? `refusé ${result.reason}` : `refusé ${result.reason}, il manque ${result.missing}`;
    case "error":
      return result.detail === undefined
        ? `erreur ${result.status} ${result.reason}`
        : `erreur ${result.status} ${result.reason} (${result.detail})`;
    case "network":
      return `réseau : ${result.message}`;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readJson(text: string): { readonly ok: true; readonly value: unknown } | { readonly ok: false } {
  if (text.trim() === "") return { ok: true, value: undefined };
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

/** Range une réponse du transport dans un ApiResult. Fonction pure, testée sans transport. */
export function classifyResponse(status: number, bodyText: string): ApiResult<unknown> {
  const parsed = readJson(bodyText);
  if (status >= 200 && status < 300) {
    if (!parsed.ok) return { kind: "error", status, reason: "REPONSE_ILLISIBLE" };
    return { kind: "ok", status, body: parsed.value };
  }
  const body = parsed.ok ? parsed.value : undefined;
  const reason = isRecord(body) && typeof body["reason"] === "string" ? body["reason"] : undefined;
  if ((status === 409 || status === 403) && reason !== undefined) {
    const missing = isRecord(body) ? body["missing"] : undefined;
    return typeof missing === "number" && Number.isFinite(missing)
      ? { kind: "refused", status, reason, missing }
      : { kind: "refused", status, reason };
  }
  return { kind: "error", status, reason: reason ?? `HTTP_${status}` };
}

// ---------------------------------------------------------------- le client

export interface CoreClientOptions {
  readonly transport: Transport;
  /** Adresse de silooo-core sans « / » final, par exemple http://silooo-core:8080. */
  readonly coreUrl: string;
  /** La clé du contrat. Une chaîne en test, un OpaqueSecret en service. */
  readonly apiKey: string | OpaqueSecret;
  readonly timeoutSeconds: number;
  /** Nouveaux essais d'une lecture qui échoue. Une écriture n'en a jamais. */
  readonly readRetries: number;
}

export interface CoreClient {
  /** `path` commence par /v1/ et porte sa requête : /v1/events?after=0&limit=100. */
  get(path: string): Promise<ApiResult<unknown>>;
  post(path: string, body: unknown): Promise<ApiResult<unknown>>;
  /** Une écriture, comme `post` : jamais rejouée par le client. */
  patch(path: string, body: unknown): Promise<ApiResult<unknown>>;
  /** Une écriture qui remplace, comme `post` : jamais rejouée par le client. */
  put(path: string, body: unknown): Promise<ApiResult<unknown>>;
  /** Une écriture sans corps : ce qu'il faut dire au service est dans la requête du chemin. Jamais rejouée par le client. */
  delete(path: string): Promise<ApiResult<unknown>>;
}

/** Un échec qui vaut un nouvel essai : réseau coupé ou panne du service, pas une requête refusée. */
function isRetryable(result: ApiResult<unknown>): boolean {
  if (result.kind === "network") return true;
  return result.kind === "error" && result.status >= 500 && result.status !== 501;
}

export function createCoreClient(options: CoreClientOptions): CoreClient {
  const base = options.coreUrl.replace(/\/+$/, "");
  const headers: readonly Header[] = [
    { name: "Content-Type", value: "application/json" },
    { name: "X-Silooo-Key", value: options.apiKey },
  ];

  async function once(method: HttpMethod, path: string, body: string | undefined): Promise<ApiResult<unknown>> {
    try {
      const response = await options.transport.send(method, `${base}${path}`, headers, body, options.timeoutSeconds);
      return classifyResponse(response.status, response.body);
    } catch (error) {
      return { kind: "network", message: describeError(error) };
    }
  }

  return {
    async get(path) {
      let result = await once("GET", path, undefined);
      for (let retry = 0; retry < options.readRetries && isRetryable(result); retry++) {
        result = await once("GET", path, undefined);
      }
      return result;
    },
    post(path, body) {
      return once("POST", path, JSON.stringify(body));
    },
    patch(path, body) {
      return once("PATCH", path, JSON.stringify(body));
    },
    put(path, body) {
      return once("PUT", path, JSON.stringify(body));
    },
    delete(path) {
      return once("DELETE", path, undefined);
    },
  };
}
