using System.Text.Json.Nodes;
using VibertemisManager.Core.ALVR;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class VrSetupTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "vr-setup-" + Guid.NewGuid().ToString("N"));
    public VrSetupTests() => Directory.CreateDirectory(_dir);
    public void Dispose() => Directory.Delete(_dir, true);

    [Fact] public void InitialDefaultsAreNativeSafeAndExistingSettingsSurvive()
    {
        var path = Path.Combine(_dir, "runtime", "session.json");
        VrSetup.InitializeSession(path);
        var doc = JsonNode.Parse(File.ReadAllText(path))!;
        Assert.Equal("20.14.1-vibertemis-pyro.1", doc["server_version"]!.GetValue<string>());
        Assert.False(doc["session_settings"]!["connection"]!["client_discovery"]!["content"]!["auto_trust_clients"]!.GetValue<bool>());
        Assert.False(doc["session_settings"]!["extra"]!["steamvr_launcher"]!["open_close_steamvr_with_dashboard"]!.GetValue<bool>());
        Assert.Equal(200, doc["session_settings"]!["video"]!["bitrate"]!["mode"]!["Adaptive"]!["max_throughput_mbps"]!["content"]!.GetValue<int>());
        doc["owner_preference"] = "keep";
        File.WriteAllText(path, doc.ToJsonString());
        var original = File.ReadAllBytes(path);
        VrSetup.InitializeSession(path);
        Assert.Equal(original, File.ReadAllBytes(path));
    }

    [Fact] public void InvalidExistingSessionIsNeverReplaced()
    {
        var path = Path.Combine(_dir, "session.json");
        File.WriteAllText(path, "{\"server_version\":\"20.14.1\"}");
        var original = File.ReadAllBytes(path);
        Assert.Throws<InvalidDataException>(() => VrSetup.InitializeSession(path));
        Assert.Equal(original, File.ReadAllBytes(path));
    }

    [Fact] public void ReadinessRequiresMatchingRegisteredDriverAndSessionWithoutChangingFiles()
    {
        var paths = Path.Combine(_dir, "openvrpaths.vrpath");
        var runtime = Path.Combine(_dir, "runtime");
        var setup = new VrSetup(_dir, paths,
            () => throw new Exception("inspection must not prepare"),
            () => throw new Exception("inspection must not mutate"));
        Assert.False(setup.IsPrepared());
        File.WriteAllText(paths, new JsonObject { ["external_drivers"] = new JsonArray(runtime) }.ToJsonString());
        Assert.False(setup.IsPrepared());
        VrSetup.InitializeSession(Path.Combine(runtime, "session.json"));
        var original = File.ReadAllBytes(paths);
        Assert.True(setup.IsPrepared());
        Assert.Equal(original, File.ReadAllBytes(paths));
        File.WriteAllText(Path.Combine(runtime, "session.json"), "{\"server_version\":\"incompatible\"}");
        Assert.False(setup.IsPrepared());
        File.WriteAllText(paths, "invalid json");
        Assert.False(setup.IsPrepared());
    }

    [Fact] public void ReadinessRejectsAConflictingAlvrDriver()
    {
        var runtime = Path.Combine(_dir, "runtime");
        var conflicting = Path.Combine(_dir, "other-alvr");
        Directory.CreateDirectory(conflicting);
        File.WriteAllText(Path.Combine(conflicting, "driver.vrdrivermanifest"), "{\"name\":\"alvr_server\"}");
        var paths = Path.Combine(_dir, "openvrpaths.vrpath");
        File.WriteAllText(paths, new JsonObject { ["external_drivers"] = new JsonArray(runtime, conflicting) }.ToJsonString());
        VrSetup.InitializeSession(Path.Combine(runtime, "session.json"));
        Assert.False(new VrSetup(_dir, paths, () => {}, () => {}).IsPrepared());
    }

    [Fact] public void RegistrationIsIdempotentPreservesOtherDriversAndRequiresConflictConsent()
    {
        string Install(string name, string driverName) {
            var path = Path.Combine(_dir,name); Directory.CreateDirectory(path);
            File.WriteAllText(Path.Combine(path,"driver.vrdrivermanifest"), new JsonObject { ["name"] = driverName }.ToJsonString());
            return path;
        }
        var other = Install("unrelated", "other_driver");
        var old = Install("old-alvr", "alvr_server");
        var steam = Path.Combine(_dir,"SteamVR");
        Directory.CreateDirectory(Path.Combine(steam,"bin","win64"));
        File.WriteAllText(Path.Combine(steam,"bin","win64","vrpathreg.exe"), "test tool");
        var paths = Path.Combine(_dir,"openvrpaths.vrpath");
        File.WriteAllText(paths,new JsonObject { ["runtime"] = new JsonArray(steam), ["external_drivers"] = new JsonArray(other,old) }.ToJsonString());
        int calls=0;
        void Run(string tool,string operation,string path) {
            calls++;
            var doc=JsonNode.Parse(File.ReadAllText(paths))!.AsObject();
            var drivers=doc["external_drivers"]!.AsArray();
            if(operation=="adddriver") drivers.Add(path);
            else { var match=drivers.FirstOrDefault(n=>n!.GetValue<string>()==path); if(match!=null) drivers.Remove(match); }
            File.WriteAllText(paths,doc.ToJsonString());
        }
        var setup=new VrSetup(_dir,paths,()=>{},()=>{},Run);
        Assert.Equal(new[]{old},setup.ConflictingDrivers());
        Assert.Throws<InvalidOperationException>(()=>setup.Prepare(false));
        Assert.Equal(0,calls);
        setup.Prepare(true);
        Assert.Empty(setup.ConflictingDrivers());
        var after=JsonNode.Parse(File.ReadAllText(paths))!["external_drivers"]!.AsArray().Select(n=>n!.GetValue<string>()).ToArray();
        Assert.Contains(other,after);
        Assert.DoesNotContain(old,after);
        Assert.Equal(2,calls);
        setup.Prepare(false);
        Assert.Equal(2,calls);
        var blocked=new VrSetup(_dir,paths,()=>throw new InvalidOperationException("VR busy"),()=>{},Run);
        Assert.Throws<InvalidOperationException>(()=>blocked.Prepare(false));
        Assert.Equal(2,calls);
    }
}
