$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$setup = (Get-Item "$repo/build/installer/VibertemisVR-HostManager-Setup-0.1.0.13.exe").FullName
$root = Join-Path $env:RUNNER_TEMP ('Vibertemis smoke ü ' + [Guid]::NewGuid().ToString('N'))
$dest = Join-Path $root 'Custom VR install'
New-Item -ItemType Directory -Path $root | Out-Null
function Run-Setup([string]$label, [bool]$expectSuccess) {
    $log = "$repo/build/installer/smoke-$label.log"
    $process = Start-Process -FilePath $setup -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/DIR=`"$dest`"", "/LOG=`"$log`"") -Wait -PassThru
    if (($process.ExitCode -eq 0) -ne $expectSuccess) { throw "Setup $label exit $($process.ExitCode), expected success=$expectSuccess" }
}
Run-Setup 'fresh' $true
$manager = "$dest/manager/VibertemisManager.App.exe"
$pe = [IO.File]::ReadAllBytes($manager)
$header = [BitConverter]::ToInt32($pe, 0x3c)
if ([BitConverter]::ToUInt16($pe, $header + 24 + 68) -ne 2) { throw 'Manager is a console app; GUI subsystem required' }
$verify = Start-Process -FilePath $manager -ArgumentList '--verify-install' -Wait -PassThru
if ($verify.ExitCode -ne 0) { throw 'Installed payload diagnostic failed' }
& "$PSScriptRoot/smoke-recovery.ps1" -Manager $manager
$session = "$dest/runtime/session.json"
[IO.File]::WriteAllText($session, '{"_smoke":"retain exact bytes"}')
$sessionHash = (Get-FileHash $session).Hash
# Untracked user data under the actual runtime must survive too.
[IO.File]::WriteAllText("$dest/runtime/user-notes.txt", 'keep')
Run-Setup 'reinstall' $true
if ((Get-FileHash $session).Hash -ne $sessionHash) { throw 'Reinstall altered VR settings' }
$uninstaller = (Get-ChildItem $dest -Filter 'unins*.exe' | Select-Object -First 1).FullName
# Controlled named process fixture; never start SteamVR on CI.
Copy-Item "$env:WINDIR/System32/ping.exe" "$root/vrserver.exe"
$fixture = Start-Process "$root/vrserver.exe" -ArgumentList @('-t', '127.0.0.1') -WindowStyle Hidden -PassThru
try {
    if ($fixture.HasExited) { throw 'Busy-process fixture did not start' }
    Run-Setup 'busy-refused' $false
    $blocked = Start-Process $uninstaller -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' -Wait -PassThru
    if ($blocked.ExitCode -eq 0) { throw 'Uninstall failed to refuse active VR process' }
    if (-not (Test-Path $manager)) { throw 'Busy refusal removed installed manager' }
} finally {
    # Only stop this test-owned fixture, not any real VR process.
    if (-not $fixture.HasExited) { $fixture.Kill(); $fixture.WaitForExit() }
    $fixture.Dispose()
}
# Hash enforcement must detect modified installed payload.
$companion = "$dest/manager/bin/vibertemis-host-companion.exe"
$bytes = [IO.File]::ReadAllBytes($companion)
try {
    $damaged = [byte[]]$bytes.Clone(); $damaged[0] = $damaged[0] -bxor 1
    [IO.File]::WriteAllBytes($companion, $damaged)
    $verify = Start-Process -FilePath $manager -ArgumentList '--verify-install' -Wait -PassThru
    if ($verify.ExitCode -eq 0) { throw 'Tampered payload passed integrity check' }
} finally { [IO.File]::WriteAllBytes($companion, $bytes) }
$removed = Start-Process $uninstaller -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' -Wait -PassThru
if ($removed.ExitCode -ne 0) { throw "Uninstall failed: $($removed.ExitCode)" }
if (Test-Path $manager) { throw 'Manager executable survived uninstall' }
if ((Get-FileHash $session).Hash -ne $sessionHash) { throw 'Uninstall altered VR settings' }
if ((Get-Content "$dest/runtime/user-notes.txt" -Raw) -ne 'keep') { throw 'Uninstall removed user data' }
Write-Host 'PASS: custom install path, hashes, reinstall, active-VR refusal, uninstall and settings retention'
