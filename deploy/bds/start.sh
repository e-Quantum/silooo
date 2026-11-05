#!/usr/bin/env bash
# Point d'entrée de l'image silooo-bds : installe BDS à partir du zip de Kr, prépare sa configuration à partir des
# variables d'environnement, puis lance bedrock_server à la place du script (exec). Rien d'autre ne tourne dans le
# conteneur.
#
#   start.sh                           installe BDS si besoin, écrit la configuration, puis démarre le serveur
#   start.sh --rendre-seulement <dir>  écrit les fichiers dans <dir>, comme si <dir> était le dossier de BDS, puis sort
#                                      sans installer BDS ni lancer le binaire (tools/tests/deploy-start.sh s'en sert)
#
# BDS n'est pas dans l'image (décision 26, docs/passation.md) : minecraft.net refuse les clients qui ne sont pas des
# navigateurs et les conditions de BDS interdisent de redistribuer le zip. Au démarrage, le script :
#   1. cherche /data/bds/bedrock-server-<version>.zip, posé par Kr dans le dossier monté (SILOOO_BDS_DIR) ; s'il manque,
#      il tente un téléchargement avec l'identité honnête de l'image (2 essais de 60 s) et, en cas d'échec, s'arrête
#      avec un message qui dit à Kr quoi faire (sortie 66) ;
#   2. contrôle le zip : SHA-256 contre bds.sha256 si la somme y est épinglée, sinon MD5 contre bds.md5 (le Content-MD5
#      de Mojang) en écrivant dans le journal la somme SHA-256 à épingler ;
#   3. le décompresse une seule fois dans /data/bds/<version>/ (marque .silooo-extrait), enlève worlds/, config/,
#      server.properties, allowlist.json et permissions.json du zip, et fait pointer worlds/ et config/ vers les volumes
#      /data/worlds et /data/config, allowlist.json et permissions.json vers des fichiers de /data/config ;
#   4. copie le pack de comportement de l'image (/opt/silooo/pack) dans behavior_packs/silooo, et son pack de ressources
#      (/opt/silooo/resource-pack) dans resource_packs/silooo ;
#   5. écrit la configuration, puis cd /data/bds/<version> et exec ./bedrock_server avec LD_LIBRARY_PATH=.
#
# Ce que le script écrit (chemins relatifs au dossier de BDS, /data/bds/<version> dans l'image) :
#   server.properties                           depuis server.properties.template, à chaque démarrage
#   config/default/permissions.json             les modules de la Script API permis par défaut
#   config/<uuid du module script>/             pour le pack Silooo : permissions.json, variables.json, secrets.json
#   worlds/<BDS_LEVEL_NAME>/world_behavior_packs.json   le pack de comportement actif dans le monde
#   worlds/<BDS_LEVEL_NAME>/world_resource_packs.json   le pack de ressources actif dans le monde (même logique)
#   allowlist.json et permissions.json          créés vides s'ils n'existent pas (des liens vers le volume de config)
#
# L'uuid du module script se lit dans le manifest.json du pack de comportement, et l'uuid et la version du pack de
# ressources dans le sien, jamais ici. La clé SILOOO_API_KEY va dans secrets.json (mode 600) sans passer par une ligne de
# commande, et le script ne l'affiche ni ne la journalise.
# Pas de « set -x » ici, jamais.
#
# Codes de sortie : 64 option inconnue, 65 zip ou manifest.json inutilisable (somme fausse comprise), 66 zip de BDS
# absent et non téléchargeable, 73 dossier de BDS non inscriptible ou fichier à la place d'un lien, 78 variable
# manquante ou invalide.
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
# Les chemins de l'image. Les variables BDS_RACINE, BDS_DONNEES, BDS_SOMMES_SHA256, BDS_SOMME_MD5, BDS_URL,
# SILOOO_PACK_DIR et SILOOO_RESOURCE_PACK_DIR ne servent qu'aux essais de tools/tests/deploy-start.sh : le compose n'en
# passe aucune.
BDS_RACINE="${BDS_RACINE:-/data/bds}"            # le dossier de Kr : le zip, puis BDS décompressé par version
DONNEES="${BDS_DONNEES:-/data}"                  # les volumes du monde (worlds/) et de la config (config/)
SOMMES_SHA256="${BDS_SOMMES_SHA256:-$DIR_SCRIPT/bds.sha256}"
SOMME_MD5="${BDS_SOMME_MD5:-$DIR_SCRIPT/bds.md5}"
PACK_SOURCE="${SILOOO_PACK_DIR:-$DIR_SCRIPT/pack}" # le pack construit par l'image, copié dans BDS à chaque démarrage
RESSOURCES_SOURCE="${SILOOO_RESOURCE_PACK_DIR:-$DIR_SCRIPT/resource-pack}" # le pack de ressources de l'image, copié de même
# Posés par le Dockerfile (ENV) : la version de BDS que l'image attend, et l'identité honnête pour le téléchargement.
BDS_VERSION="${BDS_VERSION:-}"
BDS_USER_AGENT="${BDS_USER_AGENT:-silooo-bds/1.0 (+https://github.com/e-Quantum/silooo)}"
ESSAIS_TELECHARGEMENT=2                          # décision 26 : deux essais de 60 secondes au plus
DELAI_TELECHARGEMENT=60
BDS_DIR=""                                       # le dossier de BDS : <BDS_RACINE>/<version>, ou celui de --rendre-seulement
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

# ------------------------------------------------------------------ BDS : le zip de Kr, contrôlé puis décompressé
ZEROS=0000000000000000000000000000000000000000000000000000000000000000
ZIP_TELECHARGE=0
SHA256_ZIP=""

# Comme echec, mais retire d'abord le zip que ce script vient de télécharger (un zip posé par Kr reste en place).
echec_zip() {
  local zip=$1 code=$2
  shift 2
  if [ "$ZIP_TELECHARGE" -eq 1 ]; then rm -f "$zip"; fi
  echec "$code" "$@"
}

# lire_somme <fichier> <longueur> : pose SOMME_LUE et NOM_LU depuis la première ligne qui n'est ni vide ni commentaire.
lire_somme() {
  local fichier=$1 longueur=$2 ligne
  [ -f "$fichier" ] || echec 78 "fichier de sommes introuvable : $fichier"
  ligne="$(tr -d '\r' <"$fichier" | grep -Ev '^[[:space:]]*(#|$)' | head -n 1 || true)"
  [ -n "$ligne" ] || echec 78 "$fichier ne contient aucune ligne « somme  nom du fichier »"
  if ! [[ $ligne =~ ^([0-9a-f]+)\ \ (.+)$ ]] || [ "${#BASH_REMATCH[1]}" -ne "$longueur" ]; then
    echec 78 "$fichier : la ligne utile doit être « somme  nom du fichier » (deux espaces, somme de $longueur chiffres hexadécimaux en minuscules)"
  fi
  SOMME_LUE="${BASH_REMATCH[1]}"
  NOM_LU="${BASH_REMATCH[2]}"
}

# verifier_zip <zip> : SHA-256 contre bds.sha256 si la somme y est épinglée, sinon MD5 contre bds.md5 ; refuse (65) si
# la somme ne correspond pas. Pose SHA256_ZIP.
verifier_zip() {
  local zip=$1 nom_zip md5_zip
  nom_zip="bedrock-server-$BDS_VERSION.zip"
  command -v sha256sum >/dev/null 2>&1 || echec 78 "sha256sum est introuvable"
  SHA256_ZIP="$(sha256sum "$zip" | cut -d' ' -f1)"

  lire_somme "$SOMMES_SHA256" 64
  [ "$NOM_LU" = "$nom_zip" ] ||
    echec 78 "$SOMMES_SHA256 parle de « $NOM_LU » et cette image attend « $nom_zip » : la version de BDS a changé sans les sommes (voir le Dockerfile)"
  if [ "$SOMME_LUE" != "$ZEROS" ]; then
    if [ "$SHA256_ZIP" = "$SOMME_LUE" ]; then
      dire "SHA-256 du zip conforme à bds.sha256 : $SHA256_ZIP"
      return 0
    fi
    echec_zip "$zip" 65 "le SHA-256 de $zip est $SHA256_ZIP, bds.sha256 attend $SOMME_LUE : je ne décompresse pas ce fichier. Télécharge de nouveau le zip depuis le site de Mojang (ou, si Mojang a republié le zip, mets bds.sha256 à jour dans le dépôt)."
  fi

  # Somme SHA-256 pas encore épinglée : le MD5 annoncé par Mojang attrape au moins un fichier coupé ou une autre version.
  command -v md5sum >/dev/null 2>&1 || echec 78 "md5sum est introuvable"
  lire_somme "$SOMME_MD5" 32
  [ "$NOM_LU" = "$nom_zip" ] ||
    echec 78 "$SOMME_MD5 parle de « $NOM_LU » et cette image attend « $nom_zip » : la version de BDS a changé sans les sommes (voir le Dockerfile)"
  md5_zip="$(md5sum "$zip" | cut -d' ' -f1)"
  if [ "$md5_zip" != "$SOMME_LUE" ]; then
    echec_zip "$zip" 65 "le MD5 de $zip est $md5_zip, bds.md5 (le Content-MD5 de Mojang) attend $SOMME_LUE : je ne décompresse pas ce fichier. Télécharge de nouveau le zip."
  fi
  dire "MD5 du zip conforme à bds.md5 (le Content-MD5 de Mojang) : $md5_zip"
  dire "SHA-256 pas encore épinglée. Kr : écris-la dans deploy/bds/bds.sha256 du dépôt, à la place des zéros : $SHA256_ZIP  $nom_zip"
}

# telecharger_zip <zip> : tente le téléchargement (deux essais) avec l'identité honnête de l'image, sans la déguiser.
telecharger_zip() {
  local zip=$1 essai url partiel="$1.partiel"
  url="${BDS_URL:-https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-$BDS_VERSION.zip}"
  command -v curl >/dev/null 2>&1 || {
    alerte "curl est introuvable : pas de téléchargement"
    return 1
  }
  for ((essai = 1; essai <= ESSAIS_TELECHARGEMENT; essai++)); do
    dire "téléchargement de BDS, essai $essai sur $ESSAIS_TELECHARGEMENT ($DELAI_TELECHARGEMENT s au plus) : $url"
    rm -f "$partiel"
    if curl --fail --silent --show-error --location --connect-timeout 30 --max-time "$DELAI_TELECHARGEMENT" \
      --user-agent "$BDS_USER_AGENT" --output "$partiel" "$url" && [ -s "$partiel" ]; then
      mv "$partiel" "$zip"
      ZIP_TELECHARGE=1
      return 0
    fi
    rm -f "$partiel"
  done
  return 1
}

# Le message pour Kr : le zip vient de lui. Sur la sortie d'erreur, une ligne par ligne, avec le préfixe du script.
dire_a_kr() {
  local zip=$1 ligne
  while IFS= read -r ligne; do printf 'start.sh : %s\n' "$ligne" >&2; done <<MESSAGE
le zip de BDS $BDS_VERSION manque et le téléchargement a échoué : je m'arrête.
Kr, c'est à toi de le poser : minecraft.net refuse les clients qui ne sont pas des navigateurs, et les conditions de BDS
interdisent de redistribuer le zip (décision 26, docs/passation.md).
 1. Dans un navigateur, ouvre https://www.minecraft.net/en-us/download/server/bedrock et télécharge la version Linux
    $BDS_VERSION (ou le lien direct https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-$BDS_VERSION.zip).
 2. Pose le fichier, sous le nom $(basename "$zip"), dans le dossier de l'hôte monté sur $BDS_RACINE (SILOOO_BDS_DIR,
    /var/www/silooo/bds par défaut). Le dossier doit appartenir à l'uid $(id -u) : chown 10001:10001 sur ce dossier.
 3. Calcule sa somme (sha256sum sous Linux, Get-FileHash -Algorithm SHA256 sous Windows) et écris-la dans deploy/bds/bds.sha256
    du dépôt. Tant qu'elle n'y est pas, je contrôle le MD5 de Mojang (deploy/bds/bds.md5) et j'écris la somme à épingler dans ce journal.
 4. Relance silooo-bds : bash deploy/scripts/deploy.sh <tag> (le compose ne le relance pas seul après plusieurs échecs).
MESSAGE
}

# lier <nom> <cible> : dans le dossier de BDS, un lien vers un volume. Un vrai fichier ou dossier à cet endroit n'est
# jamais écrasé ici (le monde y serait perdu) : seule la décompression d'un zip neuf les retire.
lier() {
  local chemin="$BDS_DIR/$1"
  if [ -L "$chemin" ]; then
    ln -sfn "$2" "$chemin"
  elif [ -e "$chemin" ]; then
    echec 73 "$chemin existe et n'est pas un lien : je ne l'écrase pas (le monde et la config vivent dans $2)"
  else
    ln -s "$2" "$chemin"
  fi
}

installer_bds() {
  local dir_version zip partiel marque
  [[ $BDS_VERSION =~ ^[0-9]+(\.[0-9]+){2,3}$ ]] ||
    echec 78 "BDS_VERSION doit être un numéro de version comme 1.26.62.1 (le Dockerfile le pose)"
  dir_version="$BDS_RACINE/$BDS_VERSION"
  zip="$BDS_RACINE/bedrock-server-$BDS_VERSION.zip"
  marque="$dir_version/.silooo-extrait"
  BDS_DIR="$dir_version"

  mkdir -p "$DONNEES/worlds" "$DONNEES/config" 2>/dev/null ||
    echec 73 "impossible de créer $DONNEES/worlds et $DONNEES/config (les volumes du monde et de la config)"

  if [ -f "$marque" ] && [ -x "$dir_version/bedrock_server" ]; then
    dire "BDS $BDS_VERSION déjà décompressé dans $dir_version ($(head -n 1 "$marque"))"
  else
    [ -d "$BDS_RACINE" ] || echec 73 "$BDS_RACINE n'existe pas : le compose doit monter le dossier SILOOO_BDS_DIR de l'hôte dessus"
    [ -w "$BDS_RACINE" ] ||
      echec 73 "$BDS_RACINE n'est pas inscriptible par l'uid $(id -u) : sur l'hôte, chown 10001:10001 sur le dossier SILOOO_BDS_DIR (/var/www/silooo/bds par défaut)"
    [ ! -e "$dir_version" ] ||
      echec 73 "$dir_version existe sans la marque .silooo-extrait : une décompression a été coupée ou le dossier a été modifié. Supprime-le sur l'hôte, BDS sera décompressé de nouveau."

    if [ ! -f "$zip" ]; then
      if ! telecharger_zip "$zip"; then
        dire_a_kr "$zip"
        exit 66
      fi
    else
      dire "zip de BDS trouvé : $zip"
    fi
    verifier_zip "$zip"

    command -v unzip >/dev/null 2>&1 || echec 78 "unzip est introuvable"
    partiel="$BDS_RACINE/$BDS_VERSION.partiel"
    rm -rf "$partiel"
    mkdir -p "$partiel"
    dire "décompression de $zip dans $dir_version"
    unzip -q "$zip" -d "$partiel" || {
      rm -rf "$partiel"
      echec 65 "unzip n'a pas pu décompresser $zip : le fichier est abîmé, télécharge-le de nouveau"
    }
    [ -f "$partiel/bedrock_server" ] || {
      rm -rf "$partiel"
      echec 65 "$zip ne contient pas bedrock_server : est-ce bien la version Linux de BDS $BDS_VERSION ?"
    }
    chmod 0755 "$partiel/bedrock_server"
    # Ce que le zip apporte et que l'image remplace : le monde, la config et les listes vivent dans les volumes.
    rm -rf "$partiel/worlds" "$partiel/config" "$partiel/server.properties" "$partiel/allowlist.json" "$partiel/permissions.json"
    printf 'bedrock-server-%s.zip, sha256 %s, décompressé le %s\n' "$BDS_VERSION" "$SHA256_ZIP" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$partiel/.silooo-extrait"
    mv "$partiel" "$dir_version"
  fi

  lier worlds "$DONNEES/worlds"
  lier config "$DONNEES/config"
  lier allowlist.json "$DONNEES/config/allowlist.json"
  lier permissions.json "$DONNEES/config/operateurs.json"
}

if [ "$RENDRE_SEULEMENT" -eq 0 ]; then installer_bds; fi

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

# ------------------------------------------------------------------ le monde et ses listes de packs
MONDE_DIR="$BDS_DIR/worlds/$nom_monde"
uuid_ok='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

# inscrire_pack <liste> <uuid> <version> : l'entrée du pack dans world_behavior_packs.json ou world_resource_packs.json,
# deux listes du même format. Une liste existante garde ses autres packs ; l'entrée de Silooo est remplacée par celle du
# manifeste, sans doublon. Une liste illisible n'est jamais écrasée (code 65).
inscrire_pack() {
  local liste=$1 id=$2 version=$3
  if [ -s "$liste" ]; then
    jq --arg id "$id" --argjson version "$version" \
      'if type == "array" then map(select(.pack_id != $id)) + [{pack_id: $id, version: $version}] else error("la liste n est pas un tableau") end' \
      "$liste" >"$liste.tmp" || { rm -f "$liste.tmp"; echec 65 "$liste n'est pas une liste JSON de packs : je ne l'écrase pas"; }
    mv "$liste.tmp" "$liste"
  else
    printf '[\n  {\n    "pack_id": "%s",\n    "version": %s\n  }\n]\n' "$id" "$version" >"$liste"
  fi
}

# ------------------------------------------------------------------ le pack de comportement Silooo
PACK_DIR="$BDS_DIR/behavior_packs/silooo"
MANIFESTE="$PACK_DIR/manifest.json"
# Le pack de l'image est recopié à chaque démarrage : la version de l'add-on est celle de l'image (du tag déployé),
# jamais celle d'un démarrage précédent. Le dossier de BDS vit sur l'hôte et survit aux images.
if [ -f "$PACK_SOURCE/manifest.json" ]; then
  mkdir -p "$BDS_DIR/behavior_packs"
  rm -rf "$PACK_DIR"
  cp -a "$PACK_SOURCE" "$PACK_DIR"
  dire "pack Silooo copié de $PACK_SOURCE dans $PACK_DIR"
fi
PACK_PRESENT=0
if [ -f "$MANIFESTE" ]; then
  PACK_PRESENT=1
else
  # Sans pack, le serveur démarre quand même, sans configuration de scripts.
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
  mkdir -p "$MONDE_DIR"
  inscrire_pack "$MONDE_DIR/world_behavior_packs.json" "$PACK_UUID" "$PACK_VERSION"
  dire "pack Silooo $PACK_UUID $PACK_VERSION actif dans le monde « $nom_monde » ; config des scripts dans config/$MODULE_UUID (secrets en mode 600)"

  # Les modules server-net et server-admin sont en bêta : sans l'option « Beta APIs » dans le monde, ils ne se
  # chargent pas (docs/bedrock.md, fait 1). Un monde créé par BDS lui-même ne l'a pas.
  if [ ! -f "$MONDE_DIR/level.dat" ]; then
    alerte "le monde « $nom_monde » n'existe pas encore : BDS en créera un sans « Beta APIs », et les scripts ne se chargeront pas. Envoie un monde créé avec l'option (docs/deploiement.md)."
  fi
fi

# ------------------------------------------------------------------ le pack de ressources Silooo
# Même logique que le pack de comportement : recopié de l'image à chaque démarrage (jamais celui d'un démarrage
# précédent), puis listé dans le monde. Le HUD, les textures et Oooscar viennent de lui. Le manifeste est de type
# resources : pas de module script, seuls header.uuid et header.version comptent.
RESSOURCES_DIR="$BDS_DIR/resource_packs/silooo"
RESSOURCES_MANIFESTE="$RESSOURCES_DIR/manifest.json"
if [ -f "$RESSOURCES_SOURCE/manifest.json" ]; then
  mkdir -p "$BDS_DIR/resource_packs"
  rm -rf "$RESSOURCES_DIR"
  cp -a "$RESSOURCES_SOURCE" "$RESSOURCES_DIR"
  dire "pack de ressources Silooo copié de $RESSOURCES_SOURCE dans $RESSOURCES_DIR"
fi
if [ -f "$RESSOURCES_MANIFESTE" ]; then
  command -v jq >/dev/null 2>&1 || echec 78 "jq est introuvable : il lit manifest.json"
  RESSOURCES_UUID="$(jq -er '.header.uuid' "$RESSOURCES_MANIFESTE")" || echec 65 "$RESSOURCES_MANIFESTE : header.uuid manque ou le JSON est invalide"
  RESSOURCES_VERSION="$(jq -ec '.header.version' "$RESSOURCES_MANIFESTE")" || echec 65 "$RESSOURCES_MANIFESTE : header.version manque"
  [[ $RESSOURCES_UUID =~ $uuid_ok ]] || echec 65 "$RESSOURCES_MANIFESTE : header.uuid n'est pas un uuid"
  [[ $RESSOURCES_VERSION =~ ^\[[0-9]+,[0-9]+,[0-9]+\]$ ]] || echec 65 "$RESSOURCES_MANIFESTE : header.version doit être [x, y, z]"
  # Sans cette ligne BDS ne propose pas le pack aux joueurs, même posé dans resource_packs/.
  mkdir -p "$MONDE_DIR"
  inscrire_pack "$MONDE_DIR/world_resource_packs.json" "$RESSOURCES_UUID" "$RESSOURCES_VERSION"
  dire "pack de ressources Silooo $RESSOURCES_UUID $RESSOURCES_VERSION actif dans le monde « $nom_monde »"
else
  # Sans pack de ressources, le serveur démarre quand même : les joueurs n'ont alors ni le HUD ni Oooscar.
  dire "pas de pack de ressources Silooo ($RESSOURCES_MANIFESTE absent) : le serveur démarre sans pack de ressources"
  rmdir "$RESSOURCES_DIR" 2>/dev/null || true
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
[ -x ./bedrock_server ] || echec 78 "$BDS_DIR/bedrock_server n'est pas exécutable"
export LD_LIBRARY_PATH=.
dire "démarrage de bedrock_server"
exec ./bedrock_server
