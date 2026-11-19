# Install the MagneticPlayer agent on Windows 10/11 (lite install: no administrator, no torrents,
# no sleep and no Wake-on-LAN). Run in PowerShell as a regular user:
#   powershell -ExecutionPolicy Bypass -File install.ps1
# From another server on the network this is done for you:  irm http://<serwer>:8765/i/<kod>/ps | iex
# Variables: $env:MP_PORT (port; default the first free one from 8765), $env:MP_JOIN (/i/<code> address: reports
# after install, the phone pairs by itself), $env:MP_NO_MPV=1 (don't download mpv, i.e. no screen).
# Uninstall: powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1"
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'  # the Invoke-WebRequest progress bar slows downloads several times over
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$env:PYTHONUTF8 = '1'  # Polish characters in agent messages (the Windows console has a different code page)
$env:PYTHONIOENCODING = 'utf-8'
$Src = $PSScriptRoot
$Dest = Join-Path $env:LOCALAPPDATA 'MagneticPlayer'
$ConfDir = Join-Path $HOME '.config\magneticplayer'
$Conf = Join-Path $ConfDir 'config.json'
# messages: Polish when Windows has a Polish UI language, English otherwise
$MpPl = (Get-UICulture).Name -like 'pl*'
function Tr([string]$en, [string]$pl) { if ($MpPl) { $pl } else { $en } }
function Say([string]$en, [string]$pl) { Write-Host (Tr $en $pl) }
if ($MpPl -and -not $env:LANG) { $env:LANG = 'pl_PL.UTF-8' }  # agent messages (--pair, --join) in the same language

function Find-Python {
  # python.exe in WindowsApps is only a shortcut to the Microsoft Store, not Python
  foreach ($c in @('py', 'python', 'python3')) {
    $cmd = Get-Command $c -ErrorAction SilentlyContinue | Where-Object { $_.Source -notlike '*WindowsApps*' } | Select-Object -First 1
    if (-not $cmd) { continue }
    $pa = @(); if ($c -eq 'py') { $pa = @('-3') }
    try {
      $exe = & $cmd.Source @pa -c 'import sys; print(sys.executable) if sys.version_info >= (3, 9) else None' 2>$null
      if ($LASTEXITCODE -eq 0 -and $exe) { return $exe.Trim() }
    } catch {}
  }
  foreach ($p in Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python3*\python.exe') -ErrorAction SilentlyContinue) {
    return $p.FullName
  }
  return $null
}

Say '== MagneticPlayer: lite install (Windows)' '== MagneticPlayer: instalacja lekka (Windows)'
$py = Find-Python
if (-not $py -and (Get-Command winget -ErrorAction SilentlyContinue)) {
  Say '== Python is missing: installing Python 3.12 for this user (winget, no administrator)' `
      '== brak Pythona: instaluję Python 3.12 dla tego użytkownika (winget, bez administratora)'
  winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements --disable-interactivity | Out-Host
  $py = Find-Python
}
if (-not $py) {
  # without winget (Windows 10 LTSC, old Windows 10) or when it failed: the official python.org installer, silent, per user
  $pyVer = '3.12.10'
  $pyArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
  $pyExe = Join-Path $env:TEMP "python-$pyVer-$pyArch.exe"
  Say "== Python is missing: downloading Python $pyVer from python.org (install for this user, no administrator)" `
      "== brak Pythona: pobieram Python $pyVer z python.org (instalacja dla tego użytkownika, bez administratora)"
  Invoke-WebRequest "https://www.python.org/ftp/python/$pyVer/python-$pyVer-$pyArch.exe" -OutFile $pyExe -UseBasicParsing
  $p = Start-Process $pyExe -ArgumentList '/quiet', 'InstallAllUsers=0', 'PrependPath=0', 'Include_test=0', 'Include_launcher=0', 'Shortcuts=0' -Wait -PassThru
  Remove-Item $pyExe -Force -ErrorAction SilentlyContinue
  if ($p.ExitCode -ne 0) { Say "the Python installer exited with code $($p.ExitCode)" "instalator Pythona zakończył się kodem $($p.ExitCode)" }
  $py = Find-Python
  if (-not $py) { throw (Tr "Python didn't install. Install it from python.org and run this again." 'Python się nie zainstalował. Zainstaluj go z python.org i uruchom ponownie.') }
}
Write-Host "python: $py"

# stop the previous version (update)
Get-CimInstance Win32_Process -Filter "Name like 'python%.exe'" | Where-Object { $_.CommandLine -like "*$Dest\magneticplayer_agent.py*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Host "== agent -> $Dest"
New-Item -ItemType Directory -Force -Path $Dest, (Join-Path $Dest 'web'), $ConfDir | Out-Null
Copy-Item (Join-Path $Src '*.py') $Dest -Force
Copy-Item (Join-Path $Src 'web\*') (Join-Path $Dest 'web') -Force
# scripts next to the agent: the server uses them to install the agent on further computers (mp_install.py)
foreach ($f in 'install.ps1', 'uninstall.ps1', 'join.ps1', 'install.sh', 'install-root.sh', 'uninstall.sh', 'join.sh') {
  if (Test-Path (Join-Path $Src $f)) { Copy-Item (Join-Path $Src $f) $Dest -Force }
}

Say '== subtitles, Chromecast, QR: venv' '== napisy, Chromecast, QR: venv'
$venvPy = Join-Path $Dest 'venv\Scripts\python.exe'
if (-not (Test-Path $venvPy)) { & $py -m venv (Join-Path $Dest 'venv') }
if (Test-Path $venvPy) {
  & $venvPy -m pip install -q --disable-pip-version-check --upgrade subliminal pychromecast qrcode numpy webrtcvad-wheels 2>&1 | Out-Host
  if ($LASTEXITCODE -ne 0) { Say "WARNING: some packages didn't install (subtitles or Chromecast may not work)" 'UWAGA: część pakietów się nie zainstalowała (napisy albo Chromecast mogą nie działać)' }
} else {
  Say 'WARNING: venv failed; the agent works without subtitles and Chromecast from the web page' 'UWAGA: venv się nie udał; agent działa bez napisów i Chromecasta ze strony'
  $venvPy = $py
}
$pyw = Join-Path (Split-Path $venvPy) 'pythonw.exe'
if (-not (Test-Path $pyw)) { $pyw = $venvPy }

# mpv: the screen (video on this computer). The official portable package from GitHub, next to the agent.
$mpvExe = Join-Path $Dest 'mpv\mpv.exe'
if (Test-Path $mpvExe) {  # an mpv that doesn't start (e.g. a missing DLL) is downloaded again
  & (Join-Path $Dest 'mpv\mpv.com') --version *> $null
  if ($LASTEXITCODE -ne 0) { Remove-Item -Recurse -Force (Join-Path $Dest 'mpv') }
}
if (-not $env:MP_NO_MPV -and -not (Get-Command mpv -ErrorAction SilentlyContinue) -and -not (Test-Path $mpvExe)) {
  Say '== mpv (screen): downloading from github.com/mpv-player/mpv' '== mpv (ekran): pobieram z github.com/mpv-player/mpv'
  try {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/mpv-player/mpv/releases/latest' -UseBasicParsing
    # MinGW build: the MSVC build needs the Visual C++ runtime, which a fresh Windows lacks (0xC0000135)
    $arch = 'x86_64'; if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $arch = 'aarch64' }
    $asset = $rel.assets | Where-Object { $_.name -like "mpv-*-$arch-w64-mingw32-full.zip" } | Select-Object -First 1
    if (-not $asset) { throw (Tr 'no Windows package in the latest release' 'brak paczki dla Windows w ostatnim wydaniu') }
    $zip = Join-Path $env:TEMP $asset.name
    Invoke-WebRequest $asset.browser_download_url -OutFile $zip -UseBasicParsing
    Remove-Item -Recurse -Force (Join-Path $Dest 'mpv') -ErrorAction SilentlyContinue
    Expand-Archive $zip (Join-Path $Dest 'mpv') -Force
    Remove-Item $zip -Force
    Write-Host "mpv $($rel.tag_name)"
  } catch {
    Say "WARNING: mpv didn't download ($($_.Exception.Message)); this computer won't be a screen" `
        "UWAGA: mpv się nie pobrał ($($_.Exception.Message)); ten komputer nie będzie ekranem"
  }
}

# port: a free one when none is given (8765 is sometimes taken by another program)
function Test-Port([int]$p) {
  # exclusive (like the agent): a port is also busy when someone listens on it only on 127.0.0.1
  try { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $p); $l.ExclusiveAddressUse = $true; $l.Start(); $l.Stop() } catch { return $false }
  try { $c = [Net.Sockets.TcpClient]::new(); $c.Connect('127.0.0.1', $p); $c.Close(); return $false } catch { return $true }
}
$cfg = @{}
if (Test-Path $Conf) { $cfg = Get-Content $Conf -Raw -Encoding UTF8 | ConvertFrom-Json }
$port = $null
if ($env:MP_PORT) { $port = [int]$env:MP_PORT }
else {
  # first the port from the previous install (the previous agent is already stopped), then the following ones from 8765
  $cands = @(); if ($cfg.port) { $cands += [int]$cfg.port }; $cands += 8765..8785
  foreach ($p in $cands) { if (Test-Port $p) { $port = $p; break } }
}
if (-not $port) { throw (Tr 'No free port in 8765-8785 (set $env:MP_PORT)' 'Nie znalazłem wolnego portu 8765-8785 (podaj $env:MP_PORT)') }
if ($port -ne 8765) { Say "port 8765 is taken: agent on $port" "port 8765 zajęty: agent na $port" }

Write-Host "== config: backend=none power=false wake=false port=$port"
@'
import json, os, sys
p, port = sys.argv[1], int(sys.argv[2])
cfg = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}
cfg.update(port=port, backend="none", power=False, wake=False)
os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(cfg, open(p, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
'@ | & $venvPy - $Conf $port

# firewall: the rule needs an administrator; without one Windows asks on first start
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')
if ($admin) {
  Get-NetFirewallRule -DisplayName 'MagneticPlayer*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  New-NetFirewallRule -DisplayName "MagneticPlayer (TCP $port)" -Direction Inbound -Protocol TCP -LocalPort $port -Profile Private, Domain -Action Allow | Out-Null
  New-NetFirewallRule -DisplayName 'MagneticPlayer (UDP 47700)' -Direction Inbound -Protocol UDP -LocalPort 47700 -Profile Private, Domain -Action Allow | Out-Null
  # the agent runs on the base interpreter's pythonw (the venv only launches it). A per-program rule means Windows
  # doesn't ask at start, and we remove Block rules created by clicking "Cancel" in that dialog (they take
  # precedence over Allow)
  $base = (& $venvPy -c 'import sys, os; print(os.path.dirname(sys._base_executable))').Trim()
  foreach ($exe in 'pythonw.exe', 'python.exe') {
    $prog = Join-Path $base $exe
    Get-NetFirewallApplicationFilter -Program $prog -ErrorAction SilentlyContinue | Get-NetFirewallRule |
      Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | ForEach-Object { Remove-NetFirewallRule -Name $_.Name; Say "firewall: removed block $($_.DisplayName) ($exe)" "zapora: usunięto blokadę $($_.DisplayName) ($exe)" }
    New-NetFirewallRule -DisplayName "MagneticPlayer ($exe)" -Direction Inbound -Program $prog -Profile Private, Domain -Action Allow | Out-Null
  }
  Say "firewall: opened TCP $port and UDP 47700 for private networks" "zapora: otwarte TCP $port i UDP 47700 dla sieci prywatnych"
} else {
  Say 'firewall: on first start Windows will ask about network access for Python: tick “Private networks” and “Allow”.' `
      'zapora: przy pierwszym starcie Windows zapyta o dostęp Pythona do sieci: zaznacz „Sieci prywatne” i „Zezwalaj”.'
  Say '          “Cancel” blocks the phone for good (fix: run this installer as administrator).' `
      '        „Anuluj” blokuje telefon na stałe (naprawa: ten instalator uruchomiony jako administrator).'
}
$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue)
$pub = @($profiles | Where-Object { $_.NetworkCategory -eq 'Public' })
if ($pub.Count -and $pub.Count -eq $profiles.Count) {
  Say "WARNING: network $($pub.Name -join ', ') is `“Public`”, so the firewall blocks the phone. Settings → Network → Properties → Private network." `
      "UWAGA: sieć $($pub.Name -join ', ') jest `„publiczna`”, a wtedy zapora blokuje telefon. Ustawienia → Sieć → Właściwości → Sieć prywatna."
} elseif ($pub.Count) {
  Say "Note: network $($pub.Name -join ', ') is `“Public`”. If the phone is on it, change it to private (Settings → Network → Properties)." `
      "Uwaga: sieć $($pub.Name -join ', ') jest `„publiczna`”. Jeśli to w niej jest telefon, zmień ją na prywatną (Ustawienia → Sieć → Właściwości)."
}

# autostart at logon (HKCU, no administrator) and start now
# -X utf8: files (config, devices) and the log in UTF-8, not in the Windows code page
$cmd = "`"$pyw`" -X utf8 `"$Dest\magneticplayer_agent.py`""
Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'MagneticPlayer' -Value $cmd
# via WMI so that the agent survives closing this window (and the SSH session)
$r = Invoke-CimMethod Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $Dest }
if ($r.ReturnValue -ne 0) { Start-Process $pyw -ArgumentList "-X utf8 `"$Dest\magneticplayer_agent.py`"" -WorkingDirectory $Dest }
$ok = $false
foreach ($i in 1..30) {
  Start-Sleep -Milliseconds 500
  try { Invoke-RestMethod "http://127.0.0.1:$port/api/ping" -TimeoutSec 2 | Out-Null; $ok = $true; break } catch {}
}
if (-not $ok) { Say "WARNING: the agent isn't responding; log: $Dest\agent.log" "UWAGA: agent nie odpowiada; log: $Dest\agent.log" }

Write-Host ''
Write-Host '================ MagneticPlayer ================'
$joined = $false
if ($env:MP_JOIN) {
  & $venvPy (Join-Path $Dest 'magneticplayer_agent.py') --join $env:MP_JOIN
  $joined = ($LASTEXITCODE -eq 0)
}
if ($joined) { Say " If the phone doesn't pair, get a code manually: `"$venvPy`" `"$Dest\magneticplayer_agent.py`" --pair" `
                  " Gdyby telefon się nie sparował, kod ręczny: `"$venvPy`" `"$Dest\magneticplayer_agent.py`" --pair" }
else { & $venvPy (Join-Path $Dest 'magneticplayer_agent.py') --pair }
Say " uninstall: powershell -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`" [-Purge]" `
    " odinstalowanie: powershell -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`" [-Purge]"
Write-Host '==============================================='
