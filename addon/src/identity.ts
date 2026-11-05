// L'identifiant d'un joueur pour silooo-core : ce qui part dans le champ `xuid` des corps de requête.
// C'est le seul fichier du code qui le décide.
//
// La Script API 2.11.0 n'expose pas le XUID : ni `Player`, ni playerSpawn, playerJoin ou playerLeave
// n'en ont. Elle donne `Player.id`, un identifiant opaque et propre au monde chargé (« do not parse or
// interpret it », types de @minecraft/server, Entity.id) : un monde recréé pendant l'alpha changerait
// tous les joueurs d'identité. Le gamertag, lui, survit au monde, et c'est par lui que le site SileneMC
// connaît les joueurs (phase 5).
//
// Décision provisoire (docs/passation.md, décision 25) : le `xuid` envoyé est une empreinte FNV-1a sur
// 64 bits du gamertag, en minuscules et sans espaces autour, écrite en décimal. Elle passe le schéma
// `Xuid` du contrat (`^[0-9]{1,20}$`) et la colonne `BIGINT UNSIGNED` de `players.xuid`, sans rien
// changer au service. Quand une source de vrai XUID existera, c'est ici, et seulement ici, qu'on la
// branche ; les joueurs se retrouveront par leur gamertag au `join` suivant.
//
// FNV-1a est calculée avec BigInt sur les unités UTF-16 du gamertag (deux octets par unité, octet bas
// d'abord) : aucune dépendance, pas de TextEncoder, et le même résultat partout.

import type { Xuid } from "./protocol";

const FNV_OFFSET_BASIS = 14695981039346656037n;
const FNV_PRIME = 1099511628211n;
const MASK_64 = (1n << 64n) - 1n;

/** L'identifiant provisoire d'un joueur, dérivé de son gamertag. Même gamertag, même identifiant. */
export function provisionalXuid(gamertag: string): Xuid {
  const normalized = gamertag.trim().toLowerCase();
  let hash = FNV_OFFSET_BASIS;
  for (let index = 0; index < normalized.length; index += 1) {
    const unit = normalized.charCodeAt(index);
    for (const byte of [unit & 0xff, unit >>> 8]) {
      hash ^= BigInt(byte);
      hash = (hash * FNV_PRIME) & MASK_64;
    }
  }
  return hash.toString(10);
}

export function playerXuid(player: { readonly name: string }): Xuid {
  return provisionalXuid(player.name);
}

export function leavingXuid(event: { readonly playerName: string }): Xuid {
  return provisionalXuid(event.playerName);
}
