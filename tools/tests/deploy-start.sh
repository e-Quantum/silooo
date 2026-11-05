#!/usr/bin/env bash
# Teste deploy/bds/start.sh sans Docker : l'option --rendre-seulement écrit les fichiers dans un dossier jetable, et le
# zip de BDS (décision 26) est cherché, contrôlé et décompressé dans un dossier jetable avec un faux zip (faire-zip.mjs)
# et un faux curl : zip présent avec la bonne somme, mauvaise somme refusée, zip absent et téléchargement impossible.
# Lancé par tools/tests/run.sh ; utilisable seul : bash tools/tests/deploy-start.sh
# Besoin : bash 4+, node (pour lire le JSON) et jq (que start.sh lit aussi dans l'image). Sans jq, les cas qui lisent
# manifest.json sont ignorés sur un poste local, mais ils échouent dans la CI, où CI=true et où jq est installé.
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START="$RACINE/deploy/bds/start.sh"
GABARIT="$RACINE/deploy/bds/server.properties.template"

REUSSIS=0
ECHECS=0
IGNORES=0
ok() { REUSSIS=$((REUSSIS + 1)); printf '  ok     %s\n' "$1"; }
ko() {
  ECHECS=$((ECHECS + 1))
  printf '  ECHEC  %s\n' "$1"
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/           /'; fi
}
ignore() { IGNORES=$((IGNORES + 1)); printf '  IGNORÉ %s\n' "$1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# Aucune variable de l'environnement de l'appelant ne doit changer le résultat.
for nom in $(compgen -v | grep -E '^(BDS_|SILOOO_)' || true); do unset "$nom"; done

# lancer <dossier> [VAR=valeur ...] : lance start.sh ; la sortie (stdout et stderr) va dans $SORTIE, le code dans $CODE.
lancer() {
  local dossier=$1
  shift
  SORTIE="$(env "$@" bash "$START" --rendre-seulement "$dossier" 2>&1)"
  CODE=$?
}

# lire_json <fichier> <expression sur d> : valeur de l'expression JavaScript, ERREUR:... si le JSON est invalide.
lire_json() {
  node -e '
    const fs = require("node:fs");
    try {
      const d = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      const v = eval(process.argv[2]);
      process.stdout.write(typeof v === "string" ? v : JSON.stringify(v));
    } catch (e) { process.stdout.write("ERREUR:" + e.message); }
  ' "$(natif "$1")" "$2"
}

# propriete <dossier> <clé> : la valeur de la clé dans server.properties.
propriete() { grep -E "^$2=" "$1/server.properties" | head -n 1 | cut -d= -f2-; }

# Un manifeste de pack : l'uuid de l'en-tête et celui du module script sont différents exprès.
UUID_PACK="11111111-1111-4111-8111-111111111111"
UUID_MODULE="22222222-2222-4222-8222-222222222222"
poser_pack() { # poser_pack <dossier de BDS>
  mkdir -p "$1/behavior_packs/silooo/scripts"
  cat >"$1/behavior_packs/silooo/manifest.json" <<JSON
{
  "format_version": 2,
  "header": { "name": "Silooo", "description": "pack de test", "uuid": "$UUID_PACK", "version": [1, 2, 3], "min_engine_version": [1, 21, 0] },
  "modules": [
    { "type": "data", "uuid": "33333333-3333-4333-8333-333333333333", "version": [1, 0, 0] },
    { "type": "script", "language": "javascript", "uuid": "$UUID_MODULE", "version": [1, 2, 3], "entry": "scripts/main.js" }
  ],
  "dependencies": [ { "module_name": "@minecraft/server", "version": "2.0.0-beta" } ]
}
JSON
  : >"$1/behavior_packs/silooo/scripts/main.js"
}

# Un manifeste de pack de ressources : l'uuid de l'en-tête et celui du module sont différents exprès (la liste du monde
# porte celui de l'en-tête).
UUID_RESSOURCES="44444444-4444-4444-8444-444444444444"
UUID_RESSOURCES_MODULE="55555555-5555-4555-8555-555555555555"
poser_ressources() { # poser_ressources <dossier du pack de ressources>
  mkdir -p "$1/textures/ui/silooo"
  cat >"$1/manifest.json" <<JSON
{
  "format_version": 2,
  "header": { "name": "pack.name", "description": "pack.description", "uuid": "$UUID_RESSOURCES", "version": [0, 1, 0], "min_engine_version": [1, 21, 0] },
  "modules": [ { "type": "resources", "uuid": "$UUID_RESSOURCES_MODULE", "version": [0, 1, 0] } ]
}
JSON
  printf 'png' >"$1/textures/ui/silooo/plein.png"
}

# ------------------------------------------------------------------ gabarit
echo "start.sh : le gabarit"
if grep -q '^transport=nethernet$' "$GABARIT" && grep -q '^content-log-file-enabled=true$' "$GABARIT"; then
  ok "le gabarit fixe transport=nethernet et content-log-file-enabled=true"
else
  ko "le gabarit doit fixer transport=nethernet et content-log-file-enabled=true"
fi

# ------------------------------------------------------------------ sans pack, réglages par défaut
echo "start.sh : sans pack, réglages par défaut"
D="$TMP/sans-pack"
lancer "$D"
if [ "$CODE" -eq 0 ]; then ok "sort en 0"; else ko "devait sortir en 0 (code $CODE)" "$SORTIE"; fi
for attendu in "server-name=Silooo" "level-name=silooo" "max-players=20" "transport=nethernet" "server-port=19142" \
  "server-udp-ports=19143-19174" "allow-list=true" "content-log-file-enabled=true" "online-mode=true" "texturepack-required=true"; do
  if grep -qxF -- "$attendu" "$D/server.properties"; then ok "server.properties contient $attendu"; else ko "server.properties devrait contenir $attendu" "$(cat "$D/server.properties" 2>&1)"; fi
done
if grep -q '{{' "$D/server.properties"; then ko "server.properties garde une marque non remplacée"; else ok "server.properties sans marque restante"; fi
if printf '%s\n' "$SORTIE" | grep -q "pas de pack"; then ok "dit qu'il démarre sans pack"; else ko "devait dire qu'il n'y a pas de pack" "$SORTIE"; fi
if printf '%s\n' "$SORTIE" | grep -q "pas de pack de ressources" && [ ! -e "$D/resource_packs" ] && [ ! -e "$D/worlds/silooo/world_resource_packs.json" ]; then
  ok "sans pack de ressources : le dit, et n'écrit ni resource_packs/ ni world_resource_packs.json"
else
  ko "sans pack de ressources : devait le dire et ne rien écrire" "$SORTIE"
fi
if [ ! -e "$D/config/$UUID_MODULE" ] && [ -z "$(find "$D/config" -name secrets.json 2>/dev/null)" ]; then ok "aucun secrets.json sans pack"; else ko "pas de secrets.json attendu sans pack"; fi
if [ "$(lire_json "$D/config/default/permissions.json" 'd.allowed_modules.includes("@minecraft/server-admin")')" = true ]; then
  ok "config/default/permissions.json permet server-admin"
else
  ko "config/default/permissions.json devrait permettre server-admin" "$(cat "$D/config/default/permissions.json" 2>&1)"
fi
if [ "$(lire_json "$D/config/default/permissions.json" 'd.allowed_modules.includes("@minecraft/server-net")')" = false ]; then
  ok "server-net n'est pas permis par défaut"
else
  ko "server-net ne doit être permis que pour le pack Silooo"
fi
if [ "$(cat "$D/allowlist.json" 2>/dev/null)" = "[]" ] && [ "$(cat "$D/permissions.json" 2>/dev/null)" = "[]" ]; then
  ok "allowlist.json et permissions.json créés vides"
else
  ko "allowlist.json et permissions.json devaient être créés avec []"
fi
if printf '%s\n' "$SORTIE" | grep -q "BDS_PUBLIC_IP est vide"; then ok "avertit quand BDS_PUBLIC_IP est vide"; else ko "devait avertir que BDS_PUBLIC_IP est vide" "$SORTIE"; fi

# ------------------------------------------------------------------ réglages
echo "start.sh : plages UDP, IP publique, valeurs spéciales"
D="$TMP/ip"
lancer "$D" BDS_PUBLIC_IP=203.0.113.10 BDS_UDP_PORTS=19143-19174 BDS_UDP_PORTS_PUBLIES=29143-29174 BDS_PORT=19200 BDS_MAX_PLAYERS=8
if [ "$CODE" -eq 0 ] && [ "$(propriete "$D" server-udp-ports)" = "203.0.113.10:29143-29174:19143-19174" ] &&
  [ "$(propriete "$D" server-port)" = 19200 ] && [ "$(propriete "$D" max-players)" = 8 ]; then
  ok "IP publique : server-udp-ports=IP:public:local, port et joueurs repris"
else
  ko "IP publique : server-udp-ports attendu 203.0.113.10:29143-29174:19143-19174" "$SORTIE"
fi
if ! printf '%s\n' "$SORTIE" | grep -q "BDS_PUBLIC_IP est vide"; then ok "pas d'avertissement quand l'IP publique est posée"; else ko "pas d'avertissement attendu"; fi

D="$TMP/ip-memes-ports"
lancer "$D" BDS_PUBLIC_IP=203.0.113.10
if [ "$(propriete "$D" server-udp-ports)" = "203.0.113.10:19143-19174:19143-19174" ]; then
  ok "IP publique et plages égales : les deux ports sont écrits"
else
  ko "avec une IP, public:local est obligatoire même égal" "$(propriete "$D" server-udp-ports)"
fi

D="$TMP/plages-differentes"
lancer "$D" BDS_UDP_PORTS=19143-19174 BDS_UDP_PORTS_PUBLIES=29143-29174
if [ "$(propriete "$D" server-udp-ports)" = "29143-29174:19143-19174" ]; then ok "sans IP, plages différentes : public:local"; else ko "attendu 29143-29174:19143-19174" "$(propriete "$D" server-udp-ports)"; fi

D="$TMP/nom-special"
# shellcheck disable=SC2016 # les $ et les antislash de ces chaînes doivent rester littéraux
lancer "$D" 'BDS_SERVER_NAME=Silooo & Fils \1 $HOME' 'BDS_LEVEL_NAME=mon monde'
# shellcheck disable=SC2016
if [ "$CODE" -eq 0 ] && grep -qxF 'server-name=Silooo & Fils \1 $HOME' "$D/server.properties" && grep -qxF 'level-name=mon monde' "$D/server.properties"; then
  ok "&, antislash et \$ du nom sont écrits tels quels"
else
  ko "le nom du serveur doit être écrit sans interprétation" "$(grep -E '^(server|level)-name=' "$D/server.properties" 2>&1)"
fi

D="$TMP/variables-vides"
lancer "$D" BDS_SERVER_NAME= BDS_PORT= BDS_MAX_PLAYERS= BDS_LEVEL_NAME= BDS_ALLOW_LIST= SILOOO_CORE_URL=
if [ "$CODE" -eq 0 ] && [ "$(propriete "$D" server-name)" = Silooo ] && [ "$(propriete "$D" server-port)" = 19142 ]; then
  ok "une variable vide vaut une variable absente (le compose passe les variables vides)"
else
  ko "les variables vides doivent prendre le défaut" "$SORTIE"
fi

# ------------------------------------------------------------------ refus
echo "start.sh : valeurs refusées"
NUMERO_REFUS=0
refus() { # refus <code attendu> <description> [VAR=valeur ...]
  local attendu=$1 description=$2
  shift 2
  NUMERO_REFUS=$((NUMERO_REFUS + 1))
  lancer "$TMP/refus-$NUMERO_REFUS" "$@"
  if [ "$CODE" -ne "$attendu" ]; then
    ko "$description : code $CODE au lieu de $attendu" "$SORTIE"
  elif [ -e "$TMP/refus-$NUMERO_REFUS/server.properties" ]; then
    ko "$description : un refus ne devait rien écrire"
  else
    ok "$description : code $attendu, rien d'écrit"
  fi
}
refus 78 "BDS_PORT=abc" BDS_PORT=abc
refus 78 "BDS_PORT=70000" BDS_PORT=70000
refus 78 "BDS_MAX_PLAYERS=0" BDS_MAX_PLAYERS=0
refus 78 "plage UDP à l'envers" BDS_UDP_PORTS=19174-19143
refus 78 "plages de tailles différentes" BDS_UDP_PORTS=19143-19174 BDS_UDP_PORTS_PUBLIES=29143-29150
refus 78 "BDS_PUBLIC_IP n'est pas une adresse" BDS_PUBLIC_IP=mon-vps.example
refus 78 "nom de serveur avec point-virgule" 'BDS_SERVER_NAME=Silooo;Test'
refus 78 "nom de monde avec une barre" BDS_LEVEL_NAME=../autre
refus 78 "nom de monde qui commence par un point" BDS_LEVEL_NAME=.cache
refus 78 "BDS_ALLOW_LIST=peut-etre" BDS_ALLOW_LIST=peut-etre
refus 78 "SILOOO_CORE_URL sans schéma" SILOOO_CORE_URL=silooo-core:8080
refus 78 "retour à la ligne dans le nom du serveur" "BDS_SERVER_NAME=$(printf 'a\nb')"
SORTIE="$(bash "$START" --inconnue 2>&1)"
CODE=$?
if [ "$CODE" -eq 64 ]; then ok "option inconnue : code 64"; else ko "option inconnue : code $CODE au lieu de 64" "$SORTIE"; fi

# ------------------------------------------------------------------ avec pack
echo "start.sh : avec le pack Silooo"
if ! command -v jq >/dev/null 2>&1; then
  if [ "${CI:-}" = true ]; then
    ko "jq est introuvable dans la CI : les cas du pack ne peuvent pas tourner"
  else
    ignore "jq absent de ce poste : cas du pack ignorés (la CI les lance)"
  fi
else
  # shellcheck disable=SC2016
  CLE='cle"a\b&c $d e'
  D="$TMP/avec-pack"
  poser_pack "$D"
  lancer "$D" SILOOO_API_KEY="$CLE" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 0 ]; then ok "sort en 0"; else ko "devait sortir en 0 (code $CODE)" "$SORTIE"; fi
  CFG="$D/config/$UUID_MODULE"
  if [ -d "$CFG" ] && [ ! -e "$D/config/$UUID_PACK" ]; then ok "le dossier de config porte l'uuid du module script, pas celui de l'en-tête"; else ko "config/<uuid du module script> attendu" "$(ls "$D/config")"; fi
  if [ "$(lire_json "$CFG/permissions.json" 'd.allowed_modules.includes("@minecraft/server-net") && d.allowed_modules.includes("@minecraft/server-admin")')" = true ]; then
    ok "permissions.json permet server-net et server-admin"
  else
    ko "permissions.json devrait permettre server-net et server-admin" "$(cat "$CFG/permissions.json" 2>&1)"
  fi
  if [ "$(lire_json "$CFG/permissions.json" 'd.module_permissions["@minecraft/server-net"].allowed_uris')" = '["http://silooo-core:8080/"]' ]; then
    ok "allowed_uris vise silooo-core seulement, sans force_tls"
  else
    ko "allowed_uris attendu [\"http://silooo-core:8080/\"]" "$(cat "$CFG/permissions.json" 2>&1)"
  fi
  if grep -q force_tls "$CFG/permissions.json"; then ko "force_tls ne doit pas être écrit (HTTP sur le réseau Docker)"; else ok "pas de force_tls"; fi
  if [ "$(lire_json "$CFG/variables.json" 'd.core_url')" = "http://silooo-core:8080" ]; then ok "variables.json : core_url sans barre finale"; else ko "core_url attendu http://silooo-core:8080" "$(cat "$CFG/variables.json" 2>&1)"; fi
  if [ "$(lire_json "$CFG/secrets.json" 'd.silooo_key')" = "$CLE" ]; then ok "secrets.json : la clé avec guillemet, antislash, & et \$ est écrite telle quelle"; else ko "secrets.json devrait contenir la clé exacte" "(valeur non affichée)"; fi
  if [ "$(lire_json "$D/worlds/monde-test/world_behavior_packs.json" 'd[0].pack_id')" = "$UUID_PACK" ] &&
    [ "$(lire_json "$D/worlds/monde-test/world_behavior_packs.json" 'd[0].version')" = "[1,2,3]" ]; then
    ok "world_behavior_packs.json liste le pack (uuid et version de l'en-tête)"
  else
    ko "world_behavior_packs.json devrait lister le pack" "$(cat "$D/worlds/monde-test/world_behavior_packs.json" 2>&1)"
  fi
  if printf '%s' "$SORTIE" | grep -qF 'cle"a' || printf '%s' "$SORTIE" | grep -qF 'a\b&c'; then ko "la clé apparaît dans la sortie de start.sh"; else ok "la clé n'apparaît pas dans la sortie"; fi
  fichiers_avec_cle="$(grep -rlF 'cle\"a' "$D" 2>/dev/null | sed "s#^$D/##" | sort | tr '\n' ' ')"
  if [ "$fichiers_avec_cle" = "config/$UUID_MODULE/secrets.json " ]; then ok "la clé n'est écrite que dans secrets.json"; else ko "la clé ne doit être que dans secrets.json" "$fichiers_avec_cle"; fi

  # Le mode 600 n'a de sens que si le système de fichiers l'applique (pas le cas sous Windows).
  : >"$TMP/sonde" && chmod 600 "$TMP/sonde"
  if [ "$(stat -c %a "$TMP/sonde" 2>/dev/null)" = 600 ]; then
    if [ "$(stat -c %a "$CFG/secrets.json")" = 600 ] && [ "$(stat -c %a "$CFG/variables.json")" = 600 ] && [ "$(stat -c %a "$CFG")" = 700 ]; then
      ok "secrets.json et variables.json en mode 600, dossier en 700"
    else
      ko "modes attendus 600, 600 et 700" "$(stat -c '%a %n' "$CFG/secrets.json" "$CFG/variables.json" "$CFG")"
    fi
  else
    ignore "ce système de fichiers ignore chmod : modes 600 non vérifiés ici"
  fi

  # Relancer ne duplique pas l'entrée, et garde les autres packs du monde.
  printf '[ {"pack_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","version":[1,0,0]}, {"pack_id":"%s","version":[0,0,1]} ]\n' "$UUID_PACK" >"$D/worlds/monde-test/world_behavior_packs.json"
  lancer "$D" SILOOO_API_KEY="$CLE" BDS_LEVEL_NAME=monde-test
  lancer "$D" SILOOO_API_KEY="$CLE" BDS_LEVEL_NAME=monde-test
  LISTE="$D/worlds/monde-test/world_behavior_packs.json"
  if [ "$CODE" -eq 0 ] && [ "$(lire_json "$LISTE" 'd.length')" = 2 ] &&
    [ "$(lire_json "$LISTE" 'd.filter(p => p.pack_id === "'"$UUID_PACK"'").map(p => p.version)')" = "[[1,2,3]]" ] &&
    [ "$(lire_json "$LISTE" 'd[0].pack_id')" = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" ]; then
    ok "une liste existante garde ses autres packs, l'entrée Silooo est mise à jour sans doublon"
  else
    ko "la liste existante devait garder l'autre pack et mettre le nôtre à [1,2,3]" "$(cat "$LISTE" 2>&1)"
  fi

  # Une liste illisible n'est pas écrasée.
  printf 'pas du json' >"$LISTE"
  lancer "$D" SILOOO_API_KEY="$CLE" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 65 ] && [ "$(cat "$LISTE")" = "pas du json" ]; then ok "une liste de packs illisible : code 65 et fichier intact"; else ko "liste illisible : code 65 et fichier intact attendus (code $CODE)" "$SORTIE"; fi

  # Autre URL de silooo-core, avec ou sans barre finale.
  D="$TMP/autre-url"
  poser_pack "$D"
  lancer "$D" SILOOO_API_KEY=x SILOOO_CORE_URL=http://core.interne:9000
  if [ "$(lire_json "$D/config/$UUID_MODULE/variables.json" 'd.core_url')" = "http://core.interne:9000" ] &&
    [ "$(lire_json "$D/config/$UUID_MODULE/permissions.json" 'd.module_permissions["@minecraft/server-net"].allowed_uris[0]')" = "http://core.interne:9000/" ]; then
    ok "SILOOO_CORE_URL sans barre : core_url sans barre, allowed_uris avec une barre"
  else
    ko "SILOOO_CORE_URL=http://core.interne:9000 mal repris" "$(cat "$D/config/$UUID_MODULE/variables.json" "$D/config/$UUID_MODULE/permissions.json" 2>&1)"
  fi

  # Sans clé : refus net, et aucun secrets.json.
  D="$TMP/sans-cle"
  poser_pack "$D"
  lancer "$D"
  if [ "$CODE" -eq 78 ] && [ ! -e "$D/config/$UUID_MODULE/secrets.json" ]; then ok "pack présent sans SILOOO_API_KEY : code 78, pas de secrets.json"; else ko "sans clé : code 78 et pas de secrets.json attendus (code $CODE)" "$SORTIE"; fi

  # Manifeste sans module script.
  D="$TMP/sans-module"
  poser_pack "$D"
  printf '{"format_version":2,"header":{"uuid":"%s","version":[1,0,0]},"modules":[{"type":"data","uuid":"33333333-3333-4333-8333-333333333333","version":[1,0,0]}]}\n' "$UUID_PACK" >"$D/behavior_packs/silooo/manifest.json"
  lancer "$D" SILOOO_API_KEY=x
  if [ "$CODE" -eq 65 ]; then ok "manifeste sans module script : code 65"; else ko "manifeste sans module script : code 65 attendu (code $CODE)" "$SORTIE"; fi

  # Manifeste illisible.
  D="$TMP/manifeste-casse"
  poser_pack "$D"
  printf '{ pas du json' >"$D/behavior_packs/silooo/manifest.json"
  lancer "$D" SILOOO_API_KEY=x
  if [ "$CODE" -eq 65 ]; then ok "manifeste illisible : code 65"; else ko "manifeste illisible : code 65 attendu (code $CODE)" "$SORTIE"; fi

  # Avertissement quand le monde n'existe pas (pas de level.dat, donc pas de Beta APIs).
  D="$TMP/monde-neuf"
  poser_pack "$D"
  lancer "$D" SILOOO_API_KEY=x
  if printf '%s\n' "$SORTIE" | grep -q "Beta APIs"; then ok "avertit qu'un monde sans level.dat n'a pas « Beta APIs »"; else ko "devait avertir au sujet de « Beta APIs »" "$SORTIE"; fi
  : >"$D/worlds/silooo/level.dat"
  lancer "$D" SILOOO_API_KEY=x
  if printf '%s\n' "$SORTIE" | grep -q "Beta APIs"; then ko "pas d'avertissement attendu quand level.dat existe"; else ok "pas d'avertissement quand level.dat existe"; fi

  # ---- Le pack de ressources : copié dans resource_packs/silooo, listé dans world_resource_packs.json (uuid et version
  # de l'en-tête), sans clé ni configuration de scripts.
  echo "start.sh : avec le pack de ressources Silooo"
  poser_ressources "$TMP/ressources-source"
  D="$TMP/avec-ressources"
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 0 ] && [ -f "$D/resource_packs/silooo/manifest.json" ] && [ -f "$D/resource_packs/silooo/textures/ui/silooo/plein.png" ]; then
    ok "le pack de ressources est copié dans resource_packs/silooo (sans pack de comportement, sans clé)"
  else
    ko "le pack de ressources devait être copié dans resource_packs/silooo (code $CODE)" "$SORTIE"
  fi
  LISTE_RES="$D/worlds/monde-test/world_resource_packs.json"
  if [ "$(lire_json "$LISTE_RES" 'd[0].pack_id')" = "$UUID_RESSOURCES" ] && [ "$(lire_json "$LISTE_RES" 'd[0].version')" = "[0,1,0]" ] &&
    [ "$(lire_json "$LISTE_RES" 'd.length')" = 1 ]; then
    ok "world_resource_packs.json liste le pack (uuid et version de l'en-tête, pas celui du module)"
  else
    ko "world_resource_packs.json devrait lister le pack de ressources" "$(cat "$LISTE_RES" 2>&1)"
  fi
  if [ ! -e "$D/worlds/monde-test/world_behavior_packs.json" ] && [ -z "$(find "$D/config" -name secrets.json 2>/dev/null)" ]; then
    ok "le pack de ressources n'écrit ni liste de packs de comportement ni secrets.json"
  else
    ko "le pack de ressources ne devait écrire ni world_behavior_packs.json ni secrets.json"
  fi
  if printf '%s\n' "$SORTIE" | grep -q "pack de ressources Silooo $UUID_RESSOURCES"; then ok "dit que le pack de ressources est actif"; else ko "devait dire que le pack de ressources est actif" "$SORTIE"; fi

  # Les deux packs ensemble : chaque liste ne porte que son pack.
  D="$TMP/deux-packs"
  poser_pack "$D"
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" SILOOO_API_KEY=x BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 0 ] && [ "$(lire_json "$D/worlds/monde-test/world_behavior_packs.json" 'd.map(p => p.pack_id)')" = "[\"$UUID_PACK\"]" ] &&
    [ "$(lire_json "$D/worlds/monde-test/world_resource_packs.json" 'd.map(p => p.pack_id)')" = "[\"$UUID_RESSOURCES\"]" ]; then
    ok "deux packs : chaque liste du monde ne porte que son pack"
  else
    ko "deux packs : world_behavior_packs.json et world_resource_packs.json attendus, chacun avec son pack (code $CODE)" "$SORTIE"
  fi

  # Au démarrage suivant, le pack de l'image remplace celui du dossier de BDS.
  D="$TMP/avec-ressources"
  : >"$D/resource_packs/silooo/ancien.png"
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 0 ] && [ ! -e "$D/resource_packs/silooo/ancien.png" ] && [ -f "$D/resource_packs/silooo/manifest.json" ]; then
    ok "au démarrage suivant, le pack de ressources est remplacé par celui de l'image"
  else
    ko "le pack de ressources devait être remplacé à chaque démarrage (code $CODE)" "$SORTIE"
  fi

  # Une liste existante garde ses autres packs, sans doublon, et l'entrée de Silooo prend la version du manifeste.
  printf '[ {"pack_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","version":[1,0,0]}, {"pack_id":"%s","version":[0,0,1]} ]\n' "$UUID_RESSOURCES" >"$LISTE_RES"
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" BDS_LEVEL_NAME=monde-test
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 0 ] && [ "$(lire_json "$LISTE_RES" 'd.length')" = 2 ] &&
    [ "$(lire_json "$LISTE_RES" 'd.filter(p => p.pack_id === "'"$UUID_RESSOURCES"'").map(p => p.version)')" = "[[0,1,0]]" ] &&
    [ "$(lire_json "$LISTE_RES" 'd[0].pack_id')" = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" ]; then
    ok "une liste de ressources existante garde ses autres packs, l'entrée Silooo est mise à jour sans doublon"
  else
    ko "la liste de ressources devait garder l'autre pack et mettre le nôtre à [0,1,0]" "$(cat "$LISTE_RES" 2>&1)"
  fi

  # Une liste illisible n'est pas écrasée.
  printf 'pas du json' >"$LISTE_RES"
  lancer "$D" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-source" BDS_LEVEL_NAME=monde-test
  if [ "$CODE" -eq 65 ] && [ "$(cat "$LISTE_RES")" = "pas du json" ]; then ok "une liste de ressources illisible : code 65 et fichier intact"; else ko "liste de ressources illisible : code 65 et fichier intact attendus (code $CODE)" "$SORTIE"; fi

  # Un manifeste de ressources illisible, ou dont l'uuid n'en est pas un, arrête le démarrage.
  poser_ressources "$TMP/ressources-cassees"
  printf '{ pas du json' >"$TMP/ressources-cassees/manifest.json"
  lancer "$TMP/ressources-cassees-bds" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-cassees"
  if [ "$CODE" -eq 65 ]; then ok "manifeste de ressources illisible : code 65"; else ko "manifeste de ressources illisible : code 65 attendu (code $CODE)" "$SORTIE"; fi
  poser_ressources "$TMP/ressources-uuid"
  sed -i "s/$UUID_RESSOURCES/pas-un-uuid/" "$TMP/ressources-uuid/manifest.json"
  lancer "$TMP/ressources-uuid-bds" SILOOO_RESOURCE_PACK_DIR="$TMP/ressources-uuid"
  if [ "$CODE" -eq 65 ]; then ok "manifeste de ressources dont l'uuid n'en est pas un : code 65"; else ko "uuid de ressources invalide : code 65 attendu (code $CODE)" "$SORTIE"; fi
fi

# ------------------------------------------------------------------ le zip de BDS
# BDS n'est pas dans l'image (décision 26) : Kr pose le zip dans le dossier monté sur /data/bds, ou start.sh tente un
# téléchargement. Ici, un faux zip construit par le test (faire-zip.mjs), un faux curl, et des sommes écrites pour lui.
echo "start.sh : le zip de BDS, posé par Kr ou téléchargé"
V=9.9.9.9
NOM_ZIP="bedrock-server-$V.zip"
ZEROS=0000000000000000000000000000000000000000000000000000000000000000
FAUX="$TMP/faux"
JOURNAL="$TMP/curl.journal"
mkdir -p "$FAUX" "$TMP/zips"
cat >"$FAUX/curl" <<'CURL'
#!/usr/bin/env bash
# Faux curl : note ses arguments, puis agit selon FAUX_CURL (zip : sert FAUX_ZIP ; interdit : ne devait pas être appelé ;
# autre : échoue comme un délai dépassé).
echo "$*" >>"${FAUX_JOURNAL:?}"
case "${FAUX_CURL:-echec}" in
  zip)
    sortie=""
    while [ $# -gt 0 ]; do
      if [ "$1" = --output ]; then sortie=$2; fi
      shift
    done
    cp "$FAUX_ZIP" "$sortie"
    ;;
  interdit)
    echo "curl ne devait pas être appelé" >&2
    exit 99
    ;;
  *)
    echo "curl: (28) Operation timed out after 60001 milliseconds with 0 bytes received" >&2
    exit 28
    ;;
esac
CURL
chmod +x "$FAUX/curl"

# Sous Git Bash (Windows), « ln -s » copie ou échoue sans cette option ; elle demande le mode développeur. Les liens
# vers les volumes sont l'objet de ces essais : sans liens symboliques, on ignore tout en local, et la CI échoue.
export MSYS=winsymlinks:nativestrict
LIENS=0
if ln -s "$TMP/cible-absente" "$TMP/sonde-lien" 2>/dev/null && [ -L "$TMP/sonde-lien" ]; then LIENS=1; fi
if [ "$LIENS" -eq 1 ] && command -v unzip >/dev/null 2>&1 && command -v sha256sum >/dev/null 2>&1 && command -v md5sum >/dev/null 2>&1; then
  ZIP_BON="$TMP/zips/bon/$NOM_ZIP"
  ZIP_CORROMPU="$TMP/zips/corrompu/$NOM_ZIP"
  ZIP_SANS_SERVEUR="$TMP/zips/sans-serveur/$NOM_ZIP"
  mkdir -p "$TMP/zips/bon" "$TMP/zips/corrompu" "$TMP/zips/sans-serveur"
  node "$(natif "$RACINE/tools/tests/faire-zip.mjs")" "$(natif "$ZIP_BON")"
  node "$(natif "$RACINE/tools/tests/faire-zip.mjs")" "$(natif "$ZIP_CORROMPU")" --variante corrompu
  node "$(natif "$RACINE/tools/tests/faire-zip.mjs")" "$(natif "$ZIP_SANS_SERVEUR")" --sans-serveur
  somme_sha() { sha256sum "$1" | cut -d' ' -f1; }
  somme_md5() { md5sum "$1" | cut -d' ' -f1; }
  SHA_BON="$(somme_sha "$ZIP_BON")"
  MD5_BON="$(somme_md5 "$ZIP_BON")"
  SHA_CORROMPU="$(somme_sha "$ZIP_CORROMPU")"

  # ecrire_sommes <dossier> <sha256> <md5> [nom du fichier] : les deux fichiers de sommes, avec leurs commentaires.
  ecrire_sommes() {
    local nom=${4:-$NOM_ZIP}
    mkdir -p "$1"
    printf '# commentaire\n\n%s  %s\n' "$2" "$nom" >"$1/bds.sha256"
    printf '# commentaire\n%s  %s\n' "$3" "$nom" >"$1/bds.md5"
  }
  # lancer_bds <racine> <dossier des sommes> [VAR=valeur ...] : start.sh sans l'option, avec le faux curl en tête du PATH.
  lancer_bds() {
    local racine=$1 sommes=$2
    shift 2
    : >"$JOURNAL"
    SORTIE="$(env PATH="$FAUX:$PATH" FAUX_JOURNAL="$JOURNAL" BDS_VERSION="$V" BDS_RACINE="$racine" BDS_DONNEES="$racine.donnees" \
      BDS_SOMMES_SHA256="$sommes/bds.sha256" BDS_SOMME_MD5="$sommes/bds.md5" "$@" bash "$START" 2>&1)"
    CODE=$?
  }
  appels_curl() { wc -l <"$JOURNAL" | tr -d ' '; }

  # --- A. Zip posé par Kr, SHA-256 épinglée et juste.
  R="$TMP/a/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/a/sommes" "$SHA_BON" "00000000000000000000000000000000"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/a/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 0 ] && printf '%s\n' "$SORTIE" | grep -q "serveur lancé : LD_LIBRARY_PATH=. dossier=$V\$"; then
    ok "zip posé et SHA-256 épinglée juste : décompressé, puis ./bedrock_server lancé dans <version>/ avec LD_LIBRARY_PATH=."
  else
    ko "start.sh devait décompresser le zip puis lancer ./bedrock_server dans $V/ (code $CODE)" "$SORTIE"
  fi
  if printf '%s\n' "$SORTIE" | grep -q "SHA-256 du zip conforme"; then ok "dit que la SHA-256 est conforme"; else ko "devait dire que la SHA-256 est conforme" "$SORTIE"; fi
  if [ "$(appels_curl)" -eq 0 ]; then ok "zip présent : aucun appel à curl"; else ko "curl ne devait pas être appelé quand le zip est là" "$(cat "$JOURNAL")"; fi
  if ! printf '%s\n' "$SORTIE" | grep -qi "md5"; then ok "SHA-256 épinglée : le MD5 (faux exprès) n'est pas consulté"; else ko "le MD5 ne sert pas quand la SHA-256 est épinglée" "$SORTIE"; fi
  VERSION_DIR="$R/$V"
  if [ -f "$VERSION_DIR/.silooo-extrait" ] && [ -f "$VERSION_DIR/libfausse.so" ] && [ -f "$R/$NOM_ZIP" ]; then
    ok "BDS est décompressé dans <racine>/<version>/ (marque .silooo-extrait), le zip reste en place"
  else
    ko "BDS devait être décompressé dans $VERSION_DIR" "$(ls -A "$VERSION_DIR" 2>&1)"
  fi
  if [ -x "$VERSION_DIR/bedrock_server" ]; then ok "bedrock_server est rendu exécutable"; else ko "bedrock_server devait être exécutable"; fi
  if grep -qx "server-name=Silooo" "$VERSION_DIR/server.properties" && ! grep -q "du zip" "$VERSION_DIR/server.properties"; then
    ok "le server.properties du zip est remplacé par celui du gabarit"
  else
    ko "server.properties devait venir du gabarit" "$(cat "$VERSION_DIR/server.properties" 2>&1)"
  fi
  if [ ! -e "$VERSION_DIR/worlds/Bedrock level" ] && [ ! -e "$VERSION_DIR/config/ancien.json" ]; then
    ok "les worlds/ et config/ du zip sont retirés"
  else
    ko "worlds/ et config/ du zip devaient être retirés" "$(find "$VERSION_DIR/worlds" "$VERSION_DIR/config" 2>&1 | head -n 10)"
  fi
  if [ "$(cat "$R.donnees/config/allowlist.json" 2>/dev/null)" = "[]" ] && [ "$(cat "$R.donnees/config/operateurs.json" 2>/dev/null)" = "[]" ]; then
    ok "allowlist.json et permissions.json du zip sont retirés : les listes vides sont créées dans le volume de config"
  else
    ko "les listes du zip ne devaient pas être gardées" "$(cat "$R.donnees/config/allowlist.json" "$R.donnees/config/operateurs.json" 2>&1)"
  fi
  # Les liens : worlds/ et config/ vers les volumes, allowlist.json et permissions.json vers des fichiers de config/.
  if [ "$(readlink "$VERSION_DIR/worlds")" = "$R.donnees/worlds" ] && [ "$(readlink "$VERSION_DIR/config")" = "$R.donnees/config" ] &&
    [ "$(readlink "$VERSION_DIR/allowlist.json")" = "$R.donnees/config/allowlist.json" ] &&
    [ "$(readlink "$VERSION_DIR/permissions.json")" = "$R.donnees/config/operateurs.json" ]; then
    ok "worlds/ et config/ sont des liens vers les volumes, allowlist.json et permissions.json vers config/"
  else
    ko "les liens vers les volumes sont faux" "$(find "$VERSION_DIR" -maxdepth 1 -type l -printf '%p -> %l\n' 2>&1)"
  fi

  # Deuxième démarrage : rien n'est décompressé de nouveau, et le zip n'est plus nécessaire.
  : >"$VERSION_DIR/temoin"
  rm -f "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/a/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 0 ] && [ -e "$VERSION_DIR/temoin" ] && printf '%s\n' "$SORTIE" | grep -q "déjà décompressé" &&
    printf '%s\n' "$SORTIE" | grep -q "serveur lancé" && [ "$(appels_curl)" -eq 0 ]; then
    ok "deuxième démarrage, zip supprimé : BDS n'est pas décompressé de nouveau, curl n'est pas appelé"
  else
    ko "le deuxième démarrage devait sauter la décompression (code $CODE)" "$SORTIE"
  fi

  # --- B. SHA-256 épinglée mais fausse : refus, rien décompressé, le zip de Kr reste.
  R="$TMP/b/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/b/sommes" "$SHA_CORROMPU" "$MD5_BON"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/b/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 65 ] && printf '%s\n' "$SORTIE" | grep -qF "$SHA_BON" && printf '%s\n' "$SORTIE" | grep -qF "$SHA_CORROMPU" &&
    [ ! -e "$R/$V" ] && [ ! -e "$R/$V.partiel" ] && [ -f "$R/$NOM_ZIP" ] && ! printf '%s\n' "$SORTIE" | grep -q "serveur lancé"; then
    ok "SHA-256 fausse : code 65, les deux sommes sont dans le message, rien n'est décompressé ni lancé, le zip posé reste"
  else
    ko "une SHA-256 fausse devait être refusée (code $CODE)" "$SORTIE"
  fi

  # --- C. SHA-256 pas épinglée (des zéros) : MD5 de Mojang, et la somme à épingler dans le journal.
  R="$TMP/c/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/c/sommes" "$ZEROS" "$MD5_BON"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/c/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 0 ] && printf '%s\n' "$SORTIE" | grep -q "MD5 du zip conforme" && printf '%s\n' "$SORTIE" | grep -qF "$SHA_BON  $NOM_ZIP" &&
    printf '%s\n' "$SORTIE" | grep -q "serveur lancé"; then
    ok "SHA-256 non épinglée : MD5 conforme, la somme SHA-256 à épingler est écrite dans le journal, le serveur démarre"
  else
    ko "sans SHA-256 épinglée, le MD5 devait suffire et la SHA-256 devait être journalisée (code $CODE)" "$SORTIE"
  fi
  R="$TMP/c2/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/c2/sommes" "$ZEROS" "$(somme_md5 "$ZIP_CORROMPU")"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/c2/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 65 ] && printf '%s\n' "$SORTIE" | grep -q "MD5" && [ ! -e "$R/$V" ] && ! printf '%s\n' "$SORTIE" | grep -q "serveur lancé"; then
    ok "SHA-256 non épinglée et MD5 faux : code 65, rien n'est décompressé"
  else
    ko "un MD5 faux devait être refusé (code $CODE)" "$SORTIE"
  fi

  # Les vrais fichiers du dépôt : bds.sha256 (encore des zéros) et bds.md5 sont lus par start.sh, au nom de la bonne version.
  VRAIE_VERSION="$(sed -n 's/^ARG BDS_VERSION=//p' "$RACINE/deploy/bds/Dockerfile")"
  R="$TMP/vrai/racine"
  mkdir -p "$R"
  cp "$ZIP_BON" "$R/bedrock-server-$VRAIE_VERSION.zip"
  : >"$JOURNAL"
  SORTIE="$(env PATH="$FAUX:$PATH" FAUX_JOURNAL="$JOURNAL" FAUX_CURL=interdit BDS_VERSION="$VRAIE_VERSION" BDS_RACINE="$R" BDS_DONNEES="$R.donnees" bash "$START" 2>&1)"
  CODE=$?
  if grep -qE '^0{64}  ' "$RACINE/deploy/bds/bds.sha256"; then
    if [ "$CODE" -eq 65 ] && printf '%s\n' "$SORTIE" | grep -q "MD5" && printf '%s\n' "$SORTIE" | grep -q "4a23794a882b008d3f0a5fde8b338f55"; then
      ok "les vrais bds.sha256 et bds.md5 sont lus (version $VRAIE_VERSION) : un autre zip est refusé sur le MD5 de Mojang"
    else
      ko "les vrais fichiers de sommes devaient refuser un faux zip sur le MD5 (code $CODE)" "$SORTIE"
    fi
  else
    if [ "$CODE" -eq 65 ] && printf '%s\n' "$SORTIE" | grep -q "SHA-256"; then
      ok "le vrai bds.sha256 est épinglé : un autre zip est refusé sur la SHA-256"
    else
      ko "la SHA-256 épinglée devait refuser un faux zip (code $CODE)" "$SORTIE"
    fi
  fi

  # --- D. Zip absent et téléchargement impossible : deux essais de 60 s, identité honnête, message pour Kr, sortie non nulle.
  R="$TMP/d/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/d/sommes" "$ZEROS" "$MD5_BON"
  lancer_bds "$R" "$TMP/d/sommes" FAUX_CURL=echec
  if [ "$CODE" -eq 66 ] && [ "$(appels_curl)" -eq 2 ]; then ok "zip absent, téléchargement impossible : deux essais de curl, puis code 66"; else ko "devait faire 2 essais puis sortir en 66 (code $CODE, $(appels_curl) appel(s))" "$SORTIE"; fi
  if [ "$(grep -c -- '--max-time 60' "$JOURNAL")" -eq 2 ] && [ "$(grep -c -- '--user-agent silooo-bds/1.0 (+https://github.com/e-Quantum/silooo)' "$JOURNAL")" -eq 2 ] &&
    [ "$(grep -c "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/$NOM_ZIP" "$JOURNAL")" -eq 2 ]; then
    ok "chaque essai dure 60 s au plus, avec le User-Agent honnête de l'image, sur l'adresse officielle"
  else
    ko "les essais devaient avoir --max-time 60 et le User-Agent de l'image" "$(cat "$JOURNAL")"
  fi
  if printf '%s\n' "$SORTIE" | grep -q "navigateur" && printf '%s\n' "$SORTIE" | grep -qF "$NOM_ZIP" && printf '%s\n' "$SORTIE" | grep -q "SILOOO_BDS_DIR" &&
    printf '%s\n' "$SORTIE" | grep -q "bds.sha256" && printf '%s\n' "$SORTIE" | grep -q "chown 10001:10001"; then
    ok "le message dit à Kr de télécharger dans un navigateur, où poser le zip, et où écrire la somme"
  else
    ko "le message pour Kr est incomplet" "$SORTIE"
  fi
  if [ -z "$(ls -A "$R")" ] && ! printf '%s\n' "$SORTIE" | grep -q "serveur lancé"; then ok "rien n'est créé ni lancé quand le zip manque"; else ko "rien ne devait être créé dans $R" "$(ls -A "$R")"; fi

  # --- E. Zip absent, téléchargement possible (le jour où Mojang accepte l'identité honnête) : contrôlé comme le reste.
  R="$TMP/e/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/e/sommes" "$SHA_BON" "$MD5_BON"
  lancer_bds "$R" "$TMP/e/sommes" FAUX_CURL=zip FAUX_ZIP="$ZIP_BON"
  if [ "$CODE" -eq 0 ] && [ "$(appels_curl)" -eq 1 ] && [ -f "$R/$NOM_ZIP" ] && printf '%s\n' "$SORTIE" | grep -q "serveur lancé" && [ ! -e "$R/$NOM_ZIP.partiel" ]; then
    ok "zip téléchargé, SHA-256 conforme : BDS décompressé et lancé, sans fichier partiel"
  else
    ko "le téléchargement réussi devait mener au démarrage (code $CODE)" "$SORTIE"
  fi
  R="$TMP/e2/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/e2/sommes" "$SHA_BON" "$MD5_BON"
  lancer_bds "$R" "$TMP/e2/sommes" FAUX_CURL=zip FAUX_ZIP="$ZIP_CORROMPU"
  if [ "$CODE" -eq 65 ] && [ ! -e "$R/$NOM_ZIP" ] && [ ! -e "$R/$V" ]; then
    ok "zip téléchargé mais faux : code 65, le fichier téléchargé est retiré, rien n'est décompressé"
  else
    ko "un zip téléchargé et faux devait être refusé et retiré (code $CODE)" "$SORTIE$(ls -A "$R")"
  fi

  # --- F. Dossier absent ou fermé, dossier de version incomplet, vrai dossier à la place d'un lien, zip sans serveur.
  lancer_bds "$TMP/f/inexistant" "$TMP/a/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 73 ] && printf '%s\n' "$SORTIE" | grep -q "SILOOO_BDS_DIR"; then ok "dossier de BDS absent : code 73 et le nom de SILOOO_BDS_DIR"; else ko "dossier absent : code 73 attendu (code $CODE)" "$SORTIE"; fi

  R="$TMP/f2/racine"
  mkdir -p "$R"
  chmod 555 "$R"
  if [ ! -w "$R" ]; then
    lancer_bds "$R" "$TMP/a/sommes" FAUX_CURL=interdit
    if [ "$CODE" -eq 73 ] && printf '%s\n' "$SORTIE" | grep -q "chown 10001:10001"; then ok "dossier non inscriptible : code 73 et la commande chown pour l'uid 10001"; else ko "dossier non inscriptible : code 73 attendu (code $CODE)" "$SORTIE"; fi
  else
    ignore "ce système de fichiers (ou ce compte) ignore chmod 555 : dossier non inscriptible non vérifié ici"
  fi
  chmod 755 "$R"

  R="$TMP/f3/racine"
  mkdir -p "$R/$V"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/a/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 73 ] && printf '%s\n' "$SORTIE" | grep -q "sans la marque" && [ -d "$R/$V" ]; then ok "dossier de version sans marque : code 73, il n'est pas écrasé"; else ko "dossier de version sans marque : code 73 attendu (code $CODE)" "$SORTIE"; fi

  R="$TMP/f4/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/f4/sommes" "$SHA_BON" "$MD5_BON"
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/f4/sommes" FAUX_CURL=interdit
  rm -f "$R/$V/worlds"
  mkdir -p "$R/$V/worlds/monde-precieux"
  : >"$R/$V/worlds/monde-precieux/level.dat"
  lancer_bds "$R" "$TMP/f4/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 73 ] && [ -f "$R/$V/worlds/monde-precieux/level.dat" ]; then ok "un vrai dossier worlds/ à la place du lien : code 73, le monde n'est pas touché"; else ko "un vrai dossier worlds/ ne devait pas être écrasé (code $CODE)" "$SORTIE"; fi

  R="$TMP/f5/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/f5/sommes" "$(somme_sha "$ZIP_SANS_SERVEUR")" "$(somme_md5 "$ZIP_SANS_SERVEUR")"
  cp "$ZIP_SANS_SERVEUR" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/f5/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 65 ] && printf '%s\n' "$SORTIE" | grep -q "bedrock_server" && [ ! -e "$R/$V" ] && [ ! -e "$R/$V.partiel" ]; then
    ok "zip sans bedrock_server : code 65, rien n'est gardé"
  else
    ko "un zip sans bedrock_server devait être refusé (code $CODE)" "$SORTIE"
  fi

  # --- G. Les sommes parlent d'une autre version que l'image : refus net.
  R="$TMP/g/racine"
  mkdir -p "$R"
  ecrire_sommes "$TMP/g/sommes" "$SHA_BON" "$MD5_BON" bedrock-server-1.0.0.0.zip
  cp "$ZIP_BON" "$R/$NOM_ZIP"
  lancer_bds "$R" "$TMP/g/sommes" FAUX_CURL=interdit
  if [ "$CODE" -eq 78 ] && printf '%s\n' "$SORTIE" | grep -q "la version de BDS a changé" && [ ! -e "$R/$V" ]; then
    ok "bds.sha256 au nom d'une autre version : code 78, rien n'est décompressé"
  else
    ko "des sommes d'une autre version devaient être refusées (code $CODE)" "$SORTIE"
  fi
  lancer_bds "$TMP/g/racine" "$TMP/g/sommes" FAUX_CURL=interdit BDS_VERSION=abc
  if [ "$CODE" -eq 78 ]; then ok "BDS_VERSION qui n'est pas un numéro : code 78"; else ko "BDS_VERSION=abc devait être refusée (code $CODE)" "$SORTIE"; fi

  # --- H. --rendre-seulement n'installe rien et ne télécharge rien.
  : >"$JOURNAL"
  SORTIE="$(env PATH="$FAUX:$PATH" FAUX_JOURNAL="$JOURNAL" FAUX_CURL=interdit BDS_VERSION="$V" BDS_RACINE="$TMP/h/absent" bash "$START" --rendre-seulement "$TMP/h/rendu" 2>&1)"
  CODE=$?
  if [ "$CODE" -eq 0 ] && [ "$(appels_curl)" -eq 0 ] && [ ! -e "$TMP/h/absent" ] && [ -f "$TMP/h/rendu/server.properties" ]; then
    ok "--rendre-seulement ne touche ni au zip ni au réseau"
  else
    ko "--rendre-seulement ne devait rien installer (code $CODE)" "$SORTIE"
  fi

  # --- I. Le pack de l'image est recopié dans behavior_packs/silooo à chaque démarrage (jq lit le manifeste).
  if command -v jq >/dev/null 2>&1; then
    poser_pack "$TMP/i/source"
    PACK_IMAGE="$TMP/i/source/behavior_packs/silooo"
    poser_ressources "$TMP/i/ressources"
    R="$TMP/i/racine"
    mkdir -p "$R"
    ecrire_sommes "$TMP/i/sommes" "$SHA_BON" "$MD5_BON"
    cp "$ZIP_BON" "$R/$NOM_ZIP"
    lancer_bds "$R" "$TMP/i/sommes" FAUX_CURL=interdit SILOOO_PACK_DIR="$PACK_IMAGE" SILOOO_RESOURCE_PACK_DIR="$TMP/i/ressources" SILOOO_API_KEY=cle-de-test
    if [ "$CODE" -eq 0 ] && [ -f "$R/$V/behavior_packs/silooo/manifest.json" ] && [ -f "$R/$V/behavior_packs/silooo/scripts/main.js" ] &&
      [ -f "$R/$V/behavior_packs/vanilla/manifest.json" ]; then
      ok "le pack de l'image est copié dans behavior_packs/silooo"
    else
      ko "le pack de l'image devait être copié (code $CODE)" "$SORTIE"
    fi
    if [ "$(lire_json "$R.donnees/worlds/silooo/world_behavior_packs.json" 'd[0].pack_id')" = "$UUID_PACK" ]; then
      ok "le monde (dans le volume) liste le pack copié"
    else
      ko "world_behavior_packs.json devait lister le pack" "$(cat "$R.donnees/worlds/silooo/world_behavior_packs.json" 2>&1)"
    fi
    if [ -f "$R/$V/resource_packs/silooo/textures/ui/silooo/plein.png" ] &&
      [ "$(lire_json "$R.donnees/worlds/silooo/world_resource_packs.json" 'd[0].pack_id')" = "$UUID_RESSOURCES" ]; then
      ok "le pack de ressources de l'image est copié dans resource_packs/silooo et listé dans le monde (dans le volume)"
    else
      ko "le pack de ressources devait être copié et listé" "$(cat "$R.donnees/worlds/silooo/world_resource_packs.json" 2>&1)"
    fi
    : >"$R/$V/behavior_packs/silooo/ancien-fichier.js"
    lancer_bds "$R" "$TMP/i/sommes" FAUX_CURL=interdit SILOOO_PACK_DIR="$PACK_IMAGE" SILOOO_API_KEY=cle-de-test
    if [ "$CODE" -eq 0 ] && [ ! -e "$R/$V/behavior_packs/silooo/ancien-fichier.js" ] && [ -f "$R/$V/behavior_packs/silooo/manifest.json" ]; then
      ok "au démarrage suivant, le pack est remplacé par celui de l'image (pas de fichier d'une ancienne version)"
    else
      ko "le pack devait être remplacé à chaque démarrage (code $CODE)" "$SORTIE"
    fi
  else
    if [ "${CI:-}" = true ]; then ko "jq est introuvable dans la CI : la copie du pack ne peut pas être testée"; else ignore "jq absent de ce poste : copie du pack ignorée (la CI la lance)"; fi
  fi
else
  if [ "${CI:-}" = true ]; then
    ko "unzip, sha256sum, md5sum ou les liens symboliques manquent dans la CI : les cas du zip de BDS ne peuvent pas tourner"
  else
    ignore "unzip, sha256sum, md5sum ou les liens symboliques manquent sur ce poste : cas du zip de BDS ignorés (la CI les lance)"
  fi
fi

echo
printf 'start.sh : %s réussi(s), %s en échec, %s ignoré(s).\n' "$REUSSIS" "$ECHECS" "$IGNORES"
if [ "$ECHECS" -gt 0 ]; then exit 1; fi
exit 0
