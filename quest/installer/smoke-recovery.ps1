# Exercise the actual installed GUI/child processes on the disposable CI runner.
#
# Verifies:
#   - Tray-only startup, second-instance wake, and the auto-hide-to-tray
#     behaviour.
#   - Quiet readiness header reflects the receiving posture. The
#     primary row follows the real runner fixture, which is probed
#     here rather than assumed: this script only ever runs against a
#     disposable fresh install, so VR setup is still owed and "Set up
#     VR" is offered, while "Pair headset" is always reachable while
#     the host runs. Nothing assumes Steam is installed.
#   - Reachability, not just visibility: the "Advanced" and "Activity
#     log" disclosures and the controls inside them must keep a real
#     hit area inside the window's client area. IsWindowVisible alone
#     accepts a zero-size or half-clipped control, which is exactly how
#     both disclosures went missing in review. Every click below is
#     preceded by that check, so a click is always on a control a user
#     could actually press.
#   - "Advanced" starts collapsed and reveals Start / Stop, Refresh,
#     Open ALVR Dashboard, Export pairing, Pause 1 hour / Resume /
#     Turn off / Forget, Start with Windows, and the "Retry setup"
#     fallback. Its state is read from the real checkbox, so an
#     expansion is never a blind toggle.
#   - Seamless receiving flow: a disposable LAN client does
#     /pairing/begin with a fresh RSA-2048 key. The manager's
#     coordinator picks the request up and surfaces the request card
#     with the server-computed code. The displayed code matches, the
#     Approve button is enabled, reachable and non-overlapping with the
#     code label, a single click delivers the approved result back to
#     the LAN client, and PNG screenshots are captured of the default
#     ready state, a smaller window, expanded Advanced, and the pending
#     request.
#   - Persistence: Set up VR / Pair headset enables ReceivePairingRequest
#     persistently and the host-ready status line reflects it across
#     restarts.
#   - A prior update that did not finish stays visible in the update
#     footer with the activity log collapsed, survives a background
#     check and a restart, and does not stand in the way of the next
#     update action.
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
using System.Reflection;
using System.Runtime.InteropServices;
public static class RecoverySmokeUi {
 public delegate bool EnumProc(IntPtr h, IntPtr l);
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
 [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc p, IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll", EntryPoint="SendMessageTimeoutW", CharSet=CharSet.Unicode)] public static extern IntPtr ReadText(IntPtr h, uint m, IntPtr w, StringBuilder text, uint flags, uint timeout, out IntPtr result);
 [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr h, uint m, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
 [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr h);
 [DllImport("user32.dll", EntryPoint="GetClassNameW", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder name, int max);

 [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int ht, bool repaint);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  // The MSAA entry point the checkbox state is read through, declared
  // raw so the test needs no interop or UIAutomation assembly.
  // https://learn.microsoft.com/en-us/windows/win32/api/oleacc/nf-oleacc-accessibleobjectfromwindow
  [DllImport("oleacc.dll")] public static extern int AccessibleObjectFromWindow(IntPtr hwnd, uint objectId, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out object accessible);
  private const uint OBJID_CLIENT = 0xFFFFFFFC;
  private const int CHILDID_SELF = 0;
  private const int STATE_SYSTEM_CHECKED = 0x10;
  private const int STATE_SYSTEM_MIXED = 0x20;
  private static readonly Guid IID_IAccessible = new Guid("618736E0-3C3D-11CF-810C-00AA00389B71");
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
 // The part of a control that is actually on screen: its window rect
 // intersected with the client rect of every ancestor, in screen
 // coordinates. A control scrolled out of its AutoScroll host, parked
 // outside the window, or zero-sized collapses to nothing here, which
 // IsWindowVisible by itself does not detect.
 public static bool GetVisibleBounds(IntPtr h, out int left, out int top, out int right, out int bottom) {
  left=top=right=bottom=0;
  if (h==IntPtr.Zero) return false;
  RECT r; if (!GetWindowRect(h, out r)) return false;
  int x1=r.Left, y1=r.Top, x2=r.Right, y2=r.Bottom;
  var p = GetParent(h);
  while (p != IntPtr.Zero) {
   RECT cr; if (!GetClientRect(p, out cr)) return false;
   POINT o; o.X=cr.Left; o.Y=cr.Top;
   if (!ClientToScreen(p, ref o)) return false;
   int px1=o.X, py1=o.Y, px2=o.X+(cr.Right-cr.Left), py2=o.Y+(cr.Bottom-cr.Top);
   if (x1<px1) x1=px1; if (y1<py1) y1=py1; if (x2>px2) x2=px2; if (y2>py2) y2=py2;
   p = GetParent(p);
  }
  left=x1; top=y1; right=x2; bottom=y2;
  return true;
 }
 // A control is usable only when enough of it is on screen to press
 // and read. minWidth/minHeight are the smallest hit area that counts
 // as reachable; a one-pixel sliver does not.
 public static bool IsClickable(IntPtr h, int minWidth, int minHeight) {
  if (h==IntPtr.Zero || !IsWindowVisible(h) || !IsWindowEnabled(h)) return false;
  int l,t,r,b;
  if (!GetVisibleBounds(h, out l, out t, out r, out b)) return false;
  return (r-l) >= minWidth && (b-t) >= minHeight;
 }
  // Diagnostic for a partial-clipping failure: which ancestor's client
  // area actually cuts the control off. One line per level - the window
  // class, the window rectangle, and the client rectangle converted to
  // screen coordinates - for the control itself and at most 24
  // ancestors.
  //
  // Deliberately narrow: no window text (a caption can carry a path, a
  // key or anything else that is none of this test's business), no
  // child enumeration, no other input. Purely best effort - every call
  // is guarded and nothing here throws, so a diagnostic that cannot
  // read a window can never replace the real assertion failure it was
  // attached to.
  private static void AppendChainEntry(StringBuilder sb, int depth, IntPtr h) {
   var name = new StringBuilder(256);
   var cls = "?";
   try { if (GetClassName(h, name, name.Capacity) > 0) cls = name.ToString(); } catch { }
   cls = cls.TrimEnd('\0');
   if (cls.Length == 0) cls = "?";
   RECT w;
   if (!GetWindowRect(h, out w)) { sb.Append("  [").Append(depth).Append("] ").Append(cls).AppendLine(" window rect unavailable"); return; }
   string client;
   RECT c; POINT o; o.X = 0; o.Y = 0;
   try {
    if (GetClientRect(h, out c) && ClientToScreen(h, ref o))
     client = "(" + o.X + "," + o.Y + "," + (o.X + (c.Right - c.Left)) + "," + (o.Y + (c.Bottom - c.Top)) + ")";
    else client = "(unavailable)";
   } catch { client = "(unavailable)"; }
   sb.Append("  [").Append(depth).Append("] ").Append(cls)
    .Append(" win=(").Append(w.Left).Append(",").Append(w.Top).Append(",").Append(w.Right).Append(",").Append(w.Bottom).Append(")")
    .Append(" client=").Append(client).AppendLine();
  }
  public static string DescribeBoundsChain(IntPtr h) {
   var sb = new StringBuilder();
   try {
    if (h==IntPtr.Zero) return sb.Append("  (control handle is zero)").ToString();
    int depth = 0;
    IntPtr cur = h;
    while (cur != IntPtr.Zero && depth <= 24) {
     AppendChainEntry(sb, depth, cur);
     cur = GetParent(cur);
     depth++;
    }
   } catch { }
   return sb.ToString();
  }

  // The checkbox state as an assistive technology sees it. BM_GETCHECK
  // is documented as unsupported for owner-drawn check boxes
  // (https://learn.microsoft.com/windows/win32/controls/bm-getcheck) and
  // the default WinForms CheckBox is owner-drawn, so that message
  // answers 0 for both states and a checked, expanded disclosure still
  // reads as unchecked. IAccessible.accState carries the real state in
  // STATE_SYSTEM_CHECKED / STATE_SYSTEM_MIXED
  // (https://learn.microsoft.com/windows/win32/api/oleacc/nf-oleacc-iaccessible-get_accstate),
  // which WinForms answers from its own managed CheckState.
  // Returns 1 checked, 2 mixed, 0 unchecked. A state it cannot read is
  // never reported as unchecked: the call throws with the HRESULT or the
  // detail of the failure instead.
  public static int GetCheckState(IntPtr h) {
   if (h==IntPtr.Zero) throw new ArgumentException("Checkbox window handle is zero");
   object accessible;
   var iid = IID_IAccessible;
   var hr = AccessibleObjectFromWindow(h, OBJID_CLIENT, ref iid, out accessible);
   if (hr != 0 || accessible == null)
    throw new InvalidOperationException("AccessibleObjectFromWindow(OBJID_CLIENT, IID_IAccessible) failed for the checkbox: HRESULT 0x" + hr.ToString("X8") + (accessible == null ? ", no accessible object returned" : ""));
   try {
    var state = accessible.GetType().InvokeMember("accState", BindingFlags.GetProperty, null, accessible, new object[] { CHILDID_SELF });
    if (!(state is int)) throw new InvalidOperationException("IAccessible.accState(CHILDID_SELF) returned " + (state == null ? "null" : state.GetType().FullName) + " instead of a state mask");
    var flags = (int)state;
    if ((flags & STATE_SYSTEM_MIXED) != 0) return 2;
    if ((flags & STATE_SYSTEM_CHECKED) != 0) return 1;
    return 0;
   } finally {
    // Only the wrapper this call created is released; the caller keeps
    // everything else it owns.
    if (Marshal.IsComObject(accessible)) Marshal.ReleaseComObject(accessible);
   }
  }
 public static bool ResizeWindow(IntPtr h, int width, int height) {
  RECT r; if (!GetWindowRect(h, out r)) return false;
  return MoveWindow(h, r.Left, r.Top, width, height, true);
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
function Wait-Until([scriptblock]$Check, [string]$Label, [int]$Seconds=60, [int]$PollMilliseconds=300) {
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    do { if (& $Check) { return }; Start-Sleep -Milliseconds $PollMilliseconds } while ([DateTime]::UtcNow -lt $until)
    throw "Timed out: $Label"
}
# Window sizes used by this test. The app is free to pick its own
# default; the test states the geometry it needs so a click is always on
# a control a user could press, and so a smaller-than-default window is
# covered too.
$script:defaultWindowSize = @(760, 580)
$script:smallWindowSize = @(640, 560)
$script:roomyWindowSize = @(980, 840)
function Get-ManagerWindow {
    $h = (Get-Process -Id $script:managerProcess.Id).MainWindowHandle
    if ($h -eq [IntPtr]::Zero) { throw 'Manager MainWindowHandle is zero' }
    return $h
}
function Set-ManagerWindowSize([int[]]$Size) {
    if (-not [RecoverySmokeUi]::ResizeWindow((Get-ManagerWindow), $Size[0], $Size[1])) {
        throw "MoveWindow failed for $($Size[0])x$($Size[1])"
    }
    # Let the autosize layout, the scroll host and the wrapping text
    # settle before anything is measured or clicked.
    Start-Sleep -Milliseconds 500
}
function Get-VisibleRect([IntPtr]$Hwnd) {
    $l = $t = $r = $b = 0
    if (-not [RecoverySmokeUi]::GetVisibleBounds($Hwnd, [ref]$l, [ref]$t, [ref]$r, [ref]$b)) { return $null }
    if ($r -le $l -or $b -le $t) { return $null }
    return [PSCustomObject]@{ Left = $l; Top = $t; Right = $r; Bottom = $b }
}
function Get-WindowRect([IntPtr]$Hwnd) {
    $l = $t = $r = $b = 0
    if (-not [RecoverySmokeUi]::GetBounds($Hwnd, [ref]$l, [ref]$t, [ref]$r, [ref]$b)) { return $null }
    return [PSCustomObject]@{ Left = $l; Top = $t; Right = $r; Bottom = $b }
}
# Reachable means: on screen, inside every ancestor's client area, with a
# hit area big enough to press. IsWindowVisible alone passes a zero-size
# or fully clipped control, which is how a disclosure can be "present"
# and still be impossible to use.
function Assert-Reachable {
    param([IntPtr]$Hwnd, [string]$Label, [int]$MinWidth = 16, [int]$MinHeight = 10, [switch]$NotDisabled)
    if ($Hwnd -eq [IntPtr]::Zero) { throw "Control not found: $Label" }
    if (-not [RecoverySmokeUi]::IsWindowVisible($Hwnd)) { throw "Control is not visible: $Label" }
    $rect = Get-VisibleRect $Hwnd
    if ($null -eq $rect) { throw "Control has no visible area (zero size, or outside the client area): $Label" }
    if (($rect.Right - $rect.Left) -lt $MinWidth -or ($rect.Bottom - $rect.Top) -lt $MinHeight) {
        throw "Control is clipped to a sliver ($($rect.Right - $rect.Left)x$($rect.Bottom - $rect.Top), need ${MinWidth}x${MinHeight}): $Label"
    }
    if (-not $NotDisabled -and -not [RecoverySmokeUi]::IsWindowEnabled($Hwnd)) { throw "Control is disabled: $Label" }
}
# Nothing of the control may be cut off by any ancestor: the visible part
# has to be the whole window rect, give or take a couple of pixels of
# border rounding. A control that is half inside the client area is not a
# usable control, however much of it IsWindowVisible reports.
function Assert-FullyOnScreen {
    param([IntPtr]$Hwnd, [string]$Label, [int]$MinWidth = 16, [int]$MinHeight = 10, [int]$Tolerance = 2, [switch]$NotDisabled)
    Assert-Reachable -Hwnd $Hwnd -Label $Label -MinWidth $MinWidth -MinHeight $MinHeight -NotDisabled:$NotDisabled
    $full = Get-WindowRect $Hwnd
    $vis = Get-VisibleRect $Hwnd
    $cutLeft = $vis.Left - $full.Left
    $cutTop = $vis.Top - $full.Top
    $cutRight = $full.Right - $vis.Right
    $cutBottom = $full.Bottom - $vis.Bottom
    if ($cutLeft -gt $Tolerance -or $cutTop -gt $Tolerance -or $cutRight -gt $Tolerance -or $cutBottom -gt $Tolerance) {
        # The clip numbers alone do not say WHO clips it. The chain
        # names the class and the screen-space client rectangle of the
        # control and each of its ancestors, so a failure points at the
        # exact ancestor instead of needing another blind run. The
        # diagnostic is best effort: if it cannot be produced, the
        # clipping failure below is still reported on its own terms and
        # is never masked by it.
        $chain = ''
        try { $chain = [RecoverySmokeUi]::DescribeBoundsChain($Hwnd) }
        catch { $chain = "  (bounds chain unavailable: $($_.Exception.Message))`r`n" }
        throw "Control is partly outside the client area (left=$cutLeft top=$cutTop right=$cutRight bottom=$cutBottom px): $Label`r`nBounds chain (window class, window rect, client rect in screen coords):`r`n$chain"
    }
}
# A button a user can actually press, sized from its own label: a real
# hit target, not a sliver that happens to be non-zero.
function Assert-Actionable {
    param([string]$Label, [IntPtr]$Hwnd = [IntPtr]::Zero, [switch]$NotDisabled, [int]$MinHeight = 24, [string]$Describe = '')
    if ($Hwnd -eq [IntPtr]::Zero) { $Hwnd = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, $Label) }
    $minWidth = [Math]::Max(40, 20 + 5 * $Label.Length)
    $what = if ($Describe) { "$Describe [$Label]" } else { $Label }
    Assert-FullyOnScreen -Hwnd $Hwnd -Label "action: $what" -MinWidth $minWidth -MinHeight $MinHeight -NotDisabled:$NotDisabled
    return $Hwnd
}
function Assert-NotOverlapping {
    param([IntPtr]$First, [string]$FirstLabel, [IntPtr]$Second, [string]$SecondLabel)
    $a = Get-VisibleRect $First
    $b = Get-VisibleRect $Second
    if ($null -eq $a -or $null -eq $b) { throw "Cannot measure overlap: $FirstLabel / $SecondLabel" }
    if ($a.Left -lt $b.Right -and $b.Left -lt $a.Right -and $a.Top -lt $b.Bottom -and $b.Top -lt $a.Bottom) {
        throw "$FirstLabel and $SecondLabel overlap (first=$($a.Left),$($a.Top),$($a.Right),$($a.Bottom); second=$($b.Left),$($b.Top),$($b.Right),$($b.Bottom))"
    }
}
function Save-SmokeScreenshot([string]$Name) {
    if (-not (Test-Path $script:shotDir)) { New-Item -ItemType Directory -Path $script:shotDir -Force | Out-Null }
    $path = Join-Path $script:shotDir "smoke-$Name.png"
    if (Test-Path $path) { Remove-Item $path -Force }
    Capture-ManagerScreenshot -Hwnd (Get-ManagerWindow) -Path $path
    if (-not (Test-Path $path)) { throw "Screenshot $path was not written" }
    $len = (Get-Item $path).Length
    if ($len -lt 4096) { throw "Screenshot $path is too small ($len bytes)" }
    Write-Host "Saved $Name screenshot to $path ($len bytes)"
}
# Both disclosures must be usable, and must not sit on top of each
# other, in whatever window size is on screen. A disclosure row is a
# full-width strip, so a real one is wide and tall enough to hit; a
# partly clipped row is not.
function Assert-DisclosuresReachable([string]$Where) {
    $advanced = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Advanced')
    $log = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Activity log')
    Assert-FullyOnScreen -Hwnd $advanced -Label "Advanced disclosure ($Where)" -MinWidth 90 -MinHeight 24
    Assert-FullyOnScreen -Hwnd $log -Label "Activity log disclosure ($Where)" -MinWidth 90 -MinHeight 24
    Assert-NotOverlapping -First $advanced -FirstLabel 'Advanced disclosure' -Second $log -SecondLabel 'Activity log disclosure'
    return $advanced
}
# A state this test cannot read is never "unchecked": the accessibility
# query either returns a real state or it fails, and a failure is
# reported with the control it was asked about and the HRESULT or detail
# the query raised, so a broken query can never satisfy a wait or an
# assertion by looking like an empty box.
function Get-NativeCheckState {
    param([IntPtr]$Hwnd, [string]$Label)
    try {
        return [RecoverySmokeUi]::GetCheckState($Hwnd)
    } catch {
        throw "Checkbox state query failed for '$Label': $($_.Exception.Message)"
    }
}
# Expands or collapses Advanced from its REAL checkbox state, so a
# second call is a no-op instead of flipping a disclosure the test does
# not own. A state this test cannot read is an error, never a reason to
# click: a blind toggle is exactly what this avoids.
function Set-Advanced([bool]$Expanded) {
    $toggle = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Advanced')
    Assert-FullyOnScreen -Hwnd $toggle -Label 'Advanced disclosure' -MinWidth 90 -MinHeight 24
    $want = if ($Expanded) { 1 } else { 0 }
    $current = Get-NativeCheckState -Hwnd $toggle -Label 'Advanced disclosure'
    if ($current -ne $want) {
        [RecoverySmokeUi]::Click($toggle)
        Wait-Until { [RecoverySmokeUi]::GetCheckState($toggle) -eq $want } "Advanced checkbox state $want"
    }
    $seen = Get-NativeCheckState -Hwnd $toggle -Label 'Advanced disclosure'
    if ($seen -ne $want) { throw "Advanced checkbox did not reach state $want (native check state $seen)" }
}
$settingsDir = Join-Path $env:LOCALAPPDATA 'VibertemisVRHostManager'
$pairingDir = Join-Path $env:LOCALAPPDATA 'vibertemis/companion'
$script:shotDir = Join-Path $PSScriptRoot '../../build/installer'
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
# Seed a real prior install result in the manager's own outcome contract:
# a failed installer run for a version this build is not. The manager
# must keep saying so in the always-visible update footer while the
# activity log stays collapsed, must keep saying it after a background
# metadata check and after a restart, and must not let it stand in the
# way of the next update. The fixture is never a success.
$script:priorOutcomePath = Join-Path $settingsDir 'updates/last-update.json'
$script:priorOutcomeDetail = 'Installer exit 1603; the package was not changed.'
New-Item -ItemType Directory -Path (Split-Path -Parent $script:priorOutcomePath) -Force | Out-Null
$script:priorOutcomeJson = @{
    schema = 1
    expectedVersion = '99.0.0.0-not-a-real-release'
    previousVersion = '0.0.0.0'
    kind = 'InstallerFailed'
    installerExitCode = 1603
    installedFileVersion = $null
    verifyInstallExitCode = $null
    installerLogPath = ''
    detail = $script:priorOutcomeDetail
    timestamp = (Get-Date).ToUniversalTime().ToString('O')
} | ConvertTo-Json
# Written the way the update worker writes it: UTF-8 with no BOM, so the
# manager's own parser reads the fixture exactly as it reads a real one.
[IO.File]::WriteAllText($script:priorOutcomePath, $script:priorOutcomeJson, (New-Object Text.UTF8Encoding($false)))
$script:managerProcess = $null
$script:child = $null
function Find-Child {
    $items = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($script:managerProcess.Id) AND Name='vibertemis-host-companion.exe'")
    if ($items.Count -gt 1) { throw 'Duplicate companions for one manager' }
    if ($items.Count -eq 1) { return $items[0] }
    return $null
}
# The one setup input this test actually depends on. A disposable fresh
# install has never run Set up VR, so the bundled VR runtime has no
# initialized session yet: smoke-windows.ps1 calls this script right
# after a clean /VERYSILENT install and writes that session file itself
# only afterwards. The bundled session is also the exact input
# VrSetup.IsPrepared() requires, so asserting on it keeps this test
# agreeing with the app's real IsSetupNeeded decision instead of
# re-guessing which of Steam / SteamVR / the VC++ runtime happen to be
# present on the runner image. Nothing here assumes Steam is installed.
function Get-VrRuntimeSessionPath {
    # The manager runs from <install root>/manager, so the install root
    # is one level up, exactly as EnvironmentPathResolver resolves it.
    return Join-Path (Split-Path -Parent (Split-Path -Parent $Manager)) 'runtime/session.json'
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
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Not receiving pairing requests.') -ne [IntPtr]::Zero } 'initial receiving OFF label'
    # The primary row follows the real fixture. Set up VR is offered
    # while VR setup is still owed, and on a disposable fresh install
    # the bundled VR runtime has never been prepared, so it is always
    # owed here whatever the runner image happens to have installed.
    # The prerequisite is asserted rather than assumed.
    $vrSession = Get-VrRuntimeSessionPath
    if (Test-Path $vrSession) {
        throw "Fresh install prerequisite failed: $vrSession already exists, so VR has been prepared on this runner"
    }
    Write-Host "Fresh install: no initialized VR runtime session at $vrSession, so Set up VR must be offered"
    $setupVr = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Set up VR')
    if ($setupVr -eq [IntPtr]::Zero) {
        throw 'Set up VR is missing on a fresh install whose VR runtime has never been prepared'
    }
    # Pairing is the point of the host, so "Pair headset" must be
    # reachable whenever the host is running - including on a runner
    # where Steam is not installed yet. It is a secondary beside
    # "Set up VR" in that case, never hidden behind it.
    $pair = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Pair headset')
    if ($pair -eq [IntPtr]::Zero) {
        throw 'Pair headset is not visible on a running host (fresh install)'
    }
    # The request card must NOT be visible before any LAN
    # /pairing/begin. Reject any false positive.
    $earlyApprove = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Approve')
    if ($earlyApprove -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowVisible($earlyApprove)) {
        throw 'Request card is visible before any LAN /pairing/begin'
    }
    # Demoted controls (Export pairing, Pause / Resume / Turn off /
    # Forget) MUST be reachable only via Advanced. Asserting
    # visibility at root level guards against a regression that
    # surfaces them outside Advanced.
    foreach ($label in @('Export pairing file','Pause 1 hour','Resume','Turn off','Forget paired headsets…','Refresh','Open ALVR Dashboard','Stop','Retry setup')) {
        if ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id, $label) -ne [IntPtr]::Zero) {
            throw "Demoted control visible without Advanced: $label"
        }
    }
    # State the geometry this test measures, so a click is always on a
    # control a user could press. The app's own default is not assumed.
    if (-not (Test-Path $script:shotDir)) { New-Item -ItemType Directory -Path $script:shotDir | Out-Null }
    Set-ManagerWindowSize $script:defaultWindowSize
    # Capture the default-ready state for CI review BEFORE the first
    # assertion about that state can fail, so a default-state failure
    # still leaves a picture of what it failed on.
    Save-SmokeScreenshot 'ready'
    # A prior install that did not finish is the owner's most important
    # pending fact, and the activity log is collapsed, so it has to be on
    # the always-visible update footer - short and factual, pointing at
    # the log for the worker's own detail rather than dumping raw
    # installer paths into the window.
    $logToggle = [RecoverySmokeUi]::Find($script:managerProcess.Id, 'Activity log')
    Assert-FullyOnScreen -Hwnd $logToggle -Label 'Activity log disclosure' -MinWidth 90 -MinHeight 24
    if ((Get-NativeCheckState -Hwnd $logToggle -Label 'Activity log disclosure') -ne 0) { throw 'Activity log is not collapsed by default' }
    $priorNotice = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'The last update to \S+ did not finish\.')
    Assert-FullyOnScreen -Hwnd $priorNotice -Label 'prior failed update notice in the update footer' -MinWidth 120 -MinHeight 24
    Assert-FullyOnScreen -Hwnd ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Not receiving pairing requests.')) -Label 'readiness header' -MinWidth 60 -MinHeight 16
    $priorNoticeText = [RecoverySmokeUi]::Read($priorNotice)
    # Truthful and concise: it states the running version and where the
    # detail lives, and it makes no claim about the install being rolled
    # back or the previous build still being in place.
    if ($priorNoticeText -notlike '*This manager is running *') { throw "Prior update notice does not state the running version: $priorNoticeText" }
    if ($priorNoticeText -notlike '*See Activity log for details.*') { throw "Prior update notice does not point at the activity log: $priorNoticeText" }
    if ($priorNoticeText -like "*$($script:priorOutcomeDetail)*") { throw "Raw worker detail leaked into the update footer: $priorNoticeText" }
    if ($priorNoticeText -match '[A-Za-z]:\\') { throw "Installer path leaked into the update footer: $priorNoticeText" }
    if ($priorNoticeText -match '(?i)unchanged|rolled back|not kept') { throw "Prior update notice claims something the worker never reported: $priorNoticeText" }
    if ($priorNoticeText -notlike '*Update status:*') {
        throw "Prior update notice replaced the live update status instead of preceding it: $priorNoticeText"
    }
    Write-Host "Prior update notice is visible with the log collapsed: $priorNoticeText"
    # The detail the notice points at has to exist somewhere the owner
    # can open: the log body must become usable when its disclosure is
    # expanded from its real checkbox state, and hide again after.
    $script:logExpanded = Get-NativeCheckState -Hwnd $logToggle -Label 'Activity log disclosure'
    if ($script:logExpanded -eq 0) {
        [RecoverySmokeUi]::Click($logToggle)
        # Waited on the reported state, not on the click having landed: a
        # failed state query throws here rather than reading as an empty
        # box that happens to match the wait.
        Wait-Until { [RecoverySmokeUi]::GetCheckState($logToggle) -eq 1 } 'activity log expanded'
        $logBody = Get-NativeCheckState -Hwnd $logToggle -Label 'Activity log disclosure (expanded)'
        if ($logBody -eq 0) { throw "Activity log did not expand (native check state $logBody)" }
        [RecoverySmokeUi]::Click($logToggle)
        Wait-Until { [RecoverySmokeUi]::GetCheckState($logToggle) -eq 0 } 'activity log collapsed again'
    }
    # A background metadata check repaints the status line; it must not
    # retire a standing notice about an install that really did fail.
    Wait-Until { [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'Update status: (?!not yet checked)') -ne [IntPtr]::Zero } 'background update check started' 120
    $afterCheck = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'The last update to \S+ did not finish\.')
    Assert-FullyOnScreen -Hwnd $afterCheck -Label 'prior update notice after the background check' -MinWidth 120 -MinHeight 24
    if (-not (Test-Path $script:priorOutcomePath)) { throw 'The incomplete prior update outcome was discarded by a background check' }

    # Both disclosures must be pressable in the default state.
    [void](Assert-DisclosuresReachable 'default window')
    [void](Assert-Actionable 'Set up VR')
    [void](Assert-Actionable 'Pair headset')

    # A window smaller than the default must keep both disclosures
    # usable: this is where a fixed-height layout used to push the
    # disclosure row out of the client area.
    Set-ManagerWindowSize $script:smallWindowSize
    [void](Assert-DisclosuresReachable 'small window')
    Save-SmokeScreenshot 'small'
    Set-ManagerWindowSize $script:defaultWindowSize
    [void](Assert-DisclosuresReachable 'default window again')

    # Advanced starts collapsed. Expanding it reveals Start / Stop,
    # Refresh, Open ALVR Dashboard, Export pairing, Pause 1 hour /
    # Resume / Turn off / Forget, Start with Windows, and the "Retry
    # setup" fallback. Its real checkbox state drives the change, and
    # every control below is proven reachable before it is clicked.
    Set-ManagerWindowSize $script:roomyWindowSize
    Set-Advanced $true
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Refresh') -ne [IntPtr]::Zero } 'adapter refresh button becomes visible when Advanced is expanded'
    # "Retry setup" is deliberately not "Set up VR": the primary row
    # already owns that exact label on an incomplete runner, and two
    # controls with identical text make this search ambiguous.
    foreach ($label in @('Export pairing file','Open ALVR Dashboard','Pause 1 hour','Turn off','Forget paired headsets…','Retry setup')) {
        [void](Assert-Actionable $label -NotDisabled)
    }
    # The startup checkbox is a label-sized control, so it is measured as
    # one rather than as a button.
    [void](Assert-FullyOnScreen -Hwnd ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Start with Windows')) -Label 'Advanced row: Start with Windows' -MinWidth 90 -MinHeight 24 -NotDisabled)
    Save-SmokeScreenshot 'advanced'
    Set-Advanced $false
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Export pairing file') -eq [IntPtr]::Zero } 'export control hidden again after Advanced collapsed'
    if ((Get-NativeCheckState -Hwnd ([RecoverySmokeUi]::Find($script:managerProcess.Id, 'Advanced')) -Label 'Advanced disclosure') -ne 0) { throw 'Advanced checkbox is still checked after collapsing' }
    # Pair headset path: enables persisted receiving immediately and
    # reveals the request card. Reachable on a runner with or without
    # Steam, so this step never depends on the fixture.
    Set-ManagerWindowSize $script:defaultWindowSize
    [RecoverySmokeUi]::Click($pair)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Ready for headset pairing.') -ne [IntPtr]::Zero } 'receiving ON after Pair headset'
    # With no request on screen there is no request card; the owner
    # instruction for the ready posture lives on the readiness header.
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'On your Quest, choose Set up PC and select this PC.') -ne [IntPtr]::Zero } 'ready for headset request'
    foreach ($label in @('Approve','Reject','Hide')) {
        if ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id,$label) -ne [IntPtr]::Zero) { throw "Request control visible before a request: $label" }
    }
    if ([RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id,'\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\z') -ne [IntPtr]::Zero) { throw 'Comparison code visible before a request' }
    if (Get-Process -Name vrserver,vrmonitor,vrcompositor -ErrorAction SilentlyContinue) { throw 'Pairing unexpectedly started SteamVR' }
    # Management controls only reachable through Advanced; open
    # Advanced before clicking Pause 1 hour, and only when there is
    # enough room for the click to land on the real control.
    Set-ManagerWindowSize $script:roomyWindowSize
    Set-Advanced $true
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Pause 1 hour') -ne [IntPtr]::Zero } 'Pause 1 hour reachable via Advanced'
    foreach ($label in @('Pause 1 hour','Turn off','Forget paired headsets…')) {
        [void](Assert-Actionable $label)
    }
    # Production startup checkbox must register the exact installed executable.
    $readyLabel = 'Start with Windows'
    $readyLabelHwnd = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, $readyLabel)
    if ($readyLabelHwnd -eq [IntPtr]::Zero) { throw 'automatic hosting control not present in Advanced' }
    [RecoverySmokeUi]::Click($readyLabelHwnd)
    $command = (Get-ItemProperty -Path $runPath -Name $runName).$runName
    if ($command -ne "`"$Manager`" --tray-only --silent") { throw 'Login startup does not target installed manager' }
    Set-Advanced $false  # collapse Advanced again

    # ---- Seamless receiving flow ----------------------------------------
    # A disposable LAN client does /pairing/begin with a fresh
    # RSA-2048 key. The manager's coordinator picks the request up
    # via /pairing/admin/pending and surfaces the request card with the
    # server-computed code. We verify the displayed code matches, click
    # Approve, and confirm the LAN client sees state=approved on the next
    # poll.
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

    # The request card surfaces the same code the server computed.
    # FindRegexVisible matches the four hex / dash pattern AND requires
    # the matching control to be visible.
    $displayed = $null
    $codeHwnd = [IntPtr]::Zero
    # Advanced is collapsed, so the card owns the screen; the roomy
    # geometry keeps the whole card - code and decision row - inside the
    # client area instead of clipped below a disclosure.
    Set-ManagerWindowSize $script:roomyWindowSize
    Wait-Until {
        $h = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id,'\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\z')
        if ($h -ne [IntPtr]::Zero) { $script:codeHwnd = $h; $script:displayed = [RecoverySmokeUi]::Read($h); return $true }
        return $false
    } 'request card code visible'
    $displayed = $script:displayed
    $codeHwnd = $script:codeHwnd
    if ($displayed -ne $expectedCode) {
        throw "Request card code '$displayed' does not match server code '$expectedCode'"
    }

    # Regression guard: a real pending request must NOT be
    # rendered as expired by the request card. The expiry label
    # carries "Time remaining: " followed by a mm:ss countdown;
    # an immediate "expired" reading would catch the
    # Unix-seconds-as-tick bug where every pending was treated
    # as already past.
    $expiryHwnd = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'Time remaining: ')
    if ($expiryHwnd -eq [IntPtr]::Zero) {
        throw 'Expiry label not visible while a fresh pending is on screen'
    }
    Assert-FullyOnScreen -Hwnd $expiryHwnd -Label 'expiry countdown' -MinWidth 100 -MinHeight 16
    $expiryText = [RecoverySmokeUi]::Read($expiryHwnd)
    if ($expiryText -match 'expired') {
        throw "Expiry label reads as expired for a fresh pending: '$expiryText'"
    }

    # Approve via the production UI. The button label is the
    # shared string from the request card.
    $approveBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Approve')
    if ($approveBtn -eq [IntPtr]::Zero) { throw 'Approve button not found' }
    if (-not [RecoverySmokeUi]::IsWindowEnabled($approveBtn)) { throw 'Approve button is disabled' }
    # The code and the decision must both be genuinely pressable: a
    # pending request scrolled out of the viewport is not a usable
    # approval surface even though every control reports Visible. The
    # comparison code is the one thing that must be read in full, so its
    # whole text area has to be on screen.
    Assert-FullyOnScreen -Hwnd $codeHwnd -Label 'comparison code' -MinWidth 200 -MinHeight 24
    [void](Assert-Actionable 'Approve' -Hwnd $approveBtn)
    [void](Assert-Actionable 'Reject')
    [void](Assert-Actionable 'Hide')

    # Capture the actual layout while the code is visible so a
    # reviewer can spot-overlap regressions. Bounds must be
    # non-empty for both the code label and the Approve button,
    # and the two must not overlap (they are in separate rows
    # of the request card).
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
    # The card also owns the screen: unrelated setup rows step aside
    # while a request is waiting, and the disclosures stay reachable.
    foreach ($label in @('Set up VR','Pair headset')) {
        if ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id, $label) -ne [IntPtr]::Zero) {
            throw "Unrelated primary action still visible while a request is waiting: $label"
        }
    }

    # Capture a PNG screenshot of the manager window for CI review.
    Save-SmokeScreenshot 'pairing'

    [RecoverySmokeUi]::Click($approveBtn)
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id,'Pairing request from Quest') -eq [IntPtr]::Zero } 'request card closed after approve'

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
    # After a successful approve the request card is closed, but the
    # management row (Pause / Resume / Turn off / Forget all) must
    # remain reachable so the owner can pause / resume without
    # re-enabling the flow. It lives inside Advanced, so Advanced is
    # expanded from its real checkbox state and the control is proven
    # pressable before it is clicked.
    Set-ManagerWindowSize $script:roomyWindowSize
    Set-Advanced $true
    $pauseBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Pause 1 hour')
    [void](Assert-Actionable 'Pause 1 hour' -Hwnd $pauseBtn)
    [RecoverySmokeUi]::Click($pauseBtn)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Pairing paused for the next hour.') -ne [IntPtr]::Zero } 'paused label'
    $resumeBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Resume')
    [void](Assert-Actionable 'Resume' -Hwnd $resumeBtn)
    [RecoverySmokeUi]::Click($resumeBtn)
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Ready for headset pairing.') -ne [IntPtr]::Zero } 'resumed label'
    # Collapse again: the next request and its screenshot should show the
    # quiet default screen, not a left-open management drawer.
    Set-Advanced $false

    # Approved results remain retrievable for the challenge TTL, even after
    # pause/resume. Wait for natural expiry; Cancel would revoke the saved
    # credential and would not test retention across the following restart.
    # Poll slowly: this shares the loopback request budget with the manager.
    Wait-Until {
        $snapshot = Send-Admin '/pairing/admin/pending' $null
        if ($snapshot.devices -lt 1) { throw 'Approved device was lost before the next pairing' }
        return $snapshot.state -eq 'waiting'
    } 'approved result expires while paired device is retained' 210 3000

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
    if (-not $expectedCode2) { throw 'Server did not return a code for the second LAN begin' }
    # The pending read above only proves the companion recorded the
    # second session. The manager's coordinator then polls on its own
    # ~2 second timer before it surfaces the card, so the UI is not on
    # screen at this point however fast the backend answered. The code
    # and the decision row are therefore waited for together, in one
    # polling iteration, instead of being read once and raced against:
    # a card that is up but whose Approve is not yet enabled must not
    # satisfy the wait either, or the click below would race the same
    # way one step later.
    $script:codeHwnd2 = [IntPtr]::Zero
    $script:approveBtn2 = [IntPtr]::Zero
    Wait-Until {
        $code = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id,'\A[0-9A-F]{4}(-[0-9A-F]{4}){3}\z')
        if ($code -eq [IntPtr]::Zero) { return $false }
        if ([RecoverySmokeUi]::Read($code) -ne $expectedCode2) { return $false }
        $button = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Approve')
        if ($button -eq [IntPtr]::Zero) { return $false }
        if (-not [RecoverySmokeUi]::IsWindowEnabled($button)) { return $false }
        # Recorded only once every condition holds, so a handle from an
        # earlier, half-drawn card can never leak into the assertions.
        $script:codeHwnd2 = $code
        $script:approveBtn2 = $button
        return $true
    } 'second request card code and Approve visible'
    $codeHwnd2 = $script:codeHwnd2
    $approveBtn2 = $script:approveBtn2
    Assert-FullyOnScreen -Hwnd $codeHwnd2 -Label 'comparison code (retry test)' -MinWidth 200 -MinHeight 24
    [void](Assert-Actionable 'Approve' -Hwnd $approveBtn2 -Describe 'Approve for the retry test')
    $tamperedCode = if ($expectedCode2 -eq '0000-1111-2222-3333') { 'FFFF-EEEE-DDDD-CCCC' } else { '0000-1111-2222-3333' }
    $resp = Send-Admin '/pairing/admin/decision' @{ session_id = $sessionId2; code = $tamperedCode; approve = $true } -AllowError
    # Decide intentionally treats a mismatched code as a stale decision.
    if ($resp.code -ne 'EXPIRED') { throw 'Wrong comparison code was not rejected as EXPIRED' }
    $stillPending = Send-Admin '/pairing/admin/pending' $null
    if ($stillPending.session_id -ne $sessionId2 -or $stillPending.state -ne 'pending') { throw 'Invalid decision lost the pending request' }
    $rejectBtn = $null
    Wait-Until {
        $button = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Reject')
        if ($button -ne [IntPtr]::Zero -and [RecoverySmokeUi]::IsWindowEnabled($button)) { $script:rejectBtn = $button; return $true }
        return $false
    } 'pending request remains actionable after invalid admin decision'
    $rejectBtn = $script:rejectBtn
    [void](Assert-Actionable 'Reject' -Hwnd $rejectBtn)
    [RecoverySmokeUi]::Click($rejectBtn)
    Wait-Until {
        $afterReject = Send-Admin '/pairing/admin/pending' $null
        if ($afterReject.devices -ne $stillPending.devices) { throw 'Reject changed the paired device count' }
        # The receiving lease can clear a denied slot before the next read.
        return ($afterReject.state -eq 'denied' -and $afterReject.session_id -eq $sessionId2) -or $afterReject.state -eq 'waiting'
    } 'valid UI rejection after invalid decision'
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
    Wait-Until { [RecoverySmokeUi]::Find($script:managerProcess.Id,'Ready for headset pairing.') -ne [IntPtr]::Zero } 'restart receiving ON label'
    # A prior install that did not finish has to be reported again on
    # every launch until a real attempt replaces it, and a background
    # metadata check must not quietly retire it.
    Wait-Until { [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'Update status: (?!not yet checked)') -ne [IntPtr]::Zero } 'background update check completed after restart'
    $restartNotice = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, 'The last update to \S+ did not finish\.')
    Assert-FullyOnScreen -Hwnd $restartNotice -Label 'prior update notice after restart' -MinWidth 120 -MinHeight 24
    if (-not (Test-Path $script:priorOutcomePath)) { throw 'The incomplete prior update outcome was discarded' }
    # The standing notice must not take the next update away: the one
    # update action is present, enabled and pressable. There is no
    # separate "Check for updates" control any more - a click that
    # finds nothing to install checks for itself - so these are exactly
    # the at-rest labels of that single action.
    $updateAction = [RecoverySmokeUi]::FindRegexVisible($script:managerProcess.Id, '^(Update|Update to .+|Retry update.*)$')
    Assert-FullyOnScreen -Hwnd $updateAction -Label 'the single update action' -MinWidth 90 -MinHeight 24
    if ([RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Check for updates') -ne [IntPtr]::Zero) {
        throw 'A second "Check for updates" control is present beside the single update action'
    }

    # Kill only the exact child owned by this test manager; it must recover.
    $oldId = $script:child.ProcessId
    $crashed = Get-Process -Id $oldId; $crashed.Kill(); $crashed.WaitForExit(); $crashed.Dispose()
    Wait-Listening
    if ($script:child.ProcessId -eq $oldId) { throw 'Expected replacement companion' }
    if ((Get-FileHash $identity).Hash -ne $identityHash) { throw 'Recovery changed pairing identity' }
    # Explicit Stop must defeat automatic recovery for longer than its retry cap.
    # Stop / Start live in Advanced; open Advanced from its real checkbox
    # state and click only a control that is proven pressable.
    Set-ManagerWindowSize $script:roomyWindowSize
    Set-Advanced $true
    Wait-Until { [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Stop') -ne [IntPtr]::Zero } 'Stop visible in Advanced'
    $stopBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Stop')
    [void](Assert-Actionable 'Stop' -Hwnd $stopBtn)
    [RecoverySmokeUi]::Click($stopBtn)
    Wait-Until { -not (Find-Child) } 'explicit Stop'
    Start-Sleep -Seconds 35
    if (Find-Child) { throw 'Explicit Stop resurrected companion' }
    $startBtn = [RecoverySmokeUi]::FindVisible($script:managerProcess.Id, 'Start')
    [void](Assert-Actionable 'Start' -Hwnd $startBtn)
    [RecoverySmokeUi]::Click($startBtn)
    Wait-Listening
    Stop-TestProcesses
    # Emulate the registered login command in a new process, with persisted state.
    $script:managerProcess = Start-Process $Manager -ArgumentList '--tray-only --silent' -PassThru
    Wait-Listening
    if ((Get-FileHash $identity).Hash -ne $identityHash) { throw 'Sign-in restart changed pairing identity' }
    if (Get-Process -Name vrserver,vrmonitor,vrcompositor -ErrorAction SilentlyContinue) { throw 'Host readiness unexpectedly started SteamVR' }
    Write-Host 'PASS: actual tray startup, registry command, crash recovery, Stop suppression, restart and pairing retention; no SteamVR launch; the request card surfaces a LAN begin and Approve delivers the approved state; receiving-mode preference persists across restarts; management controls (Pause / Resume) stay reachable after a successful approve; an incomplete prior update stays visible in the footer across a background check and a restart; both disclosures stay pressable at the default and a smaller window size'
} catch {
    # A failing step must not take the window down before CI has a
    # picture of it, so capture here - while the teardown has not run
    # yet. The capture is best effort: if it fails, or the window is
    # already gone, the original failure is still what gets reported.
    # $_ is rebound inside the nested catch below, so the original
    # record is held first and rethrown explicitly.
    $originalSmokeFailure = $_
    try { Save-SmokeScreenshot 'failure' } catch { Write-Host "Failure screenshot unavailable: $($_.Exception.Message)" }
    Write-Host "Smoke step failed: $($originalSmokeFailure.Exception.Message)"
    throw $originalSmokeFailure
} finally {
    try { Stop-TestProcesses } finally {
    Remove-ItemProperty -Path $runPath -Name $runName -ErrorAction SilentlyContinue
    # These directories were asserted absent before this disposable-runner test.
    Remove-Item $settingsDir,$pairingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}