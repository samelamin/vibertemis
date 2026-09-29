// Single-instance guard.
//
// Production (Windows): Microsoft.Win32.SafeHandles.SafeWaitHandle
// wraps a CreateMutexW(Local\, VibertemisVRHostManager, ...). The
// POSIX test build uses an advisory flock(2) on a temp file so the
// same semantic can be exercised on Linux.
using System;
using System.IO;
using System.Threading;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.SingleInstance;

public sealed record SingleInstanceHandle : IDisposable
{
    private readonly Action _release;
    private bool _released;

    public SingleInstanceHandle(Action release) => _release = release;

    public void Dispose()
    {
        if (_released) return;
        _released = true;
        _release();
    }
}

public interface ISingleInstanceGuardFactory
{
    ISingleInstanceGuard Create(string mutexName);
}

public sealed class FileBackedSingleInstanceGuard : ISingleInstanceGuard
{
    private readonly string _lockPath;
    public FileBackedSingleInstanceGuard(string lockPath) => _lockPath = lockPath;

    public bool TryAcquire(out IDisposable? handle)
    {
        handle = null;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_lockPath)!);
            var stream = new FileStream(_lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
            handle = stream;
            return true;
        }
        catch (IOException)
        {
            return false;
        }
    }
}

public sealed class FileBackedSingleInstanceGuardFactory : ISingleInstanceGuardFactory
{
    private readonly string _root;
    public FileBackedSingleInstanceGuardFactory(string root) => _root = root;
    public ISingleInstanceGuard Create(string mutexName)
    {
        var safeName = mutexName.Replace('\\', '_').Replace('/', '_');
        return new FileBackedSingleInstanceGuard(Path.Combine(_root, safeName + ".lock"));
    }
}

public sealed class CountdownEventSingleInstanceGuard : ISingleInstanceGuard
{
    private readonly CountdownEvent _ev;
    public CountdownEventSingleInstanceGuard(CountdownEvent ev) => _ev = ev;

    public bool TryAcquire(out IDisposable? handle)
    {
        if (_ev.IsSet) { handle = null; return false; }
        _ev.Signal();
        handle = new SingleInstanceHandle(() => _ev.Reset());
        return true;
    }
}

public sealed class AlwaysAcquiresSingleInstanceGuard : ISingleInstanceGuard
{
    public bool TryAcquire(out IDisposable? handle)
    {
        handle = new SingleInstanceHandle(() => { });
        return true;
    }
}