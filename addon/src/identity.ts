// Ce qui tient lieu de XUID pour un joueur. C'est le seul endroit du code qui le décide.
//
// Point ouvert, à trancher par la direction : le contrat (OpenAPI, schéma Xuid) attend un XUID, des
// chiffres seulement. La Script API 2.11.0 n'expose pas le XUID : ni `Player` ni les événements
// playerSpawn, playerJoin et playerLeave n'ont de propriété xuid. Elle donne `Player.id`, un
// identifiant opaque, « constant d'un chargement à l'autre du monde », à ne pas interpréter (types
// de @minecraft/server, Entity.id), et `playerId` dans playerLeave, qui porte la même valeur.
// Tant que le XUID n'a pas d'autre source, c'est cet identifiant qui part dans les corps de
// requête. Si son format ne passe pas le schéma Xuid, silooo-core répond 400 REQUETE_INVALIDE et
// l'add-on le journalise : rien ne plante, mais rien ne se joue non plus.

import type { Xuid } from "./protocol";

export function playerXuid(player: { readonly id: string }): Xuid {
  return player.id;
}

export function leavingXuid(event: { readonly playerId: string }): Xuid {
  return event.playerId;
}
