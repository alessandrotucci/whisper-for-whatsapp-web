<#
.SYNOPSIS
  Rimuove la registrazione dell'host di trascrizione da Chrome ed Edge e, a richiesta, i suoi file.
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'WhatsAppWhisper'),
    # Cancella anche InstallDir (host, config, venv dedicato). Il modello nella cache di Hugging Face resta.
    [switch]$RemoveFiles
)
$ErrorActionPreference = 'Stop'
$HostName = 'local.wa_whisper.host'
foreach ($root in @('HKCU:\Software\Google\Chrome\NativeMessagingHosts', 'HKCU:\Software\Microsoft\Edge\NativeMessagingHosts')) {
    $key = Join-Path $root $HostName
    if (Test-Path $key) { Remove-Item $key -Force; Write-Host "Rimossa $key" }
}
if ($RemoveFiles -and (Test-Path $InstallDir)) {
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "Rimossa $InstallDir"
}
