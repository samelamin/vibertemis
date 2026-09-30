using System.Diagnostics;
using VibertemisManager.Core.Network;
using VibertemisManager.Core.Bridge;
#if WINDOWS
using VibertemisManager.Core.Platform.Windows;
#endif
namespace VibertemisManager.NetworkHelper;
internal static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length >= 1 && args[0] == "--register-vr-bridge")
            return RunRegisterBridge(args);

        var vpn = args.Length == 2 && args[0] == "--setup-tailscale";
        if (!vpn && (args.Length != 1 || args[0] != "--setup-network")) return 2;
        try
        {
            var localAddress = vpn ? TailscaleNetwork.ValidateLocalAddress(args[1]) : null;
            // Elevated helper accepts no caller-controlled paths or rule options.
            var companion = Path.Combine(AppContext.BaseDirectory, "bin", "vibertemis-host-companion.exe");
            if (!File.Exists(companion)) return 3;
            foreach (var rule in new[] { (Name: "Vibertemis Companion TCP 28540", Protocol: "TCP", Port: "28540", Program: companion),
                (Name: "Vibertemis Discovery UDP 5353", Protocol: "UDP", Port: "5353", Program: companion),
                // The native driver runs inside SteamVR, not the companion.
                // Fixed transport ports, trusted LAN scope only; no public VR rule.
                (Name: "Vibertemis VR UDP 9944", Protocol: "UDP", Port: "9944", Program: ""),
                (Name: "Vibertemis VR TCP 9944", Protocol: "TCP", Port: "9944", Program: ""),
                (Name: "Vibertemis VR TCP 9943", Protocol: "TCP", Port: "9943", Program: "") })
            {
                if (vpn && rule.Port == "5353") continue;
                var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "netsh.exe"))
                {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardOutput = true, RedirectStandardError = true
                };
                foreach (var arg in new[] { "advfirewall", "firewall", "add", "rule",
                    "name=" + (vpn ? rule.Name + " Tailscale" : rule.Name), "dir=in", "action=allow", "protocol=" + rule.Protocol,
                    "localport=" + rule.Port, vpn ? "profile=any" : "profile=private,domain",
                    vpn ? "remoteip=100.64.0.0/10" : "remoteip=LocalSubnet" })
                    psi.ArgumentList.Add(arg);
                if (rule.Program.Length != 0) psi.ArgumentList.Add("program=" + rule.Program);
                if (localAddress != null) psi.ArgumentList.Add("localip=" + localAddress);
                using var process = Process.Start(psi) ?? throw new IOException("Could not start firewall configuration");
                var output = process.StandardOutput.ReadToEndAsync();
                var error = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(15000)) { try { process.Kill(); } catch (InvalidOperationException) { } return 4; }
                Task.WaitAll(output, error);
                if (process.ExitCode != 0) return 5;
            }
            return 0;
        }
        catch (Exception ex) { Console.Error.WriteLine(ex.Message); return 1; }
    }

    // --register-vr-bridge <managerPID>
    //
    // The helper invokes the bounded WindowsBridge registration
    // controller. Caller PID arg is verified against the kernel:
    // we never accept a caller-controlled path for Companion or
    // Sunshine. Both are derived (constant companion path + SCM-
    // resolved Sunshine path).
    private static int RunRegisterBridge(string[] args)
    {
        if (args.Length != 2) return 2;
        if (!int.TryParse(args[1], out var managerPid) || managerPid <= 0) return 6;
#if WINDOWS
        try
        {
            // Canonical paths: the manager is at <helper-dir>/VibertemisManager.App.exe
            // (the helper lives at <programsroot>/manager/), so we use AppContext.BaseDirectory.
            var canonicalManager = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory,
                BridgePaths.CanonicalManagerBasename));
            // CompanionPath is fixed; the helper refuses any caller-controlled value.
            var programsRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, ".."));
            var canonicalCompanion = BridgePaths.ResolveCompanionPathFromProgramsRoot(programsRoot);

            if (!File.Exists(canonicalCompanion)) return 12;
            var probe = new WindowsBridgeProcessProbe();
            var scm = new WindowsBridgeScmProbe();
            var reader = new WindowsBridgeKeyReader();
            var writer = new WindowsBridgeKeyWriter();
            var controller = new BridgeRegistrationController(
                probe, scm, reader, writer, canonicalManager, canonicalCompanion);
            controller.SetActiveSessionId(WindowsBridgeActiveSession.Read());

            var result = controller.HandleRegistration(managerPid);
            return result.Outcome switch
            {
                BridgeWriteOutcome.Written => 0,
                BridgeWriteOutcome.WrongImage => 7,
                BridgeWriteOutcome.WrongSession => 8,
                BridgeWriteOutcome.AccessDenied => 9,
                BridgeWriteOutcome.UnreadableSid => 10,
                BridgeWriteOutcome.SunshineUnresolved => 11,
                BridgeWriteOutcome.CompanionMissing => 12,
                BridgeWriteOutcome.AmbiguousUser => 13,
                _ => 14,
            };
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
#else
        return 99;
#endif
    }
}