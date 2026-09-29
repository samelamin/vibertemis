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
$zipUrl = 'https://github.com/samelamin/vibertemis/releases/download/quest-preview-v0.1.0.3/vibertemis-vr-host-windows-0.1.0.3.zip'
$zipSha = 'bc193bbd1d9ed3dfb9252d921439257e50b52bfc88b13df09f909f917db11302'
$nativeFingerprint = '512e3203110ed3cbc822cf456e36e239dcd3bcb764feca62c316d910a37764f2'
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
New-Item -ItemType Directory -Force -Path $OutRoot, "$StagingRoot/manager/bin", "$StagingRoot/runtime" | Out-Null
$zip = Join-Path $OutRoot 'native.zip'
if (-not (Test-Path $zip) -or (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zipSha) {
    Invoke-WebRequest -Uri $zipUrl -OutFile $zip
}
if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zipSha) { throw 'Native ZIP digest mismatch' }
$fingerprint = & python "$RepoRoot/quest/native/fingerprint.py"
Check-Exit 'Native fingerprint'
if ($fingerprint -ne $nativeFingerprint) { throw 'Native sources differ from bundled runtime' }
Expand-Archive -Path $zip -DestinationPath "$StagingRoot/runtime"
Remove-Item "$StagingRoot/runtime/vibertemis-host-companion.exe"

Push-Location "$RepoRoot/quest/host"
$previousGOOS = $env:GOOS; $previousGOARCH = $env:GOARCH
try {
    $env:GOOS = 'windows'; $env:GOARCH = 'amd64'
    & go build -trimpath -ldflags '-s -w' -o "$StagingRoot/manager/bin/vibertemis-host-companion.exe" ./cmd/vibertemis-host-companion
    Check-Exit 'Companion build'
} finally { $env:GOOS = $previousGOOS; $env:GOARCH = $previousGOARCH; Pop-Location }

# Helper must exist before the manager embeds its digest. Both are standalone.
& dotnet publish "$RepoRoot/quest/installer/network-helper/VibertemisNetworkHelper.csproj" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:Version=0.1.0.4 -o "$OutRoot/helper"
Check-Exit 'Helper publish'
Copy-Item "$OutRoot/helper/VibertemisNetworkHelper.exe" "$StagingRoot/manager/"
$required = @(
    'manager/bin/vibertemis-host-companion.exe', 'manager/VibertemisNetworkHelper.exe',
    'runtime/ALVR Dashboard.exe', 'runtime/driver.vrdrivermanifest',
    'runtime/bin/win64/driver_alvr_server.dll', 'runtime/bin/win64/openvr_api.dll',
    'runtime/bin/win64/pyrowave-shared.dll', 'runtime/bin/win64/vcruntime140_1.dll'
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
& dotnet publish "$RepoRoot/quest/windows/VibertemisManager.App/VibertemisManager.App.csproj" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:Version=0.1.0.4 -o "$OutRoot/manager"
Check-Exit 'Manager publish'
Copy-Item "$OutRoot/manager/VibertemisManager.App.exe" "$StagingRoot/manager/"
if (-not $SkipInno) {
    & $InnoCompiler "/dMyStagingRoot=$StagingRoot" "/O$OutRoot" "$RepoRoot/quest/installer/installer.iss"
    Check-Exit 'Installer compile'
}
Write-Host "Installer build completed: $OutRoot"
