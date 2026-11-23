# Configura TV Liberata - programma per PC Windows (si lancia con Configura-TV-Liberata.bat).
#
# Configura UNA VOLTA una Fire TV (o una TV Android TV) con TV Liberata dalla rete di casa: dopo, il PC non serve piu'.
# Serve su Fire OS 7.7, dove Amazon non permette alle app di configurare la TV da sole.
#
# Cosa fa:
#   1. scarica gli strumenti ADB ufficiali di Google accanto allo script (solo la prima volta);
#   2. cerca da solo le TV sulla rete (porta 5555 del debug ADB) o usa l'IP che gli dai;
#   3. aspetta il "Consenti sempre" sulla TV;
#   4. menu: [1] Configura TV Liberata  [2] Annulla tutto e torna come prima  [0] Esci.
# Prima di cambiare qualsiasi cosa salva un backup in backup\<nome-tv>-<data> accanto allo script.
# Niente root, niente app di sistema disinstallate (solo disattivate, e riattivabili). Il debug ADB resta acceso.
#
# Uso avanzato:  Configura-TV-Liberata.ps1 [-Ip 192.168.1.60] [-Nome Salotto] [-Canale produzione|test]
#                                          [-Azione configura|annulla] [-Prova]
#   -Prova  mostra cosa farebbe, senza cambiare niente sulla TV (legge soltanto).
#   -Liste  cartella con pacchetto-app.json e pulizia-fireos.json da usare al posto di quelli del repository.
param(
    [string]$Ip = '',
    [string]$Nome = '',
    [ValidateSet('produzione', 'test')][string]$Canale = 'produzione',
    [ValidateSet('', 'configura', 'annulla')][string]$Azione = '',
    [string]$Liste = '',
    [switch]$Prova
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Here = $PSScriptRoot
$Repo = 'turillo/tv-liberata-pubblico'
$Raw = "https://raw.githubusercontent.com/$Repo/main"
$ApkUrl = "https://github.com/$Repo/releases/latest/download/tv-liberata.apk"
$Pkg = 'com.gathin.tvhome.liberata'
$Guard = "$Pkg/com.gathin.tvhome.FireHomeGuard"
$Dream = "$Pkg/com.gathin.tvhome.ClockDream"
$Log = Join-Path $Here ("Configura-TV-Liberata-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
try { Start-Transcript -Path $Log -Append | Out-Null } catch { }

$Fatto = New-Object System.Collections.ArrayList
$NonRiuscito = New-Object System.Collections.ArrayList
function Ok([string]$t) { [void]$Fatto.Add($t); Write-Host "  OK  $t" -ForegroundColor Green }
function Ko([string]$t) { [void]$NonRiuscito.Add($t); Write-Host "  !!  $t" -ForegroundColor Yellow }
function Passo([string]$t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Chiedi([string]$domanda) { $r = Read-Host $domanda; if ($null -eq $r) { return '' }; return $r.Trim() }
function SiNo([string]$domanda) {
    while ($true) {
        $r = (Chiedi "$domanda (S/N)").ToUpper()
        if ($r -eq 'S') { return $true }
        if ($r -eq 'N') { return $false }
    }
}

# ---- 1. strumenti ADB ufficiali -----------------------------------------------------------------------------
Passo 'Strumenti ADB'
$Adb = Join-Path $Here 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    Write-Host 'Scarico gli strumenti ADB ufficiali di Google (una volta sola)...'
    $zip = Join-Path $Here 'platform-tools.zip'
    try {
        Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $Here -Force
        Remove-Item $zip -Force
    } catch {
        Write-Host "Download non riuscito: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host 'Controlla la connessione internet del PC e riprova.'
        Read-Host 'Premi Invio per chiudere'; exit 1
    }
}
if (-not (Test-Path $Adb)) { Write-Host 'ADB non trovato dopo il download.' -ForegroundColor Red; Read-Host 'Invio per chiudere'; exit 1 }
Write-Host "ADB: $Adb"

function AdbRun([string[]]$a) { return ((& $Adb @a 2>&1) | Out-String).Trim() }
$Script:Tv = ''
function Sh([string]$cmd) { return ((& $Adb -s $Script:Tv shell $cmd 2>&1) | Out-String).Trim() }
# Fai: un comando che CAMBIA la TV. In prova viene solo mostrato.
function Fai([string]$cmd) {
    if ($Prova) { Write-Host "  [prova] $cmd" -ForegroundColor DarkGray; return '' }
    $out = Sh $cmd
    Add-Content -Path $Log -Value "adb shell $cmd => $out" -ErrorAction SilentlyContinue
    return $out
}
function Stato([string]$serial) { return ((& $Adb -s $serial get-state 2>&1) | Out-String).Trim() }

# ---- 2. trova la TV -----------------------------------------------------------------------------------------
function Sottoreti {
    $out = @()
    try {
        $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object {
            $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -ne 'WellKnown' }
        foreach ($i in $ips) { $p = $i.IPAddress -split '\.'; $out += "$($p[0]).$($p[1]).$($p[2])" }
    } catch {
        foreach ($l in (ipconfig | Select-String 'IPv4')) {
            if ($l -match '(\d+)\.(\d+)\.(\d+)\.(\d+)\s*$' -and $Matches[1] -ne '127' -and $Matches[1] -ne '169') {
                $out += "$($Matches[1]).$($Matches[2]).$($Matches[3])"
            }
        }
    }
    return $out | Select-Object -Unique
}

function Scansiona {
    $trovati = @()
    foreach ($net in Sottoreti) {
        Write-Host "Cerco TV su $net.1-254 (porta 5555)..."
        $prove = @()
        for ($h = 1; $h -le 254; $h++) {
            $c = New-Object System.Net.Sockets.TcpClient
            $prove += [pscustomobject]@{ Ip = "$net.$h"; Client = $c; Task = $c.ConnectAsync("$net.$h", 5555) }
        }
        try { [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]($prove | ForEach-Object { $_.Task }), 2500) } catch { }
        foreach ($p in $prove) {
            if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) { $trovati += $p.Ip }
            try { $p.Client.Close() } catch { }
        }
    }
    return $trovati
}

function Descrivi([string]$ip) {
    $s = "${ip}:5555"
    AdbRun @('connect', $s) | Out-Null
    Start-Sleep -Milliseconds 800
    $st = Stato $s
    if ($st -ne 'device') { return [pscustomobject]@{ Ip = $ip; Testo = "$ip  (aspetta il consenso sulla TV)" } }
    $Script:Tv = $s
    $mod = Sh 'getprop ro.product.model'; $man = Sh 'getprop ro.product.manufacturer'
    $nomeTv = Sh 'settings get global device_name'
    $os = if ($man -eq 'Amazon') { Sh 'getprop ro.build.version.name' } else { "Android $(Sh 'getprop ro.build.version.release')" }
    return [pscustomobject]@{ Ip = $ip; Testo = "$ip  $man $mod  ($nomeTv)  $os" }
}

Passo 'Ricerca della TV'
if (-not $Ip) {
    $ips = @(Scansiona)
    if ($ips.Count -eq 0) {
        Write-Host 'Nessuna TV trovata con il debug ADB acceso.'
        Write-Host 'Sulla TV: Impostazioni > La mia Fire TV > Opzioni sviluppatore > Debug ADB: attivo.'
        Write-Host '(L''indirizzo IP e'' in Impostazioni > La mia Fire TV > Informazioni > Rete, o sulla pagina di TV Liberata.)'
        $Ip = Chiedi 'Scrivi l''indirizzo IP della TV'
    } else {
        $lista = @($ips | ForEach-Object { Descrivi $_ })
        if ($lista.Count -eq 1) {
            Write-Host "Trovata: $($lista[0].Testo)"
            if (SiNo 'E'' questa?') { $Ip = $lista[0].Ip } else { $Ip = Chiedi 'Scrivi l''indirizzo IP della TV' }
        } else {
            for ($i = 0; $i -lt $lista.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $lista[$i].Testo) }
            $n = Chiedi 'Numero della TV da configurare (0 = scrivo io l''IP)'
            if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $lista.Count) { $Ip = $lista[[int]$n - 1].Ip }
            else { $Ip = Chiedi 'Scrivi l''indirizzo IP della TV' }
        }
    }
}
$Script:Tv = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }

# ---- 3. consenso sulla TV -----------------------------------------------------------------------------------
Passo 'Collegamento'
AdbRun @('connect', $Script:Tv) | Out-Null
$t0 = Get-Date
$detto = $false
while ((Stato $Script:Tv) -ne 'device') {
    if (-not $detto) {
        Write-Host ''
        Write-Host '  GUARDA LA TV: compare "Consentire il debug USB?".' -ForegroundColor Yellow
        Write-Host '  Spunta "Consenti sempre da questo computer" e premi OK.' -ForegroundColor Yellow
        $detto = $true
    }
    if (((Get-Date) - $t0).TotalSeconds -gt 180) {
        Write-Host 'Nessun consenso in 3 minuti. Controlla che la TV sia accesa e riprova.' -ForegroundColor Red
        Read-Host 'Invio per chiudere'; exit 1
    }
    Start-Sleep -Seconds 2
    AdbRun @('connect', $Script:Tv) | Out-Null
}
$FireOs = (Sh 'getprop ro.product.manufacturer') -eq 'Amazon'
$Sdk = Sh 'getprop ro.build.version.sdk'
Write-Host ("Collegato: {0} {1}, {2}" -f (Sh 'getprop ro.product.manufacturer'), (Sh 'getprop ro.product.model'),
    $(if ($FireOs) { Sh 'getprop ro.build.version.name' } else { "Android $(Sh 'getprop ro.build.version.release')" }))
if ([int]$Sdk -lt 26) { Write-Host 'Questa TV ha Android piu'' vecchio di 8: TV Liberata non funziona qui.' -ForegroundColor Red; Read-Host 'Invio'; exit 1 }
if ($Prova) { Write-Host 'MODALITA'' PROVA: non cambio niente sulla TV.' -ForegroundColor Magenta }

# ---- utilita' ---------------------------------------------------------------------------------------------
function Scarica([string]$url, [string]$dest) {
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    return (Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)
}
function LeggiJson([string]$nome) {
    if ($Liste) { return (Get-Content (Join-Path $Liste $nome) -Raw -Encoding UTF8 | ConvertFrom-Json) }
    $f = Join-Path $env:TEMP "tvliberata-$nome"
    try { Invoke-WebRequest -Uri "$Raw/$nome" -OutFile $f -UseBasicParsing; return (Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json) }
    catch { Write-Host "Non riesco a leggere $nome dal repository: $($_.Exception.Message)" -ForegroundColor Red; return $null }
}
function Pacchetti([string]$opz) {
    return @(((& $Adb -s $Script:Tv shell "pm list packages $opz" 2>&1) | ForEach-Object { ($_ -replace '^package:', '').Trim() }) | Where-Object { $_ })
}
function Installata([string]$p) { return (Sh "pm path $p") -match 'package:' }
function ServiziConGuard([string]$lista) {
    $l = if ($lista -eq 'null' -or -not $lista) { @() } else { @($lista -split ':' | Where-Object { $_ }) }
    $l = @($l | Where-Object { $_ -ne $Guard -and $_ -ne "$Pkg/.FireHomeGuard" })
    return (@($l) + $Guard) -join ':'
}
function ServiziSenzaGuard([string]$lista) {
    $l = if ($lista -eq 'null' -or -not $lista) { @() } else { @($lista -split ':' | Where-Object { $_ }) }
    return (@($l | Where-Object { $_ -notmatch [regex]::Escape("$Pkg/") })) -join ':'
}

# Il nome che l'utente vede (dalla Home della TV), con il pacchetto tra parentesi.
function NomeApp([string]$p) {
    $l = Sh "dumpsys package $p | grep -m1 'versionName'"
    return "$p  $($l.Trim())"
}
# Scelta da un elenco numerato: T = tutte, N = nessuna, oppure numeri separati da virgola.
function Scegli($elenco, [string]$domanda) {
    Write-Host $domanda
    $r = Chiedi '  T = tutte, N = nessuna, oppure i numeri separati da virgola (es. 1,3)'
    if ($r.ToUpper() -eq 'T') { return @($elenco) }
    $out = @()
    if ($r -and $r.ToUpper() -ne 'N') {
        foreach ($n in ($r -split '[,; ]+')) { if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $elenco.Count) { $out += $elenco[[int]$n - 1] } }
    }
    return @($out | Select-Object -Unique)
}

# ---- configura ------------------------------------------------------------------------------------------------
function Configura {
    if (-not $Nome) { $Nome = Chiedi 'Nome di questa TV (es. Salotto)' }
    if (-not $Nome) { $Nome = 'TV' }
    $nomeFile = ($Nome -replace '[^A-Za-z0-9_-]', '-')

    Passo '1. Backup'
    $dir = Join-Path $Here ("backup\{0}-{1}" -f $nomeFile, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force $dir | Out-Null
    (Pacchetti '') | Out-File -Encoding ascii "$dir\pacchetti.txt"
    (Pacchetti '-d') | Out-File -Encoding ascii "$dir\pacchetti-disattivati.txt"
    (Pacchetti '-3') | Out-File -Encoding ascii "$dir\pacchetti-terze-parti.txt"
    foreach ($ns in 'secure', 'global', 'system') { Sh "settings list $ns" | Out-File -Encoding utf8 "$dir\settings-$ns.txt" }
    Sh 'getprop' | Out-File -Encoding utf8 "$dir\getprop.txt"
    $toccate = 'secure enabled_accessibility_services', 'secure accessibility_enabled', 'secure always_on_vpn_app',
        'secure screensaver_components', 'system screen_off_timeout', 'global alarm_manager_constants'
    $toccate | ForEach-Object { "$_=$(Sh "settings get $_")" } | Out-File -Encoding ascii "$dir\impostazioni-toccate.txt"
    "tv=$($Script:Tv)", "nome=$Nome", "fireos=$FireOs", "data=$(Get-Date -Format s)" | Out-File -Encoding ascii "$dir\tv.txt"
    if ((Get-Item "$dir\pacchetti.txt").Length -lt 100 -or (Get-Item "$dir\settings-secure.txt").Length -lt 100) {
        Write-Host 'Backup incompleto: mi fermo senza cambiare niente.' -ForegroundColor Red; return
    }
    Ok "backup in $dir"

    Passo '2. Liste dal repository'
    $pacchetto = LeggiJson 'pacchetto-app.json'
    $pulizia = if ($FireOs) { LeggiJson 'pulizia-fireos.json' } else { $null }
    if (-not $pacchetto) { Ko 'pacchetto-app.json non letto: mi fermo'; return }
    $mai = if ($pulizia) { @($pulizia.mai) } else { @($Pkg) }
    $tenere = @($pacchetto.app | ForEach-Object { $_.pacchetto })
    # Le app del pacchetto per questa TV: "solo" fireos o androidtv.
    $piattaforma = if ($FireOs) { 'fireos' } else { 'androidtv' }
    $mie = @($pacchetto.app | Where-Object { -not $_.solo -or $_.solo -eq $piattaforma })

    Passo '3. TV Liberata'
    if (Installata $Pkg) { Ok "TV Liberata gia' installata ($((Sh "dumpsys package $Pkg | grep versionName") -replace '\s+', ' '))" }
    else {
        $apk = Join-Path $env:TEMP 'tv-liberata.apk'
        if ($Prova) { Write-Host "  [prova] scarico $ApkUrl e lo installo" }
        elseif (Scarica $ApkUrl $apk) {
            $r = AdbRun @('-s', $Script:Tv, 'install', '-r', $apk)
            if ($r -match 'Success') { Ok 'TV Liberata installata' } else { Ko "TV Liberata non installata: $r"; return }
        } else { Ko 'TV Liberata non scaricata'; return }
    }

    Passo '4. Altre app: togliere o nascondere (solo app normali, con la tua conferma)'
    # Solo app vere: niente pacchetti di sistema aggiornabili (overlay delle lingue e simili).
    $terze = @(Pacchetti '-3' | Where-Object { $tenere -notcontains $_ -and $mai -notcontains $_ -and $_ -ne $Pkg -and
        $_ -notmatch '\.overlay\.' -and (Sh "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $_") -match '/' })
    # Solo ora, alla prima configurazione: le app installate dopo restano dove sono.
    $tolte = @()
    $Script:Nascoste = @()
    if ($terze.Count -eq 0) { Write-Host '  nessuna' }
    else {
        Write-Host 'Queste app non fanno parte di TV Liberata:'
        for ($i = 0; $i -lt $terze.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), (NomeApp $terze[$i])) }
        $scelte = Scegli $terze 'Quali DISINSTALLARE?'
        foreach ($p in $scelte) {
            if ($Prova) { Write-Host "  [prova] adb uninstall $p"; $tolte += $p; continue }
            $o = AdbRun @('-s', $Script:Tv, 'uninstall', $p)
            if ($o -match 'Success') { $tolte += $p; Ok "disinstallata $p" } else { Ko "non disinstallata $p ($o)" }
        }
        $restano = @($terze | Where-Object { $tolte -notcontains $_ })
        if ($restano.Count -gt 0) {
            Write-Host 'Restano installate:'
            for ($i = 0; $i -lt $restano.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), (NomeApp $restano[$i])) }
            $Script:Nascoste = @(Scegli $restano 'Quali NASCONDERE dalla Home di TV Liberata? (restano installate; si rimettono da Impostazioni TV Liberata > Sistema > App nascoste)')
            if ($Script:Nascoste.Count -gt 0) { Ok ("{0} app da nascondere" -f $Script:Nascoste.Count) }
        }
    }
    if (-not $Prova) { $tolte | Out-File -Encoding ascii "$dir\app-disinstallate.txt" }

    Passo '5. Pulizia delle app Amazon (solo disattivate, mai disinstallate)'
    $applicate = @()
    if (-not $FireOs) { Write-Host '  non e'' una Fire TV: niente pulizia Amazon' }
    elseif (-not $pulizia) { Ko 'pulizia-fireos.json non letto: pulizia saltata' }
    else {
        $tutti = Pacchetti ''
        $giaOff = Pacchetti '-d'
        foreach ($e in $pulizia.disattiva) {
            $p = $e.pacchetto
            if ($mai -contains $p) { continue }
            if ($tutti -notcontains $p) { continue }
            if ($giaOff -contains $p) { continue }
            $o = Fai "pm disable-user --user 0 $p"
            if ($Prova -or $o -match 'disabled-user') { $applicate += $p } else { Write-Host "  $p : $o" -ForegroundColor DarkGray }
        }
        Ok ("{0} app Amazon disattivate" -f $applicate.Count)
    }
    if (-not $Prova) { $applicate | Out-File -Encoding ascii "$dir\pulizia-applicata.txt" }

    Passo '6. App di TV Liberata (Netflix, Prime Video, RaiPlay, YouTube, store, Aerial Views)'
    foreach ($a in $mie) {
        if ($a.pacchetto -eq $Pkg) { continue }
        if (Installata $a.pacchetto) { Ok "$($a.nome) gia' installata"; continue }
        if ($a.tipo -eq 'sistema') { Ko "$($a.nome) non c'e' su questa TV"; continue }
        if ($a.tipo -eq 'libera') {
            $f = Join-Path $env:TEMP "$($a.pacchetto).apk"
            if ($Prova) { Write-Host "  [prova] scarico $($a.apk), controllo SHA-256, installo"; continue }
            if (-not (Scarica $a.apk $f)) { Ko "$($a.nome): download non riuscito"; continue }
            if ($a.sha256 -and ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $a.sha256.ToLower())) { Ko "$($a.nome): impronta SHA-256 diversa, non installata"; continue }
            $o = AdbRun @('-s', $Script:Tv, 'install', '-r', $f)
            if ($o -match 'Success') { Ok "$($a.nome) installata" } else { Ko "$($a.nome) non installata ($o)" }
        } elseif ($a.tipo -eq 'store') {
            $store = if ($FireOs) { "amzn://apps/android?p=$($a.pacchetto)" } else { "market://details?id=$($a.pacchetto)" }
            if ($Prova) { Write-Host "  [prova] apro la pagina dello store di $($a.nome) ($store)"; continue }
            Sh "am start -a android.intent.action.VIEW -d $store" | Out-Null
            Write-Host "  Sulla TV si e' aperta la pagina di $($a.nome): premi Scarica (o Ottieni)." -ForegroundColor Yellow
            Read-Host '  Quando ha finito, premi Invio qui'
            if (Installata $a.pacchetto) { Ok "$($a.nome) installata dall'Appstore" } else { Ko "$($a.nome) non ancora installata" }
        }
    }

    Passo '7. Permessi e configurazione di TV Liberata'
    Fai "pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null
    foreach ($op in 'REQUEST_INSTALL_PACKAGES', 'ACTIVATE_VPN', 'GET_USAGE_STATS', 'WRITE_SETTINGS') { Fai "appops set $Pkg $op allow" | Out-Null }
    $cfg = Join-Path $env:TEMP 'dispositivo.json'
    $lista = (@($Script:Nascoste) | ForEach-Object { '"' + $_ + '"' }) -join ','
    ('{{"nome":"{0}","canale":"{1}","nascondi":[{2}]}}' -f ($Nome -replace '"', ''), $Canale, $lista) | Out-File -Encoding ascii $cfg
    if ($Prova) { Write-Host "  [prova] configurazione del dispositivo: nome $Nome, canale $Canale, nascoste: $(@($Script:Nascoste) -join ', ')" }
    else {
        AdbRun @('-s', $Script:Tv, 'push', $cfg, "/sdcard/Android/data/$Pkg/files/dispositivo.json") | Out-Null
        $o = Sh "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.DeviceConfig -a com.gathin.tvhome.DEVICE_CONFIG"
        if ($o -match 'result=') { Ok "nome $Nome, canale $Canale" } else { Ko "configurazione del dispositivo: $o" }
        if ((Sh "dumpsys package $Pkg | grep 'WRITE_SECURE_SETTINGS: granted=true'")) { Ok 'permessi concessi' } else { Ko 'permesso WRITE_SECURE_SETTINGS non concesso' }
    }

    Passo '8. TV Liberata come Home'
    if ($FireOs) {
        # La Home Amazon resta installata e attiva: il servizio di TV Liberata la sostituisce quando compare.
        $alarm = Sh 'settings get global alarm_manager_constants'
        if ($alarm -notmatch 'min_futurity=0') {
            $rest = (($alarm -replace '^null$', '') -split ',' | Where-Object { $_ -and $_ -notmatch '^min_futurity=' }) -join ','
            Fai ("settings put global alarm_manager_constants " + $(if ($rest) { "$rest,min_futurity=0" } else { 'min_futurity=0' })) | Out-Null
        }
        Fai "dumpsys deviceidle whitelist +$Pkg" | Out-Null
        $nuova = ServiziConGuard (Sh 'settings get secure enabled_accessibility_services')
        $legato = $false
        for ($t = 0; $t -lt 2 -and -not $legato; $t++) {
            Fai 'settings delete secure enabled_accessibility_services' | Out-Null
            if (-not $Prova) { Start-Sleep -Seconds 2 }
            Fai "settings put secure enabled_accessibility_services $nuova" | Out-Null
            Fai 'settings put secure accessibility_enabled 1' | Out-Null
            if ($Prova) { break }
            for ($i = 0; $i -lt 15; $i++) { if ((Sh 'dumpsys accessibility') -match 'FireHomeGuard|Home Fire TV|Fire TV home') { $legato = $true; break }; Start-Sleep -Seconds 1 }
        }
        if (-not $Prova) {
            Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
            Sh "am start -n $Pkg/com.gathin.tvhome.MainActivity" | Out-Null
            Start-Sleep -Seconds 3
            $davanti = ''
            for ($i = 0; $i -lt 3; $i++) {
                Sh 'input keyevent KEYCODE_HOME' | Out-Null; Start-Sleep -Seconds 4
                $davanti = Sh 'dumpsys window windows | grep mCurrentFocus'
                if ($davanti -match [regex]::Escape("$Pkg/")) { break }
            }
            if ($legato -and $davanti -match [regex]::Escape("$Pkg/")) { Ok 'Home = TV Liberata (servizio Home attivo)' }
            else {
                Ko "Home non presa (servizio attivo: $legato). Rimetto la Home Amazon."
                $senza = ServiziSenzaGuard (Sh 'settings get secure enabled_accessibility_services')
                Sh 'settings delete secure enabled_accessibility_services' | Out-Null
                if ($senza) { Sh "settings put secure enabled_accessibility_services $senza" | Out-Null }
                Sh 'input keyevent KEYCODE_HOME' | Out-Null
            }
        }
    } else {
        $o = Fai "cmd package set-home-activity $Pkg/com.gathin.tvhome.MainActivity"
        if ($Prova -or $o -match 'Success') { Ok 'Home = TV Liberata' } else { Ko "Home: $o" }
    }

    Passo '9. Screensaver e filtro DNS'
    Fai "settings put secure screensaver_components $Dream" | Out-Null
    $timeout = Sh 'settings get system screen_off_timeout'
    if ($timeout -notmatch '^\d+$' -or [long]$timeout -gt 1800000) { Fai 'settings put system screen_off_timeout 300000' | Out-Null }
    Ok 'screensaver = TV Liberata'
    Fai "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.FilterReceiver -a com.gathin.tvhome.FILTER --ez on true --ez ota true" | Out-Null
    Fai "settings put secure always_on_vpn_app $Pkg" | Out-Null
    Ok 'filtro DNS acceso (con il blocco degli aggiornamenti di sistema)'

    Passo '10. Riavvio e verifica'
    if ($Prova) { Write-Host '  [prova] riavvio della TV e verifica finale'; Riepilogo; return }
    # "adb reboot", non "shell reboot": su Fire OS 7.7 la shell non ha il permesso e la TV non si riavvia.
    AdbRun @('-s', $Script:Tv, 'reboot') | Out-Null
    Write-Host 'La TV si riavvia (circa 1-2 minuti)...'
    Start-Sleep -Seconds 40
    $t0 = Get-Date
    while ((Stato $Script:Tv) -ne 'device' -and ((Get-Date) - $t0).TotalSeconds -lt 180) { Start-Sleep -Seconds 5; AdbRun @('connect', $Script:Tv) | Out-Null }
    if ((Stato $Script:Tv) -ne 'device') { Ko 'dopo il riavvio la TV non risponde ad ADB: controlla a mano' ; Riepilogo; return }
    $uptime = [double](((Sh 'cat /proc/uptime') -split ' ')[0])
    if ($uptime -gt 600) { Ko 'la TV non si e'' riavviata: riavviala a mano (tieni premuti Seleziona e Play) e controlla' }
    Start-Sleep -Seconds 20
    Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 5
    $davanti = Sh 'dumpsys window windows | grep mCurrentFocus'
    if ($davanti -match [regex]::Escape("$Pkg/")) { Ok 'dopo il riavvio la Home e'' TV Liberata' } else { Ko "dopo il riavvio davanti c'e': $davanti" }
    # Il filtro riparte dopo l'avvio (anche qualche decina di secondi): lo si aspetta fino a un minuto.
    $vpn = $false
    for ($i = 0; $i -lt 12 -and -not $vpn; $i++) {
        $vpn = (Sh 'dumpsys connectivity') -match 'type: VPN\[\], state: CONNECTED'
        if (-not $vpn) { Start-Sleep -Seconds 5 }
    }
    if ($vpn) { Ok 'filtro DNS attivo' } else { Ko 'filtro DNS non attivo dopo un minuto' }
    if ($FireOs) { if ((Sh 'dumpsys accessibility') -match 'FireHomeGuard|Home Fire TV') { Ok 'servizio Home attivo' } else { Ko 'servizio Home non attivo' } }
    Riepilogo
}

function Riepilogo {
    Passo 'Configurazione completata'
    foreach ($f in $Fatto) { Write-Host "  fatto:          $f" -ForegroundColor Green }
    foreach ($f in $NonRiuscito) { Write-Host "  NON riuscito:   $f" -ForegroundColor Yellow }
    Write-Host "`nIl debug ADB resta acceso. Registro completo: $Log"
}

# ---- annulla ------------------------------------------------------------------------------------------------
function Annulla {
    $dirs = @(Get-ChildItem -Directory (Join-Path $Here 'backup') -ErrorAction SilentlyContinue | Where-Object {
        (Test-Path "$($_.FullName)\tv.txt") -and ((Get-Content "$($_.FullName)\tv.txt") -contains "tv=$($Script:Tv)") } | Sort-Object { $_.CreationTime })
    if ($dirs.Count -eq 0) { Write-Host "Nessun backup di questa TV ($($Script:Tv)) accanto allo script: non so cosa rimettere." -ForegroundColor Red; return }
    $dir = $dirs[0].FullName   # il primo backup: lo stato di prima di TV Liberata
    Write-Host "Torno allo stato del backup $dir"
    if (-not (SiNo 'Procedo?')) { return }

    Passo 'Riattivo le app disattivate'
    foreach ($d in ($dirs | ForEach-Object { "$($_.FullName)\pulizia-applicata.txt" })) {
        if (Test-Path $d) { foreach ($p in Get-Content $d) { if ($p) { Fai "pm enable $p" | Out-Null } } }
    }
    $prima = Get-Content "$dir\pacchetti-disattivati.txt"
    foreach ($p in (Pacchetti '-d')) { if ($prima -notcontains $p -and $p -notmatch '^com\.gathin\.') { Fai "pm enable $p" | Out-Null } }
    Ok 'app riattivate'

    Passo 'Tolgo Home, servizio, filtro e permessi di TV Liberata'
    $senza = ServiziSenzaGuard (Sh 'settings get secure enabled_accessibility_services')
    Fai 'settings delete secure enabled_accessibility_services' | Out-Null
    if ($senza) { Fai "settings put secure enabled_accessibility_services $senza" | Out-Null }
    Fai "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.FilterReceiver -a com.gathin.tvhome.FILTER --ez on false" | Out-Null
    foreach ($l in Get-Content "$dir\impostazioni-toccate.txt") {
        $k, $v = $l -split '=', 2; $ns, $name = $k -split ' ', 2
        if ($name -eq 'enabled_accessibility_services' -or $name -eq 'accessibility_enabled') { continue }
        if ($v -eq 'null' -or -not $v) { Fai "settings delete $ns $name" | Out-Null } else { Fai "settings put $ns $name $v" | Out-Null }
    }
    Fai "pm revoke $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null
    foreach ($op in 'REQUEST_INSTALL_PACKAGES', 'ACTIVATE_VPN', 'GET_USAGE_STATS', 'WRITE_SETTINGS') { Fai "appops set $Pkg $op default" | Out-Null }
    if (-not $FireOs) { Write-Host '  Sulla TV Android scegli di nuovo la Home originale quando te lo chiede (tasto Home).' }
    Fai 'input keyevent KEYCODE_HOME' | Out-Null
    Ok 'screensaver, tempo dello schermo e filtro come prima; Home originale'

    $tolte = @()
    foreach ($d in $dirs) { if (Test-Path "$($d.FullName)\app-disinstallate.txt") { $tolte += Get-Content "$($d.FullName)\app-disinstallate.txt" } }
    $tolte = @($tolte | Where-Object { $_ } | Select-Object -Unique)
    if ($tolte.Count -gt 0) {
        Write-Host "`nQueste app erano state disinstallate e NON vengono reinstallate (riscaricale dall'Appstore se servono):"
        $tolte | ForEach-Object { Write-Host "  $_" }
    }
    Write-Host "`nTV Liberata resta installata: per toglierla, Impostazioni > Applicazioni > TV Liberata > Disinstalla."
    Riepilogo
}

# ---- menu -----------------------------------------------------------------------------------------------------
if ($Azione -eq 'configura') { Configura }
elseif ($Azione -eq 'annulla') { Annulla }
else {
    Write-Host "`n  [1] Configura TV Liberata`n  [2] Annulla tutto e torna come prima`n  [0] Esci"
    switch (Chiedi 'Scelta') {
        '1' { Configura }
        '2' { Annulla }
        default { }
    }
}
try { Stop-Transcript | Out-Null } catch { }
if (-not $Azione) { Read-Host "`nPremi Invio per chiudere" | Out-Null }
