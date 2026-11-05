#!/usr/bin/env bash
# État du dépôt en quelques lignes, sans réseau : lancé au démarrage de chaque session Claude
# (hook SessionStart) et utilisable à la main. Ne modifie rien.
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RACINE" || exit 0

echo "== État de Silooo =="
echo "Branche            : $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'inconnue')"
echo "Dernier commit     : $(git log -1 --format='%h %s (%cr)' 2>/dev/null || echo 'aucun')"

# sort -V : V10 vient après V9, ce que l'ordre alphabétique ne sait pas faire.
migration="$(ls core/src/main/resources/db/migration 2>/dev/null | grep -E '^V[0-9]+__.*\.sql$' | sort -V | tail -n 1)"
echo "Dernière migration : ${migration:-aucune}"

echo "Disque libre       : $(df -h . 2>/dev/null | awk 'NR==2 {print $4 " sur " $2 " (" $5 " utilisés)"}')"
if command -v free >/dev/null 2>&1; then
  echo "Mémoire libre      : $(free -m | awk '/^Mem:/ {print $7 " Mo disponibles sur " $2 " Mo"}')"
fi
echo "Lis docs/README.md et docs/passation.md ; avant de rendre la main : tools/verifier.sh"
exit 0
