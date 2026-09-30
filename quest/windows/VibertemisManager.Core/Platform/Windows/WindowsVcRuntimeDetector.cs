// Windows VC++ runtime detection.
//
// Reads the documented Microsoft Visual Studio runtime registry
// key under HKLM in BOTH the 64-bit and 32-bit registry views.
// The bundled native payload is x64-only, but the runtime key is
// also registered under the WOW6432Node view on x86 Windows and
// can appear in either view on x64. We accept whichever view
// reports Installed=1 + Major/Minor/Bld/Rbld parseable.
//
// The detector is a thin composition over the Core
// VcRuntimeDetector: it builds two IVcRegistryKey adapters (one
// per view) and feeds them into the testable Core contract. The
// parsing / classification logic lives in Core so POSIX tests
// can exercise it without touching Windows.
//
// We refuse to assert readiness on a partial write (Installed=1
// without Major/Minor) - that surfaces as CorruptRegistry and
// triggers guided setup action instead of a green Ready light.
#if WINDOWS
using System;
using Microsoft.Win32;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsVcRuntimeDetector : IVcRuntimeDetector
{
    private const string Native64Path = @"SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64";
    private const string Wow6432NodePath = @"SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64";

    private readonly VcRuntimeDetector _inner;

    public WindowsVcRuntimeDetector()
    {
        _inner = new VcRuntimeDetector(new[]
        {
            new VcRegistryView("HKLM-Registry64", OpenView(RegistryView.Registry64, Native64Path)),
            new VcRegistryView("HKLM-WOW6432Node", OpenView(RegistryView.Registry32, Wow6432NodePath)),
        });
    }

    public VcRuntimeStatus Detect() => _inner.Detect();

    private static IVcRegistryKey OpenView(RegistryView view, string subKey)
    {
        try
        {
            var baseKey = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
            var key = baseKey.OpenSubKey(subKey, writable: false);
            // baseKey is kept alive for the lifetime of the
            // returned IVcRegistryKey. The adapter is owned by the
            // Core detector and outlives this method's stack
            // frame, so the handle here intentionally does not
            // use 'using'.
            return new WindowsRegistryKeyAdapter(baseKey, key);
        }
        catch (Exception ex) when (ex is System.Security.SecurityException || ex is UnauthorizedAccessException)
        {
            return new WindowsRegistryKeyAdapter(null, null);
        }
        catch
        {
            return new WindowsRegistryKeyAdapter(null, null);
        }
    }

    // Wraps a real Microsoft.Win32.RegistryKey so the Core detector
    // can read DWORD / SZ values through one contract. The
    // RegistryKey handles are captured at construction and disposed
    // by Dispose(). The detector does not need to dispose the
    // adapter (its lifetime is the manager process); but if the
    // adapter is ever fed to a test that owns it, Dispose is
    // idempotent.
    private sealed class WindowsRegistryKeyAdapter : IVcRegistryKey, IDisposable
    {
        private readonly RegistryKey? _base;
        private readonly RegistryKey? _key;
        private bool _disposed;
        public WindowsRegistryKeyAdapter(RegistryKey? baseKey, RegistryKey? key)
        {
            _base = baseKey;
            _key = key;
        }
        public string? TryGetValue(string name)
        {
            try
            {
                if (_disposed || _key is null) return null;
                return _key.GetValue(name) as string;
            }
            catch { return null; }
        }
        public int? TryGetDword(string name)
        {
            try
            {
                if (_disposed || _key is null) return null;
                var v = _key.GetValue(name);
                return v switch
                {
                    null => null,
                    int i => i,
                    uint u when u <= int.MaxValue => (int)u,
                    long l when l is >= int.MinValue and <= int.MaxValue => (int)l,
                    string s when int.TryParse(s, System.Globalization.NumberStyles.Integer,
                        System.Globalization.CultureInfo.InvariantCulture, out var parsed) => parsed,
                    _ => null,
                };
            }
            catch { return null; }
        }
        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            try { _key?.Dispose(); } catch { /* ignore */ }
            try { _base?.Dispose(); } catch { /* ignore */ }
        }
    }
}
#endif