#!/usr/bin/env bash
# Point d'entrée de l'image silooo-bds : prépare la configuration de BDS à partir des variables d'environnement,
# puis lance bedrock_server à la place du script (exec). Rien d'autre ne tourne dans le conteneur.
#
#   start.sh                           écrit la configuration, puis démarre le serveur
#   start.sh --rendre-seulement <dir>  écrit les fichiers dans <dir>, comme si <dir> était /opt/bds, puis sort
#                                      sans lancer le binaire (tools/tests/deploy-start.sh s'en sert, sans Docker)
#
# Ce que le script écrit (chemins relatifs au dossier de BDS, /opt/bds dans l'image) :
#   server.properties                           depuis server.properties.template, à chaque démarrage
#   config/default/permissions.json             les modules de la Script API permis par défaut
#   config/<uuid du module script>/             pour le pack Silooo : permissions.json, variables.json, secrets.json
#   worlds/<BDS_LEVEL_NAME>/world_behavior_packs.json   le pack actif dans le monde
#   allowlist.json et permissions.json          créés vides s'ils n'existent pas (dans l'image, des liens vers le volume)
#
# L'uuid du module script se lit dans le manifest.json du pack, jamais ici. La clé SILOOO_API_KEY va dans
# secrets.json (mode 600) sans passer par une ligne de commande, et le script ne l'affiche ni ne la journalise.
# Pas de « set -x » ici, jamais.
#
# Codes de sortie : 64 option inconnue, 65 manifest.json inutilisable, 78 variable manquante ou invalide.
set -euo pipefail

dire() { printf 'start.sh : %s\n' "$*"; }
alerte() { printf 'start.sh : attention : %s\n' "$*" >&2; }
echec() {
  local code=$1
  shift
  printf 'start.sh : %s\n' "$*" >&2
  exit "$code"
}

DIR_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GABARIT="${BDS_GABARIT:-$DIR_SCRIPT/server.properties.template}"
BDS_DIR="${BDS_DIR:-/opt/bds}"
RENDRE_SEULEMENT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --rendre-seulement)
      [ $# -ge 2 ] || echec 64 "--rendre-seulement demande un dossier"
      RENDRE_SEULEMENT=1
      BDS_DIR="$2"
      shift 2
      ;;
    -h | --help)
      sed -n '2,6p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *) echec 64 "option inconnue : $1 (essaie --rendre-seulement <dossier>)" ;;
  esac
done

# ------------------------------------------------------------------ les réglages
# Ce sont des réglages d'exploitation, pas des chiffres du jeu : docs/deploiement.md et ADR 0004 disent d'où
# ils viennent. Une variable vide vaut une variable absente (le compose passe les variables vides).
nom_serveur="${BDS_SERVER_NAME:-Silooo}"        # texte vu par les joueurs : à valider par Kr
port="${BDS_PORT:-19142}"                       # ADR 0004 : port TCP de signalisation NetherNet
udp_local="${BDS_UDP_PORTS:-19143-19174}"       # plage UDP dans le conteneur
udp_public="${BDS_UDP_PORTS_PUBLIES:-$udp_local}" # plage UDP publiée sur l'hôte, même taille
ip_publique="${BDS_PUBLIC_IP:-}"
max_joueurs="${BDS_MAX_PLAYERS:-20}"
nom_monde="${BDS_LEVEL_NAME:-silooo}"
allow_list="${BDS_ALLOW_LIST:-true}"
url_core="${SILOOO_CORE_URL:-http://silooo-core:8080/}"

exiger_entier() { # exiger_entier <nom> <valeur> <min> <max>
  if ! [[ $2 =~ ^[0-9]{1,5}$ ]] || [ "$((10#$2))" -lt "$3" ] || [ "$((10#$2))" -gt "$4" ]; then
    echec 78 "$1 doit être un entier de $3 à $4"
  fi
}

# analyser_plage <nom> <valeur> : « 19143 » ou « 19143-19174 » ; pose PLAGE_DEBUT et PLAGE_FIN.
analyser_plage() {
  if [[ $2 =~ ^([0-9]{1,5})(-([0-9]{1,5}))?$ ]]; then
    PLAGE_DEBUT=$((10#${BASH_REMATCH[1]}))
    PLAGE_FIN=$((10#${BASH_REMATCH[3]:-${BASH_REMATCH[1]}}))
  else
    echec 78 "$1 doit être un port ou une plage de ports, par exemple 19143-19174"
  fi
  if [ "$PLAGE_DEBUT" -lt 1 ] || [ "$PLAGE_FIN" -gt 65535 ] || [ "$PLAGE_DEBUT" -gt "$PLAGE_FIN" ]; then
    echec 78 "$1 : les ports vont de 1 à 65535 et la plage s'écrit du plus petit au plus grand"
  fi
}

# Les marques de contrôle (saut de ligne compris) casseraient server.properties ou le JSON.
sans_controle() { # sans_controle <nom> <valeur>
  if [[ $2 == *[[:cntrl:]]* ]]; then echec 78 "$1 contient un caractère de contrôle"; fi
}

sans_controle BDS_SERVER_NAME "$nom_serveur"
# Règle de BDS pour server-name : aucun point-virgule.
if [[ $nom_serveur == *';'* ]]; then echec 78 "BDS_SERVER_NAME ne doit pas contenir de point-virgule"; fi

exiger_entier BDS_PORT "$port" 1 65535
exiger_entier BDS_MAX_PLAYERS "$max_joueurs" 1 1000

analyser_plage BDS_UDP_PORTS "$udp_local"
local_taille=$((PLAGE_FIN - PLAGE_DEBUT + 1))
analyser_plage BDS_UDP_PORTS_PUBLIES "$udp_public"
public_taille=$((PLAGE_FIN - PLAGE_DEBUT + 1))
if [ "$local_taille" -ne "$public_taille" ]; then
  echec 78 "BDS_UDP_PORTS ($local_taille ports) et BDS_UDP_PORTS_PUBLIES ($public_taille ports) doivent avoir la même taille"
fi
if [ "$local_taille" -lt "$((10#$max_joueurs))" ]; then
  alerte "la plage UDP compte $local_taille ports pour $max_joueurs joueurs : BDS réutilisera des ports (docs/bedrock.md, fait 5)"
fi

if [ -n "$ip_publique" ] && ! [[ $ip_publique =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ || $ip_publique =~ ^\[[0-9a-fA-F:.]+\]$ ]]; then
  echec 78 "BDS_PUBLIC_IP doit être une adresse IPv4, ou IPv6 entre crochets"
fi

# Nom de dossier du monde : BDS refuse le point-virgule et les caractères illégaux dans un nom de fichier.
sans_controle BDS_LEVEL_NAME "$nom_monde"
nom_interdit='[;/\`?*<>|":]' # dans une classe de caractères, l'antislash est littéral
if [[ $nom_monde =~ $nom_interdit || $nom_monde == .* || $nom_monde == *..* || ${#nom_monde} -gt 64 ]]; then
  echec 78 "BDS_LEVEL_NAME : 64 caractères au plus, sans ; / \\ \` ? * < > | \" : ni point au début"
fi

if [ "$allow_list" != true ] && [ "$allow_list" != false ]; then echec 78 "BDS_ALLOW_LIST vaut true ou false"; fi

# L'URL de silooo-core, pour allowed_uris (avec une barre finale) et pour la variable core_url (sans).
if ! [[ $url_core =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$ ]]; then
  echec 78 "SILOOO_CORE_URL doit être une URL http(s) simple, par exemple http://silooo-core:8080/"
fi
core_url_nue="${url_core%/}"
while [[ $core_url_nue == */ ]]; do core_url_nue="${core_url_nue%/}"; done
core_uri_permise="$core_url_nue/"

# server-udp-ports : [IP:]public:local. Avec une IP publique, les deux ports sont obligatoires même égaux.
if [ -n "$ip_publique" ]; then
  udp_valeur="$ip_publique:$udp_public:$udp_local"
elif [ "$udp_public" = "$udp_local" ]; then
  udp_valeur="$udp_local"
else
  udp_valeur="$udp_public:$udp_local"
fi
if [ -z "$ip_publique" ]; then
  alerte "BDS_PUBLIC_IP est vide : derrière Docker, les clients hors du VPS ne recevront pas d'adresse joignable (docs/bedrock.md, fait 5)"
fi

# ------------------------------------------------------------------ server.properties
[ -f "$GABARIT" ] || echec 78 "gabarit introuvable : $GABARIT"
mkdir -p "$BDS_DIR"

declare -A VALEURS=(
  [BDS_SERVER_NAME]="$nom_serveur"
  [BDS_LEVEL_NAME]="$nom_monde"
  [BDS_MAX_PLAYERS]="$((10#$max_joueurs))"
  [BDS_PORT]="$((10#$port))"
  [BDS_UDP_PORTS_VALEUR]="$udp_valeur"
  [BDS_ALLOW_LIST]="$allow_list"
)
# Le « & » du remplacement a un sens spécial dans bash 5.2 ; les guillemets le neutralisent, et l'option aussi.
shopt -u patsub_replacement 2>/dev/null || true
rendre_gabarit() {
  local ligne cle
  while IFS= read -r ligne || [ -n "$ligne" ]; do
    for cle in "${!VALEURS[@]}"; do
      ligne="${ligne//"{{$cle}}"/"${VALEURS[$cle]}"}"
    done
    printf '%s\n' "$ligne"
  done <"$GABARIT"
}
rendre_gabarit >"$BDS_DIR/server.properties"
if grep -q '{{' "$BDS_DIR/server.properties"; then
  echec 78 "server.properties garde une marque non remplacée : $(grep -o '{{[A-Z_]*}}' "$BDS_DIR/server.properties" | sort -u | tr '\n' ' ')"
fi
dire "server.properties écrit : monde « $nom_monde », TCP $port (signalisation), UDP $udp_valeur, $max_joueurs joueurs au plus, allow-list=$allow_list"

# ------------------------------------------------------------------ config/ : permissions des modules
CONFIG_DIR="$BDS_DIR/config"
mkdir -p "$CONFIG_DIR/default"
# Les modules permis à tous les packs : la liste par défaut de la documentation de BDS (docs/bedrock.md, fait 2).
# server-net n'y figure pas exprès : il n'est permis qu'au pack Silooo, plus bas.
cat >"$CONFIG_DIR/default/permissions.json" <<'JSON'
{
  "allowed_modules": [
    "@minecraft/server-gametest",
    "@minecraft/server",
    "@minecraft/server-ui",
    "@minecraft/server-admin",
    "@minecraft/server-editor"
  ]
}
JSON

# ------------------------------------------------------------------ le pack de comportement Silooo
PACK_DIR="$BDS_DIR/behavior_packs/silooo"
MANIFESTE="$PACK_DIR/manifest.json"
PACK_PRESENT=0
if [ -f "$MANIFESTE" ]; then
  PACK_PRESENT=1
else
  # L'image est construite sans add-on tant qu'addon/ n'existe pas : le serveur démarre quand même, sans pack.
  dire "pas de pack Silooo ($MANIFESTE absent) : le serveur démarre sans pack, sans configuration de scripts"
  rmdir "$PACK_DIR" 2>/dev/null || true
fi

if [ "$PACK_PRESENT" -eq 1 ]; then
  command -v jq >/dev/null 2>&1 || echec 78 "jq est introuvable : il lit manifest.json"
  PACK_UUID="$(jq -er '.header.uuid' "$MANIFESTE")" || echec 65 "$MANIFESTE : header.uuid manque ou le JSON est invalide"
  PACK_VERSION="$(jq -ec '.header.version' "$MANIFESTE")" || echec 65 "$MANIFESTE : header.version manque"
  # L'uuid du module de type script, pas celui de l'en-tête : c'est le nom du dossier de config/ (docs/bedrock.md, fait 2).
  MODULE_UUID="$(jq -er '[.modules[]? | select(.type == "script")][0].uuid' "$MANIFESTE")" ||
    echec 65 "$MANIFESTE : aucun module de type script"
  uuid_ok='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  [[ $PACK_UUID =~ $uuid_ok ]] || echec 65 "$MANIFESTE : header.uuid n'est pas un uuid"
  [[ $MODULE_UUID =~ $uuid_ok ]] || echec 65 "$MANIFESTE : l'uuid du module script n'est pas un uuid"
  [[ $PACK_VERSION =~ ^\[[0-9]+,[0-9]+,[0-9]+\]$ ]] || echec 65 "$MANIFESTE : header.version doit être [x, y, z]"

  # La clé est obligatoire dès qu'un pack est là : sans elle l'add-on ne peut appeler aucune route. Ni la valeur
  # ni un extrait n'est écrit dans la sortie.
  cle="${SILOOO_API_KEY:-}"
  if [ -z "$cle" ]; then echec 78 "SILOOO_API_KEY manque : l'add-on ne pourrait pas appeler silooo-core"; fi
  sans_controle SILOOO_API_KEY "$cle"

  json_chaine() { # json_chaine <valeur> : la valeur entre guillemets JSON
    local s=$1 bs=$'\\' guillemet='"'
    s=${s//"$bs"/"$bs$bs"}
    s=${s//"$guillemet"/"$bs$guillemet"}
    printf '"%s"' "$s"
  }
  ecrire_prive() { # ecrire_prive <fichier> : le contenu arrive sur l'entrée standard ; le fichier est en mode 600
    (umask 077 && cat >"$1") && chmod 600 "$1"
  }

  PACK_CONFIG="$CONFIG_DIR/$MODULE_UUID"
  # Dossier fermé aux autres comptes : il contient la clé. Le umask suffit dans l'image (Linux) ; chmod ne fait que
  # confirmer, et un système de fichiers qui ne le permet pas (essai local sous Windows) n'arrête pas le script.
  (umask 077 && mkdir -p "$PACK_CONFIG")
  chmod 700 "$PACK_CONFIG" 2>/dev/null || alerte "chmod 700 impossible sur $PACK_CONFIG : droits non vérifiés"

  # server-net et server-admin ne sont pas dans le fichier par défaut. allowed_uris vise silooo-core seulement,
  # en HTTP simple sur le réseau Docker : pas de force_tls (docs/bedrock.md, fait 2). Le format de correspondance
  # de allowed_uris reste à confirmer sur le BDS de test.
  cat >"$PACK_CONFIG/permissions.json" <<JSON
{
  "allowed_modules": [
    "@minecraft/server",
    "@minecraft/server-ui",
    "@minecraft/server-net",
    "@minecraft/server-admin"
  ],
  "module_permissions": {
    "@minecraft/server-net": {
      "allowed_uris": ["$core_uri_permise"]
    }
  }
}
JSON

  # Lus par l'add-on : variables.get("core_url") sans barre finale, secrets.get("silooo_key") qu'il met dans un
  # en-tête X-Silooo-Key sans pouvoir le lire (docs/bedrock.md, faits 2 et 3).
  printf '{\n  "core_url": %s\n}\n' "$(json_chaine "$core_url_nue")" | ecrire_prive "$PACK_CONFIG/variables.json"
  printf '{\n  "silooo_key": %s\n}\n' "$(json_chaine "$cle")" | ecrire_prive "$PACK_CONFIG/secrets.json"
  unset cle

  # Le monde liste le pack : sans cette ligne BDS ne charge pas le pack, même posé dans behavior_packs/.
  MONDE_DIR="$BDS_DIR/worlds/$nom_monde"
  mkdir -p "$MONDE_DIR"
  LISTE="$MONDE_DIR/world_behavior_packs.json"
  if [ -s "$LISTE" ]; then
    # Une liste existante garde ses autres packs ; l'entrée de Silooo est remplacée par celle du manifeste.
    jq --arg id "$PACK_UUID" --argjson version "$PACK_VERSION" \
      'if type == "array" then map(select(.pack_id != $id)) + [{pack_id: $id, version: $version}] else error("la liste n est pas un tableau") end' \
      "$LISTE" >"$LISTE.tmp" || { rm -f "$LISTE.tmp"; echec 65 "$LISTE n'est pas une liste JSON de packs : je ne l'écrase pas"; }
    mv "$LISTE.tmp" "$LISTE"
  else
    printf '[\n  {\n    "pack_id": "%s",\n    "version": %s\n  }\n]\n' "$PACK_UUID" "$PACK_VERSION" >"$LISTE"
  fi
  dire "pack Silooo $PACK_UUID $PACK_VERSION actif dans le monde « $nom_monde » ; config des scripts dans config/$MODULE_UUID (secrets en mode 600)"

  # Les modules server-net et server-admin sont en bêta : sans l'option « Beta APIs » dans le monde, ils ne se
  # chargent pas (docs/bedrock.md, fait 1). Un monde créé par BDS lui-même ne l'a pas.
  if [ ! -f "$MONDE_DIR/level.dat" ]; then
    alerte "le monde « $nom_monde » n'existe pas encore : BDS en créera un sans « Beta APIs », et les scripts ne se chargeront pas. Envoie un monde créé avec l'option (docs/deploiement.md)."
  fi
fi

# La liste des joueurs permis et celle des opérateurs : vides tant que personne n'est ajouté. Dans l'image, ces deux
# fichiers sont des liens vers le volume config/, ils survivent donc aux redéploiements.
for fichier in allowlist.json permissions.json; do
  if [ ! -e "$BDS_DIR/$fichier" ]; then printf '[]\n' >"$BDS_DIR/$fichier"; fi
done

if [ "$RENDRE_SEULEMENT" -eq 1 ]; then
  dire "rendu terminé dans $BDS_DIR : le serveur n'est pas lancé"
  exit 0
fi

cd "$BDS_DIR"
export LD_LIBRARY_PATH=.
dire "démarrage de bedrock_server"
exec ./bedrock_server
