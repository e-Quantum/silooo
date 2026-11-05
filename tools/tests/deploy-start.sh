#!/usr/bin/env bash
# Teste deploy/bds/start.sh sans Docker : l'option --rendre-seulement écrit les fichiers dans un dossier jetable.
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
  "server-udp-ports=19143-19174" "allow-list=true" "content-log-file-enabled=true" "online-mode=true"; do
  if grep -qxF -- "$attendu" "$D/server.properties"; then ok "server.properties contient $attendu"; else ko "server.properties devrait contenir $attendu" "$(cat "$D/server.properties" 2>&1)"; fi
done
if grep -q '{{' "$D/server.properties"; then ko "server.properties garde une marque non remplacée"; else ok "server.properties sans marque restante"; fi
if printf '%s\n' "$SORTIE" | grep -q "pas de pack"; then ok "dit qu'il démarre sans pack"; else ko "devait dire qu'il n'y a pas de pack" "$SORTIE"; fi
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
fi

# ------------------------------------------------------------------ lancement
echo "start.sh : lancement du serveur"
D="$TMP/lancement"
mkdir -p "$D"
cat >"$D/bedrock_server" <<'SERVEUR'
#!/usr/bin/env bash
echo "serveur lancé : LD_LIBRARY_PATH=$LD_LIBRARY_PATH dossier=$(basename "$PWD")"
SERVEUR
chmod +x "$D/bedrock_server"
SORTIE="$(env BDS_DIR="$D" bash "$START" 2>&1)"
CODE=$?
if [ "$CODE" -eq 0 ] && printf '%s\n' "$SORTIE" | grep -q "serveur lancé : LD_LIBRARY_PATH=. dossier=lancement"; then
  ok "sans l'option, start.sh lance ./bedrock_server dans son dossier avec LD_LIBRARY_PATH=."
else
  ko "start.sh devait lancer ./bedrock_server" "$SORTIE"
fi

echo
printf 'start.sh : %s réussi(s), %s en échec, %s ignoré(s).\n' "$REUSSIS" "$ECHECS" "$IGNORES"
if [ "$ECHECS" -gt 0 ]; then exit 1; fi
exit 0
