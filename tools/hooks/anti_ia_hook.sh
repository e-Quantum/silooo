#!/usr/bin/env bash
# Hook PostToolUse de Claude Code (.claude/settings.json) : après chaque écriture, relit les textes
# vus par les joueurs. Claude Code envoie le JSON du hook sur l'entrée standard ; anti_ia.py --hook
# sort en 2 s'il trouve un tic d'IA, et Claude voit alors la sortie d'erreur.
# Sans Python, le hook ne bloque personne : il prévient et sort en 0.
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../lib/python.sh
source "$RACINE/tools/lib/python.sh"

if ! trouver_python; then
  echo "anti_ia : aucun Python trouvé (python3, python, uv), le filtre anti-IA n'a pas tourné." >&2
  exit 0
fi

# exec : le code de sortie de anti_ia.py (0 ou 2) devient celui du hook.
exec "${PYTHON[@]}" "$RACINE/tools/anti_ia.py" --hook
