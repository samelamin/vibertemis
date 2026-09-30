// VcRuntime detection tests.
//
// The Core detector parses the documented Microsoft
// Major/Minor/Bld/Rbld + Installed DWORDs via the IVcRegistryKey
// abstraction so tests can inject deterministic fixture values.
// Tests cover:
//   - missing key
//   - corrupt registry (Installed=1 but no Major/Minor)
//   - happy path (Installed=1 + Major/Minor/Bld/Rbld)
//   - new Installed=0
//   - partial versions (Major/Minor only)
//   - multi-view priority (Registry64 first, then WOW6432Node)
//   - Version parser handles the optional leading 'v', missing
//     trailing components, and rejects malformed inputs.
using VibertemisManager.Core.Prerequisites;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class VcRuntimeDetectorTests
{
    [Fact]
    public void Detect_ReportsMissing_WhenKeyAbsent()
    {
        var detector = new VcRuntimeDetector(new[]
        {
            new VcRegistryView("HKLM-64", new FakeVcRegistryKey()),
        });
        var status = detector.Detect();
        Assert.False(status.Installed);
        Assert.Equal(VcReadSource.Missing, status.Source);
    }

    [Fact]
    public void Detect_ReportsMissing_WhenInstalledZero()
    {
        var key = new FakeVcRegistryKey();
        key.Values[VcVersion.RegistryFieldInstalled] = 0;
        key.Values[VcVersion.RegistryFieldMajor] = 14;
        key.Values[VcVersion.RegistryFieldMinor] = 44;
        var status = new VcRuntimeDetector(new[] { new VcRegistryView("HKLM-64", key) }).Detect();
        Assert.False(status.Installed);
        Assert.Equal(VcReadSource.InstalledFlag, status.Source);
    }

    [Fact]
    public void Detect_ReportsCorrupt_WhenInstalledButMajorMissing()
    {
        var key = new FakeVcRegistryKey();
        key.Values[VcVersion.RegistryFieldInstalled] = 1;
        key.Values[VcVersion.RegistryFieldMinor] = 44;
        var status = new VcRuntimeDetector(new[] { new VcRegistryView("HKLM-64", key) }).Detect();
        Assert.False(status.Installed);
        Assert.Equal(VcReadSource.CorruptRegistry, status.Source);
    }

    [Fact]
    public void Detect_ReportsPresent_WhenInstalledAndVersionComplete()
    {
        var key = new FakeVcRegistryKey();
        key.Values[VcVersion.RegistryFieldInstalled] = 1;
        key.Values[VcVersion.RegistryFieldMajor] = 14;
        key.Values[VcVersion.RegistryFieldMinor] = 44;
        key.Values[VcVersion.RegistryFieldBld] = 35207;
        key.Values[VcVersion.RegistryFieldRbld] = 0;
        var status = new VcRuntimeDetector(new[] { new VcRegistryView("HKLM-64", key) }).Detect();
        Assert.True(status.Installed);
        Assert.Equal(new VcVersion(14, 44, 35207, 0), status.InstalledVersion);
    }

    [Fact]
    public void Detect_AcceptsStringifiedDwordValues()
    {
        // Some Microsoft redistributable installs write Major/Minor as
        // REG_SZ. The detector must accept those for backward compat.
        var key = new FakeVcRegistryKey();
        key.Values[VcVersion.RegistryFieldInstalled] = "1";
        key.Values[VcVersion.RegistryFieldMajor] = "14";
        key.Values[VcVersion.RegistryFieldMinor] = "44";
        key.Values[VcVersion.RegistryFieldBld] = "35207";
        var status = new VcRuntimeDetector(new[] { new VcRegistryView("HKLM-64", key) }).Detect();
        Assert.True(status.Installed);
        Assert.Equal(new VcVersion(14, 44, 35207, 0), status.InstalledVersion);
    }

    [Fact]
    public void Detect_PrefersRegistry64_OverWowNode()
    {
        var primary = new FakeVcRegistryKey();
        primary.Values[VcVersion.RegistryFieldInstalled] = 1;
        primary.Values[VcVersion.RegistryFieldMajor] = 14;
        primary.Values[VcVersion.RegistryFieldMinor] = 50;
        primary.Values[VcVersion.RegistryFieldBld] = 1;
        var wow = new FakeVcRegistryKey();
        wow.Values[VcVersion.RegistryFieldInstalled] = 1;
        wow.Values[VcVersion.RegistryFieldMajor] = 14;
        wow.Values[VcVersion.RegistryFieldMinor] = 44;
        wow.Values[VcVersion.RegistryFieldBld] = 35207;
        var status = new VcRuntimeDetector(new[]
        {
            new VcRegistryView("HKLM-64", primary),
            new VcRegistryView("HKLM-WOW6432Node", wow),
        }).Detect();
        Assert.True(status.Installed);
        Assert.Equal(new VcVersion(14, 50, 1, 0), status.InstalledVersion);
    }

    [Fact]
    public void Detect_FallsBackToWowNode_WhenRegistry64Missing()
    {
        var wow = new FakeVcRegistryKey();
        wow.Values[VcVersion.RegistryFieldInstalled] = 1;
        wow.Values[VcVersion.RegistryFieldMajor] = 14;
        wow.Values[VcVersion.RegistryFieldMinor] = 44;
        wow.Values[VcVersion.RegistryFieldBld] = 35207;
        var status = new VcRuntimeDetector(new[]
        {
            new VcRegistryView("HKLM-64", new FakeVcRegistryKey()),
            new VcRegistryView("HKLM-WOW6432Node", wow),
        }).Detect();
        Assert.True(status.Installed);
        Assert.Equal(new VcVersion(14, 44, 35207, 0), status.InstalledVersion);
    }

    [Fact]
    public void Detect_ReturnsLastError_WhenNeitherViewSatisfies()
    {
        var a = new FakeVcRegistryKey();
        a.Values[VcVersion.RegistryFieldInstalled] = 0;
        var b = new FakeVcRegistryKey();
        // Key absent in second view -> reported.
        var status = new VcRuntimeDetector(new[]
        {
            new VcRegistryView("first", a),
            new VcRegistryView("second", b),
        }).Detect();
        Assert.False(status.Installed);
        Assert.Equal(VcReadSource.Missing, status.Source);
    }

    [Theory]
    [InlineData("v14.44.35207", true, 14, 44, 35207, 0)]
    [InlineData("14.44.35207", true, 14, 44, 35207, 0)]
    [InlineData("14.44.35207.0", true, 14, 44, 35207, 0)]
    [InlineData("14.44.0", true, 14, 44, 0, 0)]
    [InlineData(" 14.44.35207 ", true, 14, 44, 35207, 0)]
    [InlineData("", false, 0, 0, 0, 0)]
    [InlineData(null, false, 0, 0, 0, 0)]
    [InlineData("14.44-preview", false, 0, 0, 0, 0)]
    [InlineData("14.44.35207.bad", false, 0, 0, 0, 0)]
    [InlineData("14", true, 14, 0, 0, 0)]
    [InlineData("V14.44.35207", false, 0, 0, 0, 0)] // Only lowercase 'v' trimmed.
    public void TryParse_HandlesVersions(string? raw, bool expected, int mj, int mn, int bld, int rbld)
    {
        var ok = VcVersion.TryParse(raw, out var v);
        Assert.Equal(expected, ok);
        if (ok) Assert.Equal(new VcVersion(mj, mn, bld, rbld), v);
    }

    [Fact]
    public void CompareTo_IsSemantic_NotComponentwise()
    {
        var older = new VcVersion(14, 44, 0, 0);
        var newer = new VcVersion(14, 44, 35207, 0);
        Assert.True(older.CompareTo(newer) < 0);
        Assert.True(newer.CompareTo(older) > 0);
        Assert.Equal(0, newer.CompareTo(new VcVersion(14, 44, 35207, 0)));
    }

    [Fact]
    public void Classify_ReturnsSatisfied_WhenAtOrAboveMinimum()
    {
        var v = new VcVersion(14, 44, 35207, 0);
        var status = VcRuntimeStatus.Present(v, "ok", VcReadSource.VersionFields);
        Assert.Equal(VcSatisfaction.Satisfied, VcRuntimeRequirements.Classify(status, VcRuntimeRequirements.MinimumX64Runtime));
    }

    [Fact]
    public void Classify_ReturnsSatisfied_WhenNewerThanMinimum()
    {
        var v = new VcVersion(14, 50, 1, 0);
        var status = VcRuntimeStatus.Present(v, "ok", VcReadSource.VersionFields);
        Assert.Equal(VcSatisfaction.Satisfied, VcRuntimeRequirements.Classify(status, VcRuntimeRequirements.MinimumX64Runtime));
    }

    [Fact]
    public void Classify_ReturnsTooOld_WhenOlderThanMinimum()
    {
        var v = new VcVersion(14, 40, 0, 0);
        var status = VcRuntimeStatus.Present(v, "ok", VcReadSource.VersionFields);
        Assert.Equal(VcSatisfaction.TooOld, VcRuntimeRequirements.Classify(status, VcRuntimeRequirements.MinimumX64Runtime));
    }

    [Fact]
    public void Classify_ReturnsCorrupt_WhenRegistryCorrupt()
    {
        var status = VcRuntimeStatus.NotInstalled("corrupt", VcReadSource.CorruptRegistry);
        Assert.Equal(VcSatisfaction.Corrupt, VcRuntimeRequirements.Classify(status, VcRuntimeRequirements.MinimumX64Runtime));
    }
}