# Run the dependency/localization tests without downloading Rider SDK.
# 无需 Rider SDK，在 Windows 的 JDK 21 环境中检查核心功能。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$main = Join-Path $root 'build/offline-check/main'
$test = Join-Path $root 'build/offline-check/test'
New-Item -ItemType Directory -Force $main,$test | Out-Null
$sources = @(
    Get-ChildItem -Path (Join-Path $root 'src/main/java/dev/astral/modforge/core') -Filter '*.java' -File
    Get-ChildItem -Path (Join-Path $root 'src/main/java/dev/astral/modforge/cli') -Filter '*.java' -File
) | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 --release 21 -d $main @sources
if ($LASTEXITCODE -ne 0) { throw 'Java main compilation failed' }
& javac -encoding UTF-8 --release 21 -cp $main -d $test (Join-Path $root 'src/test/java/dev/astral/modforge/core/CoreChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed' }
$separator = [IO.Path]::PathSeparator
& java -cp "$main$separator$test" dev.astral.modforge.core.CoreChecks
if ($LASTEXITCODE -ne 0) { throw 'CoreChecks failed' }
