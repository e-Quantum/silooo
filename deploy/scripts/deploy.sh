#!/usr/bin/env bash
# Déploie un tag des images silooo sur le VPS (docs/deploiement.md, « Déployer par tag »).
#
#   bash deploy/scripts/deploy.sh <tag>      le tag est le SHA d'un commit de main (le même pour silooo-core et silooo-bds)
#
# À lancer sur le VPS depuis le dossier du projet (/var/www/silooo), avec Kr, après une vérification verte et jamais
# sans que la base, le port et le DNS soient posés. Les étapes, dans l'ordre, chacune s'arrête net si elle échoue :
#   1. mémoire et disque : free -m, df -h /, refus sous 800 Mo de mémoire disponible
#   2. le tag courant (deploy/.tag-courant) est noté comme point de retour
#   3. sauvegarde de la base et du monde (sauvegarde.sh) vers sauvegardes/<horodatage>
#   4. docker compose pull
#   5. docker compose run --rm silooo-migrate
#   6. docker compose up -d silooo-core silooo-bds
#   7. test de fumée : POST /v1/server/heartbeat avec la clé, 204 attendu dans les 60 s ; sinon retour au tag précédent
#   8. free -m après, avertissement sous 800 Mo ; nettoyage des seules images silooo
#
# Codes de sortie : 0 déployé et confirmé ; 1 refus ou échec avant le démarrage, ou déploiement annulé par le retour
# arrière ; 2 le retour arrière lui-même n'est pas confirmé ; 64 mauvais appel.
#
# INTERDITS ABSOLUS (reprise de commun.sh, parce qu'ils valent pour chaque ligne de ce fichier) :
#   - aucune commande sur un conteneur bw-* ou huby-* ;
#   - aucune lecture ni affichage du fichier d'environnement : Docker le lit, ce script jamais ;
#   - jamais « docker system prune » : seulement les images silooo-core et silooo-bds.
set -euo pipefail

# shellcheck source=commun.sh
source "$(dirname "${BASH_SOURCE[0]}")/commun.sh"

# Réglages du test de fumée (docs/deploiement.md : 60 s). Les tests de tools/tests/ les raccourcissent.
DELAI_FUMEE="${DEPLOY_DELAI_FUMEE:-60}"
PAUSE_FUMEE="${DEPLOY_PAUSE_FUMEE:-3}"

usage() {
  printf 'Usage : bash deploy/scripts/deploy.sh <tag>\n  <tag> : le SHA du commit de main dont la CI a poussé les images (ghcr.io/e-quantum/silooo-core et silooo-bds)\n' >&2
  exit 64
}

case "${1:-}" in
  '') usage ;;
  -h | --help) usage ;;
esac
[ $# -eq 1 ] || usage
TAG="$1"
# Les règles des tags Docker : 128 caractères au plus, ni espace ni barre.
if ! [[ $TAG =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echec 64 "« $TAG » n'est pas un tag Docker valable"
fi

etape() { printf '\n=== %s ===\n' "$*"; }

# ------------------------------------------------------------------ 1. mémoire et disque
etape "1. Mémoire et disque avant le déploiement"
free -m
df -h /
dispo="$(memoire_disponible_mo)"
if [ "$dispo" -lt "$SEUIL_MEMOIRE_MO" ]; then
  echec 1 "mémoire disponible $dispo Mo, sous le seuil de $SEUIL_MEMOIRE_MO Mo : je ne déploie pas (le VPS partage sa mémoire avec Hubyy et SileneMC)"
fi

command -v docker >/dev/null 2>&1 || echec 1 "docker est introuvable (lance le script avec sudo si ton compte n'est pas dans le groupe docker)"
[ -f "$COMPOSE_FICHIER" ] || echec 1 "compose introuvable : $COMPOSE_FICHIER (les fichiers de deploy/ ne sont pas là)"
# Seule l'existence est testée : le contenu n'est jamais ouvert ici.
[ -f "$FICHIER_ENV" ] || echec 1 "le fichier d'environnement manque dans $RACINE : Kr le pose, une session ne le crée pas"
# Le dossier du zip de BDS (décision 26) : le compose le monte sur /data/bds sans le créer. Sans lui, « up » échouerait
# après les migrations. Seul le système de fichiers est lu ici, jamais le fichier d'environnement : SILOOO_BDS_DIR vient
# de l'environnement de la session (comme SILENEMC_NETWORK), sinon du défaut du compose.
if [ ! -d "$SILOOO_BDS_DIR" ]; then
  echec 1 "le dossier du zip de BDS n'existe pas : $SILOOO_BDS_DIR. Crée-le (mkdir -p, puis chown 10001:10001) et pose-y le zip de BDS : docs/deploiement.md, « Le zip de BDS »"
fi
verifier_dossier_bds
docker network inspect "$SILENEMC_NETWORK" >/dev/null 2>&1 ||
  echec 1 "le réseau Docker « $SILENEMC_NETWORK » n'existe pas : vérifie avec docker network ls et exporte SILENEMC_NETWORK"

# ------------------------------------------------------------------ 2. le tag courant, pour le retour arrière
etape "2. Tag courant"
precedent=""
if [ -s "$FICHIER_TAG" ]; then
  precedent="$(head -n 1 "$FICHIER_TAG")"
else
  # Pas de fichier : on demande à Docker quelle image tourne pour silooo-core.
  TAG_CIBLE="$TAG"
  conteneur="$(compose ps -q silooo-core 2>/dev/null | head -n 1 || true)"
  if [ -n "$conteneur" ]; then
    image="$(docker inspect --format '{{.Config.Image}}' "$conteneur" 2>/dev/null || true)"
    precedent="${image##*:}"
  fi
fi
if [ -n "$precedent" ] && ! [[ $precedent =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  alerte "le tag noté dans $FICHIER_TAG n'est pas valable : pas de retour arrière possible"
  precedent=""
fi
if [ -n "$precedent" ]; then
  dire "Tag courant : $precedent (retour arrière vers lui si le test de fumée échoue)"
else
  dire "Aucun tag courant connu : premier déploiement, pas de retour arrière possible"
fi

# ------------------------------------------------------------------ 3. sauvegarde
etape "3. Sauvegarde de la base et du monde"
dossier_sauvegarde="$RACINE/sauvegardes/$(date +%Y%m%d-%H%M%S)"
bash "$DEPLOY_DIR/scripts/sauvegarde.sh" "$dossier_sauvegarde" ||
  echec 1 "la sauvegarde a échoué : rien n'a été déployé ni touché"

# ------------------------------------------------------------------ 4. tirage des images
etape "4. Tirage des images du tag $TAG"
TAG_CIBLE="$TAG"
# Le registre GitHub est privé : le VPS doit avoir fait « docker login ghcr.io » avec la clé de lecture de Kr.
compose pull || echec 1 "le tirage des images a échoué (docker login ghcr.io fait par Kr ? tag poussé par la CI ?) : rien n'a changé"
# L'image du test de fumée est tirée maintenant, pour que les 60 s ne comptent pas son téléchargement.
docker pull "$IMAGE_CURL" >/dev/null || echec 1 "le tirage de $IMAGE_CURL a échoué : rien n'a changé"

# ------------------------------------------------------------------ 5. migrations
etape "5. Migrations de la base (silooo-migrate)"
compose run --rm -T silooo-migrate ||
  echec 1 "les migrations ont échoué : les services en cours n'ont pas été touchés, la base peut être à moitié migrée (lis les journaux ci-dessus)"

# ------------------------------------------------------------------ 6. démarrage
etape "6. Démarrage de silooo-core et silooo-bds"
# silooo-bds s'arrête de lui-même, avec un message pour Kr, si le zip de BDS manque ou n'a pas la bonne somme : le test de
# fumée ci-dessous ne vérifie que silooo-core, il faut lire « docker logs » de silooo-bds au premier déploiement.
# --no-deps : les migrations viennent de tourner à l'étape 5, inutile de les rejouer. Le compose garde son
# depends_on pour un « up » lancé à la main.
compose up -d --no-deps silooo-core silooo-bds ||
  echec 1 "le démarrage a échoué : regarde docker ps --filter name=silooo- et docker logs (le dossier $SILOOO_BDS_DIR, monté sur /data/bds, doit exister)"

# ------------------------------------------------------------------ 7. test de fumée
# POST /v1/server/heartbeat avec la clé, depuis un conteneur curl sur le réseau silooo (l'image de silooo-core n'a
# pas curl). La clé vient de l'environnement du conteneur, par --env-file : elle n'est sur aucune ligne de commande.
# L'en-tête X-Silooo-Key est écrit par le shell du conteneur et lu par curl sur son entrée standard (-H @-).
# shellcheck disable=SC2016
script_fumee='
printf "X-Silooo-Key: %s\n" "$SILOOO_API_KEY" |
  curl -sS -m 5 -o /dev/null -w "%{http_code}" -X POST -H @- -H "Content-Type: application/json" \
    -d "{\"serverId\":\"deploy-fumee\",\"onlineXuids\":[]}" http://silooo-core:8080/v1/server/heartbeat
'
# fumee : 0 si le heartbeat répond 204 avant la fin du délai, 1 sinon.
fumee() {
  local fin=$((SECONDS + DELAI_FUMEE)) code=""
  while :; do
    code="$(docker run --rm --network "$RESEAU_SILOOO" --env-file "$FICHIER_ENV" --entrypoint sh "$IMAGE_CURL" \
      -c "$script_fumee" 2>/dev/null)" || true
    if [ "$code" = 204 ]; then return 0; fi
    if [ "$SECONDS" -ge "$fin" ]; then break; fi
    sleep "$PAUSE_FUMEE"
  done
  dire "Dernière réponse du heartbeat : ${code:-aucune}"
  return 1
}

etape "7. Test de fumée : POST /v1/server/heartbeat, 204 attendu en moins de $DELAI_FUMEE s"
if ! fumee; then
  alerte "le test de fumée a échoué avec le tag $TAG"
  if [ -z "$precedent" ]; then
    echec 1 "pas de tag précédent : je laisse les conteneurs en l'état pour lire leurs journaux avec docker logs, sur un conteneur silooo- seulement"
  fi
  if [ "$precedent" = "$TAG" ]; then
    echec 1 "le tag précédent est le même que celui qui vient d'échouer : pas de retour arrière utile, lis les journaux"
  fi
  etape "Retour arrière vers le tag $precedent (même compose, sans migration : la base ne se défait pas)"
  TAG_CIBLE="$precedent"
  compose up -d --no-deps silooo-core silooo-bds ||
    echec 2 "le retour arrière n'a pas pu démarrer le tag $precedent : intervention de Kr, docker ps --filter name=silooo-"
  if fumee; then
    dire "Retour arrière réussi : le tag $precedent répond. Le tag $TAG n'est pas déployé."
    dire "À noter dans le journal de docs/passation.md, avec la cause."
    exit 1
  fi
  echec 2 "retour arrière vers $precedent NON confirmé : le heartbeat ne répond pas non plus. Intervention de Kr."
fi

printf '%s\n' "$TAG" >"$FICHIER_TAG"
dire "Test de fumée réussi. Tag courant : $TAG"

# ------------------------------------------------------------------ 8. après
etape "8. Mémoire après le déploiement et nettoyage"
free -m
dispo="$(memoire_disponible_mo)"
if [ "$dispo" -lt "$SEUIL_MEMOIRE_MO" ]; then
  alerte "mémoire disponible $dispo Mo, sous le seuil de $SEUIL_MEMOIRE_MO Mo : arrête les conteneurs silooo et note-le dans docs/passation.md"
fi

# Les images des anciens tags : celles de silooo-core et silooo-bds qui ne sont ni le tag déployé ni le précédent
# (gardé pour un retour arrière sans réseau). Pas de « -f » : Docker refuse d'enlever une image en cours d'usage.
while read -r depot etiquette; do
  [ -n "$depot" ] || continue
  if [ "$etiquette" = "<none>" ] || [ "$etiquette" = "$TAG" ] || [ "$etiquette" = "$precedent" ]; then continue; fi
  docker image rm "$depot:$etiquette" >/dev/null 2>&1 || true
done < <(docker image ls --format '{{.Repository}} {{.Tag}}' | awk -v a="$IMAGE_CORE" -v b="$IMAGE_BDS" '$1 == a || $1 == b')
# Les images sans nom qui portent le label de Silooo, rien d'autre : jamais « docker system prune ».
docker image prune -f --filter "label=$LABEL_SILOOO" || true

dire "Déploiement du tag $TAG terminé."
