using VibertemisManager.Core.GuidedSetup;
using VibertemisManager.Core.Platform.Windows;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.App;

public sealed partial class MainForm
{
    private GuidedSetupController? _guidedSetup;
    private GuidedSetupRunner? _guidedRunner;
    private readonly WindowsVcRuntimeDetector _runtimeDetector = new();
    private VcRedistInstaller? _redistInstaller;
    private bool _guidedSetupBusy;

    private void InitializeGuidedSetup()
    {
        var bridge = new WindowsBridgeAdapter(_svc.Paths.ProgramsRoot);
        _guidedSetup = new GuidedSetupController(_runtimeDetector, bridge, _svc.SettingsStore,
            new NoConnectivitySignal(), SafeCurrentUserSid, WindowsBridgeActiveSession.Read);
        _redistInstaller = new VcRedistInstaller(new WindowsVcRedistLauncher(ownerWindow: Handle), _runtimeDetector,
            _ => new WindowsVcRedistInspector());
        _guidedRunner = new GuidedSetupRunner(_guidedSetup.Detect, InstallRuntime, () => {
            if (string.IsNullOrEmpty(bridge.ResolveSunshinePath()))
                return new(false, "Install the VR-enabled Vibeshine host, then retry Setup VR.");
            var helper = Path.Combine(_svc.Paths.ProgramsRoot, "manager", "VibertemisNetworkHelper.exe");
            if (!_svc.IntegrityVerifier.Verify(helper, out _))
                return new(false, "Setup helper failed verification. Reinstall this package.");
            LogStatus("Approve the Windows prompt to enable pairing with Vibeshine.");
            var result = WindowsBridgeRegistration.Register(Environment.ProcessId, _svc.UacHelper);
            return new(result.Launched && result.Completed && result.ExitCode == 0,
                result.ExitCode == 0 ? "Host pairing enabled." : "Host pairing setup needs attention: " + result.Error);
        });
        _guidedSetup.Detect();
    }

    private static string? SafeCurrentUserSid()
    {
        try {
            if (System.Diagnostics.Process.GetCurrentProcess().SessionId != WindowsBridgeActiveSession.Read()) return null;
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            return identity.User?.Value;
        } catch { return null; }
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
            if (_guidedRunner is null) InitializeGuidedSetup();
            LogStatus("Checking Windows prerequisites and host pairing...");
            var result = await Task.Run(() => _guidedRunner!.Run());
            LogStatus(result.Message);
            if (!result.Succeeded) return false;
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
