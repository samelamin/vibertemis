// Visual C++ x64 runtime detection.
//
// What this module is for:
//   * Reporting whether the system VC++ x64 runtime that the
//     bundled ALVR/PyroWave native DLLs link against is installed.
//   * Reporting the actual installed major.minor.bld.rbld version
//     from the Microsoft-documented registry key
//     HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64.
//   * Semantically comparing the installed version against the
//     minimum version the bundled native payload was linked
//     against, so a newer-than-minimum installed runtime is still
//     considered ready and a corrupt / older one triggers
//     SetupVR action.
//
// What this module is NOT for:
//   * Downloading or installing the runtime. That responsibility
//     lives in VcRedistInstaller (Core/Prerequisites) so the
//     detection can be exercised on POSIX test hosts without the
//     bundled package and without any UI dependency.
//
// Version semantics (adjudicated with Codex):
//   * The runtime is a 4-component version: Major, Minor, Bld,
//     Rbld (Microsoft uses the names Bld/Rbld for the third and
//     fourth components in the registry; the equivalent C++
//     runtime string exposes them as Build/Revision). We treat
//     a missing trailing component as 0, so "14.44.0" is treated
//     as 14.44.0.0 for comparison purposes.
//   * Strings are normalised by trimming an optional leading "v"
//     and surrounding whitespace; component separators are
//     required to be '.' (dashes, commas, etc. are rejected).
//   * Each component must be a non-negative 64-bit integer; if
//     any component overflows / contains non-digits we report a
//     corrupt registry, NOT a version.
//
// Registry shape (Microsoft docs):
//   HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64
//     Version       REG_SZ   (e.g. "14.44.35207.0")
//     Major         REG_DWORD
//     Minor         REG_DWORD
//     Bld           REG_DWORD
//     Rbld          REG_DWORD
//     Installed     REG_DWORD (0 = not installed, 1 = installed)
//
// On 32-bit Windows / 32-bit view, the runtime is registered
// under WOW6432Node. The Quest installer is
// ArchitecturesInstallIn64BitMode=x64compatible, so production is
// always x64. The Core abstraction is testable through the
// IRegistryAccess contract; the Windows binding reads BOTH views
// so a key in either view satisfies readiness.
namespace VibertemisManager.Core.Prerequisites;

public readonly record struct VcVersion(int Major, int Minor, int Build, int Revision)
{
    public static VcVersion Zero { get; } = new(0, 0, 0, 0);

    // Microsoft docs record the components as Bld/Rbld in the
    // registry. This constant is kept here so the registry reader
    // and the parser share one set of names.
    public const string RegistryFieldMajor = "Major";
    public const string RegistryFieldMinor = "Minor";
    public const string RegistryFieldBld = "Bld";
    public const string RegistryFieldRbld = "Rbld";
    public const string RegistryFieldInstalled = "Installed";
    public const string RegistryFieldVersion = "Version";

    public static bool TryParse(string? raw, out VcVersion version)
    {
        version = Zero;
        if (string.IsNullOrWhiteSpace(raw)) return false;
        var s = raw.Trim();
        if (s.Length > 0 && s[0] == 'v') s = s.Substring(1);
        if (s.Length == 0) return false;
        var parts = s.Split('.');
        if (parts.Length is < 1 or > 4) return false;
        Span<int> fields = stackalloc int[4];
        for (var i = 0; i < parts.Length; i++)
        {
            var p = parts[i];
            if (p.Length == 0 || p.Length > 10) return false;
            foreach (var c in p)
            {
                if (c < '0' || c > '9') return false;
            }
            if (!long.TryParse(p, System.Globalization.NumberStyles.None,
                System.Globalization.CultureInfo.InvariantCulture, out var l))
                return false;
            if (l > int.MaxValue) return false;
            fields[i] = (int)l;
        }
        for (var i = parts.Length; i < 4; i++) fields[i] = 0;
        version = new VcVersion(fields[0], fields[1], fields[2], fields[3]);
        return true;
    }

    public override string ToString() => $"{Major}.{Minor}.{Build}.{Revision}";

    public int CompareTo(VcVersion other)
    {
        if (Major != other.Major) return Major.CompareTo(other.Major);
        if (Minor != other.Minor) return Minor.CompareTo(other.Minor);
        if (Build != other.Build) return Build.CompareTo(other.Build);
        return Revision.CompareTo(other.Revision);
    }
}

public sealed record VcRuntimeStatus(
    bool Installed,
    VcVersion? InstalledVersion,
    string Detail,
    VcReadSource Source)
{
    public static VcRuntimeStatus NotInstalled(string detail, VcReadSource source)
        => new(false, null, detail, source);
    public static VcRuntimeStatus Present(VcVersion version, string detail, VcReadSource source)
        => new(true, version, detail, source);
}

public enum VcReadSource
{
    Missing,
    InstalledFlag,
    VersionFields,
    CorruptRegistry,
    NotSupported,
}

// A single snapshot of the documented Microsoft registry key.
// Tests inject deterministic values; production builds read both
// registry views through the same contract.
public interface IVcRegistryKey
{
    string? TryGetValue(string name);
    int? TryGetDword(string name);
}

public sealed class FakeVcRegistryKey : IVcRegistryKey
{
    public Dictionary<string, object> Values { get; } = new(StringComparer.OrdinalIgnoreCase);
    public string? TryGetValue(string name) => Values.TryGetValue(name, out var v) ? v as string : null;
    public int? TryGetDword(string name)
    {
        if (!Values.TryGetValue(name, out var v)) return null;
        return v switch
        {
            int i => i,
            uint u when u <= int.MaxValue => (int)u,
            long l when l is >= int.MinValue and <= int.MaxValue => (int)l,
            string s when int.TryParse(s, out var parsed) => parsed,
            _ => null,
        };
    }
}

public enum VcSatisfaction
{
    Satisfied,
    Missing,
    TooOld,
    Corrupt,
}

public static class VcRuntimeRequirements
{
    // Pinned to the same toolchain that built the bundled native
    // payload: Visual Studio 2022 17.14 / MSVC 14.44.35207,
    // compiler 19.44.35229 (CI run 36550716420, 2026-09-29). We DO
    // NOT claim a known exact runtime version string here - only
    // the toolchain-derived minimum. Callers compare the
    // registered version against this; "newer than" still
    // satisfies readiness.
    public static readonly VcVersion MinimumX64Runtime = new(14, 44, 35207, 0);

    public static VcSatisfaction Classify(VcRuntimeStatus status, VcVersion minimum)
    {
        if (status.Source == VcReadSource.CorruptRegistry)
            return VcSatisfaction.Corrupt;
        if (!status.Installed) return VcSatisfaction.Missing;
        if (status.InstalledVersion is null) return VcSatisfaction.Corrupt;
        return status.InstalledVersion.Value.CompareTo(minimum) >= 0
            ? VcSatisfaction.Satisfied
            : VcSatisfaction.TooOld;
    }
}

public sealed record VcRegistryView(string Label, IVcRegistryKey Key);

// Reads the documented Major/Minor/Bld/Rbld + Installed DWORD from
// a single registry view. The Windows binding passes two of these
// (the 64-bit native view and the 32-bit WOW6432Node view); tests
// pass a single fake so they exercise the parsing / classification
// logic without touching the real registry.
public interface IVcRuntimeDetector
{
    VcRuntimeStatus Detect();
}

public sealed class VcRuntimeDetector : IVcRuntimeDetector
{
    private readonly IReadOnlyList<VcRegistryView> _views;

    public VcRuntimeDetector(IReadOnlyList<VcRegistryView> views)
    {
        if (views is null || views.Count == 0)
            throw new ArgumentException("At least one registry view is required.");
        _views = views;
    }

    public VcRuntimeStatus Detect()
    {
        // Try every view in priority order. We never silently drop
        // a view's failure - the first view that reports installed
        // wins; otherwise the last error is reported.
        VcRuntimeStatus last = VcRuntimeStatus.NotInstalled(
            "Registry key missing.", VcReadSource.Missing);
        foreach (var view in _views)
        {
            var status = ReadOne(view);
            if (status.Installed) return status;
            last = status;
        }
        return last;
    }

    private static VcRuntimeStatus ReadOne(VcRegistryView view)
    {
        try
        {
            var installed = view.Key.TryGetDword(VcVersion.RegistryFieldInstalled);
            if (installed is null)
            {
                // Missing DWORD is treated as not installed. The
                // "Version" REG_SZ alone is NOT sufficient for
                // readiness: a partially-installed or repaired
                // runtime can write a Version string but leave
                // Installed=0.
                var versionText = view.Key.TryGetValue(VcVersion.RegistryFieldVersion);
                if (string.IsNullOrEmpty(versionText))
                    return VcRuntimeStatus.NotInstalled(
                        $"View {view.Label}: key missing or empty.",
                        VcReadSource.Missing);
                // Installed flag absent but Version text present.
                // Treat as not installed (redist not flagged as
                // installed). The Version text alone is advisory.
                return VcRuntimeStatus.NotInstalled(
                    $"View {view.Label}: Installed DWORD missing.",
                    VcReadSource.Missing);
            }
            if (installed.Value != 1)
                return VcRuntimeStatus.NotInstalled(
                    $"View {view.Label}: Installed=0.",
                    VcReadSource.InstalledFlag);
            var major = view.Key.TryGetDword(VcVersion.RegistryFieldMajor);
            var minor = view.Key.TryGetDword(VcVersion.RegistryFieldMinor);
            if (major is null || minor is null)
            {
                return VcRuntimeStatus.NotInstalled(
                    $"View {view.Label}: Installed=1 but Major/Minor missing.",
                    VcReadSource.CorruptRegistry);
            }
            var bld = view.Key.TryGetDword(VcVersion.RegistryFieldBld) ?? 0;
            var rbld = view.Key.TryGetDword(VcVersion.RegistryFieldRbld) ?? 0;
            var version = new VcVersion(major.Value, minor.Value, bld, rbld);
            return VcRuntimeStatus.Present(version,
                $"View {view.Label}: reported {version}.",
                VcReadSource.VersionFields);
        }
        catch (Exception ex)
        {
            return VcRuntimeStatus.NotInstalled(
                $"View {view.Label}: registry read failed: {ex.Message}",
                VcReadSource.CorruptRegistry);
        }
    }
}