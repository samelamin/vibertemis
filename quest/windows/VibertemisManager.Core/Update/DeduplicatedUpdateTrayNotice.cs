// DeduplicatedUpdateTrayNotice surfaces a single Windows tray
// balloon per (status bucket, version) tuple so a noisy update
// repository (e.g. the 1 s timer firing many times while a check
// is in flight) does not spam the user. The notice is fired
// exactly once per (bucket, version); repeating the same call
// is a no-op. The notice object is mutable so the manager can
// reset it on user dismissal or after the next state change.
using System;

namespace VibertemisManager.Core.Update;

public sealed class DeduplicatedUpdateTrayNotice
{
    public enum Bucket
    {
        Available,
        Downloaded,
        Offline,
    }

    public sealed class NoticeKey : IEquatable<NoticeKey>
    {
        public Bucket Bucket { get; }
        public string Version { get; }
        public NoticeKey(Bucket b, string v) { Bucket = b; Version = v; }
        public bool Equals(NoticeKey? other) =>
            other is not null && Bucket == other.Bucket && Version == other.Version;
        public override bool Equals(object? obj) => Equals(obj as NoticeKey);
        public override int GetHashCode() => HashCode.Combine((int)Bucket, Version);
    }

    private NoticeKey? _last;

    /// <summary>
    /// Record a notice. Returns true on the FIRST observation of
    /// the (bucket, version) tuple; returns false on repeats so
    /// the caller can skip the actual ShowBalloonTip call.
    /// </summary>
    public bool TryFire(Bucket bucket, string version)
    {
        var key = new NoticeKey(bucket, version);
        if (_last is not null && _last.Equals(key)) return false;
        _last = key;
        return true;
    }

    /// <summary>
    /// Reset the dedup state so the next observation of the same
    /// tuple re-fires. Use when the user dismisses the notice or
    /// after a state transition the user has acknowledged.
    /// </summary>
    public void Reset() => _last = null;
}