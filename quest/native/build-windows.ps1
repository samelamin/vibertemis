$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
function Invoke-Checked([string] $Program, [string[]] $Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed ($LASTEXITCODE)" }
}
Invoke-Checked python @("$PSScriptRoot/fetch.py")
$pyro = "$repo/build/quest/pyrowave"
$alvr = "$repo/build/quest/alvr"
Invoke-Checked cmake @('-S', $pyro, '-B', "$pyro/build-windows", '-A', 'x64', '-DPYROWAVE_DEVEL=OFF', '-DPYROWAVE_FP32_MATH=ON')
Invoke-Checked cmake @('--build', "$pyro/build-windows", '--config', 'Release', '--target', 'pyrowave-shared', '--parallel', '4')
$env:ALVR_PYROWAVE_DIR = $pyro
$fxc = Get-ChildItem "${env:ProgramFiles(x86)}/Windows Kits/10/bin/*/x64/fxc.exe" | Sort-Object FullName -Descending | Select-Object -First 1
if (!$fxc) { throw 'Windows SDK fxc not found' }
$env:FXC = $fxc.FullName
Push-Location $alvr
try {
    Invoke-Checked cargo @('test', '--locked', '-p', 'alvr_packets', '-p', 'alvr_session', '-p', 'alvr_server_core', 'pcvr_codec')
    Invoke-Checked cargo @('xtask', 'build-streamer', '--release')
} finally { Pop-Location }
# Derive actual driver directory rather than assume the platform layout name.
$driver = Get-ChildItem "$alvr/build" -Filter 'driver_alvr_server.dll' -Recurse | Select-Object -First 1
if (!$driver) { throw 'ALVR driver output missing' }
Copy-Item "$pyro/build-windows/Release/pyrowave-shared.dll" $driver.DirectoryName
Get-FileHash "$($driver.DirectoryName)/pyrowave-shared.dll" -Algorithm SHA256
Get-FileHash $driver.FullName -Algorithm SHA256
