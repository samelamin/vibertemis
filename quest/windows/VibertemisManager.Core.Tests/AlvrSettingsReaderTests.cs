// ALVR settings reader tests.
//
// The reader must:
//   - Use the real ALVR schema:
//       session_settings.extra.steamvr_launcher.open_close_steamvr_with_dashboard
//     (verified against quest/host/internal/alvr/adapter.go:
//      validateSchema).
//   - Return BundledDefaults when the session.json is missing (the
//     ALVR dashboard UI produces the file on first run).
//   - Surface open_close=true as a warning, not a silent default.
//   - Never silently coerce malformed/wrong-type/missing-required
//     fields to safe false; instead produce MalformedSessionJson
//     with an actionable Detail string so the UI can show the
//     user what's wrong.
using VibertemisManager.Core.ALVR;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class AlvrSettingsReaderTests
{
    [Fact]
    public void TryRead_ReturnsBundledDefaults_WhenSessionMissing()
    {
        var fs = new FakeFileSystem();
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/nope/session.json", out var analysis));
        Assert.Equal(AlvrLauncherState.BundledDefaults, analysis!.State);
        Assert.False(analysis.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.None, analysis.Warning);
    }

    [Fact]
    public void TryRead_ReportsOpenCloseTrueWarning_RealSchema()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] =
            "{\"session_settings\":{\"extra\":{\"steamvr_launcher\":{\"open_close_steamvr_with_dashboard\":true}}}}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.Equal(AlvrLauncherState.ParsedOpenClose, analysis!.State);
        Assert.True(analysis.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.OpenCloseSteamvrWithDashboardTrue, analysis.Warning);
        Assert.True(analysis.HasConflict);
    }

    [Fact]
    public void TryRead_OpenCloseFalse_NoWarning()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] =
            "{\"session_settings\":{\"extra\":{\"steamvr_launcher\":{\"open_close_steamvr_with_dashboard\":false}}}}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.False(analysis!.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.None, analysis.Warning);
    }

    [Fact]
    public void TryRead_MissingNestedFields_NoWarning()
    {
        // Real ALVR ships session_settings.extra.steamvr_launcher
        // already, but a freshly imported older config might not
        // have it yet; the absence is NOT an error and defaults
        // to safe (false).
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] = "{\"session_settings\":{}}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.False(analysis!.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.None, analysis.Warning);
    }

    [Fact]
    public void TryRead_RejectsFakeExtraRoot_NotAtSessionSettingsPath()
    {
        // The OLD (wrong) reader used root.extra.steamvr_launcher.*;
        // verify we no longer read that location.
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] =
            "{\"extra\":{\"steamvr_launcher\":{\"open_close_steamvr_with_dashboard\":true}},\"session_settings\":{}}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.False(analysis!.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.None, analysis.Warning);
    }

    [Fact]
    public void TryRead_RejectsWrongTypeField_ActionableError()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] =
            "{\"session_settings\":{\"extra\":{\"steamvr_launcher\":{\"open_close_steamvr_with_dashboard\":\"yes\"}}}}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.False(analysis!.OpenCloseSteamvrWithDashboard);
        Assert.Equal(AlvrLauncherWarning.MalformedSessionJson, analysis.Warning);
        Assert.NotNull(analysis.Detail);
        Assert.Contains("Expected boolean", analysis.Detail);
    }

    [Fact]
    public void TryRead_RejectsNonObjectSessionSettings_ActionableError()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] =
            "{\"session_settings\":42}";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.Equal(AlvrLauncherWarning.MalformedSessionJson, analysis.Warning);
        Assert.NotNull(analysis.Detail);
        Assert.Contains("session_settings", analysis.Detail);
    }

    [Fact]
    public void TryRead_InvalidJson_ReturnsMalformedWithDetail()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] = "this is not json";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.Equal(AlvrLauncherWarning.MalformedSessionJson, analysis.Warning);
        Assert.NotNull(analysis.Detail);
        Assert.Contains("not valid JSON", analysis.Detail);
    }

    [Fact]
    public void TryRead_RootNotObject_ReturnsMalformedWithDetail()
    {
        var fs = new FakeFileSystem();
        fs.Files["/tmp/session.json"] = "[]";
        var reader = new JsonAlvrSettingsReader(fs);
        Assert.True(reader.TryRead("/tmp/session.json", out var analysis));
        Assert.Equal(AlvrLauncherWarning.MalformedSessionJson, analysis.Warning);
        Assert.NotNull(analysis.Detail);
        Assert.Contains("object", analysis.Detail);
    }
}
