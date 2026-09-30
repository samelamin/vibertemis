# Real updater handoff on a disposable Windows runner. The fixture alone trusts
# an ephemeral test key. The installer under test is the unmodified release.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:CI -ne 'true') { throw 'Update smoke requires a disposable CI runner' }
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$setup = (Get-Item "$repo/build/installer/VibertemisVR-HostManager-Setup-0.1.0.7.exe").FullName
$root = Join-Path $env:RUNNER_TEMP ('VR update ü ' + [Guid]::NewGuid().ToString('N'))
$dest = "$root/Custom install"
$state = "$env:LOCALAPPDATA/VibertemisVRHostManager"
$pairing = "$env:LOCALAPPDATA/vibertemis/companion"
if ((Test-Path $state) -or (Test-Path $pairing)) { throw 'Expected clean test user' }
New-Item -ItemType Directory -Force -Path $root,$state,$pairing,"$root/quest/windows","$root/quest/update" | Out-Null
$rsa = [Security.Cryptography.RSA]::Create(3072)
$parent = $null; $worker = $null; $reopened = $null
$ready = $null; $commit = $null
try {
    # Keep the fixture build outside the source checkout and upload paths.
    foreach ($project in @('VibertemisManager.App','VibertemisManager.Core')) {
        & robocopy "$repo/quest/windows/$project" "$root/quest/windows/$project" /E /XD bin obj /NFL /NDL /NJH /NJS | Out-Null
        if ($LASTEXITCODE -gt 7) { throw 'Fixture source copy failed' }
    }
    [IO.File]::WriteAllText("$root/quest/update/public-key.pem", $rsa.ExportSubjectPublicKeyInfoPem())
    $signed = "$root/quest/windows/VibertemisManager.Core/Update/SignedRelease.cs"
    [IO.File]::WriteAllText($signed, ([IO.File]::ReadAllText($signed).Replace('CurrentSequence = 7','CurrentSequence = 6').Replace('CurrentVersion = "0.1.0.7"','CurrentVersion = "0.1.0.6"')))
    $projectFile = "$root/quest/windows/VibertemisManager.App/VibertemisManager.App.csproj"
    [IO.File]::WriteAllText($projectFile, ([IO.File]::ReadAllText($projectFile).Replace('0.1.0.7','0.1.0.6')))
    & dotnet publish $projectFile -c Release -r win-x64 --self-contained true -o "$root/fixture"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture publish failed' }
    $install = Start-Process $setup -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=`"$dest`"","/LOG=`"$repo/build/installer/smoke-update-initial.log`"") -PassThru -Wait
    if ($install.ExitCode -ne 0) { throw 'Initial install failed' }
    $manager = [IO.Path]::GetFullPath("$dest/manager/VibertemisManager.App.exe")
    Copy-Item "$root/fixture/VibertemisManager.App.exe" $manager -Force
    # No companion or startup task in this test. Payload/pairing sentinels
    # check that installing keeps user data byte-for-byte.
    [IO.File]::WriteAllText("$state/settings.json", '{"SchemaVersion":1,"RestoreCompanionOnStartup":false,"AutoStartWithWindows":false,"KeepHostReadyAfterSignIn":false,"StartupPreferencePersisted":true}')
    [IO.File]::WriteAllText("$pairing/state.json", '{"updateSmoke":"pairing preserved"}')
    [IO.File]::WriteAllText("$dest/runtime/session.json", '{"updateSmoke":"VR settings preserved"}')
    $pairHash = (Get-FileHash "$pairing/state.json").Hash
    $vrHash = (Get-FileHash "$dest/runtime/session.json").Hash
    Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
public static class UpdateSmokeExit {
 public delegate bool EnumProc(IntPtr window,IntPtr data);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc callback,IntPtr data);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr window,StringBuilder text,int count);
 [DllImport("user32.dll",SetLastError=true)] public static extern bool PostThreadMessage(uint thread,uint message,IntPtr w,IntPtr l);
 public static uint FindUiThread(int process) {
  uint found=0;
  EnumWindows((window,data)=> { uint pid; uint thread=GetWindowThreadProcessId(window,out pid);
   if(pid!=(uint)process) return true;
   var text=new StringBuilder(256); GetWindowText(window,text,256);
   if(text.ToString()=="VibertemisVR Host Manager") { found=thread; return false; }
   return true;
  },IntPtr.Zero); return found;
 }
 public static void Quit(uint thread) {
  if(thread==0 || !PostThreadMessage(thread,0x12,IntPtr.Zero,IntPtr.Zero))
   throw new Win32Exception(Marshal.GetLastWin32Error(),"Could not quit owned fixture UI thread");
 }
}
'@
    $parent = Start-Process $manager -PassThru
    $until = [DateTime]::UtcNow.AddSeconds(30)
    do {
        if ($parent.HasExited) { throw 'Fixture GUI exited before handoff' }
        $parentUiThread = [UpdateSmokeExit]::FindUiThread($parent.Id)
        if ($parentUiThread -ne 0) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $until)
    if ($parentUiThread -eq 0) { throw 'Owned fixture main window never appeared' }
    $id = [Guid]::NewGuid().ToString('N')
    $cache = [IO.Path]::GetFullPath("$state/update/$id")
    New-Item -ItemType Directory -Path $cache | Out-Null
    $helper = "$cache/VibertemisVR-HostManager-Update.exe"
    Copy-Item $manager $helper
    $filename = [IO.Path]::GetFileName($setup)
    Copy-Item $setup "$cache/$filename"
    $sha = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item $setup).Length
    $manifest = [Text.Encoding]::UTF8.GetBytes((@{
        schema=1; channel='quest-preview'; sequence=7; version='0.1.0.7'; native_protocol='20.14.1-vibertemis-pyro.1'
        assets=@{windows=@{filename=$filename;url="https://github.com/samelamin/vibertemis/releases/download/quest-preview-v0.1.0.7/$filename";bytes=$size;sha256=$sha}}
    } | ConvertTo-Json -Depth 8 -Compress))
    $signature = $rsa.SignData($manifest,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $readyName = "Local\VibertemisUpdateWorker-$id-Ready"
    $commitName = "Local\VibertemisUpdateWorker-$id-Commit"
    $job = @{
        schema=1;expectedVersion='0.1.0.7';expectedSequence=7;originalManagerPath=$manager;programsRoot=[IO.Path]::GetFullPath($dest);parentPid=$parent.Id
        cacheDir=$cache;installerFilename=$filename;installerSha256=$sha;installerBytes=$size
        manifestBase64=[Convert]::ToBase64String($manifest);signatureBase64=[Convert]::ToBase64String($signature)
        readyEventName=$readyName;commitEventName=$commitName
        originalManagerHash=[Convert]::ToBase64String([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($manager)))
    }
    $jobPath = "$cache/update-job.json"
    [IO.File]::WriteAllText($jobPath, ($job | ConvertTo-Json -Depth 8))
    $ready = [Threading.EventWaitHandle]::new($false,[Threading.EventResetMode]::AutoReset,$readyName)
    $commit = [Threading.EventWaitHandle]::new($false,[Threading.EventResetMode]::AutoReset,$commitName)
    $worker = Start-Process $helper -ArgumentList @('--apply-update',"`"$jobPath`"") -PassThru
    if (-not $ready.WaitOne(30000)) { throw 'Worker never validated job/signaled readiness' }
    if ($worker.HasExited) { throw 'Worker exited before commit' }
    $commit.Set() | Out-Null
    [UpdateSmokeExit]::Quit($parentUiThread)
    if (-not $parent.WaitForExit(30000)) { throw 'Parent did not exit' }
    if (-not $worker.WaitForExit(300000)) { throw 'Worker installation timed out' }
    if ($worker.ExitCode -ne 0) {
        Get-Content "$state/updates/last-update.json" -ErrorAction SilentlyContinue
        throw "Worker failed: $($worker.ExitCode)"
    }
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo($manager).FileVersion -ne '0.1.0.7') { throw 'Installed FileVersion unchanged' }
    $until = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $reopened = Get-Process -Name VibertemisManager.App -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $manager } | Select-Object -First 1
        if ($reopened -and (Test-Path "$state/updates/last-update.json.seen")) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $until)
    if (-not $reopened) { throw 'Updated GUI did not reopen' }
    $outcome = Get-Content "$state/updates/last-update.json.seen" -Raw | ConvertFrom-Json
    if ($outcome.kind -ne 'Success' -or $outcome.installedFileVersion -ne '0.1.0.7' -or $outcome.installerExitCode -ne 0 -or $outcome.verifyInstallExitCode -ne 0) { throw 'Update outcome did not prove installation' }
    if ((Get-FileHash "$pairing/state.json").Hash -ne $pairHash -or (Get-FileHash "$dest/runtime/session.json").Hash -ne $vrHash) { throw 'Update changed pairing or VR settings' }
    Copy-Item $outcome.installerLogPath "$repo/build/installer/smoke-update-handoff.log"
    $outcome | ConvertTo-Json | Set-Content "$repo/build/installer/smoke-update-outcome.log"
    Write-Host 'PASS: real signed worker job, retained parent/installer handles, custom Unicode install path, installed FileVersion, payload verification, GUI reopen, outcome and user-data retention'
} finally {
    foreach ($process in @($worker,$parent,$reopened)) {
        if ($null -ne $process) { try { if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() } } finally { $process.Dispose() } }
    }
    if ($ready) { $ready.Dispose() }; if ($commit) { $commit.Dispose() }; $rsa.Dispose()
    $uninstall = Get-ChildItem $dest -Filter 'unins*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($uninstall) { Start-Process $uninstall.FullName -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' -Wait }
    Remove-Item $state,$pairing -Recurse -Force -ErrorAction SilentlyContinue
}
