# Exercise the actual installed GUI/child processes on the disposable CI runner.
param([Parameter(Mandatory=$true)][string]$Manager)
$Manager = [IO.Path]::GetFullPath($Manager)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:CI -ne 'true') { throw 'Recovery smoke is restricted to a disposable CI runner' }
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class RecoverySmokeUi {
 public delegate bool EnumProc(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll", EntryPoint="SendMessageTimeoutW", CharSet=CharSet.Unicode)] public static extern IntPtr ReadText(IntPtr h, uint m, IntPtr w, StringBuilder text, uint flags, uint timeout, out IntPtr result);
 [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
 public static IntPtr Find(int pid, string label) {
  IntPtr found=IntPtr.Zero;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p!=(uint)pid) return true;
   EnumChildWindows(h,(c,x)=> { var s=new StringBuilder(512); IntPtr result; if(ReadText(c,0xD,(IntPtr)512,s,2,2000,out result)!=IntPtr.Zero && s.ToString()==label) found=c; return true; },IntPtr.Zero);
   return true;
  },IntPtr.Zero); return found;
 }
 public static bool Visible(int pid) {
  bool visible=false;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p==(uint)pid && IsWindowVisible(h)) visible=true; return true; },IntPtr.Zero);
  return visible;
 }
 public static void Click(IntPtr h) { IntPtr result; if(h==IntPtr.Zero || SendMessageTimeout(h,0xF5,IntPtr.Zero,IntPtr.Zero,2,15000,out result)==IntPtr.Zero) throw new Exception("GUI control click failed"); }
}
'@
function Wait-Until([scriptblock]$Check, [string]$Label, [int]$Seconds=60) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    do { if (& $Check) { return }; Start-Sleep -Milliseconds 300 } while ([DateTime]::UtcNow -lt $until)
    throw "Timed out: $Label"
}
$settingsDir = Join-Path $env:LOCALAPPDATA 'VibertemisVRHostManager'
$pairingDir = Join-Path $env:LOCALAPPDATA 'vibertemis/companion'
$runPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runName = 'VibertemisVRHostManager'
if ((Test-Path $settingsDir) -or (Test-Path $pairingDir)) { throw 'Expected clean CI user state' }
if (Get-ItemProperty -Path $runPath -Name $runName -ErrorAction SilentlyContinue) { throw 'Unexpected existing login startup entry' }
$adapter = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | Where-Object {
    $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' -and
    $_.Description -notmatch 'VirtualBox|VMware|VPN|Tailscale|ZeroTier|Hyper-V Virtual Switch'
} | ForEach-Object {
    $nic = $_
    $_.GetIPProperties().UnicastAddresses | Where-Object {
        $_.Address.AddressFamily -eq 'InterNetwork' -and $_.Address.ToString() -notmatch '^(127\.|169\.254\.)'
    } | ForEach-Object { [PSCustomObject]@{Id=$nic.Id;Address=$_.Address.ToString()} }
} | Select-Object -First 1
if (-not $adapter) { throw 'No usable CI network interface' }
New-Item -ItemType Directory -Path $settingsDir | Out-Null
@{LastSelectedAdapterId=$adapter.Id;LastSelectedAdapterAddress=$adapter.Address;AutoStartWithWindows=$false;RestoreCompanionOnStartup=$true;CompanionListenPort=28540;SchemaVersion=1} |
    ConvertTo-Json | Set-Content -Path "$settingsDir/settings.json" -Encoding utf8
$script:managerProcess = $null
$script:child = $null
function Find-Child {
    $items = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($script:managerProcess.Id) AND Name='vibertemis-host-companion.exe'")
    if ($items.Count -gt 1) { throw 'Duplicate companions for one manager' }
    if ($items.Count -eq 1) { return $items[0] }
    return $null
}
function Wait-Listening {
    Wait-Until {
        $script:child = Find-Child
        if (-not $script:child) { return $false }
        $listener = @(Get-NetTCPConnection -State Listen -LocalPort 28540 -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $script:child.ProcessId -and $_.LocalAddress -eq $adapter.Address })
        return $listener.Count -eq 1
    } 'owned companion listening'
}
function Stop-TestProcesses {
    if ($script:managerProcess) {
        $owned = Find-Child
        try { if (-not $script:managerProcess.HasExited) { $script:managerProcess.Kill(); $script:managerProcess.WaitForExit() } } catch { if (-not $script:managerProcess.HasExited) { throw } }
        if ($owned) { $p = Get-Process -Id $owned.ProcessId -ErrorAction SilentlyContinue; if ($p) { try { $p.Kill(); $p.WaitForExit() } catch { if (-not $p.HasExited) { throw } } finally { $p.Dispose() } } }
        $script:managerProcess.Dispose(); $script:managerProcess = $null
    }
}
try {
    $script:managerProcess = Start-Process $Manager -ArgumentList '--tray-only --silent' -PassThru
    Wait-Listening
    $identity = "$pairingDir/state.json"
    $identityHash = (Get-FileHash $identity).Hash
    # Wake the existing tray instance, as opening its Start-menu shortcut does.
    # This also creates child HWNDs before cross-process control inspection.
    $wake = Start-Process $Manager -PassThru
    if (-not $wake.WaitForExit(15000)) { throw 'Second instance did not signal existing manager' }
    if ($wake.ExitCode -ne 0) { throw 'Second instance failed' }
    $wake.Dispose()
    Wait-Until { [RecoverySmokeUi]::Visible($script:managerProcess.Id) } 'tray wake visibility'
    Start-Sleep -Seconds 2
    if (-not [RecoverySmokeUi]::Visible($script:managerProcess.Id)) { throw 'Tray wake immediately hid the manager again' }
    # Pairing is a primary action, not hidden behind Advanced. Opening it uses
    # the production local TLS/HMAC client against the installed companion.
    $pair = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Pair headset')
    if ($pair -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($pair)) { throw 'Pair headset is not visible by default' }
    $export = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Export pairing file')
    if ($export -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible($export)) { throw 'Manual export should stay under Advanced' }
    if (-not [RecoverySmokeUi]::PostMessage($pair,0xF5,[IntPtr]::Zero,[IntPtr]::Zero)) { throw 'Could not open Pair headset' }
    $approveLabel = 'Codes match — approve'
    Wait-Until {
        $approve = [RecoverySmokeUi]::Find($script:managerProcess.Id,$approveLabel)
        return $approve -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible($approve)
    } 'headset approval prompt'
    if ([RecoverySmokeUi]::IsWindowEnabled([RecoverySmokeUi]::Find($script:managerProcess.Id,$approveLabel))) { throw 'Approval enabled before a headset request' }
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Ready for a request. Pairing closes automatically after two minutes.') -ne [IntPtr]::Zero } 'actual pairing window opened'
    $close = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Close')
    if ($close -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::PostMessage($close,0xF5,[IntPtr]::Zero,[IntPtr]::Zero)) { throw 'Could not close Pair headset' }
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,$approveLabel) -eq [IntPtr]::Zero } 'pairing prompt closed'
    if (Get-Process -Name vrserver,vrmonitor,vrcompositor -ErrorAction SilentlyContinue) { throw 'Pairing unexpectedly started SteamVR' }
    # Production startup checkbox must register the exact installed executable.
    $readyLabel = 'Keep host ready after Windows sign-in'
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,$readyLabel) -ne [IntPtr]::Zero } 'automatic hosting control'
    [RecoverySmokeUi]::Click([RecoverySmokeUi]::Find($script:managerProcess.Id,$readyLabel))
    $command = (Get-ItemProperty -Path $runPath -Name $runName).$runName
    if ($command -ne "`"$Manager`" --tray-only --silent") { throw 'Login startup does not target installed manager' }
    # Kill only the exact child owned by this test manager; it must recover.
    $oldId = $script:child.ProcessId
    $crashed = Get-Process -Id $oldId; $crashed.Kill(); $crashed.WaitForExit(); $crashed.Dispose()
    Wait-Listening
    if ($script:child.ProcessId -eq $oldId) { throw 'Expected replacement companion' }
    if ((Get-FileHash $identity).Hash -ne $identityHash) { throw 'Recovery changed pairing identity' }
    # Explicit Stop must defeat automatic recovery for longer than its retry cap.
    [RecoverySmokeUi]::Click([RecoverySmokeUi]::Find($script:managerProcess.Id,'Stop hosting'))
    Wait-Until { -not (Find-Child) } 'explicit Stop'
    Start-Sleep -Seconds 35
    if (Find-Child) { throw 'Explicit Stop resurrected companion' }
    [RecoverySmokeUi]::Click([RecoverySmokeUi]::Find($script:managerProcess.Id,'Start hosting'))
    Wait-Listening
    Stop-TestProcesses
    # Emulate the registered login command in a new process, with persisted state.
    $script:managerProcess = Start-Process $Manager -ArgumentList '--tray-only --silent' -PassThru
    Wait-Listening
    if ((Get-FileHash $identity).Hash -ne $identityHash) { throw 'Sign-in restart changed pairing identity' }
    if (Get-Process -Name vrserver,vrmonitor,vrcompositor -ErrorAction SilentlyContinue) { throw 'Host readiness unexpectedly started SteamVR' }
    Write-Host 'PASS: actual tray startup, registry command, crash recovery, Stop suppression, restart and pairing retention; no SteamVR launch'
} finally {
    try { Stop-TestProcesses } finally {
    Remove-ItemProperty -Path $runPath -Name $runName -ErrorAction SilentlyContinue
    # These directories were asserted absent before this disposable-runner test.
    Remove-Item $settingsDir,$pairingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
