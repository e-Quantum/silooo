#!/usr/bin/env bash
# Tests de l'outillage : anti_ia.py (règles, JSON, hook), verifier_definitions.mjs et les scripts.
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

# ------------------------------------------------------------------ generer_types.mjs
echo "generer_types.mjs : le fichier de l'add-on est à jour, la génération est exacte et se vérifie"
generer() { node tools/generer_types.mjs "$@" 2>&1; }

sortie="$(generer --verifier)"
if [ $? -eq 0 ]; then ok "addon/src/generated/definitions.ts est à jour"; else ko "addon/src/generated/definitions.ts est périmé" "$sortie"; fi

# Le dossier fictif tools/tests/generateur/monde joue le rôle d'un futur definitions/monde.json : une
# forme de schéma de chaque sorte, et un identifiant en double (« plaine ») que l'union ne répète pas.
sortie="$(generer tools/tests/generateur/monde --sortie "$TMP_NATIF/monde.ts")"
if [ $? -eq 0 ] && cmp -s "$TMP/monde.ts" tools/tests/generateur/monde/attendu.ts; then
  ok "monde.json fictif : le fichier généré est celui qu'on attend"
else
  ko "monde.json fictif : sortie différente de tools/tests/generateur/monde/attendu.ts" "$(diff "$TMP/monde.ts" tools/tests/generateur/monde/attendu.ts 2>&1 | head -n 15)"
fi
generer tools/tests/generateur/monde --sortie "$TMP_NATIF/monde-2.ts" >/dev/null
if cmp -s "$TMP/monde.ts" "$TMP/monde-2.ts"; then ok "deux générations de suite donnent le même fichier"; else ko "la génération n'est pas déterministe"; fi

sortie="$(generer tools/tests/generateur/monde --sortie tools/tests/generateur/monde/attendu.ts --verifier)"
if [ $? -eq 0 ]; then ok "--verifier accepte un fichier à jour"; else ko "--verifier devrait accepter un fichier à jour" "$sortie"; fi
sed 's/$/\r/' tools/tests/generateur/monde/attendu.ts > "$TMP/monde-crlf.ts"
sortie="$(generer tools/tests/generateur/monde --sortie "$TMP_NATIF/monde-crlf.ts" --verifier)"
if [ $? -eq 0 ]; then ok "--verifier ignore les fins de ligne Windows"; else ko "--verifier devrait ignorer CRLF" "$sortie"; fi

sed 's/readonly prix: number/readonly prix: string/' tools/tests/generateur/monde/attendu.ts > "$TMP/monde-perime.ts"
sortie="$(generer tools/tests/generateur/monde --sortie "$TMP_NATIF/monde-perime.ts" --verifier)"
code=$?
if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -q "périmé"; then
  ok "--verifier refuse un fichier périmé (code 1, message « périmé »)"
else
  ko "--verifier devrait refuser un fichier périmé (code $code)" "$sortie"
fi
if grep -q "readonly prix: string" "$TMP/monde-perime.ts"; then ok "--verifier n'écrit rien dans le fichier"; else ko "--verifier a réécrit le fichier"; fi
sortie="$(generer tools/tests/generateur/monde --sortie "$TMP_NATIF/absent.ts" --verifier)"
code=$?
if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -q "absent"; then ok "--verifier refuse un fichier absent"; else ko "--verifier devrait refuser un fichier absent (code $code)" "$sortie"; fi

for dossier in tools/tests/generateur/mot-cle-inconnu tools/tests/generateur/sans-schema; do
  cas="$(basename "$dossier")"
  attendu="$(head -n 1 "$dossier/attendu.txt")"
  sortie="$(generer "$dossier" --sortie "$TMP_NATIF/echec.ts")"
  code=$?
  if [ "$code" -eq 1 ] && printf '%s\n' "$sortie" | grep -qF -- "$attendu" && [ ! -e "$TMP/echec.ts" ]; then
    ok "$cas échoue sans rien écrire"
  else
    ko "$cas devrait échouer avec « $attendu » (code $code)" "$sortie"
  fi
done

# Le dossier est parcouru : on ajoute un monde.json aux vraies définitions, sans rien changer au script.
mkdir -p "$TMP/definitions-plus/schemas"
cp definitions/*.json "$TMP/definitions-plus/"
cp definitions/schemas/*.json "$TMP/definitions-plus/schemas/"
cp tools/tests/generateur/monde/monde.json "$TMP/definitions-plus/"
cp tools/tests/generateur/monde/schemas/monde.schema.json "$TMP/definitions-plus/schemas/"
sortie="$(generer "$TMP_NATIF/definitions-plus" --sortie "$TMP_NATIF/plus.ts")"
if [ $? -eq 0 ] && grep -q "export type MondeDefinitions" "$TMP/plus.ts" && grep -q "export type FactoryId" "$TMP/plus.ts" \
  && grep -q "export type ShopGradeId" "$TMP/plus.ts" && grep -q "export type MondeZonesId" "$TMP/plus.ts"; then
  ok "un nouveau monde.json est repris sans changer le script"
else
  ko "un nouveau monde.json devrait être repris" "$sortie"
fi
if ! grep -q "FactoryId" "$TMP/monde.ts"; then ok "chaque union d'identifiants vient de son propre fichier"; else ko "le dossier fictif ne devrait contenir aucun identifiant d'usine"; fi

# anti_ia.py --tout saute les dossiers « generated » (les identifiants y sont déjà contrôlés à la source).
mkdir -p "$TMP/addon/src/generated"
printf '%s\n' 'export type GradeId = "ultime";' > "$TMP/addon/src/generated/ids.ts"
sortie="$(anti_ia --tout --racine "$TMP_NATIF" 2>&1)"
if ! printf '%s\n' "$sortie" | grep -q "generated/"; then ok "anti_ia.py --tout ignore addon/src/generated/"; else ko "anti_ia.py --tout ne devrait pas lire generated/" "$sortie"; fi

echo
echo "Tests de l'outillage : $REUSSIS réussi(s), $ECHECS en échec."
if [ "$ECHECS" -gt 0 ]; then exit 1; fi
exit 0
