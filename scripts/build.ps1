param(
    [string]$SdkPath = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ProxyUrl = '',
    [switch]$PrepareSdk,
    [switch]$Install,
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
if ($packageRoot.Length -gt 40) {
    throw 'Use a shorter package directory, such as C:\rs-rgb, to avoid Windows native build path limits.'
}
$projectRoot = Join-Path $packageRoot 'source\librealsense-2.58.1\wrappers\android'
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -and $env:LOCALAPPDATA) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath -PathType Container)) {
    throw 'Set -SdkPath to your Android SDK directory (see README.txt).'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 directory.'
}
$releaseFile = Join-Path $JavaHome 'release'
if (-not (Test-Path -LiteralPath $releaseFile) -or
    (Get-Content -LiteralPath $releaseFile -Raw) -notmatch 'JAVA_VERSION="17[.\"]') {
    throw 'This handoff uses JDK 17. Select JDK 17 and try again.'
}
$SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$workRoot = Join-Path $packageRoot '.work'
$outputRoot = Join-Path $packageRoot 'outputs'
New-Item -ItemType Directory -Path $workRoot,$outputRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $workRoot 'temp') -Force | Out-Null
$names = @('JAVA_HOME','ANDROID_HOME','ANDROID_USER_HOME','GRADLE_USER_HOME','TEMP','TMP','HTTP_PROXY','HTTPS_PROXY','GRADLE_OPTS')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_USER_HOME = Join-Path $workRoot 'android'
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $workRoot 'gradle' }
    $env:TEMP = Join-Path $workRoot 'temp'
    $env:TMP = $env:TEMP
    if ($ProxyUrl) {
        $proxy = [uri]$ProxyUrl
        if ($proxy.Scheme -ne 'http' -or $proxy.UserInfo) {
            throw '-ProxyUrl must be an HTTP proxy URL without embedded credentials.'
        }
        $env:HTTP_PROXY = $ProxyUrl
        $env:HTTPS_PROXY = $ProxyUrl
        $env:GRADLE_OPTS = "$env:GRADLE_OPTS -Dhttp.proxyHost=$($proxy.Host) -Dhttp.proxyPort=$($proxy.Port) -Dhttps.proxyHost=$($proxy.Host) -Dhttps.proxyPort=$($proxy.Port)"
    }
    if ($PrepareSdk) {
        $sdkmanager = Join-Path $SdkPath 'cmdline-tools\latest\bin\sdkmanager.bat'
        if (-not (Test-Path -LiteralPath $sdkmanager)) {
            throw 'Install Android SDK Command-line Tools (latest) in Android Studio first.'
        }
        & $sdkmanager "--sdk_root=$SdkPath" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'SDK license step failed.' }
        & $sdkmanager "--sdk_root=$SdkPath" 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.0.13004108' 'cmake;3.22.1' 'platform-tools'
        if ($LASTEXITCODE -ne 0) { throw 'SDK package preparation failed.' }
    }
    $required = @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt.exe',
                  'ndk\28.0.13004108\source.properties', 'cmake\3.22.1\bin\cmake.exe')
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $SdkPath $relative))) {
            throw "Missing SDK component: $relative. Use -PrepareSdk or Android Studio SDK Manager."
        }
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'),
        ('sdk.dir=' + $SdkPath.Replace('\','/') + [Environment]::NewLine), $utf8)
    Push-Location -LiteralPath $projectRoot
    try {
        # Preserve build diagnostics even when a native tool writes to stderr in Windows PowerShell.
        $ErrorActionPreference = 'Continue'
        & '.\gradlew.bat' ':tools:camera:assembleDebug' --no-daemon --max-workers=4 --console=plain 2>&1 |
            Tee-Object -FilePath (Join-Path $outputRoot 'build.log')
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($buildExit -ne 0) { throw "Build failed ($buildExit). See outputs\build.log." }
    } finally { Pop-Location }
    $builtApk = Join-Path $projectRoot 'tools\camera\build\outputs\apk\debug\camera-debug.apk'
    $version = [regex]::Match((Get-Content (Join-Path $projectRoot 'tools/camera/build.gradle') -Raw), 'versionName\s+"([^"]+)"').Groups[1].Value
    if (-not $version) { throw 'Unable to read app versionName.' }
    $outputApk = Join-Path $outputRoot "RS-Camera-$version-arm64.apk"
    Copy-Item -LiteralPath $builtApk -Destination $outputApk -Force
    Write-Output "APK ready: $outputApk"
    if ($Install) { & (Join-Path $PSScriptRoot 'install.ps1') -SdkPath $SdkPath -ApkPath $outputApk -Serial $Serial -ApplicationId 'com.intel.realsense.camera.av' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
