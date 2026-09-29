// Windows single-instance guard using a Local\ named mutex.
#if WINDOWS
using System;
using System.Threading;
using VibertemisManager.Core.Platform.Abstractions;
using VibertemisManager.Core.SingleInstance;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsSingleInstanceGuard : ISingleInstanceGuard
{
    private readonly string _name;
    private Mutex? _mutex;
    private bool _owned;

    public WindowsSingleInstanceGuard(string name) => _name = name;

    public bool TryAcquire(out IDisposable? handle)
    {
        handle = null;
        try
        {
            _mutex = new Mutex(initiallyOwned: true, _name, out var createdNew);
            _owned = createdNew;
            if (!createdNew)
            {
                _mutex.Dispose();
                _mutex = null;
                return false;
            }
            handle = new SingleInstanceHandle(() =>
            {
                try { _mutex?.ReleaseMutex(); } catch { /* ignore */ }
                _mutex?.Dispose();
                _mutex = null;
            });
            return true;
        }
        catch (Exception)
        {
            // On Windows, attempting to acquire an abandoned mutex
            // raises AbandonedMutexException; we treat that as
            // "still owned by us" so the manager does not launch
            // a duplicate instance.
            if (_mutex != null)
            {
                handle = new SingleInstanceHandle(() =>
                {
                    try { _mutex?.ReleaseMutex(); } catch { /* ignore */ }
                    _mutex?.Dispose();
                    _mutex = null;
                });
                return true;
            }
            return false;
        }
    }
}

public sealed class WindowsSingleInstanceGuardFactory : ISingleInstanceGuardFactory
{
    public ISingleInstanceGuard Create(string mutexName) => new WindowsSingleInstanceGuard(mutexName);
}
#endif