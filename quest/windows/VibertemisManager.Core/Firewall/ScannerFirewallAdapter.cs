// Firewall scanner.
//
// The manager's "Setup Network Access" button calls a tightly
// scoped helper exe (VibertemisNetworkHelper) via ShellExecute
// runas. The helper applies the single inbound rule we own:
//
//   Vibertemis Companion TCP 28540
//     program: <installroot>\VibertemisVR\manager\vibertemis-host-companion.exe
//     protocol: TCP
//     localport: 28540
//     profile:  Private,Domain
//     remote:   LocalSubnet
//     action:   Allow
//     enabled:  Yes
//
// The Core scanner / applier doesn't talk to netsh itself: the
// rule is added by the elevated helper which formats the netsh
// command with structured argv (no shell). The scanner here
// inspects the current rule set so the UI can show
// "Rule present (matching / not matching)" without elevating.
//
// Production netsh parsing lives in Platform/Windows/NetshParser.cs.
// POSIX tests use a FakeFirewallScanner.
using System.Collections.Generic;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Firewall;

public enum FirewallRuleState
{
    Missing,
    Matches,
    PathMismatch,
    PortMismatch,
    ProfileMismatch,
    RemoteMismatch,
    Disabled,
}

public sealed record FirewallRuleInspection(FirewallRuleState State, FirewallRuleDescriptor? Rule)
{
    public static FirewallRuleInspection Missing() => new(FirewallRuleState.Missing, null);
    public static FirewallRuleInspection Matches(FirewallRuleDescriptor rule) => new(FirewallRuleState.Matches, rule);
    public static FirewallRuleInspection PathMismatch(FirewallRuleDescriptor rule) => new(FirewallRuleState.PathMismatch, rule);
    public static FirewallRuleInspection PortMismatch(FirewallRuleDescriptor rule) => new(FirewallRuleState.PortMismatch, rule);
    public static FirewallRuleInspection ProfileMismatch(FirewallRuleDescriptor rule) => new(FirewallRuleState.ProfileMismatch, rule);
    public static FirewallRuleInspection RemoteMismatch(FirewallRuleDescriptor rule) => new(FirewallRuleState.RemoteMismatch, rule);
    public static FirewallRuleInspection Disabled(FirewallRuleDescriptor rule) => new(FirewallRuleState.Disabled, rule);
}

public interface IFirewallScannerEx
{
    FirewallRuleInspection Inspect(string ruleName, string programPath, ushort port);
    IReadOnlyList<FirewallRuleDescriptor> OwnedRules(string ruleNamePrefix);
}

public sealed class ScannerFirewallAdapter : IFirewallScannerEx
{
    private readonly IFirewallScanner _scanner;
    public ScannerFirewallAdapter(IFirewallScanner scanner) => _scanner = scanner;

    public IReadOnlyList<FirewallRuleDescriptor> OwnedRules(string ruleNamePrefix)
    {
        var owned = new List<FirewallRuleDescriptor>();
        foreach (var r in _scanner.ListInboundRules())
        {
            if (r.RuleName.StartsWith(ruleNamePrefix, System.StringComparison.Ordinal))
                owned.Add(r);
        }
        return owned;
    }

    public FirewallRuleInspection Inspect(string ruleName, string programPath, ushort port)
    {
        FirewallRuleDescriptor? match = null;
        foreach (var r in _scanner.ListInboundRules())
        {
            if (string.Equals(r.RuleName, ruleName, System.StringComparison.Ordinal)) { match = r; break; }
        }
        if (match is null) return FirewallRuleInspection.Missing();
        if (!match.Enabled) return FirewallRuleInspection.Disabled(match);
        if (!PathEquals(match.ProgramPath, programPath)) return FirewallRuleInspection.PathMismatch(match);
        if (match.Port != port) return FirewallRuleInspection.PortMismatch(match);
        if (!ProfilesAcceptable(match.Profiles)) return FirewallRuleInspection.ProfileMismatch(match);
        if (!RemoteAcceptable(match.RemoteAddresses)) return FirewallRuleInspection.RemoteMismatch(match);
        return FirewallRuleInspection.Matches(match);
    }

    private static bool PathEquals(string a, string b)
    {
        if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
        var na = System.IO.Path.GetFullPath(a).Replace('/', '\\');
        var nb = System.IO.Path.GetFullPath(b).Replace('/', '\\');
        return string.Equals(na, nb, System.StringComparison.OrdinalIgnoreCase);
    }

    private static bool ProfilesAcceptable(IReadOnlyList<string> profiles)
    {
        // Required profiles: Private,Domain. Public must NOT be
        // enabled. We allow additional entries only if they don't
        // include "Public".
        var ok = false;
        foreach (var p in profiles)
        {
            if (string.Equals(p, "Public", System.StringComparison.OrdinalIgnoreCase)) return false;
            if (string.Equals(p, "Private", System.StringComparison.OrdinalIgnoreCase)) ok = true;
            if (string.Equals(p, "Domain", System.StringComparison.OrdinalIgnoreCase)) ok = true;
        }
        return ok;
    }

    private static bool RemoteAcceptable(IReadOnlyList<string> remote)
    {
        foreach (var r in remote)
        {
            if (string.Equals(r, "LocalSubnet", System.StringComparison.OrdinalIgnoreCase)) return true;
            // Allowlist of CIDR blocks is checked by the helper, but
            // the scanner treats any explicit remote as suspicious
            // so the UI can prompt the user to re-run Setup.
        }
        return false;
    }
}