# Exercise the actual installed GUI/child processes on the disposable CI runner.
#
# Verifies:
#   - Tray-only startup, second-instance wake, and the auto-hide-to-tray
#     behaviour.
#   - Setup VR button label announces the headset-pairing consent.
#   - Pair headset (primary path) enables persisted receiving and reveals
#     management controls; the request pane stays hidden until a LAN
#     /pairing/begin actually surfaces a pending request. Manual export
#     stays under Advanced.
#   - Seamless receiving flow: a disposable LAN client does
#     /pairing/begin with a fresh RSA-2048 key. The manager's
#     coordinator picks the request up and surfaces the inline panel
#     with the server-computed code. The displayed code matches, the
#     Approve button is enabled and non-overlapping with the code label,
#     a single click delivers the approved result back to the LAN
#     client, and a PNG screenshot of the manager window is captured
#     to installer/smoke-pairing.png for CI review.
#   - Persistence: Setup VR / Pair headset enables ReceivePairingRequest
#     persistently and the host-ready status line reflects it across
#     restarts.
#   - Recovery: killing the owned companion triggers a replacement, the
#     pairing identity is preserved, and explicit Stop suppresses
#     automatic recovery past the retry cap.
#   - Restart: the registered --tray-only --silent login command brings
#     the host back up with the same identity and never launches SteamVR.
param([Parameter(Mandatory=$true)][string]$Manager)
$Manager = [IO.Path]::GetFullPath($Manager)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:CI -ne 'true') { throw 'Recovery smoke is restricted to a disposable CI runner' }
Add-Type @'
using System;
using System.Text;
using System.Text.RegularExpressions;
using System.Runtime.InteropServices;
public static class RecoverySmokeUi {
 public delegate bool EnumProc(IntPtr h, IntPtr l);
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll", EntryPoint="SendMessageTimeoutW", CharSet=CharSet.Unicode)] public static extern IntPtr ReadText(IntPtr h, uint m, IntPtr w, StringBuilder text, uint flags, uint timeout, out IntPtr result);
 [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
 public static IntPtr Find(int pid, string label) {
  IntPtr found=IntPtr.Zero;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p!=(uint)pid) return true;
   EnumChildWindows(h,(c,x)=> { var s=new StringBuilder(512); IntPtr result; if(ReadText(c,0xD,(IntPtr)512,s,2,2000,out result)!=IntPtr.Zero && s.ToString()==label) found=c; return true; },IntPtr.Zero);
   return true;
  },IntPtr.Zero); return found;
 }
 // Same as Find but requires the matching control to be
 // currently visible (so a hidden panel does not satisfy a
 // post-Hide "title not found" assertion).
 public static IntPtr FindVisible(int pid, string label) {
  IntPtr found = IntPtr.Zero;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p!=(uint)pid) return true;
   EnumChildWindows(h,(c,x)=> { var s=new StringBuilder(512); IntPtr result; if(ReadText(c,0xD,(IntPtr)512,s,2,2000,out result)!=IntPtr.Zero && s.ToString()==label && IsWindowVisible(c)) found=c; return true; },IntPtr.Zero);
   return true;
  },IntPtr.Zero); return found;
 }
 // Pattern-based text search on visible controls only.
 public static IntPtr FindRegexVisible(int pid, string pattern) {
  var rx = new Regex(pattern);
  IntPtr found = IntPtr.Zero;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p!=(uint)pid) return true;
   EnumChildWindows(h,(c,x)=> { var s=new StringBuilder(512); IntPtr result; if(ReadText(c,0xD,(IntPtr)512,s,2,2000,out result)!=IntPtr.Zero && rx.IsMatch(s.ToString()) && IsWindowVisible(c)) found=c; return true; },IntPtr.Zero);
   return true;
  },IntPtr.Zero); return found;
 }
 public static string Read(IntPtr h) {
  var s = new StringBuilder(512); IntPtr result;
  if (ReadText(h, 0xD, (IntPtr)512, s, 2, 2000, out result) == IntPtr.Zero) return "";
  return s.ToString();
 }
 public static bool GetBounds(IntPtr h, out int left, out int top, out int right, out int bottom) {
  RECT r; if (!GetWindowRect(h, out r)) { left=top=right=bottom=0; return false; }
  left = r.Left; top = r.Top; right = r.Right; bottom = r.Bottom; return true;
 }
 public static bool Visible(int pid) {
  bool visible=false;
  EnumWindows((h,l)=> { uint p; GetWindowThreadProcessId(h,out p); if(p==(uint)pid && IsWindowVisible(h)) visible=true; return true; },IntPtr.Zero);
  return visible;
 }
 public static void Click(IntPtr h) { IntPtr result; if(h==IntPtr.Zero || SendMessageTimeout(h,0xF5,IntPtr.Zero,IntPtr.Zero,2,15000,out result)==IntPtr.Zero) throw new Exception("GUI control click failed"); }
}
'@
# System.Drawing.Common supplies Bitmap/Graphics/ImageFormat.
# Loading it explicitly avoids reliance on the implicit
# reference set inside the Add-Type compiler above (per
# https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/add-type).
Add-Type -AssemblyName System.Drawing.Common
function Capture-ManagerScreenshot {
    param([IntPtr]$Hwnd, [string]$Path)
    if ($Hwnd -eq [IntPtr]::Zero) { throw 'Capture-ManagerScreenshot: HWND is zero' }
    $left = $top = $right = $bottom = 0
    if (-not [RecoverySmokeUi]::GetBounds($Hwnd, [ref]$left, [ref]$top, [ref]$right, [ref]$bottom)) {
        throw "Capture-ManagerScreenshot: GetBounds failed for HWND $Hwnd"
    }
    $width = $right - $left
    $height = $bottom - $top
    if ($width -le 0 -or $height -le 0) { throw "Capture-ManagerScreenshot: window has zero area ($width x $height)" }
    $bmp = New-Object System.Drawing.Bitmap $width, $height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $hdc = $g.GetHdc()
            try {
                # PW_RENDERFULLCONTENT (0x2) renders DirectComposition
                # and child controls; without it the capture is blank
                # for layered WinForms windows.
                if (-not [RecoverySmokeUi]::PrintWindow($Hwnd, $hdc, 2)) {
                    throw "Capture-ManagerScreenshot: PrintWindow failed for HWND $Hwnd"
                }
            } finally { $g.ReleaseHdc($hdc) }
        } finally { $g.Dispose() }
        $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $bmp.Dispose() }
}
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
@{LastSelectedAdapterId=$adapter.Id;LastSelectedAdapterAddress=$adapter.Address;AutoStartWithWindows=$false;RestoreCompanionOnStartup=$true;CompanionListenPort=28540;SchemaVersion=1;ReceivePairingRequests=$false} |
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
# Compute HMAC over the canonical POST envelope so the loopback
# admin listener accepts the manager-signed body. The owner
# token is read from the companion's state.json.
function New-AdminAuth {
    param([string]$Token, [string]$Path, [byte[]]$BodyBytes)
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $nb = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($nb)
    $nc = -join ($nb | ForEach-Object { $_.ToString('x2') })
    $hash = [System.Security.Cryptography.SHA256]::HashData($BodyBytes)
    $hexHash = -join ($hash | ForEach-Object { $_.ToString('x2') })
    $canon = "POST`n$Path`n$ts`n$nc`n$hexHash"
    $mac = [System.Security.Cryptography.HMACSHA256]::HashData(
        [System.Text.Encoding]::UTF8.GetBytes($Token),
        [System.Text.Encoding]::UTF8.GetBytes($canon))
    $sig = -join ($mac | ForEach-Object { $_.ToString('x2') })
    return @{ 'X-Vq-Ts' = "$ts"; 'X-Vq-Nonce' = $nc; 'X-Vq-Sig' = $sig }
}
function Send-Admin {
    param([string]$Path, [object]$Payload, [switch]$AllowError)
    $statePath = Join-Path $pairingDir 'state.json'
    $state = Get-Content $statePath -Raw | ConvertFrom-Json
    $bodyJson = if ($null -ne $Payload) { $Payload | ConvertTo-Json -Depth 8 -Compress } else { '{}' }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)
    $headers = New-AdminAuth -Token $state.token -Path $Path -BodyBytes $bodyBytes
    Invoke-RestMethod -Uri ("https://127.0.0.1:28541$Path") -Method Post -Body $bodyBytes `
        -ContentType 'application/json' -Headers $headers -SkipCertificateCheck -SkipHttpErrorCheck:$AllowError
}
# Simulate a disposable LAN client doing /pairing/begin. The
# returned challenge plus the server-computed code (queried via
# /pairing/admin/pending) are used to drive the manager's inline
# approval panel through the same code path a real headset uses.
function New-LanBegin {
    param([string]$Address)
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    try {
        $spki = $rsa.ExportSubjectPublicKeyInfo()
        $keyB64 = [Convert]::ToBase64String($spki)
        $nonceBytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($nonceBytes)
        $nonce = -join ($nonceBytes | ForEach-Object { $_.ToString('x2') })
        $payload = @{ schema = 1; client_key = $keyB64; client_nonce = $nonce } | ConvertTo-Json -Compress
        $uri = "https://${Address}:28540/pairing/begin"
        $begin = Invoke-RestMethod -Uri $uri -Method Post -Body $payload -ContentType 'application/json' -SkipCertificateCheck
        return [PSCustomObject]@{ Key = $rsa; Nonce = $nonce; Challenge = $begin }
    } finally {
        # Key is held by the caller; do not dispose here.
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
    # Receiving-mode posture starts OFF on a clean CI image.
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Host ready: receiving pairing requests is OFF.') -ne [IntPtr]::Zero } 'initial receiving OFF label'
    # Setup VR button announces the consent explicitly so the
    # owner knows clicking enables headset pairing.
    $setupVr = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Setup VR (enables headset pairing)')
    if ($setupVr -eq [IntPtr]::Zero) { throw 'Setup VR button not labelled with headset pairing consent' }
    $pair = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Pair headset')
    if ($pair -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($pair)) { throw 'Pair headset is not visible by default' }
    $export = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Export pairing file')
    if ($export -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible($export)) { throw 'Manual export should stay under Advanced' }
    # The seamless inline approval panel must NOT be visible
    # before any LAN /pairing/begin. Reject any false positive.
    $earlyApprove = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Codes match — approve')
    if ($earlyApprove -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible($earlyApprove)) {
        throw 'Inline approval panel is visible before any LAN /pairing/begin'
    }
    # Advanced toggle must be present and actually parent the
    # secondary management controls. Expanding it must reveal
    # the adapter / dashboard / export controls that stay
    # hidden by default. Collapsing hides them again.
    $advancedToggle = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Advanced: network adapter, runtime dashboard and manual pairing')
    if ($advancedToggle -eq [IntPtr]::Zero) { throw 'Advanced expander header not present' }
    [RecoverySmokeUi]::Click($advancedToggle)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Refresh') -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible([RecoverySmokeUi]::Find($script:managerProcess.Id,'Refresh')) } 'adapter refresh button becomes visible when Advanced is expanded'
    $exportVisible = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Export pairing file')
    if ($exportVisible -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($exportVisible)) {
        throw 'Export pairing file not visible after Advanced expanded'
    }
    $dashVisible = [RecoverySmokeUi]::Find($script:managerProcess.Id,'Open ALVR Dashboard')
    if ($dashVisible -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($dashVisible)) {
        throw 'Open ALVR Dashboard not visible after Advanced expanded'
    }
    [RecoverySmokeUi]::Click($advancedToggle)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Export pairing file') -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible([RecoverySmokeUi]::Find($script:managerProcess.Id,'Export pairing file')) } 'export control hidden again after Advanced collapsed'
    # Primary Pair headset path: enables persisted receiving
    # immediately and reveals the inline panel.
    [RecoverySmokeUi]::Click($pair)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Host ready: receiving pairing requests is ON.') -ne [IntPtr]::Zero } 'receiving ON after Pair headset'
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Waiting for a Quest request. Put on your Quest, choose Setup VR and select this PC.') -ne [IntPtr]::Zero } 'ready for headset request'
    foreach ($label in @('Pause 1 hour','Turn off','Forget paired headsets…')) {
        $control = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,$label)
        if ($control -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowEnabled($control)) { throw "Management control unavailable: $label" }
    }
    foreach ($label in @('Codes match — approve','Reject','Hide')) {
        if ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id,$label) -ne [IntPtr]::Zero) { throw "Request control visible before a request: $label" }
    }
    if ([RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id,'\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\z') -ne [IntPtr]::Zero) { throw 'Comparison code visible before a request' }
    if (Get-Process -Name vrserver,vrmonitor,vrcompositor -ErrorAction SilentlyContinue) { throw 'Pairing unexpectedly started SteamVR' }
    # Production startup checkbox must register the exact installed executable.
    $readyLabel = 'Keep host ready after Windows sign-in'
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,$readyLabel) -ne [IntPtr]::Zero } 'automatic hosting control'
    [RecoverySmokeUi]::Click([RecoverySmokeUi]::Find($script:managerProcess.Id,$readyLabel))
    $command = (Get-ItemProperty -Path $runPath -Name $runName).$runName
    if ($command -ne "`"$Manager`" --tray-only --silent") { throw 'Login startup does not target installed manager' }

    # ---- Seamless receiving flow ----------------------------------------
    # A disposable LAN client does /pairing/begin with a fresh
    # RSA-2048 key. The manager's coordinator picks the request up
    # via /pairing/admin/pending and surfaces the inline approval
    # panel with the server-computed code. We verify the displayed
    # code matches, click Approve, and confirm the LAN client sees
    # state=approved on the next poll.
    $begin = New-LanBegin -Address $adapter.Address
    $sessionId = $begin.Challenge.session_id
    if (-not $sessionId) { throw 'LAN begin did not return session_id' }
    $serverNonce = $begin.Challenge.server_nonce
    $expectedCode = $null
    Wait-Until {
        $pending = Send-Admin '/pairing/admin/pending' $null
        if ($pending.state -eq 'pending' -and $pending.session_id -eq $sessionId) {
            $script:expectedCode = $pending.code
            return $true
        }
        return $false
    } 'manager picked up the LAN begin'
    $expectedCode = $script:expectedCode
    if (-not $expectedCode) { throw 'Server did not return a code for the LAN begin' }

    # The inline approval panel surfaces the same code the server
    # computed. FindRegexVisible matches the four hex / dash
    # pattern AND requires the matching control to be visible.
    $displayed = $null
    $codeHwnd = [IntPtr]::Zero
    Wait-Until {
        $h = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id,'\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\z')
        if ($h -ne [IntPtr]::Zero) { $script:codeHwnd = $h; $script:displayed = [RecoverySmokeUi]::Read($h); return $true }
        return $false
    } 'inline panel code visible'
    $displayed = $script:displayed
    $codeHwnd = $script:codeHwnd
    if ($displayed -ne $expectedCode) {
        throw "Inline panel code '$displayed' does not match server code '$expectedCode'"
    }

    # Regression guard: a real pending request must NOT be
    # rendered as expired by the inline panel. The expiry label
    # carries "Time remaining: " followed by a mm:ss countdown;
    # an immediate "expired" reading would catch the
    # Unix-seconds-as-tick bug where every pending was treated
    # as already past.
    $expiryHwnd = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'Time remaining: ')
    if ($expiryHwnd -eq [IntPtr]::Zero) {
        throw 'Expiry label not visible while a fresh pending is on screen'
    }
    $expiryText = [RecoverySmokeUi]::Read($expiryHwnd)
    if ($expiryText -match 'expired') {
        throw "Expiry label reads as expired for a fresh pending: '$expiryText'"
    }

    # Approve via the production UI. The button label is the
    # shared string from the inline panel.
    $approveBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Codes match — approve')
    if ($approveBtn -eq [IntPtr]::Zero) { throw 'Approve button not found' }
    if (-not [RecoverySmokeUi]::IsWindowEnabled($approveBtn)) { throw 'Approve button is disabled' }

    # Capture the actual layout while the code is visible so a
    # reviewer can spot-overlap regressions. Bounds must be
    # non-empty for both the code label and the Approve button,
    # and the two must not overlap (they are in separate rows
    # of the inline panel's TableLayoutPanel).
    $codeLeft = $codeTop = $codeRight = $codeBottom = 0
    $btnLeft = $btnTop = $btnRight = $btnBottom = 0
    if (-not [RecoverySmokeUi]::GetBounds($codeHwnd, [ref]$codeLeft, [ref]$codeTop, [ref]$codeRight, [ref]$codeBottom)) { throw 'Could not read code bounds' }
    if (-not [RecoverySmokeUi]::GetBounds($approveBtn, [ref]$btnLeft, [ref]$btnTop, [ref]$btnRight, [ref]$btnBottom)) { throw 'Could not read Approve bounds' }
    if ($codeRight -le $codeLeft -or $codeBottom -le $codeTop) { throw "Code label bounds empty: ($codeLeft,$codeTop)-($codeRight,$codeBottom)" }
    if ($btnRight -le $btnLeft -or $btnBottom -le $btnTop) { throw "Approve button bounds empty: ($btnLeft,$btnTop)-($btnRight,$btnBottom)" }
    $codeBottomAdj = $codeBottom
    if ($btnLeft -lt $codeRight -and $btnRight -gt $codeLeft -and $btnTop -lt $codeBottomAdj -and $btnBottom -gt $codeTop) {
        throw "Code label and Approve button overlap (code=$codeLeft,$codeTop,$codeRight,$codeBottom; button=$btnLeft,$btnTop,$btnRight,$btnBottom)"
    }

    # Capture a PNG screenshot of the manager window for CI review.
    $shotDir = Join-Path $PSScriptRoot '../../build/installer'
    if (-not (Test-Path $shotDir)) { New-Item -ItemType Directory -Path $shotDir | Out-Null }
    $shotPath = Join-Path $shotDir 'smoke-pairing.png'
    $rootHwnd = (Get-Process -Id $script:managerProcess.Id).MainWindowHandle
    if ($rootHwnd -eq [IntPtr]::Zero) { throw 'Manager MainWindowHandle is zero; cannot capture' }
    Capture-ManagerScreenshot -Hwnd $rootHwnd -Path $shotPath
    if (-not (Test-Path $shotPath)) { throw "Screenshot was not written to $shotPath" }
    $shotLen = (Get-Item $shotPath).Length
    if ($shotLen -lt 4096) { throw "Screenshot $shotPath is too small ($shotLen bytes)" }
    Write-Host "Saved screenshot to $shotPath ($shotLen bytes)"

    [RecoverySmokeUi]::Click($approveBtn)
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Pairing request from Quest') -eq [IntPtr]::Zero } 'inline panel closed after approve'

    # Verify the LAN client sees the approved state. The proof
    # signs the POLL action transcript with the disposable key
    # we generated for /pairing/begin.
    $state = Get-Content (Join-Path $pairingDir 'state.json') -Raw | ConvertFrom-Json
    $pin = $state.certpin
    $transcript = "VIBERTEMIS-STANDALONE-1-POLL`n1`n$sessionId`n$($begin.Nonce)`n$serverNonce`n$pin"
    $hash = [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($transcript))
    $sigBytes = $begin.Key.SignHash($hash, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pss)
    $poll = @{ schema = 1; session_id = $sessionId; signature = [Convert]::ToBase64String($sigBytes) } | ConvertTo-Json -Compress
    try {
        $pollResp = Invoke-RestMethod -Uri "https://$($adapter.Address):28540/pairing/poll" -Method Post -Body $poll -ContentType 'application/json' -SkipCertificateCheck
        if ($pollResp.state -ne 'approved') { throw "LAN poll after click did not see approved state; got $($pollResp.state)" }
    } finally {
        $begin.Key.Dispose()
    }

    # ---- Management controls stay reachable after pause/resume ----
    # After a successful approve the inline request pane is
    # closed, but the management row (Pause / Resume / Turn off
    # / Forget all) must remain on screen so the owner can
    # pause / resume without re-enabling the flow.
    $pauseBtn = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Pause 1 hour')
    if ($pauseBtn -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($pauseBtn)) {
        throw 'Pause 1 hour control missing after approve'
    }
    if (-not [RecoverySmokeUi]::IsWindowEnabled($pauseBtn)) {
        throw 'Pause 1 hour control is disabled after approve'
    }
    [RecoverySmokeUi]::Click($pauseBtn)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Host ready: receiving pairing requests is paused for the next hour.') -ne [IntPtr]::Zero } 'paused label'
    $resumeBtn = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Resume')
    if ($resumeBtn -eq [IntPtr]::Zero -or -not [RecoverySmokeUi]::IsWindowVisible($resumeBtn)) {
        throw 'Resume control missing while paused'
    }
    if (-not [RecoverySmokeUi]::IsWindowEnabled($resumeBtn)) {
        throw 'Resume control is disabled while paused'
    }
    [RecoverySmokeUi]::Click($resumeBtn)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Host ready: receiving pairing requests is ON.') -ne [IntPtr]::Zero } 'resumed label'

    # A rejected admin decision must preserve the pending request so the
    # owner can still make a valid decision through the visible UI.
    $begin2 = New-LanBegin -Address $adapter.Address
    $sessionId2 = $begin2.Challenge.session_id
    $serverNonce2 = $begin2.Challenge.server_nonce
    $expectedCode2 = $null
    Wait-Until {
        $pending = Send-Admin '/pairing/admin/pending' $null
        if ($pending.state -eq 'pending' -and $pending.session_id -eq $sessionId2) {
            $script:expectedCode2 = $pending.code; return $true
        }
        return $false
    } 'second LAN begin surfaced to manager'
    $expectedCode2 = $script:expectedCode2
    $approveBtn2 = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Codes match — approve')
    if ($approveBtn2 -eq [IntPtr]::Zero) { throw 'Approve button missing for retry test' }
    $tamperedCode = if ($expectedCode2 -eq '0000-1111-2222-3333') { 'FFFF-EEEE-DDDD-CCCC' } else { '0000-1111-2222-3333' }
    $resp = Send-Admin '/pairing/admin/decision' @{ session_id = $sessionId2; code = $tamperedCode; approve = $true } -AllowError
    if ($resp.code -ne 'INVALID') { throw 'Wrong comparison code was not rejected as INVALID' }
    $stillPending = Send-Admin '/pairing/admin/pending' $null
    if ($stillPending.session_id -ne $sessionId2 -or $stillPending.state -ne 'pending') { throw 'Invalid decision lost the pending request' }
    Wait-Until {
        $button = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Reject')
        $button -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowEnabled($button)
    } 'pending request remains actionable after invalid admin decision'
    [RecoverySmokeUi]::Click([RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Reject'))
    Wait-Until { (Send-Admin '/pairing/admin/pending' $null).state -eq 'denied' } 'valid UI rejection after invalid decision'
    $begin2.Key.Dispose()

    # Persistence: after a successful approve, the manager persists
    # ReceivePairingRequests=true. The next manager process must
    # observe the ON label without any user interaction.
    Stop-TestProcesses
    $script:managerProcess = Start-Process $Manager -ArgumentList '--tray-only --silent' -PassThru
    Wait-Listening
    $wake = Start-Process $Manager -PassThru
    if (-not $wake.WaitForExit(15000)) { throw 'Second instance did not signal existing manager' }
    $wake.Dispose()
    Wait-Until { [RecoverySmokeUi]::Visible($script:managerProcess.Id) } 'restart tray wake visibility'
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Host ready: receiving pairing requests is ON.') -ne [IntPtr]::Zero } 'restart receiving ON label'

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
    Write-Host 'PASS: actual tray startup, registry command, crash recovery, Stop suppression, restart and pairing retention; no SteamVR launch; seamless receiving inline panel surfaces LAN begin and Approve delivers approved state; receiving-mode preference persists across restarts; management controls (Pause / Resume) stay reachable after a successful approve'
} finally {
    try { Stop-TestProcesses } finally {
    Remove-ItemProperty -Path $runPath -Name $runName -ErrorAction SilentlyContinue
    # These directories were asserted absent before this disposable-runner test.
    Remove-Item $settingsDir,$pairingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}