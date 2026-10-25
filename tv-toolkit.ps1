param([string]$Action = '')
# Frutiger Home - zestaw narzedzi 0.1 alpha
# Laczy sie z TV przez ADB, robi kopie, wylacza (NIE usuwa) reklamy/telemetrie, instaluje ekran glowny, przywraca.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = $PSScriptRoot
$DataDir = Join-Path $Root 'data'
$AppId = 'pl.frutiger.tvhome'
$script:adb = $null
$script:dev = $null
$script:devDir = $null
$script:brand = ''

# ---------------------------------------------------------------- pomocnicze
function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }

function Ask-YN($q, $def = 'n') {
    $a = (Read-Host "$q [t/n, Enter = $def]").Trim().ToLower()
    if ($a -eq '') { $a = $def }
    return ($a -eq 't' -or $a -eq 'tak' -or $a -eq 'y' -or $a -eq 'yes')
}

function Find-Adb {
    $local = Join-Path $Root 'platform-tools\adb.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Say 'Nie znaleziono ADB.' Yellow
    if (Ask-YN 'Pobrac oficjalne narzedzia Google (platform-tools, ok. 10 MB)?' 't') {
        $zip = Join-Path $Root 'platform-tools.zip'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive $zip -DestinationPath $Root -Force
        Remove-Item $zip
        return $local
    }
    throw 'Bez ADB nie da sie kontynuowac.'
}

function Adb {
    $out = & $script:adb -s $script:dev @args 2>&1
    @($out | ForEach-Object { "$_".TrimEnd("`r") })
}
function Sh { Adb shell @args }

function Need-Dev {
    if (-not $script:dev) { Connect-Tv }
    if (-not $script:dev) { throw 'Brak polaczenia z TV.' }
}

# ---------------------------------------------------------------- polaczenie
function Scan-Lan {
    $ip = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '169.254*' -and $_.IPAddress -ne '127.0.0.1' -and $_.InterfaceAlias -notmatch 'vEthernet|VirtualBox|VMware|Loopback' } |
        Select-Object -First 1
    if (-not $ip) { return @() }
    $base = ($ip.IPAddress -split '\.')[0..2] -join '.'
    $jobs = @()
    foreach ($i in 1..254) {
        $c = New-Object Net.Sockets.TcpClient
        $jobs += [pscustomobject]@{ ip = "$base.$i"; c = $c; t = $c.ConnectAsync("$base.$i", 5555) }
    }
    Start-Sleep -Milliseconds 2500
    $found = @($jobs | Where-Object { $_.t.IsCompleted -and -not $_.t.IsFaulted -and $_.c.Connected } | ForEach-Object { $_.ip })
    $jobs | ForEach-Object { $_.c.Close() }
    return $found
}

function Pair-Tv {
    $a = Read-Host 'Adres PAROWANIA z ekranu TV (ip:port)'
    $code = Read-Host 'Kod parowania (6 cyfr)'
    & $script:adb pair $a $code | ForEach-Object { Say "  $_" }
}

function Connect-Tv {
    if (-not $script:adb) { $script:adb = Find-Adb }
    & $script:adb start-server | Out-Null
    Say 'Szukam telewizorow w sieci domowej (port ADB 5555)...' Cyan
    $found = @(Scan-Lan)
    if ($found.Count -gt 0) {
        Say 'Znalezione urzadzenia:' Green
        for ($i = 0; $i -lt $found.Count; $i++) { Say ('  [{0}] {1}' -f ($i + 1), $found[$i]) }
    } else {
        Say 'Nic nie odpowiada na porcie 5555 (nowsze TV uzywaja innego portu - patrz opcja p).' Yellow
    }
    Say '  [r] wpisz adres recznie (np. 192.168.1.50 albo 192.168.1.50:37000)'
    Say '  [p] sparuj kodem (Android 11+: Opcje programisty > Debugowanie bezprzewodowe)'
    $c = Read-Host 'Wybierz numer albo litere'
    $target = $null
    if ($c -eq 'p') { Pair-Tv; $target = Read-Host 'Adres do POLACZENIA z ekranu TV (ip:port)' }
    elseif ($c -eq 'r') { $target = Read-Host 'Adres' }
    elseif ($c -match '^\d+$' -and [int]$c -ge 1 -and [int]$c -le $found.Count) { $target = $found[[int]$c - 1] }
    if (-not $target) { return }
    if ($target -notmatch ':') { $target = "${target}:5555" }

    & $script:adb connect $target | ForEach-Object { Say "  $_" }
    $ok = $false
    for ($t = 0; $t -lt 8; $t++) {
        $state = (& $script:adb -s $target get-state 2>&1 | Out-String).Trim()
        if ($state -eq 'device') { $ok = $true; break }
        if ($state -match 'unauthorized') {
            Say '  Na TV powinno byc okienko "Zezwolic na debugowanie?" - wybierz "Zawsze zezwalaj" i OK.' Yellow
        }
        Start-Sleep -Seconds 3
    }
    if (-not $ok) { Say 'Nie udalo sie polaczyc.' Red; return }

    $script:dev = $target
    $brand = (Sh getprop ro.product.brand | Out-String).Trim()
    $model = (Sh getprop ro.product.model | Out-String).Trim()
    $rel = (Sh getprop ro.build.version.release | Out-String).Trim()
    $sdk = (Sh getprop ro.build.version.sdk | Out-String).Trim()
    $script:brand = $brand.ToLower()
    Say "Polaczono: $brand $model (Android $rel, API $sdk)" Green
    $safe = (($brand + '_' + $model) -replace '[^\w\-]', '_')
    $script:devDir = Join-Path $DataDir $safe
    New-Item -ItemType Directory -Force $script:devDir | Out-Null
    Shot 'polaczono'
}

function Shot($name) {
    $dir = Join-Path $script:devDir 'screenshots'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $f = Join-Path $dir ('{0}-{1}.png' -f (Get-Date -Format 'HHmmss'), $name)
    Sh screencap -p /data/local/tmp/s.png | Out-Null
    Adb pull /data/local/tmp/s.png $f | Out-Null
    Sh rm /data/local/tmp/s.png | Out-Null
    if (Test-Path $f) { Say "  Zrzut ekranu TV: $f" DarkGray } else { Say '  (zrzut ekranu sie nie udal - niektore TV to blokuja)' Yellow }
}

# ---------------------------------------------------------------- kopia zapasowa
function Do-Backup {
    Need-Dev
    $b = Join-Path $script:devDir ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force $b | Out-Null
    Say 'Robie kopie listy aplikacji i ustawien...' Cyan
    Sh pm list packages -f -u | Set-Content (Join-Path $b 'packages-all.txt') -Encoding UTF8
    Sh pm list packages -e | Set-Content (Join-Path $b 'packages-enabled.txt') -Encoding UTF8
    Sh pm list packages -d | Set-Content (Join-Path $b 'packages-disabled.txt') -Encoding UTF8
    foreach ($ns in 'global', 'secure', 'system') {
        Sh settings list $ns | Set-Content (Join-Path $b "settings-$ns.txt") -Encoding UTF8
    }
    Sh getprop | Set-Content (Join-Path $b 'getprop.txt') -Encoding UTF8
    Sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
        Set-Content (Join-Path $b 'home-activity.txt') -Encoding UTF8
    Say "Kopia zapisana w: $b" Green
    Say 'Uwaga: to kopia list i ustawien. Skrypt niczego nie usuwa, tylko wylacza - wiec wystarczy ona do pelnego cofniecia.' DarkGray
    Shot 'kopia'
}

# ---------------------------------------------------------------- odchudzanie
$Protected = '^(android$|com\.android\.(systemui|providers|settings|tv|inputdevices|bluetooth|networkstack|permissioncontroller|packageinstaller|shell|server|certinstaller|keychain|nfc|phone|location|externalstorage|documentsui|inputmethod|managedprovisioning|vending|webview)|com\.google\.android\.(gms|gsf|webview|tts|inputmethod|permissioncontroller|packageinstaller|ext|networkstack|captiveportallogin|setupwizard|tv\.frameworkpackagestubs|tvlauncher|apps\.tv\.launcherx|leanbacklauncher)|com\.mediatek|com\.qualcomm)|(tvinput|tuner|hdmi|cec|bluetooth|wifi|provision|updater|inputmethod|keyboard|webview)'
$Keywords = '(^|\.)(ads?|adservices?|advert\w*|analytics|telemetry|tracking|promo\w*|sponsor\w*|datacollect\w*)(\.|$)'

$AskList = @(
    @{ p = 'com.google.android.feedback'; n = 'Wysylanie opinii do Google (telemetria)'; d = 't' },
    @{ p = 'com.google.android.partnersetup'; n = 'Konfiguracja partnerska Google (ustawienia/reklamy producenta)'; d = 't' },
    @{ p = 'com.google.android.katniss'; n = 'Asystent Google i wyszukiwanie glosowe (przycisk mikrofonu na pilocie)'; d = 'n' },
    @{ p = 'com.google.android.videos'; n = 'Google Play Filmy i Seriale'; d = 'n' },
    @{ p = 'com.google.android.play.games'; n = 'Google Play Gry'; d = 'n' },
    @{ p = 'com.google.android.youtube.tvmusic'; n = 'YouTube Music'; d = 'n' },
    @{ p = 'com.google.android.apps.mediashell'; n = 'Chromecast wbudowany (przesylanie z telefonu na TV)'; d = 'n' },
    @{ p = 'com.google.android.tv.remote.service'; n = 'Pilot w telefonie (usluga Android TV Remote)'; d = 'n' },
    @{ p = 'com.google.android.backdrop'; n = 'Wygaszacz ekranu Ambient'; d = 'n' },
    @{ p = 'com.google.android.apps.tv.dreamx'; n = 'Wygaszacz ekranu Google TV'; d = 'n' },
    @{ p = 'com.google.android.tvrecommendations'; n = 'Rekomendacje na ekranie glownym (potrzebne tylko starszemu ekranowi)'; d = 'n' }
)

function Get-Pkgs($flag) {
    @(Sh pm list packages $flag | ForEach-Object { ("$_" -replace '^package:', '').Trim() } | Where-Object { $_ })
}

function Disable-Pkg($p) {
    $r = (Sh pm disable-user --user 0 $p | Out-String).Trim()
    if ($r -match 'disabled-user') {
        Add-Content (Join-Path $script:devDir 'changes.log') ("DISABLED|{0}|{1}" -f $p, (Get-Date -Format s))
        Say "  wylaczono: $p" Green
        return $true
    }
    Say "  nie udalo sie ($p): $r" Yellow
    return $false
}

function Do-Debloat {
    Need-Dev
    if (-not (Get-ChildItem $script:devDir -Directory -Filter 'backup-*' -ErrorAction SilentlyContinue)) {
        Say 'Najpierw robie kopie zapasowa.' Cyan
        Do-Backup
    }
    $enabled = Get-Pkgs '-e'
    $shown = @{}
    $count = 0

    Say "`n== Czesc 1: znane aplikacje - decydujesz po kolei ==" Cyan
    foreach ($a in $AskList) {
        if ($enabled -notcontains $a.p) { continue }
        $shown[$a.p] = $true
        Say "`n$($a.n)" White
        Say "  pakiet: $($a.p)" DarkGray
        if (Ask-YN '  Wylaczyc?' $a.d) { if (Disable-Pkg $a.p) { $count++ } }
    }
    Shot 'odchudzanie-1'

    Say "`n== Czesc 2: pakiety z 'reklama/analityka/telemetria' w nazwie (domyslnie NIE) ==" Cyan
    $cand = @($enabled | Where-Object { $_ -match $Keywords -and $_ -notmatch $Protected -and -not $shown.ContainsKey($_) })
    if ($cand.Count -eq 0) { Say '  Nic takiego nie znaleziono.' DarkGray }
    foreach ($p in $cand) {
        $shown[$p] = $true
        Say "`n  $p" White
        if (Ask-YN '  Wylaczyc?' 'n') { if (Disable-Pkg $p) { $count++ } }
    }
    Shot 'odchudzanie-2'

    Say "`n== Czesc 3: pozostale pakiety producenta ($($script:brand)) ==" Cyan
    $vendorRx = $null
    if ($script:brand -match 'sony') { $vendorRx = '^com\.sony\.' }
    elseif ($script:brand -match 'tcl') { $vendorRx = '^com\.(tcl|tct)' }
    if ($vendorRx) {
        $vend = @($enabled | Where-Object { $_ -match $vendorRx -and $_ -notmatch $Protected -and -not $shown.ContainsKey($_) })
        if ($vend.Count -gt 0) {
            Say '  Nie znam ich wszystkich, wiec nic nie wylaczam sam. Lista:' DarkGray
            $vend | ForEach-Object { Say "   $_" }
            Say '  Wklej nazwy pakietow do wylaczenia (rozdziel spacja) albo Enter, zeby pominac.' Yellow
            $typed = (Read-Host '  Pakiety').Trim()
            if ($typed) {
                foreach ($p in ($typed -split '\s+')) {
                    if ($vend -notcontains $p) { Say "  pomijam (nie ma na liscie): $p" Yellow; continue }
                    if (Ask-YN "  Na pewno wylaczyc $p ?" 'n') { if (Disable-Pkg $p) { $count++ } }
                }
            }
        } else { Say '  Brak.' DarkGray }
    } else { Say '  (inna marka - pomijam)' DarkGray }
    Shot 'odchudzanie-3'

    Say "`nGotowe. Wylaczono: $count. Sprawdz TV pilotem. Jesli cos nie dziala - opcja 6 (przywroc)." Green
}

# ---------------------------------------------------------------- ekran glowny
function Get-HomePkg {
    $o = @(Sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Where-Object { $_ -match '/' })
    if ($o.Count -eq 0) { return $null }
    return ($o[-1].Trim() -split '/')[0]
}

function Do-Install {
    Need-Dev
    $apk = $null
    $f = Get-ChildItem $Root -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($f) { $apk = $f.FullName }
    $built = Join-Path $Root 'launcher\app\build\outputs\apk\debug\app-debug.apk'
    if (-not $apk -and (Test-Path $built)) { $apk = $built }
    if (-not $apk) { $apk = (Read-Host 'Podaj sciezke do pliku .apk').Trim('"') }
    if (-not (Test-Path $apk)) { Say 'Nie ma takiego pliku.' Red; return }

    $stock = Get-HomePkg
    if ($stock) { Add-Content (Join-Path $script:devDir 'changes.log') ("STOCKHOME|{0}|{1}" -f $stock, (Get-Date -Format s)) }
    Say "Obecny ekran glowny: $stock" DarkGray
    Say 'Instaluje...' Cyan
    Adb install -r $apk | ForEach-Object { Say "  $_" }
    Add-Content (Join-Path $script:devDir 'changes.log') ("INSTALLED|{0}|{1}" -f $AppId, (Get-Date -Format s))
    Sh am start -n "$AppId/.MainActivity" | Out-Null
    Start-Sleep -Seconds 3
    Shot 'nowy-ekran'

    Say 'Ustawiam jako domyslny ekran glowny (dziala na nowszym Androidzie)...' Cyan
    Sh cmd package set-home-activity "$AppId/.MainActivity" | ForEach-Object { Say "  $_" }
    Say 'Teraz nacisnij HOME na pilocie TV.' Yellow
    if (-not (Ask-YN 'Czy widzisz nowy ekran Frutiger Home?' 't')) {
        if ($stock -and $stock -ne $AppId) {
            Say "Moge wylaczyc stary ekran glowny ($stock). To odwracalne (opcja 6)." Yellow
            Say 'UWAGA: na niektorych TV stary ekran obsluguje tez przelaczanie wejsc i Asystenta.' Yellow
            if (Ask-YN 'Wylaczyc stary ekran glowny?' 'n') {
                [void](Disable-Pkg $stock)
                Start-Sleep -Seconds 2
                Sh input keyevent KEYCODE_HOME | Out-Null
                Start-Sleep -Seconds 2
                Shot 'po-wylaczeniu-starego'
            }
        }
    }
}

function Do-Wallpapers {
    Need-Dev
    $dir = (Read-Host 'Folder ze zdjeciami (jpg/png/webp)').Trim('"')
    if (-not (Test-Path $dir)) { Say 'Nie ma takiego folderu.' Red; return }
    $remote = "/sdcard/Android/data/$AppId/files/wallpapers"
    Sh mkdir -p $remote | Out-Null
    $files = @(Get-ChildItem $dir -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' })
    foreach ($f in $files) { Say "  $($f.Name)"; Adb push $f.FullName "$remote/$($f.Name)" | Out-Null }
    Sh am force-stop $AppId | Out-Null
    Sh am start -n "$AppId/.MainActivity" | Out-Null
    Say "Wgrano: $($files.Count) plikow." Green
}

# ---------------------------------------------------------------- przywracanie
function Do-Restore {
    Need-Dev
    $log = Join-Path $script:devDir 'changes.log'
    if (-not (Test-Path $log)) { Say 'Brak zmian do cofniecia.' Yellow; return }
    $pkgs = @(Get-Content $log | Where-Object { $_ -like 'DISABLED|*' } | ForEach-Object { ($_ -split '\|')[1] } | Select-Object -Unique)
    Say "Przywracam $($pkgs.Count) wylaczonych aplikacji..." Cyan
    foreach ($p in $pkgs) {
        $r = (Sh pm enable --user 0 $p | Out-String).Trim()
        Say "  $p : $r"
    }
    $installed = (Sh pm list packages $AppId | Out-String)
    if ($installed -match $AppId) {
        if (Ask-YN 'Odinstalowac Frutiger Home? (to jedyna rzecz, ktora skrypt odinstalowuje - Twoj stary ekran wroci)' 't') {
            Adb uninstall $AppId | ForEach-Object { Say "  $_" }
        }
    }
    Move-Item $log (Join-Path $script:devDir ('changes-cofniete-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log'))
    Sh input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 2
    Shot 'po-przywroceniu'
    Say 'Gotowe. Jesli ekran glowny nadal dziwny - zrestartuj TV (odlacz zasilanie na 30 s).' Green
}

# ---------------------------------------------------------------- menu
function Menu {
    while ($true) {
        Say "`n=== Frutiger Home - zestaw 0.1 alpha ===" Cyan
        if ($script:dev) { Say "Polaczono z: $($script:dev)" Green } else { Say 'Nie polaczono z TV' Yellow }
        Say ' 1  Polacz z TV (znajdz w sieci)'
        Say ' 2  Kopia zapasowa + zrzut ekranu'
        Say ' 3  Odchudzanie (reklamy, telemetria) - pyta o kazda rzecz'
        Say ' 4  Zainstaluj ekran glowny Frutiger Home'
        Say ' 5  Wgraj wlasne tapety (folder ze zdjeciami)'
        Say ' 6  PRZYWROC wszystko'
        Say ' 7  Zrzut ekranu TV teraz'
        Say ' 0  Wyjscie'
        $x = Read-Host 'Wybierz'
        try {
            switch ($x) {
                '1' { Connect-Tv }
                '2' { Do-Backup }
                '3' { Do-Debloat }
                '4' { Do-Install }
                '5' { Do-Wallpapers }
                '6' { Do-Restore }
                '7' { Need-Dev; Shot 'reczny' }
                '0' { return }
            }
        } catch { Say "Blad: $_" Red }
    }
}

if ($Action -eq 'restore') {
    try { Do-Restore } catch { Say "Blad: $_" Red }
    Read-Host 'Nacisnij Enter, zeby zamknac'
} else {
    Menu
}
