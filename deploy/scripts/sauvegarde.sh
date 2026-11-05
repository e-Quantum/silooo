#!/usr/bin/env bash
# Sauvegarde de la base silooo et du monde, prise par deploy.sh avant chaque déploiement (docs/deploiement.md,
# « Sauvegardes »). Elle s'ajoute à celle de restic, chaque nuit, qui revient à Kr.
#
#   bash deploy/scripts/sauvegarde.sh [dossier]
#
# Sans argument, le dossier est <racine>/sauvegardes/AAAAMMJJ-HHMMSS. Il contient :
#   silooo.sql.gz     mariadb-dump de la base « silooo », lancé dans un conteneur mariadb éphémère sur le réseau de
#                     SileneMC. Le compte et le mot de passe arrivent par l'environnement du conteneur (--env-file :
#                     Docker lit le fichier, ce script ne l'ouvre jamais) ; le mot de passe n'est sur aucune ligne de commande.
#   monde.tar.gz      l'archive du volume du monde (silooo-bds-worlds), lue en lecture seule. Copie à chaud : BDS peut
#                     écrire pendant la lecture, la restauration se fait serveur arrêté (docs/deploiement.md).
#
# Le fichier d'environnement ne doit pas mettre de guillemets autour des valeurs : « docker run --env-file » les garde,
# alors que Compose les retire (deploy/exemple.env.txt le rappelle).
# Les interdits de commun.sh valent ici : aucun conteneur bw-* ou huby-*, rien lu ni affiché du fichier d'environnement.
set -euo pipefail

# shellcheck source=commun.sh
source "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

case "${1:-}" in
  -h | --help)
    sed -n '2,5p' "${BASH_SOURCE[0]}"
    exit 0
    ;;
esac

dossier="${1:-$RACINE/sauvegardes/$(date +%Y%m%d-%H%M%S)}"

[ -f "$FICHIER_ENV" ] || echec 1 "le fichier d'environnement manque dans $RACINE : Kr le pose, une session ne le crée pas"
docker network inspect "$SILENEMC_NETWORK" >/dev/null 2>&1 ||
  echec 1 "le réseau Docker « $SILENEMC_NETWORK » n'existe pas : vérifie avec docker network ls et exporte SILENEMC_NETWORK"

# Droits fermés : le dump contient les données des joueurs.
(umask 077 && mkdir -p "$dossier")
dossier="$(cd "$dossier" && pwd)"

# ------------------------------------------------------------------ la base
dire "Dump de la base silooo vers $dossier/silooo.sql.gz"
# Le script du conteneur tire l'hôte et le port de SILOOO_DB_URL (jdbc:mariadb://hôte:port/silooo), puis lance le dump.
# Entre apostrophes : c'est le shell du conteneur qui développe les variables, pas celui-ci.
# shellcheck disable=SC2016
script_dump='
url="${SILOOO_DB_URL#jdbc:mariadb://}"
hote_port="${url%%/*}"
hote="${hote_port%%:*}"
port=3306
case "$hote_port" in *:*) port="${hote_port##*:}" ;; esac
MYSQL_PWD="$SILOOO_DB_PASSWORD" exec mariadb-dump -h "$hote" -P "$port" -u "$SILOOO_DB_USER" \
  --single-transaction --routines --triggers --default-character-set=utf8mb4 silooo
'
(
  umask 077
  docker run --rm --network "$SILENEMC_NETWORK" --env-file "$FICHIER_ENV" --entrypoint sh "$IMAGE_MARIADB" \
    -c "$script_dump" | gzip -c >"$dossier/silooo.sql.gz"
) || echec 1 "le dump de la base a échoué"
# mariadb-dump finit son fichier par cette ligne : sans elle le dump est coupé.
gzip -dc "$dossier/silooo.sql.gz" | tail -n 1 | grep -q 'Dump completed' ||
  echec 1 "le dump de la base est incomplet (pas de ligne « Dump completed »)"
dire "Base sauvegardée."

# ------------------------------------------------------------------ le monde
if docker volume inspect "$VOLUME_MONDE" >/dev/null 2>&1; then
  dire "Archive du volume $VOLUME_MONDE vers $dossier/monde.tar.gz"
  # tar rend 1 quand un fichier change pendant la lecture (le monde est en cours d'écriture) : ce n'est pas une erreur.
  # shellcheck disable=SC2016
  docker run --rm -v "$VOLUME_MONDE":/monde:ro -v "$dossier":/sortie --entrypoint sh "$IMAGE_MARIADB" \
    -c 'tar -C /monde -czf /sortie/monde.tar.gz .; r=$?; chown "$1:$2" /sortie/monde.tar.gz; [ "$r" -le 1 ]' \
    sh "$(id -u)" "$(id -g)" || echec 1 "l'archive du monde a échoué"
  dire "Monde sauvegardé."
else
  dire "Pas de volume $VOLUME_MONDE : premier déploiement, rien à archiver pour le monde."
fi

dire "Sauvegarde terminée :"
ls -l "$dossier"
