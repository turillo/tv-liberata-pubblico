# Fire TV only: make this launcher the home screen in place of dumbtv home (or Amazon's), the way dumbtv does it.
# Amazon's launcher stays enabled (Fire OS needs it); an accessibility service, FireHomeGuard, replaces Amazon's
# home screen whenever it comes up. dumbtv home stays installed with its settings, so going back is one command:
#   launcher\firetv-back-to-dumbtv.ps1 -TvHost <ip>
# On a Fire TV without dumbtv (Amazon's home before), going back is Amazon's home: the script says how, and puts it
# back by itself if the takeover fails.
#
# Usage: launcher\firetv-home.ps1 -TvHost 192.168.1.60 [-Apk <path>] [-Theme <config.json>] [-NoBuild]
# -Theme pushes a theme's config.json (online wallpaper, dock, columns...) before the install, so the launcher
# starts with it; the TV's previous config.json, if any, goes into the backup.
# Before any change it saves dumbtv home's APK, its home.conf and the settings it touches to backup\firetv\<date>.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Apk = '',
    [string]$Theme = '',
    [switch]$NoBuild
)
# Continue: adb prints progress on stderr, which Windows PowerShell 5.1 would turn into a stop. Every step
# checks its own result and throws instead.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
$tv = if ($TvHost -match ':') { $TvHost } else { "${TvHost}:5555" }
$pkg = 'com.gathin.tvhome.liberata'
$ours = "$pkg/com.gathin.tvhome.FireHomeGuard"
$dumbtv = 'tv.dumbtv.home/tv.dumbtv.home.Guard'

function Sh([string]$cmd) { (& adb -s $tv shell $cmd 2>&1 | Out-String).Trim() }

& adb connect $tv | Out-Null
if ((& adb -s $tv get-state 2>$null) -ne 'device') { throw "TV $tv not reachable over adb" }
if ((Sh 'getprop ro.product.manufacturer') -ne 'Amazon') { throw 'not a Fire TV: use build-and-install.ps1 instead' }

# 1. Backup, checked before anything changes.
$dir = Join-Path $PSScriptRoot "..\backup\firetv\$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force $dir | Out-Null
$path = (Sh 'pm path tv.dumbtv.home') -replace '^package:', ''
if ($path) {
    & adb -s $tv pull $path "$dir\dumbtv-home.apk" 2>$null | Out-Null
    & adb -s $tv pull /sdcard/Android/data/tv.dumbtv.home/files/home.conf "$dir\home.conf" 2>$null | Out-Null
    foreach ($f in 'dumbtv-home.apk', 'home.conf') {
        $p = Join-Path $dir $f
        if (-not (Test-Path $p) -or (Get-Item $p).Length -eq 0) { throw "backup failed: $p missing or empty; nothing was changed" }
    }
}
& adb -s $tv pull /sdcard/Android/data/$pkg/files/config.json "$dir\config.json" 2>$null | Out-Null
$keys = 'secure enabled_accessibility_services', 'secure accessibility_enabled', 'global alarm_manager_constants',
        'secure screensaver_components', 'system screen_off_timeout'
($keys | ForEach-Object { "$_=$(Sh "settings get $_")" }) | Out-File -Encoding ascii "$dir\settings.txt"
Write-Host "backup: $(Resolve-Path $dir)"

# 2. Install (a build of this repo unless -Apk is given).
if (-not $Apk) {
    if (-not $NoBuild) {
        if (-not $env:JAVA_HOME) { throw 'set JAVA_HOME to a JDK 17+' }
        & .\gradlew.bat assembleRelease -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed; nothing was changed' }
    }
    $Apk = 'app\build\outputs\apk\release\app-release.apk'
}
if ($Theme) {
    if (-not (Test-Path $Theme)) { throw "no theme file $Theme; nothing was changed" }
    Sh "mkdir -p /sdcard/Android/data/$pkg/files" | Out-Null
    & adb -s $tv push $Theme /sdcard/Android/data/$pkg/files/config.json 2>$null | Out-Null
    if (-not (Sh "ls /sdcard/Android/data/$pkg/files/config.json" | Select-String 'config.json')) { throw 'theme push failed' }
}
$before = Sh "dumpsys package $pkg | grep lastUpdateTime"
$out = & adb -s $tv install -r $Apk 2>&1 | Out-String
if ($out -notmatch 'Success') {
    # With the DNS filter on, the VPN restarts with the app and adb over Wi-Fi drops right after a good install:
    # reconnect, then trust the package's own update time rather than adb's lost answer.
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 5
        & adb disconnect $tv 2>&1 | Out-Null
        & adb connect $tv 2>&1 | Out-Null
        if ((& adb -s $tv shell echo ok 2>&1 | Out-String) -match 'ok') { break }
    }
    $after = Sh "dumpsys package $pkg | grep lastUpdateTime"
    if (-not $after -or $after -eq $before) { throw "install failed, nothing else was changed: $out" }
    Write-Host 'installed (adb dropped with the VPN restart and is back)'
}
# Syntax TV sets the system screensaver (ClockDream) itself: it needs this, granted again after every install.
Sh "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null

# 3. The alarm that brings Home up at once after HOME needs min_futurity=0 (dumbtv sets the same).
$alarm = Sh 'settings get global alarm_manager_constants'
if ($alarm -notmatch 'min_futurity=0') {
    $rest = (($alarm -replace 'null', '') -split ',' | Where-Object { $_ -and $_ -notmatch '^min_futurity=' }) -join ','
    $value = if ($rest) { "$rest,min_futurity=0" } else { 'min_futurity=0' }
    Sh "settings put global alarm_manager_constants $value" | Out-Null
}
Sh "dumpsys deviceidle whitelist +$pkg" | Out-Null

# 4. Swap the services: dumbtv's Guard off, ours on, every other service kept. Clearing first makes Android
#    bind the new list. Never both at once.
$list = Sh 'settings get secure enabled_accessibility_services'
$others = @(($list -replace '^null$', '') -split ':' | Where-Object { $_ -and $_ -ne $dumbtv -and $_ -ne 'tv.dumbtv.home/.Guard' -and $_ -ne $ours -and $_ -notmatch 'FireHomeGuard' })
$new = (@($others) + $ours) -join ':'
Sh 'settings delete secure enabled_accessibility_services' | Out-Null
Start-Sleep -Seconds 1
Sh "settings put secure enabled_accessibility_services $new" | Out-Null
Sh 'settings put secure accessibility_enabled 1' | Out-Null

# 5. Check: the service is bound and HOME lands here; otherwise the previous home goes straight back (dumbtv's
#    Guard, or Amazon's home when dumbtv is not installed). Right after an install the launcher's process is not
#    running and the first HOME can still show Amazon's home (seen on a Fire TV Stick 3rd gen, 15/11/2026): the
#    launcher is started once and HOME is tried up to three times before giving up.
# Right after a reinstall Android sometimes does not bind the new list (seen 23/11/2026): set it once more.
$bound = $false
for ($try = 0; $try -lt 2 -and -not $bound; $try++) {
    if ($try -gt 0) {
        Sh 'settings delete secure enabled_accessibility_services' | Out-Null
        Start-Sleep -Seconds 2
        Sh "settings put secure enabled_accessibility_services $new" | Out-Null
        Sh 'settings put secure accessibility_enabled 1' | Out-Null
    }
    for ($i = 0; $i -lt 15; $i++) {
        if ((Sh 'dumpsys accessibility') -match 'Fire TV home|Home Fire TV|FireHomeGuard') { $bound = $true; break }
        Start-Sleep -Seconds 1
    }
}
Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
Sh "am start -n $pkg/com.gathin.tvhome.MainActivity" | Out-Null
Start-Sleep -Seconds 3
$front = ''
for ($i = 0; $i -lt 3; $i++) {
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 4
    $front = Sh 'dumpsys window windows | grep mCurrentFocus'
    if ($front -match [regex]::Escape("$pkg/")) { break }
}
$hasDumbtv = [bool](Sh 'pm path tv.dumbtv.home')
if (-not $bound -or $front -notmatch [regex]::Escape("$pkg/")) {
    if ($hasDumbtv) {
        Write-Host "takeover failed (bound=$bound, front: $front): putting dumbtv home back"
        & (Join-Path $PSScriptRoot 'firetv-back-to-dumbtv.ps1') -TvHost $TvHost
        throw 'this launcher is installed but not Home; dumbtv home is Home again'
    }
    # No dumbtv: Amazon's home comes back once our service is off; the other services stay.
    Write-Host "takeover failed (bound=$bound, front: $front): putting Amazon's home back"
    $kept = @($others | Where-Object { $_ }) -join ':'
    Sh 'settings delete secure enabled_accessibility_services' | Out-Null
    if ($kept) { Sh "settings put secure enabled_accessibility_services $kept" | Out-Null }
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    throw "this launcher is installed but not Home; Amazon's home is Home again"
}
if ($hasDumbtv) {
    Write-Host "Home is now this launcher ($front). Back to dumbtv: launcher\firetv-back-to-dumbtv.ps1 -TvHost $TvHost"
} else {
    Write-Host "Home is now this launcher ($front). Back to Amazon's home: adb -s $tv shell settings delete secure enabled_accessibility_services"
}
