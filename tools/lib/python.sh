#!/usr/bin/env bash
# Trouve un Python utilisable et le met dans PYTHON, un tableau bash à lancer ainsi :
#   source tools/lib/python.sh && trouver_python && "${PYTHON[@]}" tools/anti_ia.py ...
# Pourquoi pas juste « python3 » : sous Windows, python3 est souvent un raccourci du Store qui échoue ;
# sur le PC de Kr, seul « uv run python » marche. On essaie donc dans l'ordre, et on vérifie que la
# commande exécute vraiment du code en Python 3.10 ou plus (le minimum de tools/anti_ia.py).
# Un tableau bash ne s'exporte pas : PYTHON_CMD en donne la version chaîne pour les sous-processus.

_python_marche() {
  "$@" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' </dev/null >/dev/null 2>&1
}

trouver_python() {
  local candidat
  # Un candidat par ligne, mots séparés par des espaces : la commande n'a pas d'espace dans ses arguments.
  while IFS= read -r candidat; do
    # shellcheck disable=SC2206
    local commande=($candidat)
    command -v "${commande[0]}" >/dev/null 2>&1 || continue
    if _python_marche "${commande[@]}"; then
      PYTHON=("${commande[@]}")
      PYTHON_CMD="${commande[*]}"
      export PYTHON_CMD
      return 0
    fi
  done <<'LISTE'
python3
python
uv run --quiet python
LISTE
  PYTHON=()
  PYTHON_CMD=""
  return 1
}
