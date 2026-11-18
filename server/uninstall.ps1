# Odinstalowanie agenta MagneticPlayer na Windows (jako zwykły użytkownik):
#   powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1"         # ustawienia zostają
#   powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1" -Purge  # także token i sparowane telefony
# Filmów (także „Z telefonu”) ani Pythona nie rusza.
param([switch]$Purge)
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$Dest = Join-Path $env:LOCALAPPDATA 'MagneticPlayer'
$ConfDir = Join-Path $HOME '.config\magneticplayer'
# komunikaty: po polsku, gdy Windows ma polski język interfejsu, inaczej po angielsku
$MpPl = (Get-UICulture).Name -like 'pl*'
function Tr([string]$en, [string]$pl) { if ($MpPl) { $pl } else { $en } }
function Say([string]$en, [string]$pl) { Write-Host (Tr $en $pl) }

Write-Host '== agent'
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'MagneticPlayer' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*$Dest\*" -and $_.ProcessId -ne $PID } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Say "stopped $($_.Name)" "zatrzymano $($_.Name)" }
Start-Sleep -Milliseconds 500

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')
if (Get-NetFirewallRule -DisplayName 'MagneticPlayer*' -ErrorAction SilentlyContinue) {
  if ($admin) { Get-NetFirewallRule -DisplayName 'MagneticPlayer*' | Remove-NetFirewallRule; Say 'removed firewall rules' 'usunięto reguły zapory' }
  else { Say 'WARNING: remove the “MagneticPlayer” firewall rules as administrator' 'UWAGA: reguły zapory „MagneticPlayer” usuń jako administrator' }
}

# skrypt leży w $Dest: kasujemy z katalogu tymczasowego, gdy działa stamtąd
Set-Location $env:TEMP
Remove-Item -Recurse -Force $Dest -ErrorAction SilentlyContinue
if (Test-Path $Dest) { Say "WARNING: not everything was removed from $Dest (file in use?)" "UWAGA: nie wszystko się usunęło z $Dest (plik w użyciu?)" }
else { Say "removed $Dest" "usunięto $Dest" }
if ($Purge) { Remove-Item -Recurse -Force $ConfDir -ErrorAction SilentlyContinue; Say "removed $ConfDir (settings, paired phones)" "usunięto $ConfDir (ustawienia, sparowane telefony)" }
else { Say "settings were kept in $ConfDir (remove them with -Purge)" "ustawienia zostały w $ConfDir (usuń je: -Purge)" }
Say '== MagneticPlayer uninstalled. Your videos were left in place.' '== MagneticPlayer odinstalowany. Filmy zostały na miejscu.'
