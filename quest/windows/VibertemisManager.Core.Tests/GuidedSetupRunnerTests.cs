using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.GuidedSetup;
using VibertemisManager.Core.Prerequisites;
using Xunit;
namespace VibertemisManager.Core.Tests;
public class GuidedSetupRunnerTests
{
    private static ReadinessReport Report(bool runtime, bool bridge)
    {
        var vc = runtime ? VcRuntimeStatus.Present(new VcVersion(14,44,35207,0), "ok", VcReadSource.VersionFields) : null;
        return new(runtime && bridge ? GuidedSetupState.PcReady : GuidedSetupState.ActionNeeded,
            Array.Empty<PrerequisiteSnapshot>(), false, "checking", "Retry Setup VR", vc,
            new(bridge ? BridgeSnapshotOutcome.Present : BridgeSnapshotOutcome.Absent,"bridge",
                new(bridge ? BridgeReadOutcome.Present : BridgeReadOutcome.Absent,null,null,null,"bridge")));
    }
    [Fact] public void AlreadyReadyDoesNotRequireConnectedHeadset()
    {
        var runner = new GuidedSetupRunner(() => Report(true,true),
            () => throw new Exception("Unexpected installer"), () => throw new Exception("Unexpected elevation"));
        Assert.True(runner.Run().Succeeded);
    }
    [Fact] public void InstallsThenRegistersAndRechecks()
    {
        bool runtime=false,bridge=false;
        var calls=new List<string>();
        var runner=new GuidedSetupRunner(()=>Report(runtime,bridge),
            ()=>{calls.Add("runtime");runtime=true;return new(true,"ok");},
            ()=>{Assert.True(runtime);calls.Add("bridge");bridge=true;return new(true,"ok");});
        Assert.True(runner.Run().Succeeded);
        Assert.True(runner.Run().Succeeded);
        Assert.Equal(new[]{"runtime","bridge"},calls);
    }
    [Fact] public void DenialStopsWithoutFalseReadinessAndCanBeRetried()
    {
        bool allowed=false,runtime=false,bridge=false;
        var runner=new GuidedSetupRunner(()=>Report(runtime,bridge),
            ()=>{runtime=allowed;return new(allowed,"Approve Windows prompt");},
            ()=>{bridge=true;return new(true,"ok");});
        Assert.False(runner.Run().Succeeded);Assert.False(bridge);
        allowed=true;Assert.True(runner.Run().Succeeded);
    }
    [Fact] public void SuccessfulExitWithoutRuntimeDoesNotContinue()
    {
        var runner=new GuidedSetupRunner(()=>Report(false,false),()=>new(true,"exit 0"),
            ()=>throw new Exception("Must recheck actual runtime"));
        Assert.False(runner.Run().Succeeded);
    }
}
