// Test fakes for the platform abstractions so the rest of Core
// can be exercised on Linux without Windows-only types.
using System.Collections.Generic;
using VibertemisManager.Core.Firewall;
using VibertemisManager.Core.Network;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.SingleInstance;

namespace VibertemisManager.Core.Tests;

public sealed class FakeProcessTable : IProcessTable
{
    public List<RunningProcess> Processes { get; } = new();
    public IReadOnlyList<RunningProcess> Snapshot() => Processes;
    public ProcessQueryStatus TryGet(int processId, out RunningProcess process)
    {
        foreach (var p in Processes)
            if (p.ProcessId == processId) { process = p; return ProcessQueryStatus.Found; }
        process = default;
        return ProcessQueryStatus.Missing;
    }
    public bool IsRunning(string executableName)
        => Processes.Exists(p => p.Name.Equals(executableName, System.StringComparison.OrdinalIgnoreCase));
}

public sealed class FakeFileSystem : IFileSystemAccess
{
    public Dictionary<string, string> Files { get; } = new();
    public bool FileExists(string path) => Files.ContainsKey(path);
    public string ReadAllText(string path) => Files[path];
    public bool DirectoryExists(string path) => true;
    public IEnumerable<string> EnumerateFiles(string directory, string pattern)
    {
        foreach (var f in Files.Keys)
            if (f.StartsWith(directory)) yield return f;
    }
}

public sealed class FakeRegistry : IRegistryAccess
{
    public Dictionary<(string Hive, string Key, string Value), string> Values { get; } = new();
    public string? TryGetString(string hive, string subKey, string valueName)
    {
        return Values.TryGetValue((hive, subKey, valueName), out var v) ? v : null;
    }
    public IEnumerable<(string ValueName, string Data)> EnumerateValues(string hive, string subKey)
    {
        foreach (var kvp in Values)
            if (kvp.Key.Hive == hive && kvp.Key.Key == subKey)
                yield return (kvp.Key.Value, kvp.Value);
    }
}

public sealed class FakeFirewallScanner : IFirewallScanner
{
    public List<FirewallRuleDescriptor> Rules { get; } = new();
    IReadOnlyList<FirewallRuleDescriptor> IFirewallScanner.ListInboundRules() => Rules;
    public bool TryAddInbound(string ruleName, string programPath, ushort port, IReadOnlyList<string> profiles, IReadOnlyList<string> remoteAddresses)
    {
        Rules.Add(new FirewallRuleDescriptor(ruleName, programPath, port, FirewallProtocol.Tcp, profiles, remoteAddresses, true));
        return true;
    }
    public bool TryDeleteInbound(string ruleName)
    {
        Rules.RemoveAll(r => r.RuleName == ruleName);
        return true;
    }
}

public sealed class FakeUacHelper : IUacHelper
{
    public List<(string HelperPath, string HelperArgs)> Calls { get; } = new();
    public UacLaunchResult NextResult { get; set; } = new(true, 9999, "");
    public UacLaunchResult Launch(string helperPath, string helperArgs)
    {
        Calls.Add((helperPath, helperArgs));
        return NextResult;
    }
}

public sealed class FakeAdapterEnumerator : IAdapterEnumerator
{
    public List<NetworkAdapter> Adapters { get; } = new();
    public IReadOnlyList<NetworkAdapter> Enumerate() => Adapters;
}

public sealed class FakeSingleInstanceGuardFactory : ISingleInstanceGuardFactory
{
    public int MaxAcquires { get; set; } = 1;
    private int _taken;
    public ISingleInstanceGuard Create(string _)
    {
        if (_taken >= MaxAcquires) return new AlwaysFailsSingleInstanceGuard();
        _taken++;
        return new AlwaysAcquiresSingleInstanceGuard();
    }
}

public sealed class AlwaysFailsSingleInstanceGuard : ISingleInstanceGuard
{
    public bool TryAcquire(out System.IDisposable? handle)
    {
        handle = null;
        return false;
    }
}

public sealed class FixedPaths : IPathResolver
{
    public FixedPaths(string root) { LocalAppData = root; }
    public string LocalAppData { get; }
    public string ProgramsRoot => System.IO.Path.Combine(LocalAppData, "Programs", "VibertemisVR");
    public string ManagerStateDir => System.IO.Path.Combine(LocalAppData, "VibertemisVRHostManager");
    public string CompanionStateDir => System.IO.Path.Combine(LocalAppData, "vibertemis", "companion");
    public string UpdateCacheDir => System.IO.Path.Combine(ManagerStateDir, "update");
    public string LogDir => System.IO.Path.Combine(ManagerStateDir, "logs");
}