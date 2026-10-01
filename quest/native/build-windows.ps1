$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
function Invoke-Checked([string] $Program, [string[]] $Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed ($LASTEXITCODE)" }
}
function Write-Utf8NoBom([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
function Get-NativeFingerprint {
    $output = & python "$PSScriptRoot/fingerprint.py"
    if ($LASTEXITCODE -ne 0) { throw "fingerprint.py failed ($LASTEXITCODE)" }
    $line = @($output)[-1]
    if (-not $line) { throw 'fingerprint.py produced no output' }
    $fingerprint = $line.ToString().Trim()
    if ($fingerprint -notmatch '^[0-9a-f]{64}$') { throw "Native fingerprint is malformed: $fingerprint" }
    return $fingerprint
}
# License notices are required inputs: a missing one must fail the build.
function Resolve-Notice([string] $Base, [string[]] $Candidates) {
    foreach ($name in $Candidates) {
        $candidate = Join-Path $Base $name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "Required license notice is missing: $($Candidates -join ', ') under $Base"
}
function Copy-Notice([string] $Source, [string] $RelativeName) {
    $destination = Join-Path $licenseDir $RelativeName
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Copy-Item -LiteralPath $Source -Destination $destination -Force
}
function Get-CargoAboutVersion {
    try { $text = ((& cargo about --version) | Out-String) } catch { return '' }
    if ($LASTEXITCODE -ne 0) { return '' }
    return $text.Trim()
}

# The pinned source identity must be identical before and after the build, so a
# mutated input can never be packaged behind a matching manifest.
$fingerprint = Get-NativeFingerprint
Write-Host "Native source fingerprint: $fingerprint"

# Toolchains come from the image or the workflow; nothing is downloaded here.
Invoke-Checked cmake @('--version')
$rustcVersion = ((& rustc --version) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $rustcVersion -notmatch '1\.97\.1') { throw "Pinned Rust 1.97.1 is required (rustc: $rustcVersion)" }
$cargoVersion = ((& cargo --version) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $cargoVersion -notmatch '1\.97\.1') { throw "Pinned cargo 1.97.1 is required (cargo: $cargoVersion)" }
$libclangDir = $null
foreach ($candidate in @(
        $(if ($env:LIBCLANG_PATH) { $env:LIBCLANG_PATH }),
        "$env:ProgramFiles\LLVM\bin",
        "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2022\Enterprise\VC\Tools\Llvm\x64\bin",
        "$env:ProgramFiles\Microsoft Visual Studio\2022\Enterprise\VC\Tools\Llvm\x64\bin")) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'libclang.dll') -PathType Leaf)) { $libclangDir = $candidate; break }
}
if (-not $libclangDir) { throw 'LLVM libclang.dll not found; set LIBCLANG_PATH' }
$env:LIBCLANG_PATH = $libclangDir
Write-Host "Using libclang from $libclangDir"

Invoke-Checked python @("$PSScriptRoot/fetch.py")
$pyro = "$repo/build/quest/pyrowave"
$alvr = "$repo/build/quest/alvr"
Invoke-Checked cmake @('-S', $pyro, '-B', "$pyro/build-windows", '-A', 'x64', '-DPYROWAVE_DEVEL=OFF', '-DPYROWAVE_FP32_MATH=ON')
Invoke-Checked cmake @('--build', "$pyro/build-windows", '--config', 'Release', '--target', 'pyrowave-shared', '--parallel', '4')
$env:ALVR_PYROWAVE_DIR = $pyro
$fxc = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin/*/x64/fxc.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (!$fxc) { throw 'Windows SDK fxc not found' }
$env:FXC = $fxc.FullName
# The packaged tree must be exactly what this build produces, so the previous
# output is dropped first. The path is fixed and stays inside the ALVR build dir.
$alvrOut = "$alvr/build/alvr_streamer_windows"
$alvrBuildRoot = [IO.Path]::GetFullPath("$alvr/build") + [IO.Path]::DirectorySeparatorChar
if (-not ([IO.Path]::GetFullPath($alvrOut)).StartsWith($alvrBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unexpected ALVR output path.'
}
if (Test-Path -LiteralPath $alvrOut) { Remove-Item -Recurse -Force -LiteralPath $alvrOut }
Push-Location $alvr
try {
    Invoke-Checked cargo @('test', '--locked', '-p', 'alvr_packets', '-p', 'alvr_session', '-p', 'alvr_server_core')
    Invoke-Checked cargo @('xtask', 'build-streamer', '--release')
} finally { Pop-Location }

# Verify the derived tree. Never fall back to a recursive search or a wildcard
# match, which could pick a stale DLL from an earlier layout or an uploaded
# artifact; the Pyrowave import library must have this exact name.
if (-not (Test-Path -LiteralPath $alvrOut -PathType Container)) { throw "ALVR streamer output missing: $alvrOut" }
$driver = "$alvrOut/bin/win64"
$requiredOutputs = @(
    "$alvrOut/ALVR Dashboard.exe",
    "$alvrOut/driver.vrdrivermanifest",
    "$driver/driver_alvr_server.dll",
    "$driver/openvr_api.dll"
)
foreach ($file in $requiredOutputs) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "ALVR streamer output missing: $file" }
    if ((Get-Item -LiteralPath $file).Length -le 0) { throw "ALVR streamer output is empty: $file" }
}
$pyroDll = "$pyro\build-windows\Release\libpyrowave-shared-0.dll"
if (-not (Test-Path -LiteralPath $pyroDll -PathType Leaf)) {
    throw "Pyrowave shared library output missing: $pyroDll"
}
if ((Get-Item -LiteralPath $pyroDll).Length -le 0) { throw "Pyrowave shared library output is empty: $pyroDll" }
Copy-Item -LiteralPath $pyroDll -Destination "$driver/pyrowave-shared.dll" -Force

# The package root is always a fresh child of the repository build directory.
$package = [IO.Path]::GetFullPath("$repo/build/quest/native-windows")
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
if (-not $package.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Native package must be a child of the repository build directory.'
}
if (Test-Path -LiteralPath $package) { Remove-Item -Recurse -Force -LiteralPath $package }
New-Item -ItemType Directory -Force -Path $package | Out-Null
foreach ($file in Get-ChildItem -LiteralPath $alvrOut -Recurse -File) {
    if ($file.Extension -ieq '.pdb') { continue }
    $relative = $file.FullName.Substring($alvrOut.Length).TrimStart('\', '/')
    $destination = Join-Path $package $relative
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}

# build-streamer does not run include_licenses, so notices are produced here.
$licenseDir = Join-Path $package 'licenses'
New-Item -ItemType Directory -Force -Path $licenseDir | Out-Null
Copy-Notice (Resolve-Notice $alvr @('LICENSE')) 'ALVR.txt'
Copy-Notice (Resolve-Notice "$alvr/alvr/server_openvr" @('LICENSE-Valve')) 'Valve.txt'
Copy-Notice (Resolve-Notice "$alvr/openvr" @('LICENSE')) 'OpenVR.txt'
Copy-Notice (Resolve-Notice $pyro @('LICENSE')) 'Pyrowave/LICENSE'
$granite = "$pyro/Granite"
Copy-Notice (Resolve-Notice $granite @('LICENSE')) 'Granite/LICENSE'
Copy-Notice (Resolve-Notice "$granite/third_party/volk" @('LICENSE.md', 'LICENSE')) 'Granite/third_party/volk/LICENSE.md'
$headers = Resolve-Notice "$granite/third_party/khronos/vulkan-headers" @('LICENSE.md', 'LICENSE.txt')
Copy-Notice $headers 'Granite/third_party/khronos/vulkan-headers/LICENSE.md'
$headerDir = Split-Path -Parent $headers
$headerNoticesDir = Join-Path $headerDir 'LICENSES'
$headerNotices = @(Get-ChildItem -LiteralPath $headerNoticesDir -Filter '*.txt' -File -ErrorAction SilentlyContinue)
if ($headerNotices.Count -eq 0) { throw "Vulkan-Headers LICENSES notices are missing under $headerNoticesDir" }
foreach ($notice in $headerNotices) {
    Copy-Notice $notice.FullName "Granite/third_party/khronos/vulkan-headers/LICENSES/$($notice.Name)"
}

# Pin the license generator so the dependency notice list stays reproducible.
$about = Get-CargoAboutVersion
if ($about -notmatch '0\.8\.4\b') {
    Push-Location $alvr
    try {
        Invoke-Checked cargo @('install', '--locked', 'cargo-about', '--version', '0.8.4')
    } finally { Pop-Location }
    $about = Get-CargoAboutVersion
}
if ($about -notmatch '0\.8\.4\b') { throw "cargo-about 0.8.4 is required (found: $about)" }
$template = 'alvr/xtask/licenses_template.hbs'
if (-not (Test-Path -LiteralPath (Join-Path $alvr $template) -PathType Leaf)) { throw "ALVR license template missing: $template" }
$dependencies = Join-Path $licenseDir 'dependencies.html'
Push-Location $alvr
try {
    Invoke-Checked cargo @('about', 'generate', $template, '-o', $dependencies)
} finally { Pop-Location }
if (-not (Test-Path -LiteralPath $dependencies -PathType Leaf)) { throw 'cargo about did not produce dependencies.html' }
Write-Utf8NoBom $dependencies ([IO.File]::ReadAllText($dependencies))

$after = Get-NativeFingerprint
if ($after -ne $fingerprint) { throw "Native sources changed during the build: $fingerprint -> $after" }

# These are build-output digests, not an Authenticode or provenance attestation.
$sourceFile = Join-Path $package 'SOURCE_SHA256'
$sumsFile = Join-Path $package 'SHA256SUMS'
Write-Utf8NoBom $sourceFile "$fingerprint`n"
$digests = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
foreach ($file in Get-ChildItem -LiteralPath $package -Recurse -File) {
    if ($file.FullName -ieq $sourceFile -or $file.FullName -ieq $sumsFile) { continue }
    $relative = $file.FullName.Substring($package.Length).TrimStart('\', '/') -replace '\\', '/'
    $digests[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$paths = [string[]]($digests.Keys)
[Array]::Sort($paths, [StringComparer]::Ordinal)
$manifest = ''
foreach ($path in $paths) { $manifest += "$($digests[$path])  $path`n" }
Write-Utf8NoBom $sumsFile $manifest
Write-Host "Native package: $package ($($paths.Count) payload files)"
Get-FileHash -LiteralPath (Join-Path $package 'bin/win64/pyrowave-shared.dll') -Algorithm SHA256
Get-FileHash -LiteralPath (Join-Path $package 'bin/win64/driver_alvr_server.dll') -Algorithm SHA256
