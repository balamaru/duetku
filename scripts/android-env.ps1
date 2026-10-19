$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdk = Get-ChildItem (Join-Path $projectRoot '.tools/jdk') -Directory | Select-Object -First 1
if (-not $jdk) { throw 'JDK portabel belum tersedia di .tools/jdk.' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.cache/android'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.cache/gradle'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk/28.2.13676358'
$env:GOCACHE = Join-Path $projectRoot '.cache/go-build'
$env:GOMODCACHE = Join-Path $projectRoot '.cache/go-mod'
$env:GOPATH = Join-Path $projectRoot '.tools/go-work'
$env:GOBIN = Join-Path $env:GOPATH 'bin'
$env:GOPROXY = 'https://proxy.golang.org'
$env:GOTOOLCHAIN = 'local'
$env:PATH = "$env:JAVA_HOME/bin;$projectRoot/.tools/compiler/go/bin;$env:GOBIN;$env:ANDROID_HOME/platform-tools;$projectRoot/.tools/gradle/gradle-8.9/bin;$env:PATH"
