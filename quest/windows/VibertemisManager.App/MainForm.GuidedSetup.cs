using VibertemisManager.Core.Platform.Windows;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private sealed record SetupActionResult(bool Succeeded, string Message);
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
            return new(false, "Windows runtime package is missing. Reinstall this package.");
        LogStatus("Installing the Windows runtime. Approve the Windows prompt; setup will continue when the installer finishes.");
        var result = _redistInstaller.EnsureInstalled(file, expected.Hex, expected.Size,
            VcRuntimeRequirements.MinimumX64Runtime, VcRuntimeRequirements.MinimumX64Runtime);
        return new(result.Exit is VcRedistExit.AlreadyInstalled or VcRedistExit.Success, result.Detail);
    }

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
                if (!result.Succeeded || !RuntimeReady()) return false;
            }
            // Remove only the obsolete app-local runtime, after the system runtime
            // is verified. Other runtime files and user settings remain intact.
            var old = Path.Combine(_svc.Paths.ProgramsRoot, "runtime", "bin", "win64", "vcruntime140_1.dll");
            if (File.Exists(old)) {
                var version = System.Diagnostics.FileVersionInfo.GetVersionInfo(old);
                if (version.FileMajorPart == 14 && version.FileMinorPart == 29) File.Delete(old);
            }
            return true;
        } catch (Exception ex) { LogStatus("Setup VR needs attention: " + ex.Message); return false; }
        finally { _guidedSetupBusy = false; }
    }
}
