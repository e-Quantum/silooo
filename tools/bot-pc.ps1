# tools/bot-pc.ps1 : lance le bot Oooscar depuis le PC de Kr, sur le serveur Silooo
# (docs/discord-passation.md, section « Lancer le bot depuis le PC »).
#
# Usage, dans PowerShell, depuis la racine du dépôt :
#   .\tools\bot-pc.ps1                 à blanc (le défaut) : le bot lit le serveur et écrit l'écart avec le plan, sans rien changer
#   .\tools\bot-pc.ps1 -Mode oui       applique le plan, une fois le compte rendu lu dans #journal-du-bot
#   .\tools\bot-pc.ps1 -Base           démarre seulement la base et les migrations (pour les essais de la session Claude)
#   .\tools\bot-pc.ps1 -Phase beta     la phase du serveur (attente par défaut) ; -Public oui quand le QG est ouvert
#
# Ce que fait le script : il vérifie que Docker Desktop répond, crée ou redémarre le conteneur silooo-mariadb (image mariadb,
# base silooo, port 3306 sur 127.0.0.1 seulement), attend que la base réponde, applique les migrations, puis lance silooo-core
# avec le bot. Le jeton et l'identifiant du serveur viennent des variables du compte Windows de Kr (SILOOO_DISCORD_TOKEN,
# SILOOO_DISCORD_SERVEUR) : le script vérifie qu'elles sont posées, sans jamais les afficher. Le mot de passe de la base locale
# est tiré au premier lancement et gardé hors du dépôt, dans %LOCALAPPDATA%\Silooo\mariadb-pc.txt ; la clé d'API est tirée à
# chaque lancement (l'add-on n'est pas relié au PC). Ctrl+C arrête le bot ; le conteneur reste, docker stop silooo-mariadb l'arrête.
# Pour repartir de zéro : docker rm -f silooo-mariadb, puis supprimer le fichier du mot de passe.

[CmdletBinding()]
param(
    [ValidateSet('a-blanc', 'oui')] [string] $Mode = 'a-blanc',
    [ValidateSet('attente', 'beta', 'ouvert')] [string] $Phase = 'attente',
    [ValidateSet('non', 'oui')] [string] $Public = 'non',
    [switch] $Base
)

$racine = Split-Path -Parent $PSScriptRoot
$conteneur = 'silooo-mariadb'
$image = 'mariadb:11'
$port = 3306
$dossierLocal = Join-Path $env:LOCALAPPDATA 'Silooo'
$fichierMotDePasse = Join-Path $dossierLocal 'mariadb-pc.txt'

function Arret([string] $message) {
    Write-Host "bot-pc : $message" -ForegroundColor Red
    exit 1
}

function Aleatoire() {
    $octets = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($octets)
    return -join ($octets | ForEach-Object { $_.ToString('x2') })
}

function Conteneur([switch] $Tous) {
    $arguments = @('ps')
    if ($Tous) { $arguments += '-a' }
    $noms = & docker @arguments --filter "name=^$conteneur$" --format '{{.Names}}' 2>$null
    return (@($noms) -contains $conteneur)
}

# 1. Les conditions : Docker Desktop, et les variables du compte si le bot doit partir.
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Arret "Docker Desktop ne répond pas : démarre-le (menu Démarrer), attends que son icône soit verte, puis relance ce script."
}
if (-not $Base) {
    foreach ($nom in 'SILOOO_DISCORD_TOKEN', 'SILOOO_DISCORD_SERVEUR') {
        if (-not (Test-Path "env:$nom")) {
            Arret "$nom est absente de ce terminal : pose-la dans les variables d'environnement du compte Windows, puis ouvre un nouveau terminal."
        }
    }
}

# 2. Le mot de passe de la base locale, hors du dépôt.
if (-not (Test-Path $fichierMotDePasse)) {
    New-Item -ItemType Directory -Force -Path $dossierLocal | Out-Null
    Set-Content -Path $fichierMotDePasse -Value (Aleatoire) -NoNewline
    Write-Host "bot-pc : mot de passe de la base locale tiré, gardé dans $fichierMotDePasse."
}
$motDePasse = (Get-Content -Path $fichierMotDePasse -Raw).Trim()

# 3. Le conteneur : créé la première fois, redémarré ensuite.
if (-not (Conteneur -Tous)) {
    Write-Host "bot-pc : création du conteneur $conteneur ($image, base silooo, port $port sur 127.0.0.1)."
    & docker run -d --name $conteneur -p "127.0.0.1:${port}:3306" `
        -e MARIADB_DATABASE=silooo -e MARIADB_USER=silooo `
        -e "MARIADB_PASSWORD=$motDePasse" -e "MARIADB_ROOT_PASSWORD=$motDePasse" $image | Out-Null
    if ($LASTEXITCODE -ne 0) { Arret "docker run a échoué." }
} elseif (-not (Conteneur)) {
    & docker start $conteneur | Out-Null
    if ($LASTEXITCODE -ne 0) { Arret "docker start $conteneur a échoué." }
}

# 4. La base répond.
$prete = $false
for ($essai = 0; $essai -lt 45; $essai++) {
    & docker exec $conteneur healthcheck.sh --connect --innodb_initialized *> $null
    if ($LASTEXITCODE -eq 0) { $prete = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $prete) { Arret "la base ne répond pas après 90 s : docker logs $conteneur." }

# 5. Les variables de silooo-core, puis les migrations.
$env:SILOOO_DB_URL = "jdbc:mariadb://127.0.0.1:${port}/silooo"
$env:SILOOO_DB_USER = 'silooo'
$env:SILOOO_DB_PASSWORD = $motDePasse
$env:SILOOO_API_KEY = Aleatoire
$gradlew = Join-Path $racine 'core\gradlew.bat'
& $gradlew -p (Join-Path $racine 'core') --quiet run --args=migrate
if ($LASTEXITCODE -ne 0) {
    Arret "les migrations ont échoué. Si le mot de passe du conteneur ne correspond plus au fichier : docker rm -f $conteneur, supprimer $fichierMotDePasse, relancer."
}
if ($Base) {
    Write-Host "bot-pc : base prête sur jdbc:mariadb://127.0.0.1:$port/silooo (utilisateur silooo, mot de passe dans $fichierMotDePasse)."
    exit 0
}

# 6. Le bot. Un seul à la fois : celui du VPS reste à non pendant le chantier (fichier d'environnement du VPS).
$env:SILOOO_DISCORD_ACTIF = $Mode
$env:SILOOO_DISCORD_PHASE = $Phase
$env:SILOOO_DISCORD_PUBLIC = $Public
if ($Mode -eq 'oui') {
    Write-Host "bot-pc : mode oui, le bot applique le plan sur le serveur Silooo." -ForegroundColor Yellow
}
Write-Host "bot-pc : lancement avec SILOOO_DISCORD_ACTIF=$Mode SILOOO_DISCORD_PHASE=$Phase SILOOO_DISCORD_PUBLIC=$Public (Ctrl+C pour arrêter)."
& $gradlew -p (Join-Path $racine 'core') --quiet run
exit $LASTEXITCODE
