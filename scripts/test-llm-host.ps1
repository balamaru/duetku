$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$root = Split-Path $PSScriptRoot -Parent
$json = Join-Path $root '.cache/test-json.jar'
if (-not (Test-Path $json)) { throw 'Download org.json:json:20240303 to .cache/test-json.jar (host test only).' }
$lib = Join-Path $root '.tools/gradle/gradle-8.9/lib'
$android = Join-Path $env:ANDROID_HOME 'platforms/android-35/android.jar'
$out = Join-Path $root '.cache/llm-host-tests.jar'
& java -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$json;$android;$lib/kotlin-stdlib-1.9.23.jar" -d $out "$root/android/app/src/main/java/id/kelolauangku/app/application/LlmConfig.kt" "$root/android/app/src/main/java/id/kelolauangku/app/application/LlmAnalyzer.kt" "$root/tests/LlmContractTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin host test compilation failed.' }
& java -cp "$out;$json;$android;$lib/kotlin-stdlib-1.9.23.jar" LlmContractTestKt
if ($LASTEXITCODE -ne 0) { throw 'LLM host tests failed.' }
