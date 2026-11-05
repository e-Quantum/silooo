#!/usr/bin/env bash
# Teste deploy/scripts/deploy.sh et sauvegarde.sh avec un faux docker (et un faux free) placés en tête du PATH :
# rien ne tourne pour de vrai, le journal des appels dit ce que les scripts ont demandé et dans quel ordre.
# Lancé par tools/tests/run.sh ; utilisable seul : bash tools/tests/deploy-deploy.sh
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

REUSSIS=0
ECHECS=0
ok() { REUSSIS=$((REUSSIS + 1)); printf '  ok     %s\n' "$1"; }
ko() {
  ECHECS=$((ECHECS + 1))
  printf '  ECHEC  %s\n' "$1"
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/           /'; fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Aucune variable de l'appelant ne doit changer le résultat.
for nom in $(compgen -v | grep -E '^(FAUX_|DEPLOY_|SILOOO_|SILENEMC_|SEUIL_)' || true); do unset "$nom"; done

# ------------------------------------------------------------------ les faux programmes
FAUX_BIN="$TMP/faux-bin"
mkdir -p "$FAUX_BIN"

cat >"$FAUX_BIN/docker" <<'FAUX'
#!/usr/bin/env bash
# Faux docker : journalise l'appel (avec SILOOO_TAG, que deploy.sh pose pour Compose) et répond selon les FAUX_*.
ETAT="${FAUX_ETAT:?FAUX_ETAT manque}"
appel="$*"
printf 'docker [%s] %s\n' "${SILOOO_TAG:-}" "${appel//$'\n'/ }" >>"$ETAT/appels.log"
case "${1:-}" in
  compose)
    shift
    while [ $# -gt 0 ]; do
      case "$1" in
        -p | --project-directory | -f) shift 2 ;;
        -*) shift ;;
        *) break ;;
      esac
    done
    case "${1:-}" in
      pull) exit "${FAUX_PULL_CODE:-0}" ;;
      run) exit "${FAUX_MIGRATE_CODE:-0}" ;;
      up)
        printf '%s' "${SILOOO_TAG:-}" >"$ETAT/tag-actif"
        exit "${FAUX_UP_CODE:-0}"
        ;;
      ps)
        if [ -f "$ETAT/tag-actif" ]; then echo conteneur-core; fi
        exit 0
        ;;
    esac
    exit 0
    ;;
  network)
    if [ "${FAUX_RESEAU_ABSENT:-}" = 1 ]; then exit 1; fi
    exit 0
    ;;
  volume)
    if [ "${FAUX_VOLUME:-1}" = 1 ]; then exit 0; fi
    exit 1
    ;;
  inspect)
    echo "ghcr.io/e-quantum/silooo-core:$(cat "$ETAT/tag-actif")"
    exit 0
    ;;
  image)
    if [ "${2:-}" = ls ]; then
      cat <<'LISTE'
ghcr.io/e-quantum/silooo-core nouveau123
ghcr.io/e-quantum/silooo-core ancien456
ghcr.io/e-quantum/silooo-core vieux111
ghcr.io/e-quantum/silooo-bds nouveau123
ghcr.io/e-quantum/silooo-bds ancien456
ghcr.io/e-quantum/silooo-bds vieux111
ghcr.io/e-quantum/silooo-core <none>
mariadb 12.3.3
curlimages/curl 8.17.0
bw-image-de-sileneMC latest
LISTE
    fi
    exit 0
    ;;
  run)
    case "$appel" in
      *curlimages/curl*)
        actif=""
        if [ -f "$ETAT/tag-actif" ]; then actif="$(cat "$ETAT/tag-actif")"; fi
        n=0
        if [ -f "$ETAT/fumee-compteur" ]; then n="$(cat "$ETAT/fumee-compteur")"; fi
        n=$((n + 1))
        echo "$n" >"$ETAT/fumee-compteur"
        if [ "$n" -le "${FAUX_FUMEE_ECHECS_AVANT:-0}" ]; then printf 502; exit 0; fi
        if [ "${FAUX_BON_TAG:-}" = '*' ] || [ "$actif" = "${FAUX_BON_TAG:-}" ]; then printf 204; else printf 502; fi
        exit 0
        ;;
      *mariadb-dump*)
        echo "-- MariaDB dump 10.19"
        echo "CREATE TABLE exemple (id int);"
        if [ "${FAUX_DUMP_INCOMPLET:-}" != 1 ]; then echo "-- Dump completed on 2026-11-05  6:00:00"; fi
        exit 0
        ;;
      *"tar -C"*)
        for a in "$@"; do
          case "$a" in *:/sortie) : >"${a%:/sortie}/monde.tar.gz" ;; esac
        done
        exit 0
        ;;
    esac
    exit 0
    ;;
esac
exit 0
FAUX

cat >"$FAUX_BIN/free" <<'FAUX'
#!/usr/bin/env bash
printf 'free %s\n' "$*" >>"${FAUX_ETAT:?}/appels.log"
printf '               total        used        free      shared  buff/cache   available\n'
printf 'Mem:            3900        1500        1000          10        1400        %s\n' "${FAUX_MEM_DISPO:-2000}"
printf 'Swap:              0           0           0\n'
FAUX

cat >"$FAUX_BIN/df" <<'FAUX'
#!/usr/bin/env bash
printf 'df %s\n' "$*" >>"${FAUX_ETAT:?}/appels.log"
printf 'Filesystem      Size  Used Avail Use%% Mounted on\n/dev/sda1        80G   40G   40G  50%% /\n'
FAUX
chmod +x "$FAUX_BIN/docker" "$FAUX_BIN/free" "$FAUX_BIN/df"

# ------------------------------------------------------------------ un projet jetable par scénario
CANARI="canari-secret-9f3a1c77"
NUMERO=0
nouveau_projet() { # pose PROJET (copie de deploy/ et fichier d'environnement avec un canari) et ETAT (état du faux docker)
  NUMERO=$((NUMERO + 1))
  PROJET="$TMP/projet-$NUMERO"
  ETAT="$TMP/etat-$NUMERO"
  mkdir -p "$PROJET" "$ETAT"
  cp -R "$RACINE/deploy" "$PROJET/deploy"
  rm -f "$PROJET/deploy/.tag-courant"
  printf 'SILOOO_API_KEY=%s\nSILOOO_DB_PASSWORD=%s\n' "$CANARI" "$CANARI" >"$PROJET/.env"
  # Le dossier du zip de BDS (décision 26), avec un zip : deploy.sh le veut présent, et avertit s'il est vide.
  mkdir -p "$PROJET/bds"
  : >"$PROJET/bds/bedrock-server-1.26.62.1.zip"
  : >"$ETAT/appels.log"
}
tag_courant() { printf '%s\n' "$1" >"$PROJET/deploy/.tag-courant"; }

# lancer_deploy [VAR=valeur ...] -- <arguments de deploy.sh> : sortie dans $SORTIE, code dans $CODE
lancer_deploy() {
  local reglages=()
  while [ $# -gt 0 ] && [ "$1" != -- ]; do
    reglages+=("$1")
    shift
  done
  shift
  SORTIE="$(env PATH="$FAUX_BIN:$PATH" FAUX_ETAT="$ETAT" DEPLOY_DELAI_FUMEE=0 DEPLOY_PAUSE_FUMEE=0 SILOOO_BDS_DIR="$PROJET/bds" "${reglages[@]}" \
    bash "$PROJET/deploy/scripts/deploy.sh" "$@" 2>&1)"
  CODE=$?
}

journal() { cat "$ETAT/appels.log"; }
compte() { grep -cE -- "$1" "$ETAT/appels.log" || true; }

# ordre <motif>... : chaque motif apparaît dans le journal, à une ligne après celle du motif précédent
ordre() {
  local derniere=0 motif ligne
  ORDRE_ERREUR=""
  for motif in "$@"; do
    ligne="$(awk -v m="$motif" -v d="$derniere" 'NR > d && $0 ~ m { print NR; exit }' "$ETAT/appels.log")"
    if [ -z "$ligne" ]; then
      ORDRE_ERREUR="« $motif » absent après la ligne $derniere du journal"
      return 1
    fi
    derniere=$ligne
  done
}

# interdits_respectes : aucune commande interdite dans le journal, canari absent du journal et de la sortie
interdits_respectes() {
  local description=$1
  if journal | grep -qE 'bw-|huby-'; then
    ko "$description : une commande vise un conteneur bw-* ou huby-*" "$(journal | grep -E 'bw-|huby-')"
  elif journal | grep -qE '^docker \[[^]]*\] (system|stop|kill|restart|exec|logs|rm|rmi|container|compose .* (config|down|stop|rm|restart|kill|exec))'; then
    ko "$description : commande interdite dans le journal" "$(journal | grep -E '^docker \[[^]]*\] (system|stop|kill|restart|exec|logs|rm|rmi|container|compose .* (config|down|stop|rm|restart|kill|exec))')"
  elif journal | grep -qF -- "$CANARI" || printf '%s' "$SORTIE" | grep -qF -- "$CANARI"; then
    ko "$description : le contenu du fichier d'environnement a fuité dans une commande ou la sortie"
  else
    ok "$description : aucune commande interdite, rien du fichier d'environnement dans les commandes ni la sortie"
  fi
}

# ------------------------------------------------------------------ appel sans tag
echo "deploy.sh : appel invalide"
nouveau_projet
lancer_deploy --
if [ "$CODE" -eq 64 ] && [ ! -s "$ETAT/appels.log" ] && printf '%s\n' "$SORTIE" | grep -q "Usage"; then
  ok "sans tag : usage affiché, code 64, aucun appel à docker ni à free"
else
  ko "sans tag : code 64, usage et aucun appel attendus (code $CODE)" "$SORTIE"
fi
for mauvais in '../x' 'a b' '-n' 'latest/../x'; do
  nouveau_projet
  lancer_deploy -- "$mauvais"
  if [ "$CODE" -eq 64 ] && [ ! -s "$ETAT/appels.log" ]; then ok "tag « $mauvais » refusé : code 64, aucun appel"; else ko "tag « $mauvais » devait être refusé (code $CODE)" "$SORTIE"; fi
done
nouveau_projet
lancer_deploy -- un deux
if [ "$CODE" -eq 64 ] && [ ! -s "$ETAT/appels.log" ]; then ok "deux arguments refusés : code 64"; else ko "deux arguments devaient être refusés (code $CODE)"; fi

# ------------------------------------------------------------------ déploiement réussi
echo "deploy.sh : déploiement réussi"
nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_BON_TAG='*' -- nouveau123
if [ "$CODE" -eq 0 ]; then ok "sort en 0"; else ko "devait sortir en 0 (code $CODE)" "$SORTIE"; fi
if ordre '^free' '^df' 'network inspect bw_internal' 'mariadb-dump' 'tar -C' ' pull$' '[]] pull curlimages/curl' \
  ' run --rm -T silooo-migrate$' ' up -d --no-deps silooo-core silooo-bds$' '[]] run .*curlimages/curl' '^free'; then
  ok "ordre : mémoire et disque, sauvegarde (base puis monde), pull, migrations, démarrage, test de fumée, mémoire"
else
  ko "ordre des étapes inattendu : $ORDRE_ERREUR" "$(journal)"
fi
if journal | grep -E ' (pull|run --rm -T silooo-migrate|up -d --no-deps silooo-core silooo-bds)$' | grep -qv '^docker \[nouveau123\] '; then
  ko "chaque commande compose devait porter SILOOO_TAG=nouveau123" "$(journal)"
else
  ok "pull, migrate et up portent SILOOO_TAG=nouveau123"
fi
if journal | grep -E ' compose ' | grep -qv -- "-p silooo --project-directory $PROJET -f $PROJET/deploy/compose.yml"; then
  ko "compose doit être lancé avec -p silooo, la racine du projet et deploy/compose.yml" "$(journal | grep ' compose ')"
else
  ok "compose : -p silooo, --project-directory sur la racine, -f deploy/compose.yml"
fi
if [ "$(compte ' run --rm -T silooo-migrate$')" -eq 1 ]; then ok "une seule migration"; else ko "les migrations devaient tourner une fois" "$(journal)"; fi
if journal | grep -q "\-\-env-file $PROJET/.env .*curlimages/curl" && journal | grep -q "\-\-network silooo "; then
  ok "test de fumée : conteneur curl sur le réseau silooo, fichier d'environnement passé par son chemin"
else
  ko "le test de fumée devait passer le fichier d'environnement par --env-file sur le réseau silooo" "$(journal | grep curlimages)"
fi
if [ "$(cat "$PROJET/deploy/.tag-courant")" = nouveau123 ]; then ok "deploy/.tag-courant porte le nouveau tag"; else ko "deploy/.tag-courant devait contenir nouveau123" "$(cat "$PROJET/deploy/.tag-courant")"; fi
sauvegarde="$(find "$PROJET/sauvegardes" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [ -n "$sauvegarde" ] && [ -s "$sauvegarde/silooo.sql.gz" ] && [ -e "$sauvegarde/monde.tar.gz" ]; then
  ok "sauvegarde horodatée : silooo.sql.gz et monde.tar.gz"
else
  ko "la sauvegarde devait contenir silooo.sql.gz et monde.tar.gz" "$(ls -lR "$PROJET/sauvegardes" 2>&1)"
fi
if gzip -dc "$sauvegarde/silooo.sql.gz" 2>/dev/null | tail -n 1 | grep -q 'Dump completed'; then ok "le dump est lisible et complet"; else ko "le dump devait être un gzip complet"; fi
# Nettoyage : seulement les anciennes images silooo, jamais le tag déployé, le précédent ni une autre image.
retirees="$(journal | grep -E '\] image rm ' | sed 's/.*image rm //' | sort | tr '\n' ' ')"
if [ "$retirees" = "ghcr.io/e-quantum/silooo-bds:vieux111 ghcr.io/e-quantum/silooo-core:vieux111 " ]; then
  ok "nettoyage : seules les anciennes images silooo sont retirées (tag déployé et précédent gardés)"
else
  ko "nettoyage inattendu : $retirees" "$(journal | grep 'image ')"
fi
if journal | grep -E '\] image prune ' | grep -q 'label=org.opencontainers.image.source=https://github.com/e-Quantum/silooo'; then
  ok "les images sans nom ne sont nettoyées que par le label de Silooo"
else
  ko "image prune devait être limité au label de Silooo" "$(journal | grep 'image prune')"
fi
interdits_respectes "déploiement réussi"
if ! printf '%s\n' "$SORTIE" | grep -q "aucun zip de BDS"; then ok "avec un zip dans le dossier de BDS : aucun avertissement sur le zip"; else ko "aucun avertissement sur le zip attendu quand il est là" "$SORTIE"; fi

# ------------------------------------------------------------------ le dossier du zip de BDS (décision 26)
echo "deploy.sh : dossier du zip de BDS"
nouveau_projet
rm -rf "$PROJET/bds"
lancer_deploy FAUX_BON_TAG='*' -- nouveau123
if [ "$CODE" -eq 1 ] && ! journal | grep -q '^docker' && printf '%s\n' "$SORTIE" | grep -q "n'existe pas" && printf '%s\n' "$SORTIE" | grep -q "Le zip de BDS"; then
  ok "dossier du zip de BDS absent : refus avant tout appel à docker, message qui renvoie à la procédure"
else
  ko "refus attendu quand le dossier du zip de BDS n'existe pas (code $CODE)" "$SORTIE"
fi

nouveau_projet
rm -f "$PROJET/bds/"*.zip
lancer_deploy FAUX_BON_TAG='*' -- nouveau123
if [ "$CODE" -eq 0 ] && printf '%s\n' "$SORTIE" | grep -q "aucun zip de BDS"; then
  ok "dossier du zip de BDS vide : avertissement, le déploiement continue (silooo-bds s'arrêtera avec sa marche à suivre)"
else
  ko "un dossier vide devait donner un avertissement sans bloquer (code $CODE)" "$SORTIE"
fi

nouveau_projet
rm -f "$PROJET/bds/"*.zip
mkdir -p "$PROJET/bds/1.26.62.1"
: >"$PROJET/bds/1.26.62.1/.silooo-extrait"
lancer_deploy FAUX_BON_TAG='*' -- nouveau123
if [ "$CODE" -eq 0 ] && ! printf '%s\n' "$SORTIE" | grep -q "aucun zip de BDS"; then
  ok "BDS déjà décompressé (marque .silooo-extrait) : pas d'avertissement sur le zip"
else
  ko "un BDS déjà décompressé ne devait pas donner d'avertissement (code $CODE)" "$SORTIE"
fi

# ------------------------------------------------------------------ test de fumée qui échoue, retour arrière
echo "deploy.sh : test de fumée en échec"
nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_BON_TAG=ancien456 -- nouveau123
if [ "$CODE" -eq 1 ] && printf '%s\n' "$SORTIE" | grep -q "Retour arrière réussi"; then
  ok "échec du test de fumée avec le nouveau tag : retour arrière réussi, code 1"
else
  ko "retour arrière réussi attendu avec le code 1 (code $CODE)" "$SORTIE"
fi
if ordre ' run --rm -T silooo-migrate$' '^docker [[]nouveau123[]] compose .* up -d --no-deps' '[]] run .*curlimages/curl' \
  '^docker [[]ancien456[]] compose .* up -d --no-deps silooo-core silooo-bds$' '[]] run .*curlimages/curl'; then
  ok "ordre : démarrage du nouveau tag, fumée en échec, démarrage de l'ancien tag, fumée"
else
  ko "ordre du retour arrière inattendu : $ORDRE_ERREUR" "$(journal)"
fi
if [ "$(compte ' run --rm -T silooo-migrate$')" -eq 1 ]; then ok "le retour arrière ne rejoue pas les migrations"; else ko "les migrations ne devaient tourner qu'une fois" "$(journal)"; fi
if [ "$(cat "$PROJET/deploy/.tag-courant")" = ancien456 ]; then ok "deploy/.tag-courant reste sur l'ancien tag"; else ko "deploy/.tag-courant devait rester ancien456" "$(cat "$PROJET/deploy/.tag-courant")"; fi
if [ "$(compte 'image rm')" -eq 0 ]; then ok "pas de nettoyage d'images après un déploiement annulé"; else ko "aucun nettoyage attendu après un retour arrière"; fi
interdits_respectes "retour arrière"

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_BON_TAG=aucun -- nouveau123
if [ "$CODE" -eq 2 ] && printf '%s\n' "$SORTIE" | grep -q "NON confirmé"; then
  ok "le retour arrière échoue lui aussi : code 2 et message « NON confirmé »"
else
  ko "code 2 et « NON confirmé » attendus (code $CODE)" "$SORTIE"
fi
if [ "$(cat "$PROJET/deploy/.tag-courant")" = ancien456 ]; then ok "deploy/.tag-courant inchangé"; else ko "deploy/.tag-courant ne devait pas changer"; fi

nouveau_projet
lancer_deploy FAUX_BON_TAG=aucun -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte ' up -d')" -eq 1 ] && printf '%s\n' "$SORTIE" | grep -q "pas de tag précédent"; then
  ok "premier déploiement en échec : pas de retour arrière possible, conteneurs laissés pour les journaux"
else
  ko "sans tag précédent : code 1, un seul up et le message attendus (code $CODE)" "$SORTIE"
fi

nouveau_projet
printf 'ancien456' >"$ETAT/tag-actif"
lancer_deploy FAUX_BON_TAG=ancien456 -- nouveau123
if [ "$CODE" -eq 1 ] && printf '%s\n' "$SORTIE" | grep -q "Retour arrière réussi"; then
  ok "sans deploy/.tag-courant, le tag précédent est lu sur le conteneur silooo-core en cours"
else
  ko "le tag précédent devait venir du conteneur en cours (code $CODE)" "$SORTIE"
fi

nouveau_projet
tag_courant nouveau123
lancer_deploy FAUX_BON_TAG=aucun -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte ' up -d')" -eq 1 ]; then ok "même tag que le courant : pas de retour arrière vers lui-même"; else ko "un seul up attendu quand le tag précédent est le même (code $CODE)" "$(journal)"; fi

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_BON_TAG='*' FAUX_FUMEE_ECHECS_AVANT=2 DEPLOY_DELAI_FUMEE=30 -- nouveau123
if [ "$CODE" -eq 0 ] && [ "$(compte '\] run .*curlimages/curl')" -eq 3 ]; then ok "le test de fumée réessaie : 502, 502 puis 204, déploiement réussi"; else ko "trois essais de fumée attendus (code $CODE)" "$(journal | grep curlimages)"; fi

# ------------------------------------------------------------------ refus avant toute action
echo "deploy.sh : refus et échecs avant le démarrage"
nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_MEM_DISPO=500 -- nouveau123
if [ "$CODE" -eq 1 ] && ! journal | grep -q '^docker' && journal | grep -q '^free' && journal | grep -q '^df'; then
  ok "mémoire disponible sous 800 Mo : refus, free et df lus, aucun appel à docker"
else
  ko "refus attendu sans aucun appel à docker (code $CODE)" "$(journal)"
fi

nouveau_projet
rm -f "$PROJET/.env"
lancer_deploy FAUX_BON_TAG='*' -- nouveau123
if [ "$CODE" -eq 1 ] && ! journal | grep -q '^docker'; then ok "fichier d'environnement absent : refus avant tout appel à docker"; else ko "refus attendu quand le fichier d'environnement manque (code $CODE)" "$SORTIE"; fi

nouveau_projet
lancer_deploy FAUX_RESEAU_ABSENT=1 -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte 'compose')" -eq 0 ] && printf '%s\n' "$SORTIE" | grep -q "docker network ls"; then
  ok "réseau de SileneMC introuvable : refus, message qui renvoie à docker network ls"
else
  ko "refus attendu quand le réseau n'existe pas (code $CODE)" "$SORTIE"
fi

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_DUMP_INCOMPLET=1 -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte ' pull$')" -eq 0 ] && [ "$(compte 'silooo-migrate')" -eq 0 ] && [ "$(compte ' up -d')" -eq 0 ]; then
  ok "dump incomplet : arrêt avant le pull, les migrations et le démarrage"
else
  ko "un dump incomplet devait tout arrêter (code $CODE)" "$SORTIE"
fi

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_PULL_CODE=1 -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte 'silooo-migrate')" -eq 0 ] && [ "$(compte ' up -d')" -eq 0 ] && [ "$(cat "$PROJET/deploy/.tag-courant")" = ancien456 ]; then
  ok "pull en échec : ni migration ni démarrage, tag courant inchangé"
else
  ko "un pull en échec devait tout arrêter (code $CODE)" "$SORTIE"
fi

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_MIGRATE_CODE=1 -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte ' up -d')" -eq 0 ] && [ "$(cat "$PROJET/deploy/.tag-courant")" = ancien456 ]; then
  ok "migration en échec : aucun démarrage, tag courant inchangé"
else
  ko "une migration en échec devait empêcher le démarrage (code $CODE)" "$SORTIE"
fi

nouveau_projet
tag_courant ancien456
lancer_deploy FAUX_UP_CODE=1 -- nouveau123
if [ "$CODE" -eq 1 ] && [ "$(compte '\] run .*curlimages/curl')" -eq 0 ] && [ "$(cat "$PROJET/deploy/.tag-courant")" = ancien456 ]; then
  ok "démarrage en échec : pas de test de fumée, tag courant inchangé"
else
  ko "un démarrage en échec devait s'arrêter là (code $CODE)" "$SORTIE"
fi

# ------------------------------------------------------------------ sauvegarde.sh seul
echo "sauvegarde.sh"
nouveau_projet
SORTIE="$(env PATH="$FAUX_BIN:$PATH" FAUX_ETAT="$ETAT" FAUX_VOLUME=0 bash "$PROJET/deploy/scripts/sauvegarde.sh" "$TMP/sortie-sauvegarde" 2>&1)"
CODE=$?
if [ "$CODE" -eq 0 ] && [ -s "$TMP/sortie-sauvegarde/silooo.sql.gz" ] && [ ! -e "$TMP/sortie-sauvegarde/monde.tar.gz" ] && printf '%s\n' "$SORTIE" | grep -q "premier déploiement"; then
  ok "sans volume du monde : la base seule est sauvegardée, le message le dit"
else
  ko "sans volume : silooo.sql.gz seul et le message « premier déploiement » attendus (code $CODE)" "$SORTIE"
fi
interdits_respectes "sauvegarde seule"
if journal | grep -q -- "--env-file $PROJET/.env .*MYSQL_PWD=\"\$SILOOO_DB_PASSWORD\" exec mariadb-dump" &&
  ! journal | grep mariadb-dump | grep -qE -- '--password|[[:space:]]-p[^[:space:]]'; then
  ok "le dump lit son mot de passe dans l'environnement du conteneur (--env-file), jamais dans une option de ligne de commande"
else
  ko "le dump devait lire ses identifiants par --env-file" "$(journal | grep mariadb)"
fi

# ------------------------------------------------------------------ contrôles statiques
echo "Scripts de deploy/ : contrôles statiques"
mauvais_syntaxe=0
for script in "$RACINE"/deploy/bds/start.sh "$RACINE"/deploy/scripts/*.sh; do
  if ! bash -n "$script" 2>/dev/null; then ko "bash -n refuse ${script#"$RACINE"/}"; mauvais_syntaxe=1; fi
done
if [ "$mauvais_syntaxe" -eq 0 ]; then ok "bash -n accepte chaque script de deploy/"; fi
for script in deploy/bds/start.sh deploy/scripts/deploy.sh deploy/scripts/sauvegarde.sh; do
  if grep -q '^set -euo pipefail$' "$RACINE/$script"; then ok "$script : set -euo pipefail"; else ko "$script devrait commencer par set -euo pipefail"; fi
done
for script in deploy/scripts/commun.sh deploy/scripts/deploy.sh deploy/scripts/sauvegarde.sh; do
  if grep -qE '^#.*bw-' "$RACINE/$script" && grep -qE '^#.*huby-' "$RACINE/$script"; then
    ok "$script : l'interdit sur les conteneurs bw-* et huby-* est écrit en commentaire"
  else
    ko "$script devrait écrire en commentaire l'interdit sur les conteneurs bw-* et huby-*"
  fi
done
interdits_code=0
for script in deploy/bds/start.sh deploy/scripts/commun.sh deploy/scripts/deploy.sh deploy/scripts/sauvegarde.sh; do
  # Les lignes de commentaire sont exclues : elles citent justement ces commandes pour les interdire.
  trouve="$(grep -nE 'set -x|system prune|compose .*config|(^|[;&|(])[[:space:]]*(sudo[[:space:]]+)?docker[[:space:]]+(stop|kill|restart|exec|logs|rm|rmi)[[:space:]]|bw-|huby-|cat .*\.env|source .*\.env' "$RACINE/$script" | grep -vE '^[0-9]+:[[:space:]]*#' || true)"
  if [ -n "$trouve" ]; then ko "$script contient une commande interdite" "$trouve"; interdits_code=1; fi
done
if [ "$interdits_code" -eq 0 ]; then ok "aucun script ne contient de commande interdite (set -x, system prune, compose config, bw-*, huby-*, lecture du fichier d'environnement)"; fi

echo
printf 'deploy.sh et sauvegarde.sh : %s réussi(s), %s en échec.\n' "$REUSSIS" "$ECHECS"
if [ "$ECHECS" -gt 0 ]; then exit 1; fi
exit 0
