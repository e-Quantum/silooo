#!/usr/bin/env bash
# Vérifie tout le dépôt avant de rendre la main ; la CI lance la même commande.
# Usage : bash tools/verifier.sh [--sans-liens]
#   --sans-liens   saute le contrôle des liens relatifs de la documentation
# Marche dans Git Bash (Windows) et sur Ubuntu. Code de sortie 1 si une étape échoue.
set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RACINE"
# shellcheck source=lib/python.sh
source tools/lib/python.sh

SANS_LIENS=0
for argument in "$@"; do
  case "$argument" in
    --sans-liens) SANS_LIENS=1 ;;
    -h | --help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "Option inconnue : $argument (essaie --sans-liens)" >&2; exit 64 ;;
  esac
done

RESUME=()
ECHECS=0

# Une étape = un titre, une fonction, un résultat. Appelée dans un « || », une fonction perd set -e :
# chacune enchaîne donc ses commandes avec « || return 1 ».
etape() {
  local titre="$1" debut=$SECONDS statut=0
  shift
  printf '\n=== %s ===\n' "$titre"
  "$@" || statut=$?
  if [ "$statut" -eq 0 ]; then
    RESUME+=("OK       $titre ($((SECONDS - debut)) s)")
  else
    RESUME+=("ÉCHEC    $titre")
    ECHECS=$((ECHECS + 1))
  fi
}

ignorer() {
  printf '\n=== %s ===\nIgnoré : %s\n' "$1" "$2"
  RESUME+=("IGNORÉ   $1 ($2)")
}

etape_node() {
  command -v node >/dev/null 2>&1 || { echo "Node introuvable : installe Node 22 ou plus."; return 1; }
  # node_modules/.package-lock.json est écrit par npm à la fin de chaque installation.
  if [ ! -d node_modules ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    npm ci --no-audit --no-fund || return 1
  else
    echo "node_modules est à jour."
  fi
}

etape_definitions() {
  node tools/verifier_definitions.mjs
}

etape_types_addon() {
  node tools/generer_types.mjs --verifier
}

etape_plan_discord() {
  node tools/verifier_plan_discord.mjs
}

etape_anti_ia() {
  trouver_python || { echo "Python introuvable : essayé python3, python, uv run python."; return 1; }
  "${PYTHON[@]}" tools/anti_ia.py --tout
}

# Liens Markdown « ](chemin) » : on ignore http, https, mailto et les ancres seules ; l'ancre « #... »
# est retirée, puis le chemin se résout depuis le dossier du fichier qui le contient.
etape_liens() {
  local fichiers=() f morts=0 total=0 ligne numero cible chemin
  for f in README.md CLAUDE.md AGENTS.md; do
    if [ -f "$f" ]; then fichiers+=("$f"); fi
  done
  while IFS= read -r f; do fichiers+=("$f"); done < <(find docs design .claude/skills -name '*.md' 2>/dev/null | sort)
  for f in "${fichiers[@]}"; do
    while IFS= read -r ligne; do
      numero="${ligne%%:*}"
      cible="${ligne#*:](}"
      case "$cible" in http://* | https://* | mailto:* | '#'* | '') continue ;; esac
      chemin="${cible%%#*}"
      chemin="${chemin%%\?*}"
      chemin="${chemin#<}"
      chemin="${chemin%>}"
      if [ -z "$chemin" ]; then continue; fi
      case "$chemin" in *%*) chemin="$(printf '%b' "${chemin//%/\\x}")" ;; esac
      total=$((total + 1))
      case "$chemin" in
        /*) cible_reelle="$RACINE$chemin" ;;
        *) cible_reelle="$(dirname "$f")/$chemin" ;;
      esac
      if [ ! -e "$cible_reelle" ]; then
        echo "$f:$numero: lien mort : $cible"
        morts=$((morts + 1))
      fi
    done < <(grep -noE '\]\([^) ]+' "$f" || true)
  done
  echo "${#fichiers[@]} fichier(s) Markdown, $total lien(s) relatif(s), $morts lien(s) mort(s)."
  if [ "$morts" -eq 0 ]; then return 0; else return 1; fi
}

# Les deux suivantes tournent dans un sous-shell : le « cd » ne doit pas changer le dossier du script.
etape_gradle() {
  (
    cd core || exit 1
    if [ -x ./gradlew ]; then ./gradlew test --console=plain; else sh ./gradlew test --console=plain; fi
  )
}

etape_addon() {
  (
    cd addon || exit 1
    npm ci --no-audit --no-fund && npm test && npm run build
  )
}

etape_resource_pack() {
  node tools/verifier_pack.mjs
}

# Les pages du site rendues par les tests Gradle (core/build/site-rendu), ouvertes dans Chromium par Playwright :
# débordement, erreurs de console, requêtes en échec, images sans alt. Sans Chromium sur un poste, l'étape est ignorée
# (la sonde répond 3) ; en CI, le workflow installe Chromium et son absence est un échec.
etape_site() {
  node tools/verifier_site.mjs
}

etape_tests_outillage() {
  bash tools/tests/run.sh
}

etape "Dépendances Node" etape_node
etape "Définitions : schémas et contrôles croisés" etape_definitions
etape "Types de l'add-on à jour" etape_types_addon
etape "Discord : plan du serveur" etape_plan_discord
etape "Anti-IA : textes vus par les joueurs" etape_anti_ia
if [ "$SANS_LIENS" -eq 1 ]; then
  ignorer "Liens relatifs de la documentation" "option --sans-liens"
else
  etape "Liens relatifs de la documentation" etape_liens
fi
if [ -f core/gradlew ]; then
  etape "silooo-core : tests Gradle" etape_gradle
else
  ignorer "silooo-core : tests Gradle" "pas de core/gradlew"
fi
if [ ! -d core/build/site-rendu ]; then
  ignorer "Site : pages dans Chromium" "pas de core/build/site-rendu (les tests Gradle ne l'ont pas écrit)"
else
  SONDE=0
  node tools/verifier_site.mjs --sonder || SONDE=$?
  if [ "$SONDE" -eq 3 ]; then
    ignorer "Site : pages dans Chromium" "Chromium de Playwright absent (npx playwright install chromium)"
  else
    etape "Site : pages dans Chromium" etape_site
  fi
fi
if [ -f addon/package.json ]; then
  etape "Add-on : tests et build" etape_addon
else
  ignorer "Add-on : tests et build" "pas de addon/package.json"
fi
if [ -d resource-pack ]; then
  etape "Pack de ressources : JSON, copies de design/, Oooscar" etape_resource_pack
else
  ignorer "Pack de ressources : JSON, copies de design/, Oooscar" "pas de resource-pack/"
fi
etape "Tests de l'outillage" etape_tests_outillage

printf '\n=== Résumé ===\n'
printf '%s\n' "${RESUME[@]}"
if [ "$ECHECS" -gt 0 ]; then
  echo "$ECHECS étape(s) en échec."
  exit 1
fi
echo "Tout est vert."
