# MagneticPlayer: install from another server or from a phone (`irm <url>/ps | iex`). Template: the address is
# filled in by the server (mp_install.py) or the phone (InstallShare.kt). Downloads the bundle (tar.exe ships with
# Windows 10+) and runs install.ps1.
& {
  $ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
  $u = '@URL@'
  $pl = (Get-UICulture).Name -like 'pl*'  # messages in Polish when Windows has a Polish UI
  $t = Join-Path $env:TEMP ('magneticplayer-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory $t | Out-Null
  Write-Host $(if ($pl) { "== pobieram agenta z $u" } else { "== downloading the agent from $u" })
  Invoke-WebRequest "$u/bundle.tar.gz" -OutFile "$t\mp.tgz" -UseBasicParsing
  tar -xzf "$t\mp.tgz" -C $t
  $env:MP_JOIN = $u
  try { powershell -NoProfile -ExecutionPolicy Bypass -File "$t\server\install.ps1" }
  finally { Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue; Remove-Item Env:MP_JOIN -ErrorAction SilentlyContinue }
}
