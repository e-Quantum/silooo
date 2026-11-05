#!/usr/bin/env bash
# Hook Stop de Claude Code : rappelle de lancer le vérificateur quand des fichiers ont changé.
# Il informe seulement et sort toujours en 0 : il ne doit jamais empêcher une session de finir.
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -n "$(git -C "$RACINE" status --porcelain 2>/dev/null)" ]; then
  echo "Des fichiers ont changé : lance tools/verifier.sh avant de rendre la main"
fi
exit 0
