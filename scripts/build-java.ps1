param(
    [Parameter(Mandatory = $true)][string]$SdkPath,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$GradleExecutable = '',
    [string]$RobolectricSdkDir = '',
    [switch]$Install,
    [string]$Serial = ''
)
# Java-only development build. Reuses the exact native SDK from the original APK.
# Use build.ps1 for C++/JNI changes or a full native rebuild.
$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $packageRoot 'source/librealsense-2.58.1/wrappers/android'
$sdkRoot = (Resolve-Path -LiteralPath $SdkPath).Path
$jdkRoot = (Resolve-Path -LiteralPath $JavaHome).Path
if ((Get-Content (Join-Path $jdkRoot 'release') -Raw) -notmatch 'JAVA_VERSION="17[.\"]') {
    throw 'Select JDK 17 for this build.'
}
foreach ($required in @('platforms/android-35/android.jar', 'build-tools/35.0.0/aapt.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot $required))) { throw "Missing SDK component: $required" }
}
$workRoot = Join-Path $packageRoot '.work'
$outputRoot = Join-Path $packageRoot 'outputs'
# A fresh directory keeps this build independent of native/Gradle caches in the source.
$buildRoot = Join-Path $workRoot ('java-build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $buildRoot,$outputRoot -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
foreach ($name in @('build.gradle','gradle.properties','gradlew','gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination $buildRoot
}
Copy-Item -LiteralPath (Join-Path $sourceRoot 'gradle') -Destination $buildRoot -Recurse
# This variant contains no native compilation; AGP's native ASCII-path check can be disabled.
[IO.File]::AppendAllText((Join-Path $buildRoot 'gradle.properties'),
    [Environment]::NewLine + 'android.overridePathCheck=true' + [Environment]::NewLine, $utf8)
[IO.File]::WriteAllText((Join-Path $buildRoot 'settings.gradle'), @'
pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = 'RS-Camera-AV'
include ':librealsense', ':tools:camera'
'@, $utf8)
foreach ($module in @('librealsense','tools/camera')) {
    $destination = Join-Path $buildRoot $module
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceRoot "$module/src") -Destination $destination -Recurse
    if ($module -eq 'tools/camera') {
        foreach ($file in @('build.gradle','proguard-rules.pro')) {
            Copy-Item -LiteralPath (Join-Path $sourceRoot "$module/$file") -Destination $destination
        }
        if (Test-Path -LiteralPath (Join-Path $sourceRoot "$module/libs")) {
            Copy-Item -LiteralPath (Join-Path $sourceRoot "$module/libs") -Destination $destination -Recurse
        }
    }
}
[IO.File]::WriteAllText((Join-Path $buildRoot 'librealsense/build.gradle'), @'
plugins { id 'com.android.library' }
android {
    namespace 'com.intel.realsense.librealsense'
    compileSdk defaultCompileSdkVersion
    defaultConfig {
        minSdk defaultMinSdkVersion
        targetSdk defaultTargetSdkVersion
        ndk { abiFilters 'arm64-v8a' }
    }
}
dependencies { implementation "androidx.appcompat:appcompat:$appcompatVersion" }
'@, $utf8)
$appBuildGradle = Join-Path $sourceRoot 'tools/camera/build.gradle'
$version = [regex]::Match((Get-Content -LiteralPath $appBuildGradle -Raw), 'versionName\s+"([^"]+)"').Groups[1].Value
if (-not $version) { throw 'Unable to read versionName from tools/camera/build.gradle.' }
$nativeApk = Join-Path $outputRoot "RS-Camera-$version-arm64.apk"
if (-not (Test-Path -LiteralPath $nativeApk)) {
    $nativeApk = Join-Path $packageRoot 'apk/RS-Camera-0.0.7-rgb-arm64.apk'
}
if (-not (Test-Path -LiteralPath $nativeApk)) {
    throw "Java-only build needs the matching APK in outputs (download it from GitHub Releases) or apk/RS-Camera-0.0.7-rgb-arm64.apk to reuse librealsense2.so."
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($nativeApk)
try {
    $entry = $archive.GetEntry('lib/arm64-v8a/librealsense2.so')
    if ($null -eq $entry) { throw 'Bundled native SDK was not found in the original APK.' }
    $nativeDir = Join-Path $buildRoot 'librealsense/src/main/jniLibs/arm64-v8a'
    New-Item -ItemType Directory -Path $nativeDir -Force | Out-Null
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $nativeDir 'librealsense2.so'))
} finally { $archive.Dispose() }
[IO.File]::WriteAllText((Join-Path $buildRoot 'local.properties'),
    'sdk.dir=' + $sdkRoot.Replace('\','/') + [Environment]::NewLine, $utf8)
$savedJava = $env:JAVA_HOME
$savedAndroid = $env:ANDROID_HOME
$savedAndroidUser = $env:ANDROID_USER_HOME
$savedGradle = $env:GRADLE_USER_HOME
$savedRobolectric = $env:RS_ROBOLECTRIC_SDK_DIR
try {
    $env:JAVA_HOME = $jdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_USER_HOME = Join-Path $workRoot 'android'
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $workRoot 'gradle' }
    if ($RobolectricSdkDir) { $env:RS_ROBOLECTRIC_SDK_DIR = (Resolve-Path -LiteralPath $RobolectricSdkDir).Path }
    if (-not $GradleExecutable) { $GradleExecutable = Join-Path $buildRoot 'gradlew.bat' }
    Push-Location -LiteralPath $buildRoot
    try {
        $ErrorActionPreference = 'Continue'
        & $GradleExecutable ':tools:camera:assembleDebug' ':tools:camera:assembleDebugAndroidTest' ':tools:camera:testDebugUnitTest' --no-daemon --max-workers=4 --console=plain 2>&1 |
            Tee-Object -FilePath (Join-Path $outputRoot 'build-java.log')
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($buildExit -ne 0) { throw "Build failed ($buildExit). See outputs/build-java.log." }
    } finally { Pop-Location }
    $version = [regex]::Match((Get-Content (Join-Path $sourceRoot 'tools/camera/build.gradle') -Raw), 'versionName\s+"([^"]+)"').Groups[1].Value
    if (-not $version) { throw 'Unable to read app versionName.' }
    $outputApk = Join-Path $outputRoot "RS-Camera-$version-arm64.apk"
    Copy-Item -LiteralPath (Join-Path $buildRoot 'tools/camera/build/outputs/apk/debug/camera-debug.apk') -Destination $outputApk -Force
    Copy-Item -LiteralPath (Join-Path $buildRoot 'tools/camera/build/outputs/apk/androidTest/debug/camera-debug-androidTest.apk') -Destination (Join-Path $outputRoot 'RS-Camera-AV-androidTest.apk') -Force
    $previewDir = Join-Path $outputRoot 'ui-preview'
    New-Item -ItemType Directory -Path $previewDir -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $buildRoot 'tools/camera/build/ui-screenshots') -Filter '*.png' |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $previewDir -Force }
    [IO.File]::WriteAllText((Join-Path $outputRoot 'native-library-origin.txt'),
        "Native SDK reused from: $nativeApk`r`nAPK SHA256: " +
        (Get-FileHash $nativeApk -Algorithm SHA256).Hash + "`r`nC++/JNI was not rebuilt. Java and Android resources were rebuilt.`r`n", $utf8)
    Write-Output "APK ready: $outputApk"
    if ($Install) {
        & (Join-Path $PSScriptRoot 'install.ps1') -SdkPath $sdkRoot -ApkPath $outputApk -Serial $Serial -ApplicationId 'com.intel.realsense.camera.av'
    }
} finally {
    $env:JAVA_HOME = $savedJava
    $env:ANDROID_HOME = $savedAndroid
    $env:ANDROID_USER_HOME = $savedAndroidUser
    $env:GRADLE_USER_HOME = $savedGradle
    $env:RS_ROBOLECTRIC_SDK_DIR = $savedRobolectric
}
