// Firewall rule descriptor (model).
//
// This struct is the single source of truth for what the Core
// considers a "matching" rule. The Windows netsh parser
// (Platform/Windows/FirewallScanner.cs) and the POSIX test fake both
// produce FirewallRuleDescriptor instances directly from the
// command-line / mocked text; we never call reg.exe or netsh via
// System.Diagnostics.Process and parse its stdout with a shell.
using System.Collections.Generic;

namespace VibertemisManager.Core.Firewall;

public enum FirewallProtocol
{
    Tcp = 6,
    Udp = 17,
}

public sealed record FirewallRuleDescriptor(
    string RuleName,
    string ProgramPath,
    ushort Port,
    FirewallProtocol Protocol,
    IReadOnlyList<string> Profiles,
    IReadOnlyList<string> RemoteAddresses,
    bool Enabled);