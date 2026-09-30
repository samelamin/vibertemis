// Bounded VC++ redistributable verification + installation.
//
// Two responsibilities, both of which run UNATTENDED and OFFLINE
// using the bundled vc_redist.x64.exe shipped under
//   <programsroot>/manager/prerequisites/vc_redist.x64.exe
// (recorded in the build-pipeline integrity manifest so the
// manager refuses to launch a package whose bundled digest /
// size / Authenticode publisher does not match the Microsoft
// redistributable):
//
//   1. Verify the bundled package BEFORE execution:
//        * Embedded manifest SHA-256 + size must match.
//        * WinVerifyTrust with WINTRUST_ACTION_GENERIC_VERIFY_V2
//          must return ERROR_SUCCESS (so we are not relying on a
//          certificate chain alone - we are verifying the
//          Authenticode signature on the file bytes themselves).
//        * The extracted leaf signer must contain
//          "Microsoft Corporation" (CN or O match) - we never
//          accept an arbitrary publisher.
//
//   2. Run the verified installer with /install /passive
//      /norestart, await exit (without blocking the UI thread -
//      the installer runs via ShellExecute runas / UAC), and
//      re-detect the runtime version. Exit-code semantics:
//        * 0  -> success (recheck must show >= minimum runtime)
//        * 3010 / 1641 -> explicit restart required (NEVER
//          claimed success; the user must restart Windows)
//        * 1223 -> UAC denial (the user clicked No)
//        * everything else -> actionable failure surfaced to
//          the guided setup UI with documented details.
//
// vc_redist.x64.exe ALWAYS requires elevation to install (per the
// Microsoft docs: "If you aren't running from an elevated command
// prompt, you'll need to respond to a User Account Control
// prompt..."). The manager itself is per-user / unelevated; the
// install runs via UAC just like the existing firewall helper
// invocation. If the user denies the UAC prompt (ERROR_CANCELLED
// 1223), the manager surfaces "UAC cancelled" so the user can
// retry. If the installer is still running when the wait
// elapses, the manager OBSERVES (does NOT kill the installer -
// it may be a slow but legitimate install) and the next pass
// re-checks the registry; a duplicate launch is never attempted
// while the previous one is in progress.
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace VibertemisManager.Core.Prerequisites;

public sealed record VcRedistPackage(
    string AbsolutePath,
    long Size,
    string Sha256,
    string Publisher,
    string AuthenticodeSubject,
    VcVersion FileVersion);

public enum VcRedistVerifyResult
{
    Valid,
    MissingFile,
    EmptyFile,
    SizeMismatch,
    HashMismatch,
    AuthenticodeMissing,
    AuthenticodeInvalid,
    WrongPublisher,
    FileVersionTooOld,
    FileVersionUnreadable,
}

public sealed record VcRedistVerification(
    VcRedistVerifyResult Result,
    string Detail,
    VcRedistPackage? Package)
{
    public bool IsValid => Result == VcRedistVerifyResult.Valid;
}

public enum VcRedistExit
{
    Success,
    RestartRequired,
    AlreadyInstalled,
    PackageCorrupt,
    Denied,
    LaunchFailed,
    StillRunning,
    Timeout,
    Other,
}

public sealed record VcRedistInstallResult(
    VcRedistExit Exit,
    int RawExitCode,
    string Detail,
    VcRuntimeStatus VerifiedRuntime);

// Bundled vc_redist.x64.exe launch. Production binding
// (Platform/Windows) uses ShellExecute=true + Verb=runas so
// Windows prompts the user with the standard UAC dialog; the
// helper itself is the redistributable, not a custom shell. The
// launch is awaited off the UI thread.
//
// Launch is stateful: a previous in-flight install survives
// timeout (we never kill the redistributable) and is reobserved
// on subsequent calls. The wrapper retries via TryGetResult
// after the wait elapses.
public interface IVcRedistLauncher
{
    // Begin the install; returns the assigned ExitCode if the
    // process finished before the wait. Returns null if the
    // wait elapsed with the process still running (we never
    // kill the redistributable).
    int? Begin(VcRedistPackage package);

    // Observes a previously in-flight install. Returns:
    //   - true with ExitCode available if the process completed.
    //   - false if there is no in-flight install OR the install
    //     is still running.
    bool TryGetResult(out int exitCode, out bool stillRunning);

    // True if a previous install is in-flight and not yet observed.
    bool IsInFlight { get; }
}

// POSIX stub. Returns null so the Core verifier reports a
// platform-mismatch failure; production uses the Windows binding.
public sealed class FallbackVcRedistLauncher : IVcRedistLauncher
{
    public int? Begin(VcRedistPackage package) =>
        throw new PlatformNotSupportedException("vc_redist.x64.exe install is Windows-only.");
    public bool TryGetResult(out int exitCode, out bool stillRunning)
    { exitCode = -1; stillRunning = false; return false; }
    public bool IsInFlight => false;
}

// Reads the PE FileVersion from the bundled redistributable's
// RT_VERSION resource AND authenticates the Authenticode
// signature on the file. The Windows binding uses WinVerifyTrust
// (which actually verifies the Authenticode signature on the
// file bytes) + X509Certificate.CreateFromSignedFile to extract
// the leaf signer name for the publisher string match.
//
// X509Certificate.CreateFromSignedFile does NOT verify the file
// content signature - it only returns the certificate stored in
// the PE. WinVerifyTrust MUST run alongside it. The inspector
// returns null if either step fails.
public interface IVcRedistInspector
{
    VcRedistPackage? Inspect(string absolutePath, VcVersion minimumFileVersion, string expectedPublisher);
}

public static class VcRedistVerifier
{
    public const string ExpectedPublisher = "Microsoft Corporation";
    public const long MaxPackageBytes = 64L * 1024L * 1024L;

    // The leaf Authenticode signing certificate's Subject O=
    // (Organization) component. WinVerifyTrust already verified
    // the chain; we then re-check the leaf DN O= explicitly so a
    // valid foreign signer whose issuer chain happens to mention
    // Microsoft is rejected. We DO NOT trust Issuer (which is
    // the issuing CA) and DO NOT trust FileVersion CompanyName
    // (a forgeable PE string).
    public static bool LeafSubjectMatchesMicrosoft(string? subjectDn)
    {
        if (string.IsNullOrEmpty(subjectDn)) return false;
        foreach (var part in subjectDn.Split(','))
        {
            var trimmed = part.Trim();
            if (trimmed.StartsWith("O=", System.StringComparison.OrdinalIgnoreCase))
            {
                var value = trimmed.Substring(2).Trim();
                return string.Equals(value, ExpectedPublisher, System.StringComparison.Ordinal);
            }
        }
        return false;
    }

    // Required: the bundled package's manifest entry MUST have a
    // SHA-256 + size. Optional hashes are deliberately not
    // permitted: skipping the manifest check on this code path
    // would let a tampered bundled package get executed. The
    // build pipeline always writes the entry; callers that don't
    // have one yet must refuse, not pass nulls.
    public static VcRedistVerification Verify(
        string absolutePath,
        string expectedSha256,
        long expectedSize,
        IVcRedistInspector inspector,
        VcVersion minimumFileVersion)
    {
        if (string.IsNullOrEmpty(absolutePath))
            return new(VcRedistVerifyResult.MissingFile, "Package path is empty.", null);
        if (string.IsNullOrEmpty(expectedSha256))
            return new(VcRedistVerifyResult.HashMismatch, "Bundled manifest SHA-256 is required.", null);
        if (expectedSize <= 0)
            return new(VcRedistVerifyResult.SizeMismatch, "Bundled manifest size is required.", null);
        if (!File.Exists(absolutePath))
            return new(VcRedistVerifyResult.MissingFile, "Package missing: " + absolutePath, null);
        var info = new FileInfo(absolutePath);
        if (info.Length <= 0)
            return new(VcRedistVerifyResult.EmptyFile, "Package is empty.", null);
        if (info.Length > MaxPackageBytes)
            return new(VcRedistVerifyResult.SizeMismatch, "Package exceeds " + MaxPackageBytes + " bytes.", null);
        if (info.Length != expectedSize)
            return new(VcRedistVerifyResult.SizeMismatch,
                "Package size " + info.Length + " differs from manifest " + expectedSize + ".", null);
        string actualHex;
        using (var fs = File.OpenRead(absolutePath))
        using (var sha = SHA256.Create())
        {
            var hash = sha.ComputeHash(fs);
            actualHex = ToHex(hash);
        }
        if (!string.Equals(actualHex, expectedSha256, StringComparison.OrdinalIgnoreCase))
            return new(VcRedistVerifyResult.HashMismatch,
                "Package SHA-256 mismatch (manifest expects " + expectedSha256 + ", got " + actualHex + ").",
                null);
        var pkg = inspector.Inspect(absolutePath, minimumFileVersion, ExpectedPublisher);
        if (pkg is null)
            return new(VcRedistVerifyResult.AuthenticodeInvalid,
                "Could not verify Authenticode signature on this platform.", null);
        if (!LeafSubjectMatchesMicrosoft(pkg.AuthenticodeSubject))
            return new(VcRedistVerifyResult.WrongPublisher,
                "Authenticode leaf Subject does not contain O=\"" + ExpectedPublisher +
                "\" (subject=\"" + pkg.AuthenticodeSubject + "\", publisher-string=\"" + pkg.Publisher + "\").",
                pkg);
        if (pkg.FileVersion.CompareTo(minimumFileVersion) < 0)
            return new(VcRedistVerifyResult.FileVersionTooOld,
                "vc_redist.x64.exe FileVersion " + pkg.FileVersion + " is older than minimum " + minimumFileVersion + ".",
                pkg);
        return new(VcRedistVerifyResult.Valid, "Verified.", pkg);
    }

    [Obsolete("Use LeafSubjectMatchesMicrosoft - this accepted any substring including CA issuers.")]
    public static bool IsMicrosoftPublisher(string? text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        return text.IndexOf(ExpectedPublisher, StringComparison.OrdinalIgnoreCase) >= 0;
    }

    private static string ToHex(byte[] bytes)
    {
        var sb = new StringBuilder(bytes.Length * 2);
        foreach (var b in bytes) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }
}

public sealed class VcRedistInstaller
{
    private readonly IVcRedistLauncher _launcher;
    private readonly IVcRuntimeDetector _detector;
    private readonly Func<string, IVcRedistInspector?> _inspectorFactory;
    private readonly Func<bool> _isWindows;

    public VcRedistInstaller(
        IVcRedistLauncher launcher,
        IVcRuntimeDetector detector,
        Func<string, IVcRedistInspector?> inspectorFactory,
        Func<bool>? isWindows = null)
    {
        _launcher = launcher;
        _detector = detector;
        _inspectorFactory = inspectorFactory;
        _isWindows = isWindows ?? (() => OperatingSystem.IsWindows());
    }

    public bool IsInstallInFlight => _launcher.IsInFlight;

    // The high-level entry point. Verifies the package, runs the
    // installer (only if needed), re-detects, and returns a
    // structured result. A 3010 / 1641 exit is reported as
    // RestartRequired and is NEVER interpreted as "Success".
    //
    // State machine:
    //   - In-flight (from a prior call that timed out) -> first
    //     observe via TryGetResult; if completed, treat as
    //     returned exit code; if still running, return
    //     StillRunning (NEVER launch a duplicate).
    //   - No in-flight, runtime already satisfactory -> return
    //     AlreadyInstalled.
    //   - No in-flight, runtime missing -> begin launch via
    //     launcher.Begin, wait up to the launcher's wait
    //     deadline. On exit, re-detect and classify.
    public VcRedistInstallResult EnsureInstalled(
        string absolutePath,
        string expectedSha256,
        long expectedSize,
        VcVersion minimumFileVersion,
        VcVersion minimumRuntimeVersion)
    {
        if (_launcher.IsInFlight)
        {
            return ObserveInFlightInstall(minimumRuntimeVersion);
        }

        var inspector = _inspectorFactory(absolutePath);
        if (inspector is null)
            return new VcRedistInstallResult(VcRedistExit.PackageCorrupt, -1,
                "Authenticode verification is not available on this platform.",
                _detector.Detect());

        var verification = VcRedistVerifier.Verify(
            absolutePath, expectedSha256, expectedSize, inspector, minimumFileVersion);
        if (!verification.IsValid || verification.Package is null)
            return new VcRedistInstallResult(
                VcRedistExit.PackageCorrupt,
                -1,
                verification.Detail,
                _detector.Detect());

        var status = _detector.Detect();
        if (VcRuntimeRequirements.Classify(status, minimumRuntimeVersion) == VcSatisfaction.Satisfied)
            return new VcRedistInstallResult(VcRedistExit.AlreadyInstalled, 0,
                "VC++ runtime " + status.InstalledVersion + " already satisfies the minimum.",
                status);

        int? exitCode;
        try { exitCode = _launcher.Begin(verification.Package); }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            return new VcRedistInstallResult(VcRedistExit.Denied, ex.NativeErrorCode,
                "UAC cancelled. Approve the prompt and retry.",
                _detector.Detect());
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            return new VcRedistInstallResult(VcRedistExit.Denied, ex.NativeErrorCode,
                "Could not launch vc_redist.x64.exe: " + ex.Message,
                _detector.Detect());
        }
        catch (InvalidOperationException ex)
        {
            return new VcRedistInstallResult(VcRedistExit.LaunchFailed, -1, ex.Message, _detector.Detect());
        }
        catch (Exception ex)
        {
            return new VcRedistInstallResult(VcRedistExit.Other, -1, ex.Message, _detector.Detect());
        }

        if (!exitCode.HasValue)
        {
            // Installer is still running; we did NOT kill it.
            // The next EnsureInstalled call observes.
            return new VcRedistInstallResult(VcRedistExit.StillRunning, -1,
                "vc_redist.x64.exe is still running. Retry to re-check; do not start a second install.",
                _detector.Detect());
        }

        return ClassifyExit(exitCode.Value, minimumRuntimeVersion);
    }

    private VcRedistInstallResult ObserveInFlightInstall(VcVersion minimumRuntimeVersion)
    {
        if (!_launcher.TryGetResult(out var exitCode, out var stillRunning))
        {
            if (stillRunning)
                return new VcRedistInstallResult(VcRedistExit.StillRunning, -1,
                    "vc_redist.x64.exe is still running. Retry to re-check.",
                    _detector.Detect());
            // Launcher reports no in-flight even though IsInFlight was true.
            // Treat as still-running to avoid a duplicate launch race.
            return new VcRedistInstallResult(VcRedistExit.StillRunning, -1,
                "Installer state is being observed. Retry to re-check.",
                _detector.Detect());
        }
        return ClassifyExit(exitCode, minimumRuntimeVersion);
    }

    private VcRedistInstallResult ClassifyExit(int exitCode, VcVersion minimumRuntimeVersion)
    {
        var post = _detector.Detect();
        if (exitCode == 0)
        {
            if (VcRuntimeRequirements.Classify(post, minimumRuntimeVersion) == VcSatisfaction.Satisfied)
                return new VcRedistInstallResult(VcRedistExit.Success, 0,
                    "Installed vc_redist.x64.exe successfully; runtime reports " + post.InstalledVersion + ".",
                    post);
            return new VcRedistInstallResult(VcRedistExit.PackageCorrupt, exitCode,
                "Installer exited 0 but the runtime is not present. Reinstall the host package.",
                post);
        }
        if (exitCode == 3010 || exitCode == 1641)
            return new VcRedistInstallResult(VcRedistExit.RestartRequired, exitCode,
                "Restart required to finish installing the VC++ runtime. Restart Windows, then reopen this manager.",
                post);
        return new VcRedistInstallResult(VcRedistExit.Other, exitCode,
            "vc_redist.x64.exe exited with code " + exitCode + ". " + DetailForExit(exitCode),
            post);
    }

    public static string DetailForExit(int code) => code switch
    {
        2 => "Invalid arguments were passed to vc_redist.x64.exe.",
        4 => "The redistributable package could not open. Reinstall the host package.",
        5 => "Access denied. Approve the elevated prompt or restart the manager as Administrator.",
        8 => "Another installer is running. Close it and retry.",
        13 => "The redistributable package did not match its manifest.",
        14 => "Insufficient disk space.",
        15 => "Could not write a temporary file.",
        16 => "Another process is using a file the redistributable needs.",
        _ => "Re-run guided setup or reinstall the host package.",
    };
}

public sealed class FakeVcRedistLauncher : IVcRedistLauncher
{
    // State machine:
    //   Idle -> Begin -> InFlight (no exit yet)
    //   InFlight -> TryGetResult (stillRunning) -> InFlight
    //   InFlight -> TryGetResult (completed) -> Idle, exit returned
    private enum F { Idle, InFlight, Observed }
    private F _state = F.Idle;
    private int _cachedExit;

    public int NextExitCode { get; set; } = 0;
    public bool NextStillRunning { get; set; }
    public int CallCount { get; private set; }
    public string? LastAbsolutePath { get; private set; }
    public bool IsInFlight => _state == F.InFlight;
    public int? Begin(VcRedistPackage package)
    {
        CallCount++;
        LastAbsolutePath = package.AbsolutePath;
        if (NextStillRunning)
        {
            _state = F.InFlight;
            _cachedExit = NextExitCode;
            return null;
        }
        _state = F.Observed;
        _cachedExit = NextExitCode;
        return NextExitCode;
    }
    public bool TryGetResult(out int exitCode, out bool stillRunning)
    {
        if (_state != F.InFlight)
        {
            exitCode = -1;
            stillRunning = false;
            return false;
        }
        if (NextStillRunning)
        {
            exitCode = -1;
            stillRunning = true;
            return false;
        }
        // Complete.
        exitCode = _cachedExit;
        stillRunning = false;
        _state = F.Observed;
        return true;
    }
}

public sealed class FakeVcRedistInspector : IVcRedistInspector
{
    public Dictionary<string, VcRedistPackage> Packages { get; } = new();
    public VcRedistPackage? Inspect(string absolutePath, VcVersion minimumFileVersion, string expectedPublisher)
        => Packages.TryGetValue(absolutePath, out var p) ? p : null;
}