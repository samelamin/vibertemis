using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.Core.GuidedSetup;

public sealed record SetupActionResult(bool Succeeded, string Message);

public sealed class GuidedSetupRunner
{
    private readonly Func<ReadinessReport> _detect;
    private readonly Func<SetupActionResult> _installRuntime;
    private readonly Func<SetupActionResult> _registerBridge;

    public GuidedSetupRunner(Func<ReadinessReport> detect, Func<SetupActionResult> installRuntime,
        Func<SetupActionResult> registerBridge)
        => (_detect, _installRuntime, _registerBridge) = (detect, installRuntime, registerBridge);

    public SetupActionResult Run()
    {
        var state = _detect();
        if (state.Runtime is null || VcRuntimeRequirements.Classify(state.Runtime,
                VcRuntimeRequirements.MinimumX64Runtime) != VcSatisfaction.Satisfied)
        {
            var install = _installRuntime();
            if (!install.Succeeded) return install;
            state = _detect();
            if (state.Runtime is null || VcRuntimeRequirements.Classify(state.Runtime,
                    VcRuntimeRequirements.MinimumX64Runtime) != VcSatisfaction.Satisfied)
                return new(false, "Windows runtime installation needs attention. Retry Setup VR.");
        }
        if (!state.Bridge.IsCurrentUserPresent)
        {
            var registration = _registerBridge();
            if (!registration.Succeeded) return registration;
            state = _detect();
        }
        return state.IsPcReady ? new(true, "PC prerequisites ready.")
            : new(false, state.NextAction ?? "Setup VR needs attention.");
    }
}
