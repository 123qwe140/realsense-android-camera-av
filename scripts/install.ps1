param(
    [string]$SdkPath = '',
    [string]$ApkPath = '',
    [string]$Serial = '',
    [string]$ApplicationId = 'com.intel.realsense.camera'
)
$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -and $env:LOCALAPPDATA) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $SdkPath) { throw 'Pass -SdkPath with your Android SDK directory.' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Install Android SDK Platform-Tools first.' }
if (-not $ApkPath) {
    $releaseApks = @(Get-ChildItem -LiteralPath (Join-Path $packageRoot 'outputs') -Filter 'RS-Camera-*-arm64.apk' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    if ($releaseApks.Count -gt 0) { $ApkPath = $releaseApks[0].FullName }
    else { $ApkPath = Join-Path $packageRoot 'apk\RS-Camera-0.0.7-rgb-arm64.apk' }
}
if (-not (Test-Path -LiteralPath $ApkPath)) { throw 'APK was not found.' }
$devices = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
$ready = @($devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($ready.Count -ne 1) {
        Write-Output $devices
        throw 'Connect and authorize exactly one phone, or pass -Serial for the target device.'
    }
    $Serial = $ready[0]
} elseif ($ready -notcontains $Serial) {
    Write-Output $devices
    throw 'The selected phone is not connected and authorized.'
}
$abi = (& $adb -s $Serial shell getprop ro.product.cpu.abilist) -join ''
$sdk = (& $adb -s $Serial shell getprop ro.build.version.sdk) -join ''
if ($abi -notmatch 'arm64-v8a') { throw 'This APK requires arm64-v8a; rebuild for this device ABI.' }
if ([int]$sdk -lt 24) { throw 'This APK requires Android 7.0 / API 24 or newer.' }
& $adb -s $Serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw 'Install failed. For a signature mismatch, see README.txt; this script never uninstalls apps.'
}
& $adb -s $Serial shell am start -W -n "$ApplicationId/com.intel.realsense.camera.DetachedActivity"
if ($LASTEXITCODE -ne 0) { throw 'Installed, but launch failed. Check the phone and logs.' }
Write-Output 'RS Camera installed. Allow camera and USB permissions on the phone when requested.'
