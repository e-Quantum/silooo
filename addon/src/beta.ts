// Le seul fichier qui importe @minecraft/server-net et @minecraft/server-admin, les deux modules bêta
// de Bedrock 26.60. Ils renomment ou déplacent leurs API d'une version à l'autre : tout appel bêta de
// l'add-on est ici, pour être retesté d'un coup (docs/bedrock.md, « Ce qui casse à chaque version »).
//
// Appels bêta de ce fichier :
//   @minecraft/server-net   : http.request, HttpRequest (uri, method, timeout, headers, body),
//                             HttpHeader, HttpRequestMethod, HttpResponse (status, body)
//   @minecraft/server-admin : secrets.get, variables.get, SecretString (type seulement)

import { secrets, variables, type SecretString } from "@minecraft/server-admin";
import { http, HttpHeader, HttpRequest, HttpRequestMethod } from "@minecraft/server-net";
import { DEFAULT_SERVER_ID, SETTING_NAMES } from "./config";
import type { Header, OpaqueSecret, Transport, TransportResponse } from "./http";
import { describeError } from "./runtime";

export type { Transport } from "./http";

// ---------------------------------------------------------------- le transport

/** Un SecretString traverse le code de l'add-on sous le type opaque : personne n'y lit rien. */
function hideSecret(secret: SecretString): OpaqueSecret {
  return secret as unknown as OpaqueSecret;
}

function nativeValue(value: Header["value"]): string | SecretString {
  return typeof value === "string" ? value : (value as unknown as SecretString);
}

export function createBetaTransport(): Transport {
  return {
    async send(method, url, headers, body, timeoutSeconds): Promise<TransportResponse> {
      const request = new HttpRequest(url);
      request.method = method === "GET" ? HttpRequestMethod.Get : HttpRequestMethod.Post;
      request.timeout = timeoutSeconds;
      request.headers = headers.map((header) => new HttpHeader(header.name, nativeValue(header.value)));
      if (body !== undefined) request.body = body;
      const response = await http.request(request);
      return { status: response.status, body: response.body };
    },
  };
}

// ---------------------------------------------------------------- les réglages posés par le déploiement

export interface ServerSettings {
  /** Adresse de silooo-core sans « / » final. */
  readonly coreUrl: string;
  readonly serverId: string;
  readonly apiKey: OpaqueSecret;
}

export type SettingsResult =
  | { readonly ok: true; readonly settings: ServerSettings }
  | { readonly ok: false; readonly problems: readonly string[] };

/**
 * Lit le secret silooo_key (secrets.json) et les variables core_url et server_id (variables.json).
 * Rend la liste de ce qui manque au lieu de lever : l'add-on reste alors inerte, sans planter le serveur.
 * Aucune valeur n'est retournée dans un message : ni la clé, qui est opaque, ni l'adresse.
 */
export function readSettings(): SettingsResult {
  const problems: string[] = [];
  let apiKey: OpaqueSecret | undefined;
  let coreUrl: string | undefined;
  let serverId: string = DEFAULT_SERVER_ID;

  try {
    const secret = secrets.get(SETTING_NAMES.secret);
    if (secret === undefined) problems.push(`secret ${SETTING_NAMES.secret} absent de secrets.json`);
    else apiKey = hideSecret(secret);
  } catch (error) {
    problems.push(`secret ${SETTING_NAMES.secret} illisible (${describeError(error)})`);
  }

  try {
    const url = variables.get(SETTING_NAMES.coreUrl);
    if (typeof url !== "string" || !/^https?:\/\/[^/\s]+/.test(url.trim())) {
      problems.push(`variable ${SETTING_NAMES.coreUrl} absente de variables.json ou sans adresse http(s)`);
    } else {
      coreUrl = url.trim().replace(/\/+$/, "");
    }
    const id = variables.get(SETTING_NAMES.serverId);
    if (typeof id === "string" && id.trim() !== "") serverId = id.trim();
  } catch (error) {
    problems.push(`variables illisibles (${describeError(error)})`);
  }

  if (problems.length > 0 || apiKey === undefined || coreUrl === undefined) {
    return { ok: false, problems };
  }
  return { ok: true, settings: { coreUrl, serverId, apiKey } };
}
