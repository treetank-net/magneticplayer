# Odinstalowanie agenta MagneticPlayer na Windows (jako zwykły użytkownik):
#   powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1"         # ustawienia zostają
#   powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1" -Purge  # także token i sparowane telefony
# Filmów (także „Z telefonu”) ani Pythona nie rusza.
param([switch]$Purge)
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$Dest = Join-Path $env:LOCALAPPDATA 'MagneticPlayer'
$ConfDir = Join-Path $HOME '.config\magneticplayer'

Write-Host '== agent'
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'MagneticPlayer' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*$Dest\*" -and $_.ProcessId -ne $PID } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host "zatrzymano $($_.Name)" }
Start-Sleep -Milliseconds 500

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')
if (Get-NetFirewallRule -DisplayName 'MagneticPlayer*' -ErrorAction SilentlyContinue) {
  if ($admin) { Get-NetFirewallRule -DisplayName 'MagneticPlayer*' | Remove-NetFirewallRule; Write-Host 'usunięto reguły zapory' }
  else { Write-Host 'UWAGA: reguły zapory „MagneticPlayer” usuń jako administrator' }
}

# skrypt leży w $Dest: kasujemy z katalogu tymczasowego, gdy działa stamtąd
Set-Location $env:TEMP
Remove-Item -Recurse -Force $Dest -ErrorAction SilentlyContinue
if (Test-Path $Dest) { Write-Host "UWAGA: nie wszystko się usunęło z $Dest (plik w użyciu?)" } else { Write-Host "usunięto $Dest" }
if ($Purge) { Remove-Item -Recurse -Force $ConfDir -ErrorAction SilentlyContinue; Write-Host "usunięto $ConfDir (ustawienia, sparowane telefony)" }
else { Write-Host "ustawienia zostały w $ConfDir (usuń je: -Purge)" }
Write-Host '== MagneticPlayer odinstalowany. Filmy zostały na miejscu.'
