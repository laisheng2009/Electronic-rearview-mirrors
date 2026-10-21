param(
    [string]$GoExecutable,
    [string]$AndroidNdk,
    [string]$BuildRoot,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
    $BuildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "..\.build-tunnel-native"))
}
if ([string]::IsNullOrWhiteSpace($GoExecutable)) {
    $GoExecutable = Join-Path $BuildRoot "go\bin\go.exe"
}
if ([string]::IsNullOrWhiteSpace($AndroidNdk)) {
    $AndroidNdk = Join-Path $env:ANDROID_SDK_ROOT "ndk\30.0.15729638"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot "app\src\main\assets\third-party\tunnel\LICENSES.txt"
}

$goPath = (Resolve-Path $GoExecutable).Path
$ndkPath = (Resolve-Path $AndroidNdk).Path
$buildModFile = Join-Path $BuildRoot "tunnel-source-patches\tunnel-build.mod"
if (-not (Test-Path $buildModFile)) {
    throw "Run build.ps1 first so the pinned native dependency graph is available."
}

$env:GOPATH = Join-Path $BuildRoot "gopath"
$env:GOMODCACHE = Join-Path $env:GOPATH "pkg\mod"
$env:GOTOOLCHAIN = "local"
$env:GOFLAGS = ""
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CGO_ENABLED = "1"
$env:CC = Join-Path $ndkPath "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"

Push-Location $PSScriptRoot
try {
    $lines = & $goPath list "-modfile=$buildModFile" -tags=ts_omit_logtail -deps `
        -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}{{end}}' .
    if ($LASTEXITCODE -ne 0) { throw "Could not resolve the pinned Android dependency graph." }

    $modules = @($lines | Where-Object { $_ } | Sort-Object -Unique)
    $entries = @()
    foreach ($line in $modules) {
        $parts = $line -split '\|', 3
        if ($parts.Count -ne 3 -or $parts[0] -eq "com.byd.extend/camera-tunnel") { continue }
        $licenseFiles = @(Get-ChildItem -LiteralPath $parts[2] -File | Where-Object {
            $_.Name -match '^(LICENSE|LICENCE|COPYING)([-.]|$)|^NOTICE([-\.]|$)'
        } | Sort-Object Name)
        if ($licenseFiles.Count -eq 0) {
            throw "No license or notice file was found for $($parts[0])@$($parts[1])."
        }
        $entries += [pscustomobject]@{
            Module = "$($parts[0])@$($parts[1])"
            Files = $licenseFiles
        }
    }

    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine("Third-party licenses for the Android camera tunnel runtime")
    [void]$builder.AppendLine("Dependency closure: Android arm64, Go build tag ts_omit_logtail.")
    [void]$builder.AppendLine("Each module is pinned in app/src/main/native/tunnel/go.mod and go.sum.")
    [void]$builder.AppendLine()
    foreach ($entry in ($entries | Sort-Object Module)) {
        [void]$builder.AppendLine("================================================================")
        [void]$builder.AppendLine($entry.Module)
        [void]$builder.AppendLine("================================================================")
        foreach ($licenseFile in $entry.Files) {
            [void]$builder.AppendLine("--- $($licenseFile.Name) ---")
            [void]$builder.AppendLine([IO.File]::ReadAllText($licenseFile.FullName).TrimEnd())
            [void]$builder.AppendLine()
        }
    }

    $outputDirectory = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    [IO.File]::WriteAllText($OutputPath, $builder.ToString(), [Text.UTF8Encoding]::new($false))
    Write-Output "Wrote notices for $($entries.Count) native dependency modules to $OutputPath"
} finally {
    Pop-Location
}
