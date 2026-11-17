# Instalacja agenta MagneticPlayer na Windows 10/11 (instalacja lekka: bez administratora, bez torrentów,
# usypiania i Wake-on-LAN). Uruchom w PowerShellu jako zwykły użytkownik:
#   powershell -ExecutionPolicy Bypass -File install.ps1
# Z innego serwera w sieci robi to za ciebie:  irm http://<serwer>:8765/i/<kod>/ps | iex
# Zmienne: $env:MP_PORT (port; domyślnie pierwszy wolny od 8765), $env:MP_JOIN (adres /i/<kod>: zgłoszenie po
# instalacji, telefon paruje się sam), $env:MP_NO_MPV=1 (bez pobierania mpv, czyli bez ekranu).
# Odinstalowanie: powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\MagneticPlayer\uninstall.ps1"
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'  # pasek postępu Invoke-WebRequest spowalnia pobieranie kilkukrotnie
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$env:PYTHONUTF8 = '1'  # polskie znaki w komunikatach agenta (konsola Windows ma inną stronę kodową)
$env:PYTHONIOENCODING = 'utf-8'
$Src = $PSScriptRoot
$Dest = Join-Path $env:LOCALAPPDATA 'MagneticPlayer'
$ConfDir = Join-Path $HOME '.config\magneticplayer'
$Conf = Join-Path $ConfDir 'config.json'

function Find-Python {
  # python.exe z WindowsApps to tylko skrót do Microsoft Store, nie Python
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

Write-Host '== MagneticPlayer: instalacja lekka (Windows)'
$py = Find-Python
if (-not $py -and (Get-Command winget -ErrorAction SilentlyContinue)) {
  Write-Host '== brak Pythona: instaluję Python 3.12 dla tego użytkownika (winget, bez administratora)'
  winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements --disable-interactivity | Out-Host
  $py = Find-Python
}
if (-not $py) {
  # bez wingeta (Windows 10 LTSC, stary Windows 10) albo gdy zawiódł: oficjalny instalator z python.org, po cichu, dla użytkownika
  $pyVer = '3.12.10'
  $pyArch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
  $pyExe = Join-Path $env:TEMP "python-$pyVer-$pyArch.exe"
  Write-Host "== brak Pythona: pobieram Python $pyVer z python.org (instalacja dla tego użytkownika, bez administratora)"
  Invoke-WebRequest "https://www.python.org/ftp/python/$pyVer/python-$pyVer-$pyArch.exe" -OutFile $pyExe -UseBasicParsing
  $p = Start-Process $pyExe -ArgumentList '/quiet', 'InstallAllUsers=0', 'PrependPath=0', 'Include_test=0', 'Include_launcher=0', 'Shortcuts=0' -Wait -PassThru
  Remove-Item $pyExe -Force -ErrorAction SilentlyContinue
  if ($p.ExitCode -ne 0) { Write-Host "instalator Pythona zakończył się kodem $($p.ExitCode)" }
  $py = Find-Python
  if (-not $py) { throw 'Python się nie zainstalował. Zainstaluj go z python.org i uruchom ponownie.' }
}
Write-Host "python: $py"

# zatrzymanie poprzedniej wersji (aktualizacja)
Get-CimInstance Win32_Process -Filter "Name like 'python%.exe'" | Where-Object { $_.CommandLine -like "*$Dest\magneticplayer_agent.py*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Host "== agent -> $Dest"
New-Item -ItemType Directory -Force -Path $Dest, (Join-Path $Dest 'web'), $ConfDir | Out-Null
Copy-Item (Join-Path $Src '*.py') $Dest -Force
Copy-Item (Join-Path $Src 'web\*') (Join-Path $Dest 'web') -Force
# skrypty obok agenta: z nich serwer instaluje agenta na kolejnych komputerach (mp_install.py)
foreach ($f in 'install.ps1', 'uninstall.ps1', 'join.ps1', 'install.sh', 'install-root.sh', 'uninstall.sh', 'join.sh') {
  if (Test-Path (Join-Path $Src $f)) { Copy-Item (Join-Path $Src $f) $Dest -Force }
}

Write-Host '== napisy, Chromecast, QR: venv'
$venvPy = Join-Path $Dest 'venv\Scripts\python.exe'
if (-not (Test-Path $venvPy)) { & $py -m venv (Join-Path $Dest 'venv') }
if (Test-Path $venvPy) {
  & $venvPy -m pip install -q --disable-pip-version-check --upgrade subliminal pychromecast qrcode 2>&1 | Out-Host
  if ($LASTEXITCODE -ne 0) { Write-Host 'UWAGA: część pakietów się nie zainstalowała (napisy albo Chromecast mogą nie działać)' }
} else {
  Write-Host 'UWAGA: venv się nie udał; agent działa bez napisów i Chromecasta ze strony'
  $venvPy = $py
}
$pyw = Join-Path (Split-Path $venvPy) 'pythonw.exe'
if (-not (Test-Path $pyw)) { $pyw = $venvPy }

# mpv: ekran (film na tym komputerze). Oficjalna paczka portable z GitHuba, obok agenta.
$mpvExe = Join-Path $Dest 'mpv\mpv.exe'
if (Test-Path $mpvExe) {  # mpv, który się nie uruchamia (np. brak DLL), pobieramy jeszcze raz
  & (Join-Path $Dest 'mpv\mpv.com') --version *> $null
  if ($LASTEXITCODE -ne 0) { Remove-Item -Recurse -Force (Join-Path $Dest 'mpv') }
}
if (-not $env:MP_NO_MPV -and -not (Get-Command mpv -ErrorAction SilentlyContinue) -and -not (Test-Path $mpvExe)) {
  Write-Host '== mpv (ekran): pobieram z github.com/mpv-player/mpv'
  try {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/mpv-player/mpv/releases/latest' -UseBasicParsing
    # wersja MinGW: kompilacja MSVC potrzebuje środowiska Visual C++, którego świeży Windows nie ma (0xC0000135)
    $arch = 'x86_64'; if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $arch = 'aarch64' }
    $asset = $rel.assets | Where-Object { $_.name -like "mpv-*-$arch-w64-mingw32-full.zip" } | Select-Object -First 1
    if (-not $asset) { throw 'brak paczki dla Windows w ostatnim wydaniu' }
    $zip = Join-Path $env:TEMP $asset.name
    Invoke-WebRequest $asset.browser_download_url -OutFile $zip -UseBasicParsing
    Remove-Item -Recurse -Force (Join-Path $Dest 'mpv') -ErrorAction SilentlyContinue
    Expand-Archive $zip (Join-Path $Dest 'mpv') -Force
    Remove-Item $zip -Force
    Write-Host "mpv $($rel.tag_name)"
  } catch {
    Write-Host "UWAGA: mpv się nie pobrał ($($_.Exception.Message)); ten komputer nie będzie ekranem"
  }
}

# port: wolny, gdy nie podano (8765 bywa zajęty przez inny program)
function Test-Port([int]$p) {
  # wyłącznie (jak agent): zajęty jest także port, na którym ktoś słucha tylko na 127.0.0.1
  try { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $p); $l.ExclusiveAddressUse = $true; $l.Start(); $l.Stop() } catch { return $false }
  try { $c = [Net.Sockets.TcpClient]::new(); $c.Connect('127.0.0.1', $p); $c.Close(); return $false } catch { return $true }
}
$cfg = @{}
if (Test-Path $Conf) { $cfg = Get-Content $Conf -Raw -Encoding UTF8 | ConvertFrom-Json }
$port = $null
if ($env:MP_PORT) { $port = [int]$env:MP_PORT }
else {
  # najpierw port z poprzedniej instalacji (poprzedni agent już zatrzymany), potem kolejne od 8765
  $cands = @(); if ($cfg.port) { $cands += [int]$cfg.port }; $cands += 8765..8785
  foreach ($p in $cands) { if (Test-Port $p) { $port = $p; break } }
}
if (-not $port) { throw 'Nie znalazłem wolnego portu 8765-8785 (podaj $env:MP_PORT)' }
if ($port -ne 8765) { Write-Host "port 8765 zajęty: agent na $port" }

Write-Host "== config: backend=none power=false wake=false port=$port"
@'
import json, os, sys
p, port = sys.argv[1], int(sys.argv[2])
cfg = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}
cfg.update(port=port, backend="none", power=False, wake=False)
os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(cfg, open(p, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
'@ | & $venvPy - $Conf $port

# zapora: reguła wymaga administratora; bez niego Windows sam zapyta przy pierwszym uruchomieniu
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')
if ($admin) {
  Get-NetFirewallRule -DisplayName 'MagneticPlayer*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  New-NetFirewallRule -DisplayName "MagneticPlayer (TCP $port)" -Direction Inbound -Protocol TCP -LocalPort $port -Profile Private, Domain -Action Allow | Out-Null
  New-NetFirewallRule -DisplayName 'MagneticPlayer (UDP 47700)' -Direction Inbound -Protocol UDP -LocalPort 47700 -Profile Private, Domain -Action Allow | Out-Null
  # agent chodzi na pythonw interpretera bazowego (venv go tylko uruchamia). Reguła dla programu: Windows nie pyta
  # przy starcie, a reguły Block z kliknięcia „Anuluj” w tamtym oknie (mają pierwszeństwo przed Allow) usuwamy
  $base = (& $venvPy -c 'import sys, os; print(os.path.dirname(sys._base_executable))').Trim()
  foreach ($exe in 'pythonw.exe', 'python.exe') {
    $prog = Join-Path $base $exe
    Get-NetFirewallApplicationFilter -Program $prog -ErrorAction SilentlyContinue | Get-NetFirewallRule |
      Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | ForEach-Object { Remove-NetFirewallRule -Name $_.Name; Write-Host "zapora: usunięto blokadę $($_.DisplayName) ($exe)" }
    New-NetFirewallRule -DisplayName "MagneticPlayer ($exe)" -Direction Inbound -Program $prog -Profile Private, Domain -Action Allow | Out-Null
  }
  Write-Host 'zapora: otwarte TCP' $port 'i UDP 47700 dla sieci prywatnych'
} else {
  Write-Host 'zapora: przy pierwszym starcie Windows zapyta o dostęp Pythona do sieci: zaznacz „Sieci prywatne” i „Zezwalaj”.'
  Write-Host '        „Anuluj” blokuje telefon na stałe (naprawa: ten instalator uruchomiony jako administrator).'
}
$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue)
$pub = @($profiles | Where-Object { $_.NetworkCategory -eq 'Public' })
if ($pub.Count -and $pub.Count -eq $profiles.Count) {
  Write-Host "UWAGA: sieć $($pub.Name -join ', ') jest „publiczna”, a wtedy zapora blokuje telefon. Ustawienia → Sieć → Właściwości → Sieć prywatna."
} elseif ($pub.Count) {
  Write-Host "Uwaga: sieć $($pub.Name -join ', ') jest „publiczna”. Jeśli to w niej jest telefon, zmień ją na prywatną (Ustawienia → Sieć → Właściwości)."
}

# autostart przy logowaniu (HKCU, bez administratora) i start teraz
# -X utf8: pliki (config, devices) i log w UTF-8, nie w stronie kodowej Windows
$cmd = "`"$pyw`" -X utf8 `"$Dest\magneticplayer_agent.py`""
Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'MagneticPlayer' -Value $cmd
# przez WMI, żeby agent przeżył zamknięcie tego okna (i sesji SSH)
$r = Invoke-CimMethod Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $Dest }
if ($r.ReturnValue -ne 0) { Start-Process $pyw -ArgumentList "-X utf8 `"$Dest\magneticplayer_agent.py`"" -WorkingDirectory $Dest }
$ok = $false
foreach ($i in 1..30) {
  Start-Sleep -Milliseconds 500
  try { Invoke-RestMethod "http://127.0.0.1:$port/api/ping" -TimeoutSec 2 | Out-Null; $ok = $true; break } catch {}
}
if (-not $ok) { Write-Host "UWAGA: agent nie odpowiada; log: $Dest\agent.log" }

Write-Host ''
Write-Host '================ MagneticPlayer ================'
$joined = $false
if ($env:MP_JOIN) {
  & $venvPy (Join-Path $Dest 'magneticplayer_agent.py') --join $env:MP_JOIN
  $joined = ($LASTEXITCODE -eq 0)
}
if ($joined) { Write-Host " Gdyby telefon się nie sparował, kod ręczny: `"$venvPy`" `"$Dest\magneticplayer_agent.py`" --pair" }
else { & $venvPy (Join-Path $Dest 'magneticplayer_agent.py') --pair }
Write-Host " odinstalowanie: powershell -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`" [-Purge]"
Write-Host '==============================================='
