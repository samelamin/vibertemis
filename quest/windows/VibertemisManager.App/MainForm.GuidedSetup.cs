using VibertemisManager.Core.Platform.Windows;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private sealed record SetupActionResult(bool Succeeded, string Message, VcRedistExit Exit = VcRedistExit.Other);
    private readonly WindowsVcRuntimeDetector _runtimeDetector = new();
    private VcRedistInstaller? _redistInstaller;
    private bool _guidedSetupBusy;

    private void InitializeGuidedSetup()
    {
        _redistInstaller = new VcRedistInstaller(new WindowsVcRedistLauncher(ownerWindow: Handle), _runtimeDetector,
            _ => new WindowsVcRedistInspector());
    }

    private SetupActionResult InstallRuntime()
    {
        var file = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "prerequisites", "vc_redist.x64.exe");
        if (_redistInstaller is null || !_svc.IntegrityVerifier.TryGetHash(file, out var expected) || expected is null)
            return new(false, "Windows runtime package is missing. Reinstall this package.", VcRedistExit.PackageCorrupt);
        LogStatus("Installing the Windows runtime. Approve the Windows prompt; setup will continue when the installer finishes.");
        var result = _redistInstaller.EnsureInstalled(file, expected.Hex, expected.Size,
            VcRuntimeRequirements.MinimumX64Runtime, VcRuntimeRequirements.MinimumX64Runtime);
        return new(result.Exit is VcRedistExit.AlreadyInstalled or VcRedistExit.Success, result.Detail, result.Exit);
    }

    // What the owner must actually do next. A cancelled prompt, a
    // required restart and a still-running installer need different
    // actions, so "try again" would be a lie for all three.
    private static string GuidedSetupNextStep(VcRedistExit exit) => exit switch
    {
        VcRedistExit.Denied => "Approve the Windows prompt, then choose Set up VR again.",
        VcRedistExit.RestartRequired => "Restart Windows, then reopen this manager.",
        VcRedistExit.StillRunning => "Wait for the installer window to close, then choose Set up VR again.",
        _ => "Choose Set up VR again.",
    };

    private bool RuntimeReady()
    {
        try {
            return VcRuntimeRequirements.Classify(_runtimeDetector.Detect(),
                VcRuntimeRequirements.MinimumX64Runtime) == VcSatisfaction.Satisfied;
        } catch { return false; }
    }

    private async Task<bool> RunGuidedSetupAsync()
    {
        if (_guidedSetupBusy || _updateBusy || _installHandOffInFlight) return false;
        _guidedSetupBusy = true;
        try {
            if (_redistInstaller is null) InitializeGuidedSetup();
            LogStatus("Checking Windows prerequisites...");
            if (!RuntimeReady()) {
                var result = await Task.Run(InstallRuntime);
                LogStatus(result.Message);
                if (!result.Succeeded)
                {
                    // Every unsuccessful exit is a persistent failure with
                    // the installer's own detail, not a line that scrolls
                    // away in the collapsed activity log.
                    FailAction("Set up VR did not install the Windows runtime: " + result.Message + " "
                        + GuidedSetupNextStep(result.Exit));
                    return false;
                }
                if (!RuntimeReady())
                {
                    FailAction("Set up VR did not finish: the runtime installer reported success but Windows "
                        + "still does not report the required runtime. Restart Windows, then choose Set up VR again.");
                    return false;
                }
            }
            // Remove only the obsolete app-local runtime, after the system runtime
            // is verified. Other runtime files and user settings remain intact.
            var old = Path.Combine(_svc.Paths.ProgramsRoot, "runtime", "bin", "win64", "vcruntime140_1.dll");
            if (File.Exists(old)) {
                var version = System.Diagnostics.FileVersionInfo.GetVersionInfo(old);
                if (version.FileMajorPart == 14 && version.FileMinorPart == 29) File.Delete(old);
            }
            return true;
        } catch (Exception ex) { FailAction("Set up VR needs attention: " + ex.Message); return false; }
        finally { _guidedSetupBusy = false; }
    }
}
