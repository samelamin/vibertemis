// Firewall rule inspection tests using a fake IFirewallScanner.
//
// Verifies that:
//   - Missing when no rule with the given name exists.
//   - Matches when program path, port, profiles, and remote all
//     match the documented contract.
//   - ProfileMismatch when Public profile is included.
//   - ProfileMismatch when neither Private nor Domain is set.
//   - Disabled when rule exists but Enabled=No.
//   - PathMismatch when the rule's program path differs.
//   - RemoteMismatch when remote doesn't restrict to LocalSubnet.
using System.Collections.Generic;
using VibertemisManager.Core.Firewall;
using VibertemisManager.Core.Tests;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class FirewallRuleInspectionTests
{
    private const string RuleName = "Vibertemis Companion TCP 28540";
    private const string Program = @"C:\Users\me\AppData\Local\Programs\VibertemisVR\manager\bin\vibertemis-host-companion.exe";

    [Fact]
    public void Inspect_MissingWhenRuleNotPresent()
    {
        var fake = new FakeFirewallScanner();
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.Missing, inspection.State);
        Assert.Null(inspection.Rule);
    }

    [Fact]
    public void Inspect_MatchesWhenAllFieldsOk()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, Program, 28540, new[] { "Private", "Domain" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.Matches, inspection.State);
    }

    [Fact]
    public void Inspect_DisabledWhenRuleOff()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(false, Program, 28540, new[] { "Private", "Domain" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.Disabled, inspection.State);
    }

    [Fact]
    public void Inspect_PathMismatchWhenExeDifferent()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, @"C:\Different\path.exe", 28540, new[] { "Private", "Domain" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.PathMismatch, inspection.State);
    }

    [Fact]
    public void Inspect_PortMismatchWhenPortWrong()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, Program, 28541, new[] { "Private", "Domain" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.PortMismatch, inspection.State);
    }

    [Fact]
    public void Inspect_ProfileMismatchWhenPublicIncluded()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, Program, 28540, new[] { "Private", "Public" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.ProfileMismatch, inspection.State);
    }

    [Fact]
    public void Inspect_ProfileMismatchWhenNoPrivateOrDomain()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, Program, 28540, new[] { "Any" }, new[] { "LocalSubnet" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.ProfileMismatch, inspection.State);
    }

    [Fact]
    public void Inspect_RemoteMismatchWhenAnyAddress()
    {
        var fake = new FakeFirewallScanner();
        fake.Rules.Add(NewRule(true, Program, 28540, new[] { "Private", "Domain" }, new[] { "Any" }));
        var adapter = new ScannerFirewallAdapter(fake);
        var inspection = adapter.Inspect(RuleName, Program, 28540);
        Assert.Equal(FirewallRuleState.RemoteMismatch, inspection.State);
    }

    private static FirewallRuleDescriptor NewRule(bool enabled, string program, ushort p, IReadOnlyList<string> profiles, IReadOnlyList<string> remote)
        => new(RuleName, program, p, FirewallProtocol.Tcp, profiles, remote, enabled);
}