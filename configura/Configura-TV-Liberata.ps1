# Configura TV Liberata - programma per PC Windows (si lancia con Configura-TV-Liberata.bat).
#
# Configura UNA VOLTA una Fire TV (o una TV Android TV) con TV Liberata dalla rete di casa: dopo, il PC non serve piu'.
# Serve su Fire OS 7.7, dove Amazon non permette alle app di configurare la TV da sole.
#
# Cosa fa:
#   1. scarica gli strumenti ADB ufficiali di Google accanto allo script (solo la prima volta);
#   2. cerca da solo le TV sulla rete (porta 5555 del debug ADB) o usa l'IP che gli dai;
#   3. aspetta il "Consenti sempre" sulla TV;
#   4. menu: [1] Configura TV Liberata  [2] Annulla tutto e torna come prima  [0] Esci;
#   5. fa tutte le domande SUBITO, poi lavora da solo mostrando un cruscotto (checklist, registro, barra).
# Prima di cambiare qualsiasi cosa salva un backup in backup\<nome-tv>-<data> accanto allo script.
# Niente root, niente app di sistema disinstallate (solo disattivate, e riattivabili). Il debug ADB resta acceso.
#
# Uso avanzato:  Configura-TV-Liberata.ps1 [-Ip 192.168.1.60] [-Nome Salotto] [-Canale produzione|test]
#                                          [-Azione configura|annulla] [-Cartolina si|no] [-Prova] [-Ascii]
#   -Cartolina  la risposta alla domanda "Vuoi far sapere a Syntax che usi TV Liberata?" (senza: la chiede).
#   -Prova  mostra cosa farebbe, senza cambiare niente sulla TV (legge soltanto).
#   -Liste  cartella con pacchetto-app.json e pulizia-fireos.json da usare al posto di quelli del repository.
#   -Ascii  barra del progresso con # e . (se il terminale non mostra bene i blocchi pieni).
param(
    [string]$Ip = '',
    [string]$Nome = '',
    [ValidateSet('produzione', 'test')][string]$Canale = 'produzione',
    [ValidateSet('', 'configura', 'annulla')][string]$Azione = '',
    [ValidateSet('', 'si', 'no')][string]$Cartolina = '',
    [string]$Liste = '',
    [switch]$Prova,
    [switch]$Ascii
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
function Log([string]$t) { try { Add-Content -Path $Log -Value ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $t) -Encoding UTF8 } catch { } }

# =================================================================================================================
#  ASPETTO: terminale verde su nero
# =================================================================================================================
try {
    $ui = $Host.UI.RawUI
    $ui.BackgroundColor = 'Black'
    $ui.ForegroundColor = 'Green'
    $ui.WindowTitle = 'TV Liberata - Configurazione'
} catch { }
Clear-Host

function Batti([string]$t, [int]$ms = 4, [string]$c = 'Green') {
    # Effetto telescrivente: una lettera alla volta.
    foreach ($ch in $t.ToCharArray()) { Write-Host -NoNewline $ch -ForegroundColor $c; if ($ms -gt 0) { Start-Sleep -Milliseconds $ms } }
    Write-Host ''
}
function Linea([string]$ch = '=', [string]$c = 'DarkGreen') { Write-Host ('  ' + ($ch * 64)) -ForegroundColor $c }

$Banner = @'
 _______     __  _     ___ ____  _____ ____      _  _____  _
|_   _\ \   / / | |   |_ _| __ )| ____|  _ \    / \|_   _|/ \
  | |  \ \ / /  | |    | ||  _ \|  _| | |_) |  / _ \ | | / _ \
  | |   \ V /   | |___ | || |_) | |___|  _ <  / ___ \| |/ ___ \
  |_|    \_/    |_____|___|____/|_____|_| \_\/_/   \_\_/_/   \_\
'@
$ScrittaFatto = @'
 _____ _  _____ _____ ___  _
|  ___/ \|_   _|_   _/ _ \| |
| |_ / _ \ | |   | || | | | |
|  _/ ___ \| |   | || |_| |_|
|_|/_/   \_\_|   |_| \___/(_)
'@
$ScrittaAnnullato = @'
    _    _   _ _   _ _   _ _     _        _  _____ ___
   / \  | \ | | \ | | | | | |   | |      / \|_   _/ _ \
  / _ \ |  \| |  \| | | | | |   | |     / _ \ | || | | |
 / ___ \| |\  | |\  | |_| | |___| |___ / ___ \| || |_| |
/_/   \_\_| \_|_| \_|\___/|_____|_____/_/   \_\_| \___/
'@

Write-Host ''
foreach ($riga in ($Banner -split "`r?`n")) { Write-Host ("  " + $riga) -ForegroundColor Green; Start-Sleep -Milliseconds 120 }
Write-Host -NoNewline (' ' * 57)
Batti 'by Syntax' 90
Write-Host ''
Linea
Batti '  Configurazione dal PC  -  una volta sola, poi il PC non serve piu''.' 8
Linea

# =================================================================================================================
#  CRUSCOTTO: checklist in alto, registro che scorre in mezzo, barra del progresso in basso
# =================================================================================================================
$Script:Cruscotto = $false      # true mentre il cruscotto e' sullo schermo
$Script:Semplice = $false       # true se il terminale non permette di riposizionare il cursore
$Script:Titolo = ''
$Script:Passi = @()
$Script:StatoPassi = @()        # ' ' da fare, '>' in corso, 'x' fatto, '!' con avvisi
$Script:Corrente = -1
$Script:Sub = 0.0               # avanzamento dentro il passo in corso (0..1)
$Script:Voci = New-Object System.Collections.ArrayList
$Script:T0 = Get-Date
$Script:InizioPasso = Get-Date
$Script:Giro = 0
$Spin = @('|', '/', '-', '\')
$Pieno = if ($Ascii) { '#' } else { [string][char]0x2588 }
$Vuoto = if ($Ascii) { '.' } else { [string][char]0x2591 }

$Fatto = New-Object System.Collections.ArrayList
$NonRiuscito = New-Object System.Collections.ArrayList

function Larg { try { return [Math]::Max(60, [Math]::Min([Console]::WindowWidth - 1, 90)) } catch { return 78 } }
function Alt { try { return [Math]::Max(22, [Console]::WindowHeight) } catch { return 30 } }
function Pad([string]$t, [int]$w) { if ($t.Length -gt $w) { return $t.Substring(0, $w) }; return $t.PadRight($w) }

function Disegna {
    if (-not $Script:Cruscotto -or $Script:Semplice) { return }
    try {
        $w = Larg; $h = Alt
        $n = [Math]::Max(5, $h - 11)
        [Console]::CursorVisible = $false
        [Console]::SetCursorPosition(0, 0)
        # riga 1-2: titolo
        Write-Host -NoNewline (Pad ("  TV LIBERATA  -  $($Script:Titolo)") ($w - 10)) -ForegroundColor Green
        Write-Host (Pad 'by Syntax' 10) -ForegroundColor DarkGreen
        Write-Host (Pad ('  ' + ('=' * ($w - 4))) $w) -ForegroundColor DarkGreen
        # riga 3: checklist compatta
        $len = 2
        Write-Host -NoNewline '  '
        $lampeggia = ((Get-Date).Millisecond -lt 500)
        for ($i = 0; $i -lt $Script:Passi.Count; $i++) {
            $s = $Script:StatoPassi[$i]
            switch ($s) {
                'x' { Write-Host -NoNewline '[x]' -ForegroundColor Green }
                '!' { Write-Host -NoNewline '[!]' -ForegroundColor Yellow }
                '>' { if ($lampeggia) { Write-Host -NoNewline '[>]' -ForegroundColor White } else { Write-Host -NoNewline '[ ]' -ForegroundColor Green } }
                default { Write-Host -NoNewline '[ ]' -ForegroundColor DarkGreen }
            }
            $len += 3
        }
        $fatti = @($Script:StatoPassi | Where-Object { $_ -eq 'x' -or $_ -eq '!' }).Count
        $coda = "   $fatti/$($Script:Passi.Count) passi"
        Write-Host (Pad $coda ($w - $len)) -ForegroundColor DarkGreen
        Write-Host (Pad '' $w)
        # riga 5-6: passo in corso
        $tit = if ($Script:Corrente -ge 0 -and $Script:Corrente -lt $Script:Passi.Count) { "  [{0:D2}/{1:D2}] {2}" -f ($Script:Corrente + 1), $Script:Passi.Count, $Script:Passi[$Script:Corrente].ToUpper() } else { '' }
        Write-Host (Pad $tit $w) -ForegroundColor Green
        Write-Host (Pad ('  ' + ('-' * ($w - 4))) $w) -ForegroundColor DarkGreen
        # registro: le ultime n voci
        $da = [Math]::Max(0, $Script:Voci.Count - $n)
        for ($r = 0; $r -lt $n; $r++) {
            $k = $da + $r
            if ($k -lt $Script:Voci.Count) {
                $v = $Script:Voci[$k]
                $es = $v.Esito
                if ($es -eq '') { $es = $Spin[$Script:Giro % 4] }
                $posto = $w - 7 - $es.Length
                $testo = $v.Testo
                if ($testo.Length -gt $posto - 2) { $testo = $testo.Substring(0, [Math]::Max(0, $posto - 5)) + '...' }
                $resto = [Math]::Max(1, $posto - $testo.Length)
                $riemp = if ($v.Puntini -and $resto -gt 2) { ' ' + ('.' * ($resto - 1)) } else { ' ' * $resto }
                Write-Host -NoNewline '    > ' -ForegroundColor DarkGreen
                Write-Host -NoNewline $testo -ForegroundColor $v.Colore
                Write-Host -NoNewline $riemp -ForegroundColor DarkGreen
                Write-Host (' ' + $es) -ForegroundColor $v.ColoreEsito
            } else { Write-Host (Pad '' $w) }
        }
        # barra
        $tot = [Math]::Max(1, $Script:Passi.Count)
        $avanz = $fatti
        if ($Script:Corrente -ge 0 -and $Script:Corrente -lt $Script:Passi.Count -and $Script:StatoPassi[$Script:Corrente] -eq '>') { $avanz += [Math]::Min(0.99, [Math]::Max(0, $Script:Sub)) }
        $perc = [int][Math]::Floor(100 * $avanz / $tot)
        $tempo = (Get-Date) - $Script:T0
        $orologio = '{0:D2}:{1:D2}' -f [int][Math]::Floor($tempo.TotalMinutes), $tempo.Seconds
        $bw = $w - 22
        $pieni = [int][Math]::Floor($bw * $avanz / $tot)
        Write-Host (Pad '' $w)
        Write-Host (Pad ('  ' + ('=' * ($w - 4))) $w) -ForegroundColor DarkGreen
        Write-Host -NoNewline '  [' -ForegroundColor DarkGreen
        Write-Host -NoNewline ($Pieno * $pieni) -ForegroundColor Green
        Write-Host -NoNewline ($Vuoto * ($bw - $pieni)) -ForegroundColor DarkGreen
        Write-Host -NoNewline ']' -ForegroundColor DarkGreen
        Write-Host (Pad ("{0,5}%   {1}" -f $perc, $orologio) ($w - $bw - 3)) -ForegroundColor Green
        Write-Host -NoNewline (Pad ('  ' + ('=' * ($w - 4))) $w) -ForegroundColor DarkGreen
    } catch {
        $Script:Semplice = $true
    }
}

# Fa girare la rotellina per $ms millisecondi (ridisegnando il cruscotto).
function Gira([int]$ms = 250) {
    if (-not $Script:Cruscotto -or $Script:Semplice) { if ($ms -ge 1000) { Start-Sleep -Milliseconds $ms } elseif ($ms -gt 0) { Start-Sleep -Milliseconds ([Math]::Min($ms, 150)) }; return }
    $fine = (Get-Date).AddMilliseconds($ms)
    do { $Script:Giro++; Disegna; Start-Sleep -Milliseconds 90 } while ((Get-Date) -lt $fine)
}

# Aggiunge una riga al registro, con la rotellina accesa. Restituisce la riga.
function Voce([string]$t, [string]$c = 'Green', [bool]$puntini = $true) {
    $v = [pscustomobject]@{ Testo = $t; Esito = ''; Colore = $c; ColoreEsito = 'Green'; Puntini = $puntini }
    [void]$Script:Voci.Add($v)
    Log "  > $t"
    if ($Script:Cruscotto -and -not $Script:Semplice) { Disegna }
    return $v
}
function Chiudi($v, [string]$esito = 'OK', [string]$c = 'Green') {
    if ($Script:Cruscotto -and -not $Script:Semplice) { Gira 220 }
    $v.Esito = $esito; $v.ColoreEsito = $c
    Log "    = $esito  ($($v.Testo))"
    if ($Script:Cruscotto -and -not $Script:Semplice) { Disegna }
    else {
        Write-Host "    > $($v.Testo) ... $esito" -ForegroundColor $c
    }
}
function Nota([string]$t, [string]$c = 'DarkGreen') {
    $v = Voce $t $c $false
    $v.Esito = ' '
    if ($Script:Cruscotto -and -not $Script:Semplice) { Disegna } else { Write-Host "    $t" -ForegroundColor $c }
}

function Ok([string]$t) {
    [void]$Fatto.Add($t)
    if ($Script:Cruscotto) { $v = Voce $t; Chiudi $v 'OK' 'Green' }
    else { Write-Host '  [ ' -NoNewline -ForegroundColor DarkGreen; Write-Host 'OK' -NoNewline -ForegroundColor Green; Write-Host " ] $t" -ForegroundColor Green; Log "OK  $t" }
}
function Ko([string]$t) {
    [void]$NonRiuscito.Add($t)
    if ($Script:Cruscotto) {
        $Script:PassoConAvvisi = $true
        $v = Voce $t 'Yellow'; Chiudi $v '!!' 'Yellow'
    } else { Write-Host '  [ ' -NoNewline -ForegroundColor DarkGreen; Write-Host '!!' -NoNewline -ForegroundColor Yellow; Write-Host " ] $t" -ForegroundColor Yellow; Log "!!  $t" }
}

# Avviso senza riga nuova nel registro (la riga col '!!' c'e' gia').
function Avviso([string]$t) { [void]$NonRiuscito.Add($t); $Script:PassoConAvvisi = $true; Log "!!  $t" }

# Titolo di una fase prima del cruscotto.
function Passo([string]$t) {
    Write-Host ''
    Write-Host '  >> ' -NoNewline -ForegroundColor Green
    Batti $t.ToUpper() 6
    Linea '-'
    Log "== $t"
}

# Apre il cruscotto con l'elenco dei passi.
function ApriCruscotto([string]$titolo, [string[]]$passi) {
    $Script:Titolo = $titolo
    $Script:Passi = $passi
    $Script:StatoPassi = @($passi | ForEach-Object { ' ' })
    $Script:Corrente = -1
    $Script:Voci.Clear()
    $Script:T0 = Get-Date
    Clear-Host
    $Script:Cruscotto = $true
    Disegna
    if ($Script:Semplice) { Write-Host "`n  $titolo" -ForegroundColor Green }
}
# Passa al passo successivo (ogni passo dura almeno 1,5 secondi, cosi' niente sfarfalla).
$Script:PassoConAvvisi = $false
function ProssimoPasso {
    if ($Script:Corrente -ge 0) {
        $durata = ((Get-Date) - $Script:InizioPasso).TotalMilliseconds
        if ($durata -lt 1500) { $Script:Sub = 0.99; Gira ([int](1500 - $durata)) }
        $Script:StatoPassi[$Script:Corrente] = if ($Script:PassoConAvvisi) { '!' } else { 'x' }
    }
    $Script:PassoConAvvisi = $false
    $Script:Corrente++
    $Script:Sub = 0.0
    $Script:InizioPasso = Get-Date
    if ($Script:Corrente -lt $Script:Passi.Count) {
        $Script:StatoPassi[$Script:Corrente] = '>'
        Log "== $($Script:Passi[$Script:Corrente])"
        if ($Script:Semplice) { Write-Host ''; Write-Host ("  [{0:D2}/{1:D2}] {2}" -f ($Script:Corrente + 1), $Script:Passi.Count, $Script:Passi[$Script:Corrente].ToUpper()) -ForegroundColor Green }
    }
    Disegna
}
function ChiudiCruscotto {
    ProssimoPasso   # chiude l'ultimo passo
    Gira 600
    $Script:Cruscotto = $false
    try { [Console]::CursorVisible = $true } catch { }
}

function Chiedi([string]$domanda) {
    Write-Host ''
    Write-Host "  $domanda" -ForegroundColor Green
    Write-Host '  > ' -NoNewline -ForegroundColor Green
    $r = [Console]::ReadLine()
    if ($null -eq $r) { return '' }; return $r.Trim()
}
function SiNo([string]$domanda) {
    while ($true) {
        $r = (Chiedi "$domanda (S/N)").ToUpper()
        if ($r -eq 'S') { return $true }
        if ($r -eq 'N') { return $false }
    }
}
function Esci([string]$msg) {
    Write-Host ''
    Write-Host "  $msg" -ForegroundColor Red
    Log "ERRORE: $msg"
    [void](Chiedi 'Premi Invio per chiudere')
    exit 1
}
# Rotellina "in linea" (prima del cruscotto): mostra il testo e gira finche' $test non e' vero o scade il tempo.
function AspettaInLinea([string]$testo, [scriptblock]$test, [int]$secondi) {
    $fine = (Get-Date).AddSeconds($secondi); $i = 0
    while ((Get-Date) -lt $fine) {
        if (& $test) { Write-Host ("`r    > $testo ... OK   ") -ForegroundColor Green; return $true }
        for ($k = 0; $k -lt 10; $k++) { Write-Host -NoNewline ("`r    > $testo ... " + $Spin[$i % 4] + ' ') -ForegroundColor Green; $i++; Start-Sleep -Milliseconds 100 }
    }
    Write-Host ("`r    > $testo ... scaduto") -ForegroundColor Yellow
    return $false
}

# =================================================================================================================
#  1. STRUMENTI ADB UFFICIALI
# =================================================================================================================
Passo 'Strumenti ADB'
$Adb = Join-Path $Here 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    Write-Host '    > Scarico da Google gli strumenti per parlare con la TV (una volta sola)...' -ForegroundColor Green
    $zip = Join-Path $Here 'platform-tools.zip'
    try {
        Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $Here -Force
        Remove-Item $zip -Force
    } catch {
        Esci "Download non riuscito: $($_.Exception.Message). Controlla la connessione internet del PC e riprova."
    }
}
if (-not (Test-Path $Adb)) { Esci 'ADB non trovato dopo il download.' }
Ok 'strumenti ADB pronti'

function AdbRun([string[]]$a) {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    try { return ((& $Adb @a 2>&1 | ForEach-Object { $_.ToString() }) | Out-String).Trim() }
    finally { $ErrorActionPreference = $eap }
}
$Script:Tv = ''
# I messaggi di errore di adb diventano testo normale (niente scritte rosse a schermo).
function Sh([string]$cmd) {
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    try { return ((& $Adb -s $Script:Tv shell $cmd 2>&1 | ForEach-Object { $_.ToString() }) | Out-String).Trim() }
    finally { $ErrorActionPreference = $eap }
}
# Fai: un comando che CAMBIA la TV. In prova viene solo mostrato.
function Fai([string]$cmd) {
    if ($Prova) { Log "[prova] $cmd"; return '' }
    $out = Sh $cmd
    Log "adb shell $cmd => $out"
    return $out
}
function Stato([string]$serial) { return (AdbRun @('-s', $serial, 'get-state')) }

# =================================================================================================================
#  2. TROVA LA TV
# =================================================================================================================
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
        $prove = @()
        for ($h = 1; $h -le 254; $h++) {
            $c = New-Object System.Net.Sockets.TcpClient
            $prove += [pscustomobject]@{ Ip = "$net.$h"; Client = $c; Task = $c.ConnectAsync("$net.$h", 5555) }
        }
        # rotellina mentre aspetta le risposte (circa 3 secondi)
        $i = 0
        for ($k = 0; $k -lt 30; $k++) { Write-Host -NoNewline ("`r    > Cerco TV su $net.1-254 ... " + $Spin[$i % 4] + ' ') -ForegroundColor Green; $i++; Start-Sleep -Milliseconds 100 }
        $n = 0
        foreach ($p in $prove) {
            if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) { $trovati += $p.Ip; $n++ }
            try { $p.Client.Close() } catch { }
        }
        Write-Host ("`r    > Cerco TV su $net.1-254 ... {0} trovate   " -f $n) -ForegroundColor Green
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
    $sdk = Sh 'getprop ro.build.version.sdk'
    $vecchia = if ($sdk -match '^\d+$' -and [int]$sdk -lt 26) { '  << TROPPO VECCHIA per TV Liberata' } else { '' }
    return [pscustomobject]@{ Ip = $ip; Testo = "$ip  $man $mod  ($nomeTv)  $os$vecchia" }
}


# =================================================================================================================
#  TV TROPPO VECCHIE (1.1.0): TV Liberata vuole Android 8 (SDK 26), cioe' Fire OS 7 o Android TV 8.
# =================================================================================================================
# Nome e anno dei modelli Amazon piu' comuni, dal codice di ro.product.model.
$ModelliAmazon = @{
    'AFTB' = 'Fire TV (1a generazione, 2014)'; 'AFTM' = 'Fire TV Stick (1a generazione, 2014)'
    'AFTS' = 'Fire TV (2a generazione, 2015)'; 'AFTT' = 'Fire TV Stick (2a generazione, 2016)'
    'AFTN' = 'Fire TV (3a generazione, 2017)'; 'AFTMM' = 'Fire TV Stick 4K (2018)'
    'AFTR' = 'Fire TV Cube (1a generazione, 2018)'; 'AFTA' = 'Fire TV Cube (2a generazione, 2019)'
    'AFTSS' = 'Fire TV Stick Lite (2020)'; 'AFTSSS' = 'Fire TV Stick (3a generazione, 2020)'
    'AFTKA' = 'Fire TV Stick 4K Max (2021)'; 'AFTGAZL' = 'Fire TV Cube (3a generazione, 2022)'
    'AFTKM' = 'Fire TV Stick 4K (2a generazione, 2023)'; 'AFTKRT' = 'Fire TV Stick 4K Max (2a generazione, 2023)'
}
# "Fire OS 5" da "Fire OS 5.2.9.5 (...)", oppure "Android 5.1".
function SistemaBreve([bool]$fire, [string]$nomeVersione, [string]$release) {
    if ($fire -and $nomeVersione -match 'Fire OS (\d+)') { return "Fire OS $($Matches[1])" }
    return "Android $release"
}
function NomeTv([string]$man, [string]$mod) {
    if ($man -eq 'Amazon' -and $ModelliAmazon.ContainsKey($mod)) { return "Questa $($ModelliAmazon[$mod])" }
    if ($man -eq 'Amazon') { return 'Questa Fire TV' }
    return 'Questa TV'
}
# Riquadro giallo e uscita: niente altre domande, niente cambiato sulla TV.
function TroppoVecchia([string]$man, [string]$mod, [string]$sistema) {
    $chi = NomeTv $man $mod
    # "Fire TV Stick (2a generazione, 2016)" + "Fire OS 5" -> "Fire TV Stick (2a generazione, 2016, Fire OS 5)"
    $chi = if ($chi.EndsWith(')')) { $chi.Substring(0, $chi.Length - 1) + ", $sistema)" } else { "$chi ($sistema)" }
    $righe = @(
        "$chi e' troppo vecchia per TV Liberata.",
        '',
        'Serve una Fire TV con Fire OS 7 o piu'' recente (es. Fire TV Stick 4K / 4K Max),',
        'oppure un dispositivo Google TV / Android TV (Android 8 o piu'' recente).',
        '',
        'Attenzione: i modelli nuovi con Vega OS non permettono di installare app.',
        '',
        'Sulla TV non ho cambiato niente.'
    )
    $w = ($righe | Measure-Object -Property Length -Maximum).Maximum + 4
    Write-Host ''
    Write-Host ('  +' + ('-' * $w) + '+') -ForegroundColor Yellow
    foreach ($r in $righe) { Write-Host ('  |  ' + $r.PadRight($w - 2) + '|') -ForegroundColor Yellow }
    Write-Host ('  +' + ('-' * $w) + '+') -ForegroundColor Yellow
    Log "TV troppo vecchia: $man $mod, $sistema"
    [void](Chiedi 'Premi Invio per chiudere')
    exit 1
}
# Se la TV e' gia' autorizzata: il controllo subito, prima del consenso e di ogni domanda.
function ControllaEta([string]$serial) {
    if ((Stato $serial) -ne 'device') { return }
    $sdk = (AdbRun @('-s', $serial, 'shell', 'getprop ro.build.version.sdk')).Trim()
    if ($sdk -match '^\d+$' -and [int]$sdk -ge 26) { return }
    $man = (AdbRun @('-s', $serial, 'shell', 'getprop ro.product.manufacturer')).Trim()
    $mod = (AdbRun @('-s', $serial, 'shell', 'getprop ro.product.model')).Trim()
    $sys = SistemaBreve ($man -eq 'Amazon') (AdbRun @('-s', $serial, 'shell', 'getprop ro.build.version.name')).Trim() `
        (AdbRun @('-s', $serial, 'shell', 'getprop ro.build.version.release')).Trim()
    TroppoVecchia $man $mod $sys
}

Passo 'Ricerca della TV'
if (-not $Ip) {
    $ips = @(Scansiona)
    if ($ips.Count -eq 0) {
        Write-Host ''
        Write-Host '  Nessuna TV trovata con il debug ADB acceso.' -ForegroundColor Yellow
        Write-Host '  Sulla TV: Impostazioni > La mia Fire TV > Opzioni sviluppatore > Debug ADB: attivo.' -ForegroundColor Green
        Write-Host '  (L''indirizzo IP e'' sulla schermata di TV Liberata, o in Impostazioni > La mia Fire TV > Informazioni > Rete.)' -ForegroundColor Green
        $Ip = Chiedi 'Scrivi l''indirizzo IP della TV'
    } else {
        $lista = @($ips | ForEach-Object { Descrivi $_ })
        Write-Host ''
        if ($lista.Count -eq 1) {
            Write-Host "  Trovata:  $($lista[0].Testo)" -ForegroundColor Green
            if (SiNo 'E'' questa?') { $Ip = $lista[0].Ip } else { $Ip = Chiedi 'Scrivi l''indirizzo IP della TV' }
        } else {
            for ($i = 0; $i -lt $lista.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $lista[$i].Testo) -ForegroundColor Green }
            $n = Chiedi 'Numero della TV da configurare (0 = scrivo io l''IP)'
            if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $lista.Count) { $Ip = $lista[[int]$n - 1].Ip }
            else { $Ip = Chiedi 'Scrivi l''indirizzo IP della TV' }
        }
    }
}
if (-not $Ip) { Esci 'Nessun indirizzo IP: mi fermo.' }
$Script:Tv = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }

# =================================================================================================================
#  3. CONSENSO SULLA TV
# =================================================================================================================
Passo 'Collegamento'
AdbRun @('connect', $Script:Tv) | Out-Null
ControllaEta $Script:Tv
if ((Stato $Script:Tv) -ne 'device') {
    Write-Host ''
    Write-Host '  GUARDA LA TV: compare "Consentire il debug USB?".' -ForegroundColor Yellow
    Write-Host '  Spunta "Consenti sempre da questo computer" e premi OK.' -ForegroundColor Yellow
    Write-Host ''
    $ok = AspettaInLinea 'Aspetto il consenso sulla TV' { AdbRun @('connect', $Script:Tv) | Out-Null; (Stato $Script:Tv) -eq 'device' } 180
    if (-not $ok) { Esci 'Nessun consenso in 3 minuti. Controlla che la TV sia accesa e riprova.' }
}
$FireOs = (Sh 'getprop ro.product.manufacturer') -eq 'Amazon'
$Sdk = Sh 'getprop ro.build.version.sdk'
Ok ("collegato: {0} {1}, {2}" -f (Sh 'getprop ro.product.manufacturer'), (Sh 'getprop ro.product.model'),
    $(if ($FireOs) { Sh 'getprop ro.build.version.name' } else { "Android $(Sh 'getprop ro.build.version.release')" }))
if (-not ($Sdk -match '^\d+$') -or [int]$Sdk -lt 26) {
    TroppoVecchia (Sh 'getprop ro.product.manufacturer') (Sh 'getprop ro.product.model') `
        (SistemaBreve $FireOs (Sh 'getprop ro.build.version.name') (Sh 'getprop ro.build.version.release'))
}
if ($Prova) { Write-Host ''; Write-Host '  MODALITA'' PROVA: non cambio niente sulla TV.' -ForegroundColor Yellow }

# =================================================================================================================
#  UTILITA'
# =================================================================================================================
function Scarica([string]$url, [string]$dest) {
    try { Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing } catch { return $false }
    return (Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)
}
function LeggiJson([string]$nome) {
    if ($Liste) { return (Get-Content (Join-Path $Liste $nome) -Raw -Encoding UTF8 | ConvertFrom-Json) }
    $f = Join-Path $env:TEMP "tvliberata-$nome"
    try { Invoke-WebRequest -Uri "$Raw/$nome" -OutFile $f -UseBasicParsing; return (Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json) }
    catch { Log "Non riesco a leggere $nome dal repository: $($_.Exception.Message)"; return $null }
}
function Pacchetti([string]$opz) {
    return @(((Sh "pm list packages $opz") -split "`r?`n") | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ -and $_ -notmatch '\s' })
}
function Installata([string]$p) { return (Sh "pm path $p") -match 'package:' }
# True se l'app ha un'icona avviabile (Home TV o telefono).
function Avviabile([string]$p) {
    foreach ($cat in 'android.intent.category.LEANBACK_LAUNCHER', 'android.intent.category.LAUNCHER') {
        if ((Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c $cat $p") -match "$([regex]::Escape($p))/") { return $true }
    }
    return $false
}
function ServiziConGuard([string]$lista) {
    $l = if ($lista -eq 'null' -or -not $lista) { @() } else { @($lista -split ':' | Where-Object { $_ }) }
    # Un solo servizio Home: via quello di TV Liberata gia' presente, quello della vecchia app (com.gathin.tvhome) e
    # la Guard di dumbtv home. Due servizi che si contendono la Home vanno in conflitto. Gli altri servizi restano.
    $l = @($l | Where-Object { $_ -notmatch 'FireHomeGuard$' -and $_ -ne 'tv.dumbtv.home/tv.dumbtv.home.Guard' -and $_ -ne 'tv.dumbtv.home/.Guard' })
    return (@($l) + $Guard) -join ':'
}
function ServiziSenzaGuard([string]$lista) {
    $l = if ($lista -eq 'null' -or -not $lista) { @() } else { @($lista -split ':' | Where-Object { $_ }) }
    return (@($l | Where-Object { $_ -notmatch [regex]::Escape("$Pkg/") })) -join ':'
}
function NomeApp([string]$p) {
    $l = Sh "dumpsys package $p 2>/dev/null | grep -m1 'versionName'"
    return "$p  $($l.Trim())"
}
# Scelta da un elenco numerato: T = tutte, N = nessuna, oppure numeri separati da virgola.
function Scegli($elenco, [string]$domanda) {
    $r = Chiedi "$domanda`n  T = tutte, N = nessuna, oppure i numeri separati da virgola (es. 1,3)"
    if ($r.ToUpper() -eq 'T') { return @($elenco) }
    $out = @()
    if ($r -and $r.ToUpper() -ne 'N') {
        foreach ($n in ($r -split '[,; ]+')) { if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $elenco.Count) { $out += $elenco[[int]$n - 1] } }
    }
    return @($out | Select-Object -Unique)
}

# =================================================================================================================
#  SCHERMATA FINALE
# =================================================================================================================
function Riepilogo([string]$scritta, [string[]]$extra = @()) {
    Clear-Host
    Write-Host ''
    foreach ($riga in ($scritta -split "`r?`n")) { Write-Host ("  " + $riga) -ForegroundColor Green; Start-Sleep -Milliseconds 120 }
    Write-Host ''
    $tempo = (Get-Date) - $Script:T0
    Linea
    Write-Host ("  Tempo totale: {0:D2}:{1:D2}     Fatto: {2}     Avvisi: {3}" -f [int][Math]::Floor($tempo.TotalMinutes), $tempo.Seconds, $Fatto.Count, $NonRiuscito.Count) -ForegroundColor Green
    Linea
    Write-Host ''
    Write-Host '  +--------------------------------------------------------------+' -ForegroundColor DarkGreen
    foreach ($f in $Fatto) { Write-Host ('  |  ' + (Pad "[ OK ] $f" 58) + '  |') -ForegroundColor Green; Start-Sleep -Milliseconds 40 }
    foreach ($f in $NonRiuscito) { Write-Host ('  |  ' + (Pad "[ !! ] $f" 58) + '  |') -ForegroundColor Yellow; Start-Sleep -Milliseconds 40 }
    Write-Host '  +--------------------------------------------------------------+' -ForegroundColor DarkGreen
    foreach ($e in $extra) { Write-Host "  $e" -ForegroundColor Green }
    Write-Host ''
    if ($NonRiuscito.Count -eq 0) { Batti '  TUTTO A POSTO. Il debug ADB resta acceso.' 15 }
    else { Batti ("  FINITO CON {0} AVVISI: leggili qui sopra." -f $NonRiuscito.Count) 15 'Yellow' }
    Write-Host "  Registro completo: $Log" -ForegroundColor DarkGreen
    try { [Console]::Beep(880, 120); [Console]::Beep(1320, 180) } catch { }
}

# =================================================================================================================
#  CONFIGURA
# =================================================================================================================
function Configura {
    # ---------- tutte le domande SUBITO ----------
    Passo 'Domande (poi faccio tutto da solo)'
    if (-not $Nome) { $Nome = Chiedi 'Nome di questa TV (es. Salotto)' }
    if (-not $Nome) { $Nome = 'TV' }
    $nomeFile = ($Nome -replace '[^A-Za-z0-9_-]', '-')
    # La "cartolina" (1.1.0): la risposta passa all'app con la configurazione del dispositivo.
    if (-not $Cartolina) {
        Write-Host ''
        Write-Host '  Vuoi far sapere a Syntax che usi TV Liberata?' -ForegroundColor Green
        Write-Host '  A ogni aggiornamento viene inviato solo il nome di questa TV, la versione e il modello.' -ForegroundColor DarkGreen
        Write-Host '  Puoi cambiare idea in Sistema.' -ForegroundColor DarkGreen
        while (-not $Cartolina) {
            $r = (Chiedi 'S = Si'', N = No  (Invio = Si'')').ToUpper()
            if ($r -eq '' -or $r -eq 'S') { $Cartolina = 'si' } elseif ($r -eq 'N') { $Cartolina = 'no' }
        }
    }

    Write-Host ''
    Write-Host '    > Leggo le liste aggiornate dal repository...' -ForegroundColor Green
    $pacchetto = LeggiJson 'pacchetto-app.json'
    $pulizia = if ($FireOs) { LeggiJson 'pulizia-fireos.json' } else { $null }
    if (-not $pacchetto) { Esci 'Non riesco a leggere pacchetto-app.json dal repository: controlla internet e riprova.' }
    $mai = if ($pulizia) { @($pulizia.mai) } else { @($Pkg) }
    $tenere = @($pacchetto.app | ForEach-Object { $_.pacchetto })
    $piattaforma = if ($FireOs) { 'fireos' } else { 'androidtv' }
    $mie = @($pacchetto.app | Where-Object { -not $_.solo -or $_.solo -eq $piattaforma })

    Write-Host '    > Guardo quali app ci sono sulla TV...' -ForegroundColor Green
    $terze = @(Pacchetti '-3' | Where-Object { $tenere -notcontains $_ -and $mai -notcontains $_ -and $_ -ne $Pkg -and
        $_ -notmatch '\.overlay\.' -and (Avviabile $_) })
    $daTogliere = @()
    $Script:Nascoste = @()
    if ($terze.Count -gt 0) {
        Write-Host ''
        Write-Host '  Queste app non fanno parte di TV Liberata:' -ForegroundColor Green
        for ($i = 0; $i -lt $terze.Count; $i++) { Write-Host ("    [{0}] {1}" -f ($i + 1), (NomeApp $terze[$i])) -ForegroundColor Green }
        $daTogliere = @(Scegli $terze 'Quali DISINSTALLARE?')
        $restano = @($terze | Where-Object { $daTogliere -notcontains $_ })
        if ($restano.Count -gt 0) {
            Write-Host ''
            Write-Host '  Restano installate:' -ForegroundColor Green
            for ($i = 0; $i -lt $restano.Count; $i++) { Write-Host ("    [{0}] {1}" -f ($i + 1), (NomeApp $restano[$i])) -ForegroundColor Green }
            $Script:Nascoste = @(Scegli $restano 'Quali NASCONDERE dalla Home di TV Liberata? (si rimettono da Impostazioni TV Liberata)')
        }
    } else { Write-Host '    > Nessuna app da togliere.' -ForegroundColor Green }

    $store = @($mie | Where-Object { $_.tipo -eq 'store' -and $_.pacchetto -ne $Pkg -and -not (Installata $_.pacchetto) })
    Write-Host ''
    Linea
    Write-Host "  Nome TV:          $Nome" -ForegroundColor Green
    Write-Host ("  Da disinstallare: {0}" -f $(if ($daTogliere.Count) { $daTogliere -join ', ' } else { 'nessuna' })) -ForegroundColor Green
    Write-Host ("  Da nascondere:    {0}" -f $(if ($Script:Nascoste.Count) { $Script:Nascoste -join ', ' } else { 'nessuna' })) -ForegroundColor Green
    if ($store.Count -gt 0) { Write-Host ("  Dall'Appstore:    {0}  (premerai Scarica sulla TV)" -f (($store | ForEach-Object { $_.nome }) -join ', ')) -ForegroundColor Green }
    Linea
    if (-not $Azione) { [void](Chiedi 'Tutto pronto. Premi Invio per iniziare (da qui faccio tutto da solo)') }

    # ---------- da qui: automatico ----------
    ApriCruscotto $(if ($Prova) { 'configurazione (PROVA)' } else { 'configurazione in corso' }) @(
        'Backup delle impostazioni', 'TV Liberata', 'Disinstallo le app scelte', 'Pulizia app Amazon', 'Pacchetto app',
        'Permessi', 'TV Liberata come Home', 'Screensaver e filtro DNS', 'Riavvio e verifica')

    # 1. Backup
    ProssimoPasso
    $dir = Join-Path $Here ("backup\{0}-{1}" -f $nomeFile, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force $dir | Out-Null
    $v = Voce 'elenco delle app'
    (Pacchetti '') | Out-File -Encoding ascii "$dir\pacchetti.txt"
    (Pacchetti '-d') | Out-File -Encoding ascii "$dir\pacchetti-disattivati.txt"
    (Pacchetti '-3') | Out-File -Encoding ascii "$dir\pacchetti-terze-parti.txt"
    Chiudi $v; $Script:Sub = 0.3
    $v = Voce 'impostazioni di sistema'
    foreach ($ns in 'secure', 'global', 'system') { Sh "settings list $ns" | Out-File -Encoding utf8 "$dir\settings-$ns.txt" }
    Sh 'getprop' | Out-File -Encoding utf8 "$dir\getprop.txt"
    Chiudi $v; $Script:Sub = 0.6
    $v = Voce 'impostazioni che cambio'
    $toccate = 'secure enabled_accessibility_services', 'secure accessibility_enabled', 'secure always_on_vpn_app',
        'secure screensaver_components', 'system screen_off_timeout', 'global alarm_manager_constants'
    $toccate | ForEach-Object { "$_=$(Sh "settings get $_")" } | Out-File -Encoding ascii "$dir\impostazioni-toccate.txt"
    "tv=$($Script:Tv)", "nome=$Nome", "fireos=$FireOs", "data=$(Get-Date -Format s)" | Out-File -Encoding ascii "$dir\tv.txt"
    Chiudi $v
    if ((Get-Item "$dir\pacchetti.txt").Length -lt 100 -or (Get-Item "$dir\settings-secure.txt").Length -lt 100) {
        Ko 'backup incompleto: mi fermo senza cambiare niente'
        ChiudiCruscotto; Riepilogo $ScrittaAnnullato; return
    }
    Ok "backup salvato"
    Nota $dir

    # 2. TV Liberata
    ProssimoPasso
    if (Installata $Pkg) { Ok ("gia' installata ({0})" -f ((Sh "dumpsys package $Pkg 2>/dev/null | grep -m1 versionName") -replace '\s+', ' ').Trim()) }
    else {
        $apk = Join-Path $env:TEMP 'tv-liberata.apk'
        $v = Voce 'scarico TV Liberata dalla Release'
        if ($Prova) { Chiudi $v 'prova' 'DarkGreen' }
        elseif (Scarica $ApkUrl $apk) {
            Chiudi $v; $Script:Sub = 0.5
            $v = Voce 'installo TV Liberata'
            $r = AdbRun @('-s', $Script:Tv, 'install', '-r', $apk)
            if ($r -match 'Success') { Chiudi $v; [void]$Fatto.Add('TV Liberata installata') }
            else { Chiudi $v '!!' 'Yellow'; Avviso "TV Liberata non installata: $r"; ChiudiCruscotto; Riepilogo $ScrittaAnnullato; return }
        } else { Chiudi $v '!!' 'Yellow'; Avviso 'TV Liberata non scaricata'; ChiudiCruscotto; Riepilogo $ScrittaAnnullato; return }
    }

    # 3. Disinstallo le app scelte
    ProssimoPasso
    $tolte = @()
    if ($daTogliere.Count -eq 0) { Nota 'nessuna app da disinstallare' }
    $i = 0
    foreach ($p in $daTogliere) {
        $i++; $Script:Sub = $i / [Math]::Max(1, $daTogliere.Count)
        $v = Voce "disinstallo $p"
        if ($Prova) { Chiudi $v 'prova' 'DarkGreen'; continue }
        $o = AdbRun @('-s', $Script:Tv, 'uninstall', $p)
        if ($o -match 'Success') { $tolte += $p; Chiudi $v 'tolta'; [void]$Fatto.Add("disinstallata $p") }
        else { Chiudi $v '!!' 'Yellow'; Avviso "non disinstallata $p" }
    }
    if (-not $Prova) { $tolte | Out-File -Encoding ascii "$dir\app-disinstallate.txt" }

    # 4. Pulizia Amazon
    ProssimoPasso
    $applicate = @()
    if (-not $FireOs) { Nota 'non e'' una Fire TV: niente pulizia Amazon' }
    elseif (-not $pulizia) { Ko 'pulizia-fireos.json non letto: pulizia saltata' }
    else {
        $tutti = Pacchetti ''
        $giaOff = Pacchetti '-d'
        $daFare = @($pulizia.disattiva | Where-Object { $mai -notcontains $_.pacchetto -and $tutti -contains $_.pacchetto -and $giaOff -notcontains $_.pacchetto })
        if ($daFare.Count -eq 0) { Nota 'gia'' tutto pulito' }
        $i = 0
        foreach ($e in $daFare) {
            $i++; $Script:Sub = $i / [Math]::Max(1, $daFare.Count)
            $p = $e.pacchetto
            $v = Voce $p
            $o = Fai "pm disable-user --user 0 $p"
            if ($Prova) { $applicate += $p; Chiudi $v 'prova' 'DarkGreen' }
            elseif ($o -match 'disabled-user') { $applicate += $p; Chiudi $v 'disattivata' }
            else { Chiudi $v 'non toccata' 'DarkGreen' }
        }
        [void]$Fatto.Add(("{0} app Amazon disattivate" -f $applicate.Count))
    }
    if (-not $Prova) { $applicate | Out-File -Encoding ascii "$dir\pulizia-applicata.txt" }

    # 5. Pacchetto app
    ProssimoPasso
    $lista = @($mie | Where-Object { $_.pacchetto -ne $Pkg })
    $i = 0
    foreach ($a in $lista) {
        $i++; $Script:Sub = ($i - 1) / [Math]::Max(1, $lista.Count)
        if (Installata $a.pacchetto) { $v = Voce "$($a.nome)"; Chiudi $v "c'e' gia'"; continue }
        if ($a.tipo -eq 'sistema') { Ko "$($a.nome) non c'e' su questa TV"; continue }
        if ($a.tipo -eq 'libera') {
            $f = Join-Path $env:TEMP "$($a.pacchetto).apk"
            $v = Voce "$($a.nome): scarico e installo"
            if ($Prova) { Chiudi $v 'prova' 'DarkGreen'; continue }
            if (-not (Scarica $a.apk $f)) { Chiudi $v '!!' 'Yellow'; Avviso "$($a.nome): download non riuscito"; continue }
            if ($a.sha256 -and ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $a.sha256.ToLower())) { Chiudi $v '!!' 'Yellow'; Avviso "$($a.nome): impronta SHA-256 diversa, non installata"; continue }
            $o = AdbRun @('-s', $Script:Tv, 'install', '-r', $f)
            if ($o -match 'Success') { Chiudi $v 'installata'; [void]$Fatto.Add("$($a.nome) installata") } else { Chiudi $v '!!' 'Yellow'; Avviso "$($a.nome) non installata" }
        } elseif ($a.tipo -eq 'store') {
            $url = if ($FireOs) { "amzn://apps/android?p=$($a.pacchetto)" } else { "market://details?id=$($a.pacchetto)" }
            $v = Voce "$($a.nome): sulla TV premi SCARICA" 'Yellow'
            if ($Prova) { Chiudi $v 'prova' 'DarkGreen'; continue }
            Sh "am start -a android.intent.action.VIEW -d $url" | Out-Null
            # aspetta da solo che l'app risulti installata (massimo 3 minuti)
            $fine = (Get-Date).AddMinutes(3); $preso = $false
            while ((Get-Date) -lt $fine) {
                Gira 2000
                if (Installata $a.pacchetto) { $preso = $true; break }
            }
            if ($preso) { Chiudi $v 'installata'; [void]$Fatto.Add("$($a.nome) installata dall'Appstore") }
            else { Chiudi $v 'saltata' 'Yellow'; Avviso "$($a.nome) non installata (puoi farlo dopo dall'Appstore)" }
            Sh 'input keyevent KEYCODE_BACK' | Out-Null
        }
    }

    # 6. Permessi
    ProssimoPasso
    $v = Voce 'permesso impostazioni di sistema'
    Fai "pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null
    Chiudi $v $(if ($Prova) { 'prova' } else { 'OK' })
    $Script:Sub = 0.3
    foreach ($op in 'REQUEST_INSTALL_PACKAGES', 'ACTIVATE_VPN', 'GET_USAGE_STATS', 'WRITE_SETTINGS') {
        $v = Voce "permesso $op"; Fai "appops set $Pkg $op allow" | Out-Null; Chiudi $v $(if ($Prova) { 'prova' } else { 'OK' })
    }
    $Script:Sub = 0.7
    $cfg = Join-Path $env:TEMP 'dispositivo.json'
    $nl = (@($Script:Nascoste) | ForEach-Object { '"' + $_ + '"' }) -join ','
    $cart = if ($Cartolina -eq 'si') { 'true' } else { 'false' }
    ('{{"nome":"{0}","canale":"{1}","nascondi":[{2}],"cartolina":{3}}}' -f ($Nome -replace '"', ''), $Canale, $nl, $cart) | Out-File -Encoding ascii $cfg
    $v = Voce "nome $Nome, canale $Canale"
    if ($Prova) { Chiudi $v 'prova' 'DarkGreen' }
    else {
        AdbRun @('-s', $Script:Tv, 'push', $cfg, "/sdcard/Android/data/$Pkg/files/dispositivo.json") | Out-Null
        $o = Sh "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.DeviceConfig -a com.gathin.tvhome.DEVICE_CONFIG"
        if ($o -match 'result=') { Chiudi $v; [void]$Fatto.Add("nome $Nome, canale $Canale") } else { Chiudi $v '!!' 'Yellow'; Avviso "configurazione del dispositivo non riuscita" }
        if ((Sh "dumpsys package $Pkg 2>/dev/null | grep 'WRITE_SECURE_SETTINGS: granted=true'")) { [void]$Fatto.Add('permessi concessi') } else { Ko 'permesso WRITE_SECURE_SETTINGS non concesso' }
    }

    # 7. Home
    ProssimoPasso
    if ($FireOs) {
        # La Home Amazon resta installata e attiva: il servizio di TV Liberata la sostituisce quando compare.
        $v = Voce 'sveglie puntuali e niente risparmio energetico'
        $alarm = Sh 'settings get global alarm_manager_constants'
        if ($alarm -notmatch 'min_futurity=0') {
            $rest = (($alarm -replace '^null$', '') -split ',' | Where-Object { $_ -and $_ -notmatch '^min_futurity=' }) -join ','
            Fai ("settings put global alarm_manager_constants " + $(if ($rest) { "$rest,min_futurity=0" } else { 'min_futurity=0' })) | Out-Null
        }
        Fai "dumpsys deviceidle whitelist +$Pkg" | Out-Null
        Chiudi $v $(if ($Prova) { 'prova' } else { 'OK' })
        $Script:Sub = 0.2
        $v = Voce 'attivo il servizio Home di TV Liberata'
        $nuova = ServiziConGuard (Sh 'settings get secure enabled_accessibility_services')
        $legato = $false
        for ($t = 0; $t -lt 2 -and -not $legato; $t++) {
            Fai 'settings delete secure enabled_accessibility_services' | Out-Null
            if (-not $Prova) { Gira 2000 }
            Fai "settings put secure enabled_accessibility_services $nuova" | Out-Null
            Fai 'settings put secure accessibility_enabled 1' | Out-Null
            if ($Prova) { break }
            for ($i = 0; $i -lt 15; $i++) { if ((Sh 'dumpsys accessibility') -match 'FireHomeGuard|Home Fire TV|Fire TV home') { $legato = $true; break }; Gira 1000 }
        }
        if ($Prova) { Chiudi $v 'prova' 'DarkGreen' } elseif ($legato) { Chiudi $v } else { Chiudi $v '!!' 'Yellow' }
        $Script:Sub = 0.6
        if (-not $Prova) {
            $v = Voce 'provo il tasto Home'
            Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
            Sh "am start -n $Pkg/com.gathin.tvhome.MainActivity" | Out-Null
            Gira 3000
            $davanti = ''
            for ($i = 0; $i -lt 3; $i++) {
                Sh 'input keyevent KEYCODE_HOME' | Out-Null; Gira 4000
                $davanti = Sh 'dumpsys window windows 2>/dev/null | grep mCurrentFocus'
                if ($davanti -match [regex]::Escape("$Pkg/")) { break }
            }
            if ($legato -and $davanti -match [regex]::Escape("$Pkg/")) { Chiudi $v; [void]$Fatto.Add('Home = TV Liberata (servizio Home attivo)') }
            else {
                Chiudi $v '!!' 'Yellow'
                Avviso "Home non presa: rimessa la Home Amazon"
                $senza = ServiziSenzaGuard (Sh 'settings get secure enabled_accessibility_services')
                Sh 'settings delete secure enabled_accessibility_services' | Out-Null
                if ($senza) { Sh "settings put secure enabled_accessibility_services $senza" | Out-Null }
                Sh 'input keyevent KEYCODE_HOME' | Out-Null
            }
        }
    } else {
        $v = Voce 'imposto la Home'
        $o = Fai "cmd package set-home-activity $Pkg/com.gathin.tvhome.MainActivity"
        if ($Prova) { Chiudi $v 'prova' 'DarkGreen' } elseif ($o -match 'Success') { Chiudi $v; [void]$Fatto.Add('Home = TV Liberata') } else { Chiudi $v '!!' 'Yellow'; Avviso "Home: $o" }
    }

    # 8. Screensaver e filtro
    ProssimoPasso
    $v = Voce 'screensaver TV Liberata'
    Fai "settings put secure screensaver_components $Dream" | Out-Null
    $timeout = Sh 'settings get system screen_off_timeout'
    if ($timeout -notmatch '^\d+$' -or [long]$timeout -gt 1800000) { Fai 'settings put system screen_off_timeout 300000' | Out-Null }
    Chiudi $v $(if ($Prova) { 'prova' } else { 'OK' }); [void]$Fatto.Add('screensaver = TV Liberata')
    $Script:Sub = 0.5
    $v = Voce 'filtro DNS con blocco aggiornamenti di sistema'
    Fai "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.FilterReceiver -a com.gathin.tvhome.FILTER --ez on true --ez ota true" | Out-Null
    Fai "settings put secure always_on_vpn_app $Pkg" | Out-Null
    Chiudi $v $(if ($Prova) { 'prova' } else { 'OK' }); [void]$Fatto.Add('filtro DNS acceso')

    # 9. Riavvio e verifica
    ProssimoPasso
    if ($Prova) { Nota 'in prova la TV non viene riavviata'; ChiudiCruscotto; Riepilogo $ScrittaFatto @('MODALITA'' PROVA: sulla TV non e'' stato cambiato niente.'); return }
    $v = Voce 'riavvio la TV (1-2 minuti)'
    # "adb reboot", non "shell reboot": su Fire OS 7.7 la shell non ha il permesso e la TV non si riavvia.
    AdbRun @('-s', $Script:Tv, 'reboot') | Out-Null
    Gira 40000
    $t0 = Get-Date
    while ((Stato $Script:Tv) -ne 'device' -and ((Get-Date) - $t0).TotalSeconds -lt 180) {
        $Script:Sub = [Math]::Min(0.6, ((Get-Date) - $t0).TotalSeconds / 180)
        Gira 5000; AdbRun @('connect', $Script:Tv) | Out-Null
    }
    if ((Stato $Script:Tv) -ne 'device') { Chiudi $v '!!' 'Yellow'; Avviso 'dopo il riavvio la TV non risponde: controlla a mano'; ChiudiCruscotto; Riepilogo $ScrittaFatto; return }
    $uptime = 0.0
    [void][double]::TryParse((((Sh 'cat /proc/uptime') -split ' ')[0]), [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$uptime)
    if ($uptime -gt 600) { Chiudi $v '!!' 'Yellow'; Avviso 'la TV non si e'' riavviata: riavviala a mano e ricontrolla' } else { Chiudi $v 'riaccesa' }
    $Script:Sub = 0.7
    $v = Voce 'aspetto che si sistemi'
    Gira 20000
    Chiudi $v
    Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    $v = Voce 'controllo la Home'
    Gira 5000
    $davanti = Sh 'dumpsys window windows 2>/dev/null | grep mCurrentFocus'
    if ($davanti -match [regex]::Escape("$Pkg/")) { Chiudi $v; [void]$Fatto.Add('dopo il riavvio la Home e'' TV Liberata') } else { Chiudi $v '!!' 'Yellow'; Avviso "dopo il riavvio la Home non e' TV Liberata" }
    $Script:Sub = 0.85
    $v = Voce 'controllo il filtro DNS'
    # Il filtro riparte dopo l'avvio, anche qualche decina di secondi dopo: lo aspetto fino a un minuto.
    # (always_on_vpn_app su Fire OS resta vuoto: non e' un buon segnale.)
    $vpn = $false
    for ($k = 0; $k -lt 12 -and -not $vpn; $k++) {
        $vpn = (Sh 'dumpsys connectivity') -match 'type: VPN\[\], state: CONNECTED'
        if (-not $vpn) { Gira 5000 }
    }
    if ($vpn) { Chiudi $v } else { Chiudi $v '!!' 'Yellow'; Avviso 'filtro DNS non attivo dopo un minuto' }
    if ($FireOs) {
        $v = Voce 'controllo il servizio Home'
        if ((Sh 'dumpsys accessibility') -match 'FireHomeGuard|Home Fire TV') { Chiudi $v } else { Chiudi $v '!!' 'Yellow'; Avviso 'servizio Home non attivo' }
    }
    ChiudiCruscotto
    Riepilogo $ScrittaFatto @('Sulla TV premi OK su "Fatto!".')
}

# =================================================================================================================
#  ANNULLA
# =================================================================================================================
function Annulla {
    $dirs = @(Get-ChildItem -Directory (Join-Path $Here 'backup') -ErrorAction SilentlyContinue | Where-Object {
        (Test-Path "$($_.FullName)\tv.txt") -and ((Get-Content "$($_.FullName)\tv.txt") -contains "tv=$($Script:Tv)") } | Sort-Object { $_.CreationTime })
    if ($dirs.Count -eq 0) { Esci "Nessun backup di questa TV ($($Script:Tv)) accanto allo script: non so cosa rimettere." }
    $dir = $dirs[0].FullName   # il primo backup: lo stato di prima di TV Liberata
    Passo 'Annulla tutto'
    Write-Host "  Torno allo stato del backup:  $dir" -ForegroundColor Green
    if (-not (SiNo 'Procedo?')) { return }

    ApriCruscotto $(if ($Prova) { 'annullamento (PROVA)' } else { 'annullamento in corso' }) @('Riattivo le app', 'Tolgo Home, filtro e permessi', 'Impostazioni di prima')

    ProssimoPasso
    $daRiattivare = @()
    foreach ($d in ($dirs | ForEach-Object { "$($_.FullName)\pulizia-applicata.txt" })) {
        if (Test-Path $d) { $daRiattivare += @(Get-Content $d | Where-Object { $_ }) }
    }
    $prima = Get-Content "$dir\pacchetti-disattivati.txt"
    $daRiattivare += @(Pacchetti '-d' | Where-Object { $prima -notcontains $_ -and $_ -notmatch '^com\.gathin\.' })
    $daRiattivare = @($daRiattivare | Select-Object -Unique)
    if ($daRiattivare.Count -eq 0) { Nota 'nessuna app da riattivare' }
    $i = 0
    foreach ($p in $daRiattivare) {
        $i++; $Script:Sub = $i / [Math]::Max(1, $daRiattivare.Count)
        $v = Voce $p; Fai "pm enable $p" | Out-Null; Chiudi $v $(if ($Prova) { 'prova' } else { 'riattivata' })
    }
    [void]$Fatto.Add(("{0} app riattivate" -f $daRiattivare.Count))

    ProssimoPasso
    $v = Voce 'servizio Home'
    $senza = ServiziSenzaGuard (Sh 'settings get secure enabled_accessibility_services')
    Fai 'settings delete secure enabled_accessibility_services' | Out-Null
    if ($senza) { Fai "settings put secure enabled_accessibility_services $senza" | Out-Null }
    Chiudi $v $(if ($Prova) { 'prova' } else { 'tolto' }); $Script:Sub = 0.3
    $v = Voce 'filtro DNS'
    Fai "am broadcast -f 0x20 -n $Pkg/com.gathin.tvhome.FilterReceiver -a com.gathin.tvhome.FILTER --ez on false" | Out-Null
    Chiudi $v $(if ($Prova) { 'prova' } else { 'spento' }); $Script:Sub = 0.6
    $v = Voce 'permessi di TV Liberata'
    Fai "pm revoke $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null
    foreach ($op in 'REQUEST_INSTALL_PACKAGES', 'ACTIVATE_VPN', 'GET_USAGE_STATS', 'WRITE_SETTINGS') { Fai "appops set $Pkg $op default" | Out-Null }
    Fai "dumpsys deviceidle whitelist -$Pkg" | Out-Null
    Chiudi $v $(if ($Prova) { 'prova' } else { 'tolti' })
    [void]$Fatto.Add('Home, filtro e permessi di TV Liberata tolti')

    ProssimoPasso
    foreach ($l in Get-Content "$dir\impostazioni-toccate.txt") {
        $k, $val = $l -split '=', 2; $ns, $name = $k -split ' ', 2
        if ($name -eq 'enabled_accessibility_services' -or $name -eq 'accessibility_enabled') { continue }
        $v = Voce "$name"
        if ($val -eq 'null' -or -not $val) { Fai "settings delete $ns $name" | Out-Null } else { Fai "settings put $ns $name $val" | Out-Null }
        Chiudi $v $(if ($Prova) { 'prova' } else { 'come prima' })
    }
    Fai 'input keyevent KEYCODE_HOME' | Out-Null
    [void]$Fatto.Add('screensaver, tempo dello schermo e filtro come prima; Home originale')

    $extra = @()
    $tolte = @()
    foreach ($d in $dirs) { if (Test-Path "$($d.FullName)\app-disinstallate.txt") { $tolte += Get-Content "$($d.FullName)\app-disinstallate.txt" } }
    $tolte = @($tolte | Where-Object { $_ } | Select-Object -Unique)
    if ($tolte.Count -gt 0) {
        $extra += 'Queste app erano state disinstallate e NON vengono reinstallate (riscaricale dall''Appstore se servono):'
        $extra += @($tolte | ForEach-Object { "    $_" })
    }
    if (-not $FireOs) { $extra += 'Sulla TV Android scegli di nuovo la Home originale quando te lo chiede (tasto Home).' }
    $extra += 'TV Liberata resta installata: per toglierla, Impostazioni > Applicazioni > TV Liberata > Disinstalla.'
    ChiudiCruscotto
    Riepilogo $ScrittaAnnullato $extra
}

# =================================================================================================================
#  MENU
# =================================================================================================================
if ($Azione -eq 'configura') { Configura }
elseif ($Azione -eq 'annulla') { Annulla }
else {
    Write-Host ''
    Write-Host '  +--------------------------------------------+' -ForegroundColor DarkGreen
    Write-Host '  |  [1]  Configura TV Liberata                |' -ForegroundColor Green
    Write-Host '  |  [2]  Annulla tutto e torna come prima     |' -ForegroundColor Green
    Write-Host '  |  [0]  Esci                                 |' -ForegroundColor Green
    Write-Host '  +--------------------------------------------+' -ForegroundColor DarkGreen
    switch (Chiedi 'Scelta') {
        '1' { Configura }
        '2' { Annulla }
        default { }
    }
}
try { [Console]::CursorVisible = $true } catch { }
if (-not $Azione) { [void](Chiedi 'Premi Invio per chiudere') }
