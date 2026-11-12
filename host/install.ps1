<#
.SYNOPSIS
  Installa l'host locale di trascrizione (faster-whisper) e lo registra per Chrome ed Edge.

.DESCRIPTION
  L'host è uno script Python avviato dal browser tramite native messaging. Lo script:
    1. trova o crea un Python con faster-whisper (venv dedicato, oppure un Python esistente);
    2. verifica o scarica il modello (nome come large-v3, oppure una cartella con model.bin);
    3. copia l'host in InstallDir, scrive config.json, il lanciatore .cmd e il manifest;
    4. registra il manifest in HKCU per Chrome ed Edge (nessun privilegio di amministratore);
    5. a richiesta esegue un autotest che carica il modello.

  Rilanciarlo è sicuro: aggiorna i file e la registrazione.

.EXAMPLE
  # Python e modello già presenti
  .\install.ps1 -Python C:\Users\me\.venvs\whisper\Scripts\python.exe -Model large-v3

.EXAMPLE
  # Installazione completa assistita (venv + faster-whisper + download del modello)
  .\install.ps1
#>
[CmdletBinding()]
param(
    # python.exe che ha già faster-whisper installato. Vuoto: menu interattivo.
    [string]$Python,
    # Nome del modello faster-whisper (large-v3, large-v3-turbo, medium, ...) o cartella con model.bin.
    [string]$Model,
    [ValidateSet('cpu', 'cuda', 'auto')]
    [string]$Device = 'cpu',
    # int8 su CPU; su GPU float16 o int8_float16.
    [string]$ComputeType = 'int8',
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'WhatsAppWhisper'),
    # ID dell'estensione; più ID separati da virgola (es. installazione da store).
    [string[]]$ExtensionId = @('mmfdonlfmjnahpaebjhjlgkpplkfplop'),
    # Crea il venv e installa faster-whisper senza chiedere.
    [switch]$CreateVenv,
    # Esegue l'autotest senza chiedere.
    [switch]$SelfTest,
    # Nessuna domanda: valgono i parametri e i default.
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
$StartTime = (Get-Date).AddSeconds(-2)
$HostName = 'local.wa_whisper.host'
$SourceDir = $PSScriptRoot
$HostDir = Join-Path $InstallDir 'host'
$VenvDir = Join-Path $InstallDir 'venv'
$Packages = @('faster-whisper==1.2.1', 'av==15.1.0')

function Write-Step([string]$Text) { Write-Host "`n== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "   OK  $Text" -ForegroundColor Green }
function Write-Warn2([string]$Text) { Write-Host "   !!  $Text" -ForegroundColor Yellow }

function Ask([string]$Question, [string]$Default) {
    if ($NonInteractive) { return $Default }
    $suffix = if ($Default) { " [$Default]" } else { '' }
    $answer = Read-Host "$Question$suffix"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim().Trim('"')
}

function Confirm([string]$Question, [bool]$Default = $true) {
    if ($NonInteractive) { return $Default }
    $hint = if ($Default) { 'S/n' } else { 's/N' }
    $answer = Read-Host "$Question ($hint)"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim().ToLower().StartsWith('s') -or $answer.Trim().ToLower().StartsWith('y')
}

function Invoke-Py([string]$Exe, [string[]]$PyArgs) {
    $ErrorActionPreference = 'Continue'
    $out = & $Exe -I -X utf8 @PyArgs 2>&1
    return @{ Code = $LASTEXITCODE; Out = ($out | Out-String).Trim() }
}

function Test-FasterWhisper([string]$Exe) {
    if (-not (Test-Path $Exe)) { return $null }
    $r = Invoke-Py $Exe @('-c', 'import faster_whisper,ctranslate2,sys;print(faster_whisper.__version__, ctranslate2.__version__, sys.version.split()[0])')
    if ($r.Code -ne 0) { return $null }
    return $r.Out
}

function Find-BasePython {
    $ErrorActionPreference = 'Continue'
    # Python 3.10-3.12: le versioni con wheel note per ctranslate2 e av.
    foreach ($v in @('3.12', '3.11', '3.10')) {
        $py = Get-Command py -ErrorAction SilentlyContinue
        if ($py) {
            $exe = (& py "-$v" -c 'import sys;print(sys.executable)' 2>$null)
            if ($LASTEXITCODE -eq 0 -and $exe) { return $exe.Trim() }
        }
    }
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -notlike '*WindowsApps*') {
        $ver = (& $cmd.Source -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>$null)
        if ($ver -in @('3.10', '3.11', '3.12')) { return $cmd.Source }
    }
    return $null
}

function New-WhisperVenv {
    Write-Step 'Creazione del venv con faster-whisper'
    $base = Find-BasePython
    if (-not $base) {
        Write-Warn2 'Nessun Python 3.10-3.12 trovato.'
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            throw 'Installa Python 3.12 da https://www.python.org/downloads/ e rilancia lo script.'
        }
        if (-not (Confirm 'Installo Python 3.12 per il solo utente corrente con winget?')) {
            throw 'Python 3.12 necessario: installalo e rilancia lo script.'
        }
        winget install -e --id Python.Python.3.12 --scope user --accept-package-agreements --accept-source-agreements
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
        $base = Find-BasePython
        if (-not $base) { throw 'Python installato ma non trovato: apri un nuovo PowerShell e rilancia lo script.' }
    }
    Write-Ok "Python di base: $base"
    if (-not (Test-Path (Join-Path $VenvDir 'Scripts\python.exe'))) {
        & $base -m venv $VenvDir
        if ($LASTEXITCODE -ne 0) { throw "Creazione del venv fallita in $VenvDir" }
    }
    $exe = Join-Path $VenvDir 'Scripts\python.exe'
    & $exe -m pip install --upgrade pip --disable-pip-version-check -q
    & $exe -m pip install --disable-pip-version-check @Packages
    if ($LASTEXITCODE -ne 0) { throw 'pip install fallito' }
    & $exe -m pip freeze --disable-pip-version-check | Set-Content -Encoding utf8 (Join-Path $InstallDir 'requirements-lock.txt')
    return $exe
}

function Resolve-Model([string]$Exe, [string]$Name) {
    if (Test-Path $Name -PathType Container) {
        if (-not (Test-Path (Join-Path $Name 'model.bin'))) { throw "La cartella $Name non contiene model.bin" }
        return (Resolve-Path $Name).Path
    }
    $code = 'import sys;from faster_whisper.utils import download_model;print(download_model(sys.argv[1], local_files_only=True))'
    $r = Invoke-Py $Exe @('-c', $code, $Name)
    if ($r.Code -eq 0) { return ($r.Out -split "`r?`n")[-1].Trim() }
    Write-Warn2 "Modello '$Name' non presente nella cache di Hugging Face."
    if (-not (Confirm "Lo scarico ora? (large-v3 occupa circa 3 GB)")) {
        throw "Modello mancante: rilancia con -Model <cartella con model.bin> oppure accetta il download."
    }
    $code = 'import sys;from faster_whisper.utils import download_model;print(download_model(sys.argv[1]))'
    & $Exe -I -X utf8 -c $code $Name | Tee-Object -Variable dl
    if ($LASTEXITCODE -ne 0) { throw 'Download del modello fallito' }
    return ($dl | Select-Object -Last 1).Trim()
}

# 1. Python
Write-Step 'Python con faster-whisper'
New-Item -ItemType Directory -Force $HostDir | Out-Null
if (-not $Python) {
    $venvExe = Join-Path $VenvDir 'Scripts\python.exe'
    $known = @($venvExe, (Join-Path $env:USERPROFILE '.venvs\whisper\Scripts\python.exe')) | Where-Object { Test-FasterWhisper $_ }
    if ($CreateVenv) {
        $Python = New-WhisperVenv
    } elseif ($known) {
        $Python = Ask 'Python con faster-whisper trovato. Uso questo?' ($known | Select-Object -First 1)
    } else {
        Write-Host '   1) Ho già un Python con faster-whisper: indico il percorso di python.exe'
        Write-Host "   2) Installa tutto: venv dedicato in $VenvDir"
        $choice = Ask 'Scelta' '2'
        if ($choice -eq '1') { $Python = Ask 'Percorso di python.exe' '' } else { $Python = New-WhisperVenv }
    }
}
$Python = (Resolve-Path $Python).Path
$versions = Test-FasterWhisper $Python
if (-not $versions) {
    if ((Join-Path $VenvDir 'Scripts\python.exe') -eq $Python -or (Confirm "$Python non ha faster-whisper. Creo un venv dedicato?")) {
        $Python = New-WhisperVenv
        $versions = Test-FasterWhisper $Python
    }
    if (-not $versions) { throw "faster-whisper non importabile con $Python" }
}
Write-Ok "$Python (faster-whisper, ctranslate2, python: $versions)"

# 2. Modello
Write-Step 'Modello'
if (-not $Model) { $Model = Ask 'Modello (nome o cartella con model.bin)' 'large-v3' }
$ModelPath = Resolve-Model $Python $Model
Write-Ok $ModelPath

# 3. File dell'host
Write-Step "File dell'host in $HostDir"
Copy-Item (Join-Path $SourceDir 'whisper_host.py') $HostDir -Force

$configPath = Join-Path $HostDir 'config.json'
$config = [ordered]@{
    model        = $ModelPath
    device       = $Device
    compute_type = $ComputeType
    cpu_threads  = 0
    beam_size    = 5
    vad_filter   = $true
    hf_offline   = $true
}
if (Test-Path $configPath) {
    $old = Get-Content $configPath -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($k in @('cpu_threads', 'beam_size', 'vad_filter')) { if ($null -ne $old.$k) { $config[$k] = $old.$k } }
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), $utf8)

$launcher = Join-Path $HostDir 'whisper_host.cmd'
$cmdText = "@echo off`r`nchcp 65001 >nul`r`n`"$Python`" -I -X utf8 `"%~dp0whisper_host.py`" %*`r`n"
[IO.File]::WriteAllText($launcher, $cmdText, (New-Object System.Text.UTF8Encoding($false)))

$manifestPath = Join-Path $HostDir "$HostName.json"
$manifest = [ordered]@{
    name            = $HostName
    description     = 'Trascrizione locale dei vocali con faster-whisper'
    path            = $launcher
    type            = 'stdio'
    allowed_origins = @($ExtensionId | ForEach-Object { $_ -split ',' } | Where-Object { $_ } | ForEach-Object { "chrome-extension://$($_.Trim())/" })
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), $utf8)
Write-Ok "config.json, whisper_host.cmd, $HostName.json"

# Un processo che gira dentro un pacchetto MSIX (per esempio un terminale aperto da
# un'app dello Store) scrive AppData e HKCU in una copia privata del pacchetto, che
# il browser non vede. Il manifest appena scritto lo rivela.
$leaf = Split-Path $InstallDir -Leaf
$redirected = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Packages') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "LocalCache\Local\$leaf\host\$HostName.json" } |
    Where-Object { (Test-Path $_) -and (Get-Item $_).LastWriteTime -ge $StartTime } |
    ForEach-Object { Split-Path (Split-Path $_) }
if ($redirected) {
    throw ("Questo PowerShell gira dentro un pacchetto MSIX: Windows ha scritto i file in $(@($redirected)[0]) " +
        "e il registro in una copia privata, invisibili a Chrome ed Edge. Apri PowerShell dal menu Start o da Windows Terminal e rilancia lo script.")
}

# 4. Registrazione
Write-Step 'Registrazione per Chrome ed Edge (HKCU)'
foreach ($root in @('HKCU:\Software\Google\Chrome\NativeMessagingHosts', 'HKCU:\Software\Microsoft\Edge\NativeMessagingHosts')) {
    $key = Join-Path $root $HostName
    New-Item -Path $key -Force | Out-Null
    Set-ItemProperty -Path $key -Name '(default)' -Value $manifestPath
    Write-Ok $key
}

# 5. Autotest
if ($SelfTest -or (Confirm "Eseguo l'autotest (carica il modello, qualche decina di secondi)?" $false)) {
    Write-Step 'Autotest'
    & $Python -I -X utf8 (Join-Path $HostDir 'whisper_host.py') --selftest
    if ($LASTEXITCODE -ne 0) { throw 'Autotest fallito' }
}

Write-Host "`nInstallazione completata. Nella pagina delle opzioni dell'estensione premi 'Verifica host'." -ForegroundColor Green
Write-Host "Log dell'host: $(Join-Path $HostDir 'whisper_host.log')"
