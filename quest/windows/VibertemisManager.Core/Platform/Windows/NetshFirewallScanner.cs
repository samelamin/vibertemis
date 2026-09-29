// Windows firewall scanner using netsh advfirewall.
//
// We invoke netsh via Process.Start with ArgumentList (no shell),
// parse the localized output via fixed regex / line-shape rules,
// and never exec reg.exe. The scanner is read-only; the elevated
// helper writes the rule via the same netsh command.
//
// netsh output is locale-dependent; we restrict the parser to
// detect:
//   - Rule Name: <name>
//   - Enabled: Yes/No
//   - Program: <path>
//   - Protocol: TCP/UDP
//   - LocalPort: <port>
//   - Profile: <comma list>
//   - RemoteIP: <ip / LocalSubnet / *>
//
// We do NOT depend on the exact display order; each line is read
// independently and matched against a fixed set of field names
// that have been stable across Windows 10 / 11 / Server 2022.
#if WINDOWS
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;
using VibertemisManager.Core.Firewall;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class NetshFirewallScanner : IFirewallScanner
{
    public IReadOnlyList<FirewallRuleDescriptor> ListInboundRules()
    {
        // `netsh advfirewall firewall show rule name=all dir=in type=static` -
        // we use type=any so per-program rules are also returned.
        var args = new List<string>
        {
            "advfirewall", "firewall", "show", "rule",
            "name=all", "dir=in", "verbose"
        };
        string stdout, stderr;
        try
        {
            var psi = new ProcessStartInfo("netsh")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi) ?? throw new InvalidOperationException("netsh start null");
            stdout = p.StandardOutput.ReadToEnd();
            stderr = p.StandardError.ReadToEnd();
            if (!p.WaitForExit(15000))
            {
                try { p.Kill(); } catch { }
                throw new InvalidOperationException("netsh firewall show rule timed out: " + stderr);
            }
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException("netsh firewall show rule failed: " + ex.Message, ex);
        }

        return NetshParser.Parse(stdout);
    }

    public bool TryAddInbound(string ruleName, string programPath, ushort port, IReadOnlyList<string> profiles, IReadOnlyList<string> remoteAddresses)
    {
        // Caller should use the elevated helper; this method is
        // provided so unit tests can verify the netsh command
        // construction without elevation. On a real workstation
        // it would require UAC.
        var profileArg = string.Join(",", profiles);
        var remoteArg = string.Join(",", remoteAddresses);
        var args = new List<string>
        {
            "advfirewall", "firewall", "add", "rule",
            $"name={ruleName}",
            "dir=in",
            "action=allow",
            "protocol=TCP",
            $"localport={port.ToString(CultureInfo.InvariantCulture)}",
            $"program={programPath}",
            $"profile={profileArg}",
            $"remoteip={remoteArg}",
        };
        var psi = new ProcessStartInfo("netsh")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi);
        if (p is null) return false;
        var stderr = p.StandardError.ReadToEnd();
        var ok = p.WaitForExit(15000) && p.ExitCode == 0;
        return ok;
    }

    public bool TryDeleteInbound(string ruleName)
    {
        var args = new List<string>
        {
            "advfirewall", "firewall", "delete", "rule",
            $"name={ruleName}",
        };
        var psi = new ProcessStartInfo("netsh")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi);
        if (p is null) return false;
        return p.WaitForExit(15000) && p.ExitCode == 0;
    }
}

internal static class NetshParser
{
    public static IReadOnlyList<FirewallRuleDescriptor> Parse(string stdout)
    {
        var rules = new List<FirewallRuleDescriptor>();
        // Blocks separated by a line containing only "----------"
        var lines = stdout.Replace("\r\n", "\n").Split('\n');
        var block = new List<string>();
        var done = new HashSet<string>();
        for (var i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            if (line.StartsWith("----------"))
            {
                if (block.Count > 0)
                {
                    var r = BuildRule(block);
                    if (r != null && done.Add(r.RuleName)) rules.Add(r);
                    block.Clear();
                }
                continue;
            }
            block.Add(line);
        }
        if (block.Count > 0)
        {
            var r = BuildRule(block);
            if (r != null && done.Add(r.RuleName)) rules.Add(r);
        }
        return rules;
    }

    private static FirewallRuleDescriptor? BuildRule(List<string> block)
    {
        string? name = null, program = null, portText = null, proto = null;
        string profileText = "", remoteText = "";
        bool enabled = false;
        foreach (var raw in block)
        {
            var line = raw.Trim();
            var colon = line.IndexOf(':');
            if (colon < 0) continue;
            var field = line.Substring(0, colon).Trim();
            var val = line.Substring(colon + 1).Trim();
            switch (field)
            {
                case "Rule Name": name = val; break;
                case "Enabled": enabled = string.Equals(val, "Yes", StringComparison.OrdinalIgnoreCase); break;
                case "Program": program = val; break;
                case "Protocol": proto = val; break;
                case "LocalPort": portText = val; break;
                case "Profile": profileText = val; break;
                case "RemoteIP": remoteText = val; break;
            }
        }
        if (name is null) return null;
        ushort port = 0;
        if (portText != null)
        {
            var first = portText.Split(',')[0].Trim();
            ushort.TryParse(first, NumberStyles.Integer, CultureInfo.InvariantCulture, out port);
        }
        var profiles = profileText.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var remotes = remoteText.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var fp = string.Equals(proto, "UDP", StringComparison.OrdinalIgnoreCase) ? FirewallProtocol.Udp : FirewallProtocol.Tcp;
        return new FirewallRuleDescriptor(name, program ?? "", port, fp, profiles, remotes, enabled);
    }
}
#endif