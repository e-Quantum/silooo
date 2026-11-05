#!/usr/bin/env bash
# shellcheck disable=SC2034 # les variables servent aux scripts qui sourcent ce fichier
# Réglages et petites fonctions communs à deploy.sh et sauvegarde.sh. À sourcer, pas à lancer.
# Les chiffres d'ici sont des réglages d'exploitation (docs/deploiement.md, ADR 0003 et 0004), pas des chiffres du jeu.
#
# INTERDITS ABSOLUS, valables pour tous les scripts de deploy/scripts/ :
#   1. Aucune commande sur un conteneur dont le nom commence par bw- ou huby- (SileneMC et Hubyy doivent rester en ligne).
#      Aucun « docker stop », « rm », « restart », « exec » ni « logs » qui ne vise pas un conteneur ou un volume silooo.
#   2. Aucune lecture ni affichage du fichier d'environnement du projet. Il n'est jamais ouvert ici : on le passe à Docker
#      par son chemin (env_file du compose, --env-file de « docker run »), comme le fait Compose lui-même.
#      Jamais de « set -x », jamais de « docker compose config » (il imprimerait les secrets).
#   3. Jamais « docker system prune » : le nettoyage se limite aux images silooo-core et silooo-bds.

# La racine du projet Compose : deploy/ est un sous-dossier, et le fichier d'environnement de Kr est posé à côté.
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="$RACINE/deploy"
COMPOSE_FICHIER="$DEPLOY_DIR/compose.yml"
FICHIER_ENV="$RACINE/.env"
FICHIER_TAG="$DEPLOY_DIR/.tag-courant"
PROJET=silooo

# Le réseau Docker de SileneMC (MariaDB et Redis). Même défaut que deploy/compose.yml : une hypothèse que Kr confirme
# avec « docker network ls ». S'il le change dans son fichier d'environnement, il doit l'exporter aussi dans sa session.
SILENEMC_NETWORK="${SILENEMC_NETWORK:-silenemc_default}"
RESEAU_SILOOO=silooo
VOLUME_MONDE=silooo-bds-worlds

IMAGE_CORE=ghcr.io/e-quantum/silooo-core
IMAGE_BDS=ghcr.io/e-quantum/silooo-bds
# Épinglées par tag, comme les images de la CI. MariaDB : la même version que SileneMC (ADR 0003).
IMAGE_MARIADB=mariadb:12.3.3
IMAGE_CURL=curlimages/curl:8.17.0
# Le label que la CI et le Dockerfile de silooo-bds posent sur nos images : le nettoyage ne vise que lui.
LABEL_SILOOO="org.opencontainers.image.source=https://github.com/e-Quantum/silooo"

# docs/deploiement.md, « Mémoire et disque » : en dessous, les conteneurs silooo s'arrêtent.
SEUIL_MEMOIRE_MO="${SEUIL_MEMOIRE_MO:-800}"

dire() { printf '%s\n' "$*"; }
alerte() { printf 'ATTENTION : %s\n' "$*" >&2; }
echec() {
  local code=$1
  shift
  printf 'ERREUR : %s\n' "$*" >&2
  exit "$code"
}

# La mémoire disponible en Mo : la colonne « available » de « free -m ».
memoire_disponible_mo() {
  local valeur
  valeur="$(free -m | awk '/^Mem:/ { print $7 }')"
  if ! [[ $valeur =~ ^[0-9]+$ ]]; then echec 1 "impossible de lire la mémoire disponible dans la sortie de free -m"; fi
  printf '%s\n' "$valeur"
}

# Le compose du projet silooo. Le dossier de projet est la racine (là où vit le fichier d'environnement), pas deploy/.
# TAG_CIBLE est le tag des images pour cet appel ; il ne vient jamais du fichier d'environnement.
TAG_CIBLE=""
compose() {
  SILOOO_TAG="$TAG_CIBLE" docker compose -p "$PROJET" --project-directory "$RACINE" -f "$COMPOSE_FICHIER" "$@"
}
