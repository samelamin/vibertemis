[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path "$PSScriptRoot/../..").Path,
    [string]$OutRoot = "$RepoRoot/build/installer",
    [string]$StagingRoot = "$RepoRoot/build/installer-staging",
    [string]$InnoCompiler = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    [switch]$SkipInno
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Check-Exit([string]$label) {
    if ($LASTEXITCODE -ne 0) { throw "$label failed: $LASTEXITCODE" }
}
# Never accept an arbitrary deletion target for staging cleanup.
$StagingRoot = [IO.Path]::GetFullPath($StagingRoot)
$buildRoot = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'build')) + [IO.Path]::DirectorySeparatorChar
if (-not $StagingRoot.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'StagingRoot must be a child of the repository build directory.'
}
if (Test-Path $StagingRoot) { Remove-Item -Recurse -Force $StagingRoot }
New-Item -ItemType Directory -Force -Path $OutRoot, "$StagingRoot/manager/bin", "$StagingRoot/manager/prerequisites", "$StagingRoot/runtime" | Out-Null

# The native runtime is always rebuilt from the pinned sources in this tree, so
# the payload can never drift away from the sources that are being shipped.
$nativeBuilder = Join-Path $RepoRoot 'quest/native/build-windows.ps1'
if (-not (Test-Path -LiteralPath $nativeBuilder -PathType Leaf)) { throw "Native builder missing: $nativeBuilder" }
try { & $nativeBuilder } catch { throw "Native build failed: $($_.Exception.Message)" }

$nativePackage = Join-Path $RepoRoot 'build/quest/native-windows'
$sourceShaFile = Join-Path $nativePackage 'SOURCE_SHA256'
$sumsFile = Join-Path $nativePackage 'SHA256SUMS'
foreach ($manifest in @($sourceShaFile, $sumsFile)) {
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "Native manifest missing: $manifest" }
}
$recorded = [IO.File]::ReadAllText($sourceShaFile).Trim()
if ($recorded -notmatch '^[0-9a-f]{64}$') { throw "SOURCE_SHA256 is malformed: $recorded" }
$fingerprintOutput = & python (Join-Path $RepoRoot 'quest/native/fingerprint.py')
Check-Exit 'Native fingerprint'
$fingerprintLine = @($fingerprintOutput)[-1]
if (-not $fingerprintLine) { throw 'Native fingerprint produced no output' }
$fingerprint = $fingerprintLine.ToString().Trim()
if ($fingerprint -notmatch '^[0-9a-f]{64}$') { throw "Native fingerprint is malformed: $fingerprint" }
if ($recorded -ne $fingerprint) { throw "Native payload was built from other sources: $recorded != $fingerprint" }

# Payload contract: digest plus exactly two spaces plus a relative path, which
# may contain spaces (for example 'ALVR Dashboard.exe').
$requiredNative = @(
    'ALVR Dashboard.exe',
    'driver.vrdrivermanifest',
    'bin/win64/driver_alvr_server.dll',
    'bin/win64/openvr_api.dll',
    'bin/win64/pyrowave-shared.dll'
)
# Windows resolves paths case-insensitively, so the entry table must too:
# otherwise 'ALVR Dashboard.exe' and 'alvr dashboard.exe' would both pass the
# duplicate check and collapse onto the same staged file.
$digests = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
$linePattern = '^([0-9a-fA-F]{64})  (\S.*)$'
foreach ($line in [IO.File]::ReadAllLines($sumsFile)) {
    if ($line -eq '') { continue }
    if ($line -notmatch $linePattern) { throw "Malformed SHA256SUMS line: $line" }
    $digest = $Matches[1].ToLowerInvariant()
    $relative = $Matches[2]
    # A backslash is a second, unreviewed separator on Windows and a colon can
    # introduce a drive or ADS suffix, so neither is allowed in a relative entry.
    if ($relative -match '[\\:]') { throw "SHA256SUMS path must be relative and use '/' separators: $relative" }
    if ($relative -match '[\x00-\x1F\x7F]') { throw "SHA256SUMS path contains control characters: $relative" }
    if ($relative.StartsWith('/')) { throw "SHA256SUMS must not contain absolute paths: $relative" }
    foreach ($segment in $relative.Split('/')) {
        if ($segment -eq '' -or $segment -eq '.' -or $segment -eq '..') { throw "SHA256SUMS path escapes the package: $relative" }
        # Win32 trims trailing dots and spaces, which would let one entry alias
        # another ('win64.' and 'win64' are the same directory on disk).
        if ($segment.EndsWith('.') -or $segment.EndsWith(' ')) { throw "SHA256SUMS path has a Windows-ambiguous segment: $relative" }
    }
    if ($digests.ContainsKey($relative)) { throw "Duplicate SHA256SUMS entry: $relative" }
    $digests[$relative] = $digest
}
if ($digests.Count -eq 0) { throw 'SHA256SUMS is empty' }
foreach ($needed in $requiredNative) {
    if (-not $digests.ContainsKey($needed)) { throw "SHA256SUMS is missing required payload: $needed" }
}
# Verify every digest before a single byte is copied into the staging runtime.
# The entry table is only claimed to be relative, so each path is re-resolved
# to its canonical form and proven to stay inside its own root before use.
$packageRootFull = [IO.Path]::GetFullPath($nativePackage) + [IO.Path]::DirectorySeparatorChar
$runtimeRootFull = [IO.Path]::GetFullPath((Join-Path $StagingRoot 'runtime')) + [IO.Path]::DirectorySeparatorChar
$verified = [string[]]($digests.Keys)
[Array]::Sort($verified, [StringComparer]::Ordinal)
$sources = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in $verified) {
    $source = [IO.Path]::GetFullPath((Join-Path $nativePackage ($relative -replace '/', '\')))
    if (-not $source.StartsWith($packageRootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SHA256SUMS path escapes the native package: $relative"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "SHA256SUMS lists a missing file: $relative" }
    $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $digests[$relative]) { throw "Native payload digest mismatch: $relative" }
    $sources[$relative] = $source
}
foreach ($relative in $verified) {
    $source = $sources[$relative]
    $destination = [IO.Path]::GetFullPath((Join-Path $runtimeRootFull ($relative -replace '/', '\')))
    if (-not $destination.StartsWith($runtimeRootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SHA256SUMS path escapes the staging runtime: $relative"
    }
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Copy-Item -LiteralPath $source -Destination $destination
}
# Keep the provenance manifests next to the payload they describe.
Copy-Item -LiteralPath $sourceShaFile -Destination "$StagingRoot/runtime/SOURCE_SHA256"
Copy-Item -LiteralPath $sumsFile -Destination "$StagingRoot/runtime/SHA256SUMS"
Write-Host "Staged $($verified.Count) native payload files from $nativePackage"

Push-Location "$RepoRoot/quest/host"
$previousGOOS = $env:GOOS; $previousGOARCH = $env:GOARCH
try {
    $env:GOOS = 'windows'; $env:GOARCH = 'amd64'
    & go build -trimpath -ldflags '-s -w' -o "$StagingRoot/manager/bin/vibertemis-host-companion.exe" ./cmd/vibertemis-host-companion
    Check-Exit 'Companion build'
} finally { $env:GOOS = $previousGOOS; $env:GOARCH = $previousGOARCH; Pop-Location }

# Helper must exist before the manager embeds its digest. Both are standalone.
& dotnet publish "$RepoRoot/quest/installer/network-helper/VibertemisNetworkHelper.csproj" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:Version=0.1.0.13 -o "$OutRoot/helper"
Check-Exit 'Helper publish'
Copy-Item "$OutRoot/helper/VibertemisNetworkHelper.exe" "$StagingRoot/manager/"
# Download the official vc_redist.x64.exe from the Microsoft aka.ms redirect
# so the manager can verify it through WinVerifyTrust + Authenticode +
# manifest SHA-256 before any install attempt. The download is bounded
# (no mirror; canonical Microsoft URL only) and verified after fetch.
$vcRedistUrl = 'https://aka.ms/vc14/vc_redist.x64.exe'
$vcRedistOut = "$OutRoot/vc_redist.x64.exe"
if (-not (Test-Path $vcRedistOut)) {
    try {
        Invoke-WebRequest -Uri $vcRedistUrl -OutFile $vcRedistOut -MaximumRedirection 5 -UseBasicParsing
    } catch {
        throw "Failed to download bundled vc_redist.x64.exe from ${vcRedistUrl}: $($_.Exception.Message)"
    }
}
if (-not (Test-Path $vcRedistOut)) { throw "vc_redist.x64.exe was not downloaded." }
$vcRedistBytes = (Get-Item $vcRedistOut).Length
if ($vcRedistBytes -le 0 -or $vcRedistBytes -gt 64MB) { throw "Bundled VC++ runtime exceeds the allowed size." }
$vcSignature = Get-AuthenticodeSignature -LiteralPath $vcRedistOut
if ($vcSignature.Status -ne 'Valid' -or
    $vcSignature.SignerCertificate.GetNameInfo([System.Security.Cryptography.X509Certificates.X509NameType]::SimpleName, $false) -ne 'Microsoft Corporation') {
    throw 'Bundled VC++ runtime must carry a valid Microsoft Corporation signature.'
}
$vcVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($vcRedistOut)
$vcMinimum = [Version]'14.44.35207.0'
$vcActual = [Version]::new($vcVersion.FileMajorPart, $vcVersion.FileMinorPart, $vcVersion.FileBuildPart, $vcVersion.FilePrivatePart)
if ($vcActual -lt $vcMinimum) { throw "Bundled VC++ runtime $vcActual is older than $vcMinimum." }
Copy-Item $vcRedistOut "$StagingRoot/manager/prerequisites/vc_redist.x64.exe"
$required = @(
    'manager/bin/vibertemis-host-companion.exe', 'manager/VibertemisNetworkHelper.exe',
    'manager/prerequisites/vc_redist.x64.exe',
    'runtime/ALVR Dashboard.exe', 'runtime/driver.vrdrivermanifest',
    'runtime/bin/win64/driver_alvr_server.dll', 'runtime/bin/win64/openvr_api.dll',
    'runtime/bin/win64/pyrowave-shared.dll'
)
$entries = @($required | ForEach-Object {
    $file = Join-Path $StagingRoot $_
    $length = (Get-Item $file).Length
    if ($length -le 0) { throw "Empty required payload: $_" }
    [ordered]@{ path = $_; sha256 = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant(); size = $length }
})
$resource = "$RepoRoot/quest/windows/VibertemisManager.App/Resources/Integrity/integrity.json"
New-Item -ItemType Directory -Force -Path (Split-Path $resource) | Out-Null
[IO.File]::WriteAllText($resource, (ConvertTo-Json -InputObject $entries -Depth 5), [Text.UTF8Encoding]::new($false))
& dotnet publish "$RepoRoot/quest/windows/VibertemisManager.App/VibertemisManager.App.csproj" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:Version=0.1.0.13 -o "$OutRoot/manager"
Check-Exit 'Manager publish'
Copy-Item "$OutRoot/manager/VibertemisManager.App.exe" "$StagingRoot/manager/"
# Include redistributable notices for the new host manager and discovery stack.
$licenseDir = "$StagingRoot/runtime/licenses"
Push-Location "$RepoRoot/quest/host"
try {
    $modules = & go list -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' all
    Check-Exit 'Dependency license inventory'
    foreach ($line in $modules) {
        $parts = $line.Split('|')
        if ($parts.Length -eq 3 -and $parts[1] -ne '' -and (Test-Path "$($parts[2])/LICENSE")) {
            $name = ($parts[0] -replace '[^a-zA-Z0-9.-]', '_') + '-' + $parts[1] + '-LICENSE.txt'
            Copy-Item "$($parts[2])/LICENSE" (Join-Path $licenseDir $name)
        }
    }
    $goRoot = & go env GOROOT
    Check-Exit 'Go runtime license location'
    Copy-Item "$goRoot/LICENSE" "$licenseDir/Go-LICENSE.txt"
} finally { Pop-Location }
foreach ($package in @('microsoft.netcore.app.runtime.win-x64', 'microsoft.windowsdesktop.app.runtime.win-x64')) {
    foreach ($notice in Get-ChildItem "$env:USERPROFILE/.nuget/packages/$package/*/*.TXT" -ErrorAction SilentlyContinue) {
        if ($notice.Name -match 'LICENSE|NOTICE') {
            Copy-Item $notice.FullName "$licenseDir/$package-$($notice.Directory.Name)-$($notice.Name)"
        }
    }
}
if (-not $SkipInno) {
    & $InnoCompiler "/dMyStagingRoot=$StagingRoot" "/O$OutRoot" "$RepoRoot/quest/installer/installer.iss"
    Check-Exit 'Installer compile'
}
Write-Host "Installer build completed: $OutRoot"
