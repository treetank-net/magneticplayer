# MagneticPlayer: instalacja z innego serwera albo z telefonu (`irm <url>/ps | iex`). Szablon: adres podstawia
# serwer (mp_install.py) albo telefon (InstallShare.kt). Pobiera paczkę (tar.exe jest w Windows 10+) i uruchamia install.ps1.
& {
  $ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
  $u = '@URL@'
  $t = Join-Path $env:TEMP ('magneticplayer-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory $t | Out-Null
  Write-Host "== pobieram agenta z $u"
  Invoke-WebRequest "$u/bundle.tar.gz" -OutFile "$t\mp.tgz" -UseBasicParsing
  tar -xzf "$t\mp.tgz" -C $t
  $env:MP_JOIN = $u
  try { powershell -NoProfile -ExecutionPolicy Bypass -File "$t\server\install.ps1" }
  finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue; Remove-Item Env:MP_JOIN -ErrorAction SilentlyContinue }
}
