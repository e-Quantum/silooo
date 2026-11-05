#!/usr/bin/env bash
# Usage : bash deploy/scripts/importer-sql.sh <fichier.sql>
# Joue un fichier SQL dans la base « silooo » du VPS, depuis un conteneur mariadb éphémère sur le réseau de SileneMC.
# Sert à recopier l'état du bot Discord posé depuis le PC de Kr (tables discord_bot_messages, discord_games,
# discord_records) avant que le bot ne tourne sur le VPS, pour qu'il retrouve ses cartes au lieu de les reposer.
#
# Le compte et le mot de passe arrivent par l'environnement du conteneur (--env-file : Docker lit le fichier, ce script
# ne l'ouvre jamais) ; le mot de passe n'est sur aucune ligne de commande. Le fichier SQL est joué dans une transaction
# implicite par instruction : une ligne déjà présente (clé en double) arrête l'import, c'est voulu.
# Les interdits de commun.sh valent ici : aucun conteneur bw-* ou huby-*, rien lu ni affiché du fichier d'environnement.
set -euo pipefail

# shellcheck source=commun.sh
source "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

case "${1:-}" in
  -h | --help | "")
    sed -n '2,5p' "${BASH_SOURCE[0]}"
    exit 64
    ;;
esac

fichier="$1"
[ -f "$fichier" ] || echec 64 "le fichier SQL n'existe pas : $fichier"
[ -f "$FICHIER_ENV" ] || echec 1 "le fichier d'environnement manque dans $RACINE : Kr le pose, une session ne le crée pas"
docker network inspect "$SILENEMC_NETWORK" >/dev/null 2>&1 ||
  echec 1 "le réseau Docker « $SILENEMC_NETWORK » n'existe pas : vérifie avec docker network ls et exporte SILENEMC_NETWORK"

dire "Import de $fichier dans la base silooo"
# Entre apostrophes : c'est le shell du conteneur qui développe les variables, pas celui-ci.
# shellcheck disable=SC2016
script_import='
url="${SILOOO_DB_URL#jdbc:mariadb://}"
hote_port="${url%%/*}"
hote="${hote_port%%:*}"
port=3306
case "$hote_port" in *:*) port="${hote_port##*:}" ;; esac
MYSQL_PWD="$SILOOO_DB_PASSWORD" exec mariadb -h "$hote" -P "$port" -u "$SILOOO_DB_USER" \
  --default-character-set=utf8mb4 silooo
'
docker run --rm -i --network "$SILENEMC_NETWORK" --env-file "$FICHIER_ENV" --entrypoint sh "$IMAGE_MARIADB" \
  -c "$script_import" <"$fichier" || echec 1 "l'import a échoué (rien n'est annulé : relire la base avant de rejouer)"
dire "Import terminé."
