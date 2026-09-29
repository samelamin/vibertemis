// Pairing export contract.
//
// The "Export pairing" button on the manager runs the companion
// with -export-pairing (a separate exit-only invocation). The
// companion writes the export to its existing per-user state dir
// (%LOCALAPPDATA%\vibertemis\companion\pairing-export.json) and
// prints the absolute path to stdout. The manager:
//
//   1. Builds argv from the SAME validated launch spec the
//      companion would have run with (listen / advertise /
//      alvr-session / state-dir) so the export uses the same
//      advertised address the live companion uses.
//   2. Verifies the companion executable's integrity through
//      the same IIntegrityVerifier the live launcher uses.
//   3. Adds the -export-pairing flag.
//   4. Reads stdout, canonicalises the path, and validates it
//      is located under the exact private state directory
//      before returning it to the caller. The manager NEVER
//      opens the file by content; the UI uses Process.Start
//      (UseShellExecute=true) to reveal the directory in
//      Explorer.
//
// The actual Go main requires -alvr-session to be ABSOLUTE and
// -advertise to be a non-loopback address (validated inside
// validateAdvertisedAddr). The launch spec the manager already
// produces satisfies both. The OLD runner only passed
// -export-pairing and -state-dir; the Go main would die with
// "-alvr-session is required" because the flag was missing.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using VibertemisManager.Core.Integrity;

namespace VibertemisManager.Core.Companion;

public sealed record PairingExportResult(string FilePath);

public interface IPairingExportRunner
{
    PairingExportResult Run(CompanionLaunchSpec spec, IIntegrityVerifier verifier, TimeSpan timeout);
}

public sealed class CompanionPairingExportRunner : IPairingExportRunner
{
    private readonly Func<string, IReadOnlyList<string>, Process> _spawner;
    public CompanionPairingExportRunner()
        : this(DefaultSpawner)
    {
    }

    public CompanionPairingExportRunner(Func<string, IReadOnlyList<string>, Process> spawner)
        => _spawner = spawner;

    public PairingExportResult Run(CompanionLaunchSpec spec, IIntegrityVerifier verifier, TimeSpan timeout)
    {
        // argv matches the actual Go CLI contract (verified with
        // `/tmp/companion --help`): -alvr-session MUST be
        // absolute, -advertise MUST be a non-loopback host:port,
        // -listen + -state-dir match the live launch. The export
        // flag toggles the one-shot write path. We validate the
        // path shapes BEFORE reading any file bytes so a buggy
        // launch spec surfaces as a clear argument error rather
        // than an integrity fault.
        if (string.IsNullOrEmpty(spec.AlvrSessionPath) || !Path.IsPathRooted(spec.AlvrSessionPath))
            throw new InvalidOperationException("Pairing export requires absolute -alvr-session path.");
        if (string.IsNullOrEmpty(spec.StateDir) || !Path.IsPathRooted(spec.StateDir))
            throw new InvalidOperationException("Pairing export requires absolute -state-dir path.");
        if (string.IsNullOrEmpty(spec.CompanionExePath) || !Path.IsPathRooted(spec.CompanionExePath))
            throw new InvalidOperationException("Pairing export requires absolute companion exe path.");

        // Same integrity gate as the live launcher. The export
        // process is a one-shot but it still touches the pairing
        // state dir and writes a file containing the token, so we
        // refuse to launch anything whose hash we don't have.
        if (!verifier.TryGetHash(spec.CompanionExePath, out var expected) || expected is null)
            throw new CompanionIntegrityException($"No integrity entry for {spec.CompanionExePath}.");
        if (!verifier.Verify(spec.CompanionExePath, out var actual))
            throw new CompanionIntegrityException($"Companion integrity check failed before export.");
        if (!string.Equals(expected.Hex, actual!.Hex, StringComparison.OrdinalIgnoreCase))
            throw new CompanionIntegrityException($"Companion integrity mismatch before export: expected {expected.Hex} got {actual.Hex}.");

        var argv = new List<string>
        {
            "-listen", $"{spec.ListenAddress}:{spec.listenPort}",
            "-advertise", $"{spec.AdvertiseAddress}:{spec.AdvertisePort}",
            "-alvr-session", spec.AlvrSessionPath,
            "-state-dir", spec.StateDir,
            "-export-pairing",
        };

        using var proc = _spawner(spec.CompanionExePath, argv);
        if (!proc.WaitForExit((int)timeout.TotalMilliseconds))
        {
            try { proc.Kill(); } catch { /* ignore */ }
            throw new TimeoutException("Companion pairing export timed out.");
        }
        var stdout = (proc.StandardOutput.ReadToEnd() ?? "").Trim();
        if (proc.ExitCode != 0 || string.IsNullOrWhiteSpace(stdout))
            throw new InvalidOperationException($"Companion -export-pairing failed (exit {proc.ExitCode}, stderr: {proc.StandardError.ReadToEnd()}).");

        // Validate the emitted path is under the exact private
        // state dir. Canonicalize both sides to defend against
        // "../" tricks, mixed separators, and Windows 8.3 short
        // names. If the canonical state dir differs at all we
        // refuse the export so the manager never opens a file
        // outside the protected directory.
        var canonicalState = CanonicalDir(spec.StateDir);
        var canonicalPath = CanonicalDir(Path.GetDirectoryName(stdout));
        if (string.IsNullOrEmpty(canonicalPath) ||
            (!canonicalPath.Equals(canonicalState, StringComparison.OrdinalIgnoreCase) &&
             !canonicalPath.StartsWith(canonicalState + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException($"Companion export path '{stdout}' is not under the state directory '{spec.StateDir}'.");

        return new PairingExportResult(stdout);
    }

    private static string CanonicalDir(string? dir)
    {
        if (string.IsNullOrEmpty(dir)) return string.Empty;
        try { return Path.GetFullPath(dir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar); }
        catch { return dir; }
    }

    private static Process DefaultSpawner(string exePath, IReadOnlyList<string> args)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        return Process.Start(psi) ?? throw new InvalidOperationException("Process.Start returned null");
    }
}
