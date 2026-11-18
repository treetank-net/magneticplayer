# MagneticPlayer: instalacja z innego serwera albo z telefonu (`irm <url>/ps | iex`). Szablon: adres podstawia
# serwer (mp_install.py) albo telefon (InstallShare.kt). Pobiera paczkę (tar.exe jest w Windows 10+) i uruchamia install.ps1.
& {
  $ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
  $u = '@URL@'
  $pl = (Get-UICulture).Name -like 'pl*'  # komunikaty po polsku, gdy Windows ma polski interfejs
  $t = Join-Path $env:TEMP ('magneticplayer-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory $t | Out-Null
  Write-Host $(if ($pl) { "== pobieram agenta z $u" } else { "== downloading the agent from $u" })
  Invoke-WebRequest "$u/bundle.tar.gz" -OutFile "$t\mp.tgz" -UseBasicParsing
  tar -xzf "$t\mp.tgz" -C $t
  $env:MP_JOIN = $u
  try { powershell -NoProfile -ExecutionPolicy Bypass -File "$t\server\install.ps1" }
  finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue; Remove-Item Env:MP_JOIN -ErrorAction SilentlyContinue }
}
