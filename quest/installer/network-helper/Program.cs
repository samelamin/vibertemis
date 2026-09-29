using System.Diagnostics;
namespace VibertemisManager.NetworkHelper;
internal static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length != 1 || args[0] != "--setup-network") return 2;
        try
        {
            // Elevated helper accepts no caller-controlled paths or rule options.
            var companion = Path.Combine(AppContext.BaseDirectory, "bin", "vibertemis-host-companion.exe");
            if (!File.Exists(companion)) return 3;
            var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "netsh.exe"))
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            foreach (var arg in new[] { "advfirewall", "firewall", "add", "rule",
                "name=Vibertemis Companion TCP 28540", "dir=in", "action=allow", "protocol=TCP",
                "localport=28540", "program=" + companion, "profile=private,domain", "remoteip=LocalSubnet" })
                psi.ArgumentList.Add(arg);
            using var process = Process.Start(psi) ?? throw new IOException("Could not start firewall configuration");
            var output = process.StandardOutput.ReadToEndAsync();
            var error = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(15000)) { try { process.Kill(); } catch (InvalidOperationException) { } return 4; }
            Task.WaitAll(output, error);
            return process.ExitCode == 0 ? 0 : 5;
        }
        catch (Exception ex) { Console.Error.WriteLine(ex.Message); return 1; }
    }
}
