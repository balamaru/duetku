# Produces an optimized APK signed with the local TEST key, never a production key.
param([switch]$SkipBridge)
. "$PSScriptRoot/android-env.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$artifactDir = Join-Path $projectRoot 'artifacts/android'
New-Item -ItemType Directory -Force $artifactDir | Out-Null
function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)." }
}
if (-not $SkipBridge) {
    New-Item -ItemType Directory -Force (Join-Path $projectRoot 'android/app/libs') | Out-Null
    Push-Location (Join-Path $projectRoot 'core-go')
    try {
        gomobile bind -target=android/arm64 -androidapi=33 -ldflags='-s -w' -o ../android/app/libs/core.aar ./mobile
        Assert-Exit 'Go Android bridge'
    } finally { Pop-Location }
}
Push-Location (Join-Path $projectRoot 'android')
try {
    gradle --no-daemon --console=plain :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintRelease :spike:assembleNativeRelease :spike:assembleWithgoRelease
    Assert-Exit 'Gradle build and lint'
} finally { Pop-Location }
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$unsigned = Join-Path $projectRoot 'android/app/build/outputs/apk/release/app-release-unsigned.apk'
$aligned = Join-Path $artifactDir 'duetku-arm64-aligned.apk'
$signed = Join-Path $artifactDir 'duetku-arm64-test.apk'
& "$buildTools/zipalign.exe" -f -P 16 4 $unsigned $aligned
Assert-Exit 'APK alignment'
& "$buildTools/apksigner.bat" sign --ks "$projectRoot/.cache/android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $aligned
Assert-Exit 'Test signing'
python "$PSScriptRoot/verify-apk.py" $signed --build-tools $buildTools --out "$artifactDir/verification"
Assert-Exit 'APK verification'
Write-Output "APK uji: $signed"
