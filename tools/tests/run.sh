#!/usr/bin/env bash
# Tests de l'outillage : anti_ia.py (règles, JSON, hook), verifier_definitions.mjs, les scripts, et les fichiers de deploy/.
# Lancé par tools/verifier.sh ; utilisable seul : bash tools/tests/run.sh
set -uo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$RACINE"
# shellcheck source=../lib/python.sh
source tools/lib/python.sh
if ! trouver_python; then
  echo "Python introuvable : impossible de tester anti_ia.py."
  exit 1
fi

REUSSIS=0
ECHECS=0
ok() { REUSSIS=$((REUSSIS + 1)); printf '  ok     %s\n' "$1"; }
ko() {
  ECHECS=$((ECHECS + 1))
  printf '  ECHEC  %s\n' "$1"
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/           /'; fi
}
anti_ia() { "${PYTHON[@]}" tools/anti_ia.py "$@"; }

# Un dossier temporaire par passage, nettoyé à la sortie. Les chemins « natifs » (C:/...) servent aux
# programmes Windows ; sous Linux, cygpath n'existe pas et le chemin reste tel quel.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
natif() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
TMP_NATIF="$(natif "$TMP")"

# ------------------------------------------------------------------ anti_ia.py : fichiers
echo "anti_ia.py : un fichier signalé par règle"
for fichier in tools/tests/anti-ia/signaler/*; do
  nom="$(basename "$fichier")"
  regle="${nom%%.*}"
  regle="${regle%%--*}"
  sortie="$(anti_ia "$fichier" 2>&1)"
  code=$?
  if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -Eq ":[0-9]+: ${regle}( |:)"; then
    ok "$nom signale « $regle »"
  else
    ko "$nom devrait être signalé « $regle » (code $code)" "$sortie"
  fi
done

attendus="$(wc -l < tools/tests/anti-ia/signaler/mot-banni.txt | tr -d ' ')"
trouves="$(anti_ia tools/tests/anti-ia/signaler/mot-banni.txt 2>&1 | grep -c ': mot-banni ')"
if [ "$attendus" = "$trouves" ]; then ok "chacun des $attendus mots bannis est trouvé"; else ko "mots bannis : $trouves trouvés sur $attendus"; fi

echo "anti_ia.py : fichiers propres, JSON, TypeScript, grade"
for fichier in tools/tests/anti-ia/propre/*; do
  sortie="$(anti_ia "$fichier" 2>&1)"
  code=$?
  if [ "$code" -eq 0 ] && [ -z "$sortie" ]; then ok "$(basename "$fichier") passe"; else ko "$(basename "$fichier") devrait passer (code $code)" "$sortie"; fi
done

sortie="$(anti_ia tools/tests/anti-ia/signaler/mot-banni--valeur.json 2>&1)"
if printf '%s\n' "$sortie" | grep -q "mot-banni--valeur.json:5: mot-banni"; then
  ok "JSON : la trouvaille porte la ligne de la valeur (5)"
else
  ko "JSON : numéro de ligne attendu 5" "$sortie"
fi
sortie="$(anti_ia tools/tests/anti-ia/signaler/mot-banni--litteral.ts 2>&1)"
if printf '%s\n' "$sortie" | grep -q "mot-banni--litteral.ts:2: mot-banni" && ! printf '%s\n' "$sortie" | grep -q "litteral.ts:1:"; then
  ok "TypeScript : seul le littéral de la ligne 2 est signalé"
else
  ko "TypeScript : trouvaille attendue ligne 2 seulement" "$sortie"
fi

if anti_ia tools/tests/anti-ia/grade-ultime/boutique.json >/dev/null 2>&1; then ok "le grade Ultime de la boutique est accepté"; else ko "le grade Ultime devrait passer"; fi
if anti_ia tools/tests/anti-ia/ultime-hors-boutique/boutique.json >/dev/null 2>&1; then ko "« Pack Ultime » devrait être signalé"; else ok "« Pack Ultime » reste signalé"; fi
if anti_ia definitions/boutique.json definitions/usines.json definitions/economie.json >/dev/null 2>&1; then ok "les vraies définitions passent"; else ko "les vraies définitions devraient passer"; fi

# ------------------------------------------------------------------ anti_ia.py : hook et --tout
echo "anti_ia.py --hook : seuls les textes des joueurs sont lus"
mkdir -p "$TMP/docs" "$TMP/definitions/schemas" "$TMP/addon/src" "$TMP/resource-pack/texts" "$TMP/world/ile"
mauvais_json='{ "nom": "Plonge dans l'"'"'aventure" }'
printf '%s\n' "Plonge dans une aventure épique !!" > "$TMP/docs/mauvais.md"
printf '%s\n' "$mauvais_json" > "$TMP/definitions/mauvais.json"
printf '%s\n' '{ "nom": "Scierie" }' > "$TMP/definitions/propre.json"
printf '%s\n' "$mauvais_json" > "$TMP/definitions/schemas/mauvais.json"
printf '%s\n' 'const t = "Une expérience unique";' > "$TMP/addon/src/ecran.ts"
printf '%s\n' "$mauvais_json" > "$TMP/world/ile/infos.json"
printf '%s\n' 'silooo.titre=SCIERIE' 'silooo.epique.cle=Scierie' 'silooo.msg=Plonge dans le jeu' > "$TMP/resource-pack/texts/fr_FR.lang"

# hook <chemin tel que Claude Code l'écrit> [racine] : rend le code de sortie, stderr dans $TMP/stderr
hook() {
  local chemin_json
  chemin_json="$(printf '%s' "$1" | sed 's/\\/\\\\/g')"
  printf '{"hook_event_name":"PostToolUse","tool_name":"Write","tool_input":{"file_path":"%s"}}' "$chemin_json" |
    "${PYTHON[@]}" tools/anti_ia.py --hook --racine "${2:-$TMP_NATIF}" 2>"$TMP/stderr"
}
attendre_hook() {  # attendre_hook <code> <description> <chemin> [motif attendu sur stderr]
  local code
  hook "$3"
  code=$?
  if [ "$code" -ne "$1" ]; then ko "$2 : code $code au lieu de $1" "$(cat "$TMP/stderr")"; return; fi
  if [ "$1" -eq 0 ] && [ -s "$TMP/stderr" ]; then ko "$2 : rien ne devait être écrit" "$(cat "$TMP/stderr")"; return; fi
  if [ -n "${4:-}" ] && ! grep -q "$4" "$TMP/stderr"; then ko "$2 : « $4 » absent de la sortie d'erreur" "$(cat "$TMP/stderr")"; return; fi
  ok "$2"
}
attendre_hook 0 "docs/ est ignoré, même plein de mots bannis" "$TMP_NATIF/docs/mauvais.md"
attendre_hook 2 "definitions/*.json mauvais : code 2 et trouvaille sur stderr" "$TMP_NATIF/definitions/mauvais.json" "mot-banni"
attendre_hook 0 "definitions/*.json propre passe" "$TMP_NATIF/definitions/propre.json"
attendre_hook 0 "definitions/schemas/ est hors motif" "$TMP_NATIF/definitions/schemas/mauvais.json"
attendre_hook 2 "addon/src/*.ts : littéral signalé" "$TMP_NATIF/addon/src/ecran.ts" "expérience"
attendre_hook 2 "world/**/*.json : valeur signalée" "$TMP_NATIF/world/ile/infos.json" "mot-banni"
attendre_hook 2 "resource-pack/**/texts/** (.lang) : ligne 3 signalée" "$TMP_NATIF/resource-pack/texts/fr_FR.lang" "fr_FR.lang:3:"
attendre_hook 0 "un fichier qui n'existe pas est ignoré" "$TMP_NATIF/definitions/absent.json"
if command -v cygpath >/dev/null 2>&1; then
  attendre_hook 2 "chemin Windows avec des antislashs" "$(cygpath -w "$TMP/definitions/mauvais.json")" "mot-banni"
  # /c/Users/... : la forme POSIX d'un disque Windows (pas /tmp/..., qui est un montage propre à Git Bash).
  lecteur="${TMP_NATIF%%:*}"
  attendre_hook 2 "chemin POSIX de Git Bash (/c/...)" "/${lecteur,,}${TMP_NATIF#*:}/definitions/mauvais.json" "mot-banni"
fi
mkdir -p "$TMP/ailleurs"
printf '%s\n' "Plonge dans l'aventure" > "$TMP/ailleurs/notes.txt"
attendre_hook 0 "un fichier hors du dépôt est ignoré" "$TMP_NATIF/ailleurs/notes.txt"
echo "pas du json" | "${PYTHON[@]}" tools/anti_ia.py --hook --racine "$TMP_NATIF" 2>/dev/null
if [ $? -eq 0 ]; then ok "une entrée illisible ne bloque pas"; else ko "une entrée illisible doit rendre 0"; fi

echo "anti_ia.py --tout : seulement les motifs des textes des joueurs"
sortie="$(anti_ia --tout --racine "$TMP_NATIF" 2>&1)"
code=$?
if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -q "definitions/mauvais.json:" && ! printf '%s\n' "$sortie" | grep -q "docs/"; then
  ok "--tout signale definitions/ et ignore docs/"
else
  ko "--tout : comportement inattendu (code $code)" "$sortie"
fi

# ------------------------------------------------------------------ hooks et scripts
echo "Scripts et hooks"
cat > "$TMP/stub_python.sh" <<'FIN'
trouver_python() { PYTHON=(); return 1; }
FIN
mkdir -p "$TMP/faux/tools/hooks" "$TMP/faux/tools/lib"
cp tools/hooks/anti_ia_hook.sh "$TMP/faux/tools/hooks/"
cp "$TMP/stub_python.sh" "$TMP/faux/tools/lib/python.sh"
sortie="$(echo '{}' | bash "$TMP/faux/tools/hooks/anti_ia_hook.sh" 2>&1)"
if [ $? -eq 0 ] && printf '%s' "$sortie" | grep -q "aucun Python"; then ok "le hook anti-IA prévient et sort en 0 sans Python"; else ko "le hook sans Python doit prévenir et rendre 0" "$sortie"; fi

chemin_reel="$(natif "$RACINE")/definitions/usines.json"
printf '{"tool_input":{"file_path":"%s"}}' "$chemin_reel" | bash tools/hooks/anti_ia_hook.sh >/dev/null 2>&1
if [ $? -eq 0 ]; then ok "le hook anti-IA laisse passer definitions/usines.json"; else ko "le hook anti-IA a refusé definitions/usines.json"; fi

sortie="$(bash tools/hooks/rappel_verifier.sh </dev/null 2>&1)"
if [ $? -eq 0 ]; then ok "rappel_verifier.sh sort en 0"; else ko "rappel_verifier.sh doit sortir en 0" "$sortie"; fi
sortie="$(bash tools/etat.sh 2>&1)"
if [ $? -eq 0 ] && printf '%s
' "$sortie" | grep -q "Branche"; then ok "etat.sh affiche la branche"; else ko "etat.sh n'affiche pas la branche" "$sortie"; fi

syntaxe_ok=1
for script in tools/*.sh tools/lib/*.sh tools/hooks/*.sh tools/tests/*.sh; do
  if ! bash -n "$script" 2>/dev/null; then ko "bash -n refuse $script"; syntaxe_ok=0; fi
done
if [ "$syntaxe_ok" -eq 1 ]; then ok "bash -n accepte chaque script"; fi
if node -e "JSON.parse(require('fs').readFileSync('.claude/settings.json','utf8'))" 2>/dev/null; then ok ".claude/settings.json est du JSON valide"; else ko ".claude/settings.json n'est pas du JSON valide"; fi

# ------------------------------------------------------------------ verifier_definitions.mjs
echo "verifier_definitions.mjs : les vraies définitions passent, chaque copie invalide est refusée"
sortie="$(node tools/verifier_definitions.mjs 2>&1)"
if [ $? -eq 0 ]; then ok "definitions/ est valide"; else ko "definitions/ devrait être valide" "$sortie"; fi
for dossier in tools/tests/definitions-invalides/*/; do
  cas="$(basename "$dossier")"
  attendu="$(head -n 1 "$dossier/attendu.txt")"
  sortie="$(node tools/verifier_definitions.mjs "$dossier" 2>&1)"
  code=$?
  if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -qF -- "$attendu"; then
    ok "$cas est refusé"
  else
    ko "$cas devrait être refusé avec « $attendu » (code $code)" "$sortie"
  fi
done

# ------------------------------------------------------------------ deploy/
# Trois essais, chacun dans son fichier : start.sh (sans Docker), deploy.sh et sauvegarde.sh (avec un faux docker),
# puis le compose, le Dockerfile, start.sh et l'exemple d'environnement lus les uns contre les autres.
echo "deploy/ : start.sh, deploy.sh et sauvegarde.sh, compose et fichiers voisins"
for essai in tools/tests/deploy-start.sh tools/tests/deploy-deploy.sh tools/tests/deploy-compose.mjs; do
  if [ "${essai##*.}" = mjs ]; then lanceur=(node); else lanceur=(bash); fi
  sortie="$("${lanceur[@]}" "$essai" 2>&1)"
  code=$?
  printf '%s\n' "$sortie" | grep 'IGNORÉ' | sed 's/^/  /' || true
  if [ "$code" -eq 0 ]; then
    ok "$essai : $(printf '%s\n' "$sortie" | tail -n 1)"
  else
    ko "$essai a échoué (code $code)" "$(printf '%s\n' "$sortie" | grep -A3 'ECHEC' || printf '%s\n' "$sortie" | tail -n 20)"
  fi
done

echo
echo "Tests de l'outillage : $REUSSIS réussi(s), $ECHECS en échec."
if [ "$ECHECS" -gt 0 ]; then exit 1; fi
exit 0
